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

package io.cloudstate.proxy.telemetry

import akka.actor.ActorRef
import akka.grpc.GrpcClientSettings
import akka.testkit.TestEvent.Mute
import akka.testkit.EventFilter
import com.google.protobuf.ByteString
import com.google.protobuf.any.{Any => ProtoAny}
import io.cloudstate.protocol.entity.{ClientAction, Failure}
import io.cloudstate.protocol.event_sourced._
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}
import io.cloudstate.proxy.eventsourced.{EventSourcedEntity, EventSourcedEntitySupervisor}
import io.cloudstate.testkit.TestService
import io.cloudstate.testkit.eventsourced.EventSourcedMessages
import io.prometheus.client.CollectorRegistry
import scala.concurrent.duration._

class EventSourcedInstrumentationSpec extends AbstractTelemetrySpec {

  "EventSourcedInstrumentation" should {

    "record event-sourced entity metrics" in withTestRegistry(
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

      // simulate user function interaction with event-sourced entity to validate instrumentation

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

      val entity = system.actorOf(
        EventSourcedEntitySupervisor.props(client, entityConfiguration),
        "entity"
      )

      watch(entity)

      val emptyCommand = Some(protobufAny(EmptyJavaMessage))

      // init with empty snapshot

      val connection = service.eventSourced.expectConnection()

      connection.expect(init("service", "entity"))

      metricValue(ActivatedEntitiesTotal, EntityName -> "test") shouldBe 1
      metricValue(RecoveryTimeSeconds + "_count", EntityName -> "test") shouldBe 1
      metricValue(RecoveryTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      // first command

      entity ! EntityCommand(entityId = "test", name = "command1", emptyCommand)

      connection.expect(command(1, "entity", "command1"))

      metricValue(ReceivedCommandsTotal, EntityName -> "test") shouldBe 1

      // second command (will be stashed)

      entity ! EntityCommand(entityId = "test", name = "command2", emptyCommand)

      eventually(timeout(5.seconds), interval(100.millis)) {
        metricValue(ReceivedCommandsTotal, EntityName -> "test") shouldBe 2
        metricValue(StashedCommandsTotal, EntityName -> "test") shouldBe 1
      }

      // send first reply

      val reply1 = ProtoAny("reply", ByteString.copyFromUtf8("reply1"))
      val event1 = ProtoAny("event", ByteString.copyFromUtf8("event1"))
      val event2 = ProtoAny("event", ByteString.copyFromUtf8("event2"))
      val event3 = ProtoAny("event", ByteString.copyFromUtf8("event3"))
      val snapshot1 = ProtoAny("snapshot", ByteString.copyFromUtf8("snapshot1"))

      connection.send(reply(1, reply1, persist(event1, event2, event3).withSnapshot(snapshot1)))

      expectMsg(UserFunctionReply(clientActionReply(messagePayload(reply1))))

      metricValue(CommandProcessingTimeSeconds + "_count", EntityName -> "test") shouldBe 1
      metricValue(CommandProcessingTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      val expectedPersistedBytes1 = Seq(event1, event2, event3).map(protobufAny).map(_.serializedSize).sum

      metricValue(PersistedEventsTotal, EntityName -> "test") shouldBe 3
      metricValue(PersistedEventBytesTotal, EntityName -> "test") shouldBe expectedPersistedBytes1
      metricValue(PersistTimeSeconds + "_count", EntityName -> "test") shouldBe 1
      metricValue(PersistTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      metricValue(PersistedSnapshotsTotal, EntityName -> "test") shouldBe 1
      metricValue(PersistedSnapshotBytesTotal, EntityName -> "test") shouldBe protobufAny(snapshot1).serializedSize

      eventually(timeout(5.seconds), interval(100.millis)) {
        metricValue(CompletedCommandsTotal, EntityName -> "test") shouldBe 1
        metricValue(CommandTotalTimeSeconds + "_count", EntityName -> "test") shouldBe 1
        metricValue(CommandTotalTimeSeconds + "_sum", EntityName -> "test") should be > 0.0
      }

      // second command is unstashed

      eventually(timeout(5.seconds), interval(100.millis)) {
        metricValue(UnstashedCommandsTotal, EntityName -> "test") shouldBe 1
        metricValue(CommandStashTimeSeconds + "_count", EntityName -> "test") shouldBe 1
        metricValue(CommandStashTimeSeconds + "_sum", EntityName -> "test") should be > 0.0
      }

      connection.expect(command(2, "entity", "command2"))

      // send second reply

      val reply2 = ProtoAny("reply", ByteString.copyFromUtf8("reply2"))
      val event4 = ProtoAny("event", ByteString.copyFromUtf8("event4"))
      val event5 = ProtoAny("event", ByteString.copyFromUtf8("event5"))

      connection.send(reply(2, reply2, persist(event4, event5)))

      expectMsg(UserFunctionReply(clientActionReply(messagePayload(reply2))))

      metricValue(CommandProcessingTimeSeconds + "_count", EntityName -> "test") shouldBe 2
      metricValue(CommandProcessingTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      val expectedPersistedBytes2 = Seq(event4, event5).map(protobufAny).map(_.serializedSize).sum
      val totalExpectedPersistedBytes = expectedPersistedBytes1 + expectedPersistedBytes2

      metricValue(PersistedEventsTotal, EntityName -> "test") shouldBe 5
      metricValue(PersistedEventBytesTotal, EntityName -> "test") shouldBe totalExpectedPersistedBytes
      metricValue(PersistTimeSeconds + "_count", EntityName -> "test") shouldBe 2
      metricValue(PersistTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      metricValue(PersistedSnapshotsTotal, EntityName -> "test") shouldBe 1
      metricValue(PersistedSnapshotBytesTotal, EntityName -> "test") shouldBe protobufAny(snapshot1).serializedSize

      eventually(timeout(5.seconds), interval(100.millis)) {
        metricValue(CompletedCommandsTotal, EntityName -> "test") shouldBe 2
        metricValue(CommandTotalTimeSeconds + "_count", EntityName -> "test") shouldBe 2
        metricValue(CommandTotalTimeSeconds + "_sum", EntityName -> "test") should be > 0.0
      }

      // passivate the entity

      entity ! EventSourcedEntity.Stop

      connection.expectClosed()

      expectTerminated(entity)

      metricValue(PassivatedEntitiesTotal, EntityName -> "test") shouldBe 1
      metricValue(EntityActiveTimeSeconds + "_count", EntityName -> "test") shouldBe 1
      metricValue(EntityActiveTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      // reactivate the entity

      val reactivatedEntity = system.actorOf(
        EventSourcedEntitySupervisor.props(client, entityConfiguration),
        "entity"
      )

      watch(reactivatedEntity)

      // init with snapshot and events

      val connection2 = service.eventSourced.expectConnection()

      connection2.expect(init("service", "entity", snapshot(sequence = 3, snapshot1)))

      metricValue(LoadedSnapshotsTotal, EntityName -> "test") shouldBe 1
      metricValue(LoadedSnapshotBytesTotal, EntityName -> "test") shouldBe protobufAny(snapshot1).serializedSize

      connection2.expect(event(4, event4))
      connection2.expect(event(5, event5))

      val expectedLoadedBytes = Seq(event4, event5).map(protobufAny).map(_.serializedSize).sum

      metricValue(LoadedEventsTotal, EntityName -> "test") shouldBe 2
      metricValue(LoadedEventBytesTotal, EntityName -> "test") shouldBe expectedLoadedBytes

      eventually(timeout(5.seconds), interval(100.millis)) {
        metricValue(ActivatedEntitiesTotal, EntityName -> "test") shouldBe 2
        metricValue(RecoveryTimeSeconds + "_count", EntityName -> "test") shouldBe 2
        metricValue(RecoveryTimeSeconds + "_sum", EntityName -> "test") should be > 0.0
      }

      // send a command that fails

      reactivatedEntity ! EntityCommand(entityId = "test", name = "command3", emptyCommand)

      connection2.expect(command(1, "entity", "command3"))

      metricValue(ReceivedCommandsTotal, EntityName -> "test") shouldBe 3

      connection2.send(actionFailure(1, "failure"))

      expectMsg(UserFunctionReply(Some(ClientAction(ClientAction.Action.Failure(Failure(1, "failure"))))))

      metricValue(FailedCommandsTotal, EntityName -> "test") shouldBe 1

      metricValue(CommandProcessingTimeSeconds + "_count", EntityName -> "test") shouldBe 3
      metricValue(CommandProcessingTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      eventually(timeout(5.seconds), interval(100.millis)) {
        metricValue(CompletedCommandsTotal, EntityName -> "test") shouldBe 3
        metricValue(CommandTotalTimeSeconds + "_count", EntityName -> "test") shouldBe 3
        metricValue(CommandTotalTimeSeconds + "_sum", EntityName -> "test") should be > 0.0
      }

      // create unexpected entity failure

      reactivatedEntity ! EntityCommand(entityId = "test", name = "command4", emptyCommand)

      connection2.expect(command(2, "entity", "command4"))

      metricValue(ReceivedCommandsTotal, EntityName -> "test") shouldBe 4

      EventFilter.error("Unexpected entity failure - boom", occurrences = 1).intercept {
        connection2.send(failure(2, "boom"))
      }

      expectMsg(UserFunctionReply(clientActionFailure("Unexpected entity failure")))

      connection2.expectClosed()

      expectTerminated(reactivatedEntity)

      metricValue(FailedCommandsTotal, EntityName -> "test") shouldBe 2

      metricValue(CommandProcessingTimeSeconds + "_count", EntityName -> "test") shouldBe 4
      metricValue(CommandProcessingTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      metricValue(CompletedCommandsTotal, EntityName -> "test") shouldBe 4
      metricValue(CommandTotalTimeSeconds + "_count", EntityName -> "test") shouldBe 4
      metricValue(CommandTotalTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      metricValue(FailedEntitiesTotal, EntityName -> "test") shouldBe 1

      metricValue(PassivatedEntitiesTotal, EntityName -> "test") shouldBe 2
      metricValue(EntityActiveTimeSeconds + "_count", EntityName -> "test") shouldBe 2
      metricValue(EntityActiveTimeSeconds + "_sum", EntityName -> "test") should be > 0.0
    }

  }
}
