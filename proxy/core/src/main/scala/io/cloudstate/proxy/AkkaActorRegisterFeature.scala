package io.cloudstate.proxy

import com.oracle.svm.core.annotate.AutomaticFeature
import org.graalvm.nativeimage.hosted.Feature.QueryReachabilityAccess
import org.graalvm.nativeimage.hosted.{Feature, RuntimeReflection}

import scala.collection.JavaConverters._

@AutomaticFeature
final class AkkaActorRegisterFeature extends Feature {
  override final def duringAnalysis(access: Feature.DuringAnalysisAccess): Unit = {
    val akkaActorClass =
      access.findClassByName(classOf[akka.actor.Actor].getName) // We do this to get compile-time safety of the classes, and allow graalvm to resolve their names
    if (akkaActorClass != null && access.isReachable(akkaActorClass)) {
      for {
        subtype <- access.reachableSubtypes(akkaActorClass).iterator.asScala
        if subtype != null
        _ = RuntimeReflection.register(subtype)
        _ = RuntimeReflection.register(subtype.getDeclaredConstructors: _*)
        //_ = RuntimeReflection.register(subtype.getDeclaredFields: _*)
        if subtype.getInterfaces.exists(_ == akkaActorClass)
        context <- getDeclaredField(subtype, "context")
        self <- getDeclaredField(subtype, "self")
      } {
        RuntimeReflection.register( /* finalIsWritable = */ true, context, self)
      }
    }
  }

  private[this] final def getDeclaredField(cls: Class[_], name: String) =
    try Option(cls.getDeclaredField(name))
    catch { case _: NoSuchFieldException => None }
}
