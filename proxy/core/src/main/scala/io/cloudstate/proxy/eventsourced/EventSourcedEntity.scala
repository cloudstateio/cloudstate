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

package io.cloudstate.proxy.eventsourced

import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong

import akka.NotUsed
import akka.actor._
import akka.cluster.sharding.ShardRegion
import akka.persistence._
import akka.stream.scaladsl._
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.Timeout
import com.google.protobuf.any.{Any => pbAny}
import io.cloudstate.entity._
import io.cloudstate.eventsourced._
import io.cloudstate.proxy.ConcurrencyEnforcer.{Action, ActionCompleted}
import io.cloudstate.proxy.StatsCollector
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}

import scala.collection.immutable.Queue

object EventSourcedEntitySupervisor {

  private final case class Relay(actorRef: ActorRef)

  def props(client: EventSourcedClient, configuration: EventSourcedEntity.Configuration, concurrencyEnforcer: ActorRef, statsCollector: ActorRef)(implicit mat: Materializer): Props =
    Props(new EventSourcedEntitySupervisor(client, configuration, concurrencyEnforcer, statsCollector))
}

/**
  * This serves two purposes.
  *
  * Firstly, when the StateManager crashes, we don't want it restarted. Cluster sharding restarts, and there's no way
  * to customise that.
  *
  * Secondly, we need to ensure that we have an Akka Streams actorRef source to publish messages two before Akka
  * persistence starts feeding us events. There's a race condition if we do this in the same persistent actor. This
  * establishes that connection first.
  */
final class EventSourcedEntitySupervisor(client: EventSourcedClient, configuration: EventSourcedEntity.Configuration, concurrencyEnforcer: ActorRef, statsCollector: ActorRef)(implicit mat: Materializer)
  extends Actor with Stash {

  import EventSourcedEntitySupervisor._

  override final def receive: Receive = PartialFunction.empty

  override final def preStart(): Unit = {
    client.handle(Source.actorRef[EventSourcedStreamIn](configuration.sendQueueSize, OverflowStrategy.fail)
      .mapMaterializedValue { ref =>
        self ! Relay(ref)
        NotUsed
      }).runWith(Sink.actorRef(self, EventSourcedEntity.StreamClosed))
    context.become(waitingForRelay)
  }

  private[this] final def waitingForRelay: Receive = {
    case Relay(relayRef) =>
      // Cluster sharding URL encodes entity ids, so to extract it we need to decode.
      val entityId = URLDecoder.decode(self.path.name, "utf-8")
      val manager = context.watch(context.actorOf(EventSourcedEntity.props(configuration, entityId, relayRef, concurrencyEnforcer, statsCollector), "entity"))
      context.become(forwarding(manager))
      unstashAll()
    case _ => stash()
  }

  private[this] final def forwarding(manager: ActorRef): Receive = {
    case Terminated(`manager`) =>
      context.stop(self)
    case toParent if sender() == manager =>
      context.parent ! toParent
    case msg =>
      manager forward msg
  }

  override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy
}

object EventSourcedEntity {

  final case object Stop

  final case object StreamClosed extends DeadLetterSuppression

  final case class Configuration(
    serviceName: String,
    userFunctionName: String,
    passivationTimeout: Timeout,
    sendQueueSize: Int
  )

  private final case class OutstandingCommand(
    commandId: Long,
    replyTo: ActorRef
  )

  final def props(configuration: Configuration, entityId: String, relay: ActorRef, concurrencyEnforcer: ActorRef, statsCollector: ActorRef): Props =
    Props(new EventSourcedEntity(configuration, entityId, relay, concurrencyEnforcer, statsCollector))

  /**
    * Used to ensure the action ids sent to the concurrency enforcer are indeed unique.
    */
  private val actorCounter = new AtomicLong(0)
}

final class EventSourcedEntity(configuration: EventSourcedEntity.Configuration, entityId: String, relay: ActorRef,
  concurrencyEnforcer: ActorRef, statsCollector: ActorRef) extends PersistentActor with ActorLogging {
  override final def persistenceId: String = configuration.userFunctionName + entityId

  private val actorId = EventSourcedEntity.actorCounter.incrementAndGet()

  private[this] final var stashedCommands = Queue.empty[(EntityCommand, ActorRef)] // PERFORMANCE: look at options for data structures
  private[this] final var currentCommand: EventSourcedEntity.OutstandingCommand = null
  private[this] final var stopped = false
  private[this] final var idCounter = 0l
  private[this] final var inited = false
  private[this] final var currentActionId: String = null
  private[this] final var reportedDatabaseOperationStarted = false
  private[this] final var databaseOperationStartTime = 0l
  private[this] final var commandStartTime = 0l

  // Set up passivation timer
  context.setReceiveTimeout(configuration.passivationTimeout.duration)

  // First thing actor will do is access database
  reportDatabaseOperationStarted()

  override final def postStop(): Unit = {
    if (currentActionId != null) {
      log.warning("Stopped but we have a current action id {}", currentActionId)
      reportActionComplete()
    }
    if (reportedDatabaseOperationStarted) {
      reportDatabaseOperationFinished()
    }
    // This will shutdown the stream (if not already shut down)
    relay ! Status.Success(())
  }

  private[this] final def commandHandled(): Unit = {
    currentCommand = null
    if (stashedCommands.nonEmpty) {
      val ((request, sender), newStashedCommands) = stashedCommands.dequeue
      stashedCommands = newStashedCommands
      handleCommand(request, sender)
    } else if (stopped) {
      context.stop(self)
    }
  }

  private[this] final def notifyOutstandingRequests(msg: String): Unit = {
    currentCommand match {
      case null =>
      case req => req.replyTo ! createFailure(msg)
    }
    val errorNotification = createFailure("Entity terminated")
    stashedCommands.foreach {
      case (_, replyTo) => replyTo ! errorNotification
    }
  }

  private[this] final def crash(msg: String): Unit = {
    notifyOutstandingRequests(msg)
    throw new Exception(msg)
  }

  private[this] final def reportActionComplete() = {
    concurrencyEnforcer ! ActionCompleted(currentActionId, System.nanoTime() - commandStartTime)
    currentActionId = null
  }

  private[this] final def handleCommand(entityCommand: EntityCommand, sender: ActorRef): Unit = {
    idCounter += 1
    currentActionId = actorId + ":" + entityId + ":" + idCounter
    val command = Command(
      entityId = entityId,
      id = idCounter,
      name = entityCommand.name,
      payload = entityCommand.payload
    )
    currentCommand = EventSourcedEntity.OutstandingCommand(idCounter, sender)
    commandStartTime = System.nanoTime()
    concurrencyEnforcer ! Action(currentActionId, () => {
      relay ! EventSourcedStreamIn(EventSourcedStreamIn.Message.Command(command))
    })
  }

  private final def esReplyToUfReply(reply: EventSourcedReply) = {
    UserFunctionReply(
      clientAction = reply.clientAction,
      sideEffects = reply.sideEffects
    )
  }

  private final def createFailure(message: String) = {
    UserFunctionReply(
      clientAction = Some(ClientAction(ClientAction.Action.Failure(Failure(description = message))))
    )
  }

  override final def receiveCommand: PartialFunction[Any, Unit] = {

    case command: EntityCommand if currentCommand != null =>
      stashedCommands = stashedCommands.enqueue((command, sender()))

    case command: EntityCommand =>
      handleCommand(command, sender())

    case EventSourcedStreamOut(m) =>

      import EventSourcedStreamOut.{Message => ESOMsg}
      m match {

        case ESOMsg.Reply(r) if currentCommand == null =>
          crash(s"Unexpected reply, had no current command: $r")

        case ESOMsg.Reply(r) if currentCommand.commandId != r.commandId =>
          crash(s"Incorrect command id in reply, expecting ${currentCommand.commandId} but got ${r.commandId}")

        case ESOMsg.Reply(r) =>
          reportActionComplete()
          val commandId = currentCommand.commandId
          val events = r.events.toVector
          if (events.isEmpty) {
            currentCommand.replyTo ! esReplyToUfReply(r)
            commandHandled()
          } else {
            reportDatabaseOperationStarted()
            var eventsLeft = events.size
            persistAll(events) { _ =>
              eventsLeft -= 1
              if (eventsLeft <= 0) { // Remove this hack when switching to Akka Persistence Typed
                reportDatabaseOperationFinished()
                r.snapshot.foreach(saveSnapshot)
                // Make sure that the current request is still ours
                if (currentCommand == null || currentCommand.commandId != commandId) {
                  crash("Internal error - currentRequest changed before all events were persisted")
                }
                currentCommand.replyTo ! esReplyToUfReply(r)
                commandHandled()
              }
            }
          }

        case ESOMsg.Failure(f) if f.commandId == 0 =>
          crash(s"Non command specific error from entity: ${f.description}")

        case ESOMsg.Failure(f) if currentCommand == null =>
          crash(s"Unexpected failure, had no current command: $f")

        case ESOMsg.Failure(f) if currentCommand.commandId != f.commandId =>
          crash(s"Incorrect command id in failure, expecting ${currentCommand.commandId} but got ${f.commandId}")

        case ESOMsg.Failure(f) =>
          reportActionComplete()
          currentCommand.replyTo ! createFailure(f.description)
          commandHandled()

        case ESOMsg.Empty =>
          // Either the reply/failure wasn't set, or its set to something unknown.
          // todo see if scalapb can give us unknown fields so we can possibly log more intelligently
          crash("Empty or unknown message from entity output stream")
      }

    case EventSourcedEntity.StreamClosed =>
      notifyOutstandingRequests("Unexpected entity termination")
      context.stop(self)

    case Status.Failure(error) =>
      notifyOutstandingRequests("Unexpected entity termination")
      throw error

    case SaveSnapshotSuccess(metadata) =>
    // Nothing to do

    case SaveSnapshotFailure(metadata, cause) =>
      log.error("Error saving snapshot", cause)

    case ReceiveTimeout =>
      context.parent ! ShardRegion.Passivate(stopMessage = EventSourcedEntity.Stop)

    case EventSourcedEntity.Stop =>
      stopped = true
      if (currentCommand == null) {
        context.stop(self)
      }
  }

  private[this] final def maybeInit(snapshot: Option[SnapshotOffer]): Unit = {
    if (!inited) {
      relay ! EventSourcedStreamIn(EventSourcedStreamIn.Message.Init(EventSourcedInit(
        serviceName = configuration.serviceName,
        entityId = entityId,
        snapshot = snapshot.map {
          case SnapshotOffer(metadata, offeredSnapshot: pbAny) => EventSourcedSnapshot(metadata.sequenceNr, Some(offeredSnapshot))
          case other => throw new IllegalStateException(s"Unexpected snapshot type received: ${other.getClass}")
        }
      )))
      inited = true
    }
  }

  override final def receiveRecover: PartialFunction[Any, Unit] = {
    case offer: SnapshotOffer =>
      maybeInit(Some(offer))

    case RecoveryCompleted =>
      reportDatabaseOperationFinished()
      maybeInit(None)

    case event: pbAny =>
      maybeInit(None)
      relay ! EventSourcedStreamIn(EventSourcedStreamIn.Message.Event(EventSourcedEvent(lastSequenceNr, Some(event))))
  }

  private def reportDatabaseOperationStarted(): Unit = {
    if (reportedDatabaseOperationStarted) {
      log.warning("Already reported database operation started")
    } else {
      databaseOperationStartTime = System.nanoTime()
      reportedDatabaseOperationStarted = true
      statsCollector ! StatsCollector.DatabaseOperationStarted
    }
  }

  private def reportDatabaseOperationFinished(): Unit = {
    if (!reportedDatabaseOperationStarted) {
      log.warning("Hadn't reported database operation started")
    } else {
      reportedDatabaseOperationStarted = false
      statsCollector ! StatsCollector.DatabaseOperationFinished(System.nanoTime() - databaseOperationStartTime)
    }
  }
}