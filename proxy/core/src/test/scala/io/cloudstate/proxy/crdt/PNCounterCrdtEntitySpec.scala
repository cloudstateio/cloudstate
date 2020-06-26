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

import akka.cluster.ddata.{PNCounter, PNCounterKey}
import io.cloudstate.protocol.crdt._

import scala.concurrent.duration._

class PNCounterCrdtEntitySpec extends AbstractCrdtEntitySpec {

  import AbstractCrdtEntitySpec._

  override protected type T = PNCounter
  override protected type S = PNCounterState
  override protected type D = PNCounterDelta

  override protected def key(name: String) = PNCounterKey(name)

  override protected def initial = PNCounter.empty

  override protected def extractState(state: CrdtState.State) = state.pncounter.value

  override protected def extractDelta(delta: CrdtDelta.Delta) = delta.pncounter.value

  def create(value: Long) =
    CrdtStateAction.Action.Create(CrdtState(CrdtState.State.Pncounter(PNCounterState(value))))

  def updateCounter(update: Long) =
    CrdtStateAction.Action.Update(CrdtDelta(CrdtDelta.Delta.Pncounter(PNCounterDelta(update))))

  "The PNCounter CrdtEntity" should {

    "allow creating an a new counter" in {
      createAndExpectInit() shouldBe None
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, create(0))
      eventually {
        get().value.toLong shouldBe 0
      }
      toUserFunction.expectNoMessage(150.millis)
    }

    "allow creating a counter with a value initialised" in {
      createAndExpectInit() shouldBe None
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, create(5))
      eventually {
        get().value.toLong shouldBe 5
      }
      toUserFunction.expectNoMessage(150.millis)
    }

    "be initialised from an empty counter" in {
      update(identity)
      createAndExpectInit().value.value shouldBe 0
    }

    "be initialised from a counter with a value" in {
      update(_ :+ 5)
      createAndExpectInit().value.value shouldBe 5
    }

    "push the full state when no entity exists" in {
      createAndExpectInit() shouldBe None
      update(_ :+ 5)
      expectState().value shouldBe 5
    }

    "detect and send increments to the user function" in {
      update(_ :+ 5)
      createAndExpectInit()

      update(_ :+ 3)
      expectDelta().change shouldBe 3
    }

    "detect and send decrements to the user function" in {
      update(_ :+ 5)
      createAndExpectInit()

      update(_ :+ -3)
      expectDelta().change shouldBe -3
    }

    "handle increment deltas" in {
      update(_ :+ 5)
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, updateCounter(3))
      eventually {
        get().value.toLong shouldBe 8
      }
      toUserFunction.expectNoMessage(200.millis)
    }

    "handle decrement deltas" in {
      update(_ :+ 5)
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, updateCounter(-3))
      eventually {
        get().value.toLong shouldBe 2
      }
      toUserFunction.expectNoMessage(200.millis)
    }

  }
}
