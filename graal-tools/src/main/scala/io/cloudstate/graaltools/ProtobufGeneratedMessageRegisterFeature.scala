package io.cloudstate.graaltools

import com.oracle.svm.core.annotate.AutomaticFeature
import org.graalvm.nativeimage.hosted.{Feature, RuntimeReflection}

@AutomaticFeature
final class ProtobufGeneratedMessageRegisterFeature extends Feature {
  final val messageClasses = Vector(
    classOf[com.google.protobuf.DescriptorProtos],
    classOf[com.google.protobuf.ListValue],
    classOf[com.google.protobuf.NullValue],
    classOf[com.google.protobuf.Struct],
    classOf[com.google.protobuf.Value]
  ).map(_.getName) // We do this to get compile-time safety of the classes, and allow graalvm to resolve their names
  override final def beforeAnalysis(access: Feature.BeforeAnalysisAccess): Unit =
    for {
      className <- messageClasses.iterator
      cls = access.findClassByName(className)
      if cls != null
    } {
      registerDeep(cls)
    }

  private def registerDeep(cls: Class[_]): Unit = {
    register(cls)
    for (innerCls <- cls.getDeclaredClasses)
      registerDeep(innerCls)
  }

  private def register(subtype: Class[_]) = {
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
