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
import io.cloudstate.protocol.entity.{EntityPassivationStrategy, TimeoutPassivationStrategy}
import io.cloudstate.protocol.value_entity.ValueEntity
import io.cloudstate.tck.model.valueentity.valueentity._
import io.cloudstate.testkit.valueentity.ValueEntityMessages._
import io.grpc.StatusRuntimeException
import scala.concurrent.duration._

trait EntityTCK extends TCKSpec {

  object EntityTCKModel {
    val Protocol: String = ValueEntity.name
    val Service: String = ValueEntityTckModel.name
    val ServiceTwo: String = ValueEntityTwo.name
    val ServiceConfigured: String = ValueEntityConfigured.name

    var entityId: Int = 0

    def nextEntityId(): String = { entityId += 1; s"entity-$entityId" }

    def valueEntityTest(test: String => Any): Unit =
      testFor(ValueEntityTckModel, ValueEntityTwo)(test(nextEntityId()))

    def valueEntityConfiguredTest(test: String => Any): Unit =
      testFor(ValueEntityConfigured)(test(nextEntityId()))

    def updateState(value: String): RequestAction =
      RequestAction(RequestAction.Action.Update(Update(value)))

    def updateStates(values: String*): Seq[RequestAction] =
      values.map(updateState)

    def deleteState(): RequestAction =
      RequestAction(RequestAction.Action.Delete(Delete()))

    def updateAndDeleteActions(values: String*): Seq[RequestAction] =
      values.map(updateState) :+ deleteState()

    def deleteBetweenUpdateActions(first: String, second: String): Seq[RequestAction] =
      Seq(updateState(first), deleteState(), updateState(second))

    def forwardTo(id: String): RequestAction =
      RequestAction(RequestAction.Action.Forward(Forward(id)))

    def sideEffectTo(id: String, synchronous: Boolean = false): RequestAction =
      RequestAction(RequestAction.Action.Effect(Effect(id, synchronous)))

    def sideEffectsTo(ids: String*): Seq[RequestAction] =
      ids.map(id => sideEffectTo(id))

    def failWith(message: String): RequestAction =
      RequestAction(RequestAction.Action.Fail(Fail(message)))

    def persisted(value: String): ScalaPbAny =
      protobufAny(Persisted(value))

    def update(value: String): Effects =
      Effects.empty.withUpdateAction(persisted(value))

    def delete(): Effects =
      Effects.empty.withDeleteAction()

    def sideEffects(ids: String*): Effects =
      createSideEffects(synchronous = false, ids)

    def synchronousSideEffects(ids: String*): Effects =
      createSideEffects(synchronous = true, ids)

    def createSideEffects(synchronous: Boolean, ids: Seq[String]): Effects =
      ids.foldLeft(Effects.empty) { case (e, id) => e.withSideEffect(ServiceTwo, "Call", Request(id), synchronous) }
  }

  def verifyEntityModel(): Unit = {
    import EntityTCKModel._

    "verify entity discovery" in testFor(ValueEntityTckModel, ValueEntityTwo) {
      discoveredServices must (contain(Service) and contain(ServiceTwo))
      entity(EntityTCKModel.Service).value.entityType mustBe EntityTCKModel.Protocol
      entity(EntityTCKModel.ServiceTwo).value.entityType mustBe EntityTCKModel.Protocol
      entity(EntityTCKModel.Service).value.persistenceId mustBe "value-entity-tck-model"
      entity(EntityTCKModel.ServiceTwo).value.persistenceId mustBe "value-entity-tck-model-two"
    }

    "verify configured entity" in testFor(ValueEntityConfigured) {
      discoveredServices must contain(ServiceConfigured)
      entity(EntityTCKModel.ServiceConfigured).value.entityType mustBe EntityTCKModel.Protocol
      entity(EntityTCKModel.ServiceConfigured).value.persistenceId mustBe "value-entity-configured"
      entity(EntityTCKModel.ServiceConfigured).value.passivationStrategy mustBe Some(
        EntityPassivationStrategy(EntityPassivationStrategy.Strategy.Timeout(TimeoutPassivationStrategy(100)))
      )
    }

    "verify initial empty state" in valueEntityTest { id =>
      protocol.valueEntity
        .connect()
        .send(init(ValueEntityTckModel.name, id))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, Response()))
        .passivate()
    }

    "verify update state" in valueEntityTest { id =>
      protocol.valueEntity
        .connect()
        .send(init(ValueEntityTckModel.name, id))
        .send(command(1, id, "Process", Request(id, updateStates("A"))))
        .expect(reply(1, Response("A"), update("A")))
        .send(command(2, id, "Process", Request(id)))
        .expect(reply(2, Response("A")))
        .passivate()
    }

    "verify delete state" in valueEntityTest { id =>
      protocol.valueEntity
        .connect()
        .send(init(ValueEntityTckModel.name, id))
        .send(command(1, id, "Process", Request(id, updateStates("A"))))
        .expect(reply(1, Response("A"), update("A")))
        .send(command(2, id, "Process", Request(id, Seq(deleteState()))))
        .expect(reply(2, Response(), delete()))
        .send(command(3, id, "Process", Request(id)))
        .expect(reply(3, Response()))
        .passivate()
    }

    "verify sub invocations with multiple update states" in valueEntityTest { id =>
      protocol.valueEntity
        .connect()
        .send(init(ValueEntityTckModel.name, id))
        .send(command(1, id, "Process", Request(id, updateStates("A", "B", "C"))))
        .expect(reply(1, Response("C"), update("C")))
        .send(command(2, id, "Process", Request(id)))
        .expect(reply(2, Response("C")))
        .passivate()
    }

    "verify sub invocations with multiple update states and delete states" in valueEntityTest { id =>
      protocol.valueEntity
        .connect()
        .send(init(ValueEntityTckModel.name, id))
        .send(command(1, id, "Process", Request(id, updateAndDeleteActions("A", "B"))))
        .expect(reply(1, Response(), delete()))
        .send(command(2, id, "Process", Request(id)))
        .expect(reply(2, Response()))
        .passivate()
    }

    "verify sub invocations with update, delete and update states" in valueEntityTest { id =>
      protocol.valueEntity
        .connect()
        .send(init(ValueEntityTckModel.name, id))
        .send(command(1, id, "Process", Request(id, deleteBetweenUpdateActions("A", "B"))))
        .expect(reply(1, Response("B"), update("B")))
        .send(command(2, id, "Process", Request(id)))
        .expect(reply(2, Response("B")))
        .passivate()
    }

    "verify rehydration after passivation" in valueEntityTest { id =>
      protocol.valueEntity
        .connect()
        .send(init(ValueEntityTckModel.name, id))
        .send(command(1, id, "Process", Request(id, updateStates("A"))))
        .expect(reply(1, Response("A"), update("A")))
        .send(command(2, id, "Process", Request(id, updateStates("B"))))
        .expect(reply(2, Response("B"), update("B")))
        .send(command(3, id, "Process", Request(id, updateStates("C"))))
        .expect(reply(3, Response("C"), update("C")))
        .send(command(4, id, "Process", Request(id, updateStates("D"))))
        .expect(reply(4, Response("D"), update("D")))
        .passivate()
      protocol.valueEntity
        .connect()
        .send(init(ValueEntityTckModel.name, id, state(persisted("D"))))
        .send(command(1, id, "Process", Request(id, updateStates("E"))))
        .expect(reply(1, Response("E"), update("E")))
        .send(command(2, id, "Process", Request(id)))
        .expect(reply(2, Response("E")))
        .passivate()
    }

    "verify reply with multiple side effects" in valueEntityTest { id =>
      protocol.valueEntity
        .connect()
        .send(init(ValueEntityTckModel.name, id))
        .send(command(1, id, "Process", Request(id, sideEffectsTo("1", "2", "3"))))
        .expect(reply(1, Response(), sideEffects("1", "2", "3")))
        .passivate()
    }

    "verify reply with side effect to second service" in valueEntityTest { id =>
      protocol.valueEntity
        .connect()
        .send(init(ValueEntityTckModel.name, id))
        .send(command(1, id, "Process", Request(id, updateStates("X"))))
        .expect(reply(1, Response("X"), update("X")))
        .send(command(2, id, "Process", Request(id, Seq(sideEffectTo(id)))))
        .expect(reply(2, Response("X"), sideEffects(id)))
        .send(command(3, id, "Process", Request(id)))
        .expect(reply(3, Response("X")))
        .passivate()
    }

    "verify reply with multiple side effects and state" in valueEntityTest { id =>
      val actions = updateStates("A", "B", "C", "D", "E") ++ sideEffectsTo("1", "2", "3")
      val effects = sideEffects("1", "2", "3").withUpdateAction(persisted("E"))
      protocol.valueEntity
        .connect()
        .send(init(ValueEntityTckModel.name, id))
        .send(command(1, id, "Process", Request(id, actions)))
        .expect(reply(1, Response("E"), effects))
        .passivate()
    }

    "verify synchronous side effect to second service" in valueEntityTest { id =>
      protocol.valueEntity
        .connect()
        .send(init(ValueEntityTckModel.name, id))
        .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(id, synchronous = true)))))
        .expect(reply(1, Response(), synchronousSideEffects(id)))
        .passivate()
    }

    "verify forward to second service" in valueEntityTest { id =>
      protocol.valueEntity
        .connect()
        .send(init(ValueEntityTckModel.name, id))
        .send(command(1, id, "Process", Request(id, updateStates("X"))))
        .expect(reply(1, Response("X"), update("X")))
        .send(command(2, id, "Process", Request(id, Seq(forwardTo(id)))))
        .expect(forward(2, ServiceTwo, "Call", Request(id)))
        .send(command(3, id, "Process", Request(id)))
        .expect(reply(3, Response("X")))
        .passivate()
    }

    "verify forward with updated state to second service" in valueEntityTest { id =>
      protocol.valueEntity
        .connect()
        .send(init(ValueEntityTckModel.name, id))
        .send(command(1, id, "Process", Request(id, Seq(updateState("A"), forwardTo(id)))))
        .expect(forward(1, ServiceTwo, "Call", Request(id), update("A")))
        .passivate()
    }

    "verify forward and side effect to second service" in valueEntityTest { id =>
      protocol.valueEntity
        .connect()
        .send(init(ValueEntityTckModel.name, id))
        .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(id), forwardTo(id)))))
        .expect(forward(1, ServiceTwo, "Call", Request(id), sideEffects(id)))
        .passivate()
    }

    "verify failure action" in valueEntityTest { id =>
      protocol.valueEntity
        .connect()
        .send(init(ValueEntityTckModel.name, id))
        .send(command(1, id, "Process", Request(id, Seq(failWith("expected failure")))))
        .expect(actionFailure(1, "expected failure"))
        .passivate()
    }

    "verify connection after failure action" in valueEntityTest { id =>
      protocol.valueEntity
        .connect()
        .send(init(ValueEntityTckModel.name, id))
        .send(command(1, id, "Process", Request(id, Seq(updateState("A")))))
        .expect(reply(1, Response("A"), update("A")))
        .send(command(2, id, "Process", Request(id, Seq(failWith("expected failure")))))
        .expect(actionFailure(2, "expected failure"))
        .send(command(3, id, "Process", Request(id)))
        .expect(reply(3, Response("A")))
        .passivate()
    }

    "verify failure action do not allow side effects" in valueEntityTest { id =>
      protocol.valueEntity
        .connect()
        .send(init(ValueEntityTckModel.name, id))
        .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(id), failWith("expected failure")))))
        .expect(actionFailure(1, "expected failure"))
        .passivate()
    }
  }

  object EntityTCKProxy {
    val tckModelClient: ValueEntityTckModelClient = ValueEntityTckModelClient(client.settings)(client.system)
    val configuredClient: ValueEntityConfiguredClient = ValueEntityConfiguredClient(client.settings)(client.system)

    def terminate(): Unit = {
      tckModelClient.close()
      configuredClient.close()
    }
  }

  override def afterAll(): Unit =
    try EntityTCKProxy.terminate()
    finally super.afterAll()

  def verifyEntityProxy(): Unit = {
    import EntityTCKModel._
    import EntityTCKProxy._

    "verify state changes" in valueEntityTest { id =>
      tckModelClient.process(Request(id)).futureValue mustBe Response()
      val connection = interceptor
        .expectEntityConnection()
        .expectIncoming(init(Service, id))
        .expectIncoming(command(1, id, "Process", Request(id)))
        .expectOutgoing(reply(1, Response()))

      tckModelClient.process(Request(id, updateStates("one"))).futureValue mustBe Response("one")
      connection
        .expectIncoming(command(2, id, "Process", Request(id, updateStates("one"))))
        .expectOutgoing(reply(2, Response("one"), update("one")))

      tckModelClient.process(Request(id, updateStates("two"))).futureValue mustBe Response("two")
      connection
        .expectIncoming(command(3, id, "Process", Request(id, updateStates("two"))))
        .expectOutgoing(reply(3, Response("two"), update("two")))

      tckModelClient.process(Request(id)).futureValue mustBe Response("two")
      connection
        .expectIncoming(command(4, id, "Process", Request(id)))
        .expectOutgoing(reply(4, Response("two")))

      tckModelClient.process(Request(id, Seq(deleteState()))).futureValue mustBe Response()
      connection
        .expectIncoming(command(5, id, "Process", Request(id, Seq(deleteState()))))
        .expectOutgoing(reply(5, Response(), delete()))

      tckModelClient.process(Request(id)).futureValue mustBe Response()
      connection
        .expectIncoming(command(6, id, "Process", Request(id)))
        .expectOutgoing(reply(6, Response()))

      tckModelClient.process(Request(id, updateStates("foo"))).futureValue mustBe Response("foo")
      connection
        .expectIncoming(command(7, id, "Process", Request(id, updateStates("foo"))))
        .expectOutgoing(reply(7, Response("foo"), update("foo")))

      tckModelClient.process(Request(id, Seq(deleteState()))).futureValue mustBe Response()
      connection
        .expectIncoming(command(8, id, "Process", Request(id, Seq(deleteState()))))
        .expectOutgoing(reply(8, Response(), delete()))
    }

    "verify forwards and side effects" in valueEntityTest { id =>
      tckModelClient.process(Request(id, updateStates("one"))).futureValue mustBe Response("one")
      val connection = interceptor
        .expectEntityConnection()
        .expectIncoming(init(Service, id))
        .expectIncoming(command(1, id, "Process", Request(id, updateStates("one"))))
        .expectOutgoing(reply(1, Response("one"), update("one")))

      tckModelClient.process(Request(id, Seq(forwardTo(id)))).futureValue mustBe Response()
      connection
        .expectIncoming(command(2, id, "Process", Request(id, Seq(forwardTo(id)))))
        .expectOutgoing(forward(2, ServiceTwo, "Call", Request(id)))
      val connection2 = interceptor
        .expectEntityConnection()
        .expectIncoming(init(ServiceTwo, id))
        .expectIncoming(command(1, id, "Call", Request(id)))
        .expectOutgoing(reply(1, Response()))

      tckModelClient.process(Request(id, Seq(sideEffectTo(id)))).futureValue mustBe Response("one")
      connection
        .expectIncoming(command(3, id, "Process", Request(id, Seq(sideEffectTo(id)))))
        .expectOutgoing(reply(3, Response("one"), sideEffects(id)))
      connection2
        .expectIncoming(command(2, id, "Call", Request(id)))
        .expectOutgoing(reply(2, Response()))

      tckModelClient.process(Request(id)).futureValue mustBe Response("one")
      connection
        .expectIncoming(command(4, id, "Process", Request(id)))
        .expectOutgoing(reply(4, Response("one")))
    }

    "verify failures" in valueEntityTest { id =>
      tckModelClient.process(Request(id, updateStates("one"))).futureValue mustBe Response("one")
      val connection = interceptor
        .expectEntityConnection()
        .expectIncoming(init(Service, id))
        .expectIncoming(command(1, id, "Process", Request(id, updateStates("one"))))
        .expectOutgoing(reply(1, Response("one"), update("one")))

      val failed = tckModelClient.process(Request(id, Seq(failWith("expected failure")))).failed.futureValue
      failed mustBe a[StatusRuntimeException]
      failed.asInstanceOf[StatusRuntimeException].getStatus.getDescription mustBe "expected failure"
      connection
        .expectIncoming(command(2, id, "Process", Request(id, Seq(failWith("expected failure")))))
        .expectOutgoing(actionFailure(2, "expected failure"))

      tckModelClient.process(Request(id)).futureValue mustBe Response("one")
      connection
        .expectIncoming(command(3, id, "Process", Request(id)))
        .expectOutgoing(reply(3, Response("one")))
    }

    "verify HTTP API" in valueEntityTest { id =>
      client.http.request(s"tck/model/entity/$id", "{}").futureValue mustBe """{"message":""}"""
      val connection = interceptor
        .expectEntityConnection()
        .expectIncoming(init(Service, id))
        .expectIncoming(command(1, id, "Process", Request(id)))
        .expectOutgoing(reply(1, Response()))

      client.http
        .request("cloudstate.tck.model.valueentity.ValueEntityTwo/Call", s"""{"id": "$id"}""")
        .futureValue mustBe """{"message":""}"""
      interceptor
        .expectEntityConnection()
        .expectIncoming(init(ServiceTwo, id))
        .expectIncoming(command(1, id, "Call", Request(id)))
        .expectOutgoing(reply(1, Response()))

      client.http
        .request(s"tck/model/entity/$id", """{"actions": [{"update": {"value": "one"}}]}""")
        .futureValue mustBe """{"message":"one"}"""
      connection
        .expectIncoming(command(2, id, "Process", Request(id, updateStates("one"))))
        .expectOutgoing(reply(2, Response("one"), update("one")))

      client.http
        .requestToError(s"tck/model/entity/$id", """{"actions": [{"fail": {"message": "expected failure"}}]}""")
        .futureValue mustBe "expected failure"
      connection
        .expectIncoming(command(3, id, "Process", Request(id, Seq(failWith("expected failure")))))
        .expectOutgoing(actionFailure(3, "expected failure"))

      client.http
        .request(s"tck/model/entity/$id", """{"actions": [{"update": {"value": "two"}}]}""")
        .futureValue mustBe """{"message":"two"}"""
      connection
        .expectIncoming(command(4, id, "Process", Request(id, updateStates("two"))))
        .expectOutgoing(reply(4, Response("two"), update("two")))

      client.http.request(s"tck/model/entity/$id", "{}").futureValue mustBe """{"message":"two"}"""
      connection
        .expectIncoming(command(5, id, "Process", Request(id)))
        .expectOutgoing(reply(5, Response("two")))
    }

    "verify passivation timeout" in valueEntityConfiguredTest { id =>
      configuredClient.call(Request(id))
      interceptor
        .expectEntityConnection()
        .expectIncoming(init(ServiceConfigured, id))
        .expectIncoming(command(1, id, "Call", Request(id)))
        .expectOutgoing(reply(1, Response()))
        .expectClosed(2.seconds) // check passivation (with expected timeout of 100 millis)
    }
  }
}
