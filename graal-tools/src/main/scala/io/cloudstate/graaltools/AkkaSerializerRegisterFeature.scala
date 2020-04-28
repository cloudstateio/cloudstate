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
    for (serializerClass <- access.lookupClass(classOf[akka.serialization.Serializer].getName)) {
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
    for (cls <- access.lookupClass(className))
      registerSerializerClass(cls)

  final def registerSerializerClass(cls: Class[_]): Unit =
    for {
      cls <- Option(cls)
      if !cls.isInterface && !isAbstract(cls.getModifiers) && cache.add(cls.getName)
      ctor <- reflect(cls.getDeclaredConstructor(classOf[akka.actor.ExtendedActorSystem]))
        .orElse(reflect(cls.getDeclaredConstructor()))
    } {
      println(s"Automatically registering serializer class for reflection purposes: $cls")
      RuntimeReflection.register(cls)
      RuntimeReflection.register(ctor)
    }

  final def registerSubtypesOf(access: Feature.DuringAnalysisAccess, className: String): Unit = {
    for {
      akkaSerializerClass <- access.lookupClass(className)
      subtype <- access.lookupSubtypes(akkaSerializerClass)
    } registerSerializerClass(subtype)
  }
}
