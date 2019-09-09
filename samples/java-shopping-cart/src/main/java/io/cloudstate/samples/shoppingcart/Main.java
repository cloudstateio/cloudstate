package io.cloudstate.samples.shoppingcart;

import io.cloudstate.javasupport.*;
import com.example.shoppingcart.Shoppingcart;
import static java.util.Collections.singletonMap;

public final class Main {
  public static final void main(String[] args) throws Exception {
    new CloudState()
        .registerEventSourcedEntity(
            ShoppingCartEntity.class,
            Shoppingcart.getDescriptor().findServiceByName("ShoppingCart"),
            com.example.shoppingcart.persistence.Domain.getDescriptor())
        .start()
        .toCompletableFuture()
        .get();
  }
}
