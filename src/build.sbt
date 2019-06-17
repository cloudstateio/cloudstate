organization in ThisBuild := "com.lightbend.statefulserverless"
name := "stateful-serverless"
scalaVersion in ThisBuild := "2.12.8"

version in ThisBuild ~= (_.replace('+', '-'))
dynver in ThisBuild ~= (_.replace('+', '-'))

// Needed for our fork of skuber
resolvers in ThisBuild += Resolver.bintrayRepo("jroper", "maven")   // TODO: Remove once skuber has the required functionality
resolvers in ThisBuild += Resolver.bintrayRepo("akka", "snapshots") // TODO: Remove once Akka Http 10.1.9 is out

organizationName in ThisBuild := "Lightbend Inc."
startYear in ThisBuild := Some(2019)
licenses in ThisBuild += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt"))

val AkkaVersion = "2.5.23"
val AkkaHttpVersion = "10.1.8+47-9ef9d823"
val AkkaManagementVersion = "1.0.0"
val AkkaPersistenceCassandraVersion = "0.93"
val ProtobufVersion = "3.5.1"

def common: Seq[Setting[_]] = Seq(
  headerMappings := headerMappings.value ++ Seq(
    de.heikoseeberger.sbtheader.FileType("proto") -> HeaderCommentStyle.cppStyleLineComment,
    de.heikoseeberger.sbtheader.FileType("js") -> HeaderCommentStyle.cStyleBlockComment
  ),

  // Akka gRPC adds all protobuf files from the classpath to this, which we don't want because it includes
  // all the Google protobuf files which are already compiled and on the classpath by ScalaPB. So we set it
  // back to just our source directory.
  PB.protoSources in Compile := Seq(),
  PB.protoSources in Test := Seq(),
)

val copyFrontendProtos = taskKey[File]("Copy the frontend protobufs")
val copyBackendProtos = taskKey[File]("Copy the backend protobufs")

def frontendProtos: Seq[Setting[_]] = Seq(
  target in copyFrontendProtos := target.value / "protos",

  copyFrontendProtos := {
    val toDir = (target in copyFrontendProtos).value
    val fromDir = file(".") / "protocols" / "frontend"
    val toCopy = (fromDir ** "*.proto").get
    val toSync = (toCopy pair Path.relativeTo(fromDir)).map {
      case (file, path) => file -> toDir / path
    }
    Sync.sync(streams.value.cacheStoreFactory.make(copyFrontendProtos.toString()))(toSync)
    toDir
  },

  PB.protoSources in Compile ++= Seq((target in copyFrontendProtos).value),

  PB.unpackDependencies in Compile := {
    copyFrontendProtos.value
    (PB.unpackDependencies in Compile).value
  }
)

def backendProtos: Seq[Setting[_]] = Seq(
  target in copyBackendProtos := target.value / "protos",

  copyBackendProtos := {
    val toDir = (target in copyBackendProtos).value
    val fromDir = file(".") / "protocols" / "backend"
    val toCopy = (fromDir ** "*.proto").get
    val toSync = (toCopy pair Path.relativeTo(fromDir)).map {
      case (file, path) => file -> toDir / path
    }
    Sync.sync(streams.value.cacheStoreFactory.make(copyBackendProtos.toString()))(toSync)
    toDir
  },

  PB.protoSources in Compile ++= Seq((target in copyBackendProtos).value),

  PB.unpackDependencies in Compile := {
    copyBackendProtos.value
    (PB.unpackDependencies in Compile).value
  }
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
      "com.typesafe.akka"             %% "akka-http-core"                    % AkkaHttpVersion,
      "com.typesafe.akka"             %% "akka-http2-support"                % AkkaHttpVersion,
      "com.typesafe.akka"             %% "akka-cluster-sharding"             % AkkaVersion,
      "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % AkkaManagementVersion,
      "com.lightbend.akka.discovery"  %% "akka-discovery-kubernetes-api"     % AkkaManagementVersion,
      "com.google.protobuf"            % "protobuf-java"                     % ProtobufVersion % "protobuf", // TODO remove this, see: https://github.com/akka/akka-grpc/issues/565
      "com.google.protobuf"            % "protobuf-java-util"                % ProtobufVersion,

      "org.scalatest"                 %% "scalatest"                         % "3.0.5" % "test",
      "com.typesafe.akka"             %% "akka-stream-testkit"               % AkkaVersion % "test",
      "com.typesafe.akka"             %% "akka-http-testkit"                 % AkkaHttpVersion % "test"
    ),

    backendProtos,

    // This adds the test/protos dir and enables the ProtocPlugin to generate protos in the Test scope
    inConfig(Test)(
      sbtprotoc.ProtocPlugin.protobufConfigSettings ++ Seq(
        PB.protoSources ++= Seq(sourceDirectory.value / "protos"),
      )
    ),

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
      "com.google.protobuf" % "protobuf-java"        % ProtobufVersion % "protobuf" // TODO remove this, see: https://github.com/akka/akka-grpc/issues/565
    ),

    frontendProtos
  )

lazy val `tck` = (project in file("tck"))
  .enablePlugins(AkkaGrpcPlugin)
  .dependsOn(`akka-client`)
  .settings(
    common,

    name := "tck",

    libraryDependencies ++= Seq(
      "com.typesafe.akka"  %% "akka-stream"          % AkkaVersion,
      "com.typesafe.akka"  %% "akka-http"            % AkkaHttpVersion,
      "com.typesafe.akka"  %% "akka-http-spray-json" % AkkaHttpVersion,
      "com.google.protobuf" % "protobuf-java"        % ProtobufVersion % "protobuf", // TODO remove this, see: https://github.com/akka/akka-grpc/issues/565
      "org.scalatest"       % "scalatest_2.12"       % "3.0.5",
      "com.typesafe.akka"  %% "akka-testkit"         % AkkaVersion
    ),

    backendProtos,
    frontendProtos,

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
