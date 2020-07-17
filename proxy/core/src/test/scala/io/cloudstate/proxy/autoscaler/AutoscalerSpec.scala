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

package io.cloudstate.proxy.autoscaler

import akka.actor.{ActorRef, ActorSystem, Address, PoisonPill}
import akka.cluster.{Cluster, UniqueAddress}
import akka.cluster.ddata.Replicator.{Get, GetSuccess, ReadLocal, Update, UpdateSuccess, WriteLocal}
import akka.cluster.ddata.{DistributedData, LWWRegister, LWWRegisterKey}
import akka.testkit.{ImplicitSender, TestKit}
import io.cloudstate.proxy.autoscaler.Autoscaler._
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration._

class AutoscalerSpec
    extends TestKit(ActorSystem("AutoscalerSpec", ConfigFactory.load("test-in-memory")))
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with ImplicitSender {

  // Start cluster with ourselves, necessary for ddata
  val cluster = Cluster(system)
  cluster.join(cluster.selfAddress)
  val ddata = DistributedData(system)
  val replicator = ddata.replicator

  import ddata.selfUniqueAddress
  val StateKey = LWWRegisterKey[AutoscalerState]("autoscaler")
  val EmptyState = LWWRegister.create[AutoscalerState](WaitingForState)

  override protected def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private def uniqueAddress(name: String): UniqueAddress =
    UniqueAddress(Address("akka", "system", Some(name), Some(2552)), 1L)

  private def addressString(name: String) = uniqueAddress(name).address.toString

  private def withAutoscaler(
      initialState: AutoscalerState = Stable(),
      members: Seq[String] = Seq("a"),
      targetUserFunctionConcurrency: Int = 1,
      targetRequestConcurrency: Int = 10,
      targetConcurrencyWindow: FiniteDuration = 60.seconds,
      scaleUpStableDeadline: FiniteDuration = 2.minutes,
      scaleDownStableDeadline: FiniteDuration = 30.seconds,
      requestRateThresholdFactor: Double = 1.5,
      requestRateThresholdWindow: FiniteDuration = 6.seconds,
      maxScaleFactor: Double = 0,
      maxScaleAbsolute: Int = 4,
      maxMembers: Int = 10
  )(block: ActorRef => Unit): Unit = {
    ensureScalerStateIs(initialState)

    val settings = AutoscalerSettings(
      enabled = true,
      targetUserFunctionConcurrency = targetUserFunctionConcurrency,
      targetRequestConcurrency = targetRequestConcurrency,
      targetConcurrencyWindow = targetConcurrencyWindow,
      scaleUpStableDeadline = scaleUpStableDeadline,
      scaleDownStableDeadline = scaleDownStableDeadline,
      requestRateThresholdFactor = requestRateThresholdFactor,
      requestRateThresholdWindow = requestRateThresholdWindow,
      maxScaleFactor = maxScaleFactor,
      maxScaleAbsolute = maxScaleAbsolute,
      maxMembers = maxMembers,
      tickPeriod = 1.hour
    )

    val clusterMembershipFacade = new ClusterMembershipFacade {
      override def upMembers: Iterable[UniqueAddress] = members.map(uniqueAddress)
    }

    val autoscaler = watch(system.actorOf(Autoscaler.props(settings, (_, _) => testActor, clusterMembershipFacade)))

    autoscaler ! Deployment("name", members.size, members.size, false)

    try {
      block(autoscaler)
    } finally {
      autoscaler ! PoisonPill
      expectTerminated(autoscaler)
    }
  }

  def ensureScalerStateIs(state: AutoscalerState) = {
    replicator ! Update(StateKey, EmptyState, WriteLocal)(_.withValueOf(state))
    expectMsg(UpdateSuccess(StateKey, None))
  }

  def expectScalerStateToBe(pf: PartialFunction[Any, Unit]) =
    within(3.seconds) {
      replicator ! Get(StateKey, ReadLocal)
      expectMsgPF() {
        case success @ GetSuccess(StateKey, None) =>
          pf.isDefinedAt(success.get(StateKey).value)
      }

    }

  private def aLongTimeFromNowMillis = System.currentTimeMillis() + 1.hour.toMillis

  private def metrics(
      address: String = "a",
      metricInterval: FiniteDuration = 1.second,
      requestConcurrency: Double = 5.0,
      requestTime: FiniteDuration = 10.millis,
      requestCount: Int = 200,
      userFunctionConcurrency: Double = 0.5,
      userFunctionTime: FiniteDuration = 5.millis,
      userFunctionCount: Int = 200,
      databaseConcurrency: Double = 0.0,
      databaseTime: FiniteDuration = Duration.Zero,
      databaseCount: Int = 0
  ): AutoscalerMetrics =
    AutoscalerMetrics(
      address = addressString(address),
      uniqueAddressLongId = 1L,
      metricIntervalNanos = metricInterval.toNanos,
      requestConcurrency = requestConcurrency,
      requestTimeNanos = requestTime.toNanos,
      requestCount = requestCount,
      userFunctionConcurrency = userFunctionConcurrency,
      userFunctionTimeNanos = userFunctionTime.toNanos,
      userFunctionCount = userFunctionCount,
      databaseConcurrency = databaseConcurrency,
      databaseTimeNanos = databaseTime.toNanos,
      databaseCount = databaseCount
    )

  "The Autoscaler" should {
    "do nothing when there is one node and metrics are below thresholds" in withAutoscaler() { autoscaler =>
      autoscaler ! metrics()
      expectNoMessage(300.millis)
    }

    "scale up when user function concurrency exceeds the threshold" in withAutoscaler() { autoscaler =>
      autoscaler ! metrics(
        userFunctionConcurrency = 1.1
      )
      expectMsg(Scale("name", 2))
      expectScalerStateToBe { case ScalingUp(2, 200, _, _) => }
    }

    "scale up directly to meet demand required by concurrency" in withAutoscaler() { autoscaler =>
      autoscaler ! metrics(
        userFunctionConcurrency = 2.2
      )
      expectMsg(Scale("name", 3))
      expectScalerStateToBe { case ScalingUp(3, 200, _, _) => }
    }

    "not exceed absolute scaling cap when scaling for concurrency" in withAutoscaler() { autoscaler =>
      autoscaler ! metrics(
        userFunctionConcurrency = 6
      )
      expectMsg(Scale("name", 5))
      expectScalerStateToBe { case ScalingUp(5, 200, _, _) => }
    }

    "not exceed scaling factor cap when scaling for concurrency" in withAutoscaler(
      maxScaleFactor = 2.0
    ) { autoscaler =>
      autoscaler ! metrics(
        userFunctionConcurrency = 6
      )
      expectMsg(Scale("name", 3))
      expectScalerStateToBe { case ScalingUp(3, 200, _, _) => }
    }

    "aggregate from multiple metrics" in withAutoscaler() { autoscaler =>
      autoscaler ! metrics()
      autoscaler ! metrics()
      autoscaler ! metrics()
      autoscaler ! Tick
      expectNoMessage(300.millis)
      autoscaler ! metrics(
        userFunctionConcurrency = 3
      )
      // Average concurrency now should be > 1, so it should scale to two
      autoscaler ! Tick
      expectMsg(Scale("name", 2))
      // Average request rate should be 200, because all samples were from the same member
      expectScalerStateToBe { case ScalingUp(2, 200, _, _) => }
    }

    "aggregate from multiple cluster members" in withAutoscaler(
      members = Seq("a", "b", "c")
    ) { autoscaler =>
      autoscaler ! metrics()
      autoscaler ! metrics(
        address = "b",
        userFunctionConcurrency = 1.0,
        requestCount = 300
      )
      autoscaler ! metrics(
        address = "c",
        userFunctionConcurrency = 2.0,
        requestCount = 400
      )
      // Average concurrency now should be > 1, so it should scale up, and request rate should be average of the
      // above
      expectMsg(Scale("name", 4))
      expectScalerStateToBe { case ScalingUp(4, 300, _, _) => }
    }

    "take into account cluster size when scaling up" in withAutoscaler(
      members = Seq("a", "b", "c")
    ) { autoscaler =>
      // Purpose of this test is to ensure that the desired number of nodes to scale to is not just based on the
      // average, but the average multiplied by the number of nodes in the cluster.
      autoscaler ! metrics(
        userFunctionConcurrency = 1.0
      )
      autoscaler ! metrics(
        address = "b",
        userFunctionConcurrency = 1.0
      )
      autoscaler ! metrics(
        address = "c",
        userFunctionConcurrency = 2.2
      )
      expectMsg(Scale("name", 5))
      expectScalerStateToBe { case ScalingUp(5, 200, _, _) => }
    }

    "not make new scale up decisions based on concurrency while scaling" in withAutoscaler(
      initialState = ScalingUp(2, 400, aLongTimeFromNowMillis),
      members = Seq("a", "b")
    ) { autoscaler =>
      autoscaler ! metrics(
        userFunctionConcurrency = 6
      )
      autoscaler ! metrics(
        address = "b",
        userFunctionConcurrency = 6
      )
      expectNoMessage(300.millis)
    }

  }

}
