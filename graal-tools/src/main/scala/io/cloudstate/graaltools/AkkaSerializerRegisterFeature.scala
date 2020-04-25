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
  private[this] final val registerTheseSerializers = {
    val c = ConcurrentHashMap.newKeySet[String]
    c.add("akka.serialization.NullSerializer$")
    c
  }

  override final def beforeAnalysis(access: Feature.BeforeAnalysisAccess): Unit = {
    val config = com.typesafe.config.ConfigFactory.load()
    config
      .getConfig("akka.actor.serializers")
      .root
      .unwrapped
      .values
      .iterator
      .asScala
      .map(_.toString)
      .foreach(registerTheseSerializers.add)
    com.typesafe.config.ConfigFactory.invalidateCaches()
  }

  override final def duringAnalysis(access: Feature.DuringAnalysisAccess): Unit =
    registerTheseSerializers.forEach { className =>
      registerSerializerClassByName(access, className)
    }

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
      registerTheseSerializers.remove(cls.getName)
    }

  final def registerSubtypesOf(access: Feature.DuringAnalysisAccess, className: String): Unit = {
    val akkaSerializerClass = access.findClassByName(className)
    if (akkaSerializerClass != null && access.isReachable(akkaSerializerClass)) {
      access.reachableSubtypes(akkaSerializerClass).iterator.asScala.filter(_ != null).foreach { subtype =>
        registerSerializerClass(access, subtype)
      }
    }
  }

  private[this] final def getDeclaredConstructor(cls: Class[_], argumentTypes: Class[_]*) =
    try Option(cls.getDeclaredConstructor(argumentTypes: _*))
    catch { case _: NoSuchMethodException => None }
}
