package io.cloudstate.proxy.eventing

import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}

import akka.{Done, NotUsed}
import akka.actor.{ActorRef, ActorSystem, ExtendedActorSystem}
import akka.cluster.Cluster
import akka.grpc.GrpcClientSettings
import akka.pattern.{BackoffOpts, BackoffSupervisor}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import io.cloudstate.proxy.{CloudStateProxyMain, EntityDiscoveryManager}
import org.scalatest.concurrent.{Eventually, Futures, ScalaFutures}
import org.scalatest.time.{Millis, Second, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.sys.process.Process
import scala.util.Random

/**
 This test requires a running Google Pubsub Emulator,
 easiest is to start it in an emulator, just don't forget to stop the image.

 `docker run --rm --expose=8085 --volume=/data --name=googlepubsub -d -p 8085:8085 google/cloud-sdk:latest /bin/sh -c "gcloud beta emulators pubsub start --project=test --host-port=0.0.0.0:8085 --data-dir=/data"`
 */
class GooglePubsubSpec extends WordSpec with BeforeAndAfterAll with Eventually with Matchers with ScalaFutures {
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
  val crdtExampleDir = new File(new File(baseDir, "samples"), "js-shopping-cart")

  // We only need to start one user function, since the multiple Akka nodes can all connect to it, it doesn't make
  // a difference whether they use different services or the same.
  var userFunction: Process = _
  var node: ActorSystem = _
  var testSystem: ActorSystem = _
  var support: GCPubsubEventingSupport = _
  var materializer: ActorMaterializer = _

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(Span(20, Seconds), Span(1, Second))

  "Google Pubsub Eventing support" should {
    implicit def implicitTestSystem = testSystem
    implicit def implicitMaterializer = materializer
    implicit def implicitDispatcher = implicitTestSystem.dispatcher
    "be tested" in pending // FIXME ADD TESTS HERE
  }

  override protected def beforeAll(): Unit = {
    val projectId = "test"
    val pubsub_port = 8085
    val pubsub_host = "localhost"

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
        .createSupport(ConfigFactory.parseString(s"""
        |support = "google-pubsub"
        |google-pubsub.host = "${pubsub_host}"
        |google-pubsub.port = ${pubsub_port}
        |google-pubsub.rootCa = "none"
        |google-pubsub.callCredentials = "none"
        |google-pubsub.project-id = "${projectId}"
        |google-pubsub.poll-interval = 1s
        |google-pubsub.upstream-ack-deadline = 10s
        |google-pubsub.downstream-batch-deadline = 5s
        |google-pubsub.downstream-batch-size = 10
        |google-pubsub.manage-topics-and-subscriptions = "by-proxy"
      """.stripMargin))
        .collect({ case g: GCPubsubEventingSupport => g })
        .getOrElse(throw new IllegalStateException("Unable to create EventingSupport for Google Pubsub"))

    testSystem = s
    materializer = m
    support = pubsub

    userFunction = Process(Seq("node", "index.js"), crdtExampleDir).run()

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
    Option(userFunction).foreach(_.destroy())
    Option(testSystem).foreach(_.terminate())
    Option(node).foreach(_.terminate())
  }

  private def freePort(): Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }

}
