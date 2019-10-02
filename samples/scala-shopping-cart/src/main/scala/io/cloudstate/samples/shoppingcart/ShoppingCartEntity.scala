package io.cloudstate.samples.shoppingcart

import com.example.shoppingcart.persistence.{Cart => DCart, ItemAdded => DItemAdded, ItemRemoved => DItemRemoved, LineItem => DLineItem}
import com.example.shoppingcart.{AddLineItem => SAddLineItem, Cart => SCart, LineItem => SLineItem, RemoveLineItem => SRemoveLineItem}
import com.google.protobuf.Empty
import io.cloudstate.javasupport.EntityId
import io.cloudstate.javasupport.eventsourced._

import scala.collection.mutable

/** An event sourced entity. */
@EventSourcedEntity
class ShoppingCartEntity(@EntityId val entityId: String) {

  private val cart = mutable.Map.empty[String, SLineItem]

  @Snapshot
  def snapshot: DCart =
    DCart(cart.values.map(convert).toSeq)

  @SnapshotHandler
  def handleSnapshot(cart: DCart): Unit = {
    this.cart.clear()
    cart.items.foreach { item =>
      this.cart.put(item.productId, convert(item))
    }
  }

  @EventHandler
  def itemAdded(itemAdded: DItemAdded): Unit = {
    val item = cart
      .get(itemAdded.getItem.productId)
      .map(item => item.copy(quantity = item.quantity + itemAdded.item.map(_.quantity).getOrElse(0)))
      .getOrElse(convert(itemAdded.getItem))
    cart.put(item.productId, item)
  }

  @EventHandler
  def itemRemoved(itemRemoved: DItemRemoved): Unit = cart.remove(itemRemoved.productId)

  @CommandHandler
  def getCart: SCart = SCart(cart.values.toSeq)

  @CommandHandler
  def addItem(item: SAddLineItem, ctx: CommandContext): Empty = {
    if (item.quantity <= 0) ctx.fail("Cannot add negative quantity of to item" + item.productId)
    ctx.emit(
      DItemAdded(Some(DLineItem(item.productId, item.name, item.quantity)))
    )
    Empty.getDefaultInstance
  }

  @CommandHandler
  def removeItem(item: SRemoveLineItem, ctx: CommandContext): Empty = {
    if (!cart.contains(item.productId)) {
      ctx.fail("Cannot remove item " + item.productId + " because it is not in the cart.")
    }
    ctx.emit(DItemRemoved(item.productId))
    Empty.getDefaultInstance
  }

  private def convert(item: DLineItem) = SLineItem(item.productId, item.name, item.quantity)

  private def convert(item: SLineItem) = DLineItem(item.productId, item.name, item.quantity)
}
