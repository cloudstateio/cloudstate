package com.lightbend.statefulserverless

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.pattern.{BackoffOpts, BackoffSupervisor}
import akka.stream.ActorMaterializer
import com.lightbend.statefulserverless.ServerManager.Configuration
import scala.concurrent.duration._

object StatefulServerlessMain extends App {
  implicit val system = ActorSystem("statefulserverless-backend")
  implicit final val materializer = ActorMaterializer()

  // FIXME go over and supply appropriate values for Cluster Sharding
  // https://doc.akka.io/docs/akka/current/cluster-sharding.html?language=scala#configuration
  val config = new Configuration(system.settings.config.getConfig("stateful-serverless"))

  val cluster = Cluster(system)

  // Bootstrap the cluster
  if (config.devMode) {
    // In development, we just have a cluster of one, so we join ourself.
    cluster.join(cluster.selfAddress)
  } else {
    AkkaManagement(system).start()
    ClusterBootstrap(system).start()
  }

  cluster.registerOnMemberUp {
    system.actorOf(BackoffSupervisor.props(
      BackoffOpts.onFailure(
        ServerManager.props(config),
        childName = "server-manager",
        minBackoff = 1.second,   // TODO make this configurable
        maxBackoff = 10.seconds, // TODO make this configurable
        randomFactor = 0.2       // TODO make this configurable
      )))
  }
}