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

package io.cloudstate.javasupport.impl.eventsourced

import java.util.Optional

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import com.google.protobuf.{Descriptors, Any => JavaPbAny}
import com.google.protobuf.any.{Any => ScalaPbAny}
import io.cloudstate.javasupport.CloudStateRunner.Configuration
import io.cloudstate.javasupport.{Context, ServiceCallFactory, StatefulService}
import io.cloudstate.javasupport.eventsourced._
import io.cloudstate.javasupport.impl.{
  AbstractClientActionContext,
  AbstractEffectContext,
  ActivatableContext,
  AnySupport,
  FailInvoked,
  ResolvedEntityFactory,
  ResolvedServiceMethod
}
import io.cloudstate.protocol.event_sourced.EventSourcedStreamIn.Message.{
  Command => InCommand,
  Empty => InEmpty,
  Event => InEvent,
  Init => InInit
}
import io.cloudstate.protocol.event_sourced.EventSourcedStreamOut.Message.{Reply => OutReply}
import io.cloudstate.protocol.event_sourced._

final class EventSourcedStatefulService(val factory: EventSourcedEntityFactory,
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

  override final val entityType = EventSourced.name
  final def withSnapshotEvery(snapshotEvery: Int): EventSourcedStatefulService =
    if (snapshotEvery != this.snapshotEvery)
      new EventSourcedStatefulService(this.factory, this.descriptor, this.anySupport, this.persistenceId, snapshotEvery)
    else
      this
}

final class EventSourcedImpl(_system: ActorSystem,
                             _services: Map[String, EventSourcedStatefulService],
                             rootContext: Context,
                             configuration: Configuration)
    extends EventSourced {
  private final val system = _system
  private final val services = _services.iterator
    .map({
      case (name, esss) =>
        // FIXME overlay configuration provided by _system
        (name, if (esss.snapshotEvery == 0) esss.withSnapshotEvery(configuration.snapshotEvery) else esss)
    })
    .toMap

  /**
   * The stream. One stream will be established per active entity.
   * Once established, the first message sent will be Init, which contains the entity ID, and,
   * if the entity has previously persisted a snapshot, it will contain that snapshot. It will
   * then send zero to many event messages, one for each event previously persisted. The entity
   * is expected to apply these to its state in a deterministic fashion. Once all the events
   * are sent, one to many commands are sent, with new commands being sent as new requests for
   * the entity come in. The entity is expected to reply to each command with exactly one reply
   * message. The entity should reply in order, and any events that the entity requests to be
   * persisted the entity should handle itself, applying them to its own state, as if they had
   * arrived as events when the event stream was being replayed on load.
   */
  override def handle(
      in: akka.stream.scaladsl.Source[EventSourcedStreamIn, akka.NotUsed]
  ): akka.stream.scaladsl.Source[EventSourcedStreamOut, akka.NotUsed] =
    in.prefixAndTail(1)
      .flatMapConcat {
        case (Seq(EventSourcedStreamIn(InInit(init), _)), source) =>
          source.via(runEntity(init))
        case _ =>
          // todo better error
          throw new RuntimeException("Expected Init message")
      }
      .recover {
        case e =>
          // FIXME translate to failure message
          throw e
      }

  private def runEntity(init: EventSourcedInit): Flow[EventSourcedStreamIn, EventSourcedStreamOut, NotUsed] = {
    val service =
      services.getOrElse(init.serviceName, throw new RuntimeException(s"Service not found: ${init.serviceName}"))
    val handler = service.factory.create(new EventSourcedContextImpl(init.entityId))
    val thisEntityId = init.entityId

    val startingSequenceNumber = (for {
      snapshot <- init.snapshot
      any <- snapshot.snapshot
    } yield {
      val snapshotSequence = snapshot.snapshotSequence
      val context = new SnapshotContext with AbstractContext {
        override def entityId: String = thisEntityId
        override def sequenceNumber: Long = snapshotSequence
      }
      handler.handleSnapshot(ScalaPbAny.toJavaProto(any), context)
      snapshotSequence
    }).getOrElse(0L)

    Flow[EventSourcedStreamIn]
      .map(_.message)
      .scan[(Long, Option[EventSourcedStreamOut.Message])]((startingSequenceNumber, None)) {
        case (_, InEvent(event)) =>
          val context = new EventContextImpl(thisEntityId, event.sequence)
          val ev = ScalaPbAny.toJavaProto(event.payload.get) // FIXME empty?
          handler.handleEvent(ev, context)
          (event.sequence, None)
        case ((sequence, _), InCommand(command)) =>
          if (thisEntityId != command.entityId)
            throw new IllegalStateException("Receiving entity is not the intended recipient of command")
          val cmd = ScalaPbAny.toJavaProto(command.payload.get)
          val context = new CommandContextImpl(thisEntityId,
                                               sequence,
                                               command.name,
                                               command.id,
                                               service.anySupport,
                                               handler,
                                               service.snapshotEvery)

          val reply = try {
            handler.handleCommand(cmd, context) // FIXME is this allowed to throw
          } catch {
            case FailInvoked =>
              Optional.empty[JavaPbAny]()
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
                 EventSourcedReply(
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
                 EventSourcedReply(
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
        case (_, Some(message)) => EventSourcedStreamOut(message)
      }
  }

  trait AbstractContext extends EventSourcedContext {
    override def serviceCallFactory(): ServiceCallFactory = rootContext.serviceCallFactory()
  }

  class CommandContextImpl(override val entityId: String,
                           override val sequenceNumber: Long,
                           override val commandName: String,
                           override val commandId: Long,
                           val anySupport: AnySupport,
                           val handler: EventSourcedEntityHandler,
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
      handler.handleEvent(ScalaPbAny.toJavaProto(encoded), new EventContextImpl(entityId, nextSequenceNumber))
      events :+= encoded
      performSnapshot = (snapshotEvery > 0) && (performSnapshot || (nextSequenceNumber % snapshotEvery == 0))
    }
  }

  class EventSourcedContextImpl(override final val entityId: String) extends EventSourcedContext with AbstractContext
  class EventContextImpl(entityId: String, override final val sequenceNumber: Long)
      extends EventSourcedContextImpl(entityId)
      with EventContext
}
