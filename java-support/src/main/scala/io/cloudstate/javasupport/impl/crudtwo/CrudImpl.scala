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
package io.cloudstate.javasupport.impl.crudtwo

import akka.actor.ActorSystem
import com.google.protobuf.Descriptors
import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.{Any => JavaPbAny}
import io.cloudstate.javasupport.CloudStateRunner.Configuration
import io.cloudstate.javasupport.crudtwo._
import io.cloudstate.javasupport.impl._
import io.cloudstate.javasupport.{Context, ServiceCallFactory, StatefulService}
import io.cloudstate.protocol.crud_two._

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

  override final val entityType = CrudTwo.name

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
    extends CrudTwo {
  // how to deal with snapshot and events by handleState. Some kind of mapping?
  // how to deal with emitted events? handleState is called now, is that right?
  // how to push the snapshot state to the user function? handleState?
  // should snapshot be exposed to the user function?
  // How to do snapshot here?

  private final val system = _system
  private final implicit val ec = system.dispatcher
  private final val services = _services.iterator.toMap

  // One option for accessing the service name and the entityId could be to pass it in the CrudCommand.
  private val serviceName = "serviceName" // FIXME where to get the service name from?
  private val entityId = "entityId" // FIXME entityId can be extract from command, where to get entityId from when creating the CrudImpl?
  private final val service =
    services.getOrElse(serviceName, throw new RuntimeException(s"Service not found: $serviceName"))
  private var handler
      : CrudEntityHandler = service.factory.create(new CrudContextImpl(entityId)) // FIXME how to create it?

  override def create(command: CrudCommand): Future[CrudReplies] =
    Future.unit
      .map { _ =>
        val cmd = ScalaPbAny.toJavaProto(command.payload.get) //FIXME payload empty?
        val state = ScalaPbAny.toJavaProto(command.state.get.payload.get) // FIXME state empty? FIXME payload empty?
        val context =
          new CommandContextImpl(command.entityId, 0, command.name, command.id, state, handler, service.anySupport)
        val reply = handler.handleCommand(cmd, context)
        val clientAction = context.createClientAction(reply, false)
        CrudReplies(
          CrudReplies.Message.Reply(
            CrudReply(
              command.id,
              clientAction,
              context.sideEffects,
              Some(context.events(0)) // FIXME deal with the events?
            )
          )
        )
      }

  override def fetch(command: CrudCommand): Future[CrudFetchReplies] =
    Future.unit
      .map { _ =>
        val cmd = ScalaPbAny.toJavaProto(command.payload.get) //FIXME payload empty?
        val state = ScalaPbAny.toJavaProto(command.state.get.payload.get) // FIXME state empty? FIXME payload empty?
        val context =
          new CommandContextImpl(command.entityId, 0, command.name, command.id, state, handler, service.anySupport)
        val reply = handler.handleCommand(cmd, context)
        val clientAction = context.createClientAction(reply, false)
        CrudFetchReplies(
          CrudFetchReplies.Message.Reply(
            CrudFetchReply(
              command.id,
              clientAction,
              context.sideEffects
            )
          )
        )
      }

  override def update(command: CrudCommand): Future[CrudReplies] = ???

  override def delete(command: CrudCommand): Future[CrudReplies] = ???

  trait AbstractContext extends CrudContext {
    override def serviceCallFactory(): ServiceCallFactory = rootContext.serviceCallFactory()
  }

  class CommandContextImpl(override val entityId: String,
                           override val sequenceNumber: Long,
                           override val commandName: String,
                           override val commandId: Long,
                           override val state: JavaPbAny, // not sure it is needed
                           val handler: CrudEntityHandler,
                           val anySupport: AnySupport)
      extends CommandContext
      with AbstractContext
      with AbstractClientActionContext
      with AbstractEffectContext
      with ActivatableContext {

    final var events: Vector[ScalaPbAny] = Vector.empty

    override def emit(event: Any): Unit = {
      val encoded = anySupport.encodeScala(event)
      // Snapshotting should be done!!
      // We want to pass the new persistent state to the User Function and is it the right option (handler.handleState ...)
      // The persisted state is already passed as part of the CommandContext of each Command
      // handler.handleState(ScalaPbAny.toJavaProto(encoded), null)
      events :+= encoded
    }
  }

  class CrudContextImpl(override final val entityId: String) extends CrudContext with AbstractContext
}
