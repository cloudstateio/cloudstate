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

package io.cloudstate.proxy.crudtwo

import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong

import akka.actor._
import akka.cluster.sharding.ShardRegion
import akka.persistence._
import akka.stream.Materializer
import akka.util.Timeout
import com.google.protobuf.any.{Any => pbAny}
import io.cloudstate.protocol.crud_two.{CreateCommand, CrudCommand, CrudCommandType, CrudFetchReplies, CrudFetchReply, CrudInitCommand, CrudReplies, CrudReply, CrudState, CrudTwoClient, DeleteCommand, FetchCommand, UpdateCommand}
import io.cloudstate.protocol.entity._
import io.cloudstate.proxy.ConcurrencyEnforcer.{Action, ActionCompleted}
import io.cloudstate.proxy.StatsCollector
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}

import scala.collection.immutable.Queue

object CrudEntitySupervisor {

  private final case class Relay(actorRef: ActorRef)
  private final case object Start

  def props(client: CrudTwoClient,
            configuration: CrudEntity.Configuration,
            concurrencyEnforcer: ActorRef,
            statsCollector: ActorRef)(implicit mat: Materializer): Props =
    Props(new CrudEntitySupervisor(client, configuration, concurrencyEnforcer, statsCollector))
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
final class CrudEntitySupervisor(client: CrudTwoClient,
                                 configuration: CrudEntity.Configuration,
                                 concurrencyEnforcer: ActorRef,
                                 statsCollector: ActorRef)(implicit mat: Materializer)
    extends Actor
    with Stash {

  import CrudEntitySupervisor._

  override final def receive: Receive = PartialFunction.empty

  override final def preStart(): Unit = {
    self ! Start
    context.become(waitingForRelay)
  }

  private[this] final def waitingForRelay: Receive = {
    case Start =>
      // Cluster sharding URL encodes entity ids, so to extract it we need to decode.
      val entityId = URLDecoder.decode(self.path.name, "utf-8")
      val manager = context.watch(
        context
          .actorOf(CrudEntity.props(configuration, entityId, client, concurrencyEnforcer, statsCollector), "entity")
      )
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

object CrudEntity {

  final case object Stop

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

  final def props(configuration: Configuration,
                  entityId: String,
                  client: CrudTwoClient,
                  concurrencyEnforcer: ActorRef,
                  statsCollector: ActorRef): Props =
    Props(new CrudEntity(configuration, entityId, client, concurrencyEnforcer, statsCollector))

  /**
   * Used to ensure the action ids sent to the concurrency enforcer are indeed unique.
   */
  private val actorCounter = new AtomicLong(0)
}

final class CrudEntity(configuration: CrudEntity.Configuration,
                       entityId: String,
                       client: CrudTwoClient,
                       concurrencyEnforcer: ActorRef,
                       statsCollector: ActorRef)
    extends PersistentActor
    with ActorLogging {

  import context.dispatcher
  import akka.pattern.pipe

  override final def persistenceId: String = configuration.userFunctionName + entityId

  private val actorId = CrudEntity.actorCounter.incrementAndGet()

  private[this] final var state = CrudState(None)

  private[this] final var stashedCommands = Queue.empty[(CrudInitCommand, ActorRef)] // PERFORMANCE: look at options for data structures
  private[this] final var currentCommand: CrudEntity.OutstandingCommand = null
  private[this] final var stopped = false
  private[this] final var idCounter = 0L
  private[this] final var reportedDatabaseOperationStarted = false
  private[this] final var databaseOperationStartTime = 0L
  private[this] final var commandStartTime = 0L

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

  private[this] final def reportActionComplete() =
    concurrencyEnforcer ! ActionCompleted(currentCommand.actionId, System.nanoTime() - commandStartTime)

  private[this] final def handleCommand(entityCommand: EntityCommand, sender: ActorRef): Unit = {
    idCounter += 1
    val command = CreateCommand(
      entityId = entityId,
      subEntityId = entityCommand.entityId,
      id = idCounter,
      name = entityCommand.name,
      payload = entityCommand.payload,
      CrudCommandType.CREATE,
      Some(state)
    )
    currentCommand = CrudEntity.OutstandingCommand(idCounter, actorId + ":" + entityId + ":" + idCounter, sender)
    commandStartTime = System.nanoTime()
    concurrencyEnforcer ! Action(currentCommand.actionId, () => {
      self ! command
    })
  }

  private[this] final def handleCommand(initCommand: CrudInitCommand, sender: ActorRef): Unit = {
    idCounter += 1
    val command = CrudCommand(
      entityId = entityId,
      subEntityId = initCommand.entityId,
      id = idCounter,
      name = initCommand.name,
      payload = initCommand.payload,
      Some(state)
    )
    currentCommand = CrudEntity.OutstandingCommand(idCounter, actorId + ":" + entityId + ":" + idCounter, sender)
    commandStartTime = System.nanoTime()
    concurrencyEnforcer ! Action(
      currentCommand.actionId,
      () => {
        initCommand.`type` match {
          case CrudCommandType.CREATE =>
            client.create(command) pipeTo self

          case CrudCommandType.FETCH =>
            client.fetch(command) pipeTo self

          case CrudCommandType.UPDATE =>
            client.update(command) pipeTo self

          case CrudCommandType.DELETE =>
            client.delete(command) pipeTo self
        }
      }
    )
  }

  private final def esReplyToUfReply(reply: CrudReply): UserFunctionReply =
    UserFunctionReply(
      clientAction = reply.clientAction,
      sideEffects = reply.sideEffects
    )

  private final def esReplyToUfReply(reply: CrudFetchReply): UserFunctionReply =
    UserFunctionReply(
      clientAction = reply.clientAction,
      sideEffects = reply.sideEffects
    )

  private final def createFailure(message: String) =
    UserFunctionReply(
      clientAction = Some(ClientAction(ClientAction.Action.Failure(Failure(description = message))))
    )

  override final def receiveCommand: PartialFunction[Any, Unit] = {

    case command: CrudInitCommand if currentCommand != null =>
      stashedCommands = stashedCommands.enqueue((command, sender()))

    case command: CrudInitCommand =>
      handleCommand(command, sender())

    case CrudReplies(m, _) =>
      m match {
        case CrudReplies.Message.Reply(r) =>
          reportActionComplete()
          val commandId = currentCommand.commandId
          r.state match {
            case None =>
              currentCommand.replyTo ! esReplyToUfReply(r)
              commandHandled()
            case Some(event) =>
              reportDatabaseOperationStarted()
              persistAll(List(event)) { _ =>
                state = CrudState(Some(event))
                reportDatabaseOperationFinished()
                // Make sure that the current request is still ours
                if (currentCommand == null || currentCommand.commandId != commandId) {
                  crash("Internal error - currentRequest changed before all events were persisted")
                }
                currentCommand.replyTo ! esReplyToUfReply(r)
                commandHandled()
              }
          }

        case otherReply => // what to do here?
      }

    case CrudFetchReplies(m, _) =>
      m match {
        case CrudFetchReplies.Message.Reply(r) =>
          reportActionComplete()
          currentCommand.replyTo ! esReplyToUfReply(r)
          commandHandled()

        case otherReply => // what to do here?
      }

    case Status.Failure(error) =>
      notifyOutstandingRequests("Unexpected entity termination")
      throw error

    case SaveSnapshotSuccess(metadata) =>
    // Nothing to do

    case SaveSnapshotFailure(metadata, cause) =>
      log.error("Error saving snapshot", cause)

    case ReceiveTimeout =>
      context.parent ! ShardRegion.Passivate(stopMessage = CrudEntity.Stop)

    case CrudEntity.Stop =>
      stopped = true
      if (currentCommand == null) {
        context.stop(self)
      }
  }

  override final def receiveRecover: PartialFunction[Any, Unit] = {
    case offer: SnapshotOffer =>
    // is it needed??

    case RecoveryCompleted =>
      reportDatabaseOperationFinished()

    case event: pbAny =>
      state = CrudState(Some(event))
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
