package io.cloudstate.proxy

import com.oracle.svm.core.annotate.AutomaticFeature
import org.graalvm.nativeimage.hosted.Feature.QueryReachabilityAccess
import org.graalvm.nativeimage.hosted.{Feature, RuntimeReflection}

import scala.collection.JavaConverters._

@AutomaticFeature
final class AkkaSerializerRegisterFeature extends Feature {
  override final def duringAnalysis(access: Feature.DuringAnalysisAccess): Unit = {
    val akkaSerializerClass =
      access.findClassByName(classOf[akka.serialization.Serializer].getName) // We do this to get compile-time safety of the classes, and allow graalvm to resolve their names
    if (akkaSerializerClass != null && access.isReachable(akkaSerializerClass)) {
      for {
        subtype <- access.reachableSubtypes(akkaSerializerClass).iterator.asScala
        if subtype != null
        ctor <- getDeclaredConstructor(subtype)
      } {
        RuntimeReflection.register(subtype)
        RuntimeReflection.register(ctor)
      }
    }
  }

  private[this] final def getDeclaredConstructor(cls: Class[_]) =
    try Option(cls.getDeclaredConstructor(classOf[akka.actor.ExtendedActorSystem]))
    catch { case _: NoSuchMethodException => None }
}
