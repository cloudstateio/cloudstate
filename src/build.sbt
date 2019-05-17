organization in ThisBuild := "com.lightbend.statefulserverless"
name := "stateful-serverless"
scalaVersion in ThisBuild := "2.12.8"

version in ThisBuild ~= (_.replace('+', '-'))
dynver in ThisBuild ~= (_.replace('+', '-'))

// Needed for our fork of skuber
resolvers in ThisBuild += Resolver.bintrayRepo("jroper", "maven")

organizationName in ThisBuild := "Lightbend Inc."
startYear in ThisBuild := Some(2019)
licenses in ThisBuild += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt"))

val AkkaVersion = "2.5.22"
val AkkaHttpVersion = "10.1.7"
val AkkaManagementVersion = "1.0.0"
val AkkaPersistenceCassandraVersion = "0.93"

def common: Seq[Setting[_]] = Seq(
  headerMappings := headerMappings.value ++ Seq(
    de.heikoseeberger.sbtheader.FileType("proto") -> HeaderCommentStyle.cppStyleLineComment,
    de.heikoseeberger.sbtheader.FileType("js") -> HeaderCommentStyle.cStyleBlockComment
  )
)

// Include sources from the npm projects
headerSources in Compile ++= {
  val nodeSupport = baseDirectory.value / "node-support"
  val jsShoppingCart = baseDirectory.value / "samples" / "js-shopping-cart"

  Seq(
    nodeSupport / "src" ** "*.js",
    nodeSupport * "*.js",
    jsShoppingCart * "*.js",
    jsShoppingCart / "test" ** "*.js"
  ).flatMap(_.get)
}

lazy val root = (project in file("."))
  .aggregate(`backend-core`, `backend-cassandra`, `akka-client`, operator, `tck`)
  .settings(common)

def dockerSettings: Seq[Setting[_]] = Seq(
  dockerBaseImage := "adoptopenjdk/openjdk8",
  dockerUpdateLatest := true,
  dockerRepository := sys.props.get("docker.registry").orElse(Some("lightbend-docker-registry.bintray.io")),
  dockerUsername := sys.props.get("docker.username").orElse(Some("octo"))
)

lazy val `backend-core` = (project in file("backend/core"))
  .enablePlugins(JavaAppPackaging, DockerPlugin, AkkaGrpcPlugin, JavaAgent, AssemblyPlugin)
  .settings(
    common,
    name := "stateful-serverless-backend-core",
    libraryDependencies ++= Seq(
      "com.typesafe.akka"             %% "akka-persistence"                  % AkkaVersion,
      "com.typesafe.akka"             %% "akka-stream"                       % AkkaVersion,
      "com.typesafe.akka"             %% "akka-http"                         % AkkaHttpVersion,
      "com.typesafe.akka"             %% "akka-http-spray-json"              % AkkaHttpVersion,
      "com.typesafe.akka"             %% "akka-cluster-sharding"             % AkkaVersion,
      "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % AkkaManagementVersion,
      "com.lightbend.akka.discovery"  %% "akka-discovery-kubernetes-api"     % AkkaManagementVersion,
      "com.google.protobuf"            % "protobuf-java"                     % "3.5.1" % "protobuf" // TODO remove this, see: https://github.com/akka/akka-grpc/issues/565
    ),

    // Akka gRPC adds all protobuf files from the classpath to this, which we don't want because it includes
    // all the Google protobuf files which are already compiled and on the classpath by ScalaPB. So we set it
    // back to just our source directory.
    PB.protoSources in Compile := Seq((sourceDirectory in Compile).value / "proto"),

    javaAgents += "org.mortbay.jetty.alpn" % "jetty-alpn-agent" % "2.0.9" % "runtime;test",

    dockerSettings,

    fork in run := true,

    // In memory journal by default
    javaOptions in run ++= Seq("-Dconfig.resource=in-memory.conf", "-Dstateful-serverless.dev-mode-enabled=true"),

    mainClass in assembly := Some("com.lightbend.statefulserverless.StatefulServerlessMain"),

    assemblyJarName in assembly := "akka-backend.jar",

    test in assembly := {},

    // logLevel in assembly := Level.Debug,

    assemblyMergeStrategy in assembly := {
      /*ADD CUSTOMIZATIONS HERE*/
      case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
    }
  )

lazy val `backend-cassandra` = (project in file("backend/cassandra"))
  .enablePlugins(JavaAppPackaging, DockerPlugin, JavaAgent)
  .dependsOn(`backend-core`)
  .settings(
    common,
    name := "stateful-serverless-backend-cassandra",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-persistence-cassandra"          % AkkaPersistenceCassandraVersion,
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
    common,
    name := "stateful-serverless-operator",
    // This is a publishLocal build of this PR https://github.com/doriordan/skuber/pull/268
    libraryDependencies ++= Seq(
      "io.skuber" %% "skuber" % "2.2.0-jroper-1"
    ),

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
    common,
    name := "akka-js-shopping-cart-client",

    fork in run := true,

    libraryDependencies ++= Seq(
      "com.typesafe.akka"  %% "akka-persistence"     % AkkaVersion,
      "com.typesafe.akka"  %% "akka-stream"          % AkkaVersion,
      "com.typesafe.akka"  %% "akka-http"            % AkkaHttpVersion,
      "com.typesafe.akka"  %% "akka-http-spray-json" % AkkaHttpVersion,
      "com.google.protobuf" % "protobuf-java"        % "3.5.1" % "protobuf" // TODO remove this, see: https://github.com/akka/akka-grpc/issues/565
    ),

    target in copyShoppingCartProtos := target.value / "js-shopping-cart-protos",
    copyShoppingCartProtos := {
      val toDir = (target in copyShoppingCartProtos).value
      val fromDir = baseDirectory.value.getParentFile / "js-shopping-cart" / "proto"
      val toCopy = (fromDir * "*.proto").get ++ (fromDir / "lightbend" ** "*.proto").get
      val toSync = (toCopy pair Path.relativeTo(fromDir)).map {
        case (file, path) => file -> toDir / path
      }
      Sync.sync(streams.value.cacheStoreFactory.make(copyShoppingCartProtos.toString()))(toSync)
      toDir
    },

    PB.protoSources in Compile ++= Seq(
      (target in copyShoppingCartProtos).value,
      (sourceDirectory in Compile in `backend-core`).value / "proto"
    ),
    (PB.unpackDependencies in Compile) := {
      copyShoppingCartProtos.value
      (PB.unpackDependencies in Compile).value
    }
  )

val copyProtocolProtosToTCK = taskKey[File]("Copy the protocol files to the tck")

lazy val `tck` = (project in file("tck"))
  .enablePlugins(AkkaGrpcPlugin)
  .dependsOn(`akka-client`)
  .settings(
    name := "tck",

    libraryDependencies ++= Seq(
      "com.typesafe.akka"  %% "akka-stream"          % AkkaVersion,
      "com.typesafe.akka"  %% "akka-http"            % AkkaHttpVersion,
      "com.typesafe.akka"  %% "akka-http-spray-json" % AkkaHttpVersion,
      "com.google.protobuf" % "protobuf-java"        % "3.5.1" % "protobuf", // TODO remove this, see: https://github.com/akka/akka-grpc/issues/565
      "org.scalatest"       % "scalatest_2.12"       % "3.0.5",
      "com.typesafe.akka"  %% "akka-testkit"         % AkkaVersion
    ),

    target in copyShoppingCartProtos := target.value / "js-shopping-cart-protos",
    target in copyProtocolProtosToTCK := target.value / "backend-protos",

    copyShoppingCartProtos := {
      val toDir = (target in copyShoppingCartProtos).value
      val fromDir = baseDirectory.value.getParentFile / "samples" / "js-shopping-cart" / "proto"
      val toCopy = (fromDir * "*.proto").get ++ (fromDir / "lightbend" ** "*.proto").get
      val toSync = (toCopy pair Path.relativeTo(fromDir)).map {
        case (file, path) => file -> toDir / path
      }
      Sync.sync(streams.value.cacheStoreFactory.make(copyShoppingCartProtos.toString()))(toSync)
      toDir
    },

    copyProtocolProtosToTCK := {
      val toDir = (target in copyProtocolProtosToTCK).value
      val fromDir = baseDirectory.value.getParentFile / "backend" / "core" / "src" / "main" / "proto"
      val toSync = (fromDir * "*.proto").get.map(file => file -> toDir / file.getName)
      Sync.sync(streams.value.cacheStoreFactory.make(copyProtocolProtosToTCK.toString()))(toSync)
      toDir
    },

    PB.protoSources in Compile ++= Seq(
      (target in copyShoppingCartProtos).value,
      (target in copyProtocolProtosToTCK).value,
      (sourceDirectory in Compile in `backend-core`).value / "proto"
    ),

    PB.unpackDependencies in Compile := {
      copyShoppingCartProtos.value
      copyProtocolProtosToTCK.value
      (PB.unpackDependencies in Compile).value
    },

    fork in test := false,

    parallelExecution in Test := false,

    executeTests in Test := (executeTests in Test).dependsOn(`backend-core`/assembly).value
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
