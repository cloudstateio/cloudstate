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

package io.cloudstate.javasupport.impl.crud

import java.util.Optional

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.stream.scaladsl.Flow
import io.cloudstate.javasupport.CloudStateRunner.Configuration
import com.google.protobuf.{Descriptors, Any => JavaPbAny}
import com.google.protobuf.any.{Any => ScalaPbAny}
import io.cloudstate.javasupport.crud._
import io.cloudstate.javasupport.impl._
import io.cloudstate.javasupport.impl.crud.CrudImpl.{failure, failureMessage, EntityException, ProtocolException}
import io.cloudstate.javasupport.{Context, ServiceCallFactory, StatefulService}
import io.cloudstate.protocol.crud.CrudAction.Action.{Delete, Update}
import io.cloudstate.protocol.crud._
import io.cloudstate.protocol.crud.CrudStreamIn.Message.{Command => InCommand, Empty => InEmpty, Init => InInit}
import io.cloudstate.protocol.crud.CrudStreamOut.Message.{Failure => OutFailure, Reply => OutReply}
import io.cloudstate.protocol.entity.{Command, Failure}

import scala.compat.java8.OptionConverters._
import scala.util.control.NonFatal

final class CrudStatefulService(val factory: CrudEntityFactory,
                                override val descriptor: Descriptors.ServiceDescriptor,
                                val anySupport: AnySupport,
                                override val persistenceId: String)
    extends StatefulService {

  override def resolvedMethods: Option[Map[String, ResolvedServiceMethod[_, _]]] =
    factory match {
      case resolved: ResolvedEntityFactory => Some(resolved.resolvedMethods)
      case _ => None
    }

  override final val entityType = io.cloudstate.protocol.crud.Crud.name
}

object CrudImpl {
  final case class EntityException(entityId: String, commandId: Long, commandName: String, message: String)
      extends RuntimeException(message)

  object EntityException {
    def apply(message: String): EntityException =
      EntityException(entityId = "", commandId = 0, commandName = "", message)

    def apply(command: Command, message: String): EntityException =
      EntityException(command.entityId, command.id, command.name, message)

    def apply(context: CommandContext[_], message: String): EntityException =
      EntityException(context.entityId, context.commandId, context.commandName, message)
  }

  object ProtocolException {
    def apply(message: String): EntityException =
      EntityException(entityId = "", commandId = 0, commandName = "", "Protocol error: " + message)

    def apply(init: CrudInit, message: String): EntityException =
      EntityException(init.entityId, commandId = 0, commandName = "", "Protocol error: " + message)

    def apply(command: Command, message: String): EntityException =
      EntityException(command.entityId, command.id, command.name, "Protocol error: " + message)
  }

  def failure(cause: Throwable): Failure = cause match {
    case e: EntityException => Failure(e.commandId, e.message)
    case e => Failure(description = "Unexpected failure: " + e.getMessage)
  }

  def failureMessage(cause: Throwable): String = cause match {
    case EntityException(entityId, commandId, commandName, _) =>
      val commandDescription = if (commandId != 0) s" for command [$commandName]" else ""
      val entityDescription = if (entityId.nonEmpty) s"entity [$entityId]" else "entity"
      s"Terminating $entityDescription due to unexpected failure$commandDescription"
    case _ => "Terminating entity due to unexpected failure"
  }
}

final class CrudImpl(_system: ActorSystem,
                     _services: Map[String, CrudStatefulService],
                     rootContext: Context,
                     configuration: Configuration)
    extends io.cloudstate.protocol.crud.Crud {

  private final val system = _system
  private final implicit val ec = system.dispatcher
  private final val services = _services.iterator.toMap
  private final val log = Logging(system.eventStream, this.getClass)

  /**
   * One stream will be established per active entity.
   * Once established, the first message sent will be Init, which contains the entity ID, and,
   * a state if the entity has previously persisted one. The entity is expected to apply the
   * received state to its state. Once the Init message is sent, one to many commands are sent,
   * with new commands being sent as new requests for the entity come in. The entity is expected
   * to reply to each command with exactly one reply message. The entity should reply in order
   * and any state update that the entity requests to be persisted the entity should handle itself.
   * The entity handles state update by replacing its own state with the update,
   * as if they had arrived as state update when the stream was being replayed on load.
   */
  override def handle(
      in: akka.stream.scaladsl.Source[CrudStreamIn, akka.NotUsed]
  ): akka.stream.scaladsl.Source[CrudStreamOut, akka.NotUsed] =
    in.prefixAndTail(1)
      .flatMapConcat {
        // TODO: check!!! the InInit message not always comes first. it is maybe because of preStart in CrudEntity Actor!!!
        case (Seq(CrudStreamIn(InInit(init), _)), source) =>
          source.via(runEntity(init))
        case _ =>
          throw ProtocolException("Expected Init message for CRUD entity")
      }
      .recover {
        case error =>
          log.error(error, failureMessage(error))
          CrudStreamOut(OutFailure(failure(error)))
      }

  private def runEntity(init: CrudInit): Flow[CrudStreamIn, CrudStreamOut, NotUsed] = {
    val service =
      services.getOrElse(init.serviceName, throw new RuntimeException(s"Service not found: ${init.serviceName}"))
    val handler = service.factory.create(new CrudContextImpl(init.entityId))
    val thisEntityId = init.entityId

    val initState = init.state match {
      case Some(CrudInitState(state, _)) => state
      case _ => None
    }

    Flow[CrudStreamIn]
      .map(_.message)
      .scan[(Option[ScalaPbAny], Option[CrudStreamOut.Message])]((initState, None)) {
        case (_, InCommand(command)) if thisEntityId != command.entityId =>
          throw ProtocolException(command, "Receiving entity is not the intended recipient for CRUD entity")

        case (_, InCommand(command)) if command.payload.isEmpty =>
          throw ProtocolException(command, "No command payload for CRUD entity")

        case ((state, _), InCommand(command)) =>
          val cmd = ScalaPbAny.toJavaProto(command.payload.get)
          val context = new CommandContextImpl(
            thisEntityId,
            command.name,
            command.id,
            state,
            service.anySupport,
            log
          )
          val reply = try {
            handler.handleCommand(cmd, context)
          } catch {
            case FailInvoked => Option.empty[JavaPbAny].asJava
            case e: EntityException => throw e
            case NonFatal(error) =>
              throw EntityException(command, s"CRUD entity Unexpected failure: ${error.getMessage}")
          } finally {
            context.deactivate() // Very important!
          }

          val clientAction = context.createClientAction(reply, false)
          if (!context.hasError) {
            val nextState = context.currentState()
            (nextState,
             Some(
               OutReply(
                 CrudReply(
                   command.id,
                   clientAction,
                   context.sideEffects,
                   context.action
                 )
               )
             ))
          } else {
            (state,
             Some(
               OutReply(
                 CrudReply(
                   commandId = command.id,
                   clientAction = clientAction,
                   crudAction = context.action
                 )
               )
             ))
          }

        case (_, InInit(_)) =>
          throw ProtocolException(init, "CRUD Entity already inited")

        case (_, InEmpty) =>
          throw ProtocolException(init, "CRUD entity received empty/unknown message")
      }
      .collect {
        case (_, Some(message)) => CrudStreamOut(message)
      }
  }

  trait AbstractContext extends CrudContext {
    override def serviceCallFactory(): ServiceCallFactory = rootContext.serviceCallFactory()
  }

  private final class CommandContextImpl(override val entityId: String,
                                         override val commandName: String,
                                         override val commandId: Long,
                                         val state: Option[ScalaPbAny],
                                         val anySupport: AnySupport,
                                         val log: LoggingAdapter)
      extends CommandContext[JavaPbAny]
      with AbstractContext
      with AbstractClientActionContext
      with AbstractEffectContext
      with ActivatableContext
      with CrudActionInvocationChecker {

    final var stateDeleted = false
    final var action: Option[CrudAction] = None
    private var _currentState: Option[ScalaPbAny] = state

    override def getState(): Optional[JavaPbAny] = {
      checkActive()
      checkStateDeleted(CrudActionInvocationChecker.CrudActionInvocationContext(entityId, commandId, commandName))

      _currentState.map(ScalaPbAny.toJavaProto(_)).asJava
    }

    override def updateState(state: JavaPbAny): Unit = {
      checkActive()
      if (state == null)
        throw EntityException("CRUD entity cannot update a 'null' state")
      checkInvocation(
        CrudActionInvocationChecker.CrudActionInvocationContext(entityId, commandId, commandName),
        CrudAction(Update(CrudUpdate(None)))
      )

      val encoded = anySupport.encodeScala(state)
      _currentState = Some(encoded)
      action = Some(CrudAction(Update(CrudUpdate(Some(encoded)))))
      stateDeleted = false
    }

    override def deleteState(): Unit = {
      checkActive()
      checkInvocation(
        CrudActionInvocationChecker.CrudActionInvocationContext(entityId, commandId, commandName),
        CrudAction(Delete(CrudDelete()))
      )

      _currentState = None
      action = Some(CrudAction(Delete(CrudDelete())))
      stateDeleted = true
    }

    override protected def logError(message: String): Unit =
      log.error("Fail invoked for command [{}] for CRUD entity [{}]: {}", commandName, entityId, message)

    def currentState(): Option[ScalaPbAny] =
      _currentState

  }

  private final class CrudContextImpl(override final val entityId: String) extends CrudContext with AbstractContext

}
