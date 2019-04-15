package com.lightbend.statefulserverless

import scala.concurrent.duration._
import akka.NotUsed
import akka.actor._
import akka.event.Logging.LogLevel
import akka.stream.OverflowStrategy
import akka.stream.scaladsl._
import akka.persistence.{ PersistentActor, SaveSnapshotSuccess, SaveSnapshotFailure, SnapshotOffer, RecoveryCompleted }
import akka.cluster.sharding.ShardRegion
import com.lightbend.statefulserverless.grpc._
import com.google.protobuf.any.{Any => pbAny}

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

  @volatile private[this] final var relay: ActorRef/*[EntityStreamIn]*/ =
    connect().to(Sink.foreach({ val s = context.self; event => s ! event})).run()(???) //FIXME use a child-actor to deal with responses?

  private[this] def connect(): Source[EntityStreamOut, ActorRef] = {
    @volatile var hackery: ActorRef = null // FIXME after this gets fixed https://github.com/akka/akka-grpc/issues/571
    val source = Source.actorRef[EntityStreamIn](100, OverflowStrategy.fail).mapMaterializedValue {
      ref =>
        if (hackery eq null)
          hackery = ref
        NotUsed
    }
    // FIXME should we add a graceful termination signal or KillSwitch to the stream to the user function?
    client.handle(source).mapMaterializedValue(_ => hackery)
  }

  private[this] final var currentEntity: pbAny = null

  override final def receiveCommand: PartialFunction[Any, Unit] = {
    case c: Command =>
      relay ! EntityStreamIn(EntityStreamIn.Message.Command(c))
      /* TODOs
        * Receive reply
          - persist any resulting events
          - persist any resulting snapshot
          - handle any resulting failure
        * Respond
          - sender() ! response.payload
      */
    case SaveSnapshotSuccess(metadata) => // FIXME specify behavior here?
    case SaveSnapshotFailure(metadata, cause) => // FIXME specify behavior here?
    case ReceiveTimeout =>
      context.parent ! Passivate(stopMessage = Stop)
    case Stop           =>
      context.stop(self)
  }

  override final def receiveRecover: PartialFunction[Any, Unit] = {
    case SnapshotOffer(metadata, offeredSnapshot: pbAny) =>
      currentEntity = offeredSnapshot
      // FIXME: metadata.persistenceId in the line below is likely not the right thing here, should we store entityId somewhere?
      relay ! EntityStreamIn(EntityStreamIn.Message.Init(Init(metadata.persistenceId, Some(Snapshot(metadata.sequenceNr, Some(currentEntity))))))
    case RecoveryCompleted                               =>
      // FIXME send to stream?
    case e: Event /* FIXME event: pbAny */               =>
      relay ! EntityStreamIn(EntityStreamIn.Message.Event(Event(lastSequenceNr, e.payload)))
  }
}