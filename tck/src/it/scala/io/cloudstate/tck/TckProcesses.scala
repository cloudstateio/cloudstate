/*
 * Copyright 2019 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.cloudstate.tck

import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import scala.sys.process._

object TckProcesses {

  def create(config: TckConfiguration): TckProcesses = {
    val service = if (config.service.dockerImage.nonEmpty) {
      createDockerService(config)
    } else createCommandProcess(config.service)
    val proxy = if (config.proxy.dockerImage.nonEmpty) {
      createDockerProxy(config)
    } else createCommandProcess(config.proxy)

    TckProcesses(proxy, service)
  }

  private def createCommandProcess(spec: TckProcessConfig): TckProcess =
    new TckProcess {
      private var process: Option[Process] = None
      private val logger = new TckProcessLogger

      override def start(): Unit = {
        require(process.isEmpty, "Process already started")
        val localhost = InetAddress.getLocalHost.getHostAddress
        val command = spec.command.map(_.replace("%LOCALHOST%", localhost))
        val pb = Process(command, spec.directory, spec.envVars.toSeq: _*)
        process = Some(pb.run(logger))
      }

      override def stop(): Unit =
        process match {
          case Some(p) =>
            spec.stopCommand match {
              case Some(stopCommand) => Process(stopCommand, spec.directory).run(logger)
              case None => p.destroy(); p.exitValue() // waits for process to terminate
            }
            process = None
          case None =>
            sys.error("Process not started")
        }

      override def logs(name: String): Unit = logger.printBufferedLogs(name)
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

  private def createDockerService(config: TckConfiguration) =
    createDocker(config.service,
                 "cloudstate-tck-service",
                 Map(
                   "PORT" -> config.service.port.toString
                 ),
                 Nil)

  private def createDocker(spec: TckProcessConfig,
                           name: String,
                           extraEnv: Map[String, String],
                           extraArgs: Seq[String]): TckProcess =
    new TckProcess {
      private val logger = new TckProcessLogger

      override def start(): Unit = {
        val env = extraEnv ++ spec.envVars

        val command = Seq("docker", "run", "--rm", "--name", name, "-p", s"${spec.port}:${spec.port}") ++
          extraArgs ++
          env.toSeq.flatMap {
            case (key, value) => Seq("-e", s"$key=$value")
          } ++
          Seq(spec.dockerImage) ++
          spec.dockerArgs

        Process(command).run(logger)
      }

      override def stop(): Unit =
        Process(Seq("docker", "stop", name)).run(logger).exitValue() // waits for process to terminate

      override def logs(name: String): Unit = logger.printBufferedLogs(name)
    }

}

case class TckProcesses(proxy: TckProcess, service: TckProcess)

trait TckProcess {
  def start(): Unit
  def stop(): Unit
  def logs(name: String): Unit
}

final class TckProcessLogger extends ProcessLogger {
  private val buffer = new ConcurrentLinkedQueue[String]

  override def out(s: => String): Unit = buffer.add(s)
  override def err(s: => String): Unit = buffer.add(s)
  override def buffer[T](f: => T): T = f

  def printBufferedLogs(name: String): Unit = buffer.forEach(line => println(s"[$name] $line"))
}
