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

package io.cloudstate.proxy.eventing

import java.net.ServerSocket
import java.util.concurrent.{CompletionStage, TimeUnit}

import com.typesafe.config.Config
import akka.Done
import akka.actor.{ActorSystem, ExtendedActorSystem}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import io.cloudstate.proxy.{CloudStateProxyMain, EntityDiscoveryManager}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Second, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}

import scala.concurrent.duration._
import io.cloudstate.pingpong._
import io.cloudstate.samples.pingpong._
import io.cloudstate.javasupport.{CloudState, CloudStateRunner}

/**
 This test requires a running Google Pubsub Emulator,
 easiest is to start it in an emulator, just don't forget to stop the image.

 `docker run --rm --expose=8085 --volume=/data --name=googlepubsub -d -p 8085:8085 google/cloud-sdk:latest /bin/sh -c "gcloud beta emulators pubsub start --project=test --host-port=0.0.0.0:8085 --data-dir=/data"`
 */
class GooglePubsubSpec extends WordSpec with BeforeAndAfterAll with Eventually with Matchers with ScalaFutures {
  /*
  lazy val projectId = System.getenv("PUBSUB_PROJECT_ID")

  final def runTheseTests: Boolean = projectId != null

  // We only need to start one user function, since the multiple Akka nodes can all connect to it, it doesn't make
  // a difference whether they use different services or the same.
  var userFunction: CloudStateRunner = _
  var userFunctionDone: CompletionStage[Done] = _
  var node: ActorSystem = _
  var testSystem: ActorSystem = _
  var support: GCPubsubEventingSupport = _
  var materializer: ActorMaterializer = _

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(Span(20, Seconds), Span(1, Second))

  "Google Pubsub Eventing support" should {
    implicit def implicitTestSystem = testSystem
    implicit def implicitMaterializer = materializer
    implicit def implicitDispatcher = implicitTestSystem.dispatcher
    "be tested" in {
      assume(runTheseTests)
    }
  }

  def startUserFunction(config: Config): CloudStateRunner =
    new CloudState()
      .registerEventSourcedEntity(classOf[PingPongEntity],
                                  Pingpong.getDescriptor.findServiceByName("PingPongService"),
                                  Pingpong.getDescriptor)
      .createRunner(config)

  override protected def beforeAll(): Unit = if (runTheseTests) {
    val user_function_port = freePort()

    implicit val s = ActorSystem(
      "GooglePubsubSpec",
      ConfigFactory.parseString("""
        |akka.remote.netty.tcp.port = 0
        |akka.remote.artery.canonical.port = 0
        |akka.remote.artery.bind.port = 0
        |akka.coordinated-shutdown.exit-jvm = off
        |akka.jvm-exit-on-fatal-error = false
      """.stripMargin).withFallback(ConfigFactory.defaultReference()).resolve()
    )
    implicit val m = ActorMaterializer()
    implicit val d = s.dispatcher

    val pubsub =
      EventingManager
        .createSupport(ConfigFactory.load("googlepubsub.conf").resolve())
        .collect({ case g: GCPubsubEventingSupport => g })
        .getOrElse(throw new IllegalStateException("Unable to create EventingSupport for Google Pubsub"))

    testSystem = s
    materializer = m
    support = pubsub

    userFunction = startUserFunction(
      ConfigFactory.parseString(s"""
        |cloudstate.user-function-interface = "0.0.0.0"
        |cloudstate.user-function-port = ${user_function_port}
        |cloudstate.eventsourced.snapshot-every = 100
      """.stripMargin).withFallback(ConfigFactory.defaultReference()).resolve()
    )

    userFunctionDone = userFunction.run()

    node = CloudStateProxyMain.start(
      ConfigFactory.load("dev-mode.conf").withFallback(ConfigFactory.defaultReference()).resolve()
    )

    akka.serialization.JavaSerializer.currentSystem.withValue(testSystem.asInstanceOf[ExtendedActorSystem]) {
      import akka.pattern.ask
      implicit val timeout = Timeout(1.seconds)
      val manager = node.actorSelection(node / "server-manager-supervisor" / "server-manager")
      eventually {
        manager.resolveOne(timeout.duration).flatMap(_ ? EntityDiscoveryManager.Ready).futureValue should ===(true)
      }
    }
  }

  override protected def afterAll(): Unit = {
    Option(userFunction).foreach(_.terminate().toCompletableFuture().get(10, TimeUnit.SECONDS)) // FIXME timeout?
    Option(testSystem).foreach(_.terminate())
    Option(node).foreach(_.terminate())
  }

  private def freePort(): Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }

 */
}
