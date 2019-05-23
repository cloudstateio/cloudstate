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

package com.lightbend.statefulserverless

import com.typesafe.config.Config
import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import akka.cluster.Cluster
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.pattern.{BackoffOpts, BackoffSupervisor}
import akka.stream.ActorMaterializer

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

final class HealthCheckReady(system: ActorSystem) extends (() => Future[Boolean]) {
  private[this] final val timeoutMs = system.settings.config.getConfig("stateful-serverless").getDuration("ready-timeout").toMillis.millis
  private[this] final implicit val ec = system.dispatcher
  private[this] final val selection = system.actorSelection("/user/server-manager-supervisor/server-manager")
  private[this] final implicit val timeout = Timeout(timeoutMs)
  override final def apply(): Future[Boolean] =
    selection.resolveOne().map(_ ? ServerManager.Ready).mapTo[Boolean].recover({ case _ => false})
}

final class HealthCheckLive(system: ActorSystem) extends (() => Future[Boolean]) {
  override final def apply(): Future[Boolean] = {
    Future.successful(true) // FIXME implement
  }
}

object StatefulServerlessMain {
  final case class Configuration (
    devMode: Boolean,
    backoffMin: FiniteDuration,
    backoffMax: FiniteDuration,
    backoffRandomFactor: Double,
    metricsPort: Int
    ) {
    validate()
    def this(config: Config) = {
      this(
        devMode             = config.getBoolean("dev-mode-enabled"),
        backoffMin          = config.getDuration("backoff.min").toMillis.millis,
        backoffMax          = config.getDuration("backoff.max").toMillis.millis,
        backoffRandomFactor = config.getDouble("backoff.random-factor"),
        metricsPort         = config.getInt("metrics-port")
      )
    }

    private[this] final def validate(): Unit = {
      require(backoffMin >= Duration.Zero)
      require(backoffMax >= backoffMin)
      require(backoffRandomFactor >= 0d)
    }
  }

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("statefulserverless-backend")
    implicit val materializer = ActorMaterializer()
    import system.dispatcher

    val c = system.settings.config.getConfig("stateful-serverless")
    val serverConfig = new ServerManager.Configuration(c)
    val appConfig = new StatefulServerlessMain.Configuration(c)

    val cluster = Cluster(system)

    // Bootstrap the cluster
    if (appConfig.devMode) {
      // In development, we just have a cluster of one, so we join ourself.
      cluster.join(cluster.selfAddress)
    } else {
      // Start cluster bootstrap
      AkkaManagement(system).start()
      ClusterBootstrap(system).start()

      // Start Prometheus exporter in prod mode
      new AkkaHttpPrometheusExporter(appConfig.metricsPort).start().onComplete {
        case Success(binding) =>
          system.log.info("Prometheus exporter started on {}", binding.localAddress)
        case Failure(error) =>
          system.log.error(error, "Error starting Prometheus exporter!")
          system.terminate()
      }
    }

    // Warmup the StateManager, connect to Cassandra, etc
    system.actorOf(Props(classOf[Warmup]), "state-manager-warm-up")

    system.actorOf(BackoffSupervisor.props(
      BackoffOpts.onFailure(
        ServerManager.props(serverConfig),
        childName = "server-manager",
        minBackoff = appConfig.backoffMin,
        maxBackoff = appConfig.backoffMax,
        randomFactor = appConfig.backoffRandomFactor
      )), "server-manager-supervisor")
  }
}