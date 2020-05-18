package io.cloudstate.samples.shoppingcart;

import com.example.crud.shoppingcart.Shoppingcart;
import com.example.crud.shoppingcart.persistence.Domain;
import com.google.protobuf.Empty;
import io.cloudstate.javasupport.EntityId;
import io.cloudstate.javasupport.crud.CommandContext;
import io.cloudstate.javasupport.crud.CommandHandler;
import io.cloudstate.javasupport.crud.CrudEntity;
import io.cloudstate.javasupport.crud.SnapshotHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/** A crud entity. */
@CrudEntity
public class ShoppingCartCrudEntity {
  private final String entityId;
  private final Map<String, Shoppingcart.LineItem> cart = new LinkedHashMap<>();

  public ShoppingCartCrudEntity(@EntityId String entityId) {
    this.entityId = entityId;
  }

  @SnapshotHandler
  public void handleState(Domain.Cart cart) {
    this.cart.clear();
    for (Domain.LineItem item : cart.getItemsList()) {
      this.cart.put(item.getProductId(), convert(item));
    }
  }

  @CommandHandler
  public Shoppingcart.Cart getCart() {
    return Shoppingcart.Cart.newBuilder().addAllItems(cart.values()).build();
  }

  @CommandHandler
  public Empty addItem(Shoppingcart.AddLineItem item, CommandContext ctx) {
    if (item.getQuantity() <= 0) {
      ctx.fail("Cannot add negative quantity of to item" + item.getProductId());
    }

    Domain.LineItem lineItem =
        Domain.LineItem.newBuilder()
            .setUserId(item.getUserId())
            .setProductId(item.getProductId())
            .setName(item.getName())
            .setQuantity(item.getQuantity())
            .build();
    ctx.emit(Domain.Cart.newBuilder().addItems(lineItem).build());
    return Empty.getDefaultInstance();
  }

  private Shoppingcart.LineItem convert(Domain.LineItem item) {
    return Shoppingcart.LineItem.newBuilder()
        .setProductId(item.getProductId())
        .setName(item.getName())
        .setQuantity(item.getQuantity())
        .build();
  }
}
