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

package io.cloudstate.testkit.eventsourced

import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.empty.{Empty => ScalaPbEmpty}
import com.google.protobuf.{Empty => JavaPbEmpty, Message => JavaPbMessage}
import io.cloudstate.protocol.entity._
import io.cloudstate.protocol.event_sourced._
import scalapb.{GeneratedMessage => ScalaPbMessage}

object EventSourcedMessages {
  import EventSourcedStreamIn.{Message => InMessage}
  import EventSourcedStreamOut.{Message => OutMessage}

  val EmptyInMessage: InMessage = InMessage.Empty
  val EmptyJavaMessage: JavaPbMessage = JavaPbEmpty.getDefaultInstance
  val EmptyScalaMessage: ScalaPbMessage = ScalaPbEmpty.defaultInstance

  def init(serviceName: String, entityId: String): InMessage =
    init(serviceName, entityId, None)

  def init(serviceName: String, entityId: String, snapshot: EventSourcedSnapshot): InMessage =
    init(serviceName, entityId, Option(snapshot))

  def init(serviceName: String, entityId: String, snapshot: Option[EventSourcedSnapshot]): InMessage =
    InMessage.Init(EventSourcedInit(serviceName, entityId, snapshot))

  def snapshot(sequence: Long, payload: JavaPbMessage): EventSourcedSnapshot =
    snapshot(sequence, messagePayload(payload))

  def snapshot(sequence: Long, payload: ScalaPbMessage): EventSourcedSnapshot =
    snapshot(sequence, messagePayload(payload))

  def snapshot(sequence: Long, payload: Option[ScalaPbAny]): EventSourcedSnapshot =
    EventSourcedSnapshot(sequence, payload)

  def event(sequence: Long, payload: JavaPbMessage): InMessage =
    event(sequence, messagePayload(payload))

  def event(sequence: Long, payload: ScalaPbMessage): InMessage =
    event(sequence, messagePayload(payload))

  def event(sequence: Long, payload: Option[ScalaPbAny]): InMessage =
    InMessage.Event(EventSourcedEvent(sequence, payload))

  def command(id: Long, entityId: String, name: String): InMessage =
    command(id, entityId, name, EmptyJavaMessage)

  def command(id: Long, entityId: String, name: String, payload: JavaPbMessage): InMessage =
    command(id, entityId, name, messagePayload(payload))

  def command(id: Long, entityId: String, name: String, payload: ScalaPbMessage): InMessage =
    command(id, entityId, name, messagePayload(payload))

  def command(id: Long, entityId: String, name: String, payload: Option[ScalaPbAny]): InMessage =
    InMessage.Command(Command(entityId, id, name, payload))

  def reply(id: Long, payload: JavaPbMessage, events: JavaPbMessage*): OutMessage =
    reply(id, messagePayload(payload), events.map(protobufAny), None)

  def reply(id: Long, payload: ScalaPbMessage, events: ScalaPbMessage*): OutMessage =
    reply(id, messagePayload(payload), events.map(protobufAny), None)

  def reply(id: Long, payload: JavaPbMessage, events: Seq[JavaPbMessage], snapshot: JavaPbMessage): OutMessage =
    reply(id, messagePayload(payload), events.map(protobufAny), messagePayload(snapshot))

  def reply(id: Long, payload: ScalaPbMessage, events: Seq[ScalaPbMessage], snapshot: ScalaPbMessage): OutMessage =
    reply(id, messagePayload(payload), events.map(protobufAny), messagePayload(snapshot))

  def reply(id: Long, payload: Option[ScalaPbAny], events: Seq[ScalaPbAny], snapshot: Option[ScalaPbAny]): OutMessage =
    OutMessage.Reply(EventSourcedReply(id, clientActionReply(payload), Seq.empty, events, snapshot))

  def actionFailure(id: Long, description: String): OutMessage =
    OutMessage.Reply(EventSourcedReply(id, clientActionFailure(id, description)))

  def failure(description: String): OutMessage =
    failure(id = 0, description)

  def failure(id: Long, description: String): OutMessage =
    OutMessage.Failure(Failure(id, description))

  def clientActionReply(payload: Option[ScalaPbAny]): Option[ClientAction] =
    Some(ClientAction(ClientAction.Action.Reply(Reply(payload))))

  def clientActionFailure(description: String): Option[ClientAction] =
    clientActionFailure(id = 0, description)

  def clientActionFailure(id: Long, description: String): Option[ClientAction] =
    Some(ClientAction(ClientAction.Action.Failure(Failure(id, description))))

  def messagePayload(message: JavaPbMessage): Option[ScalaPbAny] =
    Option(message).map(protobufAny)

  def messagePayload(message: ScalaPbMessage): Option[ScalaPbAny] =
    Option(message).map(protobufAny)

  def protobufAny(message: JavaPbMessage): ScalaPbAny =
    ScalaPbAny("type.googleapis.com/" + message.getDescriptorForType.getFullName, message.toByteString)

  def protobufAny(message: ScalaPbMessage): ScalaPbAny =
    ScalaPbAny("type.googleapis.com/" + message.companion.scalaDescriptor.fullName, message.toByteString)
}
