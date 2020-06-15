package io.cloudstate.graaltools

import com.oracle.svm.core.annotate.AutomaticFeature
import org.graalvm.nativeimage.hosted.{Feature, RuntimeReflection}

@AutomaticFeature
final class AkkaActorRegisterFeature extends Feature {
  private[this] final val ignoreClass = Set(
    // FIXME the following are configured in akka-graal-config, remove this when migrating from it
    "akka.io.TcpConnection",
    "akka.io.TcpListener",
    "akka.stream.impl.fusing.ActorGraphInterpreter"
  )

  override final def beforeAnalysis(access: Feature.BeforeAnalysisAccess): Unit = {
    val akkaActorClass =
      access.findClassByName(classOf[akka.actor.Actor].getName) // We do this to get compile-time safety of the classes, and allow graalvm to resolve their names

    access.registerSubtypeReachabilityHandler(
      (_, subtype) =>
        if (subtype != null && !subtype.isInterface && !ignoreClass(subtype.getName)) { // TODO investigate whether we should cache the one's we've already added or not?
          println("Automatically registering actor class for reflection purposes: " + subtype.getName)
          RuntimeReflection.register(subtype)
          RuntimeReflection.register(subtype.getDeclaredConstructors: _*)
          RuntimeReflection.register(subtype.getDeclaredFields: _*)
        },
      akkaActorClass
    )
  }
}
