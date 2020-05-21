import java.io.File
import java.util.Date

import sbt.Keys.{developers, scmInfo}
import sbt.url

inThisBuild(
  Seq(
    organization := "io.cloudstate",
    version := dynverGitDescribeOutput.value.mkVersion(versionFmt, "latest"),
    dynver := sbtdynver.DynVer.getGitDescribeOutput(new Date).mkVersion(versionFmt, "latest"),
    scalaVersion := "2.12.11",
    // Needed for the akka-grpc 0.8.4 snapshot
    resolvers += Resolver.bintrayRepo("akka", "maven"), // TODO: Remove once we're switching to akka-grpc 0.8.5/1.0.0
    organizationName := "Lightbend Inc.",
    organizationHomepage := Some(url("https://lightbend.com")),
    startYear := Some(2019),
    licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt")),
    resolvers += "Akka Snapshots" at "https://repo.akka.io/snapshots/", // FIXME for akka-protobuf-v390
    homepage := Some(url("https://cloudstate.io")),
    scmInfo := Some(
        ScmInfo(
          url("https://github.com/cloudstateio/cloudstate"),
          "scm:git@github.com:cloudstateio/cloudstate.git"
        )
      ),
    developers := List(
        Developer(id = "jroper", name = "James Roper", email = "james@jazzy.id.au", url = url("https://jazzy.id.au")),
        Developer(id = "viktorklang",
                  name = "Viktor Klang",
                  email = "viktor.klang@gmail.com",
                  url = url("https://viktorklang.com"))
      ),
    sonatypeProfileName := "io.cloudstate",
    scalafmtOnCompile := true,
    closeClassLoaders := false
  )
)

// Make sure the version doesn't change each time it gets built, this ensures we don't rebuild the native image
// every time we build a docker image based on it, since we actually build 3 different docker images for the proxy
// command.
def versionFmt(out: sbtdynver.GitDescribeOutput): String = {
  val dirtySuffix = if (out.isDirty()) "-dev" else ""
  if (out.isCleanAfterTag) out.ref.dropV.value
  else out.ref.dropV.value + out.commitSuffix.mkString("-", "-", "") + dirtySuffix
}

name := "cloudstate"

// Should be in sync with the version used in Akka gRPC
val GrpcJavaVersion = "1.29.0"
// Unfortunately we need to downgrade grpc-netty-shaded
// in the proxy until we have a fix to make it work with
// native-image
val GrpcNettyShadedVersion = "1.28.1"
val GraalAkkaVersion = "0.5.0"
val AkkaVersion = "2.6.5"
val AkkaHttpVersion = "10.1.12"
val AkkaManagementVersion = "1.0.5"
val AkkaPersistenceCassandraVersion = "0.102"
val PrometheusClientVersion = "0.6.0"
val ScalaTestVersion = "3.0.5"
val ProtobufVersion = "3.9.0" // We use this version because it is the latest which works with native-image 20.0.0
val GraalVersion = "20.1.0"
val DockerBaseImageVersion = "adoptopenjdk/openjdk11:debian"
val DockerBaseImageJavaLibraryPath = "${JAVA_HOME}/lib"

val excludeTheseDependencies: Seq[ExclusionRule] = Seq(
  ExclusionRule("io.netty", "netty"), // grpc-java is using grpc-netty-shaded
  ExclusionRule("io.aeron"), // we're using Artery-TCP
  ExclusionRule("org.agrona") // and we don't need this either
)

def akkaDependency(name: String, excludeThese: ExclusionRule*) =
  "com.typesafe.akka" %% name % AkkaVersion excludeAll ((excludeTheseDependencies ++ excludeThese): _*)

def akkaHttpDependency(name: String, excludeThese: ExclusionRule*) =
  "com.typesafe.akka" %% name % AkkaHttpVersion excludeAll ((excludeTheseDependencies ++ excludeThese): _*)

def akkaManagementDependency(name: String, excludeThese: ExclusionRule*) =
  "com.lightbend.akka.management" %% name % AkkaManagementVersion excludeAll ((excludeTheseDependencies ++ excludeThese): _*)

def akkaDiscoveryDependency(name: String, excludeThese: ExclusionRule*) =
  "com.lightbend.akka.discovery" %% name % AkkaManagementVersion excludeAll ((excludeTheseDependencies ++ excludeThese): _*)

def akkaPersistenceCassandraDependency(name: String, excludeThese: ExclusionRule*) =
  "com.typesafe.akka" %% name % AkkaPersistenceCassandraVersion excludeAll ((excludeTheseDependencies ++ excludeThese): _*)

def common: Seq[Setting[_]] = Seq(
  headerMappings := headerMappings.value ++ Seq(
      de.heikoseeberger.sbtheader.FileType("proto") -> HeaderCommentStyle.cppStyleLineComment,
      de.heikoseeberger.sbtheader.FileType("js") -> HeaderCommentStyle.cStyleBlockComment
    ),
  // Akka gRPC overrides the default ScalaPB setting including the file base name, let's override it right back.
  akkaGrpcCodeGeneratorSettings := Seq(),
  excludeDependencies += "com.typesafe.akka" %% "akka-protobuf-v3", // akka-protobuf-v3 shades protobuf-java 3.10.0 which is not compatible with native-image 20.0.0
  excludeFilter in headerResources := HiddenFileFilter || GlobFilter("reflection.proto"),
  javaOptions in Test ++= Seq("-Xms1G", "-XX:+CMSClassUnloadingEnabled", "-XX:+UseConcMarkSweepGC")
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
// Don't forget to add your sbt module here!
// A missing module here can lead to failing Travis test results
  .aggregate(
    `protocols`,
    `proxy-core`,
    `proxy-cassandra`,
    `proxy-postgres`,
    `proxy-tests`,
    `java-support`,
    `scala-support`,
    `java-shopping-cart`,
    `java-pingpong`,
    `akka-client`,
    operator,
    `tck`,
    `graal-tools`,
    docs
  )
  .settings(common)

val cloudstateProtocolsName = "cloudstate-protocols"
val cloudstateTCKProtocolsName = "cloudstate-tck-protocols"

lazy val protocols = (project in file("protocols"))
  .settings(
    name := "protocols",
    publish / skip := true,
    packageBin in Compile := {
      val base = baseDirectory.value
      val cloudstateProtos = base / s"$cloudstateProtocolsName.zip"
      val cloudstateTCKProtos = base / s"$cloudstateTCKProtocolsName.zip"

      def archiveStructure(topDirName: String, files: PathFinder): Seq[(File, String)] =
        files pair Path.relativeTo(base) map {
          case (f, s) => (f, s"$topDirName${File.separator}$s")
        }

      // Common Language Support Proto Dependencies
      IO.zip(
        archiveStructure(cloudstateProtocolsName,
                         (base / "frontend" ** "*.proto" +++
                         base / "protocol" ** "*.proto")),
        cloudstateProtos
      )

      // Common TCK Language Support Proto Dependencies
      IO.zip(archiveStructure(cloudstateTCKProtocolsName, base / "example" ** "*.proto"), cloudstateTCKProtos)

      cloudstateProtos
    },
    cleanFiles ++= Seq(
        baseDirectory.value / s"$cloudstateProtocolsName.zip",
        baseDirectory.value / s"$cloudstateTCKProtocolsName.zip"
      )
  )

lazy val docs = (project in file("docs"))
  .enablePlugins(ParadoxPlugin, ProtocPlugin)
  .dependsOn(`java-support` % Test)
  .settings(
    common,
    name := "Cloudstate Documentation",
    paradoxTheme := Some(builtinParadoxTheme("generic")),
    mappings in (Compile, paradox) ++= {
      val javaApiDocs = (doc in (`java-support`, Compile)).value

      // Run the npm docs build
      val nodeSupportDir = (baseDirectory in ThisBuild).value / "node-support"
      import sys.process._
      val rc = Process("npm run jsdoc", nodeSupportDir).run(streams.value.log).exitValue()
      if (rc != 0) sys.error(s"jsdoc failed with return code $rc")
      val javaScriptApiDocs = nodeSupportDir / "apidocs"

      ((javaApiDocs ** "*") pair Path.relativeTo(javaApiDocs)).map {
        case (file, path) => file -> s"user/lang/java/api/$path"
      } ++
      ((javaScriptApiDocs ** "*") pair Path.relativeTo(javaScriptApiDocs)).map {
        case (file, path) => file -> s"user/lang/javascript/api/$path"
      }
    },
    paradoxProperties in Compile ++= Map(
        "canonical.base_url" -> "https://cloudstate.io/docs/",
        "javadoc.io.cloudstate.javasupport.base_url" -> ".../user/lang/java/api/",
        "javadoc.link_style" -> "direct",
        "extref.jsdoc.base_url" -> ".../user/lang/javascript/api/module-cloudstate.%s",
        "cloudstate.version" -> "0.4.3", // hardcode, otherwise we'll end up with the wrong version in the docs
        "cloudstate.java-support.version" -> "0.4.3",
        "cloudstate.node-support.version" -> "0.0.1",
        "cloudstate.go-support.version" -> "0.1.0",
        "cloudstate.go.version" -> "1.13",
        "cloudstate.kotlin-support.version" -> "0.5.1",
        "cloudstate.dart-support.version" -> "0.5.5"
      ),
    paradoxNavigationDepth := 3,
    inConfig(Test)(
      sbtprotoc.ProtocPlugin.protobufConfigSettings ++ Seq(
        PB.protoSources += sourceDirectory.value / "proto",
        PB.targets += PB.gens.java -> sourceManaged.value
      )
    )
  )

lazy val proxyDockerBuild = settingKey[Option[(String, Option[String])]](
  "Docker artifact name and configuration file which gets overridden by the buildProxy command"
)

def dockerSettings: Seq[Setting[_]] = Seq(
  proxyDockerBuild := None,
  dockerUpdateLatest := true,
  dockerRepository := sys.props.get("docker.registry"),
  dockerUsername := sys.props.get("docker.username").orElse(Some("cloudstateio")).filter(_ != ""),
  dockerBaseImage := DockerBaseImageVersion,
  // when using tags like latest, uncomment below line, so that local cache will not be used.
  //  dockerBuildOptions += "--no-cache",
  dockerAlias := {
    val old = dockerAlias.value
    proxyDockerBuild.value match {
      case Some((dockerName, _)) => old.withName(dockerName)
      case None => old
    }
  },
  dockerAliases := {
    val old = dockerAliases.value
    val single = dockerAlias.value
    // If a tag is explicitly configured, publish that, otherwise if it's a snapshot, just publish latest, otherwise,
    // publish both latest and the version
    sys.props.get("docker.tag") match {
      case some @ Some(_) => Seq(single.withTag(some))
      case _ if isSnapshot.value => Seq(single.withTag(Some("latest")))
      case _ => old
    }
  },
  // For projects that we publish using Docker, disable the generation of java/scaladocs
  publishArtifact in (Compile, packageDoc) := false
)

def buildProxyHelp(commandName: String, name: String) =
  Help(
    (s"$commandName <task>",
     s"Execute the given docker scoped task (eg, publishLocal or publish) for the $name build of the proxy.")
  )

def buildProxyCommand(commandName: String,
                      project: => Project,
                      name: String,
                      configResource: Option[String],
                      native: Boolean): Command = {
  val cn =
    if (native) s"dockerBuildNative$commandName"
    else s"dockerBuild$commandName"
  val imageName =
    if (native) s"native-$name"
    else name
  val configResourceSetting = configResource match {
    case Some(resource) => "Some(\"" + resource + "\")"
    case None => "None"
  }
  Command.single(
    cn,
    buildProxyHelp(cn, name)
  ) { (state, command) =>
    List(
      s"""set proxyDockerBuild in `${project.id}` := Some(("cloudstate-proxy-$imageName", $configResourceSetting))""",
      s"""set graalVMDockerPublishLocalBuild in ThisBuild := $native""",
      s"${project.id}/docker:$command",
      s"set proxyDockerBuild in `${project.id}` := None"
    ) ::: state
  }
}

commands ++= Seq(
  buildProxyCommand("DevMode", `proxy-core`, "dev-mode", Some("dev-mode.conf"), true),
  buildProxyCommand("DevMode", `proxy-core`, "dev-mode", Some("dev-mode.conf"), false),
  buildProxyCommand("NoStore", `proxy-core`, "no-store", Some("no-store.conf"), true),
  buildProxyCommand("NoStore", `proxy-core`, "no-store", Some("no-store.conf"), false),
  buildProxyCommand("InMemory", `proxy-core`, "in-memory", Some("in-memory.conf"), true),
  buildProxyCommand("InMemory", `proxy-core`, "in-memory", Some("in-memory.conf"), false),
  buildProxyCommand("Cassandra", `proxy-cassandra`, "cassandra", None, true),
  buildProxyCommand("Cassandra", `proxy-cassandra`, "cassandra", None, false),
  buildProxyCommand("Postgres", `proxy-postgres`, "postgres", None, true),
  buildProxyCommand("Postgres", `proxy-postgres`, "postgres", None, false),
  Command.single("dockerBuildAllNonNative", buildProxyHelp("dockerBuildAllNonNative", "all non native")) {
    (state, command) =>
      List("DevMode", "NoStore", "InMemory", "Cassandra", "Postgres")
        .map(c => s"dockerBuild$c $command") ::: state
  },
  Command.single("dockerBuildAllNative", buildProxyHelp("dockerBuildAllNative", "all native")) { (state, command) =>
    List("DevMode", "NoStore", "InMemory", "Cassandra", "Postgres")
      .map(c => s"dockerBuildNative$c $command") ::: state
  }
)

// Shared settings for native image and docker builds
def nativeImageDockerSettings: Seq[Setting[_]] = dockerSettings ++ Seq(
  // If this is Some(…): run the native-image generation inside a Docker image
  // If this is None: run the native-image generation using a local GraalVM installation
  graalVMVersion := Some(GraalVersion + "-java11"), // make sure we use the java11 version
  graalVMNativeImageOptions ++= sharedNativeImageSettings({
      graalVMVersion.value match {
        case Some(_) => new File("/opt/docker/graal-resources/")
        case None => baseDirectory.value / "src" / "graal"
      }
    }, graalVMBuildServer.value),
  dockerEntrypoint := {
    val old = dockerEntrypoint.value
    val withLibraryPath = if (graalVMDockerPublishLocalBuild.value) {
      old :+ s"-Djava.library.path=${DockerBaseImageJavaLibraryPath}"
    } else old
    proxyDockerBuild.value match {
      case Some((_, Some(configResource))) => withLibraryPath :+ s"-Dconfig.resource=$configResource"
      case _ => withLibraryPath
    }
  }
)

def assemblySettings(jarName: String) =
  Seq(
    mainClass in assembly := (mainClass in Compile).value,
    assemblyJarName in assembly := jarName,
    test in assembly := {},
    // logLevel in assembly := Level.Debug,
    assemblyMergeStrategy in assembly := {
      /*ADD CUSTOMIZATIONS HERE*/
      case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.last
      case PathList(ps @ _*) if ps.last endsWith ".proto" => MergeStrategy.last
      case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
    }
  )

def sharedNativeImageSettings(targetDir: File, buildServer: Boolean) = Seq(
  //"-O1", // Optimization level
  "-H:ResourceConfigurationFiles=" + targetDir / "resource-config.json",
  "-H:ReflectionConfigurationFiles=" + targetDir / "reflect-config.json",
  "-H:DynamicProxyConfigurationFiles=" + targetDir / "proxy-config.json",
  "-H:IncludeResources=.+\\.conf",
  "-H:IncludeResources=.+\\.properties",
  "-H:+AllowVMInspection",
  "-H:-RuntimeAssertions",
  "-H:+RemoveSaturatedTypeFlows", // GraalVM native-image 20.1 feature which speeds up the build time
  "-H:+ReportExceptionStackTraces",
  // "-H:+PrintAnalysisCallTree", // Uncomment to dump the entire call graph, useful for debugging native-image failing builds
  //"-H:ReportAnalysisForbiddenType=java.lang.invoke.MethodHandleImpl$AsVarargsCollector", // Uncomment and specify a type which will break analysis, useful to figure out reachability
  "-H:-PrintUniverse", // if "+" prints out all classes which are included
  "-H:-NativeArchitecture", // if "+" Compiles the native image to customize to the local CPU arch
  "-H:Class=" + "io.cloudstate.proxy.CloudStateProxyMain",
  // build server is disabled for docker builds by default (and native-image will use 80% of available memory in docker)
  // for local (non-docker) builds, can be disabled with `set graalVMBuildServer := false` to avoid potential cache problems
  if (buildServer) "--verbose-server" else "--no-server",
  //"--debug-attach=5005", // Debugger makes a ton of sense to use to debug SubstrateVM
  "--verbose",
  "--report-unsupported-elements-at-runtime", // Hopefully a self-explanatory flag FIXME comment this option out once AffinityPool is gone
  "--enable-url-protocols=http,https",
  "--allow-incomplete-classpath",
  "--no-fallback",
  "--initialize-at-build-time"
  + Seq(
    "org.slf4j",
    "scala",
    "akka.dispatch.affinity",
    "akka.util",
    "com.google.Protobuf"
  ).mkString("=", ",", ""),
  "--initialize-at-run-time" +
  Seq(
    "com.typesafe.config.impl.ConfigImpl",
    "com.typesafe.config.impl.ConfigImpl$EnvVariablesHolder",
    "com.typesafe.config.impl.ConfigImpl$SystemPropertiesHolder",
    "com.typesafe.config.impl.ConfigImpl$LoaderCacheHolder",
    "akka.actor.ActorCell", // Do not initialize the actor system until runtime (native-image)
    // These are to make up for the lack of shaded configuration for svm/native-image in grpc-netty-shaded
    "com.sun.jndi.dns.DnsClient",
    "com.typesafe.sslconfig.ssl.tracing.TracingSSLContext",
    "io.grpc.netty.shaded.io.netty.handler.codec.http2.Http2CodecUtil",
    "io.grpc.netty.shaded.io.netty.handler.codec.http2.DefaultHttp2FrameWriter",
    "io.grpc.netty.shaded.io.netty.handler.codec.http.HttpObjectEncoder",
    "io.grpc.netty.shaded.io.netty.handler.codec.http.websocketx.WebSocket00FrameEncoder",
    "io.grpc.netty.shaded.io.netty.handler.ssl.util.ThreadLocalInsecureRandom",
    "io.grpc.netty.shaded.io.netty.handler.ssl.ConscryptAlpnSslEngine",
    "io.grpc.netty.shaded.io.netty.handler.ssl.JettyNpnSslEngine",
    "io.grpc.netty.shaded.io.netty.handler.ssl.ReferenceCountedOpenSslEngine",
    "io.grpc.netty.shaded.io.netty.handler.ssl.JdkNpnApplicationProtocolNegotiator",
    "io.grpc.netty.shaded.io.netty.handler.ssl.ReferenceCountedOpenSslServerContext",
    "io.grpc.netty.shaded.io.netty.handler.ssl.ReferenceCountedOpenSslClientContext",
    "io.grpc.netty.shaded.io.netty.handler.ssl.util.BouncyCastleSelfSignedCertGenerator",
    "io.grpc.netty.shaded.io.netty.handler.ssl.ReferenceCountedOpenSslContext",
    "io.grpc.netty.shaded.io.netty.channel.socket.nio.NioSocketChannel"
  ).mkString("=", ",", "")
)

lazy val `proxy-core` = (project in file("proxy/core"))
  .enablePlugins(DockerPlugin, AkkaGrpcPlugin, JavaAgent, AssemblyPlugin, GraalVMPlugin, BuildInfoPlugin)
  .dependsOn(`graal-tools` % Provided) // Only needed for compilation
  .settings(
    common,
    name := "cloudstate-proxy-core",
    buildInfoKeys := Seq[BuildInfoKey](name, version),
    buildInfoPackage := "io.cloudstate.proxy",
    dependencyOverrides += "io.grpc" % "grpc-netty-shaded" % GrpcNettyShadedVersion,
    libraryDependencies ++= Seq(
        // Since we exclude Aeron, we also exclude its transitive Agrona dependency, so we need to manually add it HERE
        "org.agrona" % "agrona" % "0.9.29",
        akkaDependency("akka-remote"),
        "com.typesafe.akka" %% "akka-protobuf-v390" % (AkkaVersion + "-proto390") excludeAll (excludeTheseDependencies: _*), // FIXME since akka-protobuf-v3 uses a shaded protobuf-java 3.10.0 which is not supported in native-image yet…
        // For Eventing support of Google Pubsub
        "com.google.api.grpc" % "grpc-google-cloud-pubsub-v1" % "0.12.0" % "protobuf", // ApacheV2
        "io.grpc" % "grpc-auth" % GrpcJavaVersion, // ApacheV2
        "com.google.auth" % "google-auth-library-oauth2-http" % "0.15.0", // BSD 3-clause
        akkaDependency("akka-persistence"),
        akkaDependency("akka-persistence-query"),
        akkaDependency("akka-stream"),
        akkaDependency("akka-slf4j"),
        akkaDependency("akka-discovery"),
        akkaHttpDependency("akka-http"),
        akkaHttpDependency("akka-http-spray-json"),
        akkaHttpDependency("akka-http-core"),
        akkaHttpDependency("akka-http2-support"),
        akkaDependency("akka-cluster-sharding", ExclusionRule("org.lmdbjava", "lmdbjava")),
        akkaManagementDependency("akka-management-cluster-bootstrap"),
        akkaDiscoveryDependency("akka-discovery-kubernetes-api"),
        "com.google.protobuf" % "protobuf-java" % ProtobufVersion % "protobuf",
        "com.google.protobuf" % "protobuf-java-util" % ProtobufVersion,
        "org.scalatest" %% "scalatest" % ScalaTestVersion % Test,
        akkaDependency("akka-testkit") % Test,
        akkaDependency("akka-stream-testkit") % Test,
        akkaHttpDependency("akka-http-testkit") % Test,
        "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
        "io.prometheus" % "simpleclient" % PrometheusClientVersion,
        "io.prometheus" % "simpleclient_common" % PrometheusClientVersion,
        "org.slf4j" % "slf4j-simple" % "1.7.26"
        //"ch.qos.logback"                 % "logback-classic"                   % "1.2.3", // Doesn't work well with SubstrateVM: https://github.com/vmencik/akka-graal-native/blob/master/README.md#logging
      ),
    // Work around for https://github.com/akka/akka-grpc/pull/673
    (PB.targets in Compile) := {
      val old = (PB.targets in Compile).value
      val ct = crossTarget.value

      old.map(_.copy(outputPath = ct / "akka-grpc" / "main"))
    },
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "frontend", baseDir / "protocol")
    },
    PB.protoSources in Test ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "frontend")
    },
    // For Google Cloud Pubsub API
    PB.protoSources in Compile += target.value / "protobuf_external" / "google" / "pubsub" / "v1",
    javaAgents += "org.mortbay.jetty.alpn" % "jetty-alpn-agent" % "2.0.10" % "runtime;test",
    mainClass in Compile := Some("io.cloudstate.proxy.CloudStateProxyMain"),
    dockerSettings,
    fork in run := true,
    // In memory journal by default
    javaOptions in run ++= Seq("-Dconfig.resource=dev-mode.conf"),
    assemblySettings("akka-proxy.jar"),
    nativeImageDockerSettings
  )

lazy val `proxy-cassandra` = (project in file("proxy/cassandra"))
  .enablePlugins(DockerPlugin, JavaAgent, GraalVMPlugin)
  .dependsOn(`proxy-core`)
  .settings(
    common,
    name := "cloudstate-proxy-cassandra",
    libraryDependencies ++= Seq(
        akkaPersistenceCassandraDependency("akka-persistence-cassandra", ExclusionRule("com.github.jnr")),
        akkaPersistenceCassandraDependency("akka-persistence-cassandra-launcher") % Test
      ),
    fork in run := true,
    mainClass in Compile := Some("io.cloudstate.proxy.CloudStateProxyMain"),
    nativeImageDockerSettings,
    graalVMNativeImageOptions ++= Seq(
        "-H:IncludeResourceBundles=com.datastax.driver.core.Driver"
      )
  )

lazy val `proxy-jdbc` = (project in file("proxy/jdbc"))
  .dependsOn(`proxy-core`)
  .settings(
    common,
    name := "cloudstate-proxy-jdbc",
    libraryDependencies ++= Seq(
        "com.github.dnvriend" %% "akka-persistence-jdbc" % "3.5.2"
      ),
    fork in run := true,
    mainClass in Compile := Some("io.cloudstate.proxy.CloudStateProxyMain")
  )

lazy val `proxy-postgres` = (project in file("proxy/postgres"))
  .enablePlugins(DockerPlugin, JavaAgent, GraalVMPlugin, AssemblyPlugin)
  .dependsOn(`proxy-jdbc`)
  .settings(
    common,
    name := "cloudstate-proxy-postgres",
    libraryDependencies ++= Seq(
        "org.postgresql" % "postgresql" % "42.2.6"
      ),
    fork in run := true,
    mainClass in Compile := Some("io.cloudstate.proxy.jdbc.CloudStateJdbcProxyMain"),
    // If run by sbt, run in dev mode
    javaOptions in run += "-Dcloudstate.proxy.dev-mode-enabled=true",
    assemblySettings("akka-proxy.jar"),
    nativeImageDockerSettings,
    graalVMNativeImageOptions ++= Seq(
        "--initialize-at-build-time"
        + Seq(
          "org.postgresql.Driver",
          "org.postgresql.util.SharedTimer"
        ).mkString("=", ",", "")
      )
  )

lazy val `proxy-tests` = (project in file("proxy/proxy-tests"))
  .dependsOn(`proxy-core`, `akka-client`, `java-pingpong`)
  .settings(
    common,
    name := "cloudstate-proxy-tests",
    fork in Test := System.getProperty("RUN_STRESS_TESTS", "false") == "true",
    parallelExecution in Test := false,
    baseDirectory in Test := (baseDirectory in ThisBuild).value,
    libraryDependencies ++= Seq(
        "org.scalatest" %% "scalatest" % ScalaTestVersion % Test,
        akkaDependency("akka-testkit") % Test
      )
  )

val compileK8sDescriptors = taskKey[File]("Compile the K8s descriptors into one")

lazy val operator = (project in file("operator"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    common,
    name := "cloudstate-operator",
    libraryDependencies ++= Seq(
        akkaDependency("akka-stream"),
        akkaDependency("akka-slf4j"),
        akkaHttpDependency("akka-http"),
        "io.skuber" %% "skuber" % "2.4.0",
        "ch.qos.logback" % "logback-classic" % "1.2.3" // Doesn't work well with SubstrateVM, use "org.slf4j"           % "slf4j-simple"     % "1.7.26" instead
      ),
    dockerSettings,
    dockerExposedPorts := Nil,
    compileK8sDescriptors := {
      val tag = version.value
      doCompileK8sDescriptors(
        baseDirectory.value / "deploy",
        baseDirectory.value,
        dockerRepository.value,
        dockerUsername.value,
        sys.props.get("docker.tag").getOrElse { if (isSnapshot.value) "latest" else tag },
        streams.value
      )
    }
  )

lazy val `java-support` = (project in file("java-support"))
  .enablePlugins(AkkaGrpcPlugin, BuildInfoPlugin)
  .settings(
    name := "cloudstate-java-support",
    common,
    crossPaths := false,
    publishMavenStyle := true,
    publishTo := sonatypePublishTo.value,
    buildInfoKeys := Seq[BuildInfoKey](name, version),
    buildInfoPackage := "io.cloudstate.javasupport",
    // Generate javadocs by just including non generated Java sources
    sourceDirectories in (Compile, doc) := Seq((javaSource in Compile).value),
    sources in (Compile, doc) := {
      val javaSourceDir = (javaSource in Compile).value.getAbsolutePath
      (sources in (Compile, doc)).value.filter(_.getAbsolutePath.startsWith(javaSourceDir))
    },
    // javadoc (I think java 9 onwards) refuses to compile javadocs if it can't compile the entire source path.
    // but since we have java files depending on Scala files, we need to include ourselves on the classpath.
    dependencyClasspath in (Compile, doc) := (fullClasspath in Compile).value,
    javacOptions in (Compile, doc) ++= Seq(
        "-overview",
        ((javaSource in Compile).value / "overview.html").getAbsolutePath,
        "-notimestamp",
        "-doctitle",
        "Cloudstate Java Support"
      ),
    libraryDependencies ++= Seq(
        akkaDependency("akka-stream"),
        akkaDependency("akka-slf4j"),
        akkaDependency("akka-discovery"),
        akkaHttpDependency("akka-http"),
        akkaHttpDependency("akka-http-spray-json"),
        akkaHttpDependency("akka-http-core"),
        akkaHttpDependency("akka-http2-support"),
        "com.google.protobuf" % "protobuf-java" % ProtobufVersion % "protobuf",
        "com.google.protobuf" % "protobuf-java-util" % ProtobufVersion,
        "org.scalatest" %% "scalatest" % ScalaTestVersion % Test,
        akkaDependency("akka-testkit") % Test,
        akkaDependency("akka-stream-testkit") % Test,
        akkaHttpDependency("akka-http-testkit") % Test,
        "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
        "org.slf4j" % "slf4j-simple" % "1.7.26",
        "com.fasterxml.jackson.core" % "jackson-databind" % "2.9.9.3"
      ),
    javacOptions in Compile ++= Seq("-encoding", "UTF-8"),
    javacOptions in (Compile, compile) ++= Seq("-source", "1.8", "-target", "1.8"),
    akkaGrpcGeneratedSources in Compile := Seq(AkkaGrpc.Server),
    akkaGrpcGeneratedLanguages in Compile := Seq(AkkaGrpc.Scala), // FIXME should be Java, but here be dragons

    // Work around for https://github.com/akka/akka-grpc/pull/673
    (PB.targets in Compile) := {
      val old = (PB.targets in Compile).value
      val ct = crossTarget.value

      old.map(_.copy(outputPath = ct / "akka-grpc" / "main"))
    },
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "protocol", baseDir / "frontend")
    },
    // We need to generate the java files for things like entity_key.proto so that downstream libraries can use them
    // without needing to generate them themselves
    PB.targets in Compile += PB.gens.java -> crossTarget.value / "akka-grpc" / "main",
    inConfig(Test)(
      sbtprotoc.ProtocPlugin.protobufConfigSettings ++ Seq(
        PB.protoSources ++= {
          val baseDir = (baseDirectory in ThisBuild).value / "protocols"
          Seq(baseDir / "example")
        },
        PB.targets := Seq(
            PB.gens.java -> crossTarget.value / "akka-grpc" / "test"
          )
      )
    )
  )

lazy val `scala-support` = (project in file("scala-support"))
  .enablePlugins(AkkaGrpcPlugin, BuildInfoPlugin)
  .settings(
    name := "cloudstate-scala-support",
    common,
    crossPaths := false,
    publishMavenStyle := true,
    publishTo := sonatypePublishTo.value,
    buildInfoKeys := Seq[BuildInfoKey](name, version),
    buildInfoPackage := "io.cloudstate.scalasupport",
    // Generate javadocs by just including non generated Java sources
    sourceDirectories in (Compile, doc) := Seq((javaSource in Compile).value),
    sources in (Compile, doc) := {
      val javaSourceDir = (javaSource in Compile).value.getAbsolutePath
      (sources in (Compile, doc)).value.filter(_.getAbsolutePath.startsWith(javaSourceDir))
    },
    // javadoc (I think java 9 onwards) refuses to compile javadocs if it can't compile the entire source path.
    // but since we have java files depending on Scala files, we need to include ourselves on the classpath.
    dependencyClasspath in (Compile, doc) := (fullClasspath in Compile).value,
    javacOptions in (Compile, doc) ++= Seq(
        "-overview",
        ((javaSource in Compile).value / "overview.html").getAbsolutePath,
        "-notimestamp",
        "-doctitle",
        "CloudState Scala Support"
      ),
    libraryDependencies ++= Seq(
        akkaDependency("akka-stream"),
        akkaDependency("akka-slf4j"),
        akkaDependency("akka-discovery"),
        akkaHttpDependency("akka-http"),
        akkaHttpDependency("akka-http-spray-json"),
        akkaHttpDependency("akka-http-core"),
        akkaHttpDependency("akka-http2-support"),
        "com.google.protobuf" % "protobuf-java" % ProtobufVersion % "protobuf",
        "com.google.protobuf" % "protobuf-java-util" % ProtobufVersion,
        "org.scalatest" %% "scalatest" % ScalaTestVersion % Test,
        akkaDependency("akka-testkit") % Test,
        akkaDependency("akka-stream-testkit") % Test,
        akkaHttpDependency("akka-http-testkit") % Test,
        "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
        "org.slf4j" % "slf4j-simple" % "1.7.26",
        "com.fasterxml.jackson.core" % "jackson-databind" % "2.9.9.3"
      ),
    javacOptions in Compile ++= Seq("-encoding", "UTF-8"),
    akkaGrpcGeneratedSources in Compile := Seq(AkkaGrpc.Server),
    akkaGrpcGeneratedLanguages in Compile := Seq(AkkaGrpc.Scala), // FIXME should be Java, but here be dragons

    // Work around for https://github.com/akka/akka-grpc/pull/673
    (PB.targets in Compile) := {
      val old = (PB.targets in Compile).value
      val ct = crossTarget.value

      old.map(_.copy(outputPath = ct / "akka-grpc" / "main"))
    },
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "protocol", baseDir / "frontend")
    }
  )

lazy val `java-shopping-cart` = (project in file("samples/java-shopping-cart"))
  .dependsOn(`java-support`)
  .enablePlugins(AkkaGrpcPlugin, AssemblyPlugin, JavaAppPackaging, DockerPlugin)
  .settings(
    name := "java-shopping-cart",
    dockerSettings,
    mainClass in Compile := Some("io.cloudstate.samples.shoppingcart.Main"),
    PB.generate in Compile := (PB.generate in Compile).dependsOn(PB.generate in (`java-support`, Compile)).value,
    akkaGrpcGeneratedLanguages := Seq(AkkaGrpc.Java),
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "frontend", baseDir / "example")
    },
    PB.targets in Compile := Seq(
        PB.gens.java -> (sourceManaged in Compile).value
      ),
    javacOptions in Compile ++= Seq("-encoding", "UTF-8", "-source", "1.8", "-target", "1.8"),
    assemblySettings("java-shopping-cart.jar")
  )

lazy val `java-pingpong` = (project in file("samples/java-pingpong"))
  .dependsOn(`java-support`)
  .enablePlugins(AkkaGrpcPlugin, AssemblyPlugin, JavaAppPackaging, DockerPlugin)
  .settings(
    name := "java-pingpong",
    dockerSettings,
    mainClass in Compile := Some("io.cloudstate.samples.pingpong.Main"),
    PB.generate in Compile := (PB.generate in Compile).dependsOn(PB.generate in (`java-support`, Compile)).value,
    akkaGrpcGeneratedLanguages := Seq(AkkaGrpc.Java),
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "frontend", (sourceDirectory in Compile).value / "protos")
    },
    PB.targets in Compile := Seq(
        PB.gens.java -> (sourceManaged in Compile).value
      ),
    javacOptions in Compile ++= Seq("-encoding", "UTF-8", "-source", "1.8", "-target", "1.8"),
    assemblySettings("java-pingpong.jar")
  )

lazy val `scala-shopping-cart` = (project in file("samples/scala-shopping-cart"))
  .dependsOn(`scala-support`)
  .enablePlugins(AkkaGrpcPlugin, DockerPlugin, JavaAppPackaging)
  .settings(
    name := "scala-shopping-cart",
    dockerSettings,
    PB.generate in Compile := (PB.generate in Compile).dependsOn(PB.generate in (`scala-support`, Compile)).value,
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "frontend", baseDir / "example")
    },
    assemblySettings("scala-shopping-cart.jar")
  )

lazy val `akka-client` = (project in file("samples/akka-client"))
  .enablePlugins(AkkaGrpcPlugin)
  .settings(
    common,
    name := "akka-client",
    fork in run := true,
    libraryDependencies ++= Seq(
        akkaDependency("akka-persistence"),
        akkaDependency("akka-stream"),
        akkaDependency("akka-discovery"),
        akkaHttpDependency("akka-http"),
        akkaHttpDependency("akka-http-spray-json"),
        akkaHttpDependency("akka-http-core"),
        akkaHttpDependency("akka-http2-support"),
        "com.google.protobuf" % "protobuf-java" % ProtobufVersion % "protobuf",
        "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
      ),
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "frontend", baseDir / "example")
    }
  )

lazy val `load-generator` = (project in file("samples/js-shopping-cart-load-generator"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(`akka-client`)
  .settings(
    common,
    name := "js-shopping-cart-load-generator",
    dockerSettings,
    dockerExposedPorts := Nil
  )

lazy val `tck` = (project in file("tck"))
  .enablePlugins(AkkaGrpcPlugin, JavaAppPackaging, DockerPlugin)
  .configs(IntegrationTest)
  .dependsOn(`akka-client`)
  .settings(
    Defaults.itSettings,
    common,
    name := "cloudstate-tck",
    libraryDependencies ++= Seq(
        akkaDependency("akka-stream"),
        akkaDependency("akka-discovery"),
        akkaHttpDependency("akka-http"),
        akkaHttpDependency("akka-http-spray-json"),
        "com.google.protobuf" % "protobuf-java" % ProtobufVersion % "protobuf",
        "org.scalatest" %% "scalatest" % ScalaTestVersion,
        akkaDependency("akka-testkit")
      ),
    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "protocol")
    },
    dockerSettings,
    Compile / bashScriptDefines / mainClass := Some("org.scalatest.run"),
    bashScriptExtraDefines += "addApp io.cloudstate.tck.ConfiguredCloudStateTCK",
    javaOptions in IntegrationTest := sys.props.get("config.resource").map(r => s"-Dconfig.resource=$r").toSeq,
    parallelExecution in IntegrationTest := false,
    executeTests in IntegrationTest := (executeTests in IntegrationTest)
        .dependsOn(`proxy-core` / assembly, `java-shopping-cart` / assembly, `scala-shopping-cart` / assembly)
        .value
  )

lazy val `graal-tools` = (project in file("graal-tools"))
  .enablePlugins(GraalVMPlugin)
  .settings(
    libraryDependencies ++= List(
        "org.graalvm.nativeimage" % "svm" % GraalVersion % "provided",
        // Adds configuration to let Graal Native Image (SubstrateVM) work
        "com.typesafe.akka" %% "akka-protobuf-v390" % (AkkaVersion + "-proto390") excludeAll (excludeTheseDependencies: _*),
        "com.github.vmencik" %% "graal-akka-actor" % GraalAkkaVersion,
        "com.github.vmencik" %% "graal-akka-stream" % GraalAkkaVersion,
        "com.github.vmencik" %% "graal-akka-http" % GraalAkkaVersion,
        akkaDependency("akka-actor"),
        "com.google.protobuf" % "protobuf-java" % ProtobufVersion,
        "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion
      )
  )

def doCompileK8sDescriptors(dir: File,
                            targetDir: File,
                            registry: Option[String],
                            username: Option[String],
                            tag: String,
                            streams: TaskStreams): File = {

  val targetFileName = if (tag != "latest") s"cloudstate-$tag.yaml" else "cloudstate.yaml"
  val target = targetDir / targetFileName
  val useNativeBuilds = sys.props.get("use.native.builds").forall(_ == "true")

  val files = ((dir / "crds") * "*.yaml").get ++
    (dir * "*.yaml").get.sortBy(_.getName)

  val fullDescriptor = files.map(IO.read(_)).mkString("\n---\n")

  val registryAndUsername = (registry.toSeq ++ username :+ "").mkString("/")
  val substitutedDescriptor = "cloudstateio/(cloudstate-.*):latest".r.replaceAllIn(fullDescriptor, m => {
    val artifact =
      if (useNativeBuilds) m.group(1)
      else m.group(1).replace("-native", "")
    s"$registryAndUsername$artifact:$tag"
  })

  IO.write(target, substitutedDescriptor)
  streams.log.info("Generated YAML descriptor in " + target)
  target
}
