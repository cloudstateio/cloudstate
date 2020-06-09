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
import java.util.stream.Collectors;

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
    // HINTS: this is called by the proxy for updating the state
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
    // HINTS: CRUD Update
    // HINTS: curl -vi -X POST localhost:9000/cart/{user_id}/items/add -H "Content-Type:
    // application/json" -d '{"commandType":"update", "productId":"foo","name":"A
    // foo","quantity":10}'
    if (item.getQuantity() <= 0) {
      ctx.fail("Cannot add negative quantity of to item" + item.getProductId());
    }

    Domain.LineItem lineItem =
        cart.get(item.getProductId()) == null ? null : convert(cart.get(item.getProductId()));
    if (lineItem == null) {
      lineItem =
          Domain.LineItem.newBuilder()
              .setUserId(item.getUserId())
              .setProductId(item.getProductId())
              .setName(item.getName())
              .setQuantity(item.getQuantity())
              .build();
    } else {
      lineItem =
          lineItem.toBuilder().setQuantity(item.getQuantity() + lineItem.getQuantity()).build();
    }
    Domain.Cart cart = convert(this.cart).toBuilder().addItems(lineItem).build(); // new state
    ctx.emit(cart); // emit new state

    return Empty.getDefaultInstance();
  }

  @CommandHandler
  public Empty removeItem(Shoppingcart.RemoveLineItem item, CommandContext ctx) {
    // HINTS: CRUD Delete
    // HINTS: curl -vi -X POST localhost:9000/cart/{user_id}/items/{product_id}/remove -H
    // "Content-Type: application/json" -d '{"commandType":"delete", "productId":"foo"}'

    if (!cart.containsKey(item.getProductId())) {
      ctx.fail("Cannot remove item " + item.getProductId() + " because it is not in the cart.");
    }

    cart.remove(item.getProductId());
    ctx.emit(convert(cart)); // emit the new state

    return Empty.getDefaultInstance();
  }

  private Shoppingcart.LineItem convert(Domain.LineItem item) {
    return Shoppingcart.LineItem.newBuilder()
        .setProductId(item.getProductId())
        .setName(item.getName())
        .setQuantity(item.getQuantity())
        .build();
  }

  private Domain.LineItem convert(Shoppingcart.LineItem item) {
    return Domain.LineItem.newBuilder()
        .setProductId(item.getProductId())
        .setName(item.getName())
        .setQuantity(item.getQuantity())
        .build();
  }

  private Domain.Cart convert(Map<String, Shoppingcart.LineItem> cart) {
    return Domain.Cart.newBuilder()
        .addAllItems(cart.values().stream().map(this::convert).collect(Collectors.toList()))
        .build();
  }
}
