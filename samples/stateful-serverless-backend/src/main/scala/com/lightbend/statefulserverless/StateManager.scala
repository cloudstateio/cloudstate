package com.lightbend.statefulserverless

import scala.concurrent.duration._
import akka.NotUsed
import akka.actor._
import akka.util.Timeout
import akka.event.Logging.LogLevel
import akka.stream.OverflowStrategy
import akka.stream.scaladsl._
import akka.stream.ActorMaterializer
import akka.persistence.{ PersistentActor, SaveSnapshotSuccess, SaveSnapshotFailure, SnapshotOffer, RecoveryCompleted }
import akka.cluster.sharding.ShardRegion
import com.lightbend.statefulserverless.grpc._
import EntityStreamIn.{Message => ESIMsg}
import com.google.protobuf.any.{Any => pbAny}

object StateManager {
  final case object Stop
  final case object StreamClosed

  final case class Configuration(
    userFunctionName: String,
    passivationTimeout: Timeout,
    sendQueueSize: Int,
    )
}

//FIXME IMPLEMENT
final class StateManager(client: EntityClient, configuration: StateManager.Configuration) extends PersistentActor {
  override final def persistenceId: String = configuration.userFunctionName + self.path.name

  context.setReceiveTimeout(configuration.passivationTimeout.duration)

  private[this] implicit final val mat = ActorMaterializer()

  @volatile private[this] final var relay: ActorRef/*[EntityStreamIn]*/ =
    connect().to(Sink.actorRef(self, StateManager.StreamClosed)).run()

  private[this] def connect(): Source[EntityStreamOut, ActorRef] = {
    @volatile var hackery: ActorRef = null // FIXME after this gets fixed https://github.com/akka/akka-grpc/issues/571
    val source = Source.actorRef[EntityStreamIn](configuration.sendQueueSize, OverflowStrategy.fail).mapMaterializedValue {
      ref =>
        if (hackery eq null)
          hackery = ref
        NotUsed
    }
    // FIXME should we add a graceful termination signal or KillSwitch to the stream to the user function?
    client.handle(source).mapMaterializedValue(_ => hackery)
  }

  override final def receiveCommand: PartialFunction[Any, Unit] = {
    case c: Command =>
      relay ! EntityStreamIn(ESIMsg.Command(c))
      // FIXME switch to stashing behavior
    case EntityStreamOut(m) =>
      import EntityStreamOut.{Message => ESOMsg}
      m match {
        case ESOMsg.Reply(r) =>
          /* FIXME
            validate r.commandId
            (optionally) store r.events
            (optionally) store r.snapshot
            reply with r.payload
          */
        case ESOMsg.Failure(f) =>
          /* FIXME
             validate f.commandId
             handle failure
             reply with f.description?
             unstash
          */
        case ESOMsg.Empty =>
      }
      // FIXME unstash
    case StateManager.StreamClosed => // FIXME what does this mean?
    case SaveSnapshotSuccess(metadata) => // FIXME specify behavior here?
    case SaveSnapshotFailure(metadata, cause) => // FIXME specify behavior here?
    case ReceiveTimeout =>
      context.parent ! ShardRegion.Passivate(stopMessage = StateManager.Stop)
    case StateManager.Stop =>
      // FIXME graceful stop
      context.stop(self)
  }

  override final def receiveRecover: PartialFunction[Any, Unit] = {
    case SnapshotOffer(metadata, offeredSnapshot: pbAny) =>
      // FIXME: metadata.persistenceId in the line below is likely not the right thing here, should we store entityId somewhere?
      relay ! EntityStreamIn(ESIMsg.Init(Init(metadata.persistenceId, Some(Snapshot(metadata.sequenceNr, Some(offeredSnapshot))))))
    case RecoveryCompleted                               => // TODO figure out if we need to do something here
    case e: Event /* FIXME event: pbAny */               =>
      relay ! EntityStreamIn(ESIMsg.Event(Event(lastSequenceNr, e.payload)))
  }
}