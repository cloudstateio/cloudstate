package com.lightbend.statefulserverless

import akka.NotUsed
import akka.actor._
import akka.util.Timeout
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl._
import akka.persistence.{PersistentActor, RecoveryCompleted, SaveSnapshotFailure, SaveSnapshotSuccess, SnapshotOffer}
import akka.cluster.sharding.ShardRegion
import com.lightbend.statefulserverless.grpc._
import EntityStreamIn.{Message => ESIMsg}
import com.google.protobuf.any.{Any => pbAny}

import scala.collection.immutable.Queue

object StateManager {
  final case object Stop
  final case object StreamClosed extends DeadLetterSuppression

  final case class Configuration(
    userFunctionName: String,
    passivationTimeout: Timeout,
    sendQueueSize: Int
    )

  final case class Request(
      final val commandId: Long,
      final val replyTo: ActorRef
    )
}

final class StateManager(client: EntityClient, configuration: StateManager.Configuration)(implicit mat: ActorMaterializer) extends PersistentActor with ActorLogging {
  override final def persistenceId: String = configuration.userFunctionName + self.path.name

  context.setReceiveTimeout(configuration.passivationTimeout.duration)

  @volatile private var relay: ActorRef/*[EntityStreamIn]*/ = connect().run()
  private var stashedCommands = Queue.empty[(Command, ActorRef)]
  private var currentRequest: StateManager.Request = null
  private var stopped = false
  private var idCounter = 0l

  private[this] def connect(): RunnableGraph[ActorRef] = {
    @volatile var hackery: ActorRef = null // FIXME after this gets fixed https://github.com/akka/akka-grpc/issues/571
    val source = Source.actorRef[EntityStreamIn](configuration.sendQueueSize, OverflowStrategy.fail).mapMaterializedValue {
      ref =>
        if (hackery eq null)
          hackery = ref
        NotUsed
    }
    client.handle(source).mapMaterializedValue(_ => hackery).to(Sink.actorRef(self, StateManager.StreamClosed))
  }


  override def postStop(): Unit = {
    // This will shutdown the stream (if not already shut down)
    relay ! Status.Success(())
  }

  private def commandHandled(): Unit = {
    currentRequest = null
    if (stashedCommands.nonEmpty) {
      val (command, newStashedCommands) = stashedCommands.dequeue
      stashedCommands = newStashedCommands
      receiveCommand(command)
    } else if (stopped) {
      context.stop(self)
    }
  }

  private def notifyOutstandingRequests(msg: String): Unit = {
    if (currentRequest != null) {
      currentRequest.replyTo ! Status.Failure(new Exception(msg))
    }
    stashedCommands.foreach {
      case (_, replyTo) => replyTo ! Status.Failure(new Exception("Entity terminated"))
    }
  }

  private def crash(msg: String): Unit = {
    notifyOutstandingRequests(msg)
    throw new Exception(msg)
  }

  override def receiveCommand: PartialFunction[Any, Unit] = {

    case c: Command if currentRequest != null =>
      stashedCommands = stashedCommands.enqueue((c, sender()))

    case c: Command =>
      idCounter += 1
      val commandWithId = c.copy(id = idCounter)
      currentRequest = StateManager.Request(idCounter, sender())
      relay ! EntityStreamIn(ESIMsg.Command(commandWithId))

    case EntityStreamOut(m) =>
      import EntityStreamOut.{Message => ESOMsg}
      m match {

        case ESOMsg.Reply(r) if currentRequest == null =>
          crash(s"Unexpected reply, had no current request: $r")

        case ESOMsg.Reply(r) if currentRequest.commandId != r.commandId =>
          crash(s"Incorrect command id in reply, expecting ${currentRequest.commandId} but got ${r.commandId}")

        case ESOMsg.Reply(r) =>
          val commandId = currentRequest.commandId
          val events = r.events.toVector
          if (events.isEmpty) {
            currentRequest.replyTo ! r.getPayload
            commandHandled()
          } else {
            var eventsLeft = events.size
            persistAll(events) { _ =>
              eventsLeft -= 1
              if (eventsLeft <= 0) { // Remove this hack when switching to Akka Persistence Typed
                r.snapshot.foreach(saveSnapshot)
                // Make sure that the current request is still ours
                if (currentRequest == null || currentRequest.commandId != commandId) {
                  crash("Internal error - currentRequest changed before all events were persisted")
                }
                currentRequest.replyTo ! r.getPayload
                commandHandled()
              }
            }
          }

        case ESOMsg.Failure(f) if currentRequest == null =>
          crash(s"Unexpected failure, had no current request: $f")

        case ESOMsg.Failure(f) if currentRequest.commandId != f.commandId =>
          crash(s"Incorrect command id in failure, expecting ${currentRequest.commandId} but got ${f.commandId}")

        case ESOMsg.Failure(f) =>
          currentRequest.replyTo ! Status.Failure(new Exception(f.description))

        case ESOMsg.Empty =>
          // Either the reply/failure wasn't set, or its set to something unknown.
          // todo see if scalapb can give us unknown fields so we can possibly log more intelligently
          crash("Empty or unknown message from entity output stream")
      }

    case StateManager.StreamClosed =>
      notifyOutstandingRequests("Unexpected entity termination")
      context.stop(self)

    case SaveSnapshotSuccess(metadata) =>
      // Nothing to do

    case SaveSnapshotFailure(metadata, cause) =>
      log.error("Error saving snapshot", cause)

    case ReceiveTimeout =>
      context.parent ! ShardRegion.Passivate(stopMessage = StateManager.Stop)

    case StateManager.Stop =>
      if (currentRequest == null) {
        context.stop(self)
      } else {
        stopped = true // FIXME do we need to set a ReceiveTimeout to time out a request?
      }
  }

  override final def receiveRecover: PartialFunction[Any, Unit] = {
    case SnapshotOffer(metadata, offeredSnapshot: pbAny) =>
      relay ! EntityStreamIn(ESIMsg.Init(Init(self.path.name, Some(Snapshot(metadata.sequenceNr, Some(offeredSnapshot))))))

    case RecoveryCompleted =>
      // For now, the protocol doesn't require this, so we can ignore

    case event: pbAny =>
      relay ! EntityStreamIn(ESIMsg.Event(Event(lastSequenceNr, Some(event))))
  }
}