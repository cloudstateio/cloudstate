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

import akka.cluster.ddata.{GSet, GSetKey}
import com.google.protobuf.any.{Any => ProtoAny}
import io.cloudstate.protocol.crdt._

import scala.concurrent.duration._

class GSetCrdtEntitySpec extends AbstractCrdtEntitySpec {

  import AbstractCrdtEntitySpec._

  override protected type T = GSet[ProtoAny]
  override protected type S = GSetState
  override protected type D = GSetDelta

  override protected def key(name: String) = GSetKey(name)

  override protected def initial = GSet.empty

  override protected def extractState(state: CrdtState.State) = state.gset.value

  override protected def extractDelta(delta: CrdtDelta.Delta) = delta.gset.value

  def createSet(elements: ProtoAny*) =
    CrdtStateAction.Action.Create(CrdtState(CrdtState.State.Gset(GSetState(elements))))

  def updateSet(elements: ProtoAny*) =
    CrdtStateAction.Action.Update(CrdtDelta(CrdtDelta.Delta.Gset(GSetDelta(elements))))

  "The GSet CrdtEntity" should {

    "allow creating an empty set" in {
      createAndExpectInit() shouldBe None
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, createSet())
      eventually {
        get().elements shouldBe empty
      }
      toUserFunction.expectNoMessage(150.millis)
    }

    "allow creating a set with elements in it" in {
      createAndExpectInit() shouldBe None
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, createSet(element1, element2))
      eventually {
        get().elements should contain theSameElementsAs Seq(element1, element2)
      }
      toUserFunction.expectNoMessage(150.millis)
    }

    "be initialised from an empty set" in {
      update(identity)
      createAndExpectInit().value.items shouldBe empty
    }

    "be initialised from a non empty set" in {
      update { s =>
        s + element1 + element2
      }
      createAndExpectInit().value.items should contain theSameElementsAs Seq(element1, element2)
    }

    "push the full state when no entity exists" in {
      createAndExpectInit() shouldBe None
      update { s =>
        s + element1 + element2
      }
      expectState().items should contain theSameElementsAs Seq(element1, element2)
    }

    "detect and send additions to the user function" in {
      update { s =>
        s + element1
      }
      createAndExpectInit()

      update { s =>
        s + element2
      }
      val delta = expectDelta()
      delta.added should contain theSameElementsAs Seq(element2)
    }

    "handle add deltas" in {
      update { s =>
        s + element1
      }
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, updateSet(element2))
      eventually {
        get().elements should contain theSameElementsAs Seq(element1, element2)
      }
      toUserFunction.expectNoMessage(200.millis)
    }
  }
}
