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
import com.google.protobuf.any.{Any => pbAny}
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
import io.cloudstate.proxy.ConcurrencyEnforcer.{Action, ActionCompleted}
import io.cloudstate.proxy.StatsCollector
import io.cloudstate.proxy.crud.store.JdbcRepository
import io.cloudstate.proxy.crud.store.JdbcStore.Key
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}

import scala.collection.immutable.Queue
import scala.concurrent.Future

object CrudEntitySupervisor {

  private final case class Relay(actorRef: ActorRef)

  def props(client: CrudClient,
            configuration: CrudEntity.Configuration,
            concurrencyEnforcer: ActorRef,
            statsCollector: ActorRef,
            repository: JdbcRepository)(implicit mat: Materializer): Props =
    Props(new CrudEntitySupervisor(client, configuration, concurrencyEnforcer, statsCollector, repository))
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
                                 concurrencyEnforcer: ActorRef,
                                 statsCollector: ActorRef,
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
      .runWith(Sink.actorRef(self, CrudEntity.StreamClosed, CrudEntity.StreamFailed))
    context.become(waitingForRelay)
  }

  private[this] final def waitingForRelay: Receive = {
    case Relay(relayRef) =>
      // Cluster sharding URL encodes entity ids, so to extract it we need to decode.
      val entityId = URLDecoder.decode(self.path.name, "utf-8")
      val manager = context.watch(
        context
          .actorOf(CrudEntity.props(configuration, entityId, relayRef, concurrencyEnforcer, statsCollector, repository),
                   "entity")
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

    case CrudEntity.StreamClosed =>
      streamTerminated = true
      manager forward CrudEntity.StreamClosed

    case failed: CrudEntity.StreamFailed =>
      streamTerminated = true
      manager forward failed

    case msg =>
      manager forward msg
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

  private case object LoadInitStateSuccess
  private case class LoadInitStateFailure(cause: Throwable)

  private case object SaveStateSuccess

  final def props(configuration: Configuration,
                  entityId: String,
                  relay: ActorRef,
                  concurrencyEnforcer: ActorRef,
                  statsCollector: ActorRef,
                  repository: JdbcRepository): Props =
    Props(new CrudEntity(configuration, entityId, relay, concurrencyEnforcer, statsCollector, repository))

  /**
   * Used to ensure the action ids sent to the concurrency enforcer are indeed unique.
   */
  private val actorCounter = new AtomicLong(0)

}

final class CrudEntity(configuration: CrudEntity.Configuration,
                       entityId: String,
                       relay: ActorRef,
                       concurrencyEnforcer: ActorRef,
                       statsCollector: ActorRef,
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
  private[this] final var reportedDatabaseOperationStarted = false
  private[this] final var databaseOperationStartTime = 0L
  private[this] final var commandStartTime = 0L

  // Set up passivation timer
  context.setReceiveTimeout(configuration.passivationTimeout.duration)

  // First thing actor will do is access database
  reportDatabaseOperationStarted()

  override final def preStart(): Unit =
    repository
      .get(Key(persistenceId, entityId))
      .map { state =>
        // related to the first access to the database when the actor starts
        reportDatabaseOperationFinished()
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
        CrudEntity.LoadInitStateSuccess
      }
      .recover {
        case error => CrudEntity.LoadInitStateFailure(error)
      }
      .pipeTo(self)

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
    val command = Command(
      entityId = entityId,
      id = idCounter,
      name = entityCommand.name,
      payload = entityCommand.payload
    )
    currentCommand = CrudEntity.OutstandingCommand(idCounter, actorId + ":" + entityId + ":" + idCounter, sender)
    commandStartTime = System.nanoTime()
    concurrencyEnforcer ! Action(currentCommand.actionId, () => {
      relay ! CrudStreamIn(CrudStreamIn.Message.Command(command))
    })
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

  override final def receive: PartialFunction[Any, Unit] = waitingForInitState

  private def waitingForInitState: PartialFunction[Any, Unit] = {
    case CrudEntity.LoadInitStateSuccess if inited == true =>
    // ignore entity already initialized

    case CrudEntity.LoadInitStateSuccess =>
      context.become(initialized)
      unstashAll()

    case CrudEntity.LoadInitStateFailure(error) =>
      log.error(error, s"CRUD Entity cannot load the initial state due to unexpected failure ${error.getMessage}")
      throw error

    case _ => stash()
  }

  private def initialized: PartialFunction[Any, Unit] = {

    case command: EntityCommand if currentCommand != null =>
      stashedCommands = stashedCommands.enqueue((command, sender()))

    case command: EntityCommand =>
      handleCommand(command, sender())

    case CrudStreamOut(m, _) =>
      import CrudStreamOut.{Message => CrudSOMsg}
      m match {

        case CrudSOMsg.Reply(r) if currentCommand == null =>
          crash(s"Unexpected reply, had no current command: $r")

        case CrudSOMsg.Reply(r) if currentCommand.commandId != r.commandId =>
          crash(s"Incorrect command id in reply, expecting ${currentCommand.commandId} but got ${r.commandId}")

        case CrudSOMsg.Reply(r) =>
          reportActionComplete()
          val commandId = currentCommand.commandId
          if (r.crudAction.isEmpty) {
            currentCommand.replyTo ! esReplyToUfReply(r)
            commandHandled()
          } else {
            reportDatabaseOperationStarted()
            r.crudAction map { a =>
              performCrudAction(a)
                .map { _ =>
                  reportDatabaseOperationFinished()
                  // Make sure that the current request is still ours
                  if (currentCommand == null || currentCommand.commandId != commandId) {
                    crash("Internal error - currentRequest changed before all events were persisted")
                  }
                  currentCommand.replyTo ! esReplyToUfReply(r)
                  commandHandled()
                  CrudEntity.SaveStateSuccess
                }
                .pipeTo(self)
            }
          }

        case CrudSOMsg.Failure(f) if f.commandId == 0 =>
          crash(s"Non command specific error from entity: ${f.description}")

        case CrudSOMsg.Failure(f) if currentCommand == null =>
          crash(s"Unexpected failure, had no current command: $f")

        case CrudSOMsg.Failure(f) if currentCommand.commandId != f.commandId =>
          crash(s"Incorrect command id in failure, expecting ${currentCommand.commandId} but got ${f.commandId}")

        case CrudSOMsg.Failure(f) =>
          reportActionComplete()
          currentCommand.replyTo ! createFailure(f.description)
          commandHandled()

        case CrudSOMsg.Empty =>
          // Either the reply/failure wasn't set, or its set to something unknown.
          // todo see if scalapb can give us unknown fields so we can possibly log more intelligently
          crash("Empty or unknown message from entity output stream")
      }

    case CrudEntity.StreamClosed =>
      notifyOutstandingRequests("Unexpected CRUD entity termination")
      context.stop(self)

    case CrudEntity.StreamFailed(error) =>
      notifyOutstandingRequests("Unexpected CRUD entity termination")
      throw error

    case CrudEntity.SaveStateSuccess =>
    // Nothing to do, access the native crud database was successful

    case ReceiveTimeout =>
      context.parent ! ShardRegion.Passivate(stopMessage = CrudEntity.Stop)

    case CrudEntity.Stop =>
      stopped = true
      if (currentCommand == null) {
        context.stop(self)
      }
  }

  private def performCrudAction(crudAction: CrudAction): Future[Unit] =
    crudAction.action match {
      case Update(CrudUpdate(Some(value), _)) =>
        repository.update(Key(persistenceId, entityId), value)

      case Delete(_) =>
        repository.delete(Key(persistenceId, entityId))
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
