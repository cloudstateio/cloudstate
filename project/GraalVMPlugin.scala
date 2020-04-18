import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64

import sbt._
import sbt.Keys._
import com.typesafe.sbt.packager.Stager
import com.typesafe.sbt.packager.Keys._
import com.typesafe.sbt.packager.Compat._
import com.typesafe.sbt.packager.archetypes.JavaAppPackaging
import com.typesafe.sbt.packager.docker.{Cmd, Dockerfile, ExecCmd}
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
    val graalVMContainerBuildImage =
      taskKey[Option[String]]("Docker image to use for the container to build the native-image in.")

    val splitByJar = inputKey[Seq[File]]("")
  }

  import autoImport._

  private val GraalVMBaseImage = "oracle/graalvm-ce"
  private val NativeImageCommand = "native-image"

  override def requires: Plugins = JavaAppPackaging

  override def projectConfigurations: Seq[Configuration] = Seq(GraalVMNativeImage)

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
      target in GraalVMNativeImage := target.value / "graalvm-native-image",
      graalVMNativeImageOptions := Seq.empty,
      graalVMVersion := None,
      resourceDirectory in GraalVMNativeImage := sourceDirectory.value / "graal",
      mainClass in GraalVMNativeImage := (mainClass in Compile).value,
      splitByJar := splitByJarImpl.evaluated,
    ) ++ inConfig(GraalVMNativeImage)(scopedSettings) ++
    inConfig(Compile)(resourceGenerators += hocon2json)

  private lazy val scopedSettings = Seq[Setting[_]](
    resourceDirectories := Seq(resourceDirectory.value),
    includeFilter := "*.json",
    resources := resourceDirectories.value.descendantsExcept(includeFilter.value, excludeFilter.value).get,
    graalVMContainerBuildImage := Def.taskDyn {
        graalVMVersion.value match {
          case Some(tag) => generateContainerBuildImage(s"$GraalVMBaseImage:$tag")
          case None => Def.task(None: Option[String])
        }
      }.value,
    packageBin := {
      import sbt.util.CacheImplicits._
      val targetDirectory = target.value
      val binaryName = name.value
      val className = mainClass.value.getOrElse(sys.error("Could not find a main class."))
      val classpathJars = universalDepMappings((fullClasspath in Compile).value, projectDependencyArtifacts.value)
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
    (files --- dirs).pair(Path.relativeTo(dirs)).map {
      case (confFile, relativePath) =>
        import com.typesafe.config._
        import com.typesafe.config.impl.Parseable
        val parseable = Parseable.newFile(confFile, ConfigParseOptions.defaults())
        val parseValue = classOf[Parseable].getDeclaredMethod("parseValue")
        parseValue.setAccessible(true) // from lightbend/config#460#issuecomment-285662952
        val conf = parseValue.invoke(parseable).asInstanceOf[ConfigValue]
        // Not resolving (yet?) as it's only a config value, not a config (object).
        val json = conf.render(ConfigRenderOptions.concise().setFormatted(true))
        val dest = destDir / relativePath.stripSuffix(".conf")
        IO.write(dest, json)
        dest
    }
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
  private def publishLocalLogger(log: Logger) =
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
        (art.get(sbt.Keys.artifact.key), dep.get(sbt.Keys.artifact.key)) match {
          case (Some(l), Some(r)) =>
            l.name == r.name && l.classifier == r.classifier
          case _ => false
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

  val splitByJarImpl: Def.Initialize[InputTask[Seq[File]]] = Def.inputTask {
    import sbt.complete._
    import scala.collection.JavaConverters._
    import com.typesafe.config._
    val files = Parsers.spaceDelimited("<file(s)...>").parsed.map(file(_))
    val resDir = (Compile / resourceDirectory).value
    val cp1 = (LocalProject("proxy-core") / Compile / fullClasspath).value
    val cp2 = (LocalProject("proxy-cassandra") / Compile / fullClasspath).value
    val cp3 = (LocalProject("proxy-postgres") / Compile / fullClasspath).value
    val cp = (cp1 ++ cp2 ++ cp3).distinct

    def abort(s: String) = throw new MessageOnlyException(s)

    val classLoaderToModuleId = cp.map { attributedJar =>
      val moduleId = attributedJar.get(Keys.moduleID.key).get
      val gid = moduleId.organization
      val aid = moduleId.name
      val jar = attributedJar.data
      val parent = null //  ClassLoader.getSystemClassLoader
      val cl = new java.net.URLClassLoader(s"CL-for-$jar", Array(jar.toURI.toURL), parent)
      cl -> (gid, aid)
    }

    def loadCl(name: String) = classLoaderToModuleId.flatMap { case (cl, coords) =>
      try {
        cl.loadClass(name)
        Some(coords)
      }
      catch {
        case _: ClassNotFoundException => Nil
        case _: NoClassDefFoundError   => Some(coords) // found but no definition found (wat)
      }
    }.distinct match {
      case List(coords) => coords
      case Nil          => abort(s"Unexpected name $name")
      case coordss      => abort(s"Name $name found in multiple jars: $coordss")
    }

    val res = files.flatMap { f =>
      val configValue = parseConfigValue(f)

      val configListJ = configValue.atPath("root").getList("root")
      val configList  = configListJ.asScala.toList

      val configList2 = configList.map {
        case x: ConfigObject => x
        case x               => abort(s"Expected class ConfigObject (${x.getClass})")
      }

      val map = configList2.groupBy { obj =>
        val name = obj.get("name").unwrapped() match {
          case s: String => s
          case x         => abort(s"Expected name: String (${x.getClass})")
        }

        name match {
          case "float" | "int" | "long" | "short" | "void"  => "net.java.openjdk"  -> "base"
          case s if s.startsWith("[")                       => "net.java.openjdk"  -> "base"
          case s if s.startsWith("java.")                   => "net.java.openjdk"  -> "base"
          case s if s.startsWith("sun.")                    => "net.java.openjdk"  -> "base"
          case "akka.cluster.AutoDown"                      => "com.typesafe.akka" -> "akka-cluster"
          case "akka.cluster.AutoDownBase"                  => "com.typesafe.akka" -> "akka-cluster"
          case "akka.cluster.AutoDowning"                   => "com.typesafe.akka" -> "akka-cluster"
          case s if s.startsWith("akka.dispatch.forkjoin.") => "com.typesafe.akka" -> "akka-actor"
          case "akka.stream.impl.ActorRefSinkActor"         => "com.typesafe.akka" -> "akka-stream"
          case _ => loadCl(name)
        }
      }

      val renderOpts = ConfigRenderOptions.defaults().setComments(false).setOriginComments(false)

      map.map { case ((gid, aid0), classes) =>
        val aid = aid0.stripSuffix("_2.12")
        val dest = resDir / s"META-INF/native-image/$gid/$aid/reflect-config.json.conf"
        val list = ConfigValueFactory.fromIterable(classes.asJava)
        val json = list.render(renderOpts)
        IO.write(dest, json, append = true)
        dest
      }
    }

    res
  }

  private def parseConfigValue(f: File) = {
    import com.typesafe.config._
    import com.typesafe.config.impl.Parseable
    val parseable = Parseable.newFile(f, ConfigParseOptions.defaults())
    val parseValue = classOf[Parseable].getDeclaredMethod("parseValue")
    parseValue.setAccessible(true) // from lightbend/config#460#issuecomment-285662952
    val res = parseValue.invoke(parseable).asInstanceOf[ConfigValue]
    // Not resolving (yet?) as it's only a config value, not a config (object).
    res
  }
}
