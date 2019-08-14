import java.util.Date

import com.typesafe.sbt.packager.docker.DockerChmodType

inThisBuild(Seq(
  organization := "io.cloudstate",
  version := dynverGitDescribeOutput.value.mkVersion(versionFmt, "latest"),
  dynver := sbtdynver.DynVer.getGitDescribeOutput(new Date).mkVersion(versionFmt, "latest"),

  scalaVersion := "2.12.9",

  // Needed for our fork of skuber
  resolvers += Resolver.bintrayRepo("jroper", "maven"), // TODO: Remove once skuber has the required functionality

  organizationName := "Lightbend Inc.",
  startYear := Some(2019),
  licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt"))
))

// Make sure the version doesn't change each time it gets built, this ensures we don't rebuild the native image
// every time we build a docker image based on it, since we actually build 3 different docker images for the proxy
// command.
def versionFmt(out: sbtdynver.GitDescribeOutput): String = {
  val dirtySuffix = if (out.isDirty()) "-dev" else ""
  if (out.isCleanAfterTag) out.ref.dropV.value
  else out.ref.dropV.value + out.commitSuffix.mkString("-", "-", "") + dirtySuffix
}

name := "cloudstate"

val GrpcJavaVersion                 = "1.22.1"
val GraalAkkaVersion                = "0.4.1"
val AkkaVersion                     = "2.5.23-jroper-rebalance-fix-1"
val AkkaHttpVersion                 = "10.1.9"
val AkkaManagementVersion           = "1.0.1"
val AkkaPersistenceCassandraVersion = "0.96"
val PrometheusClientVersion         = "0.6.0"
val ScalaTestVersion                = "3.0.5"
val ProtobufVersion                 = "3.9.0"

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

  excludeFilter in headerResources := HiddenFileFilter || GlobFilter("reflection.proto")
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
  .aggregate(`proxy-core`, `proxy-cassandra`, `java-support`, `java-shopping-cart`,`akka-client`, operator, `tck`)
  .settings(common)

lazy val proxyDockerBuild = settingKey[Option[(String, String)]]("Docker artifact name and configuration file which gets overridden by the buildProxy command")

def dockerSettings: Seq[Setting[_]] = Seq(
  proxyDockerBuild := None,
  
  dockerUpdateLatest := true,
  dockerRepository := sys.props.get("docker.registry"),
  dockerUsername := sys.props.get("docker.username"),
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
    // So basically, by default we *just* publish latest, but if -Ddocker.tag.version is passed,
    // we publish both latest and a tag for the version.
    if (!sys.props.get("docker.tag.version").forall(_ == "false")) {
      old
    } else {
      Seq(single.withTag(Some("latest")))
    }
  },
)

def buildProxyHelp(commandName: String, name: String) =
  Help((s"$commandName <task>", s"Execute the given docker scoped task (eg, publishLocal or publish) for the the $name build of the proxy."))

def buildProxyCommand(commandName: String, project: => Project, name: String, configResource: String): Command =
  Command.single(
    commandName, 
    buildProxyHelp(commandName, name)
  ) { (state, command) =>
    List(
      s"project ${project.id}",
      s"""set proxyDockerBuild := Some(("cloudstate-proxy-$name", "$configResource"))""",
      s"docker:$command",
      "set proxyDockerBuild := None",
      "project root"
    ) ::: state
  }

def dockerBuildCassandraCommand = 
  Command.single("dockerBuildCassandra", buildProxyHelp("dockerBuildCassandra", "cassandra")) { (state, command) =>
    s"proxy-cassandra/docker:$command" :: state
  }

commands ++= Seq(
  buildProxyCommand("dockerBuildDevMode", `proxy-core`, "dev-mode", "dev-mode.conf"),
  buildProxyCommand("dockerBuildNoJournal", `proxy-core`, "no-journal", "no-journal.conf"),
  buildProxyCommand("dockerBuildInMemory", `proxy-core`, "in-memory", "in-memory.conf"),
  dockerBuildCassandraCommand
)

// Shared settings for native image and docker builds
def nativeImageDockerSettings: Seq[Setting[_]] = dockerSettings ++ Seq(
  graalVMVersion := Some("19.1.1"),
  graalVMNativeImageOptions ++= sharedNativeImageSettings,

  (mappings in Universal) := Seq(
    (packageBin in GraalVMNativeImage).value -> s"bin/${executableScriptName.value}"
  ),
  dockerBaseImage := "bitnami/java:11-prod",
  // Need to make sure it has group execute permission
  dockerChmodType := DockerChmodType.Custom("u+x,g+x"),
  dockerEntrypoint := {
    val old = dockerEntrypoint.value
    val withLibraryPath = old :+ "-Djava.library.path=/opt/bitnami/java/lib"
    proxyDockerBuild.value match {
      case Some((_, configResource)) => withLibraryPath :+ s"-Dconfig.resource=$configResource"
      case None => withLibraryPath
    }
  }
)

def sharedNativeImageSettings = Seq(
      //"-O1", // Optimization level
      "-H:ResourceConfigurationFiles=/opt/graalvm/stage/resources/resource-config.json",
      "-H:ReflectionConfigurationFiles=/opt/graalvm/stage/resources/reflect-config.json",
      "-H:DynamicProxyConfigurationFiles=/opt/graalvm/stage/resources/proxy-config.json",
      "-H:IncludeResources=.+\\.conf",
      "-H:IncludeResources=.+\\.properties",
      "-H:+AllowVMInspection",
      "-H:-RuntimeAssertions",
      "-H:+ReportExceptionStackTraces",
      "-H:-PrintUniverse", // if "+" prints out all classes which are included
      "-H:-NativeArchitecture", // if "+" Compiles the native image to customize to the local CPU arch
      "-H:Class=" + "io.cloudstate.proxy.CloudStateProxyMain",
      "--verbose",
      //"--no-server", // Uncomment to not use the native-image build server, to avoid potential cache problems with builds
      //"--report-unsupported-elements-at-runtime", // Hopefully a self-explanatory flag
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
        ).mkString("=",",","")
      ,
      "--initialize-at-run-time=" +
        Seq(
          "akka.protobuf.DescriptorProtos",

          // We want to delay initialization of these to load the config at runtime
          "com.typesafe.config.impl.ConfigImpl$EnvVariablesHolder",
          "com.typesafe.config.impl.ConfigImpl$SystemPropertiesHolder",

          // These are to make up for the lack of shaded configuration for svm/native-image in grpc-netty-shaded
          "com.sun.jndi.dns.DnsClient",
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
          "io.grpc.netty.shaded.io.netty.channel.socket.nio.NioSocketChannel",
        ).mkString(",")
      )

lazy val `proxy-core` = (project in file("proxy/core"))
  .enablePlugins(DockerPlugin, AkkaGrpcPlugin, JavaAgent, AssemblyPlugin, GraalVMPlugin)
  .settings(
    common,
    name := "cloudstate-proxy-core",
    libraryDependencies ++= Seq(
      // Remove these explicit gRPC/netty dependencies once akka-grpc 0.7.1 is released and we've upgraded to using that
      "io.grpc"                       % "grpc-core"                          % GrpcJavaVersion,
      "io.grpc"                       % "grpc-netty-shaded"                  % GrpcJavaVersion,

      // Since we exclude Aeron, we also exclude its transitive Agrona dependency, so we need to manually add it HERE
      "org.agrona"                    % "agrona"                             % "0.9.29",

      // FIXME REMOVE THIS ONCE WE CAN HAVE OUR DEPS (grpc-netty-shaded, agrona, and protobuf-java respectively) DO THIS PROPERLY
      "org.graalvm.sdk"               % "graal-sdk"                          % "19.1.1" % "provided", // Only needed for compilation
      "com.oracle.substratevm"        % "svm"                                % "19.1.1" % "provided", // Only needed for compilation

      // Adds configuration to let Graal Native Image (SubstrateVM) work
      "com.github.vmencik"           %% "graal-akka-actor"                   % GraalAkkaVersion % "provided", // Only needed for compilation
      "com.github.vmencik"           %% "graal-akka-stream"                  % GraalAkkaVersion % "provided", // Only needed for compilation
      "com.github.vmencik"           %% "graal-akka-http"                    % GraalAkkaVersion % "provided", // Only needed for compilation

      "com.typesafe.akka"             %% "akka-remote"                       % AkkaVersion excludeAll(
        ExclusionRule("io.netty", "netty"), // grpc-java is using grpc-netty-shaded
        ExclusionRule("io.aeron"),          // we're using Artery-TCP
        ExclusionRule("org.agrona"),        // and we don't need this either
      ),
      "com.typesafe.akka"             %% "akka-persistence"                  % AkkaVersion,
      "com.typesafe.akka"             %% "akka-persistence-query"            % AkkaVersion,
      "com.typesafe.akka"             %% "akka-stream"                       % AkkaVersion,
      "com.typesafe.akka"             %% "akka-slf4j"                        % AkkaVersion,
      "com.typesafe.akka"             %% "akka-http"                         % AkkaHttpVersion,
      "com.typesafe.akka"             %% "akka-http-spray-json"              % AkkaHttpVersion,
      "com.typesafe.akka"             %% "akka-http-core"                    % AkkaHttpVersion,
      "com.typesafe.akka"             %% "akka-http2-support"                % AkkaHttpVersion,
      "com.typesafe.akka"             %% "akka-cluster-sharding"             % AkkaVersion exclude("org.lmdbjava", "lmdbjava"),
      "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % AkkaManagementVersion excludeAll(
        ExclusionRule("io.netty", "netty"), // grpc-java is using grpc-netty-shaded
        ExclusionRule("io.aeron"),          // we're using Artery-TCP
        ExclusionRule("org.agrona"),        // and we don't need this either
      ),
      "com.lightbend.akka.discovery"  %% "akka-discovery-kubernetes-api"     % AkkaManagementVersion excludeAll(
        ExclusionRule("io.netty", "netty"), // grpc-java is using grpc-netty-shaded
        ExclusionRule("io.aeron"),          // we're using Artery-TCP
        ExclusionRule("org.agrona"),        // and we don't need this either
      ),
      "com.google.protobuf"            % "protobuf-java"                     % ProtobufVersion % "protobuf",
      "com.google.protobuf"            % "protobuf-java-util"                % ProtobufVersion,

      "org.scalatest"                 %% "scalatest"                         % ScalaTestVersion % Test,
      "com.typesafe.akka"             %% "akka-testkit"                      % AkkaVersion % Test,
      "com.typesafe.akka"             %% "akka-stream-testkit"               % AkkaVersion % Test,
      "com.typesafe.akka"             %% "akka-http-testkit"                 % AkkaHttpVersion % Test,
      "com.thesamet.scalapb"          %% "scalapb-runtime"                   % scalapb.compiler.Version.scalapbVersion % "protobuf",
      "io.prometheus"                  % "simpleclient"                      % PrometheusClientVersion,
      "io.prometheus"                  % "simpleclient_common"               % PrometheusClientVersion,
      "org.slf4j"                      % "slf4j-simple"                      % "1.7.26"
      //"ch.qos.logback"                 % "logback-classic"                   % "1.2.3", // Doesn't work well with SubstrateVM: https://github.com/vmencik/akka-graal-native/blob/master/README.md#logging
    ),

    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "proxy", baseDir / "frontend", (sourceDirectory in Compile).value / "protos")
    },

    // This adds the test/protos dir and enables the ProtocPlugin to generate protos in the Test scope
    inConfig(Test)(
      sbtprotoc.ProtocPlugin.protobufConfigSettings ++ Seq(
        PB.protoSources ++= Seq(sourceDirectory.value / "protos"),
        akkaGrpcGeneratedSources := Seq(AkkaGrpc.Server, AkkaGrpc.Client),
      )
    ),

    javaAgents += "org.mortbay.jetty.alpn" % "jetty-alpn-agent" % "2.0.9" % "runtime;test",

    dockerSettings,

    fork in run := true,

    // In memory journal by default
    javaOptions in run ++= Seq("-Dcloudstate.proxy.dev-mode-enabled=true"),

    mainClass in assembly := Some("io.cloudstate.proxy.CloudStateProxyMain"),
    assemblyJarName in assembly := "akka-proxy.jar",
    test in assembly := {},
    // logLevel in assembly := Level.Debug,
    assemblyMergeStrategy in assembly := {
      /*ADD CUSTOMIZATIONS HERE*/
      case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.last
      case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
    },

    nativeImageDockerSettings
  )

lazy val `proxy-cassandra` = (project in file("proxy/cassandra"))
  .enablePlugins(DockerPlugin, JavaAgent, GraalVMPlugin)
  .dependsOn(`proxy-core`)
  .settings(
    common,
    name := "cloudstate-proxy-cassandra",
    libraryDependencies ++= Seq(
      "com.typesafe.akka"            %% "akka-persistence-cassandra"        % AkkaPersistenceCassandraVersion excludeAll(
        ExclusionRule("io.aeron"),   // we're using Artery-TCP
        ExclusionRule("org.agrona"), // and we don't need this either
        ExclusionRule("com.github.jnr"), // Can't native-image this, so we don't need this either
      ),
      "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % AkkaPersistenceCassandraVersion % Test,

      // FIXME REMOVE THIS ONCE WE CAN HAVE OUR DEPS (grpc-netty-shaded, agrona, and protobuf-java respectively) DO THIS PROPERLY
      "org.graalvm.sdk"               % "graal-sdk"                          % "19.1.1" % "provided", // Only needed for compilation
      "com.oracle.substratevm"        % "svm"                                % "19.1.1" % "provided", // Only needed for compilation

      // Adds configuration to let Graal Native Image (SubstrateVM) work
      "com.github.vmencik"           %% "graal-akka-actor"                   % GraalAkkaVersion % "provided", // Only needed for compilation
      "com.github.vmencik"           %% "graal-akka-stream"                  % GraalAkkaVersion % "provided", // Only needed for compilation
      "com.github.vmencik"           %% "graal-akka-http"                    % GraalAkkaVersion % "provided", // Only needed for compilation
    ),
    
    fork in run := true,
    mainClass in Compile := Some("io.cloudstate.proxy.CloudStateProxyMain"),

    nativeImageDockerSettings,
    graalVMNativeImageOptions ++= Seq(
      "-H:IncludeResourceBundles=com.datastax.driver.core.Driver",
    )
  )

lazy val `proxy-tests` = (project in file("proxy/proxy-tests"))
  .dependsOn(`proxy-core`, `akka-client`)
  .settings(
    common,
    name := "cloudstate-proxy-tests",
    fork in Test := true,
    baseDirectory in Test := (baseDirectory in ThisBuild).value,
    libraryDependencies ++= Seq(
      "org.scalatest"     %% "scalatest"    % ScalaTestVersion % Test,
      "com.typesafe.akka" %% "akka-testkit" % AkkaVersion % Test,
    )
  )

val compileK8sDescriptors = taskKey[File]("Compile the K8s descriptors into one")

lazy val operator = (project in file("operator"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    common,
    name := "cloudstate-operator",
    // This is a publishLocal build of this PR https://github.com/doriordan/skuber/pull/268
    libraryDependencies ++= Seq(
      "com.typesafe.akka"  %% "akka-stream"     % AkkaVersion,
      "com.typesafe.akka"  %% "akka-slf4j"      % AkkaVersion,
      "com.typesafe.akka"  %% "akka-http"       % AkkaHttpVersion,
      "io.skuber"          %% "skuber"          % "2.2.0-jroper-1",
      "ch.qos.logback"      % "logback-classic" % "1.2.3" // Doesn't work well with SubstrateVM, use "org.slf4j"           % "slf4j-simple"     % "1.7.26" instead

    ),

    dockerSettings,
    dockerBaseImage := "adoptopenjdk/openjdk8",
    dockerExposedPorts := Nil,
    compileK8sDescriptors := doCompileK8sDescriptors(
      baseDirectory.value / "deploy",
      baseDirectory.value / "cloudstate.yaml",
      dockerRepository.value,
      dockerUsername.value,
      version.value
    )
  )

lazy val `java-support` = (project in file("java-support"))
  .enablePlugins(AkkaGrpcPlugin)
  .settings(
    name := "java-support",

    libraryDependencies ++= Seq(
      // Remove these explicit gRPC/netty dependencies once akka-grpc 0.7.1 is released and we've upgraded to using that
      "io.grpc"                       % "grpc-core"                          % GrpcJavaVersion,
      "io.grpc"                       % "grpc-netty-shaded"                  % GrpcJavaVersion,

      "com.typesafe.akka"             %% "akka-stream"                       % AkkaVersion,
      "com.typesafe.akka"             %% "akka-slf4j"                        % AkkaVersion,
      "com.typesafe.akka"             %% "akka-http"                         % AkkaHttpVersion,
      "com.typesafe.akka"             %% "akka-http-spray-json"              % AkkaHttpVersion,
      "com.typesafe.akka"             %% "akka-http-core"                    % AkkaHttpVersion,
      "com.typesafe.akka"             %% "akka-http2-support"                % AkkaHttpVersion,
      "com.google.protobuf"            % "protobuf-java"                     % ProtobufVersion % "protobuf",
      "com.google.protobuf"            % "protobuf-java-util"                % ProtobufVersion,

      "org.scalatest"                 %% "scalatest"                         % ScalaTestVersion % Test,
      "com.typesafe.akka"             %% "akka-testkit"                      % AkkaVersion % Test,
      "com.typesafe.akka"             %% "akka-stream-testkit"               % AkkaVersion % Test,
      "com.typesafe.akka"             %% "akka-http-testkit"                 % AkkaHttpVersion % Test,
      "com.thesamet.scalapb"          %% "scalapb-runtime"                   % scalapb.compiler.Version.scalapbVersion % "protobuf",
      "org.slf4j"                      % "slf4j-simple"                      % "1.7.26"
    ),

    javacOptions in Compile ++= Seq("-encoding", "UTF-8"),

    akkaGrpcGeneratedSources in Compile := Seq(AkkaGrpc.Server),
    akkaGrpcGeneratedLanguages in Compile := Seq(AkkaGrpc.Scala), // FIXME should be Java, but here be dragons

    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "frontend")
    },
  )

lazy val `java-shopping-cart` = (project in file("samples/java-shopping-cart"))
  .dependsOn(`java-support`)
  .enablePlugins(AkkaGrpcPlugin)
  .settings(
    name := "java-shopping-cart",

    akkaGrpcGeneratedLanguages := Seq(AkkaGrpc.Java),

    /*PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "example", (sourceDirectory in Compile).value / "protos")
    },*/

    javacOptions in Compile ++= Seq("-encoding", "UTF-8"),
  )

lazy val `akka-client` = (project in file("samples/akka-client"))
  .enablePlugins(AkkaGrpcPlugin)
  .settings(
    common,
    name := "akka-client",

    fork in run := true,

    libraryDependencies ++= Seq(
      // Remove these explicit gRPC/netty dependencies once akka-grpc 0.7.1 is released and we've upgraded to using that
      "io.grpc"               % "grpc-netty-shaded"    % GrpcJavaVersion,
      "io.grpc"               % "grpc-core"            % GrpcJavaVersion,
      "com.typesafe.akka"    %% "akka-persistence"     % AkkaVersion,
      "com.typesafe.akka"    %% "akka-stream"          % AkkaVersion,
      "com.typesafe.akka"    %% "akka-http"            % AkkaHttpVersion,
      "com.typesafe.akka"    %% "akka-http-spray-json" % AkkaHttpVersion,
      "com.typesafe.akka"    %% "akka-http-core"       % AkkaHttpVersion,
      "com.typesafe.akka"    %% "akka-http2-support"   % AkkaHttpVersion,
      "com.typesafe.akka"    %% "akka-parsing"         % AkkaVersion,
      "com.google.protobuf"   % "protobuf-java"        % ProtobufVersion % "protobuf",
      "com.thesamet.scalapb" %% "scalapb-runtime"      % scalapb.compiler.Version.scalapbVersion % "protobuf"
    ),

    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "frontend", baseDir / "example")
    },
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
  .enablePlugins(AkkaGrpcPlugin)
  .dependsOn(`akka-client`)
  .settings(
    common,

    name := "tck",

    libraryDependencies ++= Seq(
      // Remove these explicit gRPC/netty dependencies once akka-grpc 0.7.1 is released and we've upgraded to using that
      "io.grpc"             % "grpc-netty-shaded"    % GrpcJavaVersion,
      "io.grpc"             % "grpc-core"            % GrpcJavaVersion,
      "com.typesafe.akka"  %% "akka-stream"          % AkkaVersion,
      "com.typesafe.akka"  %% "akka-http"            % AkkaHttpVersion,
      "com.typesafe.akka"  %% "akka-http-spray-json" % AkkaHttpVersion,
      "com.google.protobuf" % "protobuf-java"        % ProtobufVersion % "protobuf",
      "org.scalatest"      %% "scalatest"            % ScalaTestVersion,
      "com.typesafe.akka"  %% "akka-testkit"         % AkkaVersion
    ),

    PB.protoSources in Compile ++= {
      val baseDir = (baseDirectory in ThisBuild).value / "protocols"
      Seq(baseDir / "proxy")
    },

    fork in test := false,

    parallelExecution in Test := false,

    executeTests in Test := (executeTests in Test).dependsOn(`proxy-core`/assembly).value
  )

def doCompileK8sDescriptors(dir: File, target: File, registry: Option[String], username: Option[String], version: String): File = {
  val files = ((dir / "crds") * "*.yaml").get ++
    (dir * "*.yaml").get.sortBy(_.getName)

  val fullDescriptor = files.map(IO.read(_)).mkString("\n---\n")

  val user = username.getOrElse("cloudstateio")
  val registryAndUsername = registry.fold(user)(r => s"$r/$user")
  val substitutedDescriptor = fullDescriptor.replaceAll(
    "image: cloudstateio/(.*):latest", 
    s"image: $registryAndUsername/$$1:$version"
  )

  IO.write(target, substitutedDescriptor)
  target
}
