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

package io.cloudstate.javasupport.impl

import akka.NotUsed
import io.cloudstate.protocol.event_sourced._
import io.cloudstate.protocol.event_sourced.EventSourcedStreamIn.Message.{Command => InCommand, Empty => InEmpty, Event => InEvent, Init => InInit}
import io.cloudstate.protocol.event_sourced.EventSourcedStreamOut.Message.{Empty => OutEmpty, Failure => OutFailure, Reply => OutReply}
import akka.stream.scaladsl.{Flow, Source}
import akka.actor.ActorSystem
import io.cloudstate.javasupport.StatefulService
import io.cloudstate.protocol.entity.{ClientAction, Reply => ClientActionReply}
import io.cloudstate.javasupport.eventsourced._
import com.google.protobuf.any.{Any => ScalaPbAny}

class EventSourcedImpl(system: ActorSystem, service: StatefulService) extends EventSourced {

  val handlerFactories: Map[String, EventSourcedEntityFactory] =
    service
      .entities
      .iterator
      .filter(_.entityType == "cloudstate.eventsourced.EventSourced")
      .map(e => (e.serviceName, new EventSourcedEntityFactory {
        override def create(context: EventSourcedContext): EventSourcedEntityHandler = ??? // FIXME How to implement/obtain this?
      })).toMap

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
    val handler = handlerFactories.get(init.serviceName) match {
      case Some(e) =>
        e.create(new EventSourcedContextImpl(init.entityId)) // FIXME handle failure
      case None =>
      // FIXME Service does not exist, or is not an event sourced service
        throw new RuntimeException("not found")
    }

    val entityId = init.entityId

    val startingSequenceNumber = (for {
      snapshot <- init.snapshot
      any <- snapshot.snapshot
    } yield {
      val context = new SnapshotContext {
        override def entityId: String = entityId // TODO do not close over `i`
        override def sequenceNumber: Long = snapshot.snapshotSequence // TODO do not close over `snapshot`
      }
      val snapshotObject = service.anySupport.decode(any)
      handler.handleSnapshot(snapshotObject, context)
      snapshot.snapshotSequence
    }).getOrElse(0l)


    Flow[EventSourcedStreamIn].map(_.message).scan[(Long, Option[EventSourcedStreamOut.Message])]((startingSequenceNumber, None)) {
      case (_, InEvent(event)) =>
        val context = new EventContextImpl(entityId, event.sequence)
        val ev = service.anySupport.decode(event.payload.get) // FIXME empty?
        // todo deserialize the event first
        handler.handleEvent(ev, context)
        (event.sequence, None)
      case ((sequence, _), InCommand(command)) =>

        var events = Seq.empty[ScalaPbAny]
        var currentSequence = sequence
        var active = true

        // TODO check entityId == c.entityId?
        val context = new CommandContext {
          override def effect(): Unit = ???
          override def forward(): Unit = ???
          override def fail(errorMessage: String): Unit = {
            if (!active) throw new IllegalStateException()
            ??? // FIXME should this throw inline, or record an error to be thrown? Can multiple invocations change this?
            // I would expect that this would do the following:
            // - store the error message in a var
            // - throw a stacktrace-less exception that we catch later
            // - then, regardless of whether the user catches the error or not, if the error message is set in the var,
            //   we return that error.
            // - multiple invocations should probably fail
          }
          override def entityId: String = entityId
          // FIXME Should CommandContext *really* have a sequenceNo?
          // Yes. The sequence number is great for when you implement push notifications over an at-most once medium,
          // eg distributed pub-sub, as it allows you to detect dropped messages. eg, let's say you have a chat room
          // entity, the entities state might store the 100 most recent messages (not a classic event sourced entity,
          // but useful nonetheless). As an effect of persisting a message, the command will publish the message to
          // distributed pub-sub, along with its sequence number, and any users currently in the room will receive it.
          // But, if the sequence number is more than one greater than the last message they received, they can query
          // the entity to get the messages that they missed, thus effectively exactly once messaging can be
          // implemented.
          override def sequenceNumber: Long = sequence
          override def commandName: String = command.name
          override def commandId: Long = command.id
          override def emit(event: AnyRef): Unit = {
            if (!active) throw new IllegalStateException()
            // FIXME Is this really a good idea, how to handle backpressure?
            // No need to. events are sent with the command reply (since, you need to persist the events successfully
            // before the reply is sent to the user. Backpressure is implicit )
            val encoded = service.anySupport.encode(event)
            events :+= encoded
            currentSequence += 1
            handler.handleEvent(event, new EventContextImpl(entityId, currentSequence))
          }
        }
        val cmd = service.anySupport.decode(command.payload.get) // FIXME isEmpty? Also, validate that the class matches the service descriptor.
        val reply = handler.handleCommand(cmd, context) // FIXME is this allowed to throw?

        val replyPayload = service.anySupport.encode(reply) // FIXME handle failures. Also, validate that the class matches what's in the service descriptor for this command.

        // FIXME implement snapshot-every-n?
        val out = OutReply(
          EventSourcedReply(
            command.id,
            Some(
              ClientAction(
                ClientAction.Action.Reply(
                  ClientActionReply(
                    Some(replyPayload)
                  )
                )
              )
            ),
            Nil, // FIXME implement sideEffects
            events,
            None // FIXME get snapshot from handler
          )
        )

        (currentSequence, Some(out))

      case (_, InInit(i)) =>
        // FIXME fail, double init
        throw new IllegalStateException("already inited")
      case (_, InEmpty) =>
        throw new IllegalStateException("received empty/unknown message")
    }.collect {
      case (_, Some(message)) => EventSourcedStreamOut(message)
    }
  }

  class EventSourcedContextImpl(override final val entityId: String) extends EventSourcedContext
  class EventContextImpl(entityId: String, override final val sequenceNumber: Long) extends EventSourcedContextImpl(entityId) with EventContext

}