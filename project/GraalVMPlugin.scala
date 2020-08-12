import java.io.ByteArrayInputStream

import sbt._
import sbt.Keys._
import com.typesafe.sbt.packager.Stager
import com.typesafe.sbt.packager.Compat._
import com.typesafe.sbt.packager.{Keys => NativePackagerKeys}
import com.typesafe.sbt.packager.archetypes.JavaAppPackaging
import com.typesafe.sbt.packager.docker.{Cmd, DockerPlugin, Dockerfile, ExecCmd}
import com.typesafe.sbt.packager.universal.UniversalPlugin
import com.typesafe.sbt.packager.graalvmnativeimage.GraalVMNativeImagePlugin.autoImport._

/**
 * COPIED AND ADAPTED FROM https://github.com/sbt/sbt-native-packager/pull/1251
 *
 * Plugin to compile ahead-of-time native executables.
 *
 * @example Enable the plugin in the `build.sbt`
 * {{{
 *    enablePlugins(GraalVMNativeImagePlugin)
 * }}}
 */
object GraalVMPlugin extends AutoPlugin {

  object autoImport {
    val graalVMVersion = settingKey[Option[String]](
      "Version of GraalVM to build with. Setting this has the effect of generating a container build image to build the native image with this version of Graal"
    )
    val graalVMBuildServer =
      settingKey[Boolean]("Whether to use the native image build server. Disabled for Docker builds by default.")
    val graalVMContainerBuildImage =
      taskKey[Option[String]]("Docker image to use for the container to build the native-image in.")

    val graalVMDockerBaseImage =
      taskKey[String]("Docker image to use as the base image for building the container in docker.")
    val graalVMDockerPublishLocalBuild =
      settingKey[Boolean]("Whether the native image should be built directly by docker:publishLocal")
  }

  import autoImport._
  import DockerPlugin.autoImport._
  import UniversalPlugin.autoImport._

  private val GraalVMBaseImage = "oracle/graalvm-ce"
  private val NativeImageCommand = "native-image"

  override def requires: Plugins = JavaAppPackaging && DockerPlugin

  override def projectConfigurations: Seq[Configuration] = Seq(GraalVMNativeImage)

  override lazy val buildSettings: Seq[Setting[_]] = Seq(
    graalVMDockerPublishLocalBuild := true
  )

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
      target in GraalVMNativeImage := target.value / "graalvm-native-image",
      graalVMNativeImageOptions := Seq.empty,
      graalVMVersion := None,
      graalVMBuildServer := graalVMVersion.value.isEmpty,
      graalVMContainerBuildImage := Def.taskDyn {
          graalVMVersion.value match {
            case Some(tag) => generateContainerBuildImage(s"$GraalVMBaseImage:$tag")
            case None => Def.task(None: Option[String])
          }
        }.value,
      graalVMDockerBaseImage := dockerBaseImage.value,
      resourceDirectory in GraalVMNativeImage := sourceDirectory.value / "graal",
      mainClass in GraalVMNativeImage := (mainClass in Compile).value,
      (mappings in Docker) := {
        val oldMappings = (mappings in Docker).value
        val classpathJars =
          universalDepMappings((fullClasspath in Compile).value, NativePackagerKeys.projectDependencyArtifacts.value)
        val graalResourceDirectories = (resourceDirectories in GraalVMNativeImage).value
        val graalResources = (resources in GraalVMNativeImage).value
        val targetDirectory = (target in GraalVMNativeImage).value
        val binaryName = NativePackagerKeys.executableScriptName.value
        val extraOptions = graalVMNativeImageOptions.value
        val className = (mainClass in GraalVMNativeImage).value.getOrElse(sys.error("Could not find a main class."))
        val installLocation = (NativePackagerKeys.defaultLinuxInstallLocation in Docker).value

        if (graalVMDockerPublishLocalBuild.value) {
          val resourceMappings = (graalResources --- graalResourceDirectories) pair (Path.relativeTo(
              graalResourceDirectories
            ) | Path.flat)

          val command = Seq(
              "native-image",
              "-cp",
              classpathJars.map(_._2).mkString(":"),
              s"-H:Name=$binaryName"
            ) ++ extraOptions.map(_.replace("$", "\\$")) ++ Seq(className)

          val buildSh = targetDirectory / "build.sh"
          // We write this out to a script because Docker has a maximum limit on the length of commands it can run
          IO.write(buildSh, command.mkString("", " ", "\n"))

          classpathJars.map(jar => jar._1 -> s"$installLocation/${jar._2}") ++ resourceMappings.map {
            case (resource, path) => resource -> s"$installLocation/graal-resources/$path"
          } :+ (buildSh -> s"$installLocation/build.sh")
        } else oldMappings
      },
      dockerEntrypoint := {
        val installLocation = (NativePackagerKeys.defaultLinuxInstallLocation in Docker).value
        val binaryName = NativePackagerKeys.executableScriptName.value
        val oldEntryPoint = dockerEntrypoint.value
        if (graalVMDockerPublishLocalBuild.value) {
          Seq(
            s"${(NativePackagerKeys.defaultLinuxInstallLocation in Docker).value}/${NativePackagerKeys.executableScriptName.value}"
          )
        } else oldEntryPoint
      },
      Docker / dockerGroupLayers := { // disable layers for native-image builds
        val oldDockerGroupLayers = (Docker / dockerGroupLayers).value
        if (graalVMDockerPublishLocalBuild.value) PartialFunction.empty else oldDockerGroupLayers
      },
      dockerCommands := {
        val oldCommands = dockerCommands.value
        val maybeVersion = graalVMVersion.value
        val baseImage = graalVMDockerBaseImage.value
        val installLocation = (NativePackagerKeys.defaultLinuxInstallLocation in Docker).value
        val binaryName = NativePackagerKeys.executableScriptName.value
        val entryPoint = dockerEntrypoint.value
        if (graalVMDockerPublishLocalBuild.value) {
          val buildImage = GraalVMBaseImage + ":" + maybeVersion.getOrElse(
              sys.error("Cannot build native image in docker without a GraalVM version specified")
            )

          Seq(
            Cmd("FROM", buildImage, "as", "stage-0"),
            Cmd("WORKDIR", installLocation),
            ExecCmd("RUN", "gu", "install", "native-image"),
            Cmd("COPY", installLocation.drop(1), installLocation),
            ExecCmd("RUN", "sh", "build.sh"),
            Cmd("FROM", baseImage, "as", "main-stage"),
            Cmd("WORKDIR", installLocation),
            Cmd("COPY", "--from=stage-0", s"$installLocation/$binaryName", s"$installLocation/$binaryName"),
            ExecCmd("ENTRYPOINT", entryPoint: _*)
          )
        } else oldCommands
      }
    ) ++ inConfig(GraalVMNativeImage)(scopedSettings) ++
    inConfig(Compile)(
      resourceGenerators ++= List(hocon2json.taskValue, mkDynamicAkkaReflectConfig.taskValue)
    )

  private lazy val scopedSettings = Seq[Setting[_]](
    resourceDirectories := Seq(resourceDirectory.value),
    includeFilter := "*.json",
    resources := resourceDirectories.value.descendantsExcept(includeFilter.value, excludeFilter.value).get,
    packageBin := {
      import sbt.util.CacheImplicits._
      val targetDirectory = target.value
      val binaryName = name.value
      val className = mainClass.value.getOrElse(sys.error("Could not find a main class."))
      val classpathJars =
        universalDepMappings((fullClasspath in Compile).value, NativePackagerKeys.projectDependencyArtifacts.value)
      val extraOptions = graalVMNativeImageOptions.value
      val streams = Keys.streams.value
      val dockerCommand = dockerExecCommand.value
      val graalResourceDirectories = resourceDirectories.value
      val graalResources = resources.value
      val containerBuildImg = graalVMContainerBuildImage.value
      val outputFile = targetDirectory / binaryName
      val inputFiles: Seq[File] = classpathJars.map(_._1) ++ graalResources

      def doWork: File =
        containerBuildImg match {
          case None =>
            streams.log.info("Building GraalVM native image locally, this may take some time...")
            buildLocal(targetDirectory, binaryName, className, classpathJars.map(_._1), extraOptions, streams.log)

          case Some(image) =>
            streams.log.info("Building GraalVM native image in a Docker container, this may take some time...")
            val resourceMappings = (graalResources --- graalResourceDirectories) pair (Path.relativeTo(
                graalResourceDirectories
              ) | Path.flat)

            buildInDockerContainer(
              targetDirectory,
              binaryName,
              className,
              classpathJars,
              extraOptions,
              dockerCommand,
              resourceMappings,
              image,
              streams
            )
        }

      val cachedBuild = Tracked
        .inputChanged[(File, String, String, Seq[(File, String)], Seq[String], Seq[String], Seq[ModifiedFileInfo]),
                      File](streams.cacheStoreFactory.make("graalvm-native-image")) { (inChanged, _) =>
          if (inChanged || !outputFile.exists) doWork
          else outputFile
        }
      cachedBuild((targetDirectory, binaryName, className, classpathJars, extraOptions, dockerCommand, inputFiles map {
        FileInfo.lastModified(_)
      }))
    }
  )

  private val hocon2json = Def.task {
    val dirs = List((resourceDirectory in GraalVMNativeImage).value)
    val files = dirs.descendantsExcept("*.json.conf", HiddenFileFilter).get()
    val destDir = resourceManaged.value
    val log = streams.value.log

    def toMapping(files: Iterable[File]) =
      (files --- dirs).pair(Path.relativeTo(dirs)).map {
        case (confFile, relativePath) =>
          confFile -> destDir / relativePath.stripSuffix(".conf")
      }

    FileFunction.cached(streams.value.cacheStoreFactory) { (in, _) =>
      log.info(s"Converting ${in.modified.size} HOCON files to JSON, e.g. ${in.modified.take(3).mkString(", ")}...")
      toMapping(in.modified).map {
        case (confFile, dest) =>
          import com.typesafe.config._
          import com.typesafe.config.impl.Parseable
          val parseable = Parseable.newFile(confFile, ConfigParseOptions.defaults())
          val parseValue = classOf[Parseable].getDeclaredMethod("parseValue")
          parseValue.setAccessible(true) // from lightbend/config#460#issuecomment-285662952
          val conf = parseValue.invoke(parseable).asInstanceOf[ConfigValue]
          // Not resolving (yet?) as it's only a config value, not a config (object).
          val json = conf.render(ConfigRenderOptions.concise().setFormatted(true))
          IO.write(dest, json)
          dest
      }.toSet
    }(files.toSet)

    toMapping(files).map(_._2)
  }

  private val mkDynamicAkkaReflectConfig = Def.task {
    import scala.collection.JavaConverters._
    import com.typesafe.config._
    val cp = Attributed.data(dependencyClasspath.value) ++ unmanagedResourceDirectories.value
    val cl = new java.net.URLClassLoader(cp.map(_.toURI.toURL).toArray)
    val conf = ConfigFactory.load(cl, "cloudstate-common")
    val classes = conf.getConfig("akka.actor.serializers").root.unwrapped.asScala.values
    val bindings = conf.getConfig("akka.actor.serialization-bindings").root.keySet().asScala
    val cfg1 = classes.map(name => Map("name" -> name, "allDeclaredConstructors" -> true).asJava)
    val cfg2 = bindings.map(name => Map("name" -> name).asJava)
    val list = ConfigValueFactory.fromIterable((cfg1 ++ cfg2).asJava)
    val json = list.render(ConfigRenderOptions.concise().setFormatted(true))
    val base = resourceManaged.value / "META-INF" / "native-image"
    val dest = base / "com.typesafe.akka" / "dynamic-from-reference-conf" / "reflect-config.json"
    IO.write(dest, json)
    Seq(dest)
  }

  private def buildLocal(targetDirectory: File,
                         binaryName: String,
                         className: String,
                         classpathJars: Seq[File],
                         extraOptions: Seq[String],
                         log: ProcessLogger): File = {

    targetDirectory.mkdirs()
    val command = {
      val nativeImageArguments = {
        val classpath = classpathJars.mkString(":")
        Seq("--class-path", classpath, s"-H:Name=$binaryName") ++ extraOptions ++ Seq(className)
      }
      Seq(NativeImageCommand) ++ nativeImageArguments
    }
    sys.process.Process(command, targetDirectory).run(log).exitValue() match {
      case 0 => targetDirectory / binaryName
      case x => sys.error(s"Failed to run $command, exit status: " + x)
    }
  }

  private def buildInDockerContainer(targetDirectory: File,
                                     binaryName: String,
                                     className: String,
                                     classpathJars: Seq[(File, String)],
                                     extraOptions: Seq[String],
                                     dockerCommand: Seq[String],
                                     resources: Seq[(File, String)],
                                     image: String,
                                     streams: TaskStreams): File = {

    val outputFile = targetDirectory / binaryName

    stage(targetDirectory, classpathJars, resources, streams)

    val command = dockerCommand ++ Seq(
        "run",
        "--rm",
        "-v",
        s"${targetDirectory.getAbsolutePath}:/opt/graalvm",
        image,
        "-cp",
        classpathJars.map(jar => "/opt/graalvm/stage/" + jar._2).mkString(":"),
        s"-H:Name=$binaryName"
      ) ++ extraOptions ++ Seq(className)

    sys.process.Process(command) ! streams.log match {
      case 0 => outputFile
      case x => sys.error(s"Failed to run $command, exit status: " + x)
    }
  }

  /**
   * This can be used to build a custom build image starting from a custom base image. Can be used like so:
   *
   * ```
   * (containerBuildImage in GraalVMNativeImage) := generateContainerBuildImage("my-docker-hub-username/my-graalvm").value
   * ```
   *
   * The passed in docker image must have GraalVM installed and on the PATH, including the gu utility.
   */
  def generateContainerBuildImage(baseImage: String): Def.Initialize[Task[Option[String]]] = Def.task {
    val dockerCommand = (dockerExecCommand in GraalVMNativeImage).value
    val streams = Keys.streams.value

    val (baseName, tag) = baseImage.split(":", 2) match {
      case Array(n, t) => (n, t)
      case Array(n) => (n, "latest")
    }

    val imageName = s"${baseName.replace('/', '-')}-native-image:$tag"
    import sys.process._
    if ((dockerCommand ++ Seq("image", "ls", imageName, "--quiet")).!!.trim.isEmpty) {
      streams.log.info(s"Generating new GraalVM native-image image based on $baseImage: $imageName")

      val dockerContent = Dockerfile(
        Cmd("FROM", baseImage),
        Cmd("WORKDIR", "/opt/graalvm"),
        ExecCmd("RUN", "gu", "install", "native-image"),
        ExecCmd("ENTRYPOINT", "native-image")
      ).makeContent

      val command = dockerCommand ++ Seq("build", "-t", imageName, "-")

      val ret = sys.process.Process(command) #<
        new ByteArrayInputStream(dockerContent.getBytes()) !
        publishLocalLogger(streams.log)

      if (ret != 0)
        throw new RuntimeException("Nonzero exit value when generating GraalVM container build image: " + ret)

    } else {
      streams.log.info(s"Using existing GraalVM native-image image: $imageName")
    }

    Some(imageName)
  }

  private def stage(targetDirectory: File,
                    classpathJars: Seq[(File, String)],
                    resources: Seq[(File, String)],
                    streams: TaskStreams): File = {
    val stageDir = targetDirectory / "stage"
    val mappings = classpathJars ++ resources.map {
        case (resource, path) => resource -> s"resources/$path"
      }
    Stager.stage(GraalVMBaseImage)(streams, stageDir, mappings)
  }

  // Copied from DockerPlugin
  def publishLocalLogger(log: Logger) =
    new sys.process.ProcessLogger {
      override def err(err: => String): Unit =
        err match {
          case s if s.startsWith("Uploading context") =>
            log.debug(s) // pre-1.0
          case s if s.startsWith("Sending build context") =>
            log.debug(s) // 1.0
          case s if !s.trim.isEmpty => log.error(s)
          case s =>
        }

      override def out(inf: => String): Unit = inf match {
        case s if !s.trim.isEmpty => log.info(s)
        case s =>
      }

      override def buffer[T](f: => T): T = f
    }

  // Copied from JavaAppPackaging
  private def universalDepMappings(deps: Seq[Attributed[File]],
                                   projectArts: Seq[Attributed[File]]): Seq[(File, String)] =
    for {
      dep <- deps
      realDep <- findRealDep(dep, projectArts)
    } yield realDep.data -> ("lib/" + getJarFullFilename(realDep))

  // Copied from JavaAppPackaging
  private def findRealDep(dep: Attributed[File], projectArts: Seq[Attributed[File]]): Option[Attributed[File]] =
    if (dep.data.isFile) Some(dep)
    else {
      projectArts.find { art =>
        art.get(sbt.Keys.artifact.key).zip(dep.get(sbt.Keys.artifact.key)) exists {
          case (l, r) => l.name == r.name && l.classifier == r.classifier
        }
      }
    }

  // Copied from JavaAppPackaging
  private def getJarFullFilename(dep: Attributed[File]): String = {
    val filename: Option[String] = for {
      module <- dep.metadata
      // sbt 0.13.x key
        .get(AttributeKey[ModuleID]("module-id"))
        // sbt 1.x key
        .orElse(dep.metadata.get(AttributeKey[ModuleID]("moduleID")))
      artifact <- dep.metadata.get(AttributeKey[Artifact]("artifact"))
    } yield makeJarName(module.organization, module.name, module.revision, artifact.name, artifact.classifier)
    filename.getOrElse(dep.data.getName)
  }

  // Copied from JavaAppPackaging
  def makeJarName(org: String,
                  name: String,
                  revision: String,
                  artifactName: String,
                  artifactClassifier: Option[String]): String =
    org + "." +
    name + "-" +
    Option(artifactName.replace(name, "")).filterNot(_.isEmpty).map(_ + "-").getOrElse("") +
    revision +
    artifactClassifier.filterNot(_.isEmpty).map("-" + _).getOrElse("") +
    ".jar"

}
