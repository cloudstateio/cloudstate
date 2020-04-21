package io.cloudstate.proxy

import com.oracle.svm.core.annotate.AutomaticFeature
import org.graalvm.nativeimage.hosted.Feature.QueryReachabilityAccess
import org.graalvm.nativeimage.hosted.{Feature, RuntimeReflection}

import scala.collection.JavaConverters._

@AutomaticFeature
final class AkkaSerializerRegisterFeature extends Feature {
  final val akkaSerializerClass = classOf[akka.serialization.Serializer]
  override final def duringAnalysis(access: Feature.DuringAnalysisAccess) =
    if (access.isReachable(akkaSerializerClass)) {
      for {
        subtype <- access.reachableSubtypes(akkaSerializerClass).iterator.asScala
        if subtype != null
        ctor <- getDeclaredConstructor(subtype)
      } {
        RuntimeReflection.register(subtype)
        RuntimeReflection.register(ctor)
      }
    }
  private[this] final def getDeclaredConstructor(cls: Class[_]) =
    try Option(cls.getDeclaredConstructor(classOf[akka.actor.ExtendedActorSystem]))
    catch { case _: NoSuchMethodException => None }
}
