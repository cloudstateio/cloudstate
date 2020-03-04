package com.example.shoppingcart

// #shopping-cart-main
import ShoppingCartEntity
import io.cloudstate.kotlinsupport.cloudstate
import com.example.shoppingcart.persistence.Domain

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
// #shopping-cart-main
