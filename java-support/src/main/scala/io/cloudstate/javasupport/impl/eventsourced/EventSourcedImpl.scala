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

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import com.google.protobuf.Descriptors
import com.google.protobuf.any.{Any => ScalaPbAny}
import io.cloudstate.javasupport.{CloudState, StatefulService}
import io.cloudstate.javasupport.eventsourced._
import io.cloudstate.javasupport.impl.AnySupport
import io.cloudstate.protocol.entity.{ClientAction, Reply => ClientActionReply, Failure => ClientActionFailure}
import io.cloudstate.protocol.event_sourced.EventSourcedStreamIn.Message.{Command => InCommand, Empty => InEmpty, Event => InEvent, Init => InInit}
import io.cloudstate.protocol.event_sourced.EventSourcedStreamOut.Message.{Reply => OutReply}
import io.cloudstate.protocol.event_sourced._

import scala.util.control.{NonFatal, NoStackTrace}

final class EventSourcedStatefulService(val factory: EventSourcedEntityFactory,
                                  override val descriptor: Descriptors.ServiceDescriptor,
                                  val anySupport: AnySupport,
                                  override val persistenceId: String,
                                  val snapshotEvery: Int) extends StatefulService {

  override final val entityType = EventSourced.name
}

final class EventSourcedImpl(system: ActorSystem, services: Map[String, EventSourcedStatefulService]) extends EventSourced {

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
  override def handle(in: akka.stream.scaladsl.Source[EventSourcedStreamIn, akka.NotUsed]): akka.stream.scaladsl.Source[EventSourcedStreamOut, akka.NotUsed] = {
    in.prefixAndTail(1)
      .flatMapConcat {
        case (Seq(EventSourcedStreamIn(InInit(init))), source) =>
          source.via(runEntity(init))
        case _ =>
          // todo better error
          throw new RuntimeException("Expected Init message")
      }.recover {
        case e =>
          // FIXME translate to failure message
          throw e
      }
  }

  private def runEntity(init: EventSourcedInit): Flow[EventSourcedStreamIn, EventSourcedStreamOut, NotUsed] = {
    val service = services.getOrElse(init.serviceName, throw new RuntimeException("Service not found"))
    val handler = service.factory.create(new EventSourcedContextImpl(init.entityId)) // FIXME handle failure
    val entityId = init.entityId

    val startingSequenceNumber = (for {
      snapshot <- init.snapshot
      any <- snapshot.snapshot
    } yield {
      val snapshotSequence = snapshot.snapshotSequence
      val context = new SnapshotContext {
        override def entityId: String = entityId
        override def sequenceNumber: Long = snapshotSequence
      }
      handler.handleSnapshot(ScalaPbAny.toJavaProto(any), context)
      snapshotSequence
    }).getOrElse(0l)


    Flow[EventSourcedStreamIn].map(_.message).scan[(Long, Option[EventSourcedStreamOut.Message])]((startingSequenceNumber, None)) {
      case (_, InEvent(event)) =>
        val context = new EventContextImpl(entityId, event.sequence)
        val ev = ScalaPbAny.toJavaProto(event.payload.get) // FIXME empty?
        // todo deserialize the event first
        handler.handleEvent(ev, context)
        (event.sequence, None)
      case ((sequence, _), InCommand(command)) =>
        if (entityId != command.entityId) throw new IllegalStateException("Receiving entity is not the intended recipient of command")
        val cmd = ScalaPbAny.toJavaProto(command.payload.get) // FIXME isEmpty?
        val context = new CommandContextImpl(entityId, sequence, command.name, command.id, service.anySupport, handler, service.snapshotEvery)

        try {
          val reply = handler.handleCommand(cmd, context) // FIXME is this allowed to throw 
          context.error.foreach(_ => throw new FailInvoked) // Only happens if the user swallows the FailInvoked exception
          val replyPayload = ScalaPbAny.fromJavaProto(reply)
          val response = ClientAction(ClientAction.Action.Reply(ClientActionReply(Some(replyPayload))))

          val endSequenceNumber = sequence + context.events.size

          val snapshot =
            if (context.performSnapshot) {
              val s = handler.snapshot(new SnapshotContext {
                override def entityId: String     = entityId
                override def sequenceNumber: Long = endSequenceNumber
              })
              if (s.isPresent) Option(ScalaPbAny.fromJavaProto(s.get)) else None
            } else None

          val out = OutReply(
            EventSourcedReply(
              command.id,
              Some(response),
              Nil, // FIXME implement sideEffects
              context.events,
              snapshot
            )
          )

          (endSequenceNumber, Some(out))
        } catch {
          case NonFatal(t) =>
            val message = t match {
              case _: FailInvoked => context.error.getOrElse(throw new IllegalStateException("Unexpected error"))
              case t => "Internal error"// FIXME should we reply with t.getMessage?
            }

            val response = ClientAction(ClientAction.Action.Failure(ClientActionFailure(command.id, message)))

            val out = OutReply(
              EventSourcedReply(
                command.id,
                Some(response),
                Nil,
                Nil,
                None
              )
            )

          (sequence, Some(out)) // No progress was made
        } finally {
          context.active = false // Very important!
        }
      case (_, InInit(i)) =>
        throw new IllegalStateException("Entity already inited")
      case (_, InEmpty) =>
        throw new IllegalStateException("Received empty/unknown message")
    }.collect {
      case (_, Some(message)) => EventSourcedStreamOut(message)
    }
  }

  class CommandContextImpl(
    override val entityId: String,
    override val sequenceNumber: Long,
    override val commandName: String,
    override val commandId: Long,
    val anySupport: AnySupport,
    val handler: EventSourcedEntityHandler,
    val snapshotEvery: Int) extends CommandContext {
    
    final var active: Boolean = true
    final var events: Vector[ScalaPbAny] = Vector.empty
    final var error: Option[String] = None
    final var performSnapshot: Boolean = false

    override def effect(): Unit = ???
    override def forward(): Unit = ???
    override def fail(errorMessage: String): Unit = {
      if (!active) throw new IllegalStateException("Context no longer active!")
      else {
        error match {
          case None =>
            error = Some(errorMessage)
            throw new FailInvoked
          case _ =>
            throw new IllegalStateException("fail(…) already previously invoked!")
        }
      }
    }
    override def emit(event: AnyRef): Unit = {
      if (!active) throw new IllegalStateException("Context no longer active!")
      else {
        val encoded = anySupport.encodeScala(event)
        events :+= encoded // FIXME if the next line fails, we still retain the event, how to handle?
        val nextSequenceNumber = sequenceNumber + events.size
        handler.handleEvent(ScalaPbAny.toJavaProto(encoded), new EventContextImpl(entityId, nextSequenceNumber))
        performSnapshot = performSnapshot || (nextSequenceNumber % snapshotEvery == 0)
      }
    }
  }

  class FailInvoked extends Throwable with NoStackTrace {
    override def toString: String = "CommandContext.fail(…) invoked"
  }
  class EventSourcedContextImpl(override final val entityId: String) extends EventSourcedContext
  class EventContextImpl(entityId: String, override final val sequenceNumber: Long) extends EventSourcedContextImpl(entityId) with EventContext
}