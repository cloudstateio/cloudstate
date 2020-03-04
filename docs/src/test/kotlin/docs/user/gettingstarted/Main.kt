package com.example.shoppingcart

import com.google.protobuf.Empty
import io.cloudstate.kotlinsupport.annotations.CommandHandler
import io.cloudstate.kotlinsupport.annotations.EventHandler
import io.cloudstate.kotlinsupport.annotations.Snapshot
import io.cloudstate.kotlinsupport.annotations.SnapshotHandler
import io.cloudstate.kotlinsupport.cloudstate
import io.cloudstate.kotlinsupport.services.eventsourced.AnnotationEventSourcedEntity
import io.cloudstate.kotlinsupport.services.eventsourced.FunctionalEventSourcedEntity
import java.util.stream.Collectors

import com.example.shoppingcart.persistence.Domain
import com.example.shoppingcart.persistence.Domain.LineItem as DomainLineItem
import com.example.shoppingcart.persistence.Domain.ItemAdded as DomainItemAdded
import com.example.shoppingcart.persistence.Domain.ItemRemoved as DomainItemRemoved

class Main {

    companion object {

        @JvmStatic
        fun main(args: Array<String>) {

            cloudstate {

                serviceName = "shopping-cart"
                serviceVersion = "1.0.0"

                registerEventSourcedEntity {
                    entityService = ShoppingCartAnnotationEntity::class.java

                    descriptor = Shoppingcart.getDescriptor().findServiceByName("ShoppingCart")
                    additionalDescriptors = arrayOf(
                            com.example.shoppingcart.persistence.Domain.getDescriptor())
                }
            }.start()
                .toCompletableFuture()
                .get()

        }
    }

 
