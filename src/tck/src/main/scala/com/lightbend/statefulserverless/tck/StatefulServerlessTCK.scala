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

package com.lightbend.statefulserverless.tck

import akka.NotUsed
import akka.actor.{ActorSystem, Scheduler}
import akka.stream.{Materializer, ActorMaterializer}
import akka.stream.scaladsl.{Source, Sink}
import akka.pattern.after

import akka.grpc.GrpcClientSettings
import com.google.protobuf.{ByteString => ProtobufByteString}

import org.scalatest._
import com.typesafe.config.Config

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import scala.util.{Try}

import java.util.{Map => JMap}
import java.util.concurrent.TimeUnit
import java.lang.{ProcessBuilder, Process}
import java.io.File

import akka.http.scaladsl.{Http, HttpConnectionContext, UseHttp2}
import akka.http.scaladsl.Http.ServerBinding

import com.lightbend.statefulserverless.grpc._
import com.example.shoppingcart._

import akka.testkit.{ TestActors, TestKit, TestProbe }

import com.google.protobuf.empty.Empty

object StatefulServerlessTCK {
  private[this] final val BACKEND   = "backend"
  private[this] final val FRONTEND  = "frontend"
  private[this] final val TCK       = "tck"
  private[this] final val HOSTNAME  = "hostname"
  private[this] final val PORT      = "port"
  private[this] final val NAME      = "name"

  final case class ProcSpec private(
    hostname: String,
    port: Int,
    directory: File,
    command: Array[String],
    envVars: JMap[String, Object]
  ) {
    def this(config: Config) = this(
      hostname   =          config.getString(HOSTNAME ),
      port       =          config.getInt(   PORT     ),
      directory  = new File(config.getString("directory")),
      command    =          config.getList(  "command").unwrapped.toArray.map(_.toString),
      envVars    =          config.getConfig("env-vars").root.unwrapped,
    )
    def validate(): Unit = {
      require(directory.exists, s"Configured directory (${directory}) does not exist")
      require(directory.isDirectory, s"Configured directory (${directory}) is not a directory")
      require(command.nonEmpty, "Configured command missing")
    }
  }
  final case class Configuration private(
    name: String,
    backend: ProcSpec,
    frontend: ProcSpec,
    tckHostname: String,
    tckPort: Int) {
    def this(config: Config) = this(
      name        = config.getString(NAME),
      backend     = new ProcSpec(config.getConfig(BACKEND)),
      frontend    = new ProcSpec(config.getConfig(FRONTEND)),
      tckHostname = config.getString(TCK + "." + HOSTNAME),
      tckPort     = config.getInt(   TCK + "." + PORT)
    )

    def validate(): Unit = {
      backend.validate()
      frontend.validate()
      // FIXME implement
    }
  }

  final val noWait = 0.seconds

  // FIXME add interception to enable asserting exchanges
  final class EntityInterceptor(val client: EntityClient, val fromBackend: TestProbe, val fromFrontend: TestProbe)(implicit ec: ExecutionContext) extends Entity {

    private final val fromBackendInterceptor = Sink.actorRef[AnyRef](fromBackend.ref, "BACKEND_TERMINATED")
    private final val fromFrontendInterceptor = Sink.actorRef[AnyRef](fromFrontend.ref, "FRONTEND_TERMINATED")

    override def handle(in: Source[EntityStreamIn, NotUsed]): Source[EntityStreamOut, NotUsed] =
      client.handle(in.alsoTo(fromBackendInterceptor)).alsoTo(fromFrontendInterceptor)

    override def ready(in: Empty): Future[EntitySpec] = {
      import scala.util.{Success, Failure}
      fromBackend.ref ! in
      client.ready(in).andThen {
        case Success(es) => fromFrontend.ref ! es
        case Failure(f)  => fromFrontend.ref ! f
      }
    }
  }

  def attempt[T](op: => Future[T], delay: FiniteDuration, retries: Int)(implicit ec: ExecutionContext, s: Scheduler): Future[T] =
    Future.unit.flatMap(_ => op) recoverWith { case _ if retries > 0 => after(delay, s)(attempt(op, delay, retries - 1)) }
}

class StatefulServerlessTCK(private[this] final val config: StatefulServerlessTCK.Configuration) extends AsyncWordSpec with MustMatchers with BeforeAndAfterAll {
  import StatefulServerlessTCK._
            private[this] final val system                               = ActorSystem("StatefulServerlessTCK")
            private[this] final val mat                                  = ActorMaterializer()(system)
            private[this] final val fromBackend                          = TestProbe("fromBackend")(system)
            private[this] final val fromFrontend                         = TestProbe("fromFrontend")(system)
  @volatile private[this] final var shoppingClient:  ShoppingCartClient  = _
  @volatile private[this] final var entityClient:    EntityClient        = _
  @volatile private[this] final var backendProcess:  Process             = _
  @volatile private[this] final var frontendProcess: Process             = _
  @volatile private[this] final var tckProxy:        ServerBinding       = _

  final implicit override def executionContext = system match {
    case null => super.executionContext
    case some => some.dispatcher
  }

  def process(ps: ProcSpec): ProcessBuilder = {
    val pb =
      new ProcessBuilder(ps.command:_*).
      inheritIO().
      directory(ps.directory)

    val env = pb.environment

    ps.envVars.entrySet.forEach { e =>
      e.getValue match {
        case value: String => env.put(e.getKey, value)
        case _ => // Ignore
      }
    }
    pb
  }

  def buildTCKProxy(client: EntityClient, implementation: Entity): Future[ServerBinding] = {
    implicit val s = system
    implicit val m = mat
    Http().bindAndHandleAsync(
        handler = EntityHandler(implementation),
        interface = config.tckHostname,
        port = config.tckPort,
        connectionContext = HttpConnectionContext(http2 = UseHttp2.Always)
      )
    }

  override def beforeAll(): Unit = {

    config.validate()

    val fp = process(config.frontend).
               start()

    require(fp.isAlive())

    frontendProcess = fp

    val ec = EntityClient(GrpcClientSettings.connectToServiceAt(config.frontend.hostname, config.frontend.port)(system).withTls(false))(mat, mat.executionContext)

    entityClient = ec

    val tp = Await.result(buildTCKProxy(ec, new EntityInterceptor(ec, fromBackend, fromFrontend)(system.dispatcher)), 10.seconds)

    tckProxy = tp

    val bp = process(config.backend).
              start()

    require(bp.isAlive())

    backendProcess = bp

    val sc = ShoppingCartClient(GrpcClientSettings.connectToServiceAt(config.backend.hostname, config.backend.port)(system).withTls(false))(mat, mat.executionContext)

    shoppingClient = sc
  }

  override final def afterAll(): Unit = {
    def destroy(p: Process): Unit = while(p.isAlive) {
      p.destroy()
      p.waitFor(5, TimeUnit.SECONDS) || {
        p.destroyForcibly()
        true // todo revisit this
      }// todo make configurable
    }
    try
      Option(shoppingClient).foreach(c => Await.result(c.close(), 10.seconds))
    finally
      try Option(backendProcess).foreach(destroy)
        finally Option(entityClient).foreach(c => Await.result(c.close(), 10.seconds))
          try Option(frontendProcess).foreach(destroy)
            finally Await.ready(tckProxy.unbind().transformWith(_ => system.terminate())(system.dispatcher), 30.seconds)
  }

  final def fromFrontend_expectEntitySpec(within: FiniteDuration): EntitySpec = {
    val spec = fromFrontend.expectMsgType[EntitySpec](within)
    spec.proto must not be ProtobufByteString.EMPTY
    spec.serviceName must not be empty
    spec.persistenceId must not be empty
    spec
  }

  final def fromBackend_expectInit(within: FiniteDuration): Init = {
    val init = fromBackend.expectMsgType[EntityStreamIn](noWait)
    init must not be(null)
    init.message must be('init)
    init.message.init must be(defined)
    init.message.init.get
  }

  final def fromBackend_expectCommand(within: FiniteDuration): Command = {
    val command = fromBackend.expectMsgType[EntityStreamIn](noWait)
    command must not be(null)  // FIXME validate Command
    command.message must be('command)
    command.message.command must be(defined)
    val c = command.message.command.get
    c.entityId must not be(empty)
    c
  }

  final def fromFrontend_expectReply(events: Int, within: FiniteDuration): Reply = {
    val reply = fromFrontend.expectMsgType[EntityStreamOut](noWait)
    reply must not be(null)
    reply.message must be('reply)
    reply.message.reply must be(defined)
    val r = reply.message.reply.get
    r.events.size must be (events)
    r
  }

  final def fromFrontend_expectFailure(within: FiniteDuration): Failure = {
    val failure = fromFrontend.expectMsgType[EntityStreamOut](noWait)
    failure must not be(null)
    failure.message must be('failure)
    failure.message.failure must be(defined)
    failure.message.failure.get
  }

  final def correlate(cmd: Command, reply: Reply)     = cmd.id must be(reply.commandId)
  final def correlate(cmd: Command, failure: Failure) = cmd.id must be(failure.commandId)
  final def unrelated(cmd: Command, reply: Reply)     = cmd.id must not be reply.commandId
  final def unrelated(cmd: Command, failure: Failure) = cmd.id must not be failure.commandId

  ("The TCK for" + config.name) must {
    implicit val scheduler = system.scheduler

    "verify that the user function process responds" in {
      attempt(entityClient.ready(Empty()), 4.seconds, 10) map { spec =>
        spec.proto must not be ProtobufByteString.EMPTY
        spec.serviceName must not be empty
        spec.persistenceId must not be empty
      }
    }

    "verify that an initial GetShoppingCart request succeeds" in {
      val userId = "testuser:1"
      attempt(shoppingClient.getCart(GetShoppingCart(userId)), 4.seconds, 10) map {
        cart =>
          // Interaction test
          fromBackend.expectMsg(Empty())

          fromFrontend_expectEntitySpec(noWait)

          fromBackend_expectInit(noWait)

          correlate(fromBackend_expectCommand(noWait), fromFrontend_expectReply(events = 0, noWait))

          fromBackend.expectNoMsg(noWait)
          fromFrontend.expectNoMsg(noWait)
          
          // Semantical test
          cart must not be(null)
          cart.items must be(empty)
      }
    }

    // TODO convert this into a ScalaCheck generated test case
    "verify that items can be added to, and removed from, a shopping cart" in {
      val sc           = shoppingClient
      import sc.{getCart, addItem, removeItem}

      val userId       = "testuser:2"
      val productId1   = "testproduct:1"
      val productId2   = "testproduct:2"
      val productName1 = "Test Product 1"
      val productName2 = "Test Product 2"

      for {
        Cart(Nil)    <- getCart(GetShoppingCart(userId))                           // Test initial state
        Empty()      <- addItem(AddLineItem(userId, productId1, productName1, 1))  // Test add the first product
        Empty()      <- addItem(AddLineItem(userId, productId2, productName2, 2))  // Test add the second product
        Empty()      <- addItem(AddLineItem(userId, productId1, productName1, 11)) // Test increase quantity
        Empty()      <- addItem(AddLineItem(userId, productId2, productName2, 31)) // Test increase quantity
        Cart(items1) <- getCart(GetShoppingCart(userId))                           // Test intermediate state
        Empty()      <- removeItem(RemoveLineItem(userId, productId1))             // Test removal of first product
        addNeg       <- addItem(AddLineItem(userId, productId2, productName2, -7)).transform(scala.util.Success(_)) // Test decrement quantity of second product
        add0         <- addItem(AddLineItem(userId, productId1, productName1, 0)).transform(scala.util.Success(_)) // Test add 0 of new product
        removeNone   <- removeItem(RemoveLineItem(userId, productId1)).transform(scala.util.Success(_)) // Test remove non-exiting product
        Cart(items2) <- getCart(GetShoppingCart(userId))                           // Test end state
      } yield {
        val init = fromBackend_expectInit(noWait)
        init.entityId must not be(empty)

        val commands = Seq(
          (true,0),(true,1),(true, 1),(true,1),(true,1),(true,0),
          (true,1),(false,0),(false,0),(false,0),(true,0)).
        foldLeft(Set.empty[Long]){ case (set, (isReply, eventCount)) =>
          val cmd = fromBackend_expectCommand(noWait)
          if (isReply)
            correlate(cmd, fromFrontend_expectReply(events = eventCount, noWait)) // Verify correlation
          else
            correlate(cmd, fromFrontend_expectFailure(noWait)) // Verify correlation
          init.entityId must be(cmd.entityId)
          set must not contain(cmd.id)
          set + cmd.id
        }

        fromBackend.expectNoMsg(noWait)
        fromFrontend.expectNoMsg(noWait)

        commands must have(size(11)) // Verify command id uniqueness

        addNeg must be('failure) // Verfify that we get a failure when adding a negative quantity
        add0 must be('failure) // Verify that we get a failure when adding a line item of 0 items
        removeNone must be('failure) // Verify that we get a failure when removing a non-existing item

        //Semantical test
        items1.toSet must equal(Set(LineItem(productId1, productName1, 12), LineItem(productId2, productName2, 33)))
        items2.toSet must equal(Set(LineItem(productId2, productName2, 33)))
      }
    }

    "verify that the backend supports the ServerReflection API" in {
      import grpc.reflection.v1alpha._
      import ServerReflectionRequest.{ MessageRequest => In}
      import ServerReflectionResponse.{ MessageResponse => Out}

      val reflectionClient = ServerReflectionClient(GrpcClientSettings.connectToServiceAt(config.backend.hostname, config.backend.port)(system).withTls(false))(mat, mat.executionContext)

      val Host        = config.backend.hostname
      val ShoppingCart = "com.example.shoppingcart.ShoppingCart"

      val testData = List[(In, Out)](
        (In.ListServices(""), Out.ListServicesResponse(ListServiceResponse(Vector(ServiceResponse(ShoppingCart))))),
        (In.ListServices("nonsense.blabla."), Out.ListServicesResponse(ListServiceResponse(Vector(ServiceResponse(ShoppingCart))))),
        (In.FileContainingSymbol("nonsense.blabla.Void"), Out.FileDescriptorResponse(FileDescriptorResponse(Nil))),
      ) map {
        case (in, out) =>
          val req = ServerReflectionRequest(Host, in)
          val res = ServerReflectionResponse(Host, Some(req), out)
          (req, res)
      }
      val input = testData.map(_._1)
      val expected = testData.map(_._2)
      val test = for {
        output <- reflectionClient.serverReflectionInfo(Source(input)).runWith(Sink.seq)(mat)
      } yield {
        testData.zip(output) foreach {
          case ((in, exp), out) => (in, out) must equal((in, exp))
        }
        output must not(be(empty))
      }

      test andThen {
        case _ => Try(reflectionClient.close())
      }
    }
  }
}