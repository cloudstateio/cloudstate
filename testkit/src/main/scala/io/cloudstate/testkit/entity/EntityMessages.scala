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

package io.cloudstate.testkit.entity

import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.empty.{Empty => ScalaPbEmpty}
import com.google.protobuf.{StringValue, Any => JavaPbAny, Empty => JavaPbEmpty, Message => JavaPbMessage}
import io.cloudstate.protocol.entity._
import scalapb.{GeneratedMessage => ScalaPbMessage}

object EntityMessages extends EntityMessages

trait EntityMessages {
  val EmptyJavaMessage: JavaPbMessage = JavaPbEmpty.getDefaultInstance
  val EmptyScalaMessage: ScalaPbMessage = ScalaPbEmpty.defaultInstance

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

  def sideEffect(service: String, command: String, payload: JavaPbMessage): SideEffect =
    sideEffect(service, command, messagePayload(payload), synchronous = false)

  def sideEffect(service: String, command: String, payload: JavaPbMessage, synchronous: Boolean): SideEffect =
    sideEffect(service, command, messagePayload(payload), synchronous)

  def sideEffect(service: String, command: String, payload: ScalaPbMessage): SideEffect =
    sideEffect(service, command, messagePayload(payload), synchronous = false)

  def sideEffect(service: String, command: String, payload: ScalaPbMessage, synchronous: Boolean): SideEffect =
    sideEffect(service, command, messagePayload(payload), synchronous)

  def sideEffect(service: String, command: String, payload: Option[ScalaPbAny], synchronous: Boolean): SideEffect =
    SideEffect(service, command, payload, synchronous)

  def streamCancelled(entityId: String): StreamCancelled =
    streamCancelled(id = 0, entityId)

  def streamCancelled(id: Long, entityId: String): StreamCancelled =
    StreamCancelled(entityId, id)

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

  def readPrimitiveString(any: ScalaPbAny): String =
    if (any.typeUrl == "p.cloudstate.io/string") {
      val stream = any.value.newCodedInput
      stream.readTag // assume it's for string
      stream.readString
    } else ""
}
