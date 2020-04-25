package io.cloudstate.graaltools

import com.oracle.svm.core.annotate.AutomaticFeature
import org.graalvm.nativeimage.hosted.Feature.QueryReachabilityAccess
import org.graalvm.nativeimage.hosted.{Feature, RuntimeReflection}

import scala.collection.JavaConverters._
import java.lang.reflect.Modifier.isAbstract
import java.util.concurrent.ConcurrentHashMap

@AutomaticFeature
final class AkkaSerializerRegisterFeature extends Feature {
  private[this] final val cache = ConcurrentHashMap.newKeySet[String]

  // FIXME THIS IS CURRENTLY DISABLED SINCE IT WILL NOT MERGE ALL REFERENCE CONFS FOR SOME REASON
  override final def isInConfiguration(access: Feature.IsInConfigurationAccess): Boolean = false
  override final def duringAnalysis(access: Feature.DuringAnalysisAccess): Unit = {
    val serializerClass = access.findClassByName(classOf[akka.serialization.Serializer].getName)
    if (access.isReachable(serializerClass)) {
      val config = com.typesafe.config.ConfigFactory.load(serializerClass.getClassLoader)
      config
        .getConfig("akka.actor.serializers")
        .root
        .unwrapped
        .values
        .iterator
        .asScala
        .map(_.toString)
        .filter(className => !cache.contains(className))
        .foreach(className => registerSerializerClassByName(access, className))
      com.typesafe.config.ConfigFactory.invalidateCaches()
    }
  }

  override final def afterAnalysis(access: Feature.AfterAnalysisAccess): Unit =
    com.typesafe.config.ConfigFactory.invalidateCaches()

  final def registerSerializerClassByName(access: Feature.DuringAnalysisAccess, className: String): Unit =
    registerSerializerClass(access, access.findClassByName(className))

  final def registerSerializerClass(access: Feature.DuringAnalysisAccess, cls: Class[_]): Unit =
    for {
      cls <- Option(cls).filter(access.isReachable)
      if cls != null && !cls.isInterface && !isAbstract(cls.getModifiers) && cache.add(cls.getName)
      ctor <- getDeclaredConstructor(cls, classOf[akka.actor.ExtendedActorSystem]) orElse getDeclaredConstructor(
        cls
      )
      _ = println("Automatically registering serializer class for reflection purposes: " + cls.getName)
    } {
      RuntimeReflection.register(cls)
      RuntimeReflection.register(ctor)
    }

  final def registerSubtypesOf(access: Feature.DuringAnalysisAccess, className: String): Unit = {
    val akkaSerializerClass = access.findClassByName(className)
    if (akkaSerializerClass != null && access.isReachable(akkaSerializerClass)) {
      access.reachableSubtypes(akkaSerializerClass).iterator.asScala.filter(_ != null).foreach { subtype =>
        registerSerializerClass(access, subtype)
      }
    }
  }

  private[this] final def getDeclaredConstructor(cls: Class[_], parameterTypes: Class[_]*) =
    try Option(cls.getDeclaredConstructor(parameterTypes: _*))
    catch { case _: NoSuchMethodException => None }
}
