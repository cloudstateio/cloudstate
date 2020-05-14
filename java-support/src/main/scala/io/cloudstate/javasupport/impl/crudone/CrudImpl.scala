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
package io.cloudstate.javasupport.impl.crudone

import java.util.Optional

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Source}
import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.{Descriptors, Any => JavaPbAny}
import io.cloudstate.javasupport.CloudStateRunner.Configuration
import io.cloudstate.javasupport.crud._
import io.cloudstate.javasupport.impl._
import io.cloudstate.javasupport.{Context, ServiceCallFactory, StatefulService}
import io.cloudstate.protocol.crud_one.CrudStreamIn.Message.{
  Create => InCreate,
  Empty => InEmpty,
  Event => InEvent,
  Fetch => InFetch,
  Init => InInit
}
import io.cloudstate.protocol.crud_one.{CrudInit, CrudReply, CrudStreamIn, CrudStreamOut}
import io.cloudstate.protocol.crud_one.CrudStreamOut.Message.{Reply => OutReply}
import io.cloudstate.protocol.crud_one.CrudOne

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

  override final val entityType = CrudOne.name

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
    extends CrudOne {
  // how to push the snapshot state to the user function? handleState?
  // should snapshot be exposed to the user function?
  // How to do snapshot here?
  // how to deal with snapshot and events by handleState. Some kind of mapping?
  // how to deal with emitted events? handleState is called now, is that right?

  private final val system = _system
  private final val services = _services.iterator
    .map({
      case (name, crudss) =>
        // FIXME overlay configuration provided by _system
        (name, if (crudss.snapshotEvery == 0) crudss.withSnapshotEvery(configuration.snapshotEvery) else crudss)
    })
    .toMap

  override def handle(in: Source[CrudStreamIn, NotUsed]): Source[CrudStreamOut, NotUsed] =
    in.prefixAndTail(1)
      .flatMapConcat {
        case (Seq(CrudStreamIn(InInit(init), _)), source) =>
          source.via(runEntityCreate(init))
        case _ =>
          // todo better error
          throw new RuntimeException("Expected Init message")
      }
      .recover {
        case e =>
          // FIXME translate to failure message
          throw e
      }

  private def runEntityCreate(init: CrudInit): Flow[CrudStreamIn, CrudStreamOut, NotUsed] = {
    val service =
      services.getOrElse(init.serviceName, throw new RuntimeException(s"Service not found: ${init.serviceName}"))
    val handler: CrudEntityHandler = service.factory.create(new CrudContextImpl(init.entityId))
    val entityId = init.entityId

    val startingSequenceNumber = (for {
      snapshot <- init.snapshot
      any <- snapshot.snapshot
    } yield {
      val snapshotSequence = snapshot.snapshotSequence
      val context = new CrudEventContextImpl(entityId, snapshotSequence)
      handler.handleState(ScalaPbAny.toJavaProto(any), context)
      snapshotSequence
    }).getOrElse(0L)

    Flow[CrudStreamIn]
      .map(_.message)
      .scan[(Long, Option[CrudStreamOut.Message])]((startingSequenceNumber, None)) {
        case (_, InEvent(event)) =>
          val context = new CrudEventContextImpl(entityId, event.sequence)
          val ev = ScalaPbAny.toJavaProto(event.payload.get) // FIXME empty?
          handler.handleState(ev, context)
          (event.sequence, None)

        case ((sequence, _), InCreate(command)) =>
          if (entityId != command.entityId)
            throw new IllegalStateException("Receiving CRUD entity is not the intended recipient of command")
          val cmd = ScalaPbAny.toJavaProto(command.payload.get)
          val context = new CommandContextImpl(entityId,
                                               sequence,
                                               command.name,
                                               command.id,
                                               service.anySupport,
                                               handler,
                                               service.snapshotEvery)

          val reply = try {
            handler.handleCommand(cmd, context) // FIXME is this allowed to throw
          } catch {
            case FailInvoked => Optional.empty[JavaPbAny]()
            // Ignore, error already captured
          } finally {
            context.deactivate() // Very important!
          }

          val clientAction = context.createClientAction(reply, false)
          if (!context.hasError) {
            val endSequenceNumber = sequence + context.events.size

            val snapshot =
              if (context.performSnapshot) {
                val s = handler.snapshot(new SnapshotContext with AbstractContext {
                  override def entityId: String = entityId
                  override def sequenceNumber: Long = endSequenceNumber
                })
                if (s.isPresent) Option(ScalaPbAny.fromJavaProto(s.get)) else None
              } else None

            (endSequenceNumber,
             Some(
               OutReply(
                 CrudReply(
                   command.id,
                   clientAction,
                   context.sideEffects,
                   context.events,
                   snapshot
                 )
               )
             ))
          } else {
            (sequence,
             Some(
               OutReply(
                 CrudReply(
                   commandId = command.id,
                   clientAction = clientAction
                 )
               )
             ))
          }

        case (_, InInit(i)) =>
          throw new IllegalStateException("Entity already inited")

        case (_, InEmpty) =>
          throw new IllegalStateException("Received empty/unknown message")
      }
      .collect {
        case (_, Some(message)) => CrudStreamOut(message)
      }
  }
  private def runEntityOld(init: CrudInit): Flow[CrudStreamIn, CrudStreamOut, NotUsed] = {
    val service =
      services.getOrElse(init.serviceName, throw new RuntimeException(s"Service not found: ${init.serviceName}"))
    val handler: CrudEntityHandler = service.factory.create(new CrudContextImpl(init.entityId))
    val entityId = init.entityId

    val startingSequenceNumber = (for {
      snapshot <- init.snapshot
      any <- snapshot.snapshot
    } yield {
      val snapshotSequence = snapshot.snapshotSequence
      val context = new CrudEventContextImpl(entityId, snapshotSequence)
      handler.handleState(ScalaPbAny.toJavaProto(any), context)
      snapshotSequence
    }).getOrElse(0L)

    Flow[CrudStreamIn]
      .map(_.message)
      .scan[(Long, Option[CrudStreamOut.Message])]((startingSequenceNumber, None)) {
        case (_, InEvent(event)) =>
          val context = new CrudEventContextImpl(entityId, event.sequence)
          val ev = ScalaPbAny.toJavaProto(event.payload.get) // FIXME empty?
          handler.handleState(ev, context)
          (event.sequence, None)

        case ((sequence, _), InCreate(command)) =>
          if (entityId != command.entityId)
            throw new IllegalStateException("Receiving CRUD entity is not the intended recipient of command")
          val cmd = ScalaPbAny.toJavaProto(command.payload.get)
          val context = new CommandContextImpl(entityId,
                                               sequence,
                                               command.name,
                                               command.id,
                                               service.anySupport,
                                               handler,
                                               service.snapshotEvery)

          val reply = try {
            handler.handleCommand(cmd, context) // FIXME is this allowed to throw
          } catch {
            case FailInvoked => Optional.empty[JavaPbAny]()
            // Ignore, error already captured
          } finally {
            context.deactivate() // Very important!
          }

          val clientAction = context.createClientAction(reply, false)
          if (!context.hasError) {
            val endSequenceNumber = sequence + context.events.size

            val snapshot =
              if (context.performSnapshot) {
                val s = handler.snapshot(new SnapshotContext with AbstractContext {
                  override def entityId: String = entityId
                  override def sequenceNumber: Long = endSequenceNumber
                })
                if (s.isPresent) Option(ScalaPbAny.fromJavaProto(s.get)) else None
              } else None

            (endSequenceNumber,
             Some(
               OutReply(
                 CrudReply(
                   command.id,
                   clientAction,
                   context.sideEffects,
                   context.events,
                   snapshot
                 )
               )
             ))
          } else {
            (sequence,
             Some(
               OutReply(
                 CrudReply(
                   commandId = command.id,
                   clientAction = clientAction
                 )
               )
             ))
          }

        case ((sequence, _), InFetch(command)) =>
          if (entityId != command.entityId)
            throw new IllegalStateException("Receiving CRUD entity is not the intended recipient of command")
          val cmd = ScalaPbAny.toJavaProto(command.payload.get)
          val context = new CommandContextImpl(entityId,
                                               sequence,
                                               command.name,
                                               command.id,
                                               service.anySupport,
                                               handler,
                                               service.snapshotEvery)

          val reply = try {
            handler.handleCommand(cmd, context) // FIXME is this allowed to throw
          } catch {
            case FailInvoked => Optional.empty[JavaPbAny]()
            // Ignore, error already captured
          } finally {
            context.deactivate() // Very important!
          }

          val clientAction = context.createClientAction(reply, false)
          if (!context.hasError) {
            val endSequenceNumber = sequence + context.events.size

            val snapshot =
              if (context.performSnapshot) {
                val s = handler.snapshot(new SnapshotContext with AbstractContext {
                  override def entityId: String = entityId
                  override def sequenceNumber: Long = endSequenceNumber
                })
                if (s.isPresent) Option(ScalaPbAny.fromJavaProto(s.get)) else None
              } else None

            (endSequenceNumber,
             Some(
               OutReply(
                 CrudReply(
                   command.id,
                   clientAction,
                   context.sideEffects,
                   context.events,
                   snapshot
                 )
               )
             ))
          } else {
            (sequence,
             Some(
               OutReply(
                 CrudReply(
                   commandId = command.id,
                   clientAction = clientAction
                 )
               )
             ))
          }
        case (_, InInit(i)) =>
          throw new IllegalStateException("Entity already inited")
        case (_, InEmpty) =>
          throw new IllegalStateException("Received empty/unknown message")

        //case ((sequence, _), aOrB @ (InCreate(_) | InFetch(_))) => ???
      }
      .collect {
        case (_, Some(message)) => CrudStreamOut(message)
      }
  }

  trait AbstractContext extends CrudContext {
    override def serviceCallFactory(): ServiceCallFactory = rootContext.serviceCallFactory()
  }

  class CommandContextImpl(override val entityId: String,
                           override val sequenceNumber: Long,
                           override val commandName: String,
                           override val commandId: Long,
                           val anySupport: AnySupport,
                           val handler: CrudEntityHandler,
                           val snapshotEvery: Int)
      extends CommandContext
      with AbstractContext
      with AbstractClientActionContext
      with AbstractEffectContext
      with ActivatableContext {

    final var events: Vector[ScalaPbAny] = Vector.empty
    final var performSnapshot: Boolean = false

    override def emit(event: AnyRef): Unit = {
      checkActive()
      val encoded = anySupport.encodeScala(event)
      val nextSequenceNumber = sequenceNumber + events.size + 1
      handler.handleState(ScalaPbAny.toJavaProto(encoded), new CrudEventContextImpl(entityId, nextSequenceNumber))
      events :+= encoded
      performSnapshot = (snapshotEvery > 0) && (performSnapshot || (nextSequenceNumber % snapshotEvery == 0))
    }
  }

  // FIXME add final val subEntityId: String
  class CrudContextImpl(override final val entityId: String) extends CrudContext with AbstractContext
  class CrudEventContextImpl(entityId: String, override final val sequenceNumber: Long)
      extends CrudContextImpl(entityId)
      with CrudEventContext
      with SnapshotContext
}
