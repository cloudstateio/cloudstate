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

import io.cloudstate.testkit.InterceptService
import com.example.valueentity.shoppingcart.shoppingcart._
import com.example.valueentity.shoppingcart.persistence.domain
import io.cloudstate.protocol.value_entity.{ValueEntity, ValueEntityStreamOut}
import io.cloudstate.testkit.valueentity.{InterceptValueEntityService, ValueEntityMessages}
import io.grpc.StatusRuntimeException
import org.scalatest.MustMatchers
import scala.collection.mutable

trait ShoppingCartTCK extends TCKSpec {

  object ValueEntityShoppingCart {
    import ValueEntityMessages._
    import ValueEntityShoppingCartVerifier._

    val Service: String = ShoppingCart.name

    private val shoppingCartClient = ShoppingCartClient(client.settings)(client.system)

    def terminate(): Unit = shoppingCartClient.close()

    def verifyGetInitialEmptyCart(session: ValueEntityShoppingCartVerifier, cartId: String): Unit = {
      shoppingCartClient.getCart(GetShoppingCart(cartId)).futureValue mustBe Cart()
      session.verifyConnection()
      session.verifyGetInitialEmptyCart(cartId)
    }

    def verifyGetCart(session: ValueEntityShoppingCartVerifier, cartId: String, expected: Item*): Unit = {
      val expectedCart = shoppingCart(expected: _*)
      shoppingCartClient.getCart(GetShoppingCart(cartId)).futureValue mustBe expectedCart
      session.verifyGetCart(cartId, expectedCart)
    }

    def verifyAddItem(session: ValueEntityShoppingCartVerifier,
                      cartId: String,
                      item: Item,
                      expected: CartValue): Unit = {
      val addLineItem = AddLineItem(cartId, item.id, item.name, item.quantity)
      shoppingCartClient.addItem(addLineItem).futureValue mustBe EmptyScalaMessage
      session.verifyAddItem(cartId, item, expected)
    }

    def verifyRemoveItem(session: ValueEntityShoppingCartVerifier,
                         cartId: String,
                         itemId: String,
                         expected: CartValue): Unit = {
      val removeLineItem = RemoveLineItem(cartId, itemId)
      shoppingCartClient.removeItem(removeLineItem).futureValue mustBe EmptyScalaMessage
      session.verifyRemoveItem(cartId, itemId, expected)
    }

    def verifyAddItemFailure(session: ValueEntityShoppingCartVerifier, cartId: String, item: Item): Unit = {
      val addLineItem = AddLineItem(cartId, item.id, item.name, item.quantity)
      val error = shoppingCartClient.addItem(addLineItem).failed.futureValue
      error mustBe a[StatusRuntimeException]
      val description = error.asInstanceOf[StatusRuntimeException].getStatus.getDescription
      session.verifyAddItemFailure(cartId, item, description)
    }

    def verifyRemoveItemFailure(session: ValueEntityShoppingCartVerifier, cartId: String, itemId: String): Unit = {
      val removeLineItem = RemoveLineItem(cartId, itemId)
      val error = shoppingCartClient.removeItem(removeLineItem).failed.futureValue
      error mustBe a[StatusRuntimeException]
      val description = error.asInstanceOf[StatusRuntimeException].getStatus.getDescription
      session.verifyRemoveItemFailure(cartId, itemId, description)
    }
  }

  override def afterAll(): Unit =
    try ValueEntityShoppingCart.terminate()
    finally super.afterAll()

  def verifyShoppingCart(): Unit = {
    import ValueEntityShoppingCart._
    import ValueEntityShoppingCartVerifier._

    "verify shopping cart discovery" in testFor(ShoppingCart) {
      discoveredServices must contain("ShoppingCart")
      entity(ValueEntityShoppingCart.Service).value.entityType mustBe ValueEntity.name
      entity(ValueEntityShoppingCart.Service).value.persistenceId must not be empty
    }

    "verify get cart, add item, remove item, and failures" in testFor(ShoppingCart) {
      val session = shoppingCartSession(interceptor)
      verifyGetInitialEmptyCart(session, "cart:1") // initial empty state

      // add the first product and pass the expected state
      verifyAddItem(session, "cart:1", Item("product:1", "Product1", 1), CartValue(Item("product:1", "Product1", 1)))

      // add the second product and pass the expected state
      verifyAddItem(
        session,
        "cart:1",
        Item("product:2", "Product2", 2),
        CartValue(Item("product:1", "Product1", 1), Item("product:2", "Product2", 2))
      )

      // increase first product and pass the expected state
      verifyAddItem(
        session,
        "cart:1",
        Item("product:1", "Product1", 11),
        CartValue(Item("product:1", "Product1", 12), Item("product:2", "Product2", 2))
      )

      // increase second product and pass the expected state
      verifyAddItem(
        session,
        "cart:1",
        Item("product:2", "Product2", 31),
        CartValue(Item("product:1", "Product1", 12), Item("product:2", "Product2", 33))
      )

      verifyGetCart(session, "cart:1", Item("product:1", "Product1", 12), Item("product:2", "Product2", 33)) // check state

      // remove first product and pass the expected state
      verifyRemoveItem(session, "cart:1", "product:1", CartValue(Item("product:2", "Product2", 33)))
      verifyAddItemFailure(session, "cart:1", Item("product:2", "Product2", -7)) // add negative quantity
      verifyAddItemFailure(session, "cart:1", Item("product:1", "Product1", 0)) // add zero quantity
      verifyRemoveItemFailure(session, "cart:1", "product:1") // remove non-existing product
      verifyGetCart(session, "cart:1", Item("product:2", "Product2", 33)) // check final state
    }

    "verify the HTTP API for shopping cart" in testFor(ShoppingCart) {
      import ValueEntityShoppingCartVerifier._

      def checkHttpRequest(path: String, body: String = null)(expected: => String): Unit = {
        val response = client.http.request(path, body)
        val expectedResponse = expected
        response.futureValue mustBe expectedResponse
      }

      val session = shoppingCartSession(interceptor)

      checkHttpRequest("ve/carts/foo") {
        session.verifyConnection()
        session.verifyGetInitialEmptyCart("foo")
        """{"items":[]}"""
      }

      checkHttpRequest("ve/cart/foo/items/add", """{"productId": "A14362347", "name": "Deluxe", "quantity": 5}""") {
        session.verifyAddItem("foo", Item("A14362347", "Deluxe", 5), CartValue(Item("A14362347", "Deluxe", 5)))
        "{}"
      }

      checkHttpRequest("ve/cart/foo/items/add", """{"productId": "B14623482", "name": "Basic", "quantity": 1}""") {
        session.verifyAddItem("foo",
                              Item("B14623482", "Basic", 1),
                              CartValue(Item("A14362347", "Deluxe", 5), Item("B14623482", "Basic", 1)))
        "{}"
      }

      checkHttpRequest("ve/cart/foo/items/add", """{"productId": "A14362347", "name": "Deluxe", "quantity": 2}""") {
        session.verifyAddItem("foo",
                              Item("A14362347", "Deluxe", 2),
                              CartValue(Item("A14362347", "Deluxe", 7), Item("B14623482", "Basic", 1)))
        "{}"
      }

      checkHttpRequest("ve/carts/foo") {
        session.verifyGetCart("foo", shoppingCart(Item("A14362347", "Deluxe", 7), Item("B14623482", "Basic", 1)))
        """{"items":[{"productId":"A14362347","name":"Deluxe","quantity":7},{"productId":"B14623482","name":"Basic","quantity":1}]}"""
      }

      checkHttpRequest("ve/carts/foo/items") {
        session.verifyGetCart("foo", shoppingCart(Item("A14362347", "Deluxe", 7), Item("B14623482", "Basic", 1)))
        """[{"productId":"A14362347","name":"Deluxe","quantity":7.0},{"productId":"B14623482","name":"Basic","quantity":1.0}]"""
      }

      checkHttpRequest("ve/cart/foo/items/A14362347/remove", "") {
        session.verifyRemoveItem("foo", "A14362347", CartValue(Item("B14623482", "Basic", 1)))
        "{}"
      }

      checkHttpRequest("ve/carts/foo") {
        session.verifyGetCart("foo", shoppingCart(Item("B14623482", "Basic", 1)))
        """{"items":[{"productId":"B14623482","name":"Basic","quantity":1}]}"""
      }

      checkHttpRequest("ve/carts/foo/items") {
        session.verifyGetCart("foo", shoppingCart(Item("B14623482", "Basic", 1)))
        """[{"productId":"B14623482","name":"Basic","quantity":1.0}]"""
      }

      checkHttpRequest("ve/carts/foo/remove", """{"userId": "foo"}""") {
        session.verifyRemoveCart("foo")
        "{}"
      }

      checkHttpRequest("ve/carts/foo") {
        session.verifyGetCart("foo", shoppingCart())
        """{"items":[]}"""
      }
    }
  }
}

object ValueEntityShoppingCartVerifier {
  case class Item(id: String, name: String, quantity: Int)
  case class CartValue(items: Item*)

  def shoppingCartSession(interceptor: InterceptService): ValueEntityShoppingCartVerifier =
    new ValueEntityShoppingCartVerifier(interceptor)

  def shoppingCart(items: Item*): Cart = Cart(items.map(i => LineItem(i.id, i.name, i.quantity)))

  def domainShoppingCart(cart: CartValue): domain.Cart =
    domain.Cart(cart.items.map(i => domain.LineItem(i.id, i.name, i.quantity)))
}

class ValueEntityShoppingCartVerifier(interceptor: InterceptService) extends MustMatchers {
  import ValueEntityMessages._
  import ValueEntityShoppingCartVerifier._

  private val commandIds = mutable.Map.empty[String, Long]
  private var connection: InterceptValueEntityService.Connection = _

  private def nextCommandId(cartId: String): Long = commandIds.updateWith(cartId)(_.map(_ + 1).orElse(Some(1L))).get

  def verifyConnection(): Unit = connection = interceptor.expectValueBasedConnection()

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

  def verifyAddItem(cartId: String, item: Item, expected: CartValue): Unit = {
    val commandId = nextCommandId(cartId)
    val addLineItem = AddLineItem(cartId, item.id, item.name, item.quantity)
    val cartUpdated = domainShoppingCart(expected)
    connection.expectClient(command(commandId, cartId, "AddItem", addLineItem))
    val replied = connection.expectServiceMessage[ValueEntityStreamOut.Message.Reply]
    replied mustBe reply(commandId, EmptyScalaMessage, update(cartUpdated))
    connection.expectNoInteraction()
  }

  def verifyRemoveItem(cartId: String, itemId: String, expected: CartValue): Unit = {
    val commandId = nextCommandId(cartId)
    val removeLineItem = RemoveLineItem(cartId, itemId)
    val cartUpdated = domainShoppingCart(expected)
    connection.expectClient(command(commandId, cartId, "RemoveItem", removeLineItem))
    val replied = connection.expectServiceMessage[ValueEntityStreamOut.Message.Reply]
    replied mustBe reply(commandId, EmptyScalaMessage, update(cartUpdated))
    connection.expectNoInteraction()
  }

  def verifyRemoveCart(cartId: String): Unit = {
    val commandId = nextCommandId(cartId)
    val removeCart = RemoveShoppingCart(cartId)
    connection.expectClient(command(commandId, cartId, "RemoveCart", removeCart))
    val replied = connection.expectServiceMessage[ValueEntityStreamOut.Message.Reply]
    replied mustBe reply(commandId, EmptyScalaMessage, delete())
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
