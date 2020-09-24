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
import com.google.protobuf.{StringValue, Any => JavaPbAny, Empty => JavaPbEmpty, Message => JavaPbMessage}
import io.cloudstate.protocol.entity._
import io.cloudstate.protocol.event_sourced._
import scalapb.{GeneratedMessage => ScalaPbMessage}

object EventSourcedMessages {
  import EventSourcedStreamIn.{Message => InMessage}
  import EventSourcedStreamOut.{Message => OutMessage}

  case class Effects(
      events: Seq[ScalaPbAny] = Seq.empty,
      snapshot: Option[ScalaPbAny] = None,
      sideEffects: Seq[SideEffect] = Seq.empty
  ) {
    def withEvents(message: JavaPbMessage, messages: JavaPbMessage*): Effects =
      copy(events = events ++ (message +: messages).map(protobufAny))

    def withEvents(message: ScalaPbMessage, messages: ScalaPbMessage*): Effects =
      copy(events = events ++ (message +: messages).map(protobufAny))

    def withSnapshot(message: JavaPbMessage): Effects =
      copy(snapshot = messagePayload(message))

    def withSnapshot(message: ScalaPbMessage): Effects =
      copy(snapshot = messagePayload(message))

    def withSideEffect(service: String, command: String, message: JavaPbMessage): Effects =
      withSideEffect(service, command, messagePayload(message), synchronous = false)

    def withSideEffect(service: String, command: String, message: JavaPbMessage, synchronous: Boolean): Effects =
      withSideEffect(service, command, messagePayload(message), synchronous)

    def withSideEffect(service: String, command: String, message: ScalaPbMessage): Effects =
      withSideEffect(service, command, messagePayload(message), synchronous = false)

    def withSideEffect(service: String, command: String, message: ScalaPbMessage, synchronous: Boolean): Effects =
      withSideEffect(service, command, messagePayload(message), synchronous)

    def withSideEffect(service: String, command: String, payload: Option[ScalaPbAny], synchronous: Boolean): Effects =
      copy(sideEffects = sideEffects :+ SideEffect(service, command, payload, synchronous))

    def ++(other: Effects): Effects =
      Effects(events ++ other.events, snapshot.orElse(other.snapshot), sideEffects ++ other.sideEffects)
  }

  object Effects {
    val empty: Effects = Effects()
  }

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

  def reply(id: Long, payload: JavaPbMessage): OutMessage =
    reply(id, payload, Effects.empty)

  def reply(id: Long, payload: JavaPbMessage, effects: Effects): OutMessage =
    reply(id, messagePayload(payload), effects)

  def reply(id: Long, payload: ScalaPbMessage): OutMessage =
    reply(id, payload, Effects.empty)

  def reply(id: Long, payload: ScalaPbMessage, effects: Effects): OutMessage =
    reply(id, messagePayload(payload), effects)

  def reply(id: Long, payload: Option[ScalaPbAny], effects: Effects): OutMessage =
    replyAction(id, clientActionReply(payload), effects)

  def replyAction(id: Long, action: Option[ClientAction], effects: Effects): OutMessage =
    OutMessage.Reply(EventSourcedReply(id, action, effects.sideEffects, effects.events, effects.snapshot))

  def forward(id: Long, service: String, command: String, payload: JavaPbMessage): OutMessage =
    forward(id, service, command, payload, Effects.empty)

  def forward(id: Long, service: String, command: String, payload: JavaPbMessage, effects: Effects): OutMessage =
    forward(id, service, command, messagePayload(payload), effects)

  def forward(id: Long, service: String, command: String, payload: ScalaPbMessage): OutMessage =
    forward(id, service, command, payload, Effects.empty)

  def forward(id: Long, service: String, command: String, payload: ScalaPbMessage, effects: Effects): OutMessage =
    forward(id, service, command, messagePayload(payload), effects)

  def forward(id: Long, service: String, command: String, payload: Option[ScalaPbAny], effects: Effects): OutMessage =
    replyAction(id, clientActionForward(service, command, payload), effects)

  def actionFailure(id: Long, description: String): OutMessage =
    OutMessage.Reply(EventSourcedReply(id, clientActionFailure(id, description, restart = false)))

  def actionFailure(id: Long, description: String, restart: Boolean): OutMessage =
    OutMessage.Reply(EventSourcedReply(id, clientActionFailure(id, description, restart)))

  def failure(description: String): OutMessage =
    failure(id = 0, description)

  def failure(id: Long, description: String): OutMessage =
    OutMessage.Failure(Failure(id, description))

  def clientActionReply(payload: Option[ScalaPbAny]): Option[ClientAction] =
    Some(ClientAction(ClientAction.Action.Reply(Reply(payload))))

  def clientActionForward(service: String, command: String, payload: Option[ScalaPbAny]): Option[ClientAction] =
    Some(ClientAction(ClientAction.Action.Forward(Forward(service, command, payload))))

  def clientActionFailure(description: String): Option[ClientAction] =
    clientActionFailure(id = 0, description)

  def clientActionFailure(id: Long, description: String): Option[ClientAction] =
    clientActionFailure(id, description, restart = false)

  def clientActionFailure(id: Long, description: String, restart: Boolean): Option[ClientAction] =
    Some(ClientAction(ClientAction.Action.Failure(Failure(id, description, restart))))

  def persist(event: JavaPbMessage, events: JavaPbMessage*): Effects =
    Effects.empty.withEvents(event, events: _*)

  def persist(event: ScalaPbMessage, events: ScalaPbMessage*): Effects =
    Effects.empty.withEvents(event, events: _*)

  def sideEffect(service: String, command: String, payload: JavaPbMessage): Effects =
    sideEffect(service, command, messagePayload(payload), synchronous = false)

  def sideEffect(service: String, command: String, payload: JavaPbMessage, synchronous: Boolean): Effects =
    sideEffect(service, command, messagePayload(payload), synchronous)

  def sideEffect(service: String, command: String, payload: ScalaPbMessage): Effects =
    sideEffect(service, command, messagePayload(payload), synchronous = false)

  def sideEffect(service: String, command: String, payload: ScalaPbMessage, synchronous: Boolean): Effects =
    sideEffect(service, command, messagePayload(payload), synchronous)

  def sideEffect(service: String, command: String, payload: Option[ScalaPbAny], synchronous: Boolean): Effects =
    Effects.empty.withSideEffect(service, command, payload, synchronous)

  def messagePayload(message: JavaPbMessage): Option[ScalaPbAny] =
    Option(message).map(protobufAny)

  def messagePayload(message: ScalaPbMessage): Option[ScalaPbAny] =
    Option(message).map(protobufAny)

  def protobufAny(message: JavaPbMessage): ScalaPbAny = message match {
    case javaPbAny: JavaPbAny => ScalaPbAny.fromJavaProto(javaPbAny)
    case _ => ScalaPbAny("type.googleapis.com/" + message.getDescriptorForType.getFullName, message.toByteString)
  }

  def protobufAny(message: ScalaPbMessage): ScalaPbAny = message match {
    case scalaPbAny: ScalaPbAny => scalaPbAny
    case _ => ScalaPbAny("type.googleapis.com/" + message.companion.scalaDescriptor.fullName, message.toByteString)
  }

  def primitiveString(value: String): ScalaPbAny =
    ScalaPbAny("p.cloudstate.io/string", StringValue.of(value).toByteString)
}
