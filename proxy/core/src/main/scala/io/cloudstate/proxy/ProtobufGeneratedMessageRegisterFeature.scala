package io.cloudstate.proxy

import com.oracle.svm.core.annotate.AutomaticFeature
import org.graalvm.nativeimage.hosted.Feature.QueryReachabilityAccess
import org.graalvm.nativeimage.hosted.{Feature, RuntimeReflection}

import scala.collection.JavaConverters._

@AutomaticFeature
final class ProtobufGeneratedMessageRegisterFeature extends Feature {
  final val messageClasses = Vector(
    classOf[akka.protobuf.GeneratedMessage],
    classOf[akka.protobuf.GeneratedMessage.Builder[_]],
    classOf[akka.protobuf.ProtocolMessageEnum],
    classOf[com.google.protobuf.GeneratedMessageV3],
    classOf[com.google.protobuf.GeneratedMessageV3.Builder[_]],
    classOf[com.google.protobuf.ProtocolMessageEnum],
    classOf[scalapb.GeneratedMessage]
  )
  override final def duringAnalysis(access: Feature.DuringAnalysisAccess) =
    for {
      cls <- messageClasses.iterator
      if access.isReachable(cls)
      subtype <- access.reachableSubtypes(cls).iterator.asScala
      if subtype != null
    } {
      RuntimeReflection.register(subtype)
      RuntimeReflection.register(subtype.getMethods: _*)
    }
}
