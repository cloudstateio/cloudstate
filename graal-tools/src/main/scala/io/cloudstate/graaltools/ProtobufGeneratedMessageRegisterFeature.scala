package io.cloudstate.graaltools

import com.oracle.svm.core.annotate.AutomaticFeature
import org.graalvm.nativeimage.hosted.Feature.QueryReachabilityAccess
import org.graalvm.nativeimage.hosted.{Feature, RuntimeReflection}

import scala.collection.JavaConverters._
import java.util.concurrent.ConcurrentHashMap

@AutomaticFeature
final class ProtobufGeneratedMessageRegisterFeature extends Feature {
  private[this] final val cache = ConcurrentHashMap.newKeySet[String]
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
      if subtype != null && cache.add(subtype.getName)
    } {
      RuntimeReflection.register(subtype)
      subtype.getPackage.getName match {
        case "akka.cluster.protobuf.msg" | "com.google.protobuf" | "akka.cluster.ddata.protobuf"
            if !subtype.isInterface =>
          RuntimeReflection.register(subtype.getMethods: _*)
          println(
            "Automatically registering protobuf message class with all methods for reflection purposes: " + subtype.getName
          )
        case _ =>
          println("Automatically registering protobuf message class for reflection purposes: " + subtype.getName)
      }
    }
}
