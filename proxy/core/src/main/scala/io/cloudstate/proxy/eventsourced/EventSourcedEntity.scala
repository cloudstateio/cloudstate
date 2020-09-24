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
import akka.actor.SupervisorStrategy.{Decider, Restart, Stop}
import akka.actor._
import akka.cloudstate.EntityStash
import akka.cluster.sharding.ShardRegion
import akka.persistence._
import akka.stream.scaladsl._
import akka.stream.{CompletionStrategy, Materializer, OverflowStrategy}
import akka.util.Timeout
import com.google.protobuf.any.{Any => pbAny}
import io.cloudstate.protocol.entity._
import io.cloudstate.protocol.event_sourced._
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}
import io.cloudstate.proxy.telemetry.CloudstateTelemetry

import scala.collection.immutable.Queue
import scala.util.control.NoStackTrace

object EventSourcedEntitySupervisor {
  def props(client: EventSourcedClient,
            configuration: EventSourcedEntity.Configuration)(implicit mat: Materializer): Props =
    Props(new EventSourcedEntitySupervisor(client, configuration))
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
final class EventSourcedEntitySupervisor(client: EventSourcedClient, configuration: EventSourcedEntity.Configuration)(
    implicit mat: Materializer
) extends Actor
    with Stash {

  val entityId = URLDecoder.decode(self.path.name, "utf-8")

  val relay = context.watch(context.actorOf(EventSourcedEntityRelay.props(client, configuration), "relay"))
  val entity = context.watch(context.actorOf(EventSourcedEntity.props(configuration, entityId, relay), "entity"))

  relay ! EventSourcedEntityRelay.Connect(entity)

  var relayTerminated: Boolean = false
  var entityTerminated: Boolean = false

  override def receive: Receive = {
    case Terminated(`relay`) =>
      relayTerminated = true
      if (entityTerminated) context.stop(self)
    case Terminated(`entity`) =>
      entityTerminated = true
      if (relayTerminated) context.stop(self)
      else relay ! EventSourcedEntityRelay.Disconnect
    case toParent if sender() == entity =>
      context.parent ! toParent
    case msg =>
      entity forward msg
  }

  val failureDecider: Decider = {
    case _: EventSourcedEntity.RestartFailure => Restart
    case _: Exception => Stop
  }

  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy()(failureDecider)
}

object EventSourcedEntityRelay {
  final case class Connect(entity: ActorRef)
  final case class Connected(stream: ActorRef)
  final case object Disconnect
  final case object Reconnect
  final case class Fail(cause: Throwable)

  def props(client: EventSourcedClient,
            configuration: EventSourcedEntity.Configuration)(implicit mat: Materializer): Props =
    Props(new EventSourcedEntityRelay(client, configuration))
}

final class EventSourcedEntityRelay(client: EventSourcedClient, configuration: EventSourcedEntity.Configuration)(
    implicit mat: Materializer
) extends Actor
    with Stash {
  import EventSourcedEntityRelay._

  override def receive: Receive = starting

  def starting: Receive = {
    case Connect(entity) => connect(entity)
    case _ => stash()
  }

  def connect(entity: ActorRef): Unit = {
    client
      .handle(
        Source
          .actorRef[EventSourcedStreamIn](
            { case Disconnect => CompletionStrategy.draining }: PartialFunction[Any, CompletionStrategy],
            { case Fail(cause) => cause }: PartialFunction[Any, Throwable],
            configuration.sendQueueSize,
            OverflowStrategy.fail
          )
          .mapMaterializedValue { ref =>
            self ! Connected(ref)
            NotUsed
          }
      )
      .runWith(Sink.actorRef(self, EventSourcedEntity.StreamClosed, EventSourcedEntity.StreamFailed.apply))
    context.become(connecting(entity))
  }

  def connecting(entity: ActorRef): Receive = {
    case Connected(stream) =>
      context.become(relaying(entity, stream))
      unstashAll()
    case _ => stash()
  }

  def relaying(entity: ActorRef, stream: ActorRef): Receive = {
    case Disconnect =>
      stream ! Disconnect
      context.become(disconnecting)
    case Reconnect =>
      stream ! Disconnect
      context.become(reconnecting(entity))
    case fail: Fail =>
      stream ! fail
      context.become(disconnecting)
    case message if sender() == entity =>
      stream ! message
    case message =>
      entity ! message
      if (streamTerminated(message)) context.stop(self)
  }

  def disconnecting: Receive = {
    case message if streamTerminated(message) => context.stop(self)
    case _ => // ignore
  }

  def reconnecting(entity: ActorRef): Receive = {
    case message if streamTerminated(message) => connect(entity)
    case _ => stash()
  }

  def streamTerminated(message: Any): Boolean = message match {
    case EventSourcedEntity.StreamClosed => true
    case _: EventSourcedEntity.StreamFailed => true
    case _ => false
  }
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

  final class RestartFailure(message: String) extends RuntimeException(message) with NoStackTrace

  final def props(configuration: Configuration, entityId: String, relay: ActorRef): Props =
    Props(new EventSourcedEntity(configuration, entityId, relay))

  /**
   * Used to ensure the action ids sent to the concurrency enforcer are indeed unique.
   */
  private val actorCounter = new AtomicLong(0)
}

final class EventSourcedEntity(configuration: EventSourcedEntity.Configuration, entityId: String, relay: ActorRef)
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
  private[this] final var commandStartTime = 0L

  private[this] val instrumentation =
    CloudstateTelemetry(context.system).eventSourcedEntityInstrumentation(configuration.userFunctionName)

  instrumentation.entityActivated()
  instrumentation.recoveryStarted()

  // Set up passivation timer
  context.setReceiveTimeout(configuration.passivationTimeout.duration)

  override final def postStop(): Unit = {
    if (currentCommand != null) {
      log.warning("Stopped but we have a current action id {}", currentCommand.actionId)
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

  def restartFailure(description: String): Unit = {
    instrumentation.commandCompleted()
    stashedCommands.reverseIterator foreach {
      case (command, replyTo, context) =>
        instrumentation.commandUnstashed(context)
        EntityStash.unstash(this, command, replyTo) // prepend to mailbox again
    }
    relay ! EventSourcedEntityRelay.Reconnect
    throw new EventSourcedEntity.RestartFailure(s"Restarting entity [$entityId] after failure: $description")
  }

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
          instrumentation.commandProcessed()
          r.clientAction match {
            case Some(ClientAction(ClientAction.Action.Failure(Failure(_, description, restart, _)), _)) =>
              instrumentation.commandFailed()
              if (restart) {
                currentCommand.replyTo ! esReplyToUfReply(r)
                restartFailure(description)
              }
            case _ =>
          }
          val commandId = currentCommand.commandId
          val events = r.events.toVector
          if (events.isEmpty) {
            currentCommand.replyTo ! esReplyToUfReply(r)
            commandHandled()
          } else {
            instrumentation.persistStarted()
            var eventsLeft = events.size
            persistAll(events) { event =>
              eventsLeft -= 1
              instrumentation.eventPersisted(event.serializedSize)
              if (eventsLeft <= 0) { // Remove this hack when switching to Akka Persistence Typed
                instrumentation.persistCompleted() // note: this doesn't include saving snapshots
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
      maybeInit(None)

    case event: pbAny =>
      instrumentation.eventLoaded(event.serializedSize)
      maybeInit(None)
      relay ! EventSourcedStreamIn(EventSourcedStreamIn.Message.Event(EventSourcedEvent(lastSequenceNr, Some(event))))
  }
}
