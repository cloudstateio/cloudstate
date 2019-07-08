package io.cloudstate.proxy.autoscaler

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.cluster.ClusterEvent.ClusterDomainEvent
import akka.cluster.{Cluster, MemberStatus}
import Autoscaler.{Deployment, Scale}

/**
  * A scaler that doesn't do anything other than reports current cluster size.
  */
class NoScaler(autoscaler: ActorRef) extends Actor with ActorLogging {

  private val cluster = Cluster(context.system)

  cluster.subscribe(self, classOf[ClusterDomainEvent])
  sendDeployment()

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }

  private def sendDeployment(): Unit = {
    autoscaler ! Deployment(
      name = context.system.name,
      ready = cluster.state.members.count(c => c.status == MemberStatus.Up || c.status == MemberStatus.WeaklyUp),
      scale = cluster.state.members.size,
      upgrading = false
    )
  }

  override def receive: Receive = {
    case Scale(_, scale) =>
      log.info(s"Autoscaler requested scale up to $scale")
    case _: ClusterDomainEvent =>
      // Don't care what the event was, just send the current deployment state to the autoscaler
      sendDeployment()
  }
}
