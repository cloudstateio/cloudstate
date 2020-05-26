package io.cloudstate.samples.shoppingcart;

import com.example.crud.shoppingcart.Shoppingcart;
import io.cloudstate.javasupport.*;

public final class Main {
  public static final void main(String[] args) throws Exception {
    new CloudState()
        .registerCrudEntity(
            ShoppingCartCrudEntity.class,
            Shoppingcart.getDescriptor().findServiceByName("ShoppingCart"),
            com.example.crud.shoppingcart.persistence.Domain.getDescriptor())
        .start()
        .toCompletableFuture()
        .get();
  }
}
