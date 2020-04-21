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
  ).map(_.getName) // We do this to get compile-time safety of the classes, and allow graalvm to resolve their names
  override final def duringAnalysis(access: Feature.DuringAnalysisAccess): Unit =
    for {
      className <- messageClasses.iterator
      cls = access.findClassByName(className)
      if cls != null && access.isReachable(cls)
      subtype <- access.reachableSubtypes(cls).iterator.asScala
      if subtype != null
    } {
      RuntimeReflection.register(subtype)
      RuntimeReflection.register(subtype.getMethods: _*)
    }
}
