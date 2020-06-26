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

import akka.cluster.ddata.{ORSet, ORSetKey}
import io.cloudstate.protocol.crdt.{CrdtDelta, CrdtReply, CrdtState, CrdtStateAction, ORSetDelta, ORSetState}
import com.google.protobuf.any.{Any => ProtoAny}

import scala.concurrent.duration._

class ORSetCrdtEntitySpec extends AbstractCrdtEntitySpec {

  import AbstractCrdtEntitySpec._

  override protected type T = ORSet[ProtoAny]
  override protected type S = ORSetState
  override protected type D = ORSetDelta

  override protected def key(name: String) = ORSetKey(name)

  override protected def initial = ORSet.empty

  override protected def extractState(state: CrdtState.State) = state.orset.value

  override protected def extractDelta(delta: CrdtDelta.Delta) = delta.orset.value

  def createSet(elements: ProtoAny*) =
    CrdtStateAction.Action.Create(CrdtState(CrdtState.State.Orset(ORSetState(elements))))

  def updateSet(added: Seq[ProtoAny] = Nil, removed: Seq[ProtoAny] = Nil, cleared: Boolean = false) =
    CrdtStateAction.Action.Update(
      CrdtDelta(CrdtDelta.Delta.Orset(ORSetDelta(added = added, removed = removed, cleared = cleared)))
    )

  "The ORSet CrdtEntity" should {

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
        s :+ element1 :+ element2
      }
      createAndExpectInit().value.items should contain theSameElementsAs Seq(element1, element2)
    }

    "push the full state when no entity exists" in {
      createAndExpectInit() shouldBe None
      update { s =>
        s :+ element1 :+ element2
      }
      expectState().items should contain theSameElementsAs Seq(element1, element2)
    }

    "detect and send additions to the user function" in {
      update { s =>
        s :+ element1
      }
      createAndExpectInit()

      update { s =>
        s :+ element2
      }
      val delta = expectDelta()
      delta.added should contain theSameElementsAs Seq(element2)
      delta.cleared shouldBe false
      delta.removed shouldBe empty
    }

    "detect and send deletions to the user function" in {
      update { s =>
        s :+ element1 :+ element2
      }
      createAndExpectInit()

      update { s =>
        s remove element1
      }
      val delta = expectDelta()
      delta.removed should contain theSameElementsAs Seq(element1)
      delta.cleared shouldBe false
      delta.added shouldBe empty
    }

    "detect and send cleared to the user function" in {
      update { s =>
        s :+ element1 :+ element2
      }
      createAndExpectInit()

      update { s =>
        s.clear(selfUniqueAddress)
      }
      val delta = expectDelta()
      delta.cleared shouldBe true
      delta.removed shouldBe empty
      delta.added shouldBe empty
    }

    "send added and removed elements" in {
      update { s =>
        s :+ element1 :+ element2
      }
      createAndExpectInit()

      update { s =>
        (s remove element2) :+ element3
      }
      val delta = expectDelta()
      delta.added should contain theSameElementsAs Seq(element3)
      delta.removed should contain theSameElementsAs Seq(element2)
      delta.cleared shouldBe false
    }

    "send cleared when the new set contains no elements from the old" in {
      update { s =>
        s :+ element1 :+ element2
      }
      createAndExpectInit()

      update { s =>
        s.clear(selfUniqueAddress) :+ element3
      }
      val delta = expectDelta()
      delta.cleared shouldBe true
      delta.added should contain theSameElementsAs Seq(element3)
      delta.removed shouldBe empty
    }

    "clear the set if sending the elements to remove is going to be more expensive than clearing and re-adding everything" in {
      update { s =>
        s :+ element1 :+ element2 :+ element3
      }
      createAndExpectInit()

      update { s =>
        s remove element1 remove element2
      }
      val delta = expectDelta()
      delta.cleared shouldBe true
      delta.added should contain theSameElementsAs Seq(element3)
      delta.removed shouldBe empty
    }

    "handle add deltas" in {
      update { s =>
        s :+ element1
      }
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, updateSet(added = Seq(element2)))
      eventually {
        get().elements should contain theSameElementsAs Seq(element1, element2)
      }
      toUserFunction.expectNoMessage(200.millis)
    }

    "handle remove deltas" in {
      update { s =>
        s :+ element1 :+ element2
      }
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, updateSet(removed = Seq(element2)))
      eventually {
        get().elements should contain theSameElementsAs Seq(element1)
      }
      toUserFunction.expectNoMessage(200.millis)
    }

    "handle clear deltas" in {
      update { s =>
        s :+ element1 :+ element2
      }
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, updateSet(cleared = true))
      eventually {
        get().elements shouldBe empty
      }
      toUserFunction.expectNoMessage(200.millis)
    }

    "handle combination deltas" in {
      update { s =>
        s :+ element1 :+ element2
      }
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, updateSet(added = Seq(element3), removed = Seq(element2)))
      eventually {
        get().elements should contain theSameElementsAs Seq(element1, element3)
      }
      toUserFunction.expectNoMessage(200.millis)
    }

    "handle cleared and added deltas" in {
      update { s =>
        s :+ element1 :+ element2
      }
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, updateSet(added = Seq(element3), cleared = true))
      eventually {
        get().elements should contain theSameElementsAs Seq(element3)
      }
      toUserFunction.expectNoMessage(200.millis)
    }

  }
}
