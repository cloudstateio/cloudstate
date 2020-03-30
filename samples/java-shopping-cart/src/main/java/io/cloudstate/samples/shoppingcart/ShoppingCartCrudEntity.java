package io.cloudstate.samples.shoppingcart;

import com.example.shoppingcart.Shoppingcart;
import com.example.shoppingcart.persistence.Domain;
import com.google.protobuf.Empty;
import io.cloudstate.javasupport.EntityId;
import io.cloudstate.javasupport.eventsourced.CommandContext;
import io.cloudstate.javasupport.eventsourced.CommandHandler;
import io.cloudstate.javasupport.eventsourced.EventSourcedEntity;
import io.cloudstate.javasupport.eventsourced.Snapshot;
import io.cloudstate.javasupport.eventsourced.SnapshotHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** An event sourced entity. */
@EventSourcedEntity(snapshotEvery = 1)
public class ShoppingCartCrudEntity {
  private final String entityId;
  private final Map<String, Shoppingcart.LineItem> cart = new LinkedHashMap<>();

  public ShoppingCartCrudEntity(@EntityId String entityId) {
    this.entityId = entityId;
  }

  @Snapshot
  public Domain.Cart snapshot() {
    return Domain.Cart.newBuilder()
        .addAllItems(cart.values().stream().map(this::convert).collect(Collectors.toList()))
        .build();
  }

  @SnapshotHandler
  public void handleSnapshot(Domain.Cart cart) {
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
      ctx.fail("Cannot add negative quantity of to lineItem" + item.getProductId());
    }

    Domain.LineItem lineItem =
        Domain.LineItem.newBuilder()
            .setProductId(item.getProductId())
            .setName(item.getName())
            .setQuantity(quantity(item))
            .build();

    List<Domain.LineItem> lineItems =
        cart.values().stream()
            .map(this::convert)
            .filter(someItem -> !someItem.getProductId().equals(item.getProductId()))
            .collect(Collectors.toList());

    ctx.emit(Domain.Cart.newBuilder().addAllItems(lineItems).addItems(lineItem).build());
    return Empty.getDefaultInstance();
  }

  @CommandHandler
  public Empty removeItem(Shoppingcart.RemoveLineItem item, CommandContext ctx) {
    if (!cart.containsKey(item.getProductId())) {
      ctx.fail("Cannot remove item " + item.getProductId() + " because it is not in the cart.");
    }

    List<Domain.LineItem> lineItems =
        cart.values().stream()
            .map(this::convert)
            .filter(someItem -> someItem.getProductId().equals(item.getProductId()))
            .collect(Collectors.toList());

    ctx.emit(Domain.Cart.newBuilder().addAllItems(lineItems).build());
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

  private int quantity(Shoppingcart.AddLineItem item) {
    Shoppingcart.LineItem lineItem = cart.get(item.getProductId());
    return lineItem == null ? item.getQuantity() : lineItem.getQuantity() + item.getQuantity();
  }
}
