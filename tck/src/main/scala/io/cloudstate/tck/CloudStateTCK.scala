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

import akka.actor.ActorSystem
import akka.grpc.ServiceDescription
import akka.testkit.TestKit
import com.example.shoppingcart.persistence.domain
import com.example.shoppingcart.shoppingcart._
import com.google.protobuf.DescriptorProtos
import com.google.protobuf.any.{Any => ScalaPbAny}
import com.typesafe.config.{Config, ConfigFactory}
import io.cloudstate.protocol.action.ActionProtocol
import io.cloudstate.protocol.crdt.Crdt
import io.cloudstate.protocol.event_sourced._
import io.cloudstate.testkit.InterceptService.InterceptorSettings
import io.cloudstate.testkit.eventsourced.{EventSourcedMessages, InterceptEventSourcedService}
import io.cloudstate.testkit.{InterceptService, ServiceAddress, TestClient, TestProtocol}
import io.grpc.StatusRuntimeException
import io.cloudstate.tck.model.eventsourced.{EventSourcedTckModel, EventSourcedTwo}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, MustMatchers, WordSpec}
import scala.collection.mutable
import scala.concurrent.duration._

object CloudStateTCK {
  final case class Settings(tck: ServiceAddress, proxy: ServiceAddress, service: ServiceAddress)

  object Settings {
    def fromConfig(config: Config): Settings = {
      val tckConfig = config.getConfig("cloudstate.tck")
      Settings(
        ServiceAddress(tckConfig.getString("hostname"), tckConfig.getInt("port")),
        ServiceAddress(tckConfig.getString("proxy.hostname"), tckConfig.getInt("proxy.port")),
        ServiceAddress(tckConfig.getString("service.hostname"), tckConfig.getInt("service.port"))
      )
    }
  }
}

class ConfiguredCloudStateTCK extends CloudStateTCK(CloudStateTCK.Settings.fromConfig(ConfigFactory.load()))

class CloudStateTCK(description: String, settings: CloudStateTCK.Settings)
    extends WordSpec
    with MustMatchers
    with BeforeAndAfterAll
    with ScalaFutures {

  def this(settings: CloudStateTCK.Settings) = this("", settings)

  private[this] final val system = ActorSystem("CloudStateTCK", ConfigFactory.load("tck"))

  private[this] final val client = TestClient(settings.proxy.host, settings.proxy.port)
  private[this] final val shoppingCartClient = ShoppingCartClient(client.settings)(system)

  private[this] final val protocol = TestProtocol(settings.service.host, settings.service.port)

  @volatile private[this] final var interceptor: InterceptService = _
  @volatile private[this] final var enabledServices = Seq.empty[String]

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = 3.seconds, interval = 100.millis)

  override def beforeAll(): Unit =
    interceptor = new InterceptService(InterceptorSettings(bind = settings.tck, intercept = settings.service))

  override def afterAll(): Unit =
    try shoppingCartClient.close().futureValue
    finally try client.terminate()
    finally try protocol.terminate()
    finally interceptor.terminate()

  def expectProxyOnline(): Unit =
    TestKit.awaitCond(client.http.probe(), max = 10.seconds)

  def testFor(services: ServiceDescription*)(test: => Any): Unit = {
    val enabled = services.map(_.name).forall(enabledServices.contains)
    if (enabled) test else pending
  }

  ("Cloudstate TCK " + description) when {

    "verifying discovery protocol" must {
      "verify proxy info and entity discovery" in {
        import scala.jdk.CollectionConverters._

        expectProxyOnline()

        val discovery = interceptor.expectEntityDiscovery()

        val info = discovery.expectProxyInfo()

        info.protocolMajorVersion mustBe 0
        info.protocolMinorVersion mustBe 2

        info.supportedEntityTypes must contain theSameElementsAs Seq(
          EventSourced.name,
          Crdt.name,
          ActionProtocol.name
        )

        val spec = discovery.expectEntitySpec()

        val descriptorSet = DescriptorProtos.FileDescriptorSet.parseFrom(spec.proto)
        val serviceNames = descriptorSet.getFileList.asScala.flatMap(_.getServiceList.asScala.map(_.getName))

        serviceNames.size mustBe spec.entities.size

        spec.entities.find(_.serviceName == EventSourcedTckModel.name).foreach { entity =>
          serviceNames must contain("EventSourcedTckModel")
          entity.entityType mustBe EventSourced.name
          entity.persistenceId mustBe "event-sourced-tck-model"
        }

        spec.entities.find(_.serviceName == EventSourcedTwo.name).foreach { entity =>
          serviceNames must contain("EventSourcedTwo")
          entity.entityType mustBe EventSourced.name
        }

        spec.entities.find(_.serviceName == ShoppingCart.name).foreach { entity =>
          serviceNames must contain("ShoppingCart")
          entity.entityType mustBe EventSourced.name
          entity.persistenceId must not be empty
        }

        enabledServices = spec.entities.map(_.serviceName)
      }
    }

    "verifying model test: event-sourced entities" must {
      import EventSourcedMessages._
      import io.cloudstate.tck.model.eventsourced._

      val ServiceTwo = EventSourcedTwo.name

      var entityId: Int = 0
      def nextEntityId(): String = { entityId += 1; s"entity:$entityId" }

      def eventSourcedTest(test: String => Any): Unit =
        testFor(EventSourcedTckModel, EventSourcedTwo)(test(nextEntityId()))

      def emitEvent(value: String): RequestAction =
        RequestAction(RequestAction.Action.Emit(Emit(value)))

      def emitEvents(values: String*): Seq[RequestAction] =
        values.map(emitEvent)

      def forwardTo(id: String): RequestAction =
        RequestAction(RequestAction.Action.Forward(Forward(id)))

      def sideEffectTo(id: String, synchronous: Boolean = false): RequestAction =
        RequestAction(RequestAction.Action.Effect(Effect(id, synchronous)))

      def sideEffectsTo(ids: String*): Seq[RequestAction] =
        ids.map(id => sideEffectTo(id, synchronous = false))

      def failWith(message: String): RequestAction =
        RequestAction(RequestAction.Action.Fail(Fail(message)))

      def persisted(value: String): ScalaPbAny =
        protobufAny(Persisted(value))

      def events(values: String*): Effects =
        Effects(events = values.map(persisted))

      def snapshotAndEvents(snapshotValue: String, eventValues: String*): Effects =
        events(eventValues: _*).withSnapshot(persisted(snapshotValue))

      def sideEffects(ids: String*): Effects =
        ids.foldLeft(Effects.empty) { case (e, id) => e.withSideEffect(ServiceTwo, "Call", Request(id)) }

      "verify initial empty state" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Response()))
          .passivate()
      }

      "verify single emitted event" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, emitEvents("A"))))
          .expect(reply(1, Response("A"), events("A")))
          .send(command(2, id, "Process", Request(id)))
          .expect(reply(2, Response("A")))
          .passivate()
      }

      "verify multiple emitted events" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, emitEvents("A", "B", "C"))))
          .expect(reply(1, Response("ABC"), events("A", "B", "C")))
          .send(command(2, id, "Process", Request(id)))
          .expect(reply(2, Response("ABC")))
          .passivate()
      }

      "verify multiple emitted events and snapshots" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, emitEvents("A"))))
          .expect(reply(1, Response("A"), events("A")))
          .send(command(2, id, "Process", Request(id, emitEvents("B"))))
          .expect(reply(2, Response("AB"), events("B")))
          .send(command(3, id, "Process", Request(id, emitEvents("C"))))
          .expect(reply(3, Response("ABC"), events("C")))
          .send(command(4, id, "Process", Request(id, emitEvents("D"))))
          .expect(reply(4, Response("ABCD"), events("D")))
          .send(command(5, id, "Process", Request(id, emitEvents("E"))))
          .expect(reply(5, Response("ABCDE"), snapshotAndEvents("ABCDE", "E")))
          .send(command(6, id, "Process", Request(id, emitEvents("F", "G", "H"))))
          .expect(reply(6, Response("ABCDEFGH"), events("F", "G", "H")))
          .send(command(7, id, "Process", Request(id, emitEvents("I", "J"))))
          .expect(reply(7, Response("ABCDEFGHIJ"), snapshotAndEvents("ABCDEFGHIJ", "I", "J")))
          .send(command(8, id, "Process", Request(id, emitEvents("K"))))
          .expect(reply(8, Response("ABCDEFGHIJK"), events("K")))
          .send(command(9, id, "Process", Request(id)))
          .expect(reply(9, Response("ABCDEFGHIJK")))
          .passivate()
      }

      "verify initial snapshot" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id, snapshot(5, persisted("ABCDE"))))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Response("ABCDE")))
          .passivate()
      }

      "verify initial snapshot and events" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id, snapshot(5, persisted("ABCDE"))))
          .send(event(6, persisted("F")))
          .send(event(7, persisted("G")))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Response("ABCDEFG")))
          .passivate()
      }

      "verify rehydration after passivation" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, emitEvents("A", "B", "C"))))
          .expect(reply(1, Response("ABC"), events("A", "B", "C")))
          .send(command(2, id, "Process", Request(id, emitEvents("D", "E"))))
          .expect(reply(2, Response("ABCDE"), snapshotAndEvents("ABCDE", "D", "E")))
          .send(command(3, id, "Process", Request(id, emitEvents("F"))))
          .expect(reply(3, Response("ABCDEF"), events("F")))
          .send(command(4, id, "Process", Request(id, emitEvents("G"))))
          .expect(reply(4, Response("ABCDEFG"), events("G")))
          .passivate()
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id, snapshot(5, persisted("ABCDE"))))
          .send(event(6, persisted("F")))
          .send(event(7, persisted("G")))
          .send(command(1, id, "Process", Request(id)))
          .expect(reply(1, Response("ABCDEFG")))
          .passivate()
      }

      "verify forward to second service" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(forwardTo(id)))))
          .expect(forward(1, EventSourcedTwo.name, "Call", Request(id)))
          .passivate()
      }

      "verify forward with emitted events" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(emitEvent("A"), forwardTo(id)))))
          .expect(forward(1, EventSourcedTwo.name, "Call", Request(id), events("A")))
          .passivate()
      }

      "verify forward with emitted events and snapshot" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, emitEvents("A", "B", "C"))))
          .expect(reply(1, Response("ABC"), events("A", "B", "C")))
          .send(command(2, id, "Process", Request(id, Seq(emitEvent("D"), emitEvent("E"), forwardTo(id)))))
          .expect(forward(2, EventSourcedTwo.name, "Call", Request(id), snapshotAndEvents("ABCDE", "D", "E")))
          .passivate()
      }

      "verify reply with side effect to second service" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(id)))))
          .expect(reply(1, Response(), sideEffect(EventSourcedTwo.name, "Call", Request(id))))
          .passivate()
      }

      "verify synchronous side effect to second service" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(id, synchronous = true)))))
          .expect(reply(1, Response(), sideEffect(EventSourcedTwo.name, "Call", Request(id), synchronous = true)))
          .passivate()
      }

      "verify forward and side effect to second service" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(id), forwardTo(id)))))
          .expect(forward(1, ServiceTwo, "Call", Request(id), sideEffect(ServiceTwo, "Call", Request(id))))
          .passivate()
      }

      "verify reply with multiple side effects" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, sideEffectsTo("1", "2", "3"))))
          .expect(reply(1, Response(), sideEffects("1", "2", "3")))
          .passivate()
      }

      "verify reply with multiple side effects, events, and snapshot" in eventSourcedTest { id =>
        val actions = emitEvents("A", "B", "C", "D", "E") ++ sideEffectsTo("1", "2", "3")
        val effects = snapshotAndEvents("ABCDE", "A", "B", "C", "D", "E") ++ sideEffects("1", "2", "3")
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, actions)))
          .expect(reply(1, Response("ABCDE"), effects))
          .passivate()
      }

      "verify failure action" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(failWith("expected failure")))))
          .expect(actionFailure(1, "expected failure"))
          .passivate()
      }

      "verify connection after failure action" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(emitEvent("A")))))
          .expect(reply(1, Response("A"), events("A")))
          .send(command(2, id, "Process", Request(id, Seq(failWith("expected failure")))))
          .expect(actionFailure(2, "expected failure"))
          .send(command(3, id, "Process", Request(id)))
          .expect(reply(3, Response("A")))
          .passivate()
      }

      "verify failure actions do not retain emitted events, by requesting entity restart" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, emitEvents("A", "B", "C"))))
          .expect(reply(1, Response("ABC"), events("A", "B", "C")))
          .send(command(2, id, "Process", Request(id, Seq(emitEvent("4"), emitEvent("5"), failWith("failure 1")))))
          .expect(actionFailure(2, "failure 1", restart = true))
          .passivate()
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(event(1, persisted("A")))
          .send(event(2, persisted("B")))
          .send(event(3, persisted("C")))
          .send(command(1, id, "Process", Request(id, emitEvents("D"))))
          .expect(reply(1, Response("ABCD"), events("D")))
          .send(command(2, id, "Process", Request(id, Seq(emitEvent("6"), failWith("failure 2"), emitEvent("7")))))
          .expect(actionFailure(2, "failure 2", restart = true))
          .passivate()
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(event(1, persisted("A")))
          .send(event(2, persisted("B")))
          .send(event(3, persisted("C")))
          .send(event(4, persisted("D")))
          .send(command(1, id, "Process", Request(id, emitEvents("E"))))
          .expect(reply(1, Response("ABCDE"), snapshotAndEvents("ABCDE", "E")))
          .passivate()
      }

      "verify failure actions do not allow side effects" in eventSourcedTest { id =>
        protocol.eventSourced
          .connect()
          .send(init(EventSourcedTckModel.name, id))
          .send(command(1, id, "Process", Request(id, Seq(sideEffectTo(id), failWith("expected failure")))))
          .expect(actionFailure(1, "expected failure"))
          .passivate()
      }
    }

    // TODO convert this into a ScalaCheck generated test case
    "verifying app test: event-sourced shopping cart" must {
      import EventSourcedMessages._
      import ShoppingCartVerifier._

      def verifyGetInitialEmptyCart(session: ShoppingCartVerifier, cartId: String): Unit = {
        shoppingCartClient.getCart(GetShoppingCart(cartId)).futureValue mustBe Cart()
        session.verifyConnection()
        session.verifyGetInitialEmptyCart(cartId)
      }

      def verifyGetCart(session: ShoppingCartVerifier, cartId: String, expected: Item*): Unit = {
        val expectedCart = shoppingCart(expected: _*)
        shoppingCartClient.getCart(GetShoppingCart(cartId)).futureValue mustBe expectedCart
        session.verifyGetCart(cartId, expectedCart)
      }

      def verifyAddItem(session: ShoppingCartVerifier, cartId: String, item: Item): Unit = {
        val addLineItem = AddLineItem(cartId, item.id, item.name, item.quantity)
        shoppingCartClient.addItem(addLineItem).futureValue mustBe EmptyScalaMessage
        session.verifyAddItem(cartId, item)
      }

      def verifyRemoveItem(session: ShoppingCartVerifier, cartId: String, itemId: String): Unit = {
        val removeLineItem = RemoveLineItem(cartId, itemId)
        shoppingCartClient.removeItem(removeLineItem).futureValue mustBe EmptyScalaMessage
        session.verifyRemoveItem(cartId, itemId)
      }

      def verifyAddItemFailure(session: ShoppingCartVerifier, cartId: String, item: Item): Unit = {
        val addLineItem = AddLineItem(cartId, item.id, item.name, item.quantity)
        val error = shoppingCartClient.addItem(addLineItem).failed.futureValue
        error mustBe a[StatusRuntimeException]
        val description = error.asInstanceOf[StatusRuntimeException].getStatus.getDescription
        session.verifyAddItemFailure(cartId, item, description)
      }

      def verifyRemoveItemFailure(session: ShoppingCartVerifier, cartId: String, itemId: String): Unit = {
        val removeLineItem = RemoveLineItem(cartId, itemId)
        val error = shoppingCartClient.removeItem(removeLineItem).failed.futureValue
        error mustBe a[StatusRuntimeException]
        val description = error.asInstanceOf[StatusRuntimeException].getStatus.getDescription
        session.verifyRemoveItemFailure(cartId, itemId, description)
      }

      "verify get cart, add item, remove item, and failures" in testFor(ShoppingCart) {
        val session = shoppingCartSession(interceptor)
        verifyGetInitialEmptyCart(session, "cart:1") // initial empty state
        verifyAddItem(session, "cart:1", Item("product:1", "Product1", 1)) // add the first product
        verifyAddItem(session, "cart:1", Item("product:2", "Product2", 2)) // add the second product
        verifyAddItem(session, "cart:1", Item("product:1", "Product1", 11)) // increase first product
        verifyAddItem(session, "cart:1", Item("product:2", "Product2", 31)) // increase second product
        verifyGetCart(session, "cart:1", Item("product:1", "Product1", 12), Item("product:2", "Product2", 33)) // check state
        verifyRemoveItem(session, "cart:1", "product:1") // remove first product
        verifyAddItemFailure(session, "cart:1", Item("product:2", "Product2", -7)) // add negative quantity
        verifyAddItemFailure(session, "cart:1", Item("product:1", "Product1", 0)) // add zero quantity
        verifyRemoveItemFailure(session, "cart:1", "product:1") // remove non-existing product
        verifyGetCart(session, "cart:1", Item("product:2", "Product2", 33)) // check final state
      }
    }

    "verifying proxy test: gRPC ServerReflection API" must {
      "verify that the proxy supports server reflection" in {
        import grpc.reflection.v1alpha.reflection._
        import ServerReflectionRequest.{MessageRequest => In}
        import ServerReflectionResponse.{MessageResponse => Out}

        val expectedServices = Seq(ServerReflection.name) ++ enabledServices.sorted

        val connection = client.serverReflection.connect()

        connection.sendAndExpect(
          In.ListServices(""),
          Out.ListServicesResponse(ListServiceResponse(expectedServices.map(s => ServiceResponse(s))))
        )

        connection.sendAndExpect(
          In.ListServices("nonsense.blabla."),
          Out.ListServicesResponse(ListServiceResponse(expectedServices.map(s => ServiceResponse(s))))
        )

        connection.sendAndExpect(
          In.FileContainingSymbol("nonsense.blabla.Void"),
          Out.FileDescriptorResponse(FileDescriptorResponse(Nil))
        )

        connection.close()
      }
    }

    "verifying proxy test: HTTP API" must {
      "verify the HTTP API for ShoppingCart service" in testFor(ShoppingCart) {
        import ShoppingCartVerifier._

        def checkHttpRequest(path: String, body: String = null)(expected: => String): Unit = {
          val response = client.http.request(path, body)
          val expectedResponse = expected
          response.futureValue mustBe expectedResponse
        }

        val session = shoppingCartSession(interceptor)

        checkHttpRequest("carts/foo") {
          session.verifyConnection()
          session.verifyGetInitialEmptyCart("foo")
          """{"items":[]}"""
        }

        checkHttpRequest("cart/foo/items/add", """{"productId": "A14362347", "name": "Deluxe", "quantity": 5}""") {
          session.verifyAddItem("foo", Item("A14362347", "Deluxe", 5))
          "{}"
        }

        checkHttpRequest("cart/foo/items/add", """{"productId": "B14623482", "name": "Basic", "quantity": 1}""") {
          session.verifyAddItem("foo", Item("B14623482", "Basic", 1))
          "{}"
        }

        checkHttpRequest("cart/foo/items/add", """{"productId": "A14362347", "name": "Deluxe", "quantity": 2}""") {
          session.verifyAddItem("foo", Item("A14362347", "Deluxe", 2))
          "{}"
        }

        checkHttpRequest("carts/foo") {
          session.verifyGetCart("foo", shoppingCart(Item("A14362347", "Deluxe", 7), Item("B14623482", "Basic", 1)))
          """{"items":[{"productId":"A14362347","name":"Deluxe","quantity":7},{"productId":"B14623482","name":"Basic","quantity":1}]}"""
        }

        checkHttpRequest("carts/foo/items") {
          session.verifyGetCart("foo", shoppingCart(Item("A14362347", "Deluxe", 7), Item("B14623482", "Basic", 1)))
          """[{"productId":"A14362347","name":"Deluxe","quantity":7.0},{"productId":"B14623482","name":"Basic","quantity":1.0}]"""
        }

        checkHttpRequest("cart/foo/items/A14362347/remove", "") {
          session.verifyRemoveItem("foo", "A14362347")
          "{}"
        }

        checkHttpRequest("carts/foo") {
          session.verifyGetCart("foo", shoppingCart(Item("B14623482", "Basic", 1)))
          """{"items":[{"productId":"B14623482","name":"Basic","quantity":1}]}"""
        }

        checkHttpRequest("carts/foo/items") {
          session.verifyGetCart("foo", shoppingCart(Item("B14623482", "Basic", 1)))
          """[{"productId":"B14623482","name":"Basic","quantity":1.0}]"""
        }
      }
    }

  }
}

object ShoppingCartVerifier {
  case class Item(id: String, name: String, quantity: Int)

  def shoppingCartSession(interceptor: InterceptService): ShoppingCartVerifier = new ShoppingCartVerifier(interceptor)

  def shoppingCart(items: Item*): Cart = Cart(items.map(i => LineItem(i.id, i.name, i.quantity)))
}

class ShoppingCartVerifier(interceptor: InterceptService) extends MustMatchers {
  import EventSourcedMessages._
  import ShoppingCartVerifier.Item

  private val commandIds = mutable.Map.empty[String, Long]
  private def nextCommandId(cartId: String): Long = commandIds.updateWith(cartId)(_.map(_ + 1).orElse(Some(1L))).get

  private var connection: InterceptEventSourcedService.Connection = _

  def verifyConnection(): Unit = connection = interceptor.expectEventSourcedConnection()

  def verifyGetInitialEmptyCart(cartId: String): Unit = {
    val commandId = nextCommandId(cartId)
    connection.expectClient(init(ShoppingCart.name, cartId))
    connection.expectClient(command(commandId, cartId, "GetCart", GetShoppingCart(cartId)))
    connection.expectService(reply(commandId, Cart()))
    connection.expectNoInteraction()
  }

  def verifyGetCart(cartId: String, expected: Cart): Unit = {
    val commandId = nextCommandId(cartId)
    connection.expectClient(command(commandId, cartId, "GetCart", GetShoppingCart(cartId)))
    connection.expectService(reply(commandId, expected))
    connection.expectNoInteraction()
  }

  def verifyAddItem(cartId: String, item: Item): Unit = {
    val commandId = nextCommandId(cartId)
    val addLineItem = AddLineItem(cartId, item.id, item.name, item.quantity)
    val itemAdded = domain.ItemAdded(Some(domain.LineItem(item.id, item.name, item.quantity)))
    connection.expectClient(command(commandId, cartId, "AddItem", addLineItem))
    // shopping cart implementations may or may not have snapshots configured, so match without snapshot
    val replied = connection.expectServiceMessage[EventSourcedStreamOut.Message.Reply]
    replied.copy(value = replied.value.clearSnapshot) mustBe reply(commandId, EmptyScalaMessage, persist(itemAdded))
    connection.expectNoInteraction()
  }

  def verifyRemoveItem(cartId: String, itemId: String): Unit = {
    val commandId = nextCommandId(cartId)
    val removeLineItem = RemoveLineItem(cartId, itemId)
    val itemRemoved = domain.ItemRemoved(itemId)
    connection.expectClient(command(commandId, cartId, "RemoveItem", removeLineItem))
    // shopping cart implementations may or may not have snapshots configured, so match without snapshot
    val replied = connection.expectServiceMessage[EventSourcedStreamOut.Message.Reply]
    replied.copy(value = replied.value.clearSnapshot) mustBe reply(commandId, EmptyScalaMessage, persist(itemRemoved))
    connection.expectNoInteraction()
  }

  def verifyAddItemFailure(cartId: String, item: Item, failure: String): Unit = {
    val commandId = nextCommandId(cartId)
    val addLineItem = AddLineItem(cartId, item.id, item.name, item.quantity)
    connection.expectClient(command(commandId, cartId, "AddItem", addLineItem))
    connection.expectService(actionFailure(commandId, failure))
    connection.expectNoInteraction()
  }

  def verifyRemoveItemFailure(cartId: String, itemId: String, failure: String): Unit = {
    val commandId = nextCommandId(cartId)
    val removeLineItem = RemoveLineItem(cartId, itemId)
    connection.expectClient(command(commandId, cartId, "RemoveItem", removeLineItem))
    connection.expectService(actionFailure(commandId, failure))
    connection.expectNoInteraction()
  }
}
