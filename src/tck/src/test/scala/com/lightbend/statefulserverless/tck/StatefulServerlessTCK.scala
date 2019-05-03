package com.lightbend.statefulserverless.tck

import akka.NotUsed
import akka.actor.{ActorSystem, Scheduler}
import akka.stream.{Materializer, ActorMaterializer}
import akka.stream.scaladsl.{Source, Sink}
import akka.pattern.after

import akka.grpc.GrpcClientSettings

import org.scalatest._
import com.typesafe.config.Config

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import scala.util.{Success, Failure, Try}

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
  private[this] final val BACKEND   = "stateful-serverless-tck.backend"
  private[this] final val FRONTEND  = "stateful-serverless-tck.frontend"
  private[this] final val TCK       = "stateful-serverless-tck.tck"
  private[this] final val HOSTNAME  = "hostname"
  private[this] final val PORT      = "port"

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
    }
  }
  final case class Configuration private(
    backend: ProcSpec,
    frontend: ProcSpec,
    tckHostname: String,
    tckPort: Int) {
    def this(config: Config) = this(
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

class StatefulServerlessTCK extends AsyncWordSpec with MustMatchers with BeforeAndAfterAll {
  import StatefulServerlessTCK._
            private[this] final val system                               = ActorSystem("StatefulServerlessTCK")
            private[this] final val mat                                  = ActorMaterializer()(system)
            private[this] final val config                               = new Configuration(system.settings.config)
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
    spec.proto must be(defined)
    spec.serviceName must not be empty
    spec.persistenceId must not be empty
    spec
  }

  final def fromBackend_expectInit(within: FiniteDuration): Init = {
    val init = fromBackend.expectMsgType[EntityStreamIn](noWait)
    init must not be(null)
    init.message must be('init)
    init.message.init must be(defined)
    val i = init.message.init.get
    //FIXME validate
    //i.entityId
    //i.snapshot
    i
  }

  final def fromBackend_expectCommand(within: FiniteDuration): Command = {
    val command = fromBackend.expectMsgType[EntityStreamIn](noWait)
    command must not be(null)  // FIXME validate Command
    command.message must be('command)
    command.message.command must be(defined)
    val c = command.message.command.get
    //FIXME validate
    //c.entityId
    //c.id
    //c.payload
    c
  }

  final def fromFrontend_expectReply(within: FiniteDuration): Reply = {
    val reply = fromFrontend.expectMsgType[EntityStreamOut](noWait)
    reply must not be(null)
    reply.message must be('reply)
    reply.message.reply must be(defined)
    val r = reply.message.reply.get
    //FIXME validate
    //r.commandId
    //r.payload
    //r.events
    //r.snapshot
    r
  }

  "The TCK" must {
    implicit val scheduler = system.scheduler

    "verify that the user function process responds" in {
      attempt(entityClient.ready(Empty()), 4.seconds, 10) map { spec =>
        spec.proto must be(defined)
        spec.serviceName must not be empty
        spec.persistenceId must not be empty
      }
    }

    "verify that an initial GetShoppingCart request succeeds" in {
      val userId = "testuser:1"
      attempt(shoppingClient.getCart(GetShoppingCart(userId)), 4.seconds, 10) map {
        cart =>
          // Interaction test
          val _     = fromBackend.expectMsg(Empty())

          val spec  = fromFrontend_expectEntitySpec(noWait)

          val init  = fromBackend_expectInit(noWait)

          val cmd   = fromBackend_expectCommand(noWait)

          val reply = fromFrontend_expectReply(noWait)

          fromBackend.expectNoMsg(noWait)
          fromFrontend.expectNoMsg(noWait)
          
          // Semantical test
          cart must not be(null)
          cart.items must be(empty)

          cmd.id must be(reply.commandId)
      }
    }

    "verify that items can be added to a shopping cart" in {
      val userId       = "testuser:2"
      val productId1   = "testproduct:1"
      val productId2   = "testproduct:2"
      val productName1 = "Test Product 1"
      val productName2 = "Test Product 2"
      for {
        cart  <- shoppingClient.getCart(GetShoppingCart(userId))
        Empty()  <- shoppingClient.addItem(AddLineItem(userId, productId1, productName1, 1))
        Empty()  <- shoppingClient.addItem(AddLineItem(userId, productId2, productName2, 2))
        Empty()  <- shoppingClient.addItem(AddLineItem(userId, productId1, productName1, 11))
        Empty()  <- shoppingClient.addItem(AddLineItem(userId, productId2, productName2, 5))
        cart1 <- shoppingClient.getCart(GetShoppingCart(userId))
      } yield {
        //FIXME interaction test
        fromBackend_expectInit(noWait)

        1 to 6 foreach { _ =>
          val cmd = fromBackend_expectCommand(noWait)
          val rep = fromFrontend_expectReply(noWait)

          cmd.id must be (rep.commandId)
        }

        fromBackend.expectNoMsg(noWait)
        fromFrontend.expectNoMsg(noWait)

        //Semantical test
        cart must not be(null)
        cart.items must be(empty)
        cart1 must not be(null)
        cart1.items must not be(empty)
        cart1.items.toSet must be(Set(LineItem(productId1, productName1, 12), LineItem(productId2, productName2, 7)))
      }
    }
  }
}