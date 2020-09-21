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
import akka.stream.{CompletionStrategy, Materializer, OverflowStrategy}
import akka.util.Timeout
import com.google.protobuf.any.{Any => pbAny}
import io.cloudstate.protocol.entity._
import io.cloudstate.protocol.event_sourced._
import io.cloudstate.proxy.StatsCollector
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}
import io.cloudstate.proxy.telemetry.CloudstateTelemetry

import scala.collection.immutable.Queue

object EventSourcedEntitySupervisor {

  private final case class Relay(actorRef: ActorRef)

  def props(client: EventSourcedClient, configuration: EventSourcedEntity.Configuration, statsCollector: ActorRef)(
      implicit mat: Materializer
  ): Props =
    Props(new EventSourcedEntitySupervisor(client, configuration, statsCollector))
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
final class EventSourcedEntitySupervisor(client: EventSourcedClient,
                                         configuration: EventSourcedEntity.Configuration,
                                         statsCollector: ActorRef)(implicit mat: Materializer)
    extends Actor
    with Stash {

  import EventSourcedEntitySupervisor._

  private var streamTerminated: Boolean = false

  override final def receive: Receive = PartialFunction.empty

  override final def preStart(): Unit = {
    client
      .handle(
        Source
          .actorRef[EventSourcedStreamIn](configuration.sendQueueSize, OverflowStrategy.fail)
          .mapMaterializedValue { ref =>
            self ! Relay(ref)
            NotUsed
          }
      )
      .runWith(Sink.actorRef(self, EventSourcedEntity.StreamClosed, EventSourcedEntity.StreamFailed.apply))
    context.become(waitingForRelay)
  }

  private[this] final def waitingForRelay: Receive = {
    case Relay(relayRef) =>
      // Cluster sharding URL encodes entity ids, so to extract it we need to decode.
      val entityId = URLDecoder.decode(self.path.name, "utf-8")
      val manager = context.watch(
        context
          .actorOf(EventSourcedEntity.props(configuration, entityId, relayRef, statsCollector), "entity")
      )
      context.become(forwarding(manager, relayRef))
      unstashAll()
    case _ => stash()
  }

  private[this] final def forwarding(manager: ActorRef, relay: ActorRef): Receive = {
    case Terminated(`manager`) =>
      if (streamTerminated) {
        context.stop(self)
      } else {
        relay ! Status.Success(CompletionStrategy.draining)
        context.become(stopping)
      }
    case toParent if sender() == manager =>
      context.parent ! toParent
    case EventSourcedEntity.StreamClosed =>
      streamTerminated = true
      manager forward EventSourcedEntity.StreamClosed
    case failed: EventSourcedEntity.StreamFailed =>
      streamTerminated = true
      manager forward failed
    case msg =>
      manager forward msg
  }

  private def stopping: Receive = {
    case EventSourcedEntity.StreamClosed =>
      context.stop(self)
    case _: EventSourcedEntity.StreamFailed =>
      context.stop(self)
  }

  override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy
}

object EventSourcedEntity {

  final case object Stop

  final case object StreamClosed extends DeadLetterSuppression
  final case class StreamFailed(cause: Throwable) extends DeadLetterSuppression

  final case class Configuration(
      serviceName: String,
      userFunctionName: String,
      passivationTimeout: Timeout,
      sendQueueSize: Int
  )

  private final case class OutstandingCommand(
      commandId: Long,
      actionId: String,
      replyTo: ActorRef
  )

  final def props(configuration: Configuration, entityId: String, relay: ActorRef, statsCollector: ActorRef): Props =
    Props(new EventSourcedEntity(configuration, entityId, relay, statsCollector))

  /**
   * Used to ensure the action ids sent to the concurrency enforcer are indeed unique.
   */
  private val actorCounter = new AtomicLong(0)
}

final class EventSourcedEntity(configuration: EventSourcedEntity.Configuration,
                               entityId: String,
                               relay: ActorRef,
                               statsCollector: ActorRef)
    extends PersistentActor
    with ActorLogging {

  import io.cloudstate.proxy.telemetry.EventSourcedInstrumentation.StashContext

  override final def persistenceId: String = configuration.userFunctionName + entityId

  private val actorId = EventSourcedEntity.actorCounter.incrementAndGet()

  private[this] final var stashedCommands = Queue.empty[(EntityCommand, ActorRef, StashContext)] // PERFORMANCE: look at options for data structures
  private[this] final var currentCommand: EventSourcedEntity.OutstandingCommand = null
  private[this] final var stopped = false
  private[this] final var idCounter = 0L
  private[this] final var inited = false
  private[this] final var reportedDatabaseOperationStarted = false
  private[this] final var databaseOperationStartTime = 0L
  private[this] final var commandStartTime = 0L

  private[this] val instrumentation =
    CloudstateTelemetry(context.system).eventSourcedEntityInstrumentation(configuration.userFunctionName)

  instrumentation.entityActivated()
  instrumentation.recoveryStarted()

  // Set up passivation timer
  context.setReceiveTimeout(configuration.passivationTimeout.duration)

  // First thing actor will do is access database
  reportDatabaseOperationStarted()

  override final def postStop(): Unit = {
    if (currentCommand != null) {
      log.warning("Stopped but we have a current action id {}", currentCommand.actionId)
      reportActionComplete()
    }
    if (reportedDatabaseOperationStarted) {
      reportDatabaseOperationFinished()
    }
    instrumentation.entityPassivated()
  }

  private[this] final def commandHandled(): Unit = {
    currentCommand = null
    instrumentation.commandCompleted()
    if (stashedCommands.nonEmpty) {
      val ((request, sender, context), newStashedCommands) = stashedCommands.dequeue
      stashedCommands = newStashedCommands
      instrumentation.commandUnstashed(context)
      handleCommand(request, sender)
    } else if (stopped) {
      context.stop(self)
    }
  }

  private[this] final def notifyOutstandingRequests(msg: String): Unit = {
    instrumentation.entityFailed()
    currentCommand match {
      case null =>
      case req => req.replyTo ! createFailure(msg)
    }
    val errorNotification = createFailure("Entity terminated")
    stashedCommands.foreach {
      case (_, replyTo, _) => replyTo ! errorNotification
    }
  }

  // only the msg is returned to the user, while the details are also part of the exception
  private[this] final def crash(msg: String, details: String): Unit = {
    notifyOutstandingRequests(msg)
    throw new Exception(s"$msg - $details")
  }

  private[this] final def reportActionComplete() =
    statsCollector ! StatsCollector.ReplyReceived(System.nanoTime() - commandStartTime)

  private[this] final def handleCommand(entityCommand: EntityCommand, sender: ActorRef): Unit = {
    instrumentation.commandStarted()
    idCounter += 1
    val command = Command(
      entityId = entityId,
      id = idCounter,
      name = entityCommand.name,
      payload = entityCommand.payload
    )
    currentCommand =
      EventSourcedEntity.OutstandingCommand(idCounter, actorId + ":" + entityId + ":" + idCounter, sender)
    commandStartTime = System.nanoTime()
    relay ! EventSourcedStreamIn(EventSourcedStreamIn.Message.Command(command))
    statsCollector ! StatsCollector.CommandSent
  }

  private final def esReplyToUfReply(reply: EventSourcedReply) =
    UserFunctionReply(
      clientAction = reply.clientAction,
      sideEffects = reply.sideEffects
    )

  private final def createFailure(message: String) =
    UserFunctionReply(
      clientAction = Some(ClientAction(ClientAction.Action.Failure(Failure(description = message))))
    )

  override final def receiveCommand: PartialFunction[Any, Unit] = {

    case command: EntityCommand if currentCommand != null =>
      stashedCommands = stashedCommands.enqueue((command, sender(), instrumentation.commandStashed()))

    case command: EntityCommand =>
      log.debug("Entity [{}] [{}] received command [{}]", configuration.serviceName, entityId, command.name)
      instrumentation.commandReceived()
      handleCommand(command, sender())

    case EventSourcedStreamOut(m, _) =>
      import EventSourcedStreamOut.{Message => ESOMsg}
      m match {

        case ESOMsg.Reply(r) if currentCommand == null =>
          crash("Unexpected entity reply", s"(no current command) - $r")

        case ESOMsg.Reply(r) if currentCommand.commandId != r.commandId =>
          crash("Unexpected entity reply", s"(expected id ${currentCommand.commandId} but got ${r.commandId}) - $r")

        case ESOMsg.Reply(r) =>
          if (r.clientAction.exists(_.action.isFailure)) instrumentation.commandFailed()
          instrumentation.commandProcessed()
          reportActionComplete()
          val commandId = currentCommand.commandId
          val events = r.events.toVector
          if (events.isEmpty) {
            currentCommand.replyTo ! esReplyToUfReply(r)
            commandHandled()
          } else {
            instrumentation.persistStarted()
            reportDatabaseOperationStarted()
            var eventsLeft = events.size
            persistAll(events) { event =>
              eventsLeft -= 1
              instrumentation.eventPersisted(event.serializedSize)
              if (eventsLeft <= 0) { // Remove this hack when switching to Akka Persistence Typed
                instrumentation.persistCompleted() // note: this doesn't include saving snapshots
                reportDatabaseOperationFinished()
                r.snapshot.foreach { snapshot =>
                  saveSnapshot(snapshot)
                  // snapshot has not yet been successfully saved, but we need the size
                  instrumentation.snapshotPersisted(snapshot.serializedSize)
                }
                // Make sure that the current request is still ours
                if (currentCommand == null || currentCommand.commandId != commandId) {
                  crash("Unexpected entity behavior", "currentRequest changed before all events were persisted")
                }
                currentCommand.replyTo ! esReplyToUfReply(r)
                commandHandled()
              }
            }
          }

        case ESOMsg.Failure(f) if f.commandId == 0 =>
          crash("Unexpected entity failure", s"(not command specific) - ${f.description}")

        case ESOMsg.Failure(f) if currentCommand == null =>
          crash("Unexpected entity failure", s"(no current command) - ${f.description}")

        case ESOMsg.Failure(f) if currentCommand.commandId != f.commandId =>
          crash("Unexpected entity failure",
                s"(expected id ${currentCommand.commandId} but got ${f.commandId}) - ${f.description}")

        case ESOMsg.Failure(f) =>
          instrumentation.commandFailed()
          instrumentation.commandProcessed()
          instrumentation.commandCompleted()
          reportActionComplete()
          try crash("Unexpected entity failure", f.description)
          finally currentCommand = null // clear command after notifications

        case ESOMsg.Empty =>
          // Either the reply/failure wasn't set, or its set to something unknown.
          // todo see if scalapb can give us unknown fields so we can possibly log more intelligently
          crash("Unexpected entity message", "empty or unknown message from entity output stream")
      }

    case EventSourcedEntity.StreamClosed =>
      notifyOutstandingRequests("Unexpected entity termination")
      context.stop(self)

    case EventSourcedEntity.StreamFailed(error) =>
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

  private[this] final def maybeInit(snapshot: Option[SnapshotOffer]): Unit =
    if (!inited) {
      relay ! EventSourcedStreamIn(
        EventSourcedStreamIn.Message.Init(
          EventSourcedInit(
            serviceName = configuration.serviceName,
            entityId = entityId,
            snapshot = snapshot.map {
              case SnapshotOffer(metadata, offeredSnapshot: pbAny) =>
                EventSourcedSnapshot(metadata.sequenceNr, Some(offeredSnapshot))
              case other => throw new IllegalStateException(s"Unexpected snapshot type received: ${other.getClass}")
            }
          )
        )
      )
      inited = true
    }

  override final def receiveRecover: PartialFunction[Any, Unit] = {
    case offer: SnapshotOffer =>
      offer.snapshot match {
        case snapshot: pbAny => instrumentation.snapshotLoaded(snapshot.serializedSize)
        case _ =>
      }
      maybeInit(Some(offer))

    case RecoveryCompleted =>
      instrumentation.recoveryCompleted()
      reportDatabaseOperationFinished()
      maybeInit(None)

    case event: pbAny =>
      instrumentation.eventLoaded(event.serializedSize)
      maybeInit(None)
      relay ! EventSourcedStreamIn(EventSourcedStreamIn.Message.Event(EventSourcedEvent(lastSequenceNr, Some(event))))
  }

  private def reportDatabaseOperationStarted(): Unit =
    if (reportedDatabaseOperationStarted) {
      log.warning("Already reported database operation started")
    } else {
      databaseOperationStartTime = System.nanoTime()
      reportedDatabaseOperationStarted = true
      statsCollector ! StatsCollector.DatabaseOperationStarted
    }

  private def reportDatabaseOperationFinished(): Unit =
    if (!reportedDatabaseOperationStarted) {
      log.warning("Hadn't reported database operation started")
    } else {
      reportedDatabaseOperationStarted = false
      statsCollector ! StatsCollector.DatabaseOperationFinished(System.nanoTime() - databaseOperationStartTime)
    }
}
