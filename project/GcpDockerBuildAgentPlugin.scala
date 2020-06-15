import com.typesafe.sbt.packager.docker.DockerPlugin
import sbt.Keys._
import sbt._

import scala.sys.process._

object GcpDockerBuildAgentPlugin extends AutoPlugin {

  object autoImport {
    val gcpAgentInstanceName = settingKey[String]("The name of the instance to create")
    val gcpAgentMachineType = settingKey[String]("The type of machine to create")
    val gcpAgentPreemptible = settingKey[Boolean]("Whether preemptible instances should be used")
    val gcpAgentZone = settingKey[Option[String]]("The agent zone, if not using the default configured zone.")
    val gcpAgentProject = settingKey[Option[String]]("The agent project, if not using the default configured project.")
    val gcpAgentRegistryPort = settingKey[Int]("The port to start a Docker registry on")
    val gcpAgentPort = settingKey[Int]("The port to listen on locally")
    val gcpAgentManaged = settingKey[Boolean]("Whether the agent should be started/stopped by this sbt process")

    val gcpAgentEnabled = settingKey[Boolean]("Whether the gcp agent is enabled or not")

    val gcpAgentEnsureReady =
      taskKey[Unit]("Task that is run before all Docker execution tasks to ensure the agent is ready")

    val publishLocalPull = taskKey[Unit]("Do a publish local, then pull the image back.")
  }

  import autoImport._
  import DockerPlugin.autoImport._

  override def requires: Plugins = DockerPlugin

  override def trigger = allRequirements

  override val buildSettings: Seq[Setting[_]] = Seq(
    gcpAgentInstanceName := sys.env.getOrElse(
        "GCP_AGENT_INSTANCE_NAME",
        sys.env.get("USER").orElse(sys.env.get("HOST")).getOrElse("anonymous") + "-gcp-build-agent"
      ),
    gcpAgentMachineType := sys.env.getOrElse("GCP_AGENT_MACHINE_TYPE", "n2-custom-24-32768"),
    gcpAgentPreemptible := sys.env.getOrElse("GCP_AGENT_PREEMPTIBLE", "true").toBoolean,
    gcpAgentProject := sys.env.get("GCP_AGENT_PROJECT"),
    gcpAgentZone := sys.env.get("GCP_AGENT_ZONE"),
    gcpAgentRegistryPort := sys.env.getOrElse("GCP_AGENT_REGISTRY_PORT", "12377").toInt,
    gcpAgentPort := sys.env.getOrElse("GCP_AGENT_PORT", "12376").toInt,
    gcpAgentManaged := sys.env.getOrElse("GCP_AGENT_MANAGED", "true").toBoolean,
    gcpAgentEnsureReady := {
      val enabled = gcpAgentEnabled.value
      val instanceName = gcpAgentInstanceName.value
      val machineType = gcpAgentMachineType.value
      val preemptible = gcpAgentPreemptible.value
      val project = gcpAgentProject.value
      val zone = gcpAgentZone.value
      val registryPort = gcpAgentRegistryPort.value
      val localPort = gcpAgentPort.value
      val managed = gcpAgentManaged.value
      val log = streams.value.log

      if (enabled) {
        if (managed) {
          ensureAgentStarted(instanceName, machineType, project, zone, localPort, registryPort, preemptible, log)
        }
        ensurePortForwardStarted(instanceName, project, zone, localPort, registryPort, log)
      }
    },
    gcpAgentEnabled := sys.env.getOrElse("GCP_AGENT_ENABLED", "false").toBoolean,
    commands ++= Seq(startBuildAgentCommand,
                     stopBuildAgentCommand,
                     startPortForwardingCommand,
                     agentStatusCommand,
                     resetAgentCommand)
  )

  override val projectSettings: Seq[Setting[_]] = Seq(
    dockerExecCommand := {
      val oldCommand = dockerExecCommand.value
      val port = gcpAgentPort.value
      if (gcpAgentEnabled.value) {
        oldCommand ++ Seq("-H", s"tcp://localhost:$port")
      } else {
        oldCommand
      }
    },
    // --stream is an experimental feature that requires dockerd to be started with --experimental,
    // we configure dockerd on the build agent to have this because it means repeat builds on the
    // same agent only requires sending the changed build files, but if we're not building with the
    // build agent, it's likely that the instance of docker we're using won't support it so, we
    // don't want it set.
    dockerBuildOptions ++= { if (gcpAgentEnabled.value) Seq("--stream") else Nil },
    publishLocal in Docker := (publishLocal in Docker).dependsOn(gcpAgentEnsureReady).value,
    publish in Docker := (publish in Docker).dependsOn(gcpAgentEnsureReady).value,
    clean in Docker := (clean in Docker).dependsOn(gcpAgentEnsureReady).value,
    dockerVersion := dockerVersion.dependsOn(gcpAgentEnsureReady).value,
    publishLocalPull in Docker := {
      val _ = (publishLocal in Docker).value
      val log = streams.value.log
      val aliases = dockerAliases.value
      val alias = aliases.head
      val registryAlias = alias.withRegistryHost(Some(s"localhost:${gcpAgentRegistryPort.value}"))

      // Publish to local running registry, first have to tag
      run(dockerExecCommand.value ++ Seq("tag", alias.toString, registryAlias.toString), log)
      log.info("Pushing image to local registry on agent...")
      run(dockerExecCommand.value ++ Seq("push", registryAlias.toString), GraalVMPlugin.publishLocalLogger(log))

      log.info("Pulling image from local registry on agent to local docker daemon...")
      // Now pull to local docker, we can't use dockerExecCommand because it's already been overridden to include
      // out -H flag, but we explicitly want to run this against the local docker daemon.
      run(Seq("docker", "pull", registryAlias.toString), GraalVMPlugin.publishLocalLogger(log))
      // And now tag it according to dockerAliases
      aliases.foreach { alias =>
        run(Seq("docker", "tag", registryAlias.toString, alias.toString()), log)
      }
      // Cleanup
      run(Seq("docker", "rmi", registryAlias.toString), log)
    }
  )

  private def projectZoneArgs(project: Option[String], zone: Option[String]): Seq[String] =
    project.map(p => Seq("--project", p)).getOrElse(Nil) ++
    zone.map(z => Seq("--zone", z)).getOrElse(Nil)

  private def startBuildAgent(instanceName: String,
                              machineType: String,
                              project: Option[String],
                              zone: Option[String],
                              port: Int,
                              registryPort: Int,
                              preemptible: Boolean,
                              log: ProcessLogger): Unit = {
    val pzArgs = projectZoneArgs(project, zone)

    log.out(s"Starting build agent [$instanceName]...")
    run(
      Seq("gcloud",
          "compute",
          "instances",
          "create",
          instanceName,
          "--image-family",
          "cos-beta",
          "--image-project",
          "cos-cloud",
          "--machine-type",
          machineType) ++ pzArgs ++
      Seq("--preemptible").filter(_ => preemptible),
      log
    )

    log.out("Waiting for ssh to be available...")
    runUntilSuccessful(Seq("gcloud", "compute", "ssh", instanceName) ++ pzArgs ++ Seq("--", "true"), 30, log)

    log.out("Configuring docker...")
    run(
      Seq("gcloud", "compute", "ssh", instanceName) ++ pzArgs ++ Seq(
        "--",
        s"echo DOCKER_OPTS=--host tcp://127.0.0.1:$port --experimental | sudo tee /etc/default/docker > /dev/null;",
        "sudo systemctl restart docker;",
        // We pull it first because otherwise, when the run command sees it not there and pulls it, it writes the output
        // to stderr
        "docker pull registry:2;",
        s"docker run -d -p $registryPort:5000 --name registry registry:2"
      ),
      log
    )

    log.out(s"GCP docker build agent [$instanceName] started.")
  }

  private def ensureAgentStarted(instanceName: String,
                                 machineType: String,
                                 project: Option[String],
                                 zone: Option[String],
                                 port: Int,
                                 registryPort: Int,
                                 preemptible: Boolean,
                                 log: ProcessLogger): Unit =
    if (!sys.props.contains("gcp-agent-started")) {
      sys.props.put("gcp-agent-started", "true")

      // First add the shutdown hook, so if we fail during startup, we'll still try to cleanup.
      if (!sys.props.contains("gcp-agent-stop-shutdown-hook-configured")) {
        sys.props.put("gcp-agent-stop-shutdown-hook-configured", "true")
        sys.runtime.addShutdownHook(new Thread(new Runnable {
          def run(): Unit =
            stopBuildAgent(instanceName, project, zone, log)
        }))
      }

      // Check if it's already running
      checkAgentStatus(instanceName, project, zone, NoProcessLogger) match {
        case AgentStatus.Running =>
          log.out(s"Using existing build agent [$instanceName]")
        case AgentStatus.Terminated =>
          log.out(s"Deleting terminated build agent [$instanceName] first...")
          deleteBuildAgent(instanceName, project, zone)
          startBuildAgent(instanceName, machineType, project, zone, port, registryPort, preemptible, log)
        case _ =>
          startBuildAgent(instanceName, machineType, project, zone, port, registryPort, preemptible, log)
      }
    }

  private def checkAgentStatus(instanceName: String,
                               project: Option[String],
                               zone: Option[String],
                               log: ProcessLogger): AgentStatus.AgentStatus = {
    val pzArgs = projectZoneArgs(project, zone)

    val out = new StringBuffer
    if ((Seq("gcloud", "compute", "instances", "describe", instanceName, "--format=value(status)") ++ pzArgs)
          .run(BasicIO(false, out, Some(log)))
          .exitValue() == 0) {
      out.toString.trim match {
        case "TERMINATED" => AgentStatus.Terminated
        case "RUNNING" => AgentStatus.Running
        case unknown =>
          log.err("Unknown agent status: " + unknown)
          AgentStatus.Unknown
      }
    } else AgentStatus.NotFound
  }

  private def stopBuildAgent(instanceName: String,
                             project: Option[String],
                             zone: Option[String],
                             log: ProcessLogger): Unit = {
    log.out(s"Stopping GCP build agent [$instanceName]...")
    deleteBuildAgent(instanceName, project, zone)
    log.out("Stopped.")
  }

  private def deleteBuildAgent(instanceName: String, project: Option[String], zone: Option[String]): Unit = {
    val pzArgs = projectZoneArgs(project, zone)
    // Ignore the return code
    (Seq("gcloud", "compute", "instances", "delete", instanceName, "-q") ++ pzArgs).!
  }

  private def ensurePortForwardStarted(instanceName: String,
                                       project: Option[String],
                                       zone: Option[String],
                                       port: Int,
                                       registryPort: Int,
                                       log: ProcessLogger): Unit =
    if (!sys.props.contains("gcp-port-forward-started")) {
      sys.props.put("gcp-port-forward-started", "true")

      val process = startPortForward(instanceName, project, zone, port, registryPort, log)

      sys.runtime.addShutdownHook(new Thread(new Runnable {
        def run(): Unit =
          process.destroy()
      }))

      runUntilSuccessful(Seq("docker", "-H", s"tcp://localhost:$port", "version"), 10, log)
    }

  private def startPortForward(instanceName: String,
                               project: Option[String],
                               zone: Option[String],
                               port: Int,
                               registryPort: Int,
                               log: ProcessLogger): Process = {

    val pzArgs = projectZoneArgs(project, zone)
    log.out(s"Starting SSH port forward from localhost:$port to [$instanceName]...")
    (Seq("gcloud", "compute", "ssh", instanceName) ++ pzArgs ++
    Seq("--", "-L", s"$port:localhost:$port", "-L", s"$registryPort:localhost:$registryPort", "-NT"))
      .run(NoProcessLogger)
  }

  private def agentStatusCommand = Command.command("gcpAgentStatus") { state =>
    val extracted = Project.extract(state)
    val instanceName = extracted.get(gcpAgentInstanceName)
    val project = extracted.get(gcpAgentProject)
    val zone = extracted.get(gcpAgentZone)

    println("Agent status is: " + checkAgentStatus(instanceName, project, zone, state.log))
    state
  }

  private def startBuildAgentCommand = Command.command("gcpAgentStart") { state =>
    val extracted = Project.extract(state)
    val instanceName = extracted.get(gcpAgentInstanceName)
    val machineType = extracted.get(gcpAgentMachineType)
    val project = extracted.get(gcpAgentProject)
    val zone = extracted.get(gcpAgentZone)
    val port = extracted.get(gcpAgentPort)
    val registryPort = extracted.get(gcpAgentRegistryPort)
    val preemptible = extracted.get(gcpAgentPreemptible)

    startBuildAgent(instanceName, machineType, project, zone, port, registryPort, preemptible, state.log)
    state
  }

  private def stopBuildAgentCommand = Command.command("gcpAgentStop") { state =>
    val extracted = Project.extract(state)
    val instanceName = extracted.get(gcpAgentInstanceName)
    val project = extracted.get(gcpAgentProject)
    val zone = extracted.get(gcpAgentZone)

    stopBuildAgent(instanceName, project, zone, state.log)
    state
  }

  private def startPortForwardingCommand: Command = Command.command("gcpAgentPortForward") { state =>
    val extracted = Project.extract(state)
    val instanceName = extracted.get(gcpAgentInstanceName)
    val project = extracted.get(gcpAgentProject)
    val zone = extracted.get(gcpAgentZone)
    val port = extracted.get(gcpAgentPort)
    val registryPort = extracted.get(gcpAgentRegistryPort)

    startPortForward(instanceName, project, zone, port, registryPort, state.log)
    state
  }

  private def resetAgentCommand: Command = Command.command("gcpAgentReset") { state =>
    val extracted = Project.extract(state)
    val instanceName = extracted.get(gcpAgentInstanceName)
    val machineType = extracted.get(gcpAgentMachineType)
    val project = extracted.get(gcpAgentProject)
    val zone = extracted.get(gcpAgentZone)
    val port = extracted.get(gcpAgentPort)
    val registryPort = extracted.get(gcpAgentRegistryPort)
    val preemptible = extracted.get(gcpAgentPreemptible)

    checkAgentStatus(instanceName, project, zone, state.log) match {
      case AgentStatus.Running =>
        println("Agent is running.")
      case _ =>
        sys.props.remove("gcp-agent-started")
        sys.props.remove("gcp-port-forward-started")
        ensureAgentStarted(instanceName, machineType, project, zone, port, registryPort, preemptible, state.log)
        ensurePortForwardStarted(instanceName, project, zone, port, registryPort, state.log)
    }

    state
  }

  private def run(command: Seq[String], log: ProcessLogger): Unit =
    command.run(log).exitValue() match {
      case 0 => ()
      case x => sys.error(s"Command failed with exit code $x: ${command.mkString(" ")}")
    }

  private def runUntilSuccessful(command: Seq[String], maxTimes: Int, log: ProcessLogger): Unit =
    if (maxTimes <= 1) {
      run(command, log)
    } else {
      command.run(NoProcessLogger).exitValue() match {
        case 0 => ()
        case _ =>
          Thread.sleep(1000)
          runUntilSuccessful(command, maxTimes - 1, log)
      }
    }

  object NoProcessLogger extends ProcessLogger {
    def out(s: => String): Unit = ()
    def err(s: => String): Unit = ()
    def buffer[T](f: => T): T = f
  }

  object AgentStatus extends Enumeration {
    type AgentStatus = Value
    val Running, Terminated, NotFound, Unknown = Value
  }
}
