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

package io.cloudstate.proxy.valueentity

import akka.actor.ActorRef
import akka.grpc.GrpcClientSettings
import akka.testkit.EventFilter
import akka.testkit.TestEvent.Mute
import com.google.protobuf.ByteString
import com.google.protobuf.any.{Any => ProtoAny}
import io.cloudstate.protocol.value_entity.ValueEntityClient
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}
import io.cloudstate.proxy.telemetry.AbstractTelemetrySpec
import io.cloudstate.proxy.valueentity.store.{InMemoryStore, RepositoryImpl}
import io.cloudstate.testkit.TestService
import io.cloudstate.testkit.valueentity.ValueEntityMessages

import scala.concurrent.duration._

class EntityPassivateSpec extends AbstractTelemetrySpec {

  "ValueEntity" should {

    "restart entity after passivation" in withTestKit(
      """
        | include "test-in-memory"
        | akka {
        |   loglevel = DEBUG
        |   loggers = ["akka.testkit.TestEventListener"]
        |   remote.artery.canonical.port = 0
        |   remote.artery.bind.port = ""
        | }
      """
    ) { testKit =>
      import ValueEntityMessages._
      import testKit._
      import testKit.system.dispatcher

      // silence any dead letters or unhandled messages during shutdown (when using test event listener)
      system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*received dead letter.*")))
      system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*unhandled message.*")))

      implicit val replyTo: ActorRef = testActor

      val service = TestService()
      val client =
        ValueEntityClient(GrpcClientSettings.connectToServiceAt("localhost", service.port).withTls(false))

      val entityConfiguration = ValueEntity.Configuration(
        serviceName = "service",
        userFunctionName = "test",
        passivationTimeout = 30.seconds,
        sendQueueSize = 100
      )

      val repository = new RepositoryImpl(new InMemoryStore)
      val entity = system.actorOf(ValueEntitySupervisor.props(client, entityConfiguration, repository), "entity")

      val emptyCommand = Some(protobufAny(EmptyJavaMessage))

      // init with empty state
      val connection = service.valueEntity.expectConnection()
      connection.expect(init("service", "entity"))

      // first command command fails
      entity ! EntityCommand(entityId = "test", name = "command1", emptyCommand)
      connection.expect(command(1, "entity", "command1"))
      connection.send(failure(1, "boom! failure"))
      expectMsg(UserFunctionReply(clientActionFailure(0, "Unexpected Value entity failure")))
      EventFilter.error("Unexpected Value entity failure - boom! failure", occurrences = 1)
      connection.expectClosed()
      //expectTerminated(entity) // should be expected!

      // re-init entity with empty state and send command
      val recreatedEntity =
        system.actorOf(ValueEntitySupervisor.props(client, entityConfiguration, repository), "entity1")
      val connection2 = service.valueEntity.expectConnection()
      connection2.expect(init("service", "entity1"))
      recreatedEntity ! EntityCommand(entityId = "test", name = "command2", emptyCommand)
      connection2.expect(command(1, "entity1", "command2"))
      val reply1 = ProtoAny("reply", ByteString.copyFromUtf8("reply1"))
      connection2.send(reply(1, reply1))
      expectMsg(UserFunctionReply(clientActionReply(messagePayload(reply1))))

      // passivate
      recreatedEntity ! ValueEntity.Stop
      connection2.expectClosed()
    //expectTerminated(recreatedEntity) // should be expected!
    }

  }
}
