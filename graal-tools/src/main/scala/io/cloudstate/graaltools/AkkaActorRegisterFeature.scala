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

    def register(subtype: Class[_]) =
      for {
        subtype <- Option(subtype)
        if !subtype.isInterface && !ignoreClass(subtype.getName)
        _ = println("Automatically registering actor class for reflection purposes: " + subtype.getName)
        _ = RuntimeReflection.register(subtype)
        _ = RuntimeReflection.register(subtype.getDeclaredConstructors: _*)
        _ = RuntimeReflection.register(subtype.getDeclaredFields: _*)
        if subtype.getInterfaces.exists(_ == akkaActorClass)
        (context, self) <- getDeclaredField(subtype, "context") zip getDeclaredField(subtype, "self")
      } {
        RuntimeReflection.register( /* finalIsWritable = */ true, context, self)
      }

    access.registerSubtypeReachabilityHandler((_, subtype) => register(subtype), akkaActorClass)
  }

  private[this] final def getDeclaredField(cls: Class[_], name: String) =
    try Option(cls.getDeclaredField(name))
    catch { case _: NoSuchFieldException => None }
}
