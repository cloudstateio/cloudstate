package io.cloudstate.tck

import java.net.InetAddress
import java.util.concurrent.TimeUnit

object TckProcesses {

  def create(config: TckConfiguration): TckProcesses = {
    val frontend = if (config.frontend.dockerImage.nonEmpty) {
      createDockerFrontend(config)
    } else createCommandProcess(config.frontend)
    val proxy = if (config.proxy.dockerImage.nonEmpty) {
      createDockerProxy(config)
    } else createCommandProcess(config.proxy)

    TckProcesses(proxy, frontend)
  }

  private def createCommandProcess(spec: TckProcessConfig): TckProcess =
    new TckProcess {
      private var process: Option[Process] = None

      override def start(): Unit = {
        require(process.isEmpty, "Process already started")
        val localhost = InetAddress.getLocalHost.getHostAddress
        val pb =
          new ProcessBuilder(spec.command.map(_.replace("%LOCALHOST%", localhost)): _*)
            .inheritIO()
            .directory(spec.directory)

        val env = pb.environment

        spec.envVars.foreach {
          case (key, value) =>
            env.put(key, value)
        }
        process = Some(pb.start())
      }

      override def stop(): Unit =
        process match {
          case Some(p) =>
            spec.stopCommand match {
              case Some(stopCommand) =>
                new ProcessBuilder(stopCommand: _*)
                  .inheritIO()
                  .directory(spec.directory)
                  .start()
              case None => p.destroy()
            }
            p.waitFor(5, TimeUnit.SECONDS) || {
              p.destroyForcibly()
              true
            }
            process = None
          case None =>
            sys.error("Process not started")
        }
    }

  private def createDockerProxy(config: TckConfiguration) = {
    // Determine if platform is Linux, if it is then we need to add a record for host.docker.internal, since that
    // doesn't happen by default, see https://github.com/docker/cli/issues/2290
    val addHost = if (sys.props.get("os.name").contains("Linux")) {
      import sys.process._
      "inet +([\\d.]*)".r.findFirstMatchIn("ip -4 addr show docker0".!!).map { m =>
        s"--add-host=host.docker.internal:${m.group(1)}"
      }
    } else None

    createDocker(
      config.proxy,
      "cloudstate-tck-proxy",
      Map(
        "USER_FUNCTION_HOST" -> "host.docker.internal",
        "USER_FUNCTION_PORT" -> config.tckPort.toString,
        "HTTP_PORT" -> config.proxy.port.toString
      ),
      addHost.toSeq
    )
  }

  private def createDockerFrontend(config: TckConfiguration) =
    createDocker(config.frontend,
                 "cloudstate-tck-frontend",
                 Map(
                   "PORT" -> config.frontend.port.toString
                 ),
                 Nil)

  private def createDocker(spec: TckProcessConfig,
                           name: String,
                           extraEnv: Map[String, String],
                           extraArgs: Seq[String]): TckProcess =
    new TckProcess {
      override def start(): Unit = {
        val env = extraEnv ++ spec.envVars

        val command = Seq("docker", "run", "--rm", "--name", name, "-p", s"${spec.port}:${spec.port}") ++
          extraArgs ++
          env.toSeq.flatMap {
            case (key, value) => Seq("-e", s"$key=$value")
          } :+
          spec.dockerImage

        new ProcessBuilder(command: _*)
          .inheritIO()
          .start()
      }

      override def stop(): Unit =
        new ProcessBuilder("docker", "stop", name)
          .inheritIO()
          .start()
    }

}

case class TckProcesses(proxy: TckProcess, frontend: TckProcess)

trait TckProcess {
  def start(): Unit

  def stop(): Unit
}
