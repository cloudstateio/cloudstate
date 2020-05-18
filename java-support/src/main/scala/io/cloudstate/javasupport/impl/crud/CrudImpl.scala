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
import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.{Descriptors, Any => JavaPbAny}
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
  private final val services = _services.iterator.toMap

  private final var serviceInit = false
  private final var handlerInit = false
  private final var service: CrudStatefulService = _
  private final var handler: CrudEntityHandler = _

  override def create(command: CrudCommand): Future[CrudReplyOut] = {
    maybeInitService(command.serviceName)
    maybeInitHandler(command.entityId)

    Future.unit
      .map { _ =>
        command.state.map { state =>
          // Not sure about the best way to push the state to the user function
          // There are two options here. The first is using an annotation which is called on handler.handleState.
          // handler.handleState will use a new special context called StateContext (will be implemented).
          // The other option is to pass the state in the CommandContext and use emit or something else to publish the new state
          handler.handleState(ScalaPbAny.toJavaProto(state.payload.get), new SnapshotContextImpl(command.entityId, 0))
        }
        val cmd = ScalaPbAny.toJavaProto(command.payload.get) //FIXME payload empty?
        val context =
          new CommandContextImpl(command.entityId, 0, command.name, command.id, handler, service.anySupport)
        val reply = handleCommand(context, cmd)
        val clientAction = context.createClientAction(reply, false)
        if (!context.hasError) {
          CrudReplyOut(
            CrudReplyOut.Message.Reply(
              CrudReply(
                command.id,
                clientAction,
                context.sideEffects,
                Some(context.events(0)) // FIXME deal with the events?
              )
            )
          )
        } else {
          CrudReplyOut(
            CrudReplyOut.Message.Reply(
              CrudReply(
                commandId = command.id,
                clientAction = clientAction,
                state = Some(context.events(0)) // FIXME deal with the events?
              )
            )
          )
        }
      }
  }

  override def fetch(command: CrudCommand): Future[CrudReplyOut] = {
    maybeInitService(command.serviceName)
    maybeInitHandler(command.entityId)

    Future.unit
      .map { _ =>
        command.state.map { state =>
          handler.handleState(ScalaPbAny.toJavaProto(state.payload.get), new SnapshotContextImpl(command.entityId, 0))
        }

        val cmd = ScalaPbAny.toJavaProto(command.payload.get) //FIXME payload empty?
        val context =
          new CommandContextImpl(command.entityId, 0, command.name, command.id, handler, service.anySupport)
        val reply = handleCommand(context, cmd)
        val clientAction = context.createClientAction(reply, false)
        if (!context.hasError) {
          CrudReplyOut(
            CrudReplyOut.Message.Reply(
              CrudReply(
                command.id,
                clientAction,
                context.sideEffects
              )
            )
          )
        } else {
          CrudReplyOut(
            CrudReplyOut.Message.Reply(
              CrudReply(
                commandId = command.id,
                clientAction = clientAction
              )
            )
          )
        }
      }
  }

  override def save(command: CrudCommand): Future[CrudReplyOut] = ???

  override def delete(command: CrudCommand): Future[CrudReplyOut] = ???

  override def fetchAll(command: CrudCommand): Future[CrudReplyOut] = ???

  private def maybeInitService(serviceName: String): Unit =
    if (!serviceInit) {
      service = services.getOrElse(serviceName, throw new RuntimeException(s"Service not found: $serviceName"))
      serviceInit = true
    }

  private def maybeInitHandler(entityId: String): Unit =
    if (!handlerInit) {
      handlerInit = true
      handler = service.factory.create(new CrudContextImpl(entityId))
    }

  private def handleCommand(context: CommandContextImpl, command: JavaPbAny): Optional[JavaPbAny] =
    try {
      handler.handleCommand(command, context)
    } catch {
      case FailInvoked =>
        Optional.empty[JavaPbAny]()
    } finally {
      context.deactivate()
    }

  trait AbstractContext extends CrudContext {
    override def serviceCallFactory(): ServiceCallFactory = rootContext.serviceCallFactory()
  }

  class CommandContextImpl(override val entityId: String,
                           override val sequenceNumber: Long,
                           override val commandName: String,
                           override val commandId: Long,
                           val handler: CrudEntityHandler,
                           val anySupport: AnySupport)
      extends CommandContext
      with AbstractContext
      with AbstractClientActionContext
      with AbstractEffectContext
      with ActivatableContext {

    final var events: Vector[ScalaPbAny] = Vector.empty

    override def emit(event: AnyRef): Unit = {
      val encoded = anySupport.encodeScala(event)
      // Snapshotting should be done!!
      handler.handleState(ScalaPbAny.toJavaProto(encoded), new SnapshotContextImpl(entityId, 0))
      events :+= encoded
    }
  }

  class CrudContextImpl(override final val entityId: String) extends CrudContext with AbstractContext

  class SnapshotContextImpl(override final val entityId: String, override final val sequenceNumber: Long)
      extends SnapshotContext
      with AbstractContext

  //class StateContextImpl(override final val entityId: String) extends CrudContext with AbstractContext with StateContext
}
