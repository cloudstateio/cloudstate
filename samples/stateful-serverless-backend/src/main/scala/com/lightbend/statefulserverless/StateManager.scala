package com.lightbend.statefulserverless

import scala.concurrent.duration._
import akka.actor._
import akka.persistence.PersistentActor
import akka.cluster.sharding.ShardRegion
import com.lightbend.statefulserverless.grpc.{ Command, EntityClient }

object StateManager {
  final case object Stop
}

//FIXME IMPLEMENT
final class StateManager(client: EntityClient) extends PersistentActor {
  // FIXME derive name from actual proxied service?
  override final def persistenceId: String = "StateManager-" + self.path.name
  // FIXME devise passivation strategy
  import ShardRegion.Passivate
  import StateManager.Stop

  // TODO make configurable
  context.setReceiveTimeout(15.minutes)

  override final def receiveCommand: PartialFunction[Any, Unit] = {
    case c: Command =>
      /* TODOs
        * Forward command to user logic
          - val response = client.handle(???)
        * Receive reply
          - persist any resulting events
          - persist any resulting snapshot
          - handle any resulting failure
        * Respond
          - sender() ! response.payload
      */
    case ReceiveTimeout =>
      context.parent ! Passivate(stopMessage = Stop)
    case Stop           =>
      context.stop(self)
  }

  override final def receiveRecover: PartialFunction[Any, Unit] = {
    case _ => ??? // FIXME define and use events?
  }
}