package io.cloudstate.proxy.crdt

import akka.cluster.ddata.{GCounter, GCounterKey, PNCounter, PNCounterKey}
import io.cloudstate.crdt._
import io.cloudstate.entity.UserFunctionError

import scala.concurrent.duration._


class CrdtEntitySpec extends AbstractCrdtEntitySpec {

  import AbstractCrdtEntitySpec._

  // We just use a PNCounter for testing of non CRDT specific functionality
  override protected type T = PNCounter
  override protected type S = PNCounterState
  override protected type D = PNCounterDelta

  override protected def key(name: String) = PNCounterKey(name)

  override protected def initial = PNCounter.empty

  override protected def extractState(state: CrdtState.State) = state.pncounter.value

  override protected def extractDelta(delta: CrdtDelta.Delta) = delta.pncounter.value

  def updateCounter(update: Long) = {
    CrdtReply.Action.Update(CrdtDelta(CrdtDelta.Delta.Pncounter(PNCounterDelta(update))))
  }

  "The CrdtEntity" should {

    "drop all updates received while a command is being handled" in {
      update(_ :+ 5)
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      update(_ :+ 3)
      toUserFunction.expectNoMessage(200.millis)
      update(_ :+ -3)
      toUserFunction.expectNoMessage(200.millis)
      sendAndExpectReply(cid, updateCounter(2))
      toUserFunction.expectNoMessage(200.millis)
    }

    "send missed updates once a command has been handled" in {
      update(_ :+ 5)
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      update(_ :+ 3)
      toUserFunction.expectNoMessage(200.millis)
      update(_ :+ 6)
      toUserFunction.expectNoMessage(200.millis)
      sendAndExpectReply(cid, updateCounter(2))
      expectDelta().change shouldBe 9
    }

    "send missed updates once a command has been handled with more than local consitency" in {
      update(_ :+ 5)
      createAndExpectInit()
      val cid = sendAndExpectCommand("cmd", command)
      update(_ :+ 3)
      toUserFunction.expectNoMessage(200.millis)
      update(_ :+ 6)
      toUserFunction.expectNoMessage(200.millis)
      sendAndExpectReply(cid, updateCounter(2), CrdtReply.WriteConsistency.ALL)
      expectDelta().change shouldBe 9
    }

    "not send missed updates if there is still another command being handled" in {
      update(_ :+ 5)
      createAndExpectInit()
      val cid1 = sendAndExpectCommand("cmd", command)
      update(_ :+ 3)
      toUserFunction.expectNoMessage(200.millis)
      update(_ :+ 6)
      toUserFunction.expectNoMessage(200.millis)
      val cid2 = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid1, updateCounter(2))
      toUserFunction.expectNoMessage(200.millis)
      sendAndExpectReply(cid2, updateCounter(4))
      expectDelta().change shouldBe 9
    }

    "not send missed updates if there is still another command being handled with more than local consistency" in {
      update(_ :+ 5)
      createAndExpectInit()
      val cid1 = sendAndExpectCommand("cmd", command)
      update(_ :+ 3)
      toUserFunction.expectNoMessage(200.millis)
      update(_ :+ 6)
      toUserFunction.expectNoMessage(200.millis)
      val cid2 = sendAndExpectCommand("cmd", command)
      sendAndExpectReply(cid1, updateCounter(2), CrdtReply.WriteConsistency.ALL)
      toUserFunction.expectNoMessage(200.millis)
      sendAndExpectReply(cid2, updateCounter(4))
      expectDelta().change shouldBe 9
    }


  }
}
