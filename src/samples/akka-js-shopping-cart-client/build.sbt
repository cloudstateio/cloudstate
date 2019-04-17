organization := "com.lightbend.statefulserverless.samples"
name := "akka-js-shopping-cart-client"
scalaVersion := "2.12.8"

enablePlugins(AkkaGrpcPlugin)

// Used for when running on the command line
fork in run := true

PB.protoSources in Compile := Seq(baseDirectory.value.getParentFile / "js-shopping-cart" / "proto")