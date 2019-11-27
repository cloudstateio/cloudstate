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

package io.cloudstate.proxy.function

import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong
import java.nio.charset.StandardCharsets.UTF_8

import akka.actor._
import akka.pattern.pipe
import akka.cluster.sharding.ShardRegion
import akka.stream.scaladsl._
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.Timeout
import io.cloudstate.protocol.entity._
import io.cloudstate.protocol.function._
import io.cloudstate.proxy.ConcurrencyEnforcer.{Action, ActionCompleted}
import io.cloudstate.proxy.StatsCollector
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}

import scala.collection.immutable.Queue

object StatelessFunctionEntitySupervisor {

  def props(client: StatelessFunctionClient,
            configuration: StatelessFunctionEntity.Configuration,
            concurrencyEnforcer: ActorRef, // FIXME Do we really need this thing?
            statsCollector: ActorRef)(implicit mat: Materializer): Props =
    Props(new StatelessFunctionEntitySupervisor(client, configuration, concurrencyEnforcer, statsCollector))
}

/**
 * TODO Document
 */
final class StatelessFunctionEntitySupervisor(client: StatelessFunctionClient,
                                              configuration: StatelessFunctionEntity.Configuration,
                                              concurrencyEnforcer: ActorRef,
                                              statsCollector: ActorRef)(implicit mat: Materializer)
    extends Actor {

  override final def receive: Receive = PartialFunction.empty

  override final def preStart(): Unit = {
    val entityId = URLDecoder.decode(self.path.name, UTF_8)
    val manager = context.watch(
      context
        .actorOf(
          StatelessFunctionEntity.props(configuration, entityId, client, concurrencyEnforcer, statsCollector),
          "entity"
        )
    )
    context.become({
      case Terminated(`manager`) =>
        context.stop(self)
      case toParent if sender() == manager =>
        context.parent ! toParent
      case msg =>
        manager forward msg
    })
  }

  override final def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy
}

object StatelessFunctionEntity {

  final case object Stop

  final case class Configuration(
      serviceName: String,
      userFunctionName: String,
      passivationTimeout: Timeout,
      sendQueueSize: Int
  )

  private final case class OutstandingCommand(
      actionId: String,
      replyTo: ActorRef,
      startTime: Long
  )

  final def props(configuration: Configuration,
                  entityId: String, // TODO do we need to care about this at all?
                  client: StatelessFunctionClient,
                  concurrencyEnforcer: ActorRef,
                  statsCollector: ActorRef): Props =
    Props(new StatelessFunctionEntity(configuration, entityId, client, concurrencyEnforcer, statsCollector))

  /**
   * Used to ensure the action ids sent to the concurrency enforcer are indeed unique.
   */
  private val actorCounter = new AtomicLong(0)
}

final class StatelessFunctionEntity(configuration: StatelessFunctionEntity.Configuration,
                                    entityId: String,
                                    client: StatelessFunctionClient,
                                    concurrencyEnforcer: ActorRef,
                                    statsCollector: ActorRef)
    extends Actor
    with ActorLogging {
  private val actorId = StatelessFunctionEntity.actorCounter.incrementAndGet()

  private[this] final var stashedCommands = Queue.empty[(EntityCommand, ActorRef)] // PERFORMANCE: look at options for data structures
  private[this] final var currentCommand: StatelessFunctionEntity.OutstandingCommand = null
  private[this] final var stopped = false
  private[this] final var idCounter = 0L

  // Set up passivation timer
  context.setReceiveTimeout(configuration.passivationTimeout.duration)

  override final def postStop(): Unit = {
    currentCommand match {
      case null =>
      case command =>
        log.warning("Stopped but we have a current action id {}", command.actionId)
        command.replyTo ! createFailure("Unexpected entity termination")
        reportActionComplete(command)
        currentCommand = null
    }

    val errorNotification = createFailure("Entity terminated")
    for ((_, replyTo) <- stashedCommands) {
      replyTo ! errorNotification
    }
    stashedCommands = Queue.empty
  }

  private[this] final def reportActionComplete(command: StatelessFunctionEntity.OutstandingCommand) =
    concurrencyEnforcer ! ActionCompleted(command.actionId, System.nanoTime() - command.startTime)

  private[this] final def handleCommand(entityCommand: EntityCommand, sender: ActorRef): Unit =
    currentCommand match {
      case null =>
        idCounter += 1
        val command = FunctionCommand(
          serviceName = configuration.serviceName,
          name = entityCommand.name,
          payload = entityCommand.payload
        )
        val actionFunction: () => Unit =
          if (entityCommand.streamed) { () =>
            { ??? } // FIXME IMPLEMENT STREAMING? Also, do we need to separate streamed in from streamed out?
          } else {
            val replyer = self
            import context.dispatcher // TODO consider using sameThreadExecutionContext here
            () => client.handleUnary(command) pipeTo replyer
          }
        currentCommand = StatelessFunctionEntity.OutstandingCommand(actorId + ":" + entityId + ":" + idCounter,
                                                                    sender,
                                                                    System.nanoTime())
        concurrencyEnforcer ! Action(currentCommand.actionId, actionFunction)
      case _ =>
        if (stashedCommands.length < configuration.sendQueueSize) {
          stashedCommands = stashedCommands.enqueue((entityCommand, sender))
        } else {
          sender ! createFailure("Try again later")
        }
    }

  private final def createFailure(message: String) =
    UserFunctionReply(
      clientAction = Some(ClientAction(ClientAction.Action.Failure(Failure(description = message))))
    )

  private final def createResponse(reply: FunctionReply): UserFunctionReply = {
    import FunctionReply.Response
    import ClientAction.Action
    val clientAction = Some(ClientAction(reply.response match {
      case Response.Reply(r) => Action.Reply(r)
      case Response.Failure(f) => Action.Failure(f)
      case Response.Forward(f) => Action.Forward(f)
      case Response.Empty => Action.Empty
    }))
    UserFunctionReply(
      clientAction = clientAction,
      sideEffects = reply.sideEffects
    )
  }

  override final def receive: PartialFunction[Any, Unit] = {
    case command: EntityCommand =>
      handleCommand(command, sender())

    case reply: FunctionReply =>
      currentCommand match {
        case null =>
          throw new Exception("Received reply when no command was being processed") // Will stop the actor, hence postStop will be called
        case command =>
          reportActionComplete(command)
          command.replyTo ! createResponse(reply)
          currentCommand = null
          if (stashedCommands.nonEmpty) {
            val ((request, sender), newStashedCommands) = stashedCommands.dequeue
            stashedCommands = newStashedCommands
            handleCommand(request, sender)
          } else if (stopped) {
            context.stop(self)
          }
      }

    case Status.Failure(error) => throw error // Will stop the actor, hence postStop will be called

    case ReceiveTimeout =>
      context.parent ! ShardRegion.Passivate(stopMessage = StatelessFunctionEntity.Stop)

    case StatelessFunctionEntity.Stop =>
      stopped = true
      if (currentCommand == null) {
        context.stop(self)
      }
  }
}
