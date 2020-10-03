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
import com.example.crud.shoppingcart.shoppingcart.{
  LineItem,
  RemoveShoppingCart,
  AddLineItem => CrudAddLineItem,
  Cart => CrudCart,
  GetShoppingCart => CrudGetShoppingCart,
  RemoveLineItem => CrudRemoveLineItem,
  ShoppingCart => CrudShoppingCart
}
import com.example.crud.shoppingcart.persistence.domain
import io.cloudstate.protocol.crud.CrudStreamOut
import io.cloudstate.testkit.crud.{CrudMessages, InterceptCrudService}
import org.scalatest.MustMatchers

import scala.collection.mutable

object CrudShoppingCartVerifier {
  case class Item(id: String, name: String, quantity: Int)
  case class Cart(items: Item*)

  def shoppingCartSession(interceptor: InterceptService): CrudShoppingCartVerifier =
    new CrudShoppingCartVerifier(interceptor)

  def shoppingCart(items: Item*): CrudCart = CrudCart(items.map(i => LineItem(i.id, i.name, i.quantity)))

  def domainShoppingCart(cart: Cart): domain.Cart =
    domain.Cart(cart.items.map(i => domain.LineItem(i.id, i.name, i.quantity)))
}

class CrudShoppingCartVerifier(interceptor: InterceptService) extends MustMatchers {
  import CrudMessages._
  import CrudShoppingCartVerifier._

  private val commandIds = mutable.Map.empty[String, Long]
  private var connection: InterceptCrudService.Connection = _

  private def nextCommandId(cartId: String): Long = commandIds.updateWith(cartId)(_.map(_ + 1).orElse(Some(1L))).get

  def verifyConnection(): Unit = connection = interceptor.expectCrudConnection()

  def verifyGetInitialEmptyCart(cartId: String): Unit = {
    val commandId = nextCommandId(cartId)
    connection.expectClient(init(CrudShoppingCart.name, cartId))
    connection.expectClient(command(commandId, cartId, "GetCart", CrudGetShoppingCart(cartId)))
    connection.expectService(reply(commandId, CrudCart()))
    connection.expectNoInteraction()
  }

  def verifyGetCart(cartId: String, expected: CrudCart): Unit = {
    val commandId = nextCommandId(cartId)
    connection.expectClient(command(commandId, cartId, "GetCart", CrudGetShoppingCart(cartId)))
    connection.expectService(reply(commandId, expected))
    connection.expectNoInteraction()
  }

  def verifyAddItem(cartId: String, item: Item, expected: Cart): Unit = {
    val commandId = nextCommandId(cartId)
    val addLineItem = CrudAddLineItem(cartId, item.id, item.name, item.quantity)
    val cartUpdated = domainShoppingCart(expected)
    connection.expectClient(command(commandId, cartId, "AddItem", addLineItem))
    val replied = connection.expectServiceMessage[CrudStreamOut.Message.Reply]
    replied mustBe reply(commandId, EmptyScalaMessage, update(cartUpdated))
    connection.expectNoInteraction()
  }

  def verifyRemoveItem(cartId: String, itemId: String, expected: Cart): Unit = {
    val commandId = nextCommandId(cartId)
    val removeLineItem = CrudRemoveLineItem(cartId, itemId)
    val cartUpdated = domainShoppingCart(expected)
    connection.expectClient(command(commandId, cartId, "RemoveItem", removeLineItem))
    val replied = connection.expectServiceMessage[CrudStreamOut.Message.Reply]
    replied mustBe reply(commandId, EmptyScalaMessage, update(cartUpdated))
    connection.expectNoInteraction()
  }

  def verifyRemoveCart(cartId: String): Unit = {
    val commandId = nextCommandId(cartId)
    val removeCart = RemoveShoppingCart(cartId)
    connection.expectClient(command(commandId, cartId, "RemoveCart", removeCart))
    val replied = connection.expectServiceMessage[CrudStreamOut.Message.Reply]
    replied mustBe reply(commandId, EmptyScalaMessage, delete())
    connection.expectNoInteraction()
  }

  def verifyAddItemFailure(cartId: String, item: Item, failure: String): Unit = {
    val commandId = nextCommandId(cartId)
    val addLineItem = CrudAddLineItem(cartId, item.id, item.name, item.quantity)
    connection.expectClient(command(commandId, cartId, "AddItem", addLineItem))
    connection.expectService(actionFailure(commandId, failure))
    connection.expectNoInteraction()
  }

  def verifyRemoveItemFailure(cartId: String, itemId: String, failure: String): Unit = {
    val commandId = nextCommandId(cartId)
    val removeLineItem = CrudRemoveLineItem(cartId, itemId)
    connection.expectClient(command(commandId, cartId, "RemoveItem", removeLineItem))
    connection.expectService(actionFailure(commandId, failure))
    connection.expectNoInteraction()
  }
}
