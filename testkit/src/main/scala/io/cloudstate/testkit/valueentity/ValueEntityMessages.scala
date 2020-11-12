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

package io.cloudstate.testkit.valueentity

import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.{Message => JavaPbMessage}
import io.cloudstate.protocol.entity._
import io.cloudstate.protocol.value_entity._
import io.cloudstate.testkit.entity.EntityMessages
import scalapb.{GeneratedMessage => ScalaPbMessage}

object ValueEntityMessages extends EntityMessages {
  import ValueEntityStreamIn.{Message => InMessage}
  import ValueEntityStreamOut.{Message => OutMessage}
  import ValueEntityAction.Action._

  case class Effects(sideEffects: Seq[SideEffect] = Seq.empty, valueEntityAction: Option[ValueEntityAction] = None) {

    def withUpdateAction(message: JavaPbMessage): Effects =
      copy(valueEntityAction = Some(ValueEntityAction(Update(ValueEntityUpdate(messagePayload(message))))))

    def withUpdateAction(message: ScalaPbMessage): Effects =
      copy(valueEntityAction = Some(ValueEntityAction(Update(ValueEntityUpdate(messagePayload(message))))))

    def withDeleteAction(): Effects =
      copy(valueEntityAction = Some(ValueEntityAction(Delete(ValueEntityDelete()))))

    def withSideEffect(service: String, command: String, message: ScalaPbMessage, synchronous: Boolean): Effects =
      withSideEffect(service, command, messagePayload(message), synchronous)

    private def withSideEffect(service: String,
                               command: String,
                               payload: Option[ScalaPbAny],
                               synchronous: Boolean): Effects =
      copy(sideEffects = sideEffects :+ SideEffect(service, command, payload, synchronous))
  }

  object Effects {
    val empty: Effects = Effects()
  }

  val EmptyInMessage: InMessage = InMessage.Empty

  def init(serviceName: String, entityId: String): InMessage =
    init(serviceName, entityId, Some(ValueEntityInitState()))

  def init(serviceName: String, entityId: String, state: ValueEntityInitState): InMessage =
    init(serviceName, entityId, Some(state))

  def init(serviceName: String, entityId: String, state: Option[ValueEntityInitState]): InMessage =
    InMessage.Init(ValueEntityInit(serviceName, entityId, state))

  def state(payload: JavaPbMessage): ValueEntityInitState =
    ValueEntityInitState(messagePayload(payload))

  def state(payload: ScalaPbMessage): ValueEntityInitState =
    ValueEntityInitState(messagePayload(payload))

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

  private def reply(id: Long, payload: Option[ScalaPbAny], crudAction: Option[ValueEntityAction]): OutMessage =
    OutMessage.Reply(ValueEntityReply(id, clientActionReply(payload), Seq.empty, crudAction))

  private def reply(id: Long, payload: Option[ScalaPbAny], effects: Effects): OutMessage =
    OutMessage.Reply(ValueEntityReply(id, clientActionReply(payload), effects.sideEffects, effects.valueEntityAction))

  def forward(id: Long, service: String, command: String, payload: ScalaPbMessage): OutMessage =
    forward(id, service, command, payload, Effects.empty)

  def forward(id: Long, service: String, command: String, payload: ScalaPbMessage, effects: Effects): OutMessage =
    forward(id, service, command, messagePayload(payload), effects)

  private def forward(id: Long,
                      service: String,
                      command: String,
                      payload: Option[ScalaPbAny],
                      effects: Effects): OutMessage =
    replyAction(id, clientActionForward(service, command, payload), effects)

  private def replyAction(id: Long, action: Option[ClientAction], effects: Effects): OutMessage =
    OutMessage.Reply(ValueEntityReply(id, action, effects.sideEffects, effects.valueEntityAction))

  def actionFailure(id: Long, description: String): OutMessage =
    OutMessage.Reply(ValueEntityReply(id, clientActionFailure(id, description)))

  def failure(description: String): OutMessage =
    failure(id = 0, description)

  def failure(id: Long, description: String): OutMessage =
    OutMessage.Failure(Failure(id, description))

  def update(state: JavaPbMessage): Effects =
    Effects.empty.withUpdateAction(state)

  def update(state: ScalaPbMessage): Effects =
    Effects.empty.withUpdateAction(state)

  def delete(): Effects =
    Effects.empty.withDeleteAction()
}
