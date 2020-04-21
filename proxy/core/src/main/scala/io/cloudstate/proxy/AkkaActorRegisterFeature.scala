package io.cloudstate.proxy

import com.oracle.svm.core.annotate.AutomaticFeature
import org.graalvm.nativeimage.hosted.Feature.QueryReachabilityAccess
import org.graalvm.nativeimage.hosted.{Feature, RuntimeReflection}

import scala.collection.JavaConverters._

@AutomaticFeature
final class AkkaActorRegisterFeature extends Feature {
  val akkaActorClass = classOf[akka.actor.Actor]
  override def duringAnalysis(access: Feature.DuringAnalysisAccess) =
    if (access.isReachable(akkaActorClass)) {
      access.reachableSubtypes(akkaActorClass).iterator.asScala.filter(_ != null).foreach { subtype =>
        if (subtype.getInterfaces.exists(_ == akkaActorClass)) {
          for {
            context <- getDeclaredField(subtype, "context")
            self <- getDeclaredField(subtype, "self")
          } {
            RuntimeReflection.register(subtype)
            RuntimeReflection.register( /* finalIsWritable = */ true, context, self)
            //RuntimeReflection.register(subtype.getDeclaredFields: _*)
            RuntimeReflection.register(subtype.getDeclaredConstructors: _*)
          }
        }
      }
    }

  private def getDeclaredField(cls: Class[_], name: String) =
    try Option(cls.getDeclaredField(name))
    catch { case _: NoSuchFieldException => None }
}
