package com.lightbend.statefulserverless

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

class WarmupSpec extends TestKit(ActorSystem("WarmupSpec", ConfigFactory.load("test-in-memory")))
  with WordSpecLike with BeforeAndAfterAll with ImplicitSender {

  override protected def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "The Warmup Actor" should {
    "successfully complete warmup" in {
      val warmup = system.actorOf(Warmup.props)
      awaitCond({
        warmup ! Warmup.Ready
        expectMsgType[Boolean]
      })
    }
  }
}
