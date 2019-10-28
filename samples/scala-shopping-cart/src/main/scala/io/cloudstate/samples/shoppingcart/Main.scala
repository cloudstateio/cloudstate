package io.cloudstate.samples.shoppingcart

import com.example.shoppingcart.ShoppingcartProto
import io.cloudstate.javasupport._

object Main extends App {
  new CloudState()
    .registerEventSourcedEntity(
      classOf[ShoppingCartEntity],
      ShoppingcartProto.javaDescriptor.findServiceByName("ShoppingCart"),
      com.example.shoppingcart.persistence.DomainProto.javaDescriptor
    )
    .start
}
