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
import com.example.valueentity.shoppingcart.shoppingcart.{Cart => ValueEntityCart}
import com.example.valueentity.shoppingcart.shoppingcart._
import com.example.valueentity.shoppingcart.persistence.domain
import io.cloudstate.protocol.value_entity.ValueEntityStreamOut
import io.cloudstate.testkit.valueentity.{InterceptValueEntityService, ValueEntityMessages}
import org.scalatest.MustMatchers

import scala.collection.mutable

object ValueEntityShoppingCartVerifier {
  case class Item(id: String, name: String, quantity: Int)
  case class Cart(items: Item*)

  def shoppingCartSession(interceptor: InterceptService): ValueEntityShoppingCartVerifier =
    new ValueEntityShoppingCartVerifier(interceptor)

  def shoppingCart(items: Item*): ValueEntityCart = ValueEntityCart(items.map(i => LineItem(i.id, i.name, i.quantity)))

  def domainShoppingCart(cart: Cart): domain.Cart =
    domain.Cart(cart.items.map(i => domain.LineItem(i.id, i.name, i.quantity)))
}

class ValueEntityShoppingCartVerifier(interceptor: InterceptService) extends MustMatchers {
  import ValueEntityMessages._
  import ValueEntityShoppingCartVerifier._

  private val commandIds = mutable.Map.empty[String, Long]
  private var connection: InterceptValueEntityService.Connection = _

  private def nextCommandId(cartId: String): Long = commandIds.updateWith(cartId)(_.map(_ + 1).orElse(Some(1L))).get

  def verifyConnection(): Unit = connection = interceptor.expectCrudConnection()

  def verifyGetInitialEmptyCart(cartId: String): Unit = {
    val commandId = nextCommandId(cartId)
    connection.expectClient(init(ShoppingCart.name, cartId))
    connection.expectClient(command(commandId, cartId, "GetCart", GetShoppingCart(cartId)))
    connection.expectService(reply(commandId, ValueEntityCart()))
    connection.expectNoInteraction()
  }

  def verifyGetCart(cartId: String, expected: ValueEntityCart): Unit = {
    val commandId = nextCommandId(cartId)
    connection.expectClient(command(commandId, cartId, "GetCart", GetShoppingCart(cartId)))
    connection.expectService(reply(commandId, expected))
    connection.expectNoInteraction()
  }

  def verifyAddItem(cartId: String, item: Item, expected: Cart): Unit = {
    val commandId = nextCommandId(cartId)
    val addLineItem = AddLineItem(cartId, item.id, item.name, item.quantity)
    val cartUpdated = domainShoppingCart(expected)
    connection.expectClient(command(commandId, cartId, "AddItem", addLineItem))
    val replied = connection.expectServiceMessage[ValueEntityStreamOut.Message.Reply]
    replied mustBe reply(commandId, EmptyScalaMessage, update(cartUpdated))
    connection.expectNoInteraction()
  }

  def verifyRemoveItem(cartId: String, itemId: String, expected: Cart): Unit = {
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
