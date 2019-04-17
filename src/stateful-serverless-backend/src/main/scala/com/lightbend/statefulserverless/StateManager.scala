package com.lightbend.statefulserverless

import scala.annotation.unchecked
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

  final case class Request(
      final val commandId: Long,
      final val replyTo: ActorRef
    )
}

//FIXME IMPLEMENT
final class StateManager(client: EntityClient, configuration: StateManager.Configuration) extends PersistentActor {
  override final def persistenceId: String = configuration.userFunctionName + self.path.name // FIXME is this right?

  context.setReceiveTimeout(configuration.passivationTimeout.duration)

  private[this] implicit final val mat = ActorMaterializer() // FIXME does this need explicit shutdown() or do we rely on child termination?

  @volatile private[this] final var relay: ActorRef/*[EntityStreamIn]*/ = connect().run()
  private[this] final var currentRequest: StateManager.Request = null

  private[this] def connect(): RunnableGraph[ActorRef] = {
    @volatile var hackery: ActorRef = null // FIXME after this gets fixed https://github.com/akka/akka-grpc/issues/571
    val source = Source.actorRef[EntityStreamIn](configuration.sendQueueSize, OverflowStrategy.fail).mapMaterializedValue {
      ref =>
        if (hackery eq null)
          hackery = ref
        NotUsed
    }
    // FIXME should we add a graceful termination signal or KillSwitch to the stream to the user function?
    client.handle(source).mapMaterializedValue(_ => hackery).to(Sink.actorRef(self, StateManager.StreamClosed))
  }

  override final def receiveCommand: PartialFunction[Any, Unit] = {
    case c: Command =>
      currentRequest = StateManager.Request(c.id, sender())
      relay ! EntityStreamIn(ESIMsg.Command(c))
      // FIXME switch to stashing behavior
    case EntityStreamOut(m) =>
      import EntityStreamOut.{Message => ESOMsg}
      val stableRequest = currentRequest
      m match {
        case ESOMsg.Reply(r) =>
          if (r.commandId != stableRequest.commandId) ??? // FIXME handle validation
          else {
            val events = r.events.toVector
            if (events.isEmpty) stableRequest.replyTo ! Array[Byte]() // FIXME what is the appropriate course of action here?
            else {
              var eventsLeft = events.size
              persistAll(events) { _ =>
                eventsLeft -= 1
                if (eventsLeft <= 0) { // Remove this hack when switching to Akka Persistence Typed
                  r.snapshot.foreach(saveSnapshot)
                  stableRequest.replyTo ! r.getPayload.toByteArray // FIXME should we not respond with a pbAny?
                }
              }
            }

          }
        case ESOMsg.Failure(f) =>
          if (f.commandId != stableRequest.commandId) ??? // FIXME handle validation
          else {
            // FIXME how to deal with failures?
            stableRequest.replyTo ! Status.Failure(new Exception(f.description))
          }
        case ESOMsg.Empty =>
          stableRequest.replyTo ! Array[Byte]() // FIXME what is the appropriate course of action here?
      }
      currentRequest = null
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
    case RecoveryCompleted => // TODO figure out if we need to do something here
    case event: pbAny =>
      relay ! EntityStreamIn(ESIMsg.Event(Event(lastSequenceNr, Some(event))))
  }
}