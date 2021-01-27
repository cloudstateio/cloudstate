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

import akka.actor.ActorSystem
import akka.stream.testkit.scaladsl.TestSink
import io.cloudstate.protocol.crdt._
import io.cloudstate.protocol.entity.{EntityPassivationStrategy, TimeoutPassivationStrategy}
import io.cloudstate.tck.model.crdt._
import io.cloudstate.testkit.crdt.CrdtMessages._
import io.grpc.StatusRuntimeException

import scala.concurrent.duration._

trait CrdtEntityTCK extends TCKSpec {

  object CrdtEntityTCKModel {
    val Protocol: String = Crdt.name
    val Service: String = CrdtTckModel.name
    val ServiceTwo: String = CrdtTwo.name
    val ServiceConfigured: String = CrdtConfigured.name

    var entityId: Int = 0

    def nextEntityId(crdtType: String): String = {
      entityId += 1; s"$crdtType-$entityId"
    }

    def crdtTest(crdtType: String)(test: String => Any): Unit =
      testFor(CrdtTckModel, CrdtTwo)(test(nextEntityId(crdtType)))

    def crdtConfiguredTest(test: String => Any): Unit =
      testFor(CrdtConfigured)(test(nextEntityId("Configured")))

    def requestUpdate(update: Update): RequestAction =
      RequestAction(RequestAction.Action.Update(update))

    val requestDelete: RequestAction =
      RequestAction(RequestAction.Action.Delete(Delete()))

    val deleteCrdt: Effects =
      Effects(stateAction = crdtDelete)

    def forwardTo(id: String): RequestAction =
      RequestAction(RequestAction.Action.Forward(Forward(id)))

    def sideEffectTo(id: String, synchronous: Boolean = false): RequestAction =
      RequestAction(RequestAction.Action.Effect(Effect(id, synchronous)))

    def sideEffectsTo(ids: String*): Seq[RequestAction] =
      ids.map(id => sideEffectTo(id))

    def sideEffects(ids: String*): Effects =
      createSideEffects(synchronous = false, ids)

    def synchronousSideEffects(ids: String*): Effects =
      createSideEffects(synchronous = true, ids)

    def createSideEffects(synchronous: Boolean, ids: Seq[String]): Effects =
      ids.foldLeft(Effects.empty) { case (e, id) => e.withSideEffect(ServiceTwo, "Call", Request(id), synchronous) }

    def forwarded(id: Long, entityId: String, effects: Effects = Effects.empty): CrdtStreamOut.Message =
      forward(id, ServiceTwo, "Call", Request(entityId), effects)

    def failWith(message: String): RequestAction =
      RequestAction(RequestAction.Action.Fail(Fail(message)))

    // Sort sequences in received Deltas (using ScalaPB Lens support) for comparing easily
    object Delta {
      def sorted(out: CrdtStreamOut): CrdtStreamOut =
        out.update(_.reply.stateAction.update.modify(Delta.sort))

      def sort(delta: CrdtDelta): CrdtDelta =
        if (delta.delta.isGset)
          delta.update(
            _.gset.added.modify(_.sortBy(readPrimitiveString))
          )
        else if (delta.delta.isOrset)
          delta.update(
            _.orset.update(
              _.removed.modify(_.sortBy(readPrimitiveString)),
              _.added.modify(_.sortBy(readPrimitiveString))
            )
          )
        else if (delta.delta.isOrmap)
          delta.update(
            _.ormap.update(
              _.removed.modify(_.sortBy(readPrimitiveString)),
              _.updated.modify(_.map(_.update(_.delta.modify(Delta.sort))).sortBy(_.key.map(readPrimitiveString))),
              _.added.modify(_.map(_.update(_.delta.modify(Delta.sort))).sortBy(_.key.map(readPrimitiveString)))
            )
          )
        else delta
    }

    object GCounter {
      def state(value: Long): Response =
        Response(Some(State(State.Value.Gcounter(GCounterValue(value)))))

      def incrementBy(increments: Long*): Seq[RequestAction] =
        increments.map(increment => requestUpdate(updateWith(increment)))

      def updateWith(increment: Long): Update =
        Update(Update.Update.Gcounter(GCounterUpdate(increment)))

      def update(value: Long): Effects =
        Effects(stateAction = crdtUpdate(delta(value)))

      def delta(value: Long): CrdtDelta.Delta.Gcounter = deltaGCounter(value)

      def test(run: String => Any): Unit = crdtTest("GCounter")(run)
    }

    object PNCounter {
      def state(value: Long): Response =
        Response(Some(State(State.Value.Pncounter(PNCounterValue(value)))))

      def changeBy(changes: Long*): Seq[RequestAction] =
        changes.map(change => requestUpdate(updateWith(change)))

      def updateWith(change: Long): Update =
        Update(Update.Update.Pncounter(PNCounterUpdate(change)))

      def update(value: Long): Effects =
        Effects(stateAction = crdtUpdate(delta(value)))

      def delta(value: Long): CrdtDelta.Delta.Pncounter = deltaPNCounter(value)

      def test(run: String => Any): Unit = crdtTest("PNCounter")(run)
    }

    object GSet {
      def state(elements: String*): Response =
        Response(Some(State(State.Value.Gset(GSetValue(elements)))))

      def add(elements: String*): Seq[RequestAction] =
        elements.map(element => requestUpdate(updateWith(element)))

      def updateWith(element: String): Update =
        Update(Update.Update.Gset(GSetUpdate(element)))

      def update(added: String*): Effects =
        Effects(stateAction = crdtUpdate(delta(added: _*)))

      def delta(added: String*): CrdtDelta.Delta.Gset = deltaGSet(added.map(primitiveString))

      def test(run: String => Any): Unit = crdtTest("GSet")(run)
    }

    object ORSet {
      def state(elements: String*): Response =
        Response(Some(State(State.Value.Orset(ORSetValue(elements)))))

      def add(elements: String*): Seq[RequestAction] =
        elements.map(element => action(ORSetUpdate.Action.Add(element)))

      def remove(elements: String*): Seq[RequestAction] =
        elements.map(element => action(ORSetUpdate.Action.Remove(element)))

      def clear(value: Boolean = true): Seq[RequestAction] =
        Seq(action(ORSetUpdate.Action.Clear(value)))

      def action(updateAction: ORSetUpdate.Action): RequestAction =
        requestUpdate(Update(Update.Update.Orset(ORSetUpdate(updateAction))))

      def update(cleared: Boolean = false, removed: Seq[String] = Seq.empty, added: Seq[String] = Seq.empty): Effects =
        Effects(stateAction = crdtUpdate(delta(cleared, removed, added)))

      def delta(cleared: Boolean = false,
                removed: Seq[String] = Seq.empty,
                added: Seq[String] = Seq.empty): CrdtDelta.Delta.Orset =
        CrdtDelta.Delta.Orset(ORSetDelta(cleared, removed.map(primitiveString), added.map(primitiveString)))

      def test(run: String => Any): Unit = crdtTest("ORSet")(run)
    }

    object LWWRegister {
      val DefaultClock: LWWRegisterClockType = LWWRegisterClockType.DEFAULT
      val ReverseClock: LWWRegisterClockType = LWWRegisterClockType.REVERSE
      val CustomClock: LWWRegisterClockType = LWWRegisterClockType.CUSTOM
      val CustomAutoIncClock: LWWRegisterClockType = LWWRegisterClockType.CUSTOM_AUTO_INCREMENT

      def state(value: String): Response =
        Response(Some(State(State.Value.Lwwregister(LWWRegisterValue(value)))))

      def setTo(value: String,
                clockType: LWWRegisterClockType = DefaultClock,
                customClockValue: Long = 0L): Seq[RequestAction] =
        updateWith(LWWRegisterUpdate(value, Some(LWWRegisterClock(clockType, customClockValue))))

      def updateWith(update: LWWRegisterUpdate): Seq[RequestAction] =
        Seq(requestUpdate(Update(Update.Update.Lwwregister(update))))

      def update(value: String, clock: CrdtClock = CrdtClock.DEFAULT, customClockValue: Long = 0L): Effects =
        Effects(stateAction = crdtUpdate(delta(value, clock, customClockValue)))

      def delta(value: String,
                clock: CrdtClock = CrdtClock.DEFAULT,
                customClockValue: Long = 0L): CrdtDelta.Delta.Lwwregister =
        deltaLWWRegister(Some(primitiveString(value)), clock, customClockValue)

      def test(run: String => Any): Unit = crdtTest("LWWRegister")(run)
    }

    object Flag {
      def state(value: Boolean): Response =
        Response(Some(State(State.Value.Flag(FlagValue(value)))))

      def enable(): Seq[RequestAction] =
        Seq(requestUpdate(Update(Update.Update.Flag(FlagUpdate()))))

      def update(value: Boolean): Effects =
        Effects(stateAction = crdtUpdate(delta(value)))

      def delta(value: Boolean): CrdtDelta.Delta.Flag = deltaFlag(value)

      def test(run: String => Any): Unit = crdtTest("Flag")(run)
    }

    object ORMap {
      def state(entries: (String, Response)*): Response =
        Response(
          Some(State(State.Value.Ormap(ORMapValue(entries.map {
            case (key, value) => ORMapEntryValue(key, value.state)
          }))))
        )

      def add(keys: String*): Seq[RequestAction] =
        keys.map(key => action(ORMapUpdate.Action.Add(key)))

      def updateWith(entries: (String, Seq[RequestAction])*): Seq[RequestAction] =
        entries flatMap {
          case (key, actions) =>
            actions map { a =>
              action(ORMapUpdate.Action.Update(ORMapEntryUpdate(key, Option(a.getUpdate))))
            }
        }

      def remove(keys: String*): Seq[RequestAction] =
        keys.map(key => action(ORMapUpdate.Action.Remove(key)))

      def clear(value: Boolean = true): Seq[RequestAction] =
        Seq(action(ORMapUpdate.Action.Clear(value)))

      def action(updateAction: ORMapUpdate.Action): RequestAction =
        requestUpdate(Update(Update.Update.Ormap(ORMapUpdate(updateAction))))

      def update(cleared: Boolean = false,
                 removed: Seq[String] = Seq.empty,
                 updated: Seq[(String, Effects)] = Seq.empty,
                 added: Seq[(String, Effects)] = Seq.empty): Effects =
        Effects(
          stateAction = crdtUpdate(
            delta(
              cleared,
              removed,
              updated map { case (key, effects) => key -> effects.stateAction.get.getUpdate.delta },
              added map { case (key, effects) => key -> effects.stateAction.get.getUpdate.delta }
            )
          )
        )

      def delta(cleared: Boolean = false,
                removed: Seq[String] = Seq.empty,
                updated: Seq[(String, CrdtDelta.Delta)] = Seq.empty,
                added: Seq[(String, CrdtDelta.Delta)] = Seq.empty): CrdtDelta.Delta.Ormap =
        CrdtDelta.Delta.Ormap(
          ORMapDelta(
            cleared,
            removed.map(primitiveString),
            updated map { case (key, delta) => ORMapEntryDelta(Some(primitiveString(key)), Some(CrdtDelta(delta))) },
            added map { case (key, delta) => ORMapEntryDelta(Some(primitiveString(key)), Some(CrdtDelta(delta))) }
          )
        )

      def test(run: String => Any): Unit = crdtTest("ORMap")(run)
    }

    object Vote {
      def state(selfVote: Boolean, votesFor: Int, totalVoters: Int): Response =
        Response(Some(State(State.Value.Vote(VoteValue(selfVote, votesFor, totalVoters)))))

      def self(vote: Boolean): Seq[RequestAction] =
        Seq(requestUpdate(updateWith(vote)))

      def updateWith(vote: Boolean): Update =
        Update(Update.Update.Vote(VoteUpdate(vote)))

      def update(selfVote: Boolean): Effects =
        Effects(stateAction = crdtUpdate(delta(selfVote)))

      def delta(selfVote: Boolean, votesFor: Int = 0, totalVoters: Int = 0): CrdtDelta.Delta.Vote =
        deltaVote(selfVote, votesFor, totalVoters)

      def test(run: String => Any): Unit = crdtTest("Vote")(run)
    }
  }

  def verifyCrdtEntityModel(): Unit = {
    import CrdtEntityTCKModel._

    "verify CRDT entity discovery" in testFor(CrdtTckModel, CrdtTwo) {
      discoveredServices must (contain(Service) and contain(ServiceTwo))
      entity(CrdtEntityTCKModel.Service).value.entityType mustBe CrdtEntityTCKModel.Protocol
      entity(CrdtEntityTCKModel.ServiceTwo).value.entityType mustBe CrdtEntityTCKModel.Protocol
    }

    "verify CRDT configured entity" in testFor(CrdtConfigured) {
      discoveredServices must contain(ServiceConfigured)
      entity(CrdtEntityTCKModel.ServiceConfigured).value.entityType mustBe CrdtEntityTCKModel.Protocol
      entity(CrdtEntityTCKModel.ServiceConfigured).value.passivationStrategy mustBe Some(
        EntityPassivationStrategy(EntityPassivationStrategy.Strategy.Timeout(TimeoutPassivationStrategy(100)))
      )
    }

    // GCounter tests

    "verify GCounter initial empty state" in GCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, GCounter.state(0)))
        .passivate()
    }

    "verify GCounter state changes" in GCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, GCounter.incrementBy(42))))
        .expect(reply(1, GCounter.state(42), GCounter.update(42)))
        .send(command(2, id, "Process", Request(id, GCounter.incrementBy(1, 2, 3))))
        .expect(reply(2, GCounter.state(48), GCounter.update(6)))
        .passivate()
    }

    "verify GCounter initial delta in init" in GCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id, GCounter.delta(42)))
        .send(command(1, id, "Process", Request(id, GCounter.incrementBy(123))))
        .expect(reply(1, GCounter.state(165), GCounter.update(123)))
        .passivate()
    }

    "verify GCounter initial empty state with replicated initial delta" in GCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(delta(GCounter.delta(42)))
        .send(command(1, id, "Process", Request(id, GCounter.incrementBy(123))))
        .expect(reply(1, GCounter.state(165), GCounter.update(123)))
        .passivate()
    }

    "verify GCounter mix of local and replicated state changes" in GCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, GCounter.incrementBy(1))))
        .expect(reply(1, GCounter.state(1), GCounter.update(1)))
        .send(delta(GCounter.delta(2)))
        .send(delta(GCounter.delta(3)))
        .send(command(2, id, "Process", Request(id)))
        .expect(reply(2, GCounter.state(6)))
        .send(command(3, id, "Process", Request(id, GCounter.incrementBy(4))))
        .expect(reply(3, GCounter.state(10), GCounter.update(4)))
        .send(delta(GCounter.delta(5)))
        .send(command(4, id, "Process", Request(id, GCounter.incrementBy(6))))
        .expect(reply(4, GCounter.state(21), GCounter.update(6)))
        .passivate()
    }

    "verify GCounter rehydration after passivation" in GCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, GCounter.incrementBy(1))))
        .expect(reply(1, GCounter.state(1), GCounter.update(1)))
        .send(delta(GCounter.delta(2)))
        .send(command(2, id, "Process", Request(id)))
        .expect(reply(2, GCounter.state(3)))
        .send(command(3, id, "Process", Request(id, GCounter.incrementBy(3))))
        .expect(reply(3, GCounter.state(6), GCounter.update(3)))
        .passivate()
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id, GCounter.delta(6)))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, GCounter.state(6)))
        .send(delta(GCounter.delta(4)))
        .send(command(2, id, "Process", Request(id, GCounter.incrementBy(5))))
        .expect(reply(2, GCounter.state(15), GCounter.update(5)))
        .passivate()
    }

    "verify GCounter delete action received from entity" in GCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, GCounter.incrementBy(42))))
        .expect(reply(1, GCounter.state(42), GCounter.update(42)))
        .send(command(2, id, "Process", Request(id, Seq(requestDelete))))
        .expect(reply(2, GCounter.state(42), deleteCrdt))
        .passivate()
    }

    "verify GCounter delete action sent to entity" in GCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(delete)
        .passivate()
    }

    // PNCounter tests

    "verify PNCounter initial empty state" in PNCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, PNCounter.state(0)))
        .passivate()
    }

    "verify PNCounter state changes" in PNCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, PNCounter.changeBy(+1, -2, +3))))
        .expect(reply(1, PNCounter.state(+2), PNCounter.update(+2)))
        .send(command(2, id, "Process", Request(id, PNCounter.changeBy(-4, +5, -6))))
        .expect(reply(2, PNCounter.state(-3), PNCounter.update(-5)))
        .passivate()
    }

    "verify PNCounter initial delta in init" in PNCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id, PNCounter.delta(42)))
        .send(command(1, id, "Process", Request(id, PNCounter.changeBy(-123))))
        .expect(reply(1, PNCounter.state(-81), PNCounter.update(-123)))
        .passivate()
    }

    "verify PNCounter initial empty state with replicated initial delta" in PNCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(delta(PNCounter.delta(-42)))
        .send(command(1, id, "Process", Request(id, PNCounter.changeBy(+123))))
        .expect(reply(1, PNCounter.state(81), PNCounter.update(+123)))
        .passivate()
    }

    "verify PNCounter mix of local and replicated state changes" in PNCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, PNCounter.changeBy(+1))))
        .expect(reply(1, PNCounter.state(+1), PNCounter.update(+1)))
        .send(delta(PNCounter.delta(-2)))
        .send(delta(PNCounter.delta(+3)))
        .send(command(2, id, "Process", Request(id)))
        .expect(reply(2, PNCounter.state(+2)))
        .send(command(3, id, "Process", Request(id, PNCounter.changeBy(-4))))
        .expect(reply(3, PNCounter.state(-2), PNCounter.update(-4)))
        .send(delta(PNCounter.delta(+5)))
        .send(command(4, id, "Process", Request(id, PNCounter.changeBy(-6))))
        .expect(reply(4, PNCounter.state(-3), PNCounter.update(-6)))
        .passivate()
    }

    "verify PNCounter rehydration after passivation" in PNCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, PNCounter.changeBy(+1))))
        .expect(reply(1, PNCounter.state(+1), PNCounter.update(+1)))
        .send(delta(PNCounter.delta(-2)))
        .send(command(2, id, "Process", Request(id)))
        .expect(reply(2, PNCounter.state(-1)))
        .send(command(3, id, "Process", Request(id, PNCounter.changeBy(+3))))
        .expect(reply(3, PNCounter.state(+2), PNCounter.update(+3)))
        .passivate()
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id, PNCounter.delta(+2)))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, PNCounter.state(+2)))
        .send(delta(PNCounter.delta(-4)))
        .send(command(2, id, "Process", Request(id, PNCounter.changeBy(+5))))
        .expect(reply(2, PNCounter.state(+3), PNCounter.update(+5)))
        .passivate()
    }

    "verify PNCounter delete action received from entity" in PNCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, PNCounter.changeBy(+42))))
        .expect(reply(1, PNCounter.state(+42), PNCounter.update(+42)))
        .send(command(2, id, "Process", Request(id, Seq(requestDelete))))
        .expect(reply(2, PNCounter.state(+42), deleteCrdt))
        .passivate()
    }

    "verify PNCounter delete action sent to entity" in PNCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(delete)
        .passivate()
    }

    // GSet tests

    "verify GSet initial empty state" in GSet.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, GSet.state()))
        .passivate()
    }

    "verify GSet state changes" in GSet.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, GSet.add("a", "b"))))
        .expect(reply(1, GSet.state("a", "b"), GSet.update("a", "b")), Delta.sorted)
        .send(command(2, id, "Process", Request(id, GSet.add("b", "c"))))
        .expect(reply(2, GSet.state("a", "b", "c"), GSet.update("c")))
        .send(command(3, id, "Process", Request(id, GSet.add("c", "a"))))
        .expect(reply(3, GSet.state("a", "b", "c")))
        .passivate()
    }

    "verify GSet initial delta in init" in GSet.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id, GSet.delta("a", "b", "c")))
        .send(command(1, id, "Process", Request(id, GSet.add("c", "d", "e"))))
        .expect(reply(1, GSet.state("a", "b", "c", "d", "e"), GSet.update("d", "e")), Delta.sorted)
        .passivate()
    }

    "verify GSet initial empty state with replicated initial delta" in GSet.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(delta(GSet.delta("x", "y")))
        .send(command(1, id, "Process", Request(id, GSet.add("z"))))
        .expect(reply(1, GSet.state("x", "y", "z"), GSet.update("z")))
        .passivate()
    }

    "verify GSet mix of local and replicated state changes" in GSet.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, GSet.add("a"))))
        .expect(reply(1, GSet.state("a"), GSet.update("a")))
        .send(delta(GSet.delta("a")))
        .send(delta(GSet.delta("b")))
        .send(delta(GSet.delta("c")))
        .send(command(2, id, "Process", Request(id)))
        .expect(reply(2, GSet.state("a", "b", "c")))
        .send(command(3, id, "Process", Request(id, GSet.add("c", "d", "e"))))
        .expect(reply(3, GSet.state("a", "b", "c", "d", "e"), GSet.update("d", "e")), Delta.sorted)
        .send(delta(GSet.delta("f")))
        .send(command(4, id, "Process", Request(id, GSet.add("g", "d", "b"))))
        .expect(reply(4, GSet.state("a", "b", "c", "d", "e", "f", "g"), GSet.update("g")))
        .passivate()
    }

    "verify GSet rehydration after passivation" in GSet.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, GSet.add("a"))))
        .expect(reply(1, GSet.state("a"), GSet.update("a")))
        .send(delta(GSet.delta("b")))
        .send(command(2, id, "Process", Request(id)))
        .expect(reply(2, GSet.state("a", "b")))
        .send(command(3, id, "Process", Request(id, GSet.add("c"))))
        .expect(reply(3, GSet.state("a", "b", "c"), GSet.update("c")))
        .passivate()
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id, GSet.delta("a", "b", "c")))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, GSet.state("a", "b", "c")))
        .send(delta(GSet.delta("d")))
        .send(command(2, id, "Process", Request(id, GSet.add("e"))))
        .expect(reply(2, GSet.state("a", "b", "c", "d", "e"), GSet.update("e")))
        .passivate()
    }

    "verify GSet delete action received from entity" in GSet.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, GSet.add("x"))))
        .expect(reply(1, GSet.state("x"), GSet.update("x")))
        .send(command(2, id, "Process", Request(id, Seq(requestDelete))))
        .expect(reply(2, GSet.state("x"), deleteCrdt))
        .passivate()
    }

    "verify GSet delete action sent to entity" in GSet.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(delete)
        .passivate()
    }

    // ORSet tests

    "verify ORSet initial empty state" in ORSet.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, ORSet.state()))
        .passivate()
    }

    "verify ORSet state changes" in ORSet.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, ORSet.add("a", "b"))))
        .expect(reply(1, ORSet.state("a", "b"), ORSet.update(added = Seq("a", "b"))), Delta.sorted)
        .send(command(2, id, "Process", Request(id, ORSet.add("b", "c"))))
        .expect(reply(2, ORSet.state("a", "b", "c"), ORSet.update(added = Seq("c"))))
        .send(command(3, id, "Process", Request(id, ORSet.add("c", "a"))))
        .expect(reply(3, ORSet.state("a", "b", "c")))
        .send(command(4, id, "Process", Request(id, ORSet.remove("b", "d"))))
        .expect(reply(4, ORSet.state("a", "c"), ORSet.update(removed = Seq("b"))))
        .send(command(5, id, "Process", Request(id, ORSet.remove("c", "d") ++ ORSet.add("b", "c"))))
        .expect(reply(5, ORSet.state("a", "b", "c"), ORSet.update(added = Seq("b"))))
        .send(command(6, id, "Process", Request(id, ORSet.clear())))
        .expect(reply(6, ORSet.state(), ORSet.update(cleared = true)))
        .send(command(7, id, "Process", Request(id, ORSet.add("a") ++ ORSet.clear() ++ ORSet.add("x"))))
        .expect(reply(7, ORSet.state("x"), ORSet.update(cleared = true, added = Seq("x"))))
        .passivate()
    }

    "verify ORSet initial delta in init" in ORSet.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id, ORSet.delta(added = Seq("a", "b", "c"))))
        .send(command(1, id, "Process", Request(id, ORSet.remove("c") ++ ORSet.add("d"))))
        .expect(reply(1, ORSet.state("a", "b", "d"), ORSet.update(removed = Seq("c"), added = Seq("d"))))
        .passivate()
    }

    "verify ORSet initial empty state with replicated initial delta" in ORSet.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(delta(ORSet.delta(added = Seq("x", "y"))))
        .send(command(1, id, "Process", Request(id, ORSet.remove("x") ++ ORSet.add("z"))))
        .expect(reply(1, ORSet.state("y", "z"), ORSet.update(removed = Seq("x"), added = Seq("z"))))
        .passivate()
    }

    "verify ORSet mix of local and replicated state changes" in ORSet.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, ORSet.add("a"))))
        .expect(reply(1, ORSet.state("a"), ORSet.update(added = Seq("a"))))
        .send(delta(ORSet.delta(added = Seq("a", "b"))))
        .send(delta(ORSet.delta(added = Seq("c", "d"))))
        .send(delta(ORSet.delta(removed = Seq("b", "d"))))
        .send(command(2, id, "Process", Request(id)))
        .expect(reply(2, ORSet.state("a", "c")))
        .send(command(3, id, "Process", Request(id, ORSet.add("c", "d", "e"))))
        .expect(reply(3, ORSet.state("a", "c", "d", "e"), ORSet.update(added = Seq("d", "e"))), Delta.sorted)
        .send(delta(ORSet.delta(removed = Seq("a", "c"), added = Seq("f"))))
        .send(command(4, id, "Process", Request(id, ORSet.add("g") ++ ORSet.remove("a", "d"))))
        .expect(reply(4, ORSet.state("e", "f", "g"), ORSet.update(removed = Seq("d"), added = Seq("g"))))
        .send(delta(ORSet.delta(cleared = true, added = Seq("x", "y", "z"))))
        .send(command(5, id, "Process", Request(id, ORSet.add("q", "x") ++ ORSet.remove("z"))))
        .expect(reply(5, ORSet.state("q", "x", "y"), ORSet.update(removed = Seq("z"), added = Seq("q"))))
        .send(delta(ORSet.delta(removed = Seq("x", "y"), added = Seq("r", "p"))))
        .send(command(6, id, "Process", Request(id, ORSet.add("s"))))
        .expect(reply(6, ORSet.state("p", "q", "r", "s"), ORSet.update(added = Seq("s"))))
        .send(delta(ORSet.delta(added = Seq("a", "b", "c"))))
        .send(command(7, id, "Process", Request(id, ORSet.clear() ++ ORSet.add("abc"))))
        .expect(reply(7, ORSet.state("abc"), ORSet.update(cleared = true, added = Seq("abc"))))
        .passivate()
    }

    "verify ORSet rehydration after passivation" in ORSet.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, ORSet.add("a"))))
        .expect(reply(1, ORSet.state("a"), ORSet.update(added = Seq("a"))))
        .send(delta(ORSet.delta(removed = Seq("a"), added = Seq("b"))))
        .send(command(2, id, "Process", Request(id)))
        .expect(reply(2, ORSet.state("b")))
        .send(command(3, id, "Process", Request(id, ORSet.add("c"))))
        .expect(reply(3, ORSet.state("b", "c"), ORSet.update(added = Seq("c"))))
        .passivate()
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id, ORSet.delta(added = Seq("b", "c"))))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, ORSet.state("b", "c")))
        .send(delta(ORSet.delta(cleared = true, added = Seq("p", "q"))))
        .send(command(2, id, "Process", Request(id, ORSet.add("x", "y") ++ ORSet.remove("x"))))
        .expect(reply(2, ORSet.state("p", "q", "y"), ORSet.update(added = Seq("y"))))
        .passivate()
    }

    "verify ORSet delete action received from entity" in ORSet.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, ORSet.add("x"))))
        .expect(reply(1, ORSet.state("x"), ORSet.update(added = Seq("x"))))
        .send(command(2, id, "Process", Request(id, Seq(requestDelete))))
        .expect(reply(2, ORSet.state("x"), deleteCrdt))
        .passivate()
    }

    "verify ORSet delete action sent to entity" in ORSet.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(delete)
        .passivate()
    }

    // LWWRegister tests

    "verify LWWRegister initial empty state" in LWWRegister.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, LWWRegister.state(""), LWWRegister.update("")))
        .passivate()
    }

    "verify LWWRegister state changes" in LWWRegister.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, LWWRegister.setTo("one"))))
        .expect(reply(1, LWWRegister.state("one"), LWWRegister.update("one")))
        .send(command(2, id, "Process", Request(id, LWWRegister.setTo("two"))))
        .expect(reply(2, LWWRegister.state("two"), LWWRegister.update("two")))
        .passivate()
    }

    "verify LWWRegister initial delta in init" in LWWRegister.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id, LWWRegister.delta("one")))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, LWWRegister.state("one")))
        .passivate()
    }

    "verify LWWRegister initial empty state with replicated initial delta" in LWWRegister.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(delta(LWWRegister.delta("one")))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, LWWRegister.state("one")))
        .passivate()
    }

    "verify LWWRegister mix of local and replicated state changes" in LWWRegister.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, LWWRegister.setTo("A"))))
        .expect(reply(1, LWWRegister.state("A"), LWWRegister.update("A")))
        .send(delta(LWWRegister.delta("B")))
        .send(delta(LWWRegister.delta("C")))
        .send(command(2, id, "Process", Request(id)))
        .expect(reply(2, LWWRegister.state("C")))
        .send(command(3, id, "Process", Request(id, LWWRegister.setTo("D"))))
        .expect(reply(3, LWWRegister.state("D"), LWWRegister.update("D")))
        .send(delta(LWWRegister.delta("D")))
        .send(command(4, id, "Process", Request(id, LWWRegister.setTo("E"))))
        .expect(reply(4, LWWRegister.state("E"), LWWRegister.update("E")))
        .passivate()
    }

    "verify LWWRegister state changes with clock settings" in LWWRegister.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, LWWRegister.setTo("A", LWWRegister.ReverseClock))))
        .expect(reply(1, LWWRegister.state("A"), LWWRegister.update("A", CrdtClock.REVERSE)))
        .send(command(2, id, "Process", Request(id, LWWRegister.setTo("A", LWWRegister.CustomClock, 123L))))
        .expect(reply(2, LWWRegister.state("A"), LWWRegister.update("A", CrdtClock.CUSTOM, 123L)))
        .send(command(2, id, "Process", Request(id, LWWRegister.setTo("A", LWWRegister.CustomAutoIncClock, 456L))))
        .expect(reply(2, LWWRegister.state("A"), LWWRegister.update("A", CrdtClock.CUSTOM_AUTO_INCREMENT, 456L)))
        .passivate()
    }

    "verify LWWRegister rehydration after passivation" in LWWRegister.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, LWWRegister.setTo("A"))))
        .expect(reply(1, LWWRegister.state("A"), LWWRegister.update("A")))
        .send(delta(LWWRegister.delta("B")))
        .send(command(2, id, "Process", Request(id)))
        .expect(reply(2, LWWRegister.state("B")))
        .send(command(3, id, "Process", Request(id, LWWRegister.setTo("C"))))
        .expect(reply(3, LWWRegister.state("C"), LWWRegister.update("C")))
        .passivate()
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id, LWWRegister.delta("C")))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, LWWRegister.state("C")))
        .send(delta(LWWRegister.delta("D")))
        .send(command(2, id, "Process", Request(id, LWWRegister.setTo("E"))))
        .expect(reply(2, LWWRegister.state("E"), LWWRegister.update("E")))
        .passivate()
    }

    "verify LWWRegister delete action received from entity" in LWWRegister.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, LWWRegister.setTo("one"))))
        .expect(reply(1, LWWRegister.state("one"), LWWRegister.update("one")))
        .send(command(2, id, "Process", Request(id, Seq(requestDelete))))
        .expect(reply(2, LWWRegister.state("one"), deleteCrdt))
        .passivate()
    }

    "verify LWWRegister delete action sent to entity" in LWWRegister.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(delete)
        .passivate()
    }

    // Flag tests

    "verify Flag initial empty state" in Flag.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, Flag.state(false)))
        .passivate()
    }

    "verify Flag state changes" in Flag.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, Flag.enable())))
        .expect(reply(1, Flag.state(true), Flag.update(true)))
        .passivate()
    }

    "verify Flag initial delta in init" in Flag.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id, Flag.delta(false)))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, Flag.state(false)))
        .passivate()
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id, Flag.delta(true)))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, Flag.state(true)))
        .passivate()
    }

    "verify Flag initial empty state with replicated initial delta" in Flag.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(delta(Flag.delta(false)))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, Flag.state(false)))
        .passivate()
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(delta(Flag.delta(true)))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, Flag.state(true)))
        .passivate()
    }

    "verify Flag mix of local and replicated state changes" in Flag.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(delta(Flag.delta(false)))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, Flag.state(false)))
        .send(command(2, id, "Process", Request(id, Flag.enable())))
        .expect(reply(2, Flag.state(true), Flag.update(true)))
        .send(delta(Flag.delta(true)))
        .send(delta(Flag.delta(false)))
        .send(command(3, id, "Process", Request(id)))
        .expect(reply(3, Flag.state(true)))
        .passivate()
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(delta(Flag.delta(true)))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, Flag.state(true)))
        .send(command(2, id, "Process", Request(id, Flag.enable())))
        .expect(reply(2, Flag.state(true)))
        .send(delta(Flag.delta(true)))
        .send(delta(Flag.delta(false)))
        .send(command(3, id, "Process", Request(id)))
        .expect(reply(3, Flag.state(true)))
        .passivate()
    }

    "verify Flag rehydration after passivation" in Flag.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, Flag.state(false)))
        .send(command(2, id, "Process", Request(id, Flag.enable())))
        .expect(reply(2, Flag.state(true), Flag.update(true)))
        .passivate()
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id, Flag.delta(true)))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, Flag.state(true)))
        .passivate()
    }

    "verify Flag delete action received from entity" in Flag.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, Flag.enable())))
        .expect(reply(1, Flag.state(true), Flag.update(true)))
        .send(command(2, id, "Process", Request(id, Seq(requestDelete))))
        .expect(reply(2, Flag.state(true), deleteCrdt))
        .passivate()
    }

    "verify Flag delete action sent to entity" in Flag.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(delete)
        .passivate()
    }

    // ORMap tests

    "verify ORMap initial empty state" in ORMap.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, ORMap.state()))
        .passivate()
    }

    "verify ORMap state changes" in ORMap.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, ORMap.add("PNCounter-1", "ORSet-1"))))
        .expect(
          reply(
            1,
            ORMap.state(
              "ORSet-1" -> ORSet.state(),
              "PNCounter-1" -> PNCounter.state(0)
            ),
            ORMap.update(
              added = Seq(
                "ORSet-1" -> ORSet.update(),
                "PNCounter-1" -> PNCounter.update(0)
              )
            )
          ),
          Delta.sorted
        )
        .send(
          command(2,
                  id,
                  "Process",
                  Request(id,
                          ORMap.add("ORSet-1") ++ ORMap.updateWith(
                            "LWWRegister-1" -> LWWRegister.setTo("zero")
                          )))
        )
        .expect(
          reply(
            2,
            ORMap.state(
              "LWWRegister-1" -> LWWRegister.state("zero"),
              "ORSet-1" -> ORSet.state(),
              "PNCounter-1" -> PNCounter.state(0)
            ),
            ORMap.update(
              added = Seq(
                "LWWRegister-1" -> LWWRegister.update("zero")
              )
            )
          ),
          Delta.sorted
        )
        .send(
          command(3,
                  id,
                  "Process",
                  Request(id,
                          ORMap.updateWith(
                            "PNCounter-1" -> PNCounter.changeBy(+42),
                            "LWWRegister-1" -> LWWRegister.setTo("one")
                          )))
        )
        .expect(
          reply(
            3,
            ORMap.state(
              "LWWRegister-1" -> LWWRegister.state("one"),
              "ORSet-1" -> ORSet.state(),
              "PNCounter-1" -> PNCounter.state(+42)
            ),
            ORMap.update(
              updated = Seq(
                "LWWRegister-1" -> LWWRegister.update("one"),
                "PNCounter-1" -> PNCounter.update(+42)
              )
            )
          ),
          Delta.sorted
        )
        .send(command(4, id, "Process", Request(id, ORMap.updateWith("ORSet-1" -> ORSet.add("a", "b", "c")))))
        .expect(
          reply(
            4,
            ORMap.state(
              "LWWRegister-1" -> LWWRegister.state("one"),
              "ORSet-1" -> ORSet.state("a", "b", "c"),
              "PNCounter-1" -> PNCounter.state(+42)
            ),
            ORMap.update(
              updated = Seq(
                "ORSet-1" -> ORSet.update(added = Seq("a", "b", "c"))
              )
            )
          ),
          Delta.sorted
        )
        .send(
          command(
            5,
            id,
            "Process",
            Request(id,
                    ORMap.updateWith(
                      "ORSet-1" -> (ORSet.remove("b") ++ ORSet.add("d")),
                      "PNCounter-1" -> PNCounter.changeBy(-123),
                      "LWWRegister-1" -> LWWRegister.setTo("two")
                    ))
          )
        )
        .expect(
          reply(
            5,
            ORMap.state(
              "LWWRegister-1" -> LWWRegister.state("two"),
              "ORSet-1" -> ORSet.state("a", "c", "d"),
              "PNCounter-1" -> PNCounter.state(-81)
            ),
            ORMap.update(
              updated = Seq(
                "LWWRegister-1" -> LWWRegister.update("two"),
                "ORSet-1" -> ORSet.update(removed = Seq("b"), added = Seq("d")),
                "PNCounter-1" -> PNCounter.update(-123)
              )
            )
          ),
          Delta.sorted
        )
        .send(command(6, id, "Process", Request(id, ORMap.remove("ORSet-1"))))
        .expect(
          reply(
            6,
            ORMap.state(
              "LWWRegister-1" -> LWWRegister.state("two"),
              "PNCounter-1" -> PNCounter.state(-81)
            ),
            ORMap.update(removed = Seq("ORSet-1"))
          ),
          Delta.sorted
        )
        .send(
          command(7,
                  id,
                  "Process",
                  Request(id, ORMap.remove("LWWRegister-1") ++ ORMap.updateWith("Flag-1" -> Flag.enable())))
        )
        .expect(
          reply(
            7,
            ORMap.state(
              "Flag-1" -> Flag.state(true),
              "PNCounter-1" -> PNCounter.state(-81)
            ),
            ORMap.update(removed = Seq("LWWRegister-1"), added = Seq("Flag-1" -> Flag.update(true)))
          ),
          Delta.sorted
        )
        .send(command(8, id, "Process", Request(id, ORMap.clear())))
        .expect(reply(8, ORMap.state(), ORMap.update(cleared = true)))
        .send(
          command(
            9,
            id,
            "Process",
            Request(id,
                    ORMap.add("PNCounter-2") ++ ORMap.clear() ++ ORMap.updateWith(
                      "GCounter-1" -> GCounter.incrementBy(123)
                    ))
          )
        )
        .expect(
          reply(9,
                ORMap.state("GCounter-1" -> GCounter.state(123)),
                ORMap.update(cleared = true, added = Seq("GCounter-1" -> GCounter.update(123)))),
          Delta.sorted
        )
        .passivate()
    }

    "verify ORMap initial delta in init" in ORMap.test { id =>
      protocol.crdt
        .connect()
        .send(
          init(
            CrdtTckModel.name,
            id,
            ORMap.delta(
              added = Seq("PNCounter-1" -> PNCounter.delta(+123),
                          "LWWRegister-1" -> LWWRegister.delta("one"),
                          "GSet-1" -> GSet.delta("a", "b", "c"))
            )
          )
        )
        .send(command(1, id, "Process", Request(id)))
        .expect(
          reply(1,
                ORMap.state("GSet-1" -> GSet.state("a", "b", "c"),
                            "LWWRegister-1" -> LWWRegister.state("one"),
                            "PNCounter-1" -> PNCounter.state(+123)))
        )
        .send(
          command(
            2,
            id,
            "Process",
            Request(id, ORMap.remove("LWWRegister-1") ++ ORMap.updateWith("PNCounter-1" -> PNCounter.changeBy(-42)))
          )
        )
        .expect(
          reply(
            2,
            ORMap.state("GSet-1" -> GSet.state("a", "b", "c"), "PNCounter-1" -> PNCounter.state(+81)),
            ORMap.update(removed = Seq("LWWRegister-1"), updated = Seq("PNCounter-1" -> PNCounter.update(-42)))
          ),
          Delta.sorted
        )
        .passivate()
    }

    "verify ORMap initial empty state with replicated initial delta" in ORMap.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(
          delta(
            ORMap.delta(
              added = Seq("GCounter-1" -> GCounter.delta(42),
                          "ORSet-1" -> ORSet.delta(added = Seq("a", "b", "c")),
                          "Flag-1" -> Flag.delta(false))
            )
          )
        )
        .send(command(1, id, "Process", Request(id)))
        .expect(
          reply(1,
                ORMap.state("Flag-1" -> Flag.state(false),
                            "GCounter-1" -> GCounter.state(42),
                            "ORSet-1" -> ORSet.state("a", "b", "c")))
        )
        .passivate()
    }

    "verify ORMap mix of local and replicated state changes" in ORMap.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, ORMap.add("PNCounter-1"))))
        .expect(
          reply(
            1,
            ORMap.state(
              "PNCounter-1" -> PNCounter.state(0)
            ),
            ORMap.update(
              added = Seq(
                "PNCounter-1" -> PNCounter.update(0)
              )
            )
          ),
          Delta.sorted
        )
        .send(delta(ORMap.delta(added = Seq("ORSet-1" -> ORSet.delta(added = Seq("x"))))))
        .send(
          delta(
            ORMap.delta(added = Seq("LWWRegister-1" -> LWWRegister.delta("one")),
                        updated = Seq("ORSet-1" -> ORSet.delta(added = Seq("y", "z"))))
          )
        )
        .send(
          command(2,
                  id,
                  "Process",
                  Request(id,
                          ORMap.add("ORSet-2") ++ ORMap.updateWith(
                            "LWWRegister-2" -> LWWRegister.setTo("two")
                          )))
        )
        .expect(
          reply(
            2,
            ORMap.state(
              "LWWRegister-1" -> LWWRegister.state("one"),
              "LWWRegister-2" -> LWWRegister.state("two"),
              "ORSet-1" -> ORSet.state("x", "y", "z"),
              "ORSet-2" -> ORSet.state(),
              "PNCounter-1" -> PNCounter.state(0)
            ),
            ORMap.update(
              added = Seq(
                "LWWRegister-2" -> LWWRegister.update("two"),
                "ORSet-2" -> ORSet.update()
              )
            )
          ),
          Delta.sorted
        )
        .send(
          command(3,
                  id,
                  "Process",
                  Request(id,
                          ORMap.updateWith(
                            "PNCounter-1" -> PNCounter.changeBy(+42),
                            "LWWRegister-1" -> LWWRegister.setTo("ONE")
                          )))
        )
        .expect(
          reply(
            3,
            ORMap.state(
              "LWWRegister-1" -> LWWRegister.state("ONE"),
              "LWWRegister-2" -> LWWRegister.state("two"),
              "ORSet-1" -> ORSet.state("x", "y", "z"),
              "ORSet-2" -> ORSet.state(),
              "PNCounter-1" -> PNCounter.state(+42)
            ),
            ORMap.update(
              updated = Seq(
                "LWWRegister-1" -> LWWRegister.update("ONE"),
                "PNCounter-1" -> PNCounter.update(+42)
              )
            )
          ),
          Delta.sorted
        )
        .send(
          delta(
            ORMap.delta(removed = Seq("LWWRegister-2"),
                        added = Seq("ORMap-1" -> ORMap.delta(added = Seq("PNCounter-1" -> PNCounter.delta(+123)))))
          )
        )
        .send(
          delta(
            ORMap.delta(
              removed = Seq("ORSet-1"),
              updated = Seq("ORSet-2" -> ORSet.delta(added = Seq("a", "b", "c")),
                            "ORMap-1" -> ORMap.delta(added = Seq("LWWRegister-1" -> LWWRegister.delta("ABC"))))
            )
          )
        )
        .send(
          command(4,
                  id,
                  "Process",
                  Request(id,
                          ORMap.updateWith("ORMap-1" -> ORMap.updateWith("PNCounter-1" -> PNCounter.changeBy(-456)))))
        )
        .expect(
          reply(
            4,
            ORMap.state(
              "LWWRegister-1" -> LWWRegister.state("ONE"),
              "ORMap-1" -> ORMap.state("LWWRegister-1" -> LWWRegister.state("ABC"),
                                       "PNCounter-1" -> PNCounter.state(-333)),
              "ORSet-2" -> ORSet.state("a", "b", "c"),
              "PNCounter-1" -> PNCounter.state(+42)
            ),
            ORMap.update(
              updated = Seq(
                "ORMap-1" -> ORMap.update(updated = Seq("PNCounter-1" -> PNCounter.update(-456)))
              )
            )
          ),
          Delta.sorted
        )
        .send(delta(ORMap.delta(cleared = true, added = Seq("GSet-1" -> GSet.delta("1", "2", "3")))))
        .send(
          command(
            5,
            id,
            "Process",
            Request(id,
                    ORMap.remove("PNCounter-1", "LWWRegister-1") ++
                    ORMap.updateWith("GSet-1" -> GSet.add("2", "4", "6")))
          )
        )
        .expect(
          reply(
            5,
            ORMap.state(
              "GSet-1" -> GSet.state("1", "2", "3", "4", "6")
            ),
            ORMap.update(
              updated = Seq(
                "GSet-1" -> GSet.update("4", "6")
              )
            )
          ),
          Delta.sorted
        )
        .send(command(6, id, "Process", Request(id, ORMap.clear() ++ ORMap.add("GCounter-1"))))
        .expect(
          reply(6,
                ORMap.state("GCounter-1" -> GCounter.state(0)),
                ORMap.update(cleared = true, added = Seq("GCounter-1" -> GCounter.update(0))))
        )
        .send(delta(ORMap.delta(removed = Seq("GSet-1"), added = Seq("GCounter-1" -> GCounter.delta(42)))))
        .send(command(7, id, "Process", Request(id, ORMap.updateWith("GCounter-1" -> GCounter.incrementBy(7)))))
        .expect(
          reply(7,
                ORMap.state("GCounter-1" -> GCounter.state(49)),
                ORMap.update(updated = Seq("GCounter-1" -> GCounter.update(7))))
        )
        .passivate()
    }
    "verify ORMap rehydration after passivation" in ORMap.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(delta(ORMap.delta(added = Seq("PNCounter-1" -> PNCounter.delta(+123)))))
        .send(command(1, id, "Process", Request(id, ORMap.updateWith("PNCounter-1" -> PNCounter.changeBy(+321)))))
        .expect(
          reply(1,
                ORMap.state("PNCounter-1" -> PNCounter.state(+444)),
                ORMap.update(updated = Seq("PNCounter-1" -> PNCounter.update(+321))))
        )
        .passivate()
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id, ORMap.delta(added = Seq("PNCounter-1" -> PNCounter.delta(+444)))))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, ORMap.state("PNCounter-1" -> PNCounter.state(+444))))
        .send(delta(ORMap.delta(updated = Seq("PNCounter-1" -> PNCounter.delta(-42)))))
        .send(command(2, id, "Process", Request(id, ORMap.updateWith("PNCounter-1" -> PNCounter.changeBy(-360)))))
        .expect(
          reply(2,
                ORMap.state("PNCounter-1" -> PNCounter.state(+42)),
                ORMap.update(updated = Seq("PNCounter-1" -> PNCounter.update(-360))))
        )
        .passivate()
    }

    "verify ORMap delete action received from entity" in ORMap.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, ORMap.add("Flag-1"))))
        .expect(
          reply(1,
                ORMap.state("Flag-1" -> Flag.state(false)),
                ORMap.update(added = Seq("Flag-1" -> Flag.update(false))))
        )
        .send(command(2, id, "Process", Request(id, ORMap.updateWith("Flag-1" -> Flag.enable()) :+ requestDelete)))
        .expect(reply(2, ORMap.state("Flag-1" -> Flag.state(true)), deleteCrdt))
        .passivate()
    }

    "verify ORMap delete action sent to entity" in ORMap.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(delete)
        .passivate()
    }

    // Vote tests

    "verify Vote initial empty state" in Vote.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, Vote.state(selfVote = false, votesFor = 0, totalVoters = 1)))
        .passivate()
    }

    "verify Vote state changes" in Vote.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, Vote.self(true))))
        .expect(reply(1, Vote.state(selfVote = true, votesFor = 1, totalVoters = 1), Vote.update(true)))
        .send(command(2, id, "Process", Request(id, Vote.self(true))))
        .expect(reply(2, Vote.state(selfVote = true, votesFor = 1, totalVoters = 1)))
        .send(command(3, id, "Process", Request(id, Vote.self(false))))
        .expect(reply(3, Vote.state(selfVote = false, votesFor = 0, totalVoters = 1), Vote.update(false)))
        .send(command(4, id, "Process", Request(id, Vote.self(false))))
        .expect(reply(4, Vote.state(selfVote = false, votesFor = 0, totalVoters = 1)))
        .passivate()
    }

    "verify Vote initial delta in init" in Vote.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id, Vote.delta(selfVote = true, votesFor = 2, totalVoters = 3)))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, Vote.state(selfVote = true, votesFor = 2, totalVoters = 3)))
        .send(command(2, id, "Process", Request(id, Vote.self(false))))
        .expect(reply(2, Vote.state(selfVote = false, votesFor = 1, totalVoters = 3), Vote.update(false)))
        .passivate()
    }

    "verify Vote initial empty state with replicated initial delta" in Vote.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(delta(Vote.delta(selfVote = false, votesFor = 3, totalVoters = 5)))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, Vote.state(selfVote = false, votesFor = 3, totalVoters = 5)))
        .send(command(2, id, "Process", Request(id, Vote.self(true))))
        .expect(reply(2, Vote.state(selfVote = true, votesFor = 4, totalVoters = 5), Vote.update(true)))
        .passivate()
    }

    "verify Vote mix of local and replicated state changes" in Vote.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(delta(Vote.delta(selfVote = false, votesFor = 2, totalVoters = 4)))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, Vote.state(selfVote = false, votesFor = 2, totalVoters = 4)))
        .send(command(2, id, "Process", Request(id, Vote.self(true))))
        .expect(reply(2, Vote.state(selfVote = true, votesFor = 3, totalVoters = 4), Vote.update(true)))
        .send(delta(Vote.delta(selfVote = true, votesFor = 5, totalVoters = 7)))
        .send(delta(Vote.delta(selfVote = true, votesFor = 4, totalVoters = 6)))
        .send(command(3, id, "Process", Request(id, Vote.self(false))))
        .expect(reply(3, Vote.state(selfVote = false, votesFor = 3, totalVoters = 6), Vote.update(false)))
        .passivate()
    }

    "verify Vote rehydration after passivation" in Vote.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, Vote.state(selfVote = false, votesFor = 0, totalVoters = 1)))
        .send(delta(Vote.delta(selfVote = false, votesFor = 2, totalVoters = 5)))
        .send(command(2, id, "Process", Request(id, Vote.self(true))))
        .expect(reply(2, Vote.state(selfVote = true, votesFor = 3, totalVoters = 5), Vote.update(true)))
        .passivate()
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id, Vote.delta(selfVote = true, votesFor = 3, totalVoters = 5)))
        .send(command(1, id, "Process", Request(id)))
        .expect(reply(1, Vote.state(selfVote = true, votesFor = 3, totalVoters = 5)))
        .send(command(2, id, "Process", Request(id, Vote.self(false))))
        .expect(reply(2, Vote.state(selfVote = false, votesFor = 2, totalVoters = 5), Vote.update(false)))
        .passivate()
    }

    "verify Vote delete action received from entity" in Vote.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, Vote.self(true))))
        .expect(reply(1, Vote.state(selfVote = true, votesFor = 1, totalVoters = 1), Vote.update(true)))
        .send(command(2, id, "Process", Request(id, Seq(requestDelete))))
        .expect(reply(2, Vote.state(selfVote = true, votesFor = 1, totalVoters = 1), deleteCrdt))
        .passivate()
    }

    "verify Vote delete action sent to entity" in Vote.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(delete)
        .passivate()
    }

    // forward and side effect tests

    "verify forward to second service" in GCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, GCounter.incrementBy(42))))
        .expect(reply(1, GCounter.state(42), GCounter.update(42)))
        .send(command(2, id, "Process", Request(id, Seq(forwardTo(s"X-$id")))))
        .expect(forwarded(2, s"X-$id"))
        .send(command(3, id, "Process", Request(id)))
        .expect(reply(3, GCounter.state(42)))
        .passivate()
    }

    "verify forward with state changes" in PNCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, PNCounter.changeBy(-42) :+ forwardTo(s"X-$id"))))
        .expect(forwarded(1, s"X-$id", PNCounter.update(-42)))
        .passivate()
    }

    "verify reply with side effect to second service" in GSet.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, GSet.add("one"))))
        .expect(reply(1, GSet.state("one"), GSet.update("one")))
        .send(command(2, id, "Process", Request(id, Seq(sideEffectTo(s"X-$id")))))
        .expect(reply(2, GSet.state("one"), sideEffects(s"X-$id")))
        .send(command(3, id, "Process", Request(id)))
        .expect(reply(3, GSet.state("one")))
        .passivate()
    }

    "verify synchronous side effect to second service" in ORSet.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(s"X-$id", synchronous = true)))))
        .expect(reply(1, ORSet.state(), synchronousSideEffects(s"X-$id")))
        .passivate()
    }

    "verify forward and side effect to second service" in LWWRegister.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(s"B-$id"), forwardTo(s"A-$id")))))
        .expect(forwarded(1, s"A-$id", LWWRegister.update("") ++ sideEffects(s"B-$id")))
        .passivate()
    }

    "verify reply with multiple side effects" in Flag.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, sideEffectsTo("1", "2", "3"))))
        .expect(reply(1, Flag.state(false), sideEffects("1", "2", "3")))
        .passivate()
    }

    "verify reply with multiple side effects and state changes" in ORMap.test { id =>
      val crdtActions = ORMap.add("PNCounter-1") ++ ORMap.updateWith("GSet-1" -> GSet.add("a", "b", "c"))
      val actions = crdtActions ++ sideEffectsTo("1", "2", "3")
      val expectedState = ORMap.state("GSet-1" -> GSet.state("a", "b", "c"), "PNCounter-1" -> PNCounter.state(0))
      val crdtUpdates = ORMap.update(
        added = Seq(
          "GSet-1" -> GSet.update("a", "b", "c"),
          "PNCounter-1" -> PNCounter.update(0)
        )
      )
      val effects = crdtUpdates ++ sideEffects("1", "2", "3")
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, actions)))
        .expect(reply(1, expectedState, effects), Delta.sorted)
        .passivate()
    }

    // failure tests

    "verify failure action" in GCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, Seq(failWith("expected failure")))))
        .expect(failure(1, "expected failure"))
        .passivate()
    }

    "verify connection after failure action" in PNCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, PNCounter.changeBy(+42))))
        .expect(reply(1, PNCounter.state(+42), PNCounter.update(+42)))
        .send(command(2, id, "Process", Request(id, Seq(failWith("expected failure")))))
        .expect(failure(2, "expected failure"))
        .send(command(3, id, "Process", Request(id)))
        .expect(reply(3, PNCounter.state(+42)))
        .passivate()
    }

    // streamed response tests

    "verify streamed responses" in GCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "ProcessStreamed", StreamedRequest(id), streamed = true))
        .expect(reply(1, GCounter.state(0), Effects(streamed = true)))
        .send(command(2, id, "Process", Request(id, GCounter.incrementBy(1))))
        .expect(reply(2, GCounter.state(1), GCounter.update(1)))
        .expect(streamed(1, GCounter.state(1)))
        .send(command(3, id, "Process", Request(id, GCounter.incrementBy(2))))
        .expect(reply(3, GCounter.state(3), GCounter.update(2)))
        .expect(streamed(1, GCounter.state(3)))
        .send(command(4, id, "Process", Request(id, GCounter.incrementBy(3))))
        .expect(reply(4, GCounter.state(6), GCounter.update(3)))
        .expect(streamed(1, GCounter.state(6)))
        .passivate()
    }

    "verify streamed responses with stream ending action" in GCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(
          command(1, id, "ProcessStreamed", StreamedRequest(id, endState = GCounter.state(3).state), streamed = true)
        )
        .expect(reply(1, GCounter.state(0), Effects(streamed = true)))
        .send(command(2, id, "Process", Request(id, GCounter.incrementBy(1))))
        .expect(reply(2, GCounter.state(1), GCounter.update(1)))
        .expect(streamed(1, GCounter.state(1)))
        .send(command(3, id, "Process", Request(id, GCounter.incrementBy(2))))
        .expect(reply(3, GCounter.state(3), GCounter.update(2)))
        .expect(streamed(1, GCounter.state(3), Effects(endStream = true)))
        .send(command(4, id, "Process", Request(id, GCounter.incrementBy(3))))
        .expect(reply(4, GCounter.state(6), GCounter.update(3)))
        .passivate()
    }

    "verify streamed responses with cancellation" in PNCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(
          command(
            1,
            id,
            "ProcessStreamed",
            StreamedRequest(id, cancelUpdate = Some(PNCounter.updateWith(-42))),
            streamed = true
          )
        )
        .expect(reply(1, PNCounter.state(0), Effects(streamed = true)))
        .send(command(2, id, "Process", Request(id, PNCounter.changeBy(+10))))
        .expect(reply(2, PNCounter.state(+10), PNCounter.update(+10)))
        .expect(streamed(1, PNCounter.state(+10)))
        .send(command(3, id, "Process", Request(id, PNCounter.changeBy(+20))))
        .expect(reply(3, PNCounter.state(+30), PNCounter.update(+20)))
        .expect(streamed(1, PNCounter.state(+30)))
        .send(crdtStreamCancelled(1, id))
        .expect(streamCancelledResponse(1, PNCounter.update(-42)))
        .send(command(4, id, "Process", Request(id)))
        .expect(reply(4, PNCounter.state(-12)))
        .send(command(5, id, "Process", Request(id, PNCounter.changeBy(+30))))
        .expect(reply(5, PNCounter.state(+18), PNCounter.update(+30)))
        .passivate()
    }

    "verify empty streamed responses with cancellation" in PNCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, PNCounter.changeBy(+10))))
        .expect(reply(1, PNCounter.state(+10), PNCounter.update(+10)))
        .send(
          command(
            2,
            id,
            "ProcessStreamed",
            StreamedRequest(id, cancelUpdate = Some(PNCounter.updateWith(-42)), empty = true),
            streamed = true
          )
        )
        .expect(crdtReply(2, None, Effects(streamed = true)))
        .send(crdtStreamCancelled(2, id))
        .expect(streamCancelledResponse(2, PNCounter.update(-42)))
        .send(command(3, id, "Process", Request(id)))
        .expect(reply(3, PNCounter.state(-32)))
        .passivate()
    }

    "verify streamed responses with side effects" in GSet.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(
          command(
            1,
            id,
            "ProcessStreamed",
            StreamedRequest(id, effects = Seq(Effect("one"), Effect("two", synchronous = true))),
            streamed = true
          )
        )
        .expect(reply(1, GSet.state(), Effects(streamed = true)))
        .send(command(2, id, "Process", Request(id, GSet.add("a"))))
        .expect(reply(2, GSet.state("a"), GSet.update("a")))
        .expect(streamed(1, GSet.state("a"), sideEffects("one") ++ synchronousSideEffects("two")))
        .send(command(3, id, "Process", Request(id, GSet.add("b"))))
        .expect(reply(3, GSet.state("a", "b"), GSet.update("b")))
        .expect(streamed(1, GSet.state("a", "b"), sideEffects("one") ++ synchronousSideEffects("two")))
        .passivate()
    }

    // write consistency tests

    def incrementWith(increment: Long, writeConsistency: UpdateWriteConsistency): Seq[RequestAction] =
      Seq(requestUpdate(GCounter.updateWith(increment).withWriteConsistency(writeConsistency)))

    def updateWith(value: Long, writeConsistency: CrdtWriteConsistency): Effects =
      Effects(stateAction = crdtUpdate(GCounter.delta(value), writeConsistency))

    "verify write consistency can be configured" in GCounter.test { id =>
      protocol.crdt
        .connect()
        .send(init(CrdtTckModel.name, id))
        .send(command(1, id, "Process", Request(id, incrementWith(1, UpdateWriteConsistency.LOCAL))))
        .expect(reply(1, GCounter.state(1), updateWith(1, CrdtWriteConsistency.LOCAL)))
        .send(command(2, id, "Process", Request(id, incrementWith(2, UpdateWriteConsistency.MAJORITY))))
        .expect(reply(2, GCounter.state(3), updateWith(2, CrdtWriteConsistency.MAJORITY)))
        .send(command(3, id, "Process", Request(id, incrementWith(3, UpdateWriteConsistency.ALL))))
        .expect(reply(3, GCounter.state(6), updateWith(3, CrdtWriteConsistency.ALL)))
        .passivate()
    }
  }

  object CrdtEntityTCKProxy {
    val tckModelClient: CrdtTckModelClient = CrdtTckModelClient(client.settings)(client.system)
    val modelTwoClient: CrdtTwoClient = CrdtTwoClient(client.settings)(client.system)
    val configuredClient: CrdtConfiguredClient = CrdtConfiguredClient(client.settings)(client.system)

    def terminate(): Unit = {
      tckModelClient.close()
      modelTwoClient.close()
      configuredClient.close()
    }
  }

  override def afterAll(): Unit =
    try CrdtEntityTCKProxy.terminate()
    finally super.afterAll()

  def verifyCrdtEntityProxy(): Unit = {
    import CrdtEntityTCKModel._
    import CrdtEntityTCKProxy._

    "verify state changes" in PNCounter.test { id =>
      tckModelClient.process(Request(id)).futureValue mustBe PNCounter.state(0)
      val connection = interceptor
        .expectCrdtEntityConnection()
        .expectIncoming(init(Service, id))
        .expectIncoming(command(1, id, "Process", Request(id)))
        .expectOutgoing(reply(1, PNCounter.state(0)))

      tckModelClient.process(Request(id, PNCounter.changeBy(+1, -2, +3))).futureValue mustBe PNCounter.state(+2)
      connection
        .expectIncoming(command(2, id, "Process", Request(id, PNCounter.changeBy(+1, -2, +3))))
        .expectOutgoing(reply(2, PNCounter.state(+2), PNCounter.update(+2)))

      tckModelClient.process(Request(id, PNCounter.changeBy(+4, -5, +6))).futureValue mustBe PNCounter.state(+7)
      connection
        .expectIncoming(command(3, id, "Process", Request(id, PNCounter.changeBy(+4, -5, +6))))
        .expectOutgoing(reply(3, PNCounter.state(+7), PNCounter.update(+5)))

      tckModelClient.process(Request(id, Seq(requestDelete))).futureValue mustBe PNCounter.state(+7)
      connection
        .expectIncoming(command(4, id, "Process", Request(id, Seq(requestDelete))))
        .expectOutgoing(reply(4, PNCounter.state(+7), deleteCrdt))
        .expectClosed()
    }

    "verify forwards and side effects" in GSet.test { id =>
      tckModelClient.process(Request(id, GSet.add("one"))).futureValue mustBe GSet.state("one")
      val connection = interceptor
        .expectCrdtEntityConnection()
        .expectIncoming(init(Service, id))
        .expectIncoming(command(1, id, "Process", Request(id, GSet.add("one"))))
        .expectOutgoing(reply(1, GSet.state("one"), GSet.update("one")))

      tckModelClient.process(Request(id, Seq(forwardTo(id)))).futureValue mustBe Response()
      connection
        .expectIncoming(command(2, id, "Process", Request(id, Seq(forwardTo(id)))))
        .expectOutgoing(forward(2, ServiceTwo, "Call", Request(id)))
      val connection2 = interceptor
        .expectCrdtEntityConnection()
        .expectIncoming(init(ServiceTwo, id))
        .expectIncoming(command(1, id, "Call", Request(id)))
        .expectOutgoing(reply(1, Response()))

      tckModelClient.process(Request(id, Seq(sideEffectTo(id)))).futureValue mustBe GSet.state("one")
      connection
        .expectIncoming(command(3, id, "Process", Request(id, Seq(sideEffectTo(id)))))
        .expectOutgoing(reply(3, GSet.state("one"), sideEffects(id)))
      connection2
        .expectIncoming(command(2, id, "Call", Request(id)))
        .expectOutgoing(reply(2, Response()))

      tckModelClient.process(Request(id, Seq(requestDelete))).futureValue mustBe GSet.state("one")
      connection
        .expectIncoming(command(4, id, "Process", Request(id, Seq(requestDelete))))
        .expectOutgoing(reply(4, GSet.state("one"), deleteCrdt))
        .expectClosed()

      modelTwoClient.call(Request(id, Seq(requestDelete))).futureValue mustBe Response()
      connection2
        .expectIncoming(command(3, id, "Call", Request(id, Seq(requestDelete))))
        .expectOutgoing(reply(3, Response(), deleteCrdt))
        .expectClosed()
    }

    "verify failures" in GCounter.test { id =>
      tckModelClient.process(Request(id, GCounter.incrementBy(42))).futureValue mustBe GCounter.state(42)
      val connection = interceptor
        .expectCrdtEntityConnection()
        .expectIncoming(init(Service, id))
        .expectIncoming(command(1, id, "Process", Request(id, GCounter.incrementBy(42))))
        .expectOutgoing(reply(1, GCounter.state(42), GCounter.update(42)))

      val failed = tckModelClient.process(Request(id, Seq(failWith("expected failure")))).failed.futureValue
      failed mustBe a[StatusRuntimeException]
      failed.asInstanceOf[StatusRuntimeException].getStatus.getDescription mustBe "expected failure"
      connection
        .expectIncoming(command(2, id, "Process", Request(id, Seq(failWith("expected failure")))))
        .expectOutgoing(failure(2, "expected failure"))

      tckModelClient.process(Request(id, Seq(requestDelete))).futureValue mustBe GCounter.state(42)
      connection
        .expectIncoming(command(3, id, "Process", Request(id, Seq(requestDelete))))
        .expectOutgoing(reply(3, GCounter.state(42), deleteCrdt))
        .expectClosed()
    }

    "verify streamed responses for connection tracking" in Vote.test { id =>
      implicit val actorSystem: ActorSystem = client.system

      val state0 = Vote.state(selfVote = false, votesFor = 0, totalVoters = 1)
      val state1 = Vote.state(selfVote = true, votesFor = 1, totalVoters = 1)
      val voteTrue = Some(Vote.updateWith(true))
      val voteFalse = Some(Vote.updateWith(false))

      val monitor = tckModelClient.processStreamed(StreamedRequest(id)).runWith(TestSink.probe[Response])
      monitor.request(1).expectNext(state0)
      val connection = interceptor
        .expectCrdtEntityConnection()
        .expectIncoming(init(CrdtTckModel.name, id))
        .expectIncoming(command(1, id, "ProcessStreamed", StreamedRequest(id), streamed = true))
        .expectOutgoing(reply(1, state0, Effects(streamed = true)))

      val connectRequest = StreamedRequest(id, initialUpdate = voteTrue, cancelUpdate = voteFalse, empty = true)
      val connect = tckModelClient.processStreamed(connectRequest).runWith(TestSink.probe[Response])
      connect.request(1).expectNoMessage(100.millis)
      monitor.request(1).expectNext(state1)
      connection
        .expectIncoming(command(2, id, "ProcessStreamed", connectRequest, streamed = true))
        .expectOutgoing(crdtReply(2, None, Effects(streamed = true) ++ Vote.update(true)))
        .expectOutgoing(streamed(1, state1))

      connect.cancel()
      monitor.request(1).expectNext(state0)
      connection
        .expectIncoming(crdtStreamCancelled(2, id))
        .expectOutgoing(streamCancelledResponse(2, Vote.update(false)))
        .expectOutgoing(streamed(1, state0))

      monitor.cancel()
      connection
        .expectIncoming(crdtStreamCancelled(1, id))
        .expectOutgoing(streamCancelledResponse(1))

      val deleteRequest = Request(id, Seq(requestDelete))
      tckModelClient.process(deleteRequest).futureValue mustBe state0
      connection
        .expectIncoming(command(3, id, "Process", deleteRequest))
        .expectOutgoing(reply(3, state0, deleteCrdt))
        .expectClosed()
    }

    "verify HTTP API" in PNCounter.test { id =>
      client.http
        .request(s"tck/model/crdt/$id", "{}")
        .futureValue mustBe """{"state":{"pncounter":{"value":"0"}}}"""
      val connection = interceptor
        .expectCrdtEntityConnection()
        .expectIncoming(init(Service, id))
        .expectIncoming(command(1, id, "Process", Request(id)))
        .expectOutgoing(reply(1, PNCounter.state(0)))

      client.http
        .request("cloudstate.tck.model.crdt.CrdtTwo/Call", s"""{"id": "$id"}""")
        .futureValue mustBe "{}"
      interceptor
        .expectCrdtEntityConnection()
        .expectIncoming(init(ServiceTwo, id))
        .expectIncoming(command(1, id, "Call", Request(id)))
        .expectOutgoing(reply(1, Response()))

      client.http
        .request(s"tck/model/crdt/$id", """{"actions": [{"update": {"pncounter": {"change": 42} }}]}""")
        .futureValue mustBe """{"state":{"pncounter":{"value":"42"}}}"""
      connection
        .expectIncoming(command(2, id, "Process", Request(id, PNCounter.changeBy(+42))))
        .expectOutgoing(reply(2, PNCounter.state(+42), PNCounter.update(+42)))

      client.http
        .requestToError(s"tck/model/crdt/$id", """{"actions": [{"fail": {"message": "expected failure"}}]}""")
        .futureValue mustBe "expected failure"
      connection
        .expectIncoming(command(3, id, "Process", Request(id, Seq(failWith("expected failure")))))
        .expectOutgoing(failure(3, "expected failure"))

      client.http
        .request(s"tck/model/crdt/$id", """{"actions": [{"update": {"pncounter": {"change": -123} }}]}""")
        .futureValue mustBe """{"state":{"pncounter":{"value":"-81"}}}"""
      connection
        .expectIncoming(command(4, id, "Process", Request(id, PNCounter.changeBy(-123))))
        .expectOutgoing(reply(4, PNCounter.state(-81), PNCounter.update(-123)))

      client.http
        .request(s"tck/model/crdt/$id", """{"actions": [{"delete": {}}]}""")
        .futureValue mustBe """{"state":{"pncounter":{"value":"-81"}}}"""
      connection
        .expectIncoming(command(5, id, "Process", Request(id, Seq(requestDelete))))
        .expectOutgoing(reply(5, PNCounter.state(-81), deleteCrdt))
        .expectClosed()
    }

    "verify passivation timeout" in crdtConfiguredTest { id =>
      configuredClient.call(Request(id))
      interceptor
        .expectCrdtEntityConnection()
        .expectIncoming(init(ServiceConfigured, id))
        .expectIncoming(command(1, id, "Call", Request(id)))
        .expectOutgoing(reply(1, Response()))
        .expectClosed(2.seconds) // check passivation (with expected timeout of 100 millis)
    }
  }
}
