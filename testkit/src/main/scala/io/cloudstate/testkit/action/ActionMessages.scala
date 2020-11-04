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

package io.cloudstate.testkit.action

import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.{Message => JavaPbMessage}
import io.cloudstate.protocol.action.{ActionCommand, ActionResponse}
import io.cloudstate.protocol.entity.{Failure, Forward, Reply, SideEffect}
import io.cloudstate.testkit.entity.EntityMessages
import scalapb.{GeneratedMessage => ScalaPbMessage}

object ActionMessages extends EntityMessages {
  type SideEffects = Seq[SideEffect]

  def command(service: String, name: String, payload: JavaPbMessage): ActionCommand =
    command(service, name, messagePayload(payload))

  def command(service: String, name: String, payload: ScalaPbMessage): ActionCommand =
    command(service, name, messagePayload(payload))

  def command(service: String, name: String, payload: Option[ScalaPbAny]): ActionCommand =
    ActionCommand(service, name, payload)

  def command(service: String, name: String): ActionCommand =
    ActionCommand(service, name)

  def command(payload: JavaPbMessage): ActionCommand =
    command(messagePayload(payload))

  def command(payload: ScalaPbMessage): ActionCommand =
    command(messagePayload(payload))

  def command(payload: Option[ScalaPbAny]): ActionCommand =
    ActionCommand(payload = payload)

  def reply(payload: JavaPbMessage): ActionResponse =
    reply(payload, Seq.empty)

  def reply(payload: JavaPbMessage, sideEffects: SideEffects): ActionResponse =
    reply(messagePayload(payload), sideEffects)

  def reply(payload: ScalaPbMessage): ActionResponse =
    reply(payload, Seq.empty)

  def reply(payload: ScalaPbMessage, sideEffects: SideEffects): ActionResponse =
    reply(messagePayload(payload), sideEffects)

  def reply(payload: Option[ScalaPbAny], sideEffects: SideEffects): ActionResponse =
    ActionResponse(ActionResponse.Response.Reply(Reply(payload)), sideEffects)

  def noReply(): ActionResponse =
    ActionResponse()

  def noReply(sideEffects: SideEffects): ActionResponse =
    ActionResponse(sideEffects = sideEffects)

  def forward(service: String, command: String, payload: JavaPbMessage): ActionResponse =
    forward(service, command, payload, Seq.empty)

  def forward(service: String, command: String, payload: JavaPbMessage, sideEffects: SideEffects): ActionResponse =
    forward(service, command, messagePayload(payload), sideEffects)

  def forward(service: String, command: String, payload: ScalaPbMessage): ActionResponse =
    forward(service, command, payload, Seq.empty)

  def forward(service: String, command: String, payload: ScalaPbMessage, sideEffects: SideEffects): ActionResponse =
    forward(service, command, messagePayload(payload), sideEffects)

  def forward(service: String, command: String, payload: Option[ScalaPbAny], sideEffects: SideEffects): ActionResponse =
    ActionResponse(ActionResponse.Response.Forward(Forward(service, command, payload)), sideEffects)

  def failure(description: String): ActionResponse =
    failure(description, Seq.empty)

  def failure(description: String, sideEffects: SideEffects): ActionResponse =
    ActionResponse(ActionResponse.Response.Failure(Failure(description = description)), sideEffects)
}
