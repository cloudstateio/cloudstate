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

package io.cloudstate.proxy.eventsourced

import akka.actor.{Actor, ActorRef}
import akka.grpc.GrpcClientSettings
import akka.testkit.TestEvent.Mute
import akka.testkit.{EventFilter, TestActorRef, TestBarrier}
import com.google.protobuf.ByteString
import com.google.protobuf.any.{Any => ProtoAny}
import io.cloudstate.protocol.event_sourced._
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}
import io.cloudstate.proxy.telemetry.{AbstractTelemetrySpec, CloudstateTelemetry, PrometheusEventSourcedInstrumentation}
import io.cloudstate.testkit.TestService
import io.cloudstate.testkit.eventsourced.EventSourcedMessages
import io.prometheus.client.CollectorRegistry
import scala.concurrent.duration._

class EventSourcedRestartSpec extends AbstractTelemetrySpec {

  "EventSourcedEntity" should {

    "restart entity on restart failures" in withTestKit(
      """
      | include "test-in-memory"
      | akka {
      |   loglevel = ERROR
      |   loggers = ["akka.testkit.TestEventListener"]
      |   remote.artery.canonical.port = 0
      |   remote.artery.bind.port = ""
      | }
      """
    ) { testKit =>
      import testKit._
      import EventSourcedMessages._
      import PrometheusEventSourcedInstrumentation.MetricName._
      import PrometheusEventSourcedInstrumentation.MetricLabel._

      // silence any dead letters or unhandled messages during shutdown (when using test event listener)
      system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*received dead letter.*")))
      system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*unhandled message.*")))

      // use the metrics to wait for particular internal conditions (like commands stashed)
      implicit val registry: CollectorRegistry = CloudstateTelemetry(system).prometheusRegistry

      implicit val replyTo: ActorRef = testActor

      val service = TestService()
      val client = EventSourcedClient(GrpcClientSettings.connectToServiceAt("localhost", service.port).withTls(false))

      val entityConfiguration = EventSourcedEntity.Configuration(
        serviceName = "service",
        userFunctionName = "test",
        passivationTimeout = 30.seconds,
        sendQueueSize = 100
      )

      val entity = watch(system.actorOf(EventSourcedEntitySupervisor.props(client, entityConfiguration), "entity"))

      val emptyCommand = Some(protobufAny(EmptyJavaMessage))

      // init with empty snapshot

      val connection = service.eventSourced.expectConnection()

      connection.expect(init("service", "entity"))
      metricValue(ActivatedEntitiesTotal, EntityName -> "test") shouldBe 1

      // first command

      // add a test actor and barrier for the first command reply, so we always send to the mailbox during processing
      val replyBarrier = TestBarrier(2)
      val blockReply = TestActorRef(new Actor {
        def receive: Receive = {
          case message =>
            replyTo forward message
            replyBarrier.await(30.seconds)
        }
      })

      entity.tell(EntityCommand(entityId = "test", name = "command1", emptyCommand), blockReply)

      connection.expect(command(1, "entity", "command1"))
      metricValue(ReceivedCommandsTotal, EntityName -> "test") shouldBe 1

      // second and third commands will be stashed

      entity ! EntityCommand(entityId = "test", name = "command2", emptyCommand)
      entity ! EntityCommand(entityId = "test", name = "command3", emptyCommand)

      eventually(timeout(5.seconds), interval(100.millis)) {
        metricValue(ReceivedCommandsTotal, EntityName -> "test") shouldBe 3
        metricValue(StashedCommandsTotal, EntityName -> "test") shouldBe 2
      }

      // first command fails with restart

      connection.send(actionFailure(1, "restart please", restart = true))

      expectMsg(UserFunctionReply(clientActionFailure(1, "restart please", restart = true)))

      // send fourth command while still processing first reply, so that both mailbox and command stash have messages
      entity ! EntityCommand(entityId = "test", name = "command4", emptyCommand)

      EventFilter.error("Restarting entity [entity] after failure: restart please", occurrences = 1).intercept {
        replyBarrier.await(5.seconds)
      }

      // reconnection after restart, with new init

      connection.expectClosed()

      eventually(timeout(5.seconds), interval(100.millis)) {
        metricValue(FailedCommandsTotal, EntityName -> "test") shouldBe 1
        metricValue(CommandProcessingTimeSeconds + "_count", EntityName -> "test") shouldBe 1
        metricValue(CompletedCommandsTotal, EntityName -> "test") shouldBe 1
        metricValue(CommandTotalTimeSeconds + "_count", EntityName -> "test") shouldBe 1
        metricValue(UnstashedCommandsTotal, EntityName -> "test") shouldBe 2
        metricValue(CommandStashTimeSeconds + "_count", EntityName -> "test") shouldBe 2
        metricValue(PassivatedEntitiesTotal, EntityName -> "test") shouldBe 1
      }

      val connection2 = service.eventSourced.expectConnection()

      connection2.expect(init("service", "entity"))
      metricValue(ActivatedEntitiesTotal, EntityName -> "test") shouldBe 2

      // second command is processed first after restart

      connection2.expect(command(1, "entity", "command2"))

      eventually(timeout(5.seconds), interval(100.millis)) {
        // NOTE: the second and third commands are recounted as received, the third recounted as stashed again
        metricValue(ReceivedCommandsTotal, EntityName -> "test") shouldBe 6
        metricValue(StashedCommandsTotal, EntityName -> "test") shouldBe 4
      }

      // finish processing second, third, and fourth commands

      val reply1 = ProtoAny("reply", ByteString.copyFromUtf8("reply1"))
      val reply2 = ProtoAny("reply", ByteString.copyFromUtf8("reply2"))
      val reply3 = ProtoAny("reply", ByteString.copyFromUtf8("reply3"))

      connection2.send(reply(1, reply1))
      expectMsg(UserFunctionReply(clientActionReply(messagePayload(reply1))))

      connection2.expect(command(2, "entity", "command3"))
      connection2.send(reply(2, reply2))
      expectMsg(UserFunctionReply(clientActionReply(messagePayload(reply2))))

      connection2.expect(command(3, "entity", "command4"))
      connection2.send(reply(3, reply3))
      expectMsg(UserFunctionReply(clientActionReply(messagePayload(reply3))))

      // passivate the entity

      entity ! EventSourcedEntity.Stop
      connection2.expectClosed()
      expectTerminated(entity)

      // NOTE: received commands metric has counted second and third commands twice
      // TODO: should we have another counter for commands reprocessed after restart failures?
      metricValue(ActivatedEntitiesTotal, EntityName -> "test") shouldBe 2
      metricValue(ReceivedCommandsTotal, EntityName -> "test") shouldBe 6
      metricValue(StashedCommandsTotal, EntityName -> "test") shouldBe 4
      metricValue(UnstashedCommandsTotal, EntityName -> "test") shouldBe 4
      metricValue(CommandStashTimeSeconds + "_count", EntityName -> "test") shouldBe 4
      metricValue(FailedCommandsTotal, EntityName -> "test") shouldBe 1
      metricValue(CommandProcessingTimeSeconds + "_count", EntityName -> "test") shouldBe 4
      metricValue(CompletedCommandsTotal, EntityName -> "test") shouldBe 4
      metricValue(CommandTotalTimeSeconds + "_count", EntityName -> "test") shouldBe 4
      metricValue(PassivatedEntitiesTotal, EntityName -> "test") shouldBe 2
      metricValue(EntityActiveTimeSeconds + "_count", EntityName -> "test") shouldBe 2
    }

  }
}
