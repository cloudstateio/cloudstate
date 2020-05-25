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

package io.cloudstate.tck

import akka.NotUsed
import akka.actor.{ActorSystem, Scheduler}
import akka.grpc.GrpcClientSettings
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling._
import akka.pattern.after
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import com.example.shoppingcart.shoppingcart._
import com.google.protobuf.empty.Empty
import com.google.protobuf.{ByteString => ProtobufByteString}
import com.typesafe.config.{Config, ConfigFactory}
import io.cloudstate.protocol.entity._
import io.cloudstate.protocol.event_sourced._
import org.scalatest._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

object CloudStateTCK {
  final case class Address(hostname: String, port: Int)
  final case class Settings(tck: Address, proxy: Address, frontend: Address)

  object Settings {
    def fromConfig(config: Config): Settings = {
      val tckConfig = config.getConfig("cloudstate.tck")
      Settings(
        Address(tckConfig.getString("hostname"), tckConfig.getInt("port")),
        Address(tckConfig.getString("proxy.hostname"), tckConfig.getInt("proxy.port")),
        Address(tckConfig.getString("frontend.hostname"), tckConfig.getInt("frontend.port"))
      )
    }
  }

  final val noWait = 0.seconds

  // FIXME add interception to enable asserting exchanges
  final class EventSourcedInterceptor(val client: EventSourcedClient,
                                      val fromBackend: TestProbe,
                                      val fromFrontend: TestProbe)(implicit ec: ExecutionContext)
      extends EventSourced {

    private final val fromBackendInterceptor = Sink.actorRef[AnyRef](fromBackend.ref, "BACKEND_TERMINATED")
    private final val fromFrontendInterceptor = Sink.actorRef[AnyRef](fromFrontend.ref, "FRONTEND_TERMINATED")

    override def handle(in: Source[EventSourcedStreamIn, NotUsed]): Source[EventSourcedStreamOut, NotUsed] =
      client.handle(in.alsoTo(fromBackendInterceptor)).alsoTo(fromFrontendInterceptor)
  }

  // FIXME add interception to enable asserting exchanges
  final class EntityDiscoveryInterceptor(val client: EntityDiscoveryClient,
                                         val fromBackend: TestProbe,
                                         val fromFrontend: TestProbe)(implicit ec: ExecutionContext)
      extends EntityDiscovery {
    import scala.util.{Failure, Success}

    override def discover(info: ProxyInfo): Future[EntitySpec] = {
      fromBackend.ref ! info
      client.discover(info).andThen {
        case Success(es) => fromFrontend.ref ! es
        case Failure(f) => fromFrontend.ref ! f
      }
    }

    override def reportError(error: UserFunctionError): Future[Empty] = {
      fromBackend.ref ! error
      client.reportError(error).andThen {
        case Success(e) => fromFrontend.ref ! e
        case Failure(f) => fromFrontend.ref ! f
      }
    }
  }

  def attempt[T](op: => Future[T], delay: FiniteDuration, retries: Int)(implicit ec: ExecutionContext,
                                                                        s: Scheduler): Future[T] =
    Future.unit.flatMap(_ => op) recoverWith {
      case _ if retries > 0 => after(delay, s)(attempt(op, delay, retries - 1))
    }

  final val proxyInfo = ProxyInfo(
    protocolMajorVersion = 0,
    protocolMinorVersion = 1,
    proxyName = "TCK",
    proxyVersion = "0.1",
    supportedEntityTypes = Seq(EventSourced.name)
  )
}

class ConfiguredCloudStateTCK extends CloudStateTCK(CloudStateTCK.Settings.fromConfig(ConfigFactory.load()))

class CloudStateTCK(description: String, settings: CloudStateTCK.Settings)
    extends AsyncWordSpec
    with MustMatchers
    with BeforeAndAfterAll {
  import CloudStateTCK._

  def this(settings: CloudStateTCK.Settings) = this("", settings)

  private[this] final val system = ActorSystem("CloudStateTCK", ConfigFactory.load("tck"))
  private[this] final val mat = ActorMaterializer()(system)
  private[this] final val discoveryFromBackend = TestProbe("discoveryFromBackend")(system)
  private[this] final val discoveryFromFrontend = TestProbe("discoveryFromFrontend")(system)
  private[this] final val eventSourcedFromBackend = TestProbe("eventSourcedFromBackend")(system)
  private[this] final val eventSourcedFromFrontend = TestProbe("eventSourcedFromFrontend")(system)
  @volatile private[this] final var shoppingClient: ShoppingCartClient = _
  @volatile private[this] final var entityDiscoveryClient: EntityDiscoveryClient = _
  @volatile private[this] final var eventSourcedClient: EventSourcedClient = _
  @volatile private[this] final var tckProxy: ServerBinding = _

  def buildTCKProxy(entityDiscovery: EntityDiscovery, eventSourced: EventSourced): Future[ServerBinding] = {
    implicit val s = system
    implicit val m = mat
    Http().bindAndHandleAsync(
      handler = EntityDiscoveryHandler.partial(entityDiscovery) orElse EventSourcedHandler.partial(eventSourced),
      interface = settings.tck.hostname,
      port = settings.tck.port
    )
  }

  override def beforeAll(): Unit = {
    val clientSettings =
      GrpcClientSettings.connectToServiceAt(settings.frontend.hostname, settings.frontend.port)(system).withTls(false)

    val edc = EntityDiscoveryClient(clientSettings)(mat, mat.executionContext)

    entityDiscoveryClient = edc

    val esc = EventSourcedClient(clientSettings)(mat, mat.executionContext)

    eventSourcedClient = esc

    val tp = Await.result(
      buildTCKProxy(
        new EntityDiscoveryInterceptor(edc, discoveryFromBackend, discoveryFromFrontend),
        new EventSourcedInterceptor(esc, eventSourcedFromBackend, eventSourcedFromFrontend)(system.dispatcher)
      ),
      10.seconds
    )

    tckProxy = tp

    // Wait for the frontend to come up before starting the backend, otherwise the discovery call from the backend,
    // if it happens before the frontend starts, will cause the proxy probes to have failures in them
    Await.ready(attempt(entityDiscoveryClient.discover(proxyInfo), 4.seconds, 10)(system.dispatcher, system.scheduler),
                1.minute)

    val sc = ShoppingCartClient(
      GrpcClientSettings.connectToServiceAt(settings.proxy.hostname, settings.proxy.port)(system).withTls(false)
    )(mat, mat.executionContext)

    shoppingClient = sc
  }

  override def afterAll(): Unit =
    try Option(shoppingClient).foreach(c => Await.result(c.close(), 10.seconds))
    finally try Seq(entityDiscoveryClient, eventSourcedClient).foreach(c => Await.result(c.close(), 10.seconds))
    finally Await.ready(tckProxy.unbind().transformWith(_ => system.terminate())(system.dispatcher), 30.seconds)

  final def fromFrontend_expectEntitySpec(within: FiniteDuration): EntitySpec =
    withClue("EntitySpec was not received, or not well-formed: ") {
      val spec = discoveryFromFrontend.expectMsgType[EntitySpec](within)
      spec.proto must not be ProtobufByteString.EMPTY
      spec.entities must not be empty
      spec.entities.head.serviceName must not be empty
      spec.entities.head.persistenceId must not be empty
      // fixme event sourced?
      spec.entities.head.entityType must not be empty
      spec
    }

  final def fromBackend_expectInit(within: FiniteDuration): EventSourcedInit =
    withClue("Init message was not received, or not well-formed: ") {
      val init = eventSourcedFromBackend.expectMsgType[EventSourcedStreamIn](noWait)
      init must not be (null)
      init.message must be('init)
      init.message.init must be(defined)
      init.message.init.get
    }

  final def fromBackend_expectCommand(within: FiniteDuration): Command =
    withClue("Command was not received, or not well-formed: ") {
      val command = eventSourcedFromBackend.expectMsgType[EventSourcedStreamIn](noWait)
      command must not be (null) // FIXME validate Command
      command.message must be('command)
      command.message.command must be(defined)
      val c = command.message.command.get
      c.entityId must not be (empty)
      c
    }

  final def fromFrontend_expectReply(events: Int, within: FiniteDuration): EventSourcedReply =
    withClue("Reply was not received, or not well-formed: ") {
      val reply = eventSourcedFromFrontend.expectMsgType[EventSourcedStreamOut](noWait)
      reply must not be (null)
      reply.message must be('reply)
      reply.message.reply must be(defined)
      val r = reply.message.reply.get
      r.clientAction must be(defined)
      val clientAction = r.clientAction.get
      clientAction.action must be('reply)
      clientAction.action.reply must be('defined)
      withClue("Reply did not have the expected number of events: ") { r.events.size must be(events) }
      r
    }

  final def fromFrontend_expectFailure(within: FiniteDuration): Failure =
    withClue("Failure was not received, or not well-formed: ") {
      val failure = eventSourcedFromFrontend.expectMsgType[EventSourcedStreamOut](noWait) // FIXME Expects entity.Failure, but gets lientAction.Action.Failure(Failure(commandId, msg)))
      failure must not be (null)
      failure.message must be('reply)
      failure.message.reply must be(defined)
      failure.message.reply.get.clientAction must be(defined)
      val clientAction = failure.message.reply.get.clientAction.get
      clientAction.action must be('failure)
      clientAction.action.failure must be('defined)
      clientAction.action.failure.get
    }

  final def correlate(cmd: Command, commandId: Long) = withClue("Command had the wrong id: ") {
    cmd.id must be(commandId)
  }
  final def unrelated(cmd: Command, commandId: Long) = withClue("Command had the wrong id: ") {
    cmd.id must not be commandId
  }

  ("Cloudstate TCK " + description) must {
    implicit val scheduler = system.scheduler

    "verify that the user function process responds" in {
      attempt(entityDiscoveryClient.discover(proxyInfo), 4.seconds, 10) map { spec =>
        spec.proto must not be ProtobufByteString.EMPTY
        spec.entities must not be empty
        spec.entities.head.serviceName must not be empty
        spec.entities.head.persistenceId must not be empty
      }
    }

    "verify that an initial GetShoppingCart request succeeds" in {
      val userId = "testuser:1"
      attempt(shoppingClient.getCart(GetShoppingCart(userId)), 4.seconds, 10) map { cart =>
        // Interaction test
        val proxyInfo = discoveryFromBackend.expectMsgType[ProxyInfo]
        proxyInfo.supportedEntityTypes must contain(EventSourced.name)
        proxyInfo.protocolMajorVersion must be >= 0
        proxyInfo.protocolMinorVersion must be >= 0

        fromFrontend_expectEntitySpec(noWait)

        fromBackend_expectInit(noWait)

        correlate(fromBackend_expectCommand(noWait), fromFrontend_expectReply(events = 0, noWait).commandId)

        eventSourcedFromBackend.expectNoMsg(noWait)
        eventSourcedFromFrontend.expectNoMsg(noWait)

        // Semantical test
        cart must not be (null)
        cart.items must be(empty)
      }
    }

    // TODO convert this into a ScalaCheck generated test case
    "verify that items can be added to, and removed from, a shopping cart" in {
      val sc = shoppingClient
      import sc.{addItem, getCart, removeItem}

      val userId = "testuser:2"
      val productId1 = "testproduct:1"
      val productId2 = "testproduct:2"
      val productName1 = "Test Product 1"
      val productName2 = "Test Product 2"

      for {
        Cart(Nil, _) <- getCart(GetShoppingCart(userId)) // Test initial state
        Empty(_) <- addItem(AddLineItem(userId, productId1, productName1, 1)) // Test add the first product
        Empty(_) <- addItem(AddLineItem(userId, productId2, productName2, 2)) // Test add the second product
        Empty(_) <- addItem(AddLineItem(userId, productId1, productName1, 11)) // Test increase quantity
        Empty(_) <- addItem(AddLineItem(userId, productId2, productName2, 31)) // Test increase quantity
        Cart(items1, _) <- getCart(GetShoppingCart(userId)) // Test intermediate state
        Empty(_) <- removeItem(RemoveLineItem(userId, productId1)) // Test removal of first product
        addNeg <- addItem(AddLineItem(userId, productId2, productName2, -7))
          .transform(scala.util.Success(_)) // Test decrement quantity of second product
        add0 <- addItem(AddLineItem(userId, productId1, productName1, 0))
          .transform(scala.util.Success(_)) // Test add 0 of new product
        removeNone <- removeItem(RemoveLineItem(userId, productId1))
          .transform(scala.util.Success(_)) // Test remove non-exiting product
        Cart(items2, _) <- getCart(GetShoppingCart(userId)) // Test end state
      } yield {
        val init = fromBackend_expectInit(noWait)
        init.entityId must not be (empty)

        val commands = Seq((true, 0),
                           (true, 1),
                           (true, 1),
                           (true, 1),
                           (true, 1),
                           (true, 0),
                           (true, 1),
                           (false, 0),
                           (false, 0),
                           (false, 0),
                           (true, 0)).foldLeft(Set.empty[Long]) {
          case (set, (isReply, eventCount)) =>
            val cmd = fromBackend_expectCommand(noWait)
            correlate(
              cmd,
              if (isReply) fromFrontend_expectReply(events = eventCount, noWait).commandId
              else fromFrontend_expectFailure(noWait).commandId
            )
            init.entityId must be(cmd.entityId)
            set must not contain (cmd.id)
            set + cmd.id
        }

        eventSourcedFromBackend.expectNoMsg(noWait)
        eventSourcedFromFrontend.expectNoMsg(noWait)

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
      import grpc.reflection.v1alpha.reflection._
      import ServerReflectionRequest.{MessageRequest => In}
      import ServerReflectionResponse.{MessageResponse => Out}

      val reflectionClient = ServerReflectionClient(
        GrpcClientSettings.connectToServiceAt(settings.proxy.hostname, settings.proxy.port)(system).withTls(false)
      )(mat, mat.executionContext)

      val Host = settings.proxy.hostname
      val ShoppingCart = "com.example.shoppingcart.ShoppingCart"

      val testData = List[(In, Out)](
          (In.ListServices(""),
           Out.ListServicesResponse(
             ListServiceResponse(Vector(ServiceResponse(ServerReflection.name), ServiceResponse(ShoppingCart)))
           )),
          (In.ListServices("nonsense.blabla."),
           Out.ListServicesResponse(
             ListServiceResponse(Vector(ServiceResponse(ServerReflection.name), ServiceResponse(ShoppingCart)))
           )),
          (In.FileContainingSymbol("nonsense.blabla.Void"), Out.FileDescriptorResponse(FileDescriptorResponse(Nil)))
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

    "verify that the HTTP API of ShoppingCart protocol works" in {
      implicit val s = system
      implicit val m = mat

      def validateResponse(response: HttpResponse): Future[String] = {
        response.status must be(StatusCodes.OK)
        response.entity.contentType must be(ContentTypes.`application/json`)
        Unmarshal(response).to[String]
      }

      def getCart(userId: String): Future[String] =
        Http()
          .singleRequest(
            HttpRequest(
              method = HttpMethods.GET,
              headers = Nil,
              uri = s"http://${settings.proxy.hostname}:${settings.proxy.port}/carts/${userId}",
              entity = HttpEntity.Empty,
              protocol = HttpProtocols.`HTTP/1.1`
            )
          )
          .flatMap(validateResponse)

      def getItems(userId: String): Future[String] =
        Http()
          .singleRequest(
            HttpRequest(
              method = HttpMethods.GET,
              headers = Nil,
              uri = s"http://${settings.proxy.hostname}:${settings.proxy.port}/carts/${userId}/items",
              entity = HttpEntity.Empty,
              protocol = HttpProtocols.`HTTP/1.1`
            )
          )
          .flatMap(validateResponse)

      def addItem(userId: String, productId: String, productName: String, quantity: Int): Future[String] =
        Http()
          .singleRequest(
            HttpRequest(
              method = HttpMethods.POST,
              headers = Nil,
              uri = s"http://${settings.proxy.hostname}:${settings.proxy.port}/cart/${userId}/items/add",
              entity = HttpEntity(
                ContentTypes.`application/json`,
                s"""
              {
                "product_id": "${productId}",
                "name": "${productName}",
                "quantity": ${quantity}
              }
              """.trim
              ),
              protocol = HttpProtocols.`HTTP/1.1`
            )
          )
          .flatMap(validateResponse)

      def removeItem(userId: String, productId: String): Future[String] =
        Http()
          .singleRequest(
            HttpRequest(
              method = HttpMethods.POST,
              headers = Nil,
              uri = s"http://${settings.proxy.hostname}:${settings.proxy.port}/cart/${userId}/items/${productId}/remove",
              entity = HttpEntity.Empty,
              protocol = HttpProtocols.`HTTP/1.1`
            )
          )
          .flatMap(validateResponse)

      val userId = "foo"
      for {
        c0 <- getCart(userId)
        a0 <- addItem(userId, "A14362347", "Deluxe", 5)
        a1 <- addItem(userId, "B14623482", "Basic", 1)
        a2 <- addItem(userId, "A14362347", "Deluxe", 2)
        c1 <- getCart(userId)
        l0 <- getItems(userId)
        r0 <- removeItem(userId, "A14362347")
        c2 <- getCart(userId)
        l1 <- getItems(userId)
      } yield {
        c0 must equal("""{"items":[]}""")
        a0 must equal("""{}""")
        a1 must equal("""{}""")
        a2 must equal("""{}""")
        c1 must equal(
          """{"items":[{"productId":"A14362347","name":"Deluxe","quantity":7},{"productId":"B14623482","name":"Basic","quantity":1}]}"""
        )
        l0 must equal(
          """[{"productId":"A14362347","name":"Deluxe","quantity":7.0},{"productId":"B14623482","name":"Basic","quantity":1.0}]"""
        )
        r0 must equal("""{}""")
        c2 must equal(
          """{"items":[{"productId":"B14623482","name":"Basic","quantity":1}]}"""
        )
        l1 must equal(
          """[{"productId":"B14623482","name":"Basic","quantity":1.0}]"""
        )
      }
    }
  }
}
