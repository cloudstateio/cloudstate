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
import com.example.shoppingcart.shoppingcart.{
  AddLineItem,
  Cart,
  GetShoppingCart,
  LineItem,
  RemoveLineItem,
  ShoppingCart
}
import io.cloudstate.protocol.event_sourced.EventSourcedStreamOut
import io.cloudstate.testkit.InterceptService
import io.cloudstate.testkit.eventsourced.{EventSourcedMessages, InterceptEventSourcedService}
import org.scalatest.MustMatchers

import scala.collection.mutable

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
