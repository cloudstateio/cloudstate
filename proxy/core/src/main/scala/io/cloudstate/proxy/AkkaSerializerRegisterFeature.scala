package io.cloudstate.proxy

import com.oracle.svm.core.annotate.AutomaticFeature
import org.graalvm.nativeimage.hosted.Feature.QueryReachabilityAccess
import org.graalvm.nativeimage.hosted.{Feature, RuntimeReflection}

import scala.collection.JavaConverters._

@AutomaticFeature
final class AkkaSerializerRegisterFeature extends Feature {

  override def duringAnalysis(access: Feature.DuringAnalysisAccess) =
    reachableSubtypes(access, access.findClassByName("akka.serialization.Serializer")).foreach { cls =>
      getDeclaredConstructor(cls, classOf[akka.actor.ExtendedActorSystem]).foreach { ctor =>
        RuntimeReflection.register(cls)
        RuntimeReflection.register(ctor)
      }
    }

  private def reachableSubtypes(access: QueryReachabilityAccess, cls: Class[_]) =
    try access.reachableSubtypes(cls).asScala
    catch { case _: NullPointerException => Nil }

  private def getDeclaredConstructor(cls: Class[_], parameterTypes: Class[_]*) =
    try Some(cls.getDeclaredConstructor(parameterTypes: _*))
    catch { case _: NoSuchMethodException => None }

}
