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

package io.cloudstate.testkit.crud

import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.empty.{Empty => ScalaPbEmpty}
import com.google.protobuf.{Any => JavaPbAny, Empty => JavaPbEmpty, Message => JavaPbMessage}
import io.cloudstate.protocol.crud.CrudAction.Action.Update
import io.cloudstate.protocol.crud.CrudAction.Action.Delete
import io.cloudstate.protocol.entity._
import io.cloudstate.protocol.crud._
import scalapb.{GeneratedMessage => ScalaPbMessage}

object CrudMessages {
  import CrudStreamIn.{Message => InMessage}
  import CrudStreamOut.{Message => OutMessage}

  case class Effects(sideEffects: Seq[SideEffect] = Seq.empty, crudAction: Option[CrudAction] = None) {

    def withUpdateAction(message: JavaPbMessage): Effects =
      copy(crudAction = Some(CrudAction(Update(CrudUpdate(messagePayload(message))))))

    def withUpdateAction(message: ScalaPbMessage): Effects =
      copy(crudAction = Some(CrudAction(Update(CrudUpdate(messagePayload(message))))))

    def withDeleteAction(): Effects =
      copy(crudAction = Some(CrudAction(Delete(CrudDelete()))))

    def withSideEffect(service: String, command: String, message: ScalaPbMessage): Effects =
      withSideEffect(service, command, messagePayload(message), synchronous = false)

    def withSideEffect(service: String, command: String, payload: Option[ScalaPbAny], synchronous: Boolean): Effects =
      copy(sideEffects = sideEffects :+ SideEffect(service, command, payload, synchronous))
  }

  object Effects {
    val empty: Effects = Effects()
  }

  val EmptyInMessage: InMessage = InMessage.Empty
  val EmptyJavaMessage: JavaPbMessage = JavaPbEmpty.getDefaultInstance
  val EmptyScalaMessage: ScalaPbMessage = ScalaPbEmpty.defaultInstance

  def init(serviceName: String, entityId: String): InMessage =
    init(serviceName, entityId, Some(CrudInitState()))

  def init(serviceName: String, entityId: String, state: CrudInitState): InMessage =
    init(serviceName, entityId, Some(state))

  def init(serviceName: String, entityId: String, state: Option[CrudInitState]): InMessage =
    InMessage.Init(CrudInit(serviceName, entityId, state))

  def state(payload: JavaPbMessage): CrudInitState =
    CrudInitState(messagePayload(payload))

  def state(payload: ScalaPbMessage): CrudInitState =
    CrudInitState(messagePayload(payload))

  def command(id: Long, entityId: String, name: String): InMessage =
    command(id, entityId, name, EmptyJavaMessage)

  def command(id: Long, entityId: String, name: String, payload: JavaPbMessage): InMessage =
    command(id, entityId, name, messagePayload(payload))

  def command(id: Long, entityId: String, name: String, payload: ScalaPbMessage): InMessage =
    command(id, entityId, name, messagePayload(payload))

  def command(id: Long, entityId: String, name: String, payload: Option[ScalaPbAny]): InMessage =
    InMessage.Command(Command(entityId, id, name, payload))

  def reply(id: Long, payload: JavaPbMessage): OutMessage =
    reply(id, messagePayload(payload), None)

  def reply(id: Long, payload: JavaPbMessage, effects: Effects): OutMessage =
    reply(id, messagePayload(payload), effects)

  def reply(id: Long, payload: ScalaPbMessage): OutMessage =
    reply(id, messagePayload(payload), None)

  def reply(id: Long, payload: ScalaPbMessage, effects: Effects): OutMessage =
    reply(id, messagePayload(payload), effects)

  def reply(id: Long, payload: Option[ScalaPbAny], crudAction: Option[CrudAction]): OutMessage =
    OutMessage.Reply(CrudReply(id, clientActionReply(payload), Seq.empty, crudAction))

  def reply(id: Long, payload: Option[ScalaPbAny], effects: Effects): OutMessage =
    OutMessage.Reply(CrudReply(id, clientActionReply(payload), effects.sideEffects, effects.crudAction))

  def replyAction(id: Long, action: Option[ClientAction], effects: Effects): OutMessage =
    OutMessage.Reply(CrudReply(id, action, effects.sideEffects, effects.crudAction))

  def forward(id: Long, service: String, command: String, payload: ScalaPbMessage): OutMessage =
    forward(id, service, command, payload, Effects.empty)

  def forward(id: Long, service: String, command: String, payload: ScalaPbMessage, effects: Effects): OutMessage =
    forward(id, service, command, messagePayload(payload), effects)

  def forward(id: Long, service: String, command: String, payload: Option[ScalaPbAny], effects: Effects): OutMessage =
    replyAction(id, clientActionForward(service, command, payload), effects)

  def actionFailure(id: Long, description: String): OutMessage =
    OutMessage.Reply(CrudReply(id, clientActionFailure(id, description)))

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

  def clientActionForward(service: String, command: String, payload: Option[ScalaPbAny]): Option[ClientAction] =
    Some(ClientAction(ClientAction.Action.Forward(Forward(service, command, payload))))

  def sideEffect(service: String, command: String, payload: ScalaPbMessage, synchronous: Boolean): Effects =
    sideEffect(service, command, messagePayload(payload), synchronous)

  def sideEffect(service: String, command: String, payload: ScalaPbMessage): Effects =
    sideEffect(service, command, messagePayload(payload), synchronous = false)

  def sideEffect(service: String, command: String, payload: Option[ScalaPbAny], synchronous: Boolean): Effects =
    Effects.empty.withSideEffect(service, command, payload, synchronous)

  def update(state: JavaPbMessage): Effects =
    Effects.empty.withUpdateAction(state)

  def update(state: ScalaPbMessage): Effects =
    Effects.empty.withUpdateAction(state)

  def delete(): Effects =
    Effects.empty.withDeleteAction()

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

}
