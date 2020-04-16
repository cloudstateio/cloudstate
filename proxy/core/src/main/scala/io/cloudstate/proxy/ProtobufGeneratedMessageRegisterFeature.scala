package io.cloudstate.proxy

import com.oracle.svm.core.annotate.AutomaticFeature
import org.graalvm.nativeimage.hosted.Feature.QueryReachabilityAccess
import org.graalvm.nativeimage.hosted.{Feature, RuntimeReflection}

import scala.collection.JavaConverters._

@AutomaticFeature
final class ProtobufGeneratedMessageRegisterFeature extends Feature {

  override def duringAnalysis(access: Feature.DuringAnalysisAccess) =
    List(
      "akka.protobuf.GeneratedMessage",
      "akka.protobuf.GeneratedMessage$Builder",
      "akka.protobuf.ProtocolMessageEnum",
      "com.google.protobuf.GeneratedMessageV3",
      "com.google.protobuf.GeneratedMessageV3$Builder",
      "com.google.protobuf.ProtocolMessageEnum",
      "scalapb.GeneratedMessage"
    ).foreach { className =>
      val cls = access.findClassByName(className)
      if (cls != null) reachableSubtypes(access, cls).foreach { cls =>
        RuntimeReflection.register(cls)
        RuntimeReflection.register(cls.getMethods: _*)
      }
    }

  private def reachableSubtypes(access: QueryReachabilityAccess, cls: Class[_]) =
    try access.reachableSubtypes(cls).asScala
    catch { case _: NullPointerException => Nil }

}
