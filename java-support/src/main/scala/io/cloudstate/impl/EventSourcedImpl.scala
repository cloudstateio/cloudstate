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

package io.cloudstate.impl

import io.cloudstate.eventsourced._

class EventSourcedImpl extends EventSourced {
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
  def handle(in: akka.stream.scaladsl.Source[io.cloudstate.eventsourced.EventSourcedStreamIn, akka.NotUsed]): akka.stream.scaladsl.Source[io.cloudstate.eventsourced.EventSourcedStreamOut, akka.NotUsed] = ???
  
}