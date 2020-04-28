package io.cloudstate.graaltools

import com.oracle.svm.core.annotate.AutomaticFeature
import org.graalvm.nativeimage.hosted.Feature.QueryReachabilityAccess
import org.graalvm.nativeimage.hosted.{Feature, RuntimeReflection}

import scala.collection.JavaConverters._
import java.util.concurrent.ConcurrentHashMap

@AutomaticFeature
final class AkkaActorRegisterFeature extends Feature {
  private[this] final val cache = {
    val c = ConcurrentHashMap.newKeySet[String]
    // FIXME the following are configured in akka-graal-config, remove this when migrating from it
    c.add("akka.io.TcpConnection")
    c.add("akka.io.TcpListener")
    c.add("akka.stream.impl.fusing.ActorGraphInterpreter")
    c
  }

  override final def duringAnalysis(access: Feature.DuringAnalysisAccess): Unit = {
    for {
      akkaActorClass <- access.lookupClass(classOf[akka.actor.Actor].getName) // We do this to get compile-time safety of the classes, and allow graalvm to resolve their names
      subtype <- access.lookupSubtypes(akkaActorClass)
      if !subtype.isInterface && cache.add(subtype.getName)
      _ = println("Automatically registering actor class for reflection purposes: " + subtype.getName)
      _ = RuntimeReflection.register(subtype)
      _ = RuntimeReflection.register(subtype.getDeclaredConstructors: _*)
      _ = RuntimeReflection.register(subtype.getDeclaredFields: _*)
      if subtype.getInterfaces.exists(_ == akkaActorClass)
      context <- reflect(subtype.getDeclaredField("context"))
      self <- reflect(subtype.getDeclaredField("self"))
    } {
      RuntimeReflection.register( /* finalIsWritable = */ true, context, self)
    }
  }
}
