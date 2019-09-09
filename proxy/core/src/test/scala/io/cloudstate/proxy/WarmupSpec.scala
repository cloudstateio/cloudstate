package io.cloudstate.proxy

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

class WarmupSpec
    extends TestKit(ActorSystem("WarmupSpec", ConfigFactory.load("test-in-memory")))
    with WordSpecLike
    with BeforeAndAfterAll
    with ImplicitSender {

  override protected def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "The Warmup Actor" should {
    "successfully complete warmup when needsWarmup is true" in {
      val warmup = system.actorOf(Warmup.props(true))
      awaitCond({
        warmup ! Warmup.Ready
        expectMsgType[Boolean]
      })
    }

    "successfully complete warmup when needsWarmup is false" in {
      val warmup = system.actorOf(Warmup.props(false))
      awaitCond({
        warmup ! Warmup.Ready
        expectMsgType[Boolean]
      })
    }
  }
}
