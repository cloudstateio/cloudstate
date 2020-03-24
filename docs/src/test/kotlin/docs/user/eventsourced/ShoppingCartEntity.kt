package com.example.shoppingcart

import com.example.shoppingcart.Shoppingcart
import com.example.shoppingcart.persistence.Domain
import com.google.protobuf.Empty
import io.cloudstate.javasupport.EntityId
import io.cloudstate.javasupport.eventsourced.CommandContext
import io.cloudstate.kotlinsupport.api.eventsourced.*

import java.util.stream.Collectors
import com.example.shoppingcart.persistence.Domain

// #constructing
class ShoppingCartEntity(@param:EntityId private val entityId: String)
// #constructing

// #entity-class
@EventSourcedEntity
class ShoppingCartEntity(@param:EntityId private val entityId: String) {
// #entity-class

    // #entity-state
    private val cart: MutableMap<String, Shoppingcart.LineItem?> = mutableMapOf<String, Shoppingcart.LineItem?>()
    // #entity-state

    // #snapshot
    @Snapshot
    fun snapshot(): Domain.Cart =
            Domain.Cart.newBuilder()
                    .addAllItems(
                            cart.values.stream()
                                    .map { item: Shoppingcart.LineItem? -> this.convert(item) }
                                    .collect(Collectors.toList())
                    )
                    .build()

    private fun convert(item: Shoppingcart.LineItem?): Domain.LineItem =
            Domain.LineItem.newBuilder()
                    .setProductId(item!!.productId)
                    .setName(item.name)
                    .setQuantity(item.quantity)
                    .build()
    // #snapshot

    // #handle-snapshot
    @SnapshotHandler
    fun handleSnapshot(cart: Domain.Cart) {
        this.cart.clear()
        for (item in cart.itemsList) {
            this.cart[item.productId] = convert(item)
        }
    }
    // #handle-snapshot

    // #item-added
    @EventHandler
    fun itemAdded(itemAdded: Domain.ItemAdded) {
        var item = cart[itemAdded.item.productId]

        item = if (item == null) {
            convert(itemAdded.item)
        } else {
            item.toBuilder()
                    .setQuantity(item.quantity + itemAdded.item.quantity)
                    .build()
        }
        cart[item!!.productId] = item
    }
    // #item-added

    // #item-removed
    @EventHandler
    fun itemRemoved(itemRemoved: Domain.ItemRemoved): Shoppingcart.LineItem? = cart.remove(itemRemoved.productId)

    private fun convert(item: Domain.LineItem): Shoppingcart.LineItem =
            Shoppingcart.LineItem.newBuilder()
                    .setProductId(item.productId)
                    .setName(item.name)
                    .setQuantity(item.quantity)
                    .build()
    // #item-removed

    // #get-cart
    @CommandHandler
    fun getCart(): Shoppingcart.Cart = Shoppingcart.Cart.newBuilder().addAllItems(cart.values).build()
    // #get-cart

    // #add-item
    @CommandHandler
    fun addItem(item: Shoppingcart.AddLineItem, ctx: CommandContext): Empty {
        if (item.quantity <= 0) {
            ctx.fail("Cannot add negative quantity of to item ${item.productId}" )
        }
        ctx.emit(
                Domain.ItemAdded.newBuilder()
                        .setItem(
                                Domain.LineItem.newBuilder()
                                        .setProductId(item.productId)
                                        .setName(item.name)
                                        .setQuantity(item.quantity)
                                        .build())
                        .build())
        return Empty.getDefaultInstance()
    }
    // #add-item

    @CommandHandler
    fun removeItem(item: Shoppingcart.RemoveLineItem, ctx: CommandContext): Empty {
        if (!cart.containsKey(item.productId)) {
            ctx.fail("Cannot remove item ${item.productId} because it is not in the cart.")
        }
        ctx.emit(
                Domain.ItemRemoved.newBuilder()
                        .setProductId(item.productId)
                        .build())
        return Empty.getDefaultInstance()
    }

    // #register
    fun main() {
        cloudstate {
            registerEventSourcedEntity {
                entityService = ShoppingCartEntity::class.java
                descriptor = Shoppingcart.getDescriptor().findServiceByName("ShoppingCart")
                additionalDescriptors = arrayOf( Domain.getDescriptor() )
            }
        }.start()
                .toCompletableFuture()
                .get()
    }
    // #register

    // #options
    fun main() {
        cloudstate {

            host = "0.0.0.0"
            port = 8080
            loglevel = "DEBUG"

            registerEventSourcedEntity {
                entityService = ShoppingCartEntity::class.java
                descriptor = Shoppingcart.getDescriptor().findServiceByName("ShoppingCart")
                additionalDescriptors = arrayOf( Domain.getDescriptor() )

                snapshotEvery = 1
                persistenceId = "shopping-cart"
            }
        }.start()
                .toCompletableFuture()
                .get()
    }
    // #options

}