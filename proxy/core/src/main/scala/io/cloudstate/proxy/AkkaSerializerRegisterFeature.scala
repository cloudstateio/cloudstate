package io.cloudstate.proxy

import com.oracle.svm.core.annotate.AutomaticFeature
import org.graalvm.nativeimage.hosted.Feature.QueryReachabilityAccess
import org.graalvm.nativeimage.hosted.{Feature, RuntimeReflection}

import scala.collection.JavaConverters._

@AutomaticFeature
final class AkkaSerializerRegisterFeature extends Feature {
  val akkaSerializerClass = classOf[akka.serialization.Serializer]
  override def duringAnalysis(access: Feature.DuringAnalysisAccess) =
    if (access.isReachable(akkaSerializerClass)) {
      access.reachableSubtypes(akkaSerializerClass).iterator.asScala.filter(_ != null).foreach { subtype =>
        try {
          val ctor = subtype.getDeclaredConstructor(classOf[akka.actor.ExtendedActorSystem])
          RuntimeReflection.register(subtype)
          RuntimeReflection.register(ctor)
        } catch {
          case nsme: NoSuchMethodException =>
          // Ignore?
        }
      }
    }
}
