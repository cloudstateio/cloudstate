organization := "com.lightbend.statefulserverless.samples"
name := "akka-js-shopping-cart-client"
scalaVersion := "2.12.8"

enablePlugins(AkkaGrpcPlugin)

// Used for when running on the command line
fork in run := true

val AkkaVersion = "2.5.22"
val AkkaHttpVersion = "10.1.7"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-persistence" % AkkaVersion,
  "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
  "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
  "com.google.protobuf" % "protobuf-java" % "3.5.1" % "protobuf" // TODO remove this, see: https://github.com/akka/akka-grpc/issues/565
)
