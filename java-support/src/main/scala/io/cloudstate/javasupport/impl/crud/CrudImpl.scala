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

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Source}
import io.cloudstate.javasupport.CloudStateRunner.Configuration
import com.google.protobuf.{Descriptors, Any => JavaPbAny}
import com.google.protobuf.any.{Any => ScalaPbAny}
import io.cloudstate.javasupport.crud._
import io.cloudstate.javasupport.impl._
import io.cloudstate.javasupport.{Context, ServiceCallFactory, StatefulService}
import io.cloudstate.protocol.crud.CrudAction.Action.{Delete, Update}
import io.cloudstate.protocol.crud._
import io.cloudstate.protocol.crud.CrudStreamIn.Message.{Command => InCommand, Empty => InEmpty, Init => InInit}
import io.cloudstate.protocol.entity.Failure

import scala.compat.java8.OptionConverters._

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

final class CrudImpl(_system: ActorSystem,
                     _services: Map[String, CrudStatefulService],
                     rootContext: Context,
                     configuration: Configuration)
    extends io.cloudstate.protocol.crud.Crud {

  private final val system = _system
  private final implicit val ec = system.dispatcher
  private final val services = _services.iterator.toMap

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
        case (Seq(CrudStreamIn(InInit(init), _)), source) =>
          source.via(runEntity(init))
        case _ =>
          Source.single(
            CrudStreamOut(
              CrudStreamOut.Message.Failure(
                Failure(
                  0,
                  "Cloudstate protocol failure for CRUD entity: expected init message"
                )
              )
            )
          )
      }
      .recover {
        case e =>
          system.log.error(e, "Unexpected error, terminating CRUD Entity")
          CrudStreamOut(
            CrudStreamOut.Message.Failure(
              Failure(
                0,
                s"Cloudstate protocol failure for CRUD entity: ${e.getMessage}"
              )
            )
          )
      }

  private def runEntity(init: CrudInit): Flow[CrudStreamIn, CrudStreamOut, NotUsed] = {
    val service =
      services.getOrElse(init.serviceName, throw new RuntimeException(s"Service not found: ${init.serviceName}"))
    val handler = service.factory.create(new CrudContextImpl(init.entityId))
    val thisEntityId = init.entityId
    val sequenceNumber = 0L //TODO: should be removed every where, CRUD do not need sequence!!!

    val startingSequenceNumber = init.state match {
      case Some(CrudInitState(Some(payload), _)) =>
        val encoded = service.anySupport.encodeScala(payload)
        handler.handleUpdate(ScalaPbAny.toJavaProto(encoded), new StateContextImpl(thisEntityId, sequenceNumber))
        sequenceNumber

      case Some(CrudInitState(None, _)) =>
        handler.handleDelete(new StateContextImpl(thisEntityId, sequenceNumber))
        sequenceNumber

      case _ => 0L // should not happen!
    }

    Flow[CrudStreamIn]
      .map(_.message)
      .scan[(Long, Option[CrudStreamOut.Message])]((startingSequenceNumber, None)) {
        case ((sequence, _), InCommand(command)) if thisEntityId != command.entityId =>
          (sequence,
           Some(
             CrudStreamOut.Message.Failure(
               Failure(
                 command.id,
                 s"""Cloudstate protocol failure for CRUD entity:
                       |Receiving entity - $thisEntityId is not the intended recipient
                       |of command with id - ${command.id} and name - ${command.name}""".stripMargin.replaceAll("\n",
                                                                                                                " ")
               )
             )
           ))

        case ((sequence, _), InCommand(command)) if command.payload.isEmpty =>
          (sequence,
           Some(
             CrudStreamOut.Message.Failure(
               Failure(
                 command.id,
                 s"Cloudstate protocol failure for CRUD entity: Command (id: ${command.id}, name: ${command.name}) should have a payload"
               )
             )
           ))

        case ((sequence, _), InCommand(command)) =>
          val cmd = ScalaPbAny.toJavaProto(command.payload.get)
          val context = new CommandContextImpl(
            thisEntityId,
            sequence,
            command.name,
            command.id,
            service.anySupport,
            handler
          )
          val reply = try {
            handler.handleCommand(cmd, context)
          } catch {
            case FailInvoked => Option.empty[JavaPbAny].asJava
          } finally {
            context.deactivate() // Very important!
          }

          val clientAction = context.createClientAction(reply, false)
          if (!context.hasError) {
            context.applyCrudAction()
            val endSequenceNumber = context.nextSequenceNumber

            (endSequenceNumber,
             Some(
               CrudStreamOut.Message.Reply(
                 CrudReply(
                   command.id,
                   clientAction,
                   context.sideEffects,
                   context.crudAction()
                 )
               )
             ))
          } else {
            (sequence,
             Some(
               CrudStreamOut.Message.Reply(
                 CrudReply(
                   commandId = command.id,
                   clientAction = clientAction,
                   crudAction = context.crudAction()
                 )
               )
             ))
          }

        case (_, InInit(_)) =>
          throw new IllegalStateException("CRUD Entity already inited")

        case (_, InEmpty) =>
          throw new IllegalStateException("CRUD Entity received empty/unknown message")
      }
      .collect {
        case (_, Some(message)) => CrudStreamOut(message)
      }
  }

  trait AbstractContext extends CrudContext {
    override def serviceCallFactory(): ServiceCallFactory = rootContext.serviceCallFactory()
  }

  private final class CommandContextImpl(override val entityId: String,
                                         override val sequenceNumber: Long,
                                         override val commandName: String,
                                         override val commandId: Long,
                                         val anySupport: AnySupport,
                                         val handler: CrudEntityHandler)
      extends CommandContext
      with AbstractContext
      with AbstractClientActionContext
      with AbstractEffectContext
      with ActivatableContext {

    private var _nextSequenceNumber: Long = sequenceNumber
    private var mayBeAction: Option[CrudAction] = None

    override def updateEntity(state: AnyRef): Unit = {
      // TODO null check for state
      checkActive()
      val encoded = anySupport.encodeScala(state)
      mayBeAction = Some(CrudAction(Update(CrudUpdate(Some(encoded)))))
    }

    override def deleteEntity(): Unit = {
      checkActive()
      mayBeAction = Some(CrudAction(Delete(CrudDelete())))
    }

    def nextSequenceNumber: Long = _nextSequenceNumber

    def crudAction(): Option[CrudAction] = mayBeAction

    def applyCrudAction(): Unit =
      mayBeAction match {
        case Some(CrudAction(action, _)) =>
          action match {
            case Update(CrudUpdate(Some(value), _)) =>
              _nextSequenceNumber += 1
              handler.handleUpdate(ScalaPbAny.toJavaProto(value), new StateContextImpl(entityId, _nextSequenceNumber))

            case Delete(CrudDelete(_)) =>
              _nextSequenceNumber += 1
              handler.handleDelete(new StateContextImpl(entityId, _nextSequenceNumber))
          }
        case None =>
        // ignored, nothing to do it is a get request!
      }
  }

  private final class CrudContextImpl(override final val entityId: String) extends CrudContext with AbstractContext

  private final class StateContextImpl(override final val entityId: String, override val sequenceNumber: Long)
      extends StateContext
      with AbstractContext
}
