package io.cloudstate.proxy

import com.oracle.svm.core.annotate.AutomaticFeature
import org.graalvm.nativeimage.hosted.Feature.QueryReachabilityAccess
import org.graalvm.nativeimage.hosted.{Feature, RuntimeReflection}

import scala.collection.JavaConverters._

@AutomaticFeature
final class AkkaActorRegisterFeature extends Feature {
  final val akkaActorClass = classOf[akka.actor.Actor]
  override final def duringAnalysis(access: Feature.DuringAnalysisAccess) =
    if (access.isReachable(akkaActorClass)) {
      for {
        subtype <- access.reachableSubtypes(akkaActorClass).iterator.asScala
        if subtype != null && subtype.getInterfaces.exists(_ == akkaActorClass)
        context <- getDeclaredField(subtype, "context")
        self <- getDeclaredField(subtype, "self")
      } {
        RuntimeReflection.register(subtype)
        RuntimeReflection.register( /* finalIsWritable = */ true, context, self)
        //RuntimeReflection.register(subtype.getDeclaredFields: _*)
        RuntimeReflection.register(subtype.getDeclaredConstructors: _*)
      }
    }

  private[this] final def getDeclaredField(cls: Class[_], name: String) =
    try Option(cls.getDeclaredField(name))
    catch { case _: NoSuchFieldException => None }
}
