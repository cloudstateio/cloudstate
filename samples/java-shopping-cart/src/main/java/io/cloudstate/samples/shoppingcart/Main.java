package io.cloudstate.samples.shoppingcart;

import io.cloudstate.javasupport.*;
import com.example.shoppingcart.Shoppingcart;

public final class Main {
  public static final void main(String[] args) throws Exception {
    new CloudState()
        .registerCrudEntity(
            ShoppingCartCrudEntity.class,
            Shoppingcart.getDescriptor().findServiceByName("ShoppingCart"),
            com.example.shoppingcart.crud.persistence.Domain.getDescriptor())
        .start()
        .toCompletableFuture()
        .get();
  }
}
