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

import io.cloudstate.protocol.event_sourced._
import io.cloudstate.protocol.event_sourced.EventSourcedStreamIn.Message.{Empty => InEmpty, Init => InInit, Event => InEvent, Command => InCommand}
import io.cloudstate.protocol.event_sourced.EventSourcedStreamOut.Message.{Empty => OutEmpty, Reply => OutReply, Failure => OutFailure}

import akka.stream.scaladsl.Source
import akka.actor.ActorSystem
import io.cloudstate.javasupport.StatefulService
import io.cloudstate.protocol.entity.{ClientAction, Reply => ClientActionReply}
import io.cloudstate.javasupport.eventsourced._

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
    in.mapConcat {
      // FIXME create some sort of uber-context which implements all contexts so we can set/unset values on it?
      var handler: EventSourcedEntityHandler = null
      var entityId: String = null
      (e: EventSourcedStreamIn) => e.message match {
          case InEvent(e) =>
            handler match {
              case null => ??? // FIXME error
              case h =>
                val context = new EventContext {
                  override def sequenceNumber: Long = e.sequence // TODO do not close over `h`
                  override def entityId: String = entityId // FIXME Should this be needed given that we pass it when creating the EventSourcedEntityHandler?
                }
                val ev = e.payload.get // FIXME deserialize
                h.handleEvent(ev, context)
            }
            Nil

          case InCommand(c) =>
            handler match {
              case null => ??? // FIXME error
              case h =>
                // TODO check entityId == c.entityId?
                val context = new CommandContext {
                  override def effect(): Unit = ???
                  override def forward(): Unit = ???
                  override def fail(errorMessage: String): Unit = ??? // FIXME should this throw inline, or record an error to be thrown? Can multiple invocations change this?
                  override def entityId: String = c.entityId
                  override def sequenceNumber: Long = ??? // FIXME Should CommandContext *really* have a sequenceNo?
                  override def commandName: String = c.name
                  override def commandId: Long = c.id
                  override def emit(event: AnyRef): Unit = ??? // FIXME Is this really a good idea, how to handle backpressure?
                }
                val cmd = service.anySupport.decode(c.payload.get) // FIXME isEmpty?
                val reply = h.handleCommand(cmd, context) // FIXME is this allowed to throw?

                val replyPayload = service.anySupport.encode(reply) // FIXME handle failures

                // FIXME implement snapshot-every-n?
                EventSourcedStreamOut(
                  OutReply(
                    EventSourcedReply(
                      c.id,
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
                      Nil, // FIXME collect events in handler
                      None // FIXME get snapshot from handler
                      )
                    )
                ) :: Nil // This is allocation Hell
            }

          case InInit(i) =>
            handler match {
              case null =>
                handlerFactories.get(i.serviceName) match {
                  case Some(e) =>
                    val context = new EventSourcedContext { override def entityId: String = i.entityId } // TODO do not close over `i`
                    entityId = i.entityId
                    handler = e.create(context) // FIXME handle failure
                  case None =>
                    // FIXME Service does not exist, or is not an event sourced service
                }

                for {
                  s <- i.snapshot
                  any <- s.snapshot
                } {
                  val context = new SnapshotContext {
                    override def entityId: String = i.entityId // TODO do not close over `i`
                    override def sequenceNumber: Long = s.snapshotSequence // TODO do not close over `snapshot`
                  }
                  val snapshotObject = service.anySupport.decode(any)
                  handler.handleSnapshot(snapshotObject, context)
                }
              case h => // FIXME handle double-init?
            }
            Nil
          case InEmpty =>
            Nil // FIXME Ignore?
        }
    }
  }
}