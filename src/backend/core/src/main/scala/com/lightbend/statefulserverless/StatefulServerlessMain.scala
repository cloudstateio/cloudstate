package com.lightbend.statefulserverless

import com.typesafe.config.Config
import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.pattern.{BackoffOpts, BackoffSupervisor}
import akka.stream.ActorMaterializer
import scala.concurrent.duration._

object StatefulServerlessMain {
  final case class Configuration (
    devMode: Boolean,
    backoffMin: FiniteDuration,
    backoffMax: FiniteDuration,
    backoffRandomFactor: Double
    ) {
    validate()
    def this(config: Config) = {
      this(
        devMode             = config.getBoolean("dev-mode-enabled"),
        backoffMin          = config.getDuration("backoff.min").toMillis.millis,
        backoffMax          = config.getDuration("backoff.max").toMillis.millis,
        backoffRandomFactor = config.getDouble("backoff.random-factor")
      )
    }

    private[this] final def validate(): Unit = {
      require(backoffMin >= Duration.Zero)
      require(backoffMax >= backoffMin)
      require(backoffRandomFactor >= 0d)
      // TODO add more config validation here
    }
  }

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("statefulserverless-backend")
    implicit val materializer = ActorMaterializer()

    // FIXME go over and supply appropriate values for Cluster Sharding
    // https://doc.akka.io/docs/akka/current/cluster-sharding.html?language=scala#configuration
    val c = system.settings.config.getConfig("stateful-serverless")
    val serverConfig = new ServerManager.Configuration(c)
    val appConfig = new StatefulServerlessMain.Configuration(c)


    val cluster = Cluster(system)

    // Bootstrap the cluster
    if (appConfig.devMode) {
      // In development, we just have a cluster of one, so we join ourself.
      cluster.join(cluster.selfAddress)
    } else {
      AkkaManagement(system).start()
      ClusterBootstrap(system).start()
    }

    cluster.registerOnMemberUp {
      system.actorOf(BackoffSupervisor.props(
        BackoffOpts.onFailure(
          ServerManager.props(serverConfig),
          childName = "server-manager",
          minBackoff = appConfig.backoffMin,
          maxBackoff = appConfig.backoffMax,
          randomFactor = appConfig.backoffRandomFactor
        )))
    }
  }
}