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
import akka.testkit.TestProbe
import com.google.protobuf.ByteString
import com.google.protobuf.any.{Any => ProtoAny}
import io.cloudstate.protocol.entity.{ClientAction, Command, Failure, Reply}
import io.cloudstate.protocol.event_sourced._
import io.cloudstate.proxy.ConcurrencyEnforcer
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}
import io.cloudstate.proxy.eventsourced.EventSourcedEntity
import io.prometheus.client.CollectorRegistry
import scala.concurrent.duration._

class EventSourcedInstrumentationSpec extends AbstractTelemetrySpec {

  "EventSourcedInstrumentation" should {

    "record event-sourced entity metrics" in withTestRegistry(
      """
      | # use in-memory journal for testing
      | cloudstate.proxy.journal-enabled = true
      | akka.persistence {
      |   journal.plugin = "akka.persistence.journal.inmem"
      |   snapshot-store.plugin = inmem-snapshot-store
      | }
      | inmem-snapshot-store.class = "io.cloudstate.proxy.eventsourced.InMemSnapshotStore"
      """
    ) { testKit =>
      import testKit._
      import PrometheusEventSourcedInstrumentation.MetricName._
      import PrometheusEventSourcedInstrumentation.MetricLabel._

      // simulate user function interaction with event-sourced entity to validate instrumentation

      implicit val registry: CollectorRegistry = CloudstateTelemetry(system).prometheusRegistry

      implicit val replyTo: ActorRef = testActor

      val userFunction = TestProbe()

      val statsCollector = TestProbe() // ignored

      val concurrencyEnforcer = system.actorOf(
        ConcurrencyEnforcer.props(
          ConcurrencyEnforcer.ConcurrencyEnforcerSettings(
            concurrency = 1,
            actionTimeout = 10.seconds,
            cleanupPeriod = 5.seconds
          ),
          statsCollector.ref
        ),
        "concurrency-enforcer"
      )

      val entity = system.actorOf(
        EventSourcedEntity.props(
          EventSourcedEntity.Configuration(
            serviceName = "service",
            userFunctionName = "test",
            passivationTimeout = 30.seconds,
            sendQueueSize = 100
          ),
          entityId = "entity",
          userFunction.ref,
          concurrencyEnforcer,
          statsCollector.ref
        ),
        "test-entity"
      )

      watch(entity)

      // init with empty snapshot

      userFunction.expectMsg(
        EventSourcedStreamIn(
          EventSourcedStreamIn.Message.Init(
            EventSourcedInit(
              serviceName = "service",
              entityId = "entity",
              snapshot = None
            )
          )
        )
      )

      metricValue(ActivatedEntitiesTotal, EntityName -> "test") shouldBe 1
      metricValue(RecoveryTimeSeconds + "_count", EntityName -> "test") shouldBe 1
      metricValue(RecoveryTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      // first command

      entity ! EntityCommand(entityId = "test", name = "command")

      userFunction.expectMsg(
        EventSourcedStreamIn(
          EventSourcedStreamIn.Message.Command(
            Command(
              entityId = "entity",
              id = 1,
              name = "command",
              payload = None
            )
          )
        )
      )

      metricValue(ReceivedCommandsTotal, EntityName -> "test") shouldBe 1

      // second command (will be stashed)

      entity ! EntityCommand(entityId = "test", name = "command")

      eventually(timeout(5.seconds), interval(100.millis)) {
        metricValue(ReceivedCommandsTotal, EntityName -> "test") shouldBe 2
        metricValue(StashedCommandsTotal, EntityName -> "test") shouldBe 1
      }

      // send first reply

      val replyPayload1 = ProtoAny("reply", ByteString.copyFromUtf8("reply1"))
      val reply1 = ClientAction(ClientAction.Action.Reply(Reply(payload = Some(replyPayload1))))

      val event1 = ProtoAny("event", ByteString.copyFromUtf8("event1"))
      val event2 = ProtoAny("event", ByteString.copyFromUtf8("event2"))
      val event3 = ProtoAny("event", ByteString.copyFromUtf8("event3"))
      val snapshot1 = ProtoAny("snapshot", ByteString.copyFromUtf8("snapshot1"))

      entity ! EventSourcedStreamOut(
        EventSourcedStreamOut.Message.Reply(
          EventSourcedReply(
            commandId = 1,
            clientAction = Some(reply1),
            events = Seq(event1, event2, event3),
            snapshot = Some(snapshot1)
          )
        )
      )

      expectMsg(UserFunctionReply(Some(reply1)))

      metricValue(CommandProcessingTimeSeconds + "_count", EntityName -> "test") shouldBe 1
      metricValue(CommandProcessingTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      val expectedPersistedBytes1 = Seq(event1, event2, event3).map(_.serializedSize).sum

      metricValue(PersistedEventsTotal, EntityName -> "test") shouldBe 3
      metricValue(PersistedEventBytesTotal, EntityName -> "test") shouldBe expectedPersistedBytes1
      metricValue(PersistTimeSeconds + "_count", EntityName -> "test") shouldBe 1
      metricValue(PersistTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      metricValue(PersistedSnapshotsTotal, EntityName -> "test") shouldBe 1
      metricValue(PersistedSnapshotBytesTotal, EntityName -> "test") shouldBe snapshot1.serializedSize

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

      userFunction.expectMsg(
        EventSourcedStreamIn(
          EventSourcedStreamIn.Message.Command(
            Command(
              entityId = "entity",
              id = 2,
              name = "command",
              payload = None
            )
          )
        )
      )

      // send second reply

      val replyPayload2 = ProtoAny("reply", ByteString.copyFromUtf8("reply2"))
      val reply2 = ClientAction(ClientAction.Action.Reply(Reply(payload = Some(replyPayload2))))

      val event4 = ProtoAny("event", ByteString.copyFromUtf8("event4"))
      val event5 = ProtoAny("event", ByteString.copyFromUtf8("event5"))

      entity ! EventSourcedStreamOut(
        EventSourcedStreamOut.Message.Reply(
          EventSourcedReply(
            commandId = 2,
            clientAction = Some(reply2),
            events = Seq(event4, event5),
            snapshot = None
          )
        )
      )

      expectMsg(UserFunctionReply(Some(reply2)))

      metricValue(CommandProcessingTimeSeconds + "_count", EntityName -> "test") shouldBe 2
      metricValue(CommandProcessingTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      val expectedPersistedBytes2 = expectedPersistedBytes1 + Seq(event4, event5).map(_.serializedSize).sum

      metricValue(PersistedEventsTotal, EntityName -> "test") shouldBe 5
      metricValue(PersistedEventBytesTotal, EntityName -> "test") shouldBe expectedPersistedBytes2
      metricValue(PersistTimeSeconds + "_count", EntityName -> "test") shouldBe 2
      metricValue(PersistTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      metricValue(PersistedSnapshotsTotal, EntityName -> "test") shouldBe 1
      metricValue(PersistedSnapshotBytesTotal, EntityName -> "test") shouldBe snapshot1.serializedSize

      eventually(timeout(5.seconds), interval(100.millis)) {
        metricValue(CompletedCommandsTotal, EntityName -> "test") shouldBe 2
        metricValue(CommandTotalTimeSeconds + "_count", EntityName -> "test") shouldBe 2
        metricValue(CommandTotalTimeSeconds + "_sum", EntityName -> "test") should be > 0.0
      }

      // passivate the entity

      entity ! EventSourcedEntity.Stop

      expectTerminated(entity)

      metricValue(PassivatedEntitiesTotal, EntityName -> "test") shouldBe 1
      metricValue(EntityActiveTimeSeconds + "_count", EntityName -> "test") shouldBe 1
      metricValue(EntityActiveTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      // reactivate the entity

      val reactivatedEntity = system.actorOf(
        EventSourcedEntity.props(
          EventSourcedEntity.Configuration(
            serviceName = "service",
            userFunctionName = "test",
            passivationTimeout = 30.seconds,
            sendQueueSize = 100
          ),
          entityId = "entity",
          userFunction.ref,
          concurrencyEnforcer,
          statsCollector.ref
        ),
        "test-entity-reactivated"
      )

      watch(reactivatedEntity)

      // init with snapshot and events

      userFunction.expectMsg(
        EventSourcedStreamIn(
          EventSourcedStreamIn.Message.Init(
            EventSourcedInit(
              serviceName = "service",
              entityId = "entity",
              snapshot = Some(EventSourcedSnapshot(snapshotSequence = 3, snapshot = Some(snapshot1)))
            )
          )
        )
      )

      metricValue(LoadedSnapshotsTotal, EntityName -> "test") shouldBe 1
      metricValue(LoadedSnapshotBytesTotal, EntityName -> "test") shouldBe snapshot1.serializedSize

      userFunction.expectMsg(
        EventSourcedStreamIn(
          EventSourcedStreamIn.Message.Event(
            EventSourcedEvent(
              sequence = 4,
              payload = Some(event4)
            )
          )
        )
      )

      userFunction.expectMsg(
        EventSourcedStreamIn(
          EventSourcedStreamIn.Message.Event(
            EventSourcedEvent(
              sequence = 5,
              payload = Some(event5)
            )
          )
        )
      )

      val expectedLoadedBytes = Seq(event4, event5).map(_.serializedSize).sum

      metricValue(LoadedEventsTotal, EntityName -> "test") shouldBe 2
      metricValue(LoadedEventBytesTotal, EntityName -> "test") shouldBe expectedLoadedBytes

      eventually(timeout(5.seconds), interval(100.millis)) {
        metricValue(ActivatedEntitiesTotal, EntityName -> "test") shouldBe 2
        metricValue(RecoveryTimeSeconds + "_count", EntityName -> "test") shouldBe 2
        metricValue(RecoveryTimeSeconds + "_sum", EntityName -> "test") should be > 0.0
      }

      // send a command that fails

      reactivatedEntity ! EntityCommand(entityId = "test", name = "command")

      userFunction.expectMsg(
        EventSourcedStreamIn(
          EventSourcedStreamIn.Message.Command(
            Command(
              entityId = "entity",
              id = 1,
              name = "command",
              payload = None
            )
          )
        )
      )

      metricValue(ReceivedCommandsTotal, EntityName -> "test") shouldBe 3

      reactivatedEntity ! EventSourcedStreamOut(
        EventSourcedStreamOut.Message.Failure(Failure(commandId = 1, description = "failure"))
      )

      expectMsg(UserFunctionReply(Some(ClientAction(ClientAction.Action.Failure(Failure(description = "failure"))))))

      metricValue(FailedCommandsTotal, EntityName -> "test") shouldBe 1

      metricValue(CommandProcessingTimeSeconds + "_count", EntityName -> "test") shouldBe 3
      metricValue(CommandProcessingTimeSeconds + "_sum", EntityName -> "test") should be > 0.0

      eventually(timeout(5.seconds), interval(100.millis)) {
        metricValue(CompletedCommandsTotal, EntityName -> "test") shouldBe 3
        metricValue(CommandTotalTimeSeconds + "_count", EntityName -> "test") shouldBe 3
        metricValue(CommandTotalTimeSeconds + "_sum", EntityName -> "test") should be > 0.0
      }

      // create unexpected termination for entity failure

      reactivatedEntity ! EventSourcedEntity.StreamClosed

      expectTerminated(reactivatedEntity)

      metricValue(FailedEntitiesTotal, EntityName -> "test") shouldBe 1
    }

  }
}
