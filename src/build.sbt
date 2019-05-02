organization in ThisBuild := "com.lightbend.statefulserverless"
name := "stateful-serverless"
scalaVersion in ThisBuild := "2.12.8"

version in ThisBuild ~= (_.replace('+', '-'))
dynver in ThisBuild ~= (_.replace('+', '-'))

// Needed for our fork of skuber
resolvers in ThisBuild += Resolver.bintrayRepo("jroper", "maven")

val AkkaVersion = "2.5.22"
val AkkaHttpVersion = "10.1.7"
val AkkaManagementVersion = "1.0.0"
val AkkaPersistenceCassandraVersion = "0.93"

lazy val root = (project in file("."))
  .aggregate(`backend-core`, `backend-cassandra`, `akka-client`, operator)

def dockerSettings: Seq[Setting[_]] = Seq(
  dockerBaseImage := "adoptopenjdk/openjdk8",
  dockerUpdateLatest := true,
  dockerRepository := sys.props.get("docker.registry").orElse(Some("lightbend-docker-registry.bintray.io")),
  dockerUsername := sys.props.get("docker.username").orElse(Some("octo"))
)

lazy val `backend-core` = (project in file("backend/core"))
  .enablePlugins(JavaAppPackaging, DockerPlugin, AkkaGrpcPlugin, JavaAgent)
  .settings(
    name := "stateful-serverless-backend-core",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-persistence" % AkkaVersion,
      "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
      "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
      "com.typesafe.akka" %% "akka-cluster-sharding" % AkkaVersion,
      "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % AkkaManagementVersion,
      "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % AkkaManagementVersion,
      "com.google.protobuf" % "protobuf-java" % "3.5.1" % "protobuf" // TODO remove this, see: https://github.com/akka/akka-grpc/issues/565
    ),

    // Akka gRPC adds all protobuf files from the classpath to this, which we don't want because it includes
    // all the Google protobuf files which are already compiled and on the classpath by ScalaPB. So we set it
    // back to just our source directory.
    PB.protoSources in Compile := Seq((sourceDirectory in Compile).value / "proto"),

    javaAgents += "org.mortbay.jetty.alpn" % "jetty-alpn-agent" % "2.0.9" % "runtime;test",

    dockerSettings,

    fork in run := true,

    // In memory journal by default
    javaOptions in run ++= Seq("-Dconfig.resource=in-memory.conf", "-Dstateful-serverless.dev-mode-enabled=true")
  )

lazy val `backend-cassandra` = (project in file("backend/cassandra"))
  .enablePlugins(JavaAppPackaging, DockerPlugin, JavaAgent)
  .dependsOn(`backend-core`)
  .settings(
    name := "stateful-serverless-backend-cassandra",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-persistence-cassandra" % AkkaPersistenceCassandraVersion,
      "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % AkkaPersistenceCassandraVersion % Test
    ),
    dockerSettings,

    fork in run := true,
    mainClass in Compile := Some("com.lightbend.statefulserverless.StatefulServerlessMain")
  )

val compileK8sDescriptors = taskKey[File]("Compile the K8s descriptors into one")

lazy val operator = (project in file("operator"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name := "stateful-serverless-operator",
    // This is a publishLocal build of this PR https://github.com/doriordan/skuber/pull/268
    libraryDependencies += "io.skuber" %% "skuber" % "2.2.0-jroper-1",

    dockerSettings,
    dockerExposedPorts := Nil,
    compileK8sDescriptors := doCompileK8sDescriptors(
      baseDirectory.value / "deploy",
      baseDirectory.value / "stateful-serverless.yaml",
      dockerRepository.value.get,
      dockerUsername.value.get,
      version.value
    )
  )

val copyShoppingCartProtos = taskKey[File]("Copy the shopping cart protobufs")

lazy val `akka-client` = (project in file("samples/akka-js-shopping-cart-client"))
  .enablePlugins(AkkaGrpcPlugin)
  .settings(
    name := "akka-js-shopping-cart-client",

    fork in run := true,

    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-persistence" % AkkaVersion,
      "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
      "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
      "com.google.protobuf" % "protobuf-java" % "3.5.1" % "protobuf" // TODO remove this, see: https://github.com/akka/akka-grpc/issues/565
    ),

    target in copyShoppingCartProtos := target.value / "js-shopping-cart-protos",
    copyShoppingCartProtos := {
      val toDir = (target in copyShoppingCartProtos).value
      val fromDir = baseDirectory.value.getParentFile / "js-shopping-cart" / "proto"
      val toSync = (fromDir * "*.proto").get.map(file => file -> toDir / file.getName)
      Sync.sync(streams.value.cacheStoreFactory.make(copyShoppingCartProtos.toString()))(toSync)
      toDir
    },

    PB.protoSources in Compile += (target in copyShoppingCartProtos).value,
    (PB.unpackDependencies in Compile) := {
      copyShoppingCartProtos.value
      (PB.unpackDependencies in Compile).value
    }

  )

def doCompileK8sDescriptors(dir: File, target: File, registry: String, username: String, version: String): File = {
  val files = ((dir / "crds") * "*.yaml").get ++
    (dir * "*.yaml").get.sortBy(_.getName)

  val fullDescriptor = files.map(IO.read(_)).mkString("\n---\n")

  val substitutedDescriptor = List("stateful-serverless-operator", "stateful-serverless-backend-cassandra")
    .foldLeft(fullDescriptor) { (descriptor, image) =>
      descriptor.replace(s"lightbend-docker-registry.bintray.io/octo/$image:latest",
        s"$registry/$username/$image:$version")
    }

  IO.write(target, substitutedDescriptor)
  target
}
