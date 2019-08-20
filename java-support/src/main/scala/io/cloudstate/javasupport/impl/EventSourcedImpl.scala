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

class EventSourcedImpl(system: ActorSystem, service: StatefulService) extends EventSourced {
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
  override def handle(in: akka.stream.scaladsl.Source[EventSourcedStreamIn, akka.NotUsed]): akka.stream.scaladsl.Source[EventSourcedStreamOut, akka.NotUsed] =
    in.flatMapConcat {
      _.message match {
          case InInit(i) =>
            //Look up service by i.serviceName
            //Provide the i.entityId
            //Deserialize the i.snapshot
            //Pass the snapshot to the user's service
            ???
          case InEvent(e) =>
            //Sanity-check the e.sequence number against the previous
            //Deserialize the e.payload event
            //Pass the payload event to the user's service
            ???
          case InCommand(c) =>
            //Lookup c.entityId
            //c.id
            //c.name
            //Deserialize the c.payload
            //Return the response as a stream if c.streamed is true, a reply if not
            ???
          case InEmpty => Source.empty // FIXME Ignore?
        }
    }
}