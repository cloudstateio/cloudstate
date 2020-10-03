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

package io.cloudstate.proxy.crud

import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong

import akka.NotUsed
import akka.actor._
import akka.cluster.sharding.ShardRegion
import akka.pattern.pipe
import akka.stream.scaladsl._
import akka.stream.{CompletionStrategy, Materializer, OverflowStrategy}
import akka.util.Timeout
import io.cloudstate.protocol.crud.CrudAction.Action.{Delete, Update}
import io.cloudstate.protocol.crud.{
  CrudAction,
  CrudClient,
  CrudInit,
  CrudInitState,
  CrudReply,
  CrudStreamIn,
  CrudStreamOut,
  CrudUpdate
}
import io.cloudstate.protocol.entity._
import io.cloudstate.proxy.crud.store.JdbcRepository
import io.cloudstate.proxy.crud.store.JdbcStore.Key
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}

import scala.collection.immutable.Queue
import scala.concurrent.Future

object CrudEntitySupervisor {

  private final case class Relay(actorRef: ActorRef)

  def props(client: CrudClient, configuration: CrudEntity.Configuration, repository: JdbcRepository)(
      implicit mat: Materializer
  ): Props =
    Props(new CrudEntitySupervisor(client, configuration, repository))
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
final class CrudEntitySupervisor(client: CrudClient,
                                 configuration: CrudEntity.Configuration,
                                 repository: JdbcRepository)(implicit mat: Materializer)
    extends Actor
    with Stash {

  import CrudEntitySupervisor._

  private var streamTerminated: Boolean = false

  override final def receive: Receive = PartialFunction.empty

  override final def preStart(): Unit = {
    client
      .handle(
        Source
          .actorRef[CrudStreamIn](configuration.sendQueueSize, OverflowStrategy.fail)
          .mapMaterializedValue { ref =>
            self ! Relay(ref)
            NotUsed
          }
      )
      .runWith(Sink.actorRef(self, CrudEntity.StreamClosed, CrudEntity.StreamFailed.apply))
    context.become(waitingForRelay)
  }

  private[this] final def waitingForRelay: Receive = {
    case Relay(relayRef) =>
      // Cluster sharding URL encodes entity ids, so to extract it we need to decode.
      val entityId = URLDecoder.decode(self.path.name, "utf-8")
      val entity = context.watch(
        context
          .actorOf(CrudEntity.props(configuration, entityId, relayRef, repository), "entity")
      )
      context.become(forwarding(entity, relayRef))
      unstashAll()
    case _ => stash()
  }

  private[this] final def forwarding(entity: ActorRef, relay: ActorRef): Receive = {
    case Terminated(`entity`) =>
      if (streamTerminated) {
        context.stop(self)
      } else {
        relay ! Status.Success(CompletionStrategy.draining)
        context.become(stopping)
      }

    case message if sender() == entity =>
      context.parent ! message

    case CrudEntity.StreamClosed =>
      streamTerminated = true
      entity forward CrudEntity.StreamClosed

    case failed: CrudEntity.StreamFailed =>
      streamTerminated = true
      entity forward failed

    case message =>
      entity forward message
  }

  private def stopping: Receive = {
    case CrudEntity.StreamClosed =>
      context.stop(self)
    case _: CrudEntity.StreamFailed =>
      context.stop(self)
  }

  override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy
}

object CrudEntity {

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

  private case class ReadStateSuccess(initialized: Boolean)
  private case class ReadStateFailure(cause: Throwable)

  private sealed trait DatabaseOperationWriteStatus
  private case object WriteStateSuccess extends DatabaseOperationWriteStatus
  private case class WriteStateFailure(cause: Throwable) extends DatabaseOperationWriteStatus

  final def props(configuration: Configuration, entityId: String, relay: ActorRef, repository: JdbcRepository): Props =
    Props(new CrudEntity(configuration, entityId, relay, repository))

  /**
   * Used to ensure the action ids sent to the concurrency enforcer are indeed unique.
   */
  private val actorCounter = new AtomicLong(0)

}

final class CrudEntity(configuration: CrudEntity.Configuration,
                       entityId: String,
                       relay: ActorRef,
                       repository: JdbcRepository)
    extends Actor
    with Stash
    with ActorLogging {

  private implicit val ec = context.dispatcher

  private val persistenceId: String = configuration.userFunctionName + entityId

  private val actorId = CrudEntity.actorCounter.incrementAndGet()

  private[this] final var stashedCommands = Queue.empty[(EntityCommand, ActorRef)] // PERFORMANCE: look at options for data structures
  private[this] final var currentCommand: CrudEntity.OutstandingCommand = null
  private[this] final var stopped = false
  private[this] final var idCounter = 0L
  private[this] final var inited = false
  private[this] final var commandStartTime = 0L

  // Set up passivation timer
  context.setReceiveTimeout(configuration.passivationTimeout.duration)

  override final def preStart(): Unit =
    repository
      .get(Key(persistenceId, entityId))
      .map { state =>
        if (!inited) {
          relay ! CrudStreamIn(
            CrudStreamIn.Message.Init(
              CrudInit(
                serviceName = configuration.serviceName,
                entityId = entityId,
                state = Some(CrudInitState(state))
              )
            )
          )
          inited = true
        }
        CrudEntity.ReadStateSuccess(inited)
      }
      .recover {
        case error => CrudEntity.ReadStateFailure(error)
      }
      .pipeTo(self)

  override final def postStop(): Unit =
    if (currentCommand != null) {
      log.warning("Stopped but we have a current action id {}", currentCommand.actionId)
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
    val errorNotification = createFailure("CRUD Entity terminated")
    stashedCommands.foreach {
      case (_, replyTo) => replyTo ! errorNotification
    }
  }

  // only the msg is returned to the user, while the details are also part of the exception
  private[this] final def crash(msg: String, details: String): Unit = {
    notifyOutstandingRequests(msg)
    throw new Exception(s"$msg - $details")
  }

  private[this] final def handleCommand(entityCommand: EntityCommand, sender: ActorRef): Unit = {
    idCounter += 1
    val command = Command(
      entityId = entityId,
      id = idCounter,
      name = entityCommand.name,
      payload = entityCommand.payload
    )
    currentCommand = CrudEntity.OutstandingCommand(idCounter, actorId + ":" + entityId + ":" + idCounter, sender)
    commandStartTime = System.nanoTime()
    relay ! CrudStreamIn(CrudStreamIn.Message.Command(command))
  }

  private final def esReplyToUfReply(reply: CrudReply) =
    UserFunctionReply(
      clientAction = reply.clientAction,
      sideEffects = reply.sideEffects
    )

  private final def createFailure(message: String) =
    UserFunctionReply(
      clientAction = Some(ClientAction(ClientAction.Action.Failure(Failure(description = message))))
    )

  override final def receive: Receive = {
    case CrudEntity.ReadStateSuccess(initialize) =>
      if (initialize) {
        context.become(running)
        unstashAll()
      }

    case CrudEntity.ReadStateFailure(error) =>
      throw error

    case _ => stash()
  }

  private def running: Receive = {

    case command: EntityCommand if currentCommand != null =>
      stashedCommands = stashedCommands.enqueue((command, sender()))

    case command: EntityCommand =>
      handleCommand(command, sender())

    case CrudStreamOut(m, _) =>
      import CrudStreamOut.{Message => CrudSOMsg}
      m match {

        case CrudSOMsg.Reply(r) if currentCommand == null =>
          crash("Unexpected CRUD entity reply", s"(no current command) - $r")

        case CrudSOMsg.Reply(r) if currentCommand.commandId != r.commandId =>
          crash("Unexpected CRUD entity reply",
                s"(expected id ${currentCommand.commandId} but got ${r.commandId}) - $r")

        case CrudSOMsg.Reply(r) =>
          val commandId = currentCommand.commandId
          if (r.crudAction.isEmpty) {
            currentCommand.replyTo ! esReplyToUfReply(r)
            commandHandled()
          } else {
            r.crudAction.map { a =>
              performAction(a) { _ =>
                // Make sure that the current request is still ours
                if (currentCommand == null || currentCommand.commandId != commandId) {
                  crash("Unexpected CRUD entity behavior", "currentRequest changed before the state were persisted")
                }
                currentCommand.replyTo ! esReplyToUfReply(r)
                commandHandled()
              }.pipeTo(self)
            }
          }

        case CrudSOMsg.Failure(f) if f.commandId == 0 =>
          crash("Unexpected CRUD entity failure", s"(not command specific) - ${f.description}")

        case CrudSOMsg.Failure(f) if currentCommand == null =>
          crash("Unexpected CRUD entity failure", s"(no current command) - ${f.description}")

        case CrudSOMsg.Failure(f) if currentCommand.commandId != f.commandId =>
          crash("Unexpected CRUD entity failure",
                s"(expected id ${currentCommand.commandId} but got ${f.commandId}) - ${f.description}")

        case CrudSOMsg.Failure(f) =>
          try crash("Unexpected CRUD entity failure", f.description)
          finally currentCommand = null // clear command after notifications

        case CrudSOMsg.Empty =>
          // Either the reply/failure wasn't set, or its set to something unknown.
          // todo see if scalapb can give us unknown fields so we can possibly log more intelligently
          crash("Unexpected CRUD entity failure", "empty or unknown message from entity output stream")
      }

    case CrudEntity.WriteStateSuccess =>
    // Nothing to do, database write access the native crud database was successful

    case CrudEntity.WriteStateFailure(error) =>
      notifyOutstandingRequests("Unexpected CRUD entity failure")
      throw error

    case CrudEntity.StreamClosed =>
      notifyOutstandingRequests("Unexpected CRUD entity termination")
      context.stop(self)

    case CrudEntity.StreamFailed(error) =>
      notifyOutstandingRequests("Unexpected CRUD entity termination")
      throw error

    case CrudEntity.Stop =>
      stopped = true
      if (currentCommand == null) {
        context.stop(self)
      }

    case ReceiveTimeout =>
      context.parent ! ShardRegion.Passivate(stopMessage = CrudEntity.Stop)
  }

  private def performAction(
      crudAction: CrudAction
  )(handler: Unit => Unit): Future[CrudEntity.DatabaseOperationWriteStatus] =
    crudAction.action match {
      case Update(CrudUpdate(Some(value), _)) =>
        repository
          .update(Key(persistenceId, entityId), value)
          .map { _ =>
            handler(())
            CrudEntity.WriteStateSuccess
          }
          .recover {
            case error => CrudEntity.WriteStateFailure(error)
          }

      case Delete(_) =>
        repository
          .delete(Key(persistenceId, entityId))
          .map { _ =>
            handler(())
            CrudEntity.WriteStateSuccess
          }
          .recover {
            case error => CrudEntity.WriteStateFailure(error)
          }
    }
}
