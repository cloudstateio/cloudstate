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

package io.cloudstate.tck

import com.google.protobuf.ByteString
import io.cloudstate.protocol.action.ActionResponse
import io.cloudstate.protocol.event_sourced.{EventSourcedStreamIn, EventSourcedStreamOut}
import io.cloudstate.tck.model.eventlogeventing.{
  EmitEventRequest,
  EventLogSubscriberModel,
  EventSourcedEntityOneClient,
  EventSourcedEntityTwoClient
}
import io.cloudstate.tck.model.eventlogeventing

trait EventingTCK extends TCKSpec {

  object EventingTCKModel {
    def eventLogSubscriptionTest(test: => Any): Unit =
      testFor(EventLogSubscriberModel)(test)
  }

  object EventingTCKProxy {
    val eventLogEventingEventSourcedEntityOne: EventSourcedEntityOneClient =
      eventlogeventing.EventSourcedEntityOneClient(client.settings)(client.system)

    val eventLogEventingEventSourcedEntityTwo: EventSourcedEntityTwoClient =
      eventlogeventing.EventSourcedEntityTwoClient(client.settings)(client.system)

    def emitEventOne(id: String, step: eventlogeventing.ProcessStep.Step): Unit =
      eventLogEventingEventSourcedEntityOne.emitEvent(
        EmitEventRequest(id,
                         EmitEventRequest.Event
                           .EventOne(eventlogeventing.EventOne(Some(eventlogeventing.ProcessStep(step)))))
      )

    def emitReplyEventOne(id: String, message: String): Unit =
      emitEventOne(id, eventlogeventing.ProcessStep.Step.Reply(eventlogeventing.Reply(message)))

    def emitForwardEventOne(id: String, message: String): Unit =
      emitEventOne(id, eventlogeventing.ProcessStep.Step.Forward(eventlogeventing.Forward(message)))

    def verifyEventSourcedInitCommandReply(id: String): Unit = {
      val connection = interceptor.expectEventSourcedEntityConnection()
      val init = connection.expectIncomingMessage[EventSourcedStreamIn.Message.Init]
      init.value.serviceName must ===(eventlogeventing.EventSourcedEntityOne.name)
      init.value.entityId must ===(id)
      connection.expectIncomingMessage[EventSourcedStreamIn.Message.Command]
      connection.expectOutgoingMessage[EventSourcedStreamOut.Message.Reply]
    }

    def verifySubscriberCommandResponse(step: eventlogeventing.ProcessStep.Step): ActionResponse = {
      val subscriberConnection = interceptor.expectActionUnaryConnection()
      val eventOneIn = eventlogeventing.EventOne.parseFrom(
        subscriberConnection.command.payload.fold(ByteString.EMPTY)(_.value).newCodedInput()
      )
      eventOneIn.step must ===(Some(eventlogeventing.ProcessStep(step)))
      subscriberConnection.expectResponse()
    }

    def verifySubscriberReplyCommand(id: String, message: String): Unit = {
      val response =
        verifySubscriberCommandResponse(eventlogeventing.ProcessStep.Step.Reply(eventlogeventing.Reply(message)))
      response.response.isReply must ===(true)
      val reply = eventlogeventing.Response.parseFrom(response.response.reply.get.payload.get.value.newCodedInput())
      reply.id must ===(id)
      reply.message must ===(message)
    }

    def verifySubscriberForwardCommand(id: String, message: String): Unit = {
      val response =
        verifySubscriberCommandResponse(eventlogeventing.ProcessStep.Step.Forward(eventlogeventing.Forward(message)))
      response.response.isForward must ===(true)
      val subscriberConnection = interceptor.expectActionUnaryConnection()
      subscriberConnection.command.name must ===("Effect")
    }

    def terminate(): Unit = {
      eventLogEventingEventSourcedEntityOne.close()
      eventLogEventingEventSourcedEntityTwo.close()
    }
  }

  override def afterAll(): Unit =
    try EventingTCKProxy.terminate()
    finally super.afterAll()

  def verifyEventingProxy(): Unit = {
    import EventingTCKModel._
    import EventingTCKProxy._

    "consume an event" in eventLogSubscriptionTest {
      emitReplyEventOne("eventlogeventing:1", "some message")
      verifyEventSourcedInitCommandReply("eventlogeventing:1")
      verifySubscriberReplyCommand("eventlogeventing:1", "some message")
    }

    "forward a consumed event" in eventLogSubscriptionTest {
      emitForwardEventOne("eventlogeventing:2", "some message")
      verifyEventSourcedInitCommandReply("eventlogeventing:2")
      verifySubscriberForwardCommand("eventlogeventing:2", "some message")
    }

    "process json events" in eventLogSubscriptionTest {
      eventLogEventingEventSourcedEntityTwo.emitJsonEvent(
        eventlogeventing.JsonEvent("eventlogeventing:3", "some json message")
      )

      val connection = interceptor.expectEventSourcedEntityConnection()
      val init = connection.expectIncomingMessage[EventSourcedStreamIn.Message.Init]
      init.value.serviceName must ===(eventlogeventing.EventSourcedEntityTwo.name)
      init.value.entityId must ===("eventlogeventing:3")
      connection.expectIncomingMessage[EventSourcedStreamIn.Message.Command]
      val reply = connection.expectOutgoingMessage[EventSourcedStreamOut.Message.Reply]
      reply.value.events must have size (1)
      reply.value.events.head.typeUrl must startWith("json.cloudstate.io/")

      val subscriberConnection = interceptor.expectActionUnaryConnection()
      val response = subscriberConnection.expectResponse()
      val parsed = eventlogeventing.Response.parseFrom(response.response.reply.get.payload.get.value.newCodedInput())
      parsed.id must ===("eventlogeventing:3")
      parsed.message must ===("some json message")
    }
  }
}
