package io.cloudstate.samples.shoppingcart;

import io.cloudstate.javasupport.*;
import com.example.shoppingcart.Shoppingcart;

public final class Main {
  public static final void main(String[] args) throws Exception {
    // it would be better to register this descriptor
    // io.cloudstate.keyvalue.KeyValue.getDescriptor() in the registerCrudEntity
    // so it is implicit to the user
    new CloudState()
        .registerCrudEntity(
            ShoppingCartCrudEntity.class,
            Shoppingcart.getDescriptor().findServiceByName("ShoppingCart"),
            com.example.shoppingcart.crud.persistence.Domain.getDescriptor(),
            io.cloudstate.keyvalue.KeyValue.getDescriptor())
        .start()
        .toCompletableFuture()
        .get();
  }
}
