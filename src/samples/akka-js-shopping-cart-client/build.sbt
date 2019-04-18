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

val copyShoppingCartProtos = taskKey[File]("Copy the shopping cart protobufs")
target in copyShoppingCartProtos := target.value / "js-shopping-cart-protos"
copyShoppingCartProtos := {
  val toDir = (target in copyShoppingCartProtos).value
  val fromDir = baseDirectory.value.getParentFile / "js-shopping-cart" / "proto"
  val toSync = (fromDir * "*.proto").get.map(file => file -> toDir / file.getName)
  Sync.sync(streams.value.cacheStoreFactory.make(copyShoppingCartProtos.toString()))(toSync)
  toDir
}

PB.protoSources in Compile += (target in copyShoppingCartProtos).value
(PB.unpackDependencies in Compile) := {
  copyShoppingCartProtos.value
  (PB.unpackDependencies in Compile).value
}
