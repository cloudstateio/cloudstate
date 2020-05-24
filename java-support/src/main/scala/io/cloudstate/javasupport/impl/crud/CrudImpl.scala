/*
 * Copyright 2020 Lightbend Inc.
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

import akka.actor.ActorSystem
import com.google.protobuf.Descriptors
import io.cloudstate.javasupport.CloudStateRunner.Configuration
import io.cloudstate.javasupport.crud._
import io.cloudstate.javasupport.impl._
import io.cloudstate.javasupport.{Context, ServiceCallFactory, StatefulService}
import io.cloudstate.protocol.crud._

import scala.concurrent.Future

final class CrudStatefulService(val factory: CrudEntityFactory,
                                override val descriptor: Descriptors.ServiceDescriptor,
                                val anySupport: AnySupport,
                                override val persistenceId: String,
                                val snapshotEvery: Int)
    extends StatefulService {

  override def resolvedMethods: Option[Map[String, ResolvedServiceMethod[_, _]]] =
    factory match {
      case resolved: ResolvedEntityFactory => Some(resolved.resolvedMethods)
      case _ => None
    }

  override final val entityType = Crud.name

  final def withSnapshotEvery(snapshotEvery: Int): CrudStatefulService =
    if (snapshotEvery != this.snapshotEvery)
      new CrudStatefulService(this.factory, this.descriptor, this.anySupport, this.persistenceId, snapshotEvery)
    else
      this
}

final class CrudImpl(_system: ActorSystem,
                     _services: Map[String, CrudStatefulService],
                     rootContext: Context,
                     configuration: Configuration)
    extends Crud {

  private final val system = _system
  private final implicit val ec = system.dispatcher
  private final val services = _services.iterator
    .map({
      case (name, crudss) =>
        // FIXME overlay configuration provided by _system
        (name, if (crudss.snapshotEvery == 0) crudss.withSnapshotEvery(configuration.snapshotEvery) else crudss)
    })
    .toMap

  private final val runner = new EntityHandlerRunner()

  override def create(command: CrudCommand): Future[CrudReplyOut] =
    Future.unit
      .map { _ =>
        runner.handleState(command)
        val (reply, context) = runner.handleCommand(command)
        val clientAction = context.createClientAction(reply, false)
        runner.endSequenceNumber(context.hasError)

        if (!context.hasError) {
          CrudReplyOut(
            CrudReplyOut.Message.Reply(
              CrudReply(
                command.id,
                clientAction,
                context.sideEffects,
                runner.event(command.id),
                runner.snapshot()
              )
            )
          )
        } else {
          CrudReplyOut(
            CrudReplyOut.Message.Reply(
              CrudReply(
                commandId = command.id,
                clientAction = clientAction,
                state = runner.event(command.id)
              )
            )
          )
        }
      }

  override def fetch(command: CrudCommand): Future[CrudReplyOut] =
    Future.unit
      .map { _ =>
        runner.handleState(command)
        val (reply, context) = runner.handleCommand(command)
        val clientAction = context.createClientAction(reply, false)
        if (!context.hasError) {
          CrudReplyOut(
            CrudReplyOut.Message.Reply(
              CrudReply(
                command.id,
                clientAction,
                context.sideEffects,
                runner.event(command.id),
                runner.snapshot()
              )
            )
          )
        } else {
          CrudReplyOut(
            CrudReplyOut.Message.Reply(
              CrudReply(
                commandId = command.id,
                clientAction = clientAction,
                state = runner.event(command.id)
              )
            )
          )
        }
      }

  override def save(command: CrudCommand): Future[CrudReplyOut] =
    Future.unit
      .map { _ =>
        runner.handleState(command)
        val (reply, context) = runner.handleCommand(command)
        val clientAction = context.createClientAction(reply, false)
        runner.endSequenceNumber(context.hasError)

        if (!context.hasError) {
          CrudReplyOut(
            CrudReplyOut.Message.Reply(
              CrudReply(
                command.id,
                clientAction,
                context.sideEffects,
                runner.event(command.id),
                runner.snapshot()
              )
            )
          )
        } else {
          CrudReplyOut(
            CrudReplyOut.Message.Reply(
              CrudReply(
                commandId = command.id,
                clientAction = clientAction,
                state = runner.event(command.id)
              )
            )
          )
        }
      }

  override def delete(command: CrudCommand): Future[CrudReplyOut] = ???

  override def fetchAll(command: CrudCommand): Future[CrudReplyOut] = ???

  /*
   * Represents a wrapper for the crud service and crud entity handler.
   * It creates the service and entity handler once depending on the first command which starts the flow.
   * It also deals with snapshotting of events and the deactivation of the command context.
   */
  private final class EntityHandlerRunner() {
    import com.google.protobuf.{Any => JavaPbAny}
    import com.google.protobuf.any.{Any => ScalaPbAny}

    private final var handlerInit = false
    private final var service: CrudStatefulService = _
    private final var handler: CrudEntityHandler = _

    private final var sequenceNumber: Long = 0
    private final var performSnapshot: Boolean = false
    private final var events = Map.empty[Long, ScalaPbAny]

    def handleCommand(command: CrudCommand): (Optional[JavaPbAny], CommandContextImpl) = {
      maybeInitHandler(command)

      val context = new CommandContextImpl(command.entityId, sequenceNumber, command.name, command.id, this)
      try {
        command.payload
          .map(p => (handler.handleCommand(ScalaPbAny.toJavaProto(p), context), context))
          .getOrElse((Optional.empty[JavaPbAny](), context)) //FIXME payload empty should throw an exception or not?
      } catch {
        case FailInvoked =>
          (Optional.empty[JavaPbAny](), context)
      } finally {
        context.deactivate()
      }
    }

    def handleState(command: CrudCommand): Unit = {
      maybeInitHandler(command)

      val context = new SnapshotContextImpl(command.entityId, sequenceNumber)
      // Not sure about the best way to push the state to the user function
      // There are two options here. The first is using an annotation which is called on runner.handleState.
      // runner.handleState will use a new special context called StateContext (will be implemented).
      // The other option is to pass the state in the CommandContext and use emit or something else to publish the new state
      command.state.map(s => handler.handleState(ScalaPbAny.toJavaProto(s.payload.get), context))
    }

    def emit(event: AnyRef, context: CommandContext): Unit = {
      val encoded = service.anySupport.encodeScala(event)
      val nextSequenceNumber = context.sequenceNumber() + events.size + 1
      handler.handleState(ScalaPbAny.toJavaProto(encoded),
                          new SnapshotContextImpl(context.entityId, nextSequenceNumber))

      events += (context.commandId() -> encoded)
      performSnapshot = (service.snapshotEvery > 0) && (performSnapshot || (nextSequenceNumber % service.snapshotEvery == 0))
    }

    def endSequenceNumber(hasError: Boolean): Unit =
      if (!hasError) {
        sequenceNumber = sequenceNumber + events.size
      }

    def snapshot(): Option[ScalaPbAny] =
      if (performSnapshot) {
        val (_, lastEvent) = events.last
        Some(lastEvent)
      } else None

    def event(commandId: Long): Option[ScalaPbAny] = {
      val e = events.get(commandId)
      events -= commandId // remove the event for the command id
      e
    }

    private def maybeInitHandler(command: CrudCommand): Unit =
      if (!handlerInit) {
        service = services.getOrElse(command.serviceName,
                                     throw new RuntimeException(s"Service not found: ${command.serviceName}"))
        handler = service.factory.create(new CrudContextImpl(command.entityId))
        sequenceNumber = command.state.map(_.snapshotSequence).getOrElse(0L)
        handlerInit = true
      }
  }

  trait AbstractContext extends CrudContext {
    override def serviceCallFactory(): ServiceCallFactory = rootContext.serviceCallFactory()
  }

  private final class CommandContextImpl(override val entityId: String,
                                         override val sequenceNumber: Long,
                                         override val commandName: String,
                                         override val commandId: Long,
                                         private val runner: EntityHandlerRunner)
      extends CommandContext
      with AbstractContext
      with AbstractClientActionContext
      with AbstractEffectContext
      with ActivatableContext {

    override def emit(event: AnyRef): Unit =
      runner.emit(event, this)
  }

  private final class CrudContextImpl(override final val entityId: String) extends CrudContext with AbstractContext

  private final class SnapshotContextImpl(override final val entityId: String, override final val sequenceNumber: Long)
      extends SnapshotContext
      with AbstractContext
}
