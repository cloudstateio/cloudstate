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

import io.cloudstate.protocol.crdt._
import akka.actor.ActorSystem
import io.cloudstate.javasupport.StatefulService

class CrdtImpl(system: ActorSystem, service: StatefulService) extends Crdt {
  /**
   * After invoking handle, the first message sent will always be a CrdtInit message, containing the entity ID, and,
   * if it exists or is available, the current state of the entity. After that, one or more commands may be sent,
   * as well as deltas as they arrive, and the entire state if either the entity is created, or the proxy wishes the
   * user function to replace its entire state.
   *  The user function must respond with one reply per command in. They do not necessarily have to be sent in the same
   * order that the commands were sent, the command ID is used to correlate commands to replies.
   */
  def handle(in: akka.stream.scaladsl.Source[CrdtStreamIn, akka.NotUsed]): akka.stream.scaladsl.Source[CrdtStreamOut, akka.NotUsed] = ???
}