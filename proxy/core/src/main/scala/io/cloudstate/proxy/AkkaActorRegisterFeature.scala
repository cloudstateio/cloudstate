package io.cloudstate.proxy

import com.oracle.svm.core.annotate.AutomaticFeature
import org.graalvm.nativeimage.hosted.Feature.QueryReachabilityAccess
import org.graalvm.nativeimage.hosted.{Feature, RuntimeReflection}

import scala.collection.JavaConverters._

@AutomaticFeature
final class AkkaActorRegisterFeature extends Feature {

  override def duringAnalysis(access: Feature.DuringAnalysisAccess) =
    reachableSubtypes(access, access.findClassByName("akka.akka.Actor")).foreach { cls =>
      for {
        context <- getDeclaredField(cls, "context")
        self <- getDeclaredField(cls, "self")
      } {
        RuntimeReflection.register(cls)
        RuntimeReflection.register( /* finalIsWritable = */ true, context, self)
        RuntimeReflection.register(cls.getDeclaredFields: _*)
        RuntimeReflection.register(cls.getDeclaredConstructors: _*)
      }
    }

  private def reachableSubtypes(access: QueryReachabilityAccess, cls: Class[_]) =
    try access.reachableSubtypes(cls).asScala
    catch { case _: NullPointerException => Nil }

  private def getDeclaredField(cls: Class[_], name: String) =
    try Some(cls.getDeclaredField(name))
    catch { case _: NoSuchFieldException => None }

}
