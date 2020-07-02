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

package io.cloudstate.proxy.stress

import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicLong

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.Cluster
import akka.grpc.GrpcClientSettings
import akka.pattern.{BackoffOpts, BackoffSupervisor}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import com.example.crdts.crdt_example.{CrdtExampleClient, Get, MutateSet, SomeValue, UpdateCounter}
import com.typesafe.config.ConfigFactory
import io.cloudstate.proxy.EntityDiscoveryManager
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Second, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}
import org.scalatest.{Ignore, Tag}

import scala.collection.concurrent.TrieMap
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.sys.process.Process
import scala.util.Random

object WhenStressing
    extends Tag(
      if (System.getProperty("RUN_STRESS_TESTS", "false") == "true") "WhenStressing" else classOf[Ignore].getName
    )

/**
 * This class attempts to stress test the CRDT support.
 *
 * The idea is, start several nodes, then hammer a single CRDT with lots of updates, and ensure that at the end,
 * the state of the CRDT is the same on every node, and is what we expect it to be.
 */
class CrdtStressSpec extends WordSpec with BeforeAndAfterAll with Eventually with Matchers with ScalaFutures {

  val numberOfNodes = sys.props.getOrElse("num-nodes", "3").toInt
  val parallelism = sys.props.getOrElse("parallelism", "500").toInt
  // Working directory should be the base directory of the sbt build, but in case it's not, try walking up the
  // directory hierarchy until we find the the samples directory.
  val baseDir = {
    @annotation.tailrec
    def locateBaseDir(dir: File): File =
      if (new File(dir, "samples").isDirectory) {
        dir
      } else if (dir.getParentFile == null) {
        sys.error("Could not locate base directory")
      } else locateBaseDir(dir.getParentFile)
    locateBaseDir(new File("."))
  }
  val nodeSupportDir = new File(baseDir, "node_support")
  val crdtExampleDir = new File(new File(baseDir, "samples"), "js-crdts")

  // We only need to start one user function, since the multiple Akka nodes can all connect to it, it doesn't make
  // a difference whether they use different services or the same.
  var userFunction: Process = _
  var nodes: Seq[(ActorSystem, CrdtExampleClient)] = _

  private def getClient(index: Long) =
    nodes((index % numberOfNodes).toInt)._2

  val config = ConfigFactory.parseString("""
        |akka.coordinated-shutdown.exit-jvm = off
        |akka.jvm-exit-on-fatal-error = false
      """.stripMargin).withFallback(ConfigFactory.defaultReference()).resolve()

  implicit val testSystem = ActorSystem("CrdtStressSpec", config)
  implicit val mat = ActorMaterializer()
  import testSystem.dispatcher

  private def reportProgress(total: Int): Flow[Int, Int, NotUsed] = {
    val reportEvery = total / 100
    Flow[Int]
      .map { i =>
        if (i % reportEvery == 0) {
          print("\r" + (i / reportEvery) + "%")
        }
        i
      }
      .watchTermination() { (_, terminated) =>
        terminated.onComplete(_ => println("\r100%"))
        NotUsed
      }
  }

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(Span(20, Seconds), Span(1, Second))

  "CRDT stress tests" should {

    "work for GCounter" taggedAs (WhenStressing) in {
      val countTo = sys.props.getOrElse("gcounter-count-to", "1000").toInt
      println(s"GCounter counting to $countTo...")
      Await.result(
        Source(1 to countTo)
          .mapAsync(parallelism) { idx =>
            getClient(Random.nextInt(numberOfNodes))
              .incrementGCounter(UpdateCounter("gcounter-count-to", 1))
              .map(_ => idx)
          }
          .via(reportProgress(countTo))
          .runWith(Sink.ignore),
        10.minutes
      )

      for (i <- 0 until numberOfNodes) {
        eventually {
          getClient(i).getGCounter(Get("gcounter-count-to")).futureValue.value should ===(countTo)
        }
      }
    }

    "work for PNCounter" taggedAs (WhenStressing) in {
      val pnCounterOps = sys.props.getOrElse("pncounter-ops", "1000").toInt
      val local = new AtomicLong()
      println(s"PNCounter doing $pnCounterOps ops...")
      Await.result(
        Source(1 to pnCounterOps)
          .mapAsync(parallelism) { idx =>
            val update = Random.nextInt(10) - 5
            local.getAndAdd(update)
            getClient(Random.nextInt(numberOfNodes))
              .updatePNCounter(UpdateCounter("pncounter", update))
              .map(_ => idx)
          }
          .via(reportProgress(pnCounterOps))
          .runWith(Sink.ignore),
        10.minutes
      )

      for (i <- 0 until numberOfNodes) {
        eventually {
          getClient(i).getPNCounter(Get("pncounter")).futureValue.value should ===(local.get())
        }
      }
    }

    "work for GSet" taggedAs (WhenStressing) in {
      val gsetOps = sys.props.getOrElse("gset-ops", "1000").toInt
      val gsetElements = sys.props.getOrElse("gset-elements", "500").toInt

      val local = new TrieMap[Int, SomeValue]()
      println(s"GSet doing $gsetOps ops with up to $gsetElements elements...")
      Await.result(
        Source(1 to gsetOps)
          .mapAsync(parallelism) { idx =>
            val itemId = Random.nextInt(gsetElements)
            val value = SomeValue(itemId.toString, "this is a lovely description of " + itemId.toString)
            local.put(itemId, value)
            getClient(Random.nextInt(numberOfNodes))
              .mutateGSet(MutateSet(key = "gset", add = Seq(value)))
              .map(_ => idx)
          }
          .via(reportProgress(gsetOps))
          .runWith(Sink.ignore),
        10.minutes
      )

      val expected = local.values.toSet

      for (i <- 0 until numberOfNodes) {
        eventually {
          val items = getClient(i).getGSet(Get("gset")).futureValue.items
          items should contain theSameElementsAs expected
        }
      }
    }

    "work for ORSet" taggedAs (WhenStressing) in {
      val orsetOps = sys.props.getOrElse("orset-ops", "10000").toInt
      val orsetElements = sys.props.getOrElse("orset-elements", "1000").toInt

      val local = new TrieMap[Int, SomeValue]()
      println(s"ORSet doing $orsetOps ops with up to $orsetElements elements...")
      Await.result(
        Source(0 until orsetOps)
          .mapAsync(parallelism) { idx =>
            val itemId = idx % orsetElements
            val clientId = itemId % numberOfNodes
            val value = SomeValue(itemId.toString, "this is a lovely description of " + itemId.toString)
            val op = if (((idx / orsetElements) & 1) == 0 || (itemId & 1) == 1) {
              local.put(itemId, value)
              MutateSet(key = "orset", add = Seq(value))
            } else {
              local.remove(itemId)
              MutateSet(key = "orset", remove = Seq(value))
            }
            getClient(clientId)
              .mutateORSet(op)
              .map(_ => idx)
          }
          .via(reportProgress(orsetOps))
          .runWith(Sink.ignore),
        10.minutes
      )

      val expected = local.values.toSet

      for (i <- 0 until numberOfNodes) {
        eventually {
          val theset = getClient(i).getORSet(Get("orset")).futureValue.items
          theset should contain theSameElementsAs expected
        }
      }
    }

  }

  override protected def beforeAll(): Unit = if (WhenStressing.name == "WhenStressing") {
    userFunction = Process(Seq("node", "index.js"), crdtExampleDir).run()

    nodes = (1 to numberOfNodes).foldLeft(Seq.empty[(ActorSystem, CrdtExampleClient)]) { (systems, node) =>
      val httpPort = freePort()

      println(s"Starting node $node on port $httpPort")

      val (manager, system) = CrdtStressSpec.startNode(httpPort, systems.headOption.map(_._1))
      // Wait for it to be ready before moving onto the next one
      import akka.pattern.ask
      implicit val timeout = Timeout(1.seconds)
      eventually {
        (manager ? EntityDiscoveryManager.Ready).futureValue should ===(true)
      }

      systems :+ (system, createClient(httpPort))
    }
  }

  private def createClient(port: Int) =
    CrdtExampleClient(GrpcClientSettings.connectToServiceAt("localhost", port).withTls(false))

  override protected def afterAll(): Unit = {
    Option(userFunction).foreach(_.destroy())
    Option(testSystem).foreach(_.terminate())
    Option(nodes).foreach(_.foreach {
      case (system, client) =>
        client.close()
        system.terminate()
    })
  }

  private def freePort(): Int = {
    val socket = new ServerSocket(0)
    try {
      socket.getLocalPort
    } finally {
      socket.close()
    }
  }

}

object CrdtStressSpec {
  def startNode(httpPort: Int, seedNode: Option[ActorSystem]): (ActorRef, ActorSystem) = {
    val config = ConfigFactory.parseString("""
        |include "cloudstate-common"
        |
        |akka.remote.netty.tcp.port = 0
        |akka.remote.artery.canonical.port = 0
        |akka.remote.artery.bind.port = 0
        |akka.coordinated-shutdown.exit-jvm = off
        |akka.jvm-exit-on-fatal-error = false
      """.stripMargin).withFallback(ConfigFactory.defaultReference()).resolve()
    implicit val system = ActorSystem("cloudstate-proxy", config)
    implicit val materializer = ActorMaterializer()

    val c = config.getConfig("cloudstate.proxy")
    val serverConfig = new EntityDiscoveryManager.Configuration(c).copy(httpPort = httpPort, devMode = true)

    val cluster = Cluster(system)

    seedNode match {
      case None =>
        // First node joins itself
        cluster.join(cluster.selfAddress)
      case Some(node) =>
        // Otherwise, join the first node
        cluster.join(Cluster(node).selfAddress)
    }

    // Start it
    val manager = system.actorOf(
      BackoffSupervisor.props(
        BackoffOpts.onFailure(
          EntityDiscoveryManager.props(serverConfig),
          childName = "entity-manager",
          minBackoff = 1.second,
          maxBackoff = 30.seconds,
          randomFactor = 0.2
        )
      ),
      "server-manager-supervisor"
    )

    (manager, system)
  }
}
