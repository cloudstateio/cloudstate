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

import com.example.shoppingcart.persistence.domain
import com.example.shoppingcart.shoppingcart._
import io.cloudstate.protocol.event_sourced.{EventSourced, EventSourcedStreamOut}
import io.cloudstate.testkit.InterceptService
import io.cloudstate.testkit.eventsourced.{EventSourcedMessages, InterceptEventSourcedService}
import io.grpc.StatusRuntimeException
import org.scalatest.MustMatchers
import scala.collection.mutable

trait EventSourcedShoppingCartTCK extends TCKSpec {

  object EventSourcedShoppingCart {
    import EventSourcedMessages._
    import EventSourcedShoppingCartVerifier._

    val Service: String = ShoppingCart.name

    private val shoppingCartClient = ShoppingCartClient(client.settings)(client.system)

    def terminate(): Unit = shoppingCartClient.close()

    def verifyGetInitialEmptyCart(session: EventSourcedShoppingCartVerifier, cartId: String): Unit = {
      shoppingCartClient.getCart(GetShoppingCart(cartId)).futureValue mustBe Cart()
      session.verifyConnection()
      session.verifyGetInitialEmptyCart(cartId)
    }

    def verifyGetCart(session: EventSourcedShoppingCartVerifier, cartId: String, expected: Item*): Unit = {
      val expectedCart = shoppingCart(expected: _*)
      shoppingCartClient.getCart(GetShoppingCart(cartId)).futureValue mustBe expectedCart
      session.verifyGetCart(cartId, expectedCart)
    }

    def verifyAddItem(session: EventSourcedShoppingCartVerifier, cartId: String, item: Item): Unit = {
      val addLineItem = AddLineItem(cartId, item.id, item.name, item.quantity)
      shoppingCartClient.addItem(addLineItem).futureValue mustBe EmptyScalaMessage
      session.verifyAddItem(cartId, item)
    }

    def verifyRemoveItem(session: EventSourcedShoppingCartVerifier, cartId: String, itemId: String): Unit = {
      val removeLineItem = RemoveLineItem(cartId, itemId)
      shoppingCartClient.removeItem(removeLineItem).futureValue mustBe EmptyScalaMessage
      session.verifyRemoveItem(cartId, itemId)
    }

    def verifyAddItemFailure(session: EventSourcedShoppingCartVerifier, cartId: String, item: Item): Unit = {
      val addLineItem = AddLineItem(cartId, item.id, item.name, item.quantity)
      val error = shoppingCartClient.addItem(addLineItem).failed.futureValue
      error mustBe a[StatusRuntimeException]
      val description = error.asInstanceOf[StatusRuntimeException].getStatus.getDescription
      session.verifyAddItemFailure(cartId, item, description)
    }

    def verifyRemoveItemFailure(session: EventSourcedShoppingCartVerifier, cartId: String, itemId: String): Unit = {
      val removeLineItem = RemoveLineItem(cartId, itemId)
      val error = shoppingCartClient.removeItem(removeLineItem).failed.futureValue
      error mustBe a[StatusRuntimeException]
      val description = error.asInstanceOf[StatusRuntimeException].getStatus.getDescription
      session.verifyRemoveItemFailure(cartId, itemId, description)
    }
  }

  override def afterAll(): Unit =
    try EventSourcedShoppingCart.terminate()
    finally super.afterAll()

  def verifyEventSourcedShoppingCart(): Unit = {
    import EventSourcedShoppingCart._
    import EventSourcedShoppingCartVerifier._

    "verify event sourced shopping cart discovery" in testFor(ShoppingCart) {
      discoveredServices must contain("ShoppingCart")
      entity(EventSourcedShoppingCart.Service).value.entityType mustBe EventSourced.name
      entity(EventSourcedShoppingCart.Service).value.persistenceId must not be empty
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

    "verify the HTTP API for event sourced shopping cart" in testFor(ShoppingCart) {
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

object EventSourcedShoppingCartVerifier {
  case class Item(id: String, name: String, quantity: Int)

  def shoppingCartSession(interceptor: InterceptService): EventSourcedShoppingCartVerifier =
    new EventSourcedShoppingCartVerifier(interceptor)

  def shoppingCart(items: Item*): Cart = Cart(items.map(i => LineItem(i.id, i.name, i.quantity)))
}

class EventSourcedShoppingCartVerifier(interceptor: InterceptService) extends MustMatchers {
  import EventSourcedMessages._
  import EventSourcedShoppingCartVerifier.Item

  private val commandIds = mutable.Map.empty[String, Long]
  private var connection: InterceptEventSourcedService.Connection = _

  private def nextCommandId(cartId: String): Long = commandIds.updateWith(cartId)(_.map(_ + 1).orElse(Some(1L))).get

  def verifyConnection(): Unit = connection = interceptor.expectEventSourcedEntityConnection()

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
