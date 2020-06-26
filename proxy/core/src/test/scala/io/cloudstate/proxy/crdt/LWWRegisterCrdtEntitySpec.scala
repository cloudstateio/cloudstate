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

package io.cloudstate.proxy.crdt

import akka.cluster.ddata.{LWWRegister, LWWRegisterKey}
import com.google.protobuf.any.{Any => ProtoAny}
import io.cloudstate.protocol.crdt._

import scala.concurrent.duration._

class LWWRegisterCrdtEntitySpec extends AbstractCrdtEntitySpec {

  import AbstractCrdtEntitySpec._

  override protected type T = LWWRegister[ProtoAny]
  override protected type S = LWWRegisterState
  override protected type D = LWWRegisterDelta

  override protected def key(name: String) = LWWRegisterKey(name)

  override protected def initial = LWWRegister.create(element1)

  override protected def extractState(state: CrdtState.State) = state.lwwregister.value

  override protected def extractDelta(delta: CrdtDelta.Delta) = delta.lwwregister.value

  def create(element: ProtoAny) =
    CrdtStateAction.Action.Create(CrdtState(CrdtState.State.Lwwregister(LWWRegisterState(value = Some(element)))))

  def updateRegister(element: ProtoAny, clock: CrdtClock = CrdtClock.DEFAULT, customClockValue: Long = 0) =
    CrdtStateAction.Action.Update(
      CrdtDelta(
        CrdtDelta.Delta
          .Lwwregister(LWWRegisterDelta(value = Some(element), clock = clock, customClockValue = customClockValue))
      )
    )

  "The LWWRegister CrdtEntity" should {

    "allow creating a register" in {
      createAndExpectInit() shouldBe None
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, create(element1))
      eventually {
        get().value shouldBe element1
      }
      toUserFunction.expectNoMessage(150.millis)
    }

    "be initialised from a register" in {
      update(identity)
      createAndExpectInit().value.value.value /* value! value! value! */ shouldBe element1
    }

    "push the full state when no entity exists" in {
      createAndExpectInit() shouldBe None
      update(identity)
      expectState().value.value shouldBe element1
    }

    "detect and send changes in the register" in {
      update(identity)
      createAndExpectInit()

      update(_.withValueOf(element2))
      expectDelta().value.value shouldBe element2
    }

    "handle change deltas" in {
      update(identity)
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, updateRegister(element2))
      eventually {
        get().value shouldBe element2
      }
      toUserFunction.expectNoMessage(200.millis)
    }

    "handle change deltas with reverse clock" in {
      update(identity)
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, updateRegister(element2, clock = CrdtClock.REVERSE))
      Thread.sleep(200)
      get().value shouldBe element1
      // It should end up being changed back to element1
      expectDelta().value.value shouldBe element1
    }

    "handle change deltas with custom clock" in {
      val start = System.currentTimeMillis() + 1000000
      update(identity)
      createAndExpectInit()

      val cid1 = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid1, updateRegister(element2, clock = CrdtClock.CUSTOM, customClockValue = start + 1000))
      get().value shouldBe element2
      expectNoMessage(200.millis)

      val cid2 = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid2, updateRegister(element3, clock = CrdtClock.CUSTOM, customClockValue = start))
      Thread.sleep(200)
      get().value shouldBe element2
      expectDelta().value.value shouldBe element2
    }

    "handle change deltas with custom auto incrementing clock" in {
      val start = System.currentTimeMillis() + 1000000
      update(identity)
      createAndExpectInit()

      val cid1 = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(
        cid1,
        updateRegister(element2, clock = CrdtClock.CUSTOM_AUTO_INCREMENT, customClockValue = start + 1000)
      )
      get().value shouldBe element2
      expectNoMessage(200.millis)

      val cid2 = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid2,
                         updateRegister(element3, clock = CrdtClock.CUSTOM_AUTO_INCREMENT, customClockValue = start))
      get().value shouldBe element3
      expectNoMessage(200.millis)
    }

  }
}
