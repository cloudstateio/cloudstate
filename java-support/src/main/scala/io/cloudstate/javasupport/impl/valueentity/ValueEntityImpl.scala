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

package io.cloudstate.javasupport.impl.valueentity

import java.util.Optional

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.stream.scaladsl.Flow
import io.cloudstate.javasupport.CloudStateRunner.Configuration
import com.google.protobuf.{Descriptors, Any => JavaPbAny}
import com.google.protobuf.any.{Any => ScalaPbAny}
import io.cloudstate.javasupport.valueentity._
import io.cloudstate.javasupport.impl._
import io.cloudstate.javasupport.{Context, Metadata, Service, ServiceCallFactory}
import io.cloudstate.protocol.value_entity.ValueEntityAction.Action.{Delete, Update}
import io.cloudstate.protocol.value_entity.ValueEntityStreamIn.Message.{
  Command => InCommand,
  Empty => InEmpty,
  Init => InInit
}
import io.cloudstate.protocol.value_entity.ValueEntityStreamOut.Message.{Failure => OutFailure, Reply => OutReply}
import io.cloudstate.protocol.value_entity._
import io.cloudstate.protocol.entity.{Command, Failure}

import scala.compat.java8.OptionConverters._
import scala.util.control.NonFatal

final class ValueEntityStatefulService(val factory: ValueEntityFactory,
                                       override val descriptor: Descriptors.ServiceDescriptor,
                                       val anySupport: AnySupport,
                                       override val persistenceId: String)
    extends Service {

  override def resolvedMethods: Option[Map[String, ResolvedServiceMethod[_, _]]] =
    factory match {
      case resolved: ResolvedEntityFactory => Some(resolved.resolvedMethods)
      case _ => None
    }

  override final val entityType = io.cloudstate.protocol.value_entity.ValueEntityProtocol.name
}

object ValueEntityImpl {
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

    def apply(init: ValueEntityInit, message: String): EntityException =
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

final class ValueEntityImpl(_system: ActorSystem,
                            _services: Map[String, ValueEntityStatefulService],
                            rootContext: Context,
                            configuration: Configuration)
    extends ValueEntityProtocol {

  import ValueEntityImpl._

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
      in: akka.stream.scaladsl.Source[ValueEntityStreamIn, akka.NotUsed]
  ): akka.stream.scaladsl.Source[ValueEntityStreamOut, akka.NotUsed] =
    in.prefixAndTail(1)
      .flatMapConcat {
        case (Seq(ValueEntityStreamIn(InInit(init), _)), source) =>
          source.via(runEntity(init))
        case _ =>
          throw ProtocolException("Expected init message for Value entity")
      }
      .recover {
        case error =>
          log.error(error, failureMessage(error))
          ValueEntityStreamOut(OutFailure(failure(error)))
      }

  private def runEntity(init: ValueEntityInit): Flow[ValueEntityStreamIn, ValueEntityStreamOut, NotUsed] = {
    val service =
      services.getOrElse(init.serviceName, throw ProtocolException(init, s"Service not found: ${init.serviceName}"))
    val handler = service.factory.create(new ValueEntityContextImpl(init.entityId))
    val thisEntityId = init.entityId

    val initState = init.state match {
      case Some(ValueEntityInitState(state, _)) => state
      case _ => None // should not happen!!!
    }

    Flow[ValueEntityStreamIn]
      .map(_.message)
      .scan[(Option[ScalaPbAny], Option[ValueEntityStreamOut.Message])]((initState, None)) {
        case (_, InCommand(command)) if thisEntityId != command.entityId =>
          throw ProtocolException(command, "Receiving Value entity is not the intended recipient of command")

        case (_, InCommand(command)) if command.payload.isEmpty =>
          throw ProtocolException(command, "No command payload for Value entity")

        case ((state, _), InCommand(command)) =>
          val cmd = ScalaPbAny.toJavaProto(command.payload.get)
          val metadata = new MetadataImpl(command.metadata.map(_.entries.toVector).getOrElse(Nil))
          val context = new CommandContextImpl(
            thisEntityId,
            command.name,
            command.id,
            metadata,
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
              throw EntityException(command, s"Value entity unexpected failure: ${error.getMessage}")
          } finally {
            context.deactivate() // Very important!
          }

          val clientAction = context.createClientAction(reply, false, false)
          if (!context.hasError) {
            val nextState = context.currentState()
            (nextState,
             Some(
               OutReply(
                 ValueEntityReply(
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
                 ValueEntityReply(
                   commandId = command.id,
                   clientAction = clientAction
                 )
               )
             ))
          }

        case (_, InInit(_)) =>
          throw ProtocolException(init, "Value entity already inited")

        case (_, InEmpty) =>
          throw ProtocolException(init, "Value entity received empty/unknown message")
      }
      .collect {
        case (_, Some(message)) => ValueEntityStreamOut(message)
      }
  }

  trait AbstractContext extends ValueEntityContext {
    override def serviceCallFactory(): ServiceCallFactory = rootContext.serviceCallFactory()
  }

  private final class CommandContextImpl(override val entityId: String,
                                         override val commandName: String,
                                         override val commandId: Long,
                                         override val metadata: Metadata,
                                         val state: Option[ScalaPbAny],
                                         val anySupport: AnySupport,
                                         val log: LoggingAdapter)
      extends CommandContext[JavaPbAny]
      with AbstractContext
      with AbstractClientActionContext
      with AbstractEffectContext
      with ActivatableContext {

    final var action: Option[ValueEntityAction] = None
    private var _state: Option[ScalaPbAny] = state

    override def getState(): Optional[JavaPbAny] = {
      checkActive()
      _state.map(ScalaPbAny.toJavaProto(_)).asJava
    }

    override def updateState(state: JavaPbAny): Unit = {
      checkActive()
      if (state == null)
        throw EntityException("Value entity cannot update a 'null' state")

      val encoded = anySupport.encodeScala(state)
      _state = Some(encoded)
      action = Some(ValueEntityAction(Update(ValueEntityUpdate(_state))))
    }

    override def deleteState(): Unit = {
      checkActive()

      _state = None
      action = Some(ValueEntityAction(Delete(ValueEntityDelete())))
    }

    override protected def logError(message: String): Unit =
      log.error("Fail invoked for command [{}] for Value entity [{}]: {}", commandName, entityId, message)

    def currentState(): Option[ScalaPbAny] =
      _state

  }

  private final class ValueEntityContextImpl(override final val entityId: String)
      extends ValueEntityContext
      with AbstractContext

}
