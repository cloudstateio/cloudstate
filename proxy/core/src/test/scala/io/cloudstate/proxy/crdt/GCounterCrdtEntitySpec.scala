package io.cloudstate.proxy.crdt

import akka.cluster.ddata.{GCounter, GCounterKey}
import io.cloudstate.crdt._
import io.cloudstate.entity.UserFunctionError

import scala.concurrent.duration._


class GCounterCrdtEntitySpec extends AbstractCrdtEntitySpec {

  import AbstractCrdtEntitySpec._

  override protected type T = GCounter
  override protected type S = GCounterState
  override protected type D = GCounterDelta

  override protected def key(name: String) = GCounterKey(name)

  override protected def initial = GCounter.empty

  override protected def extractState(state: CrdtState.State) = state.gcounter.value

  override protected def extractDelta(delta: CrdtDelta.Delta) = delta.gcounter.value

  def create(value: Long) = {
    CrdtReply.Action.Create(CrdtState(CrdtState.State.Gcounter(GCounterState(value))))
  }

  def updateCounter(increment: Long) = {
    CrdtReply.Action.Update(CrdtDelta(CrdtDelta.Delta.Gcounter(GCounterDelta(increment))))
  }

  "The GCounter CrdtEntity" should {

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
      expectDelta().increment shouldBe 3
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

    "refuse to decrement a GCounter" in {
      update(_ :+ 5)
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      sendAndExpectFailure(cid, updateCounter(-3))
      entityDiscovery.expectMsgType[UserFunctionError]
      get().value.toLong shouldBe 5
    }

  }
}
