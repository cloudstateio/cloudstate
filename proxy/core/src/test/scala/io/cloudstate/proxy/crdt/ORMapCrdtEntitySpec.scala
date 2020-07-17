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

import akka.cluster.ddata.{GCounter, ORMap, ORMapKey, PNCounter, ReplicatedData}
import com.google.protobuf.any.{Any => ProtoAny}
import io.cloudstate.protocol.crdt._

import scala.concurrent.duration._

class ORMapCrdtEntitySpec extends AbstractCrdtEntitySpec {

  import AbstractCrdtEntitySpec._

  override protected type T = ORMap[ProtoAny, ReplicatedData]
  override protected type S = ORMapState
  override protected type D = ORMapDelta

  override protected def key(name: String) = ORMapKey(name)

  override protected def initial = ORMap.empty

  override protected def extractState(state: CrdtState.State) = state.ormap.value

  override protected def extractDelta(delta: CrdtDelta.Delta) = delta.ormap.value

  def mapCounterEntries(elements: (ProtoAny, Int)*) =
    elements.map {
      case (k, v) => ORMapEntry(Some(k), Some(createCounter(v)))
    }

  def mapCounterDeltas(elements: (ProtoAny, Int)*) =
    elements.map {
      case (k, v) => ORMapEntryDelta(Some(k), Some(updateCounter(v)))
    }

  def createMap(elements: Seq[ORMapEntry]) =
    CrdtStateAction.Action.Create(CrdtState(CrdtState.State.Ormap(ORMapState(elements))))

  def updateMap(added: Seq[(ProtoAny, CrdtState)] = Nil,
                removed: Seq[ProtoAny] = Nil,
                updated: Seq[(ProtoAny, CrdtDelta)] = Nil,
                cleared: Boolean = false) =
    CrdtStateAction.Action.Update(
      CrdtDelta(
        CrdtDelta.Delta.Ormap(
          ORMapDelta(
            added = added.map(e => ORMapEntry(Some(e._1), Some(e._2))),
            updated = updated.map(e => ORMapEntryDelta(Some(e._1), Some(e._2))),
            removed = removed,
            cleared = cleared
          )
        )
      )
    )

  def createCounter(value: Int) =
    CrdtState(CrdtState.State.Gcounter(GCounterState(value)))

  def updateCounter(increment: Int) =
    CrdtDelta(CrdtDelta.Delta.Gcounter(GCounterDelta(increment)))

  def verifyMapHasCounters(entries: (ProtoAny, Int)*) = {
    val map = get()
    map should have size entries.size
    entries.foreach {
      case (k, v) =>
        map.entries should contain key k
        inside(map.get(k).value) {
          case counter: GCounter => counter.value should ===(v)
        }
    }
  }

  "The ORMap CrdtEntity" should {

    "allow creating an empty map" in {

      createAndExpectInit() shouldBe None
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, createMap(mapCounterEntries()))
      eventually {
        get().entries shouldBe empty
      }
      toUserFunction.expectNoMessage(150.millis)
    }

    "allow creating a map with elements in it" in {
      createAndExpectInit() shouldBe None
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, createMap(mapCounterEntries(element1 -> 2, element2 -> 5)))
      eventually {
        verifyMapHasCounters(element1 -> 2, element2 -> 5)
      }
      toUserFunction.expectNoMessage(150.millis)
    }

    "be initialised from an empty map" in {
      update(identity)
      createAndExpectInit().value.entries shouldBe empty
    }

    "be initialised from a non empty set" in {
      update { s =>
        s :+ (element1 -> (GCounter.empty :+ 2)) :+ (element2 -> (GCounter.empty :+ 5))
      }
      createAndExpectInit().value.entries should contain theSameElementsAs mapCounterEntries(element1 -> 2,
                                                                                             element2 -> 5)
    }

    "push the full state when no entity exists" in {
      createAndExpectInit() shouldBe None
      update { s =>
        s :+ (element1 -> (GCounter.empty :+ 2)) :+ (element2 -> (GCounter.empty :+ 5))
      }
      expectState().entries should contain theSameElementsAs mapCounterEntries(element1 -> 2, element2 -> 5)
    }

    "detect and send additions to the user function" in {
      update { s =>
        s :+ (element1 -> (GCounter.empty :+ 2))
      }
      createAndExpectInit()

      update { s =>
        s :+ (element2 -> (GCounter.empty :+ 5))
      }
      val delta = expectDelta()
      delta.added should contain theSameElementsAs mapCounterEntries(element2 -> 5)
      delta.cleared shouldBe false
      delta.removed shouldBe empty
      delta.updated shouldBe empty
    }

    "detect and send deletions to the user function" in {
      update { s =>
        s :+ (element1 -> (GCounter.empty :+ 2)) :+ (element2 -> (GCounter.empty :+ 5))
      }
      createAndExpectInit()

      update { s =>
        s remove element1
      }
      val delta = expectDelta()
      delta.removed should contain theSameElementsAs Seq(element1)
      delta.cleared shouldBe false
      delta.added shouldBe empty
      delta.updated shouldBe empty
    }

    "detect and send cleared to the user function" in {
      update { s =>
        s :+ (element1 -> (GCounter.empty :+ 2)) :+ (element2 -> (GCounter.empty :+ 5))
      }
      createAndExpectInit()

      update { s =>
        s remove element1 remove element2
      }
      val delta = expectDelta()
      delta.cleared shouldBe true
      delta.removed shouldBe empty
      delta.added shouldBe empty
    }

    "detect and send updated elements to the user function" in {
      update { s =>
        s :+ (element1 -> (GCounter.empty :+ 2)) :+ (element2 -> (GCounter.empty :+ 5))
      }
      createAndExpectInit()

      update { s =>
        s.updated(selfUniqueAddress, element1, GCounter.empty) {
          case gcounter: GCounter => gcounter :+ 7
        }
      }
      val delta = expectDelta()
      delta.updated should contain theSameElementsAs mapCounterDeltas(element1 -> 7)
      delta.cleared shouldBe false
      delta.removed shouldBe empty
      delta.added shouldBe empty
    }

    "send added, removed and updated elements" in {
      update { s =>
        s :+ (element1 -> (GCounter.empty :+ 2)) :+ (element2 -> (GCounter.empty :+ 5))
      }
      createAndExpectInit()

      update { s =>
        ((s remove element2) :+ (element3 -> GCounter.empty))
          .updated(selfUniqueAddress, element1, GCounter.empty) {
            case gcounter: GCounter => gcounter :+ 7
          }
      }
      val delta = expectDelta()
      delta.added should contain theSameElementsAs mapCounterEntries(element3 -> 0)
      delta.removed should contain theSameElementsAs Seq(element2)
      delta.updated should contain theSameElementsAs mapCounterDeltas(element1 -> 7)
      delta.cleared shouldBe false
    }

    "detect and handle CRDT value type changes" in {
      update { _ :+ (element1 -> (GCounter.empty :+ 2)) }
      createAndExpectInit()
      update { _ remove element1 }
      update { _ :+ (element1 -> (PNCounter.empty :+ 5)) }
      val delta = expectDelta()
      delta.removed should contain theSameElementsAs Seq(element1)
      // Usually we should only get one delta, but there's a race condition that means we could get the readded elements in another delta
      val added =
        if (delta.added.isEmpty) expectDelta().added
        else delta.added
      added should contain theSameElementsAs Seq(
        ORMapEntry(Some(element1), Some(CrdtState(CrdtState.State.Pncounter(PNCounterState(5)))))
      )
    }

    "detect and handle incompatible CRDT value changes" in {
      update { _ :+ (element1 -> (GCounter.empty :+ 5)) }
      createAndExpectInit()
      update { _ remove element1 }
      update { _ :+ (element1 -> (GCounter.empty :+ 3)) }
      val delta = expectDelta()
      delta.removed should contain theSameElementsAs Seq(element1)
      // Usually we should only get one delta, but there's a race condition that means we could get the readded elements in another delta
      val added =
        if (delta.added.isEmpty) expectDelta().added
        else delta.added
      added should contain theSameElementsAs mapCounterEntries(element1 -> 3)
    }

    "handle add deltas" in {
      update { s =>
        s :+ (element1 -> (GCounter.empty :+ 2))
      }
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, updateMap(added = Seq(element2 -> createCounter(5))))
      eventually {
        verifyMapHasCounters(element1 -> 2, element2 -> 5)
      }
      toUserFunction.expectNoMessage(200.millis)
    }

    "handle remove deltas" in {
      update { s =>
        s :+ (element1 -> (GCounter.empty :+ 2)) :+ (element2 -> (GCounter.empty :+ 5))
      }
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, updateMap(removed = Seq(element2)))
      eventually {
        verifyMapHasCounters(element1 -> 2)
      }
      toUserFunction.expectNoMessage(200.millis)
    }

    "handle update deltas" in {
      update { s =>
        s :+ (element1 -> (GCounter.empty :+ 2)) :+ (element2 -> (GCounter.empty :+ 5))
      }
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, updateMap(updated = Seq(element2 -> updateCounter(3))))
      eventually {
        verifyMapHasCounters(element1 -> 2, element2 -> 8)
      }
      toUserFunction.expectNoMessage(200.millis)
    }

    "handle clear deltas" in {
      update { s =>
        s :+ (element1 -> (GCounter.empty :+ 2)) :+ (element2 -> (GCounter.empty :+ 5))
      }
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid, updateMap(cleared = true))
      eventually {
        verifyMapHasCounters()
      }
      toUserFunction.expectNoMessage(200.millis)
    }

    "handle combination deltas" in {
      update { s =>
        s :+ (element1 -> (GCounter.empty :+ 2)) :+ (element2 -> (GCounter.empty :+ 5))
      }
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid,
                         updateMap(updated = Seq(element1 -> updateCounter(4)),
                                   removed = Seq(element2),
                                   added = Seq(element3 -> createCounter(3))))
      eventually {
        verifyMapHasCounters(element1 -> 6, element3 -> 3)
      }
      toUserFunction.expectNoMessage(200.millis)
    }

  }
}
