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

package com.lightbend.statefulserverless

import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong

import akka.NotUsed
import akka.actor._
import akka.util.Timeout
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl._
import akka.persistence.{PersistentActor, RecoveryCompleted, SaveSnapshotFailure, SaveSnapshotSuccess, SnapshotOffer}
import akka.cluster.sharding.ShardRegion
import com.lightbend.statefulserverless.grpc._
import EntityStreamIn.{Message => ESIMsg}
import com.google.protobuf.any.{Any => pbAny}
import com.lightbend.statefulserverless.ConcurrencyEnforcer.{Action, ActionCompleted}
import com.lightbend.statefulserverless.StateManager.CommandFailure
import com.lightbend.statefulserverless.internal.grpc.{GrpcEntityCommand, Request}

import scala.collection.immutable.Queue

object StateManagerSupervisor {

  final case class Relay(actorRef: ActorRef)

  def props(client: EntityClient, configuration: StateManager.Configuration, concurrencyEnforcer: ActorRef)(implicit mat: Materializer): Props =
    Props(new StateManagerSupervisor(client, configuration, concurrencyEnforcer))
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
final class StateManagerSupervisor(client: EntityClient, configuration: StateManager.Configuration, concurrencyEnforcer: ActorRef)(implicit mat: Materializer)
  extends Actor with Stash {

  import StateManagerSupervisor._

  override final def receive: Receive = PartialFunction.empty

  override final def preStart(): Unit = {
    client.handle(Source.actorRef[EntityStreamIn](configuration.sendQueueSize, OverflowStrategy.fail)
      .mapMaterializedValue { ref =>
        self ! Relay(ref)
        NotUsed
      }).runWith(Sink.actorRef(self, StateManager.StreamClosed))
    context.become(waitingForRelay)
  }

  private[this] final def waitingForRelay: Receive = {
    case Relay(relayRef) =>
      // Cluster sharding URL encodes entity ids, so to extract it we need to decode.
      val entityId = URLDecoder.decode(self.path.name, "utf-8")
      val manager = context.watch(context.actorOf(StateManager.props(configuration, entityId, relayRef, concurrencyEnforcer), "entity"))
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

object StateManager {

  final case object Stop

  final case object StreamClosed extends DeadLetterSuppression

  final case class Configuration(
    userFunctionName: String,
    passivationTimeout: Timeout,
    sendQueueSize: Int
  )

  private final case class OutstandingRequest(
    commandId: Long,
    replyTo: ActorRef
  )

  /**
    * Exception indicating a failure that has been signalled by the user function
    * in response to a command.
    */
  final case class CommandFailure(msg: String) extends RuntimeException(msg)

  final def props(configuration: Configuration, entityId: String, relay: ActorRef, concurrencyEnforcer: ActorRef): Props =
    Props(new StateManager(configuration, entityId, relay, concurrencyEnforcer))

  /**
    * Used to ensure the action ids sent to the concurrency enforcer are indeed unique.
    */
  private val actorCounter = new AtomicLong(0)
}

final class StateManager(configuration: StateManager.Configuration, entityId: String, relay: ActorRef, concurrencyEnforcer: ActorRef) extends PersistentActor with ActorLogging {
  override final def persistenceId: String = configuration.userFunctionName + entityId

  private val actorId = StateManager.actorCounter.incrementAndGet()

  private[this] final var stashedRequests = Queue.empty[(Request, ActorRef)] // PERFORMANCE: look at options for data structures
  private[this] final var currentRequest: StateManager.OutstandingRequest = null
  private[this] final var stopped = false
  private[this] final var idCounter = 0l
  private[this] final var inited = false
  private[this] final var currentActionId: String = null

  // Set up passivation timer
  context.setReceiveTimeout(configuration.passivationTimeout.duration)

  override final def postStop(): Unit = {
    if (currentActionId != null) {
      log.warning("Stopped but we have a current action id {}", currentActionId)
      reportActionComplete()
    }
    // This will shutdown the stream (if not already shut down)
    relay ! Status.Success(())
  }

  private[this] final def commandHandled(): Unit = {
    currentRequest = null
    if (stashedRequests.nonEmpty) {
      val ((request, sender), newStashedCommands) = stashedRequests.dequeue
      stashedRequests = newStashedCommands
      handleCommand(request, sender)
    } else if (stopped) {
      context.stop(self)
    }
  }

  private[this] final def notifyOutstandingRequests(msg: String): Unit = {
    currentRequest match {
      case null =>
      case req => req.replyTo ! Status.Failure(new Exception(msg))
    }
    val errorNotification = Status.Failure(new Exception("Entity terminated"))
    stashedRequests.foreach {
      case (_, replyTo) => replyTo ! errorNotification
    }
  }

  private[this] final def crash(msg: String): Unit = {
    notifyOutstandingRequests(msg)
    throw new Exception(msg)
  }

  private[this] final def reportActionComplete() = {
    concurrencyEnforcer ! ActionCompleted(currentActionId)
    currentActionId = null
  }

  private[this] final def handleCommand(request: Request, sender: ActorRef): Unit = {
    val Request(Some(GrpcEntityCommand(_, name, payload)), isProxied) = request
    idCounter += 1
    currentActionId = actorId + ":" + entityId + ":" + idCounter
    val command = Command(
      entityId = entityId,
      id = idCounter,
      name = name,
      payload = payload
    )
    currentRequest = StateManager.OutstandingRequest(idCounter, sender)
    concurrencyEnforcer ! Action(currentActionId, isProxied, () => {
      relay ! EntityStreamIn(ESIMsg.Command(command))
    })
  }

  override final def receiveCommand: PartialFunction[Any, Unit] = {

    case r: Request if currentRequest != null =>
      stashedRequests = stashedRequests.enqueue((r, sender()))

    case r: Request =>
      handleCommand(r, sender())

    case EntityStreamOut(m) =>

      import EntityStreamOut.{Message => ESOMsg}
      m match {

        case ESOMsg.Reply(r) if currentRequest == null =>
          crash(s"Unexpected reply, had no current request: $r")

        case ESOMsg.Reply(r) if currentRequest.commandId != r.commandId =>
          crash(s"Incorrect command id in reply, expecting ${currentRequest.commandId} but got ${r.commandId}")

        case ESOMsg.Reply(r) =>
          reportActionComplete()
          val commandId = currentRequest.commandId
          val events = r.events.toVector
          if (events.isEmpty) {
            currentRequest.replyTo ! r.getPayload.value
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
                currentRequest.replyTo ! r.getPayload.value
                commandHandled()
              }
            }
          }

        case ESOMsg.Failure(f) if f.commandId == 0 =>
          crash(s"Non command specific error from entity: ${f.description}")

        case ESOMsg.Failure(f) if currentRequest == null =>
          crash(s"Unexpected failure, had no current request: $f")

        case ESOMsg.Failure(f) if currentRequest.commandId != f.commandId =>
          crash(s"Incorrect command id in failure, expecting ${currentRequest.commandId} but got ${f.commandId}")

        case ESOMsg.Failure(f) =>
          reportActionComplete()
          currentRequest.replyTo ! Status.Failure(CommandFailure(f.description))
          commandHandled()

        case ESOMsg.Empty =>
          // Either the reply/failure wasn't set, or its set to something unknown.
          // todo see if scalapb can give us unknown fields so we can possibly log more intelligently
          crash("Empty or unknown message from entity output stream")
      }

    case StateManager.StreamClosed =>
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
      context.parent ! ShardRegion.Passivate(stopMessage = StateManager.Stop)

    case StateManager.Stop =>
      stopped = true
      if (currentRequest == null) {
        context.stop(self)
      }
  }

  private[this] final def maybeInit(snapshot: Option[SnapshotOffer]): Unit = {
    if (!inited) {
      relay ! EntityStreamIn(ESIMsg.Init(Init(entityId, snapshot.map {
        case SnapshotOffer(metadata, offeredSnapshot: pbAny) => Snapshot(metadata.sequenceNr, Some(offeredSnapshot))
        case other => throw new IllegalStateException(s"Unexpected snapshot type received: ${other.getClass}")
      })))
      inited = true
    }
  }

  override final def receiveRecover: PartialFunction[Any, Unit] = {
    case offer: SnapshotOffer =>
      maybeInit(Some(offer))

    case RecoveryCompleted =>
      maybeInit(None)

    case event: pbAny =>
      maybeInit(None)
      relay ! EntityStreamIn(ESIMsg.Event(Event(lastSequenceNr, Some(event))))
  }
}