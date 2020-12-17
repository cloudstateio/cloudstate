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

import com.google.protobuf.any.{Any => ScalaPbAny}
import io.cloudstate.protocol.event_sourced.EventSourced
import io.cloudstate.tck.model.eventsourced._
import io.cloudstate.testkit.eventsourced.EventSourcedMessages._

trait EventSourcedEntityTCK extends TCKSpec {

  object EventSourcedEntityTCKModel {
    val Protocol: String = EventSourced.name
    val Service: String = EventSourcedTckModel.name
    val ServiceTwo: String = EventSourcedTwo.name

    var entityId: Int = 0

    def nextEntityId(): String = { entityId += 1; s"entity:$entityId" }

    def eventSourcedTest(test: String => Any): Unit =
      testFor(EventSourcedTckModel, EventSourcedTwo)(test(nextEntityId()))

    def emitEvent(value: String): RequestAction =
      RequestAction(RequestAction.Action.Emit(Emit(value)))

    def emitEvents(values: String*): Seq[RequestAction] =
      values.map(emitEvent)

    def forwardTo(id: String): RequestAction =
      RequestAction(RequestAction.Action.Forward(Forward(id)))

    def sideEffectTo(id: String, synchronous: Boolean = false): RequestAction =
      RequestAction(RequestAction.Action.Effect(Effect(id, synchronous)))

    def sideEffectsTo(ids: String*): Seq[RequestAction] =
      ids.map(id => sideEffectTo(id, synchronous = false))

    def failWith(message: String): RequestAction =
      RequestAction(RequestAction.Action.Fail(Fail(message)))

    def persisted(value: String): ScalaPbAny =
      protobufAny(Persisted(value))

    def events(values: String*): Effects =
      Effects(events = values.map(persisted))

    def snapshotAndEvents(snapshotValue: String, eventValues: String*): Effects =
      events(eventValues: _*).withSnapshot(persisted(snapshotValue))

    def sideEffects(ids: String*): Effects =
      createSideEffects(synchronous = false, ids)

    def synchronousSideEffects(ids: String*): Effects =
      createSideEffects(synchronous = true, ids)

    def createSideEffects(synchronous: Boolean, ids: Seq[String]): Effects =
      ids.foldLeft(Effects.empty) { case (e, id) => e.withSideEffect(ServiceTwo, "Call", Request(id), synchronous) }
  }

  def verifyEventSourcedEntityModel(): Unit = {
    import EventSourcedEntityTCKModel._

    "verify event sourced entity discovery" in testFor(EventSourcedTckModel, EventSourcedTwo) {
      discoveredServices must (contain("EventSourcedTckModel") and contain("EventSourcedTwo"))
      entity(EventSourcedEntityTCKModel.Service).value.entityType mustBe EventSourcedEntityTCKModel.Protocol
      entity(EventSourcedEntityTCKModel.ServiceTwo).value.entityType mustBe EventSourcedEntityTCKModel.Protocol
      entity(EventSourcedEntityTCKModel.Service).value.persistenceId mustBe "event-sourced-tck-model"
    }

    "verify initial empty state" in eventSourcedTest { id =>
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, Response()))
        .passivate()
    }

    "verify single emitted event" in eventSourcedTest { id =>
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id))
        .send(command(1, id, "Process", Request(id, emitEvents("A"))))
        .expect(reply(1, Response("A"), events("A")))
        .send(command(2, id, "Process", Request(id)))
        .expect(reply(2, Response("A")))
        .passivate()
    }

    "verify multiple emitted events" in eventSourcedTest { id =>
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id))
        .send(command(1, id, "Process", Request(id, emitEvents("A", "B", "C"))))
        .expect(reply(1, Response("ABC"), events("A", "B", "C")))
        .send(command(2, id, "Process", Request(id)))
        .expect(reply(2, Response("ABC")))
        .passivate()
    }

    "verify multiple emitted events and snapshots" in eventSourcedTest { id =>
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id))
        .send(command(1, id, "Process", Request(id, emitEvents("A"))))
        .expect(reply(1, Response("A"), events("A")))
        .send(command(2, id, "Process", Request(id, emitEvents("B"))))
        .expect(reply(2, Response("AB"), events("B")))
        .send(command(3, id, "Process", Request(id, emitEvents("C"))))
        .expect(reply(3, Response("ABC"), events("C")))
        .send(command(4, id, "Process", Request(id, emitEvents("D"))))
        .expect(reply(4, Response("ABCD"), events("D")))
        .send(command(5, id, "Process", Request(id, emitEvents("E"))))
        .expect(reply(5, Response("ABCDE"), snapshotAndEvents("ABCDE", "E")))
        .send(command(6, id, "Process", Request(id, emitEvents("F", "G", "H"))))
        .expect(reply(6, Response("ABCDEFGH"), events("F", "G", "H")))
        .send(command(7, id, "Process", Request(id, emitEvents("I", "J"))))
        .expect(reply(7, Response("ABCDEFGHIJ"), snapshotAndEvents("ABCDEFGHIJ", "I", "J")))
        .send(command(8, id, "Process", Request(id, emitEvents("K"))))
        .expect(reply(8, Response("ABCDEFGHIJK"), events("K")))
        .send(command(9, id, "Process", Request(id)))
        .expect(reply(9, Response("ABCDEFGHIJK")))
        .passivate()
    }

    "verify initial snapshot" in eventSourcedTest { id =>
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id, snapshot(5, persisted("ABCDE"))))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, Response("ABCDE")))
        .passivate()
    }

    "verify initial snapshot and events" in eventSourcedTest { id =>
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id, snapshot(5, persisted("ABCDE"))))
        .send(event(6, persisted("F")))
        .send(event(7, persisted("G")))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, Response("ABCDEFG")))
        .passivate()
    }

    "verify rehydration after passivation" in eventSourcedTest { id =>
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id))
        .send(command(1, id, "Process", Request(id, emitEvents("A", "B", "C"))))
        .expect(reply(1, Response("ABC"), events("A", "B", "C")))
        .send(command(2, id, "Process", Request(id, emitEvents("D", "E"))))
        .expect(reply(2, Response("ABCDE"), snapshotAndEvents("ABCDE", "D", "E")))
        .send(command(3, id, "Process", Request(id, emitEvents("F"))))
        .expect(reply(3, Response("ABCDEF"), events("F")))
        .send(command(4, id, "Process", Request(id, emitEvents("G"))))
        .expect(reply(4, Response("ABCDEFG"), events("G")))
        .passivate()
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id, snapshot(5, persisted("ABCDE"))))
        .send(event(6, persisted("F")))
        .send(event(7, persisted("G")))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, Response("ABCDEFG")))
        .passivate()
    }

    "verify forward to second service" in eventSourcedTest { id =>
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id))
        .send(command(1, id, "Process", Request(id, Seq(forwardTo(id)))))
        .expect(forward(1, EventSourcedTwo.name, "Call", Request(id)))
        .passivate()
    }

    "verify forward with emitted events" in eventSourcedTest { id =>
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id))
        .send(command(1, id, "Process", Request(id, Seq(emitEvent("A"), forwardTo(id)))))
        .expect(forward(1, EventSourcedTwo.name, "Call", Request(id), events("A")))
        .passivate()
    }

    "verify forward with emitted events and snapshot" in eventSourcedTest { id =>
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id))
        .send(command(1, id, "Process", Request(id, emitEvents("A", "B", "C"))))
        .expect(reply(1, Response("ABC"), events("A", "B", "C")))
        .send(command(2, id, "Process", Request(id, Seq(emitEvent("D"), emitEvent("E"), forwardTo(id)))))
        .expect(forward(2, EventSourcedTwo.name, "Call", Request(id), snapshotAndEvents("ABCDE", "D", "E")))
        .passivate()
    }

    "verify reply with side effect to second service" in eventSourcedTest { id =>
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id))
        .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(id)))))
        .expect(reply(1, Response(), sideEffects(id)))
        .passivate()
    }

    "verify synchronous side effect to second service" in eventSourcedTest { id =>
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id))
        .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(id, synchronous = true)))))
        .expect(reply(1, Response(), synchronousSideEffects(id)))
        .passivate()
    }

    "verify forward and side effect to second service" in eventSourcedTest { id =>
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id))
        .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(id), forwardTo(id)))))
        .expect(forward(1, ServiceTwo, "Call", Request(id), sideEffects(id)))
        .passivate()
    }

    "verify reply with multiple side effects" in eventSourcedTest { id =>
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id))
        .send(command(1, id, "Process", Request(id, sideEffectsTo("1", "2", "3"))))
        .expect(reply(1, Response(), sideEffects("1", "2", "3")))
        .passivate()
    }

    "verify reply with multiple side effects, events, and snapshot" in eventSourcedTest { id =>
      val actions = emitEvents("A", "B", "C", "D", "E") ++ sideEffectsTo("1", "2", "3")
      val effects = snapshotAndEvents("ABCDE", "A", "B", "C", "D", "E") ++ sideEffects("1", "2", "3")
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id))
        .send(command(1, id, "Process", Request(id, actions)))
        .expect(reply(1, Response("ABCDE"), effects))
        .passivate()
    }

    "verify failure action" in eventSourcedTest { id =>
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id))
        .send(command(1, id, "Process", Request(id, Seq(failWith("expected failure")))))
        .expect(actionFailure(1, "expected failure"))
        .passivate()
    }

    "verify connection after failure action" in eventSourcedTest { id =>
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id))
        .send(command(1, id, "Process", Request(id, Seq(emitEvent("A")))))
        .expect(reply(1, Response("A"), events("A")))
        .send(command(2, id, "Process", Request(id, Seq(failWith("expected failure")))))
        .expect(actionFailure(2, "expected failure"))
        .send(command(3, id, "Process", Request(id)))
        .expect(reply(3, Response("A")))
        .passivate()
    }

    "verify failure actions do not retain emitted events, by requesting entity restart" in eventSourcedTest { id =>
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id))
        .send(command(1, id, "Process", Request(id, emitEvents("A", "B", "C"))))
        .expect(reply(1, Response("ABC"), events("A", "B", "C")))
        .send(command(2, id, "Process", Request(id, Seq(emitEvent("4"), emitEvent("5"), failWith("failure 1")))))
        .expect(actionFailure(2, "failure 1", restart = true))
        .passivate()
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id))
        .send(event(1, persisted("A")))
        .send(event(2, persisted("B")))
        .send(event(3, persisted("C")))
        .send(command(1, id, "Process", Request(id, emitEvents("D"))))
        .expect(reply(1, Response("ABCD"), events("D")))
        .send(command(2, id, "Process", Request(id, Seq(emitEvent("6"), failWith("failure 2"), emitEvent("7")))))
        .expect(actionFailure(2, "failure 2", restart = true))
        .passivate()
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id))
        .send(event(1, persisted("A")))
        .send(event(2, persisted("B")))
        .send(event(3, persisted("C")))
        .send(event(4, persisted("D")))
        .send(command(1, id, "Process", Request(id, emitEvents("E"))))
        .expect(reply(1, Response("ABCDE"), snapshotAndEvents("ABCDE", "E")))
        .passivate()
    }

    "verify failure actions do not allow side effects" in eventSourcedTest { id =>
      protocol.eventSourced
        .connect()
        .send(init(EventSourcedTckModel.name, id))
        .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(id), failWith("expected failure")))))
        .expect(actionFailure(1, "expected failure"))
        .passivate()
    }
  }
}
