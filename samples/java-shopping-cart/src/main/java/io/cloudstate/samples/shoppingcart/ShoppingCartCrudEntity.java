package io.cloudstate.samples.shoppingcart;

import com.example.shoppingcart.Shoppingcart;
import com.example.shoppingcart.crud.persistence.Domain;
import com.google.protobuf.Empty;
import io.cloudstate.javasupport.EntityId;
import io.cloudstate.javasupport.crud.CrudEntity;
import io.cloudstate.javasupport.eventsourced.CommandContext;
import io.cloudstate.javasupport.eventsourced.CommandHandler;
import io.cloudstate.javasupport.eventsourced.EventHandler;
import io.cloudstate.javasupport.eventsourced.Snapshot;
import io.cloudstate.javasupport.eventsourced.SnapshotHandler;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;


/** A CRUD entity. */
@CrudEntity
public class ShoppingCartCrudEntity {
  private final String entityId;
  private final CrudState state = new CrudState();

  public ShoppingCartCrudEntity(@EntityId String entityId) {
    this.entityId = entityId;
  }

  @Snapshot
  public Domain.Cart snapshot() {
    return Domain.Cart.newBuilder()
            .addAllItems(state.cart.values().stream().collect(Collectors.toList()))
            .build();
  }

  @SnapshotHandler
  public void handleSnapshot(Domain.Cart cart) {
    state.resetTo(cart);
  }

  @EventHandler
  public void cartModification(Domain.CartModification modification) {
    state.applyModification(modification);
  }

  @CommandHandler
  public Shoppingcart.Cart getCart() {
    Collection<Shoppingcart.LineItem> lineItems =
            state.cart.values().stream().map(this::convert).collect(Collectors.toList());

    return Shoppingcart.Cart.newBuilder().addAllItems(lineItems).build();
  }

  @CommandHandler
  public Empty addItem(Shoppingcart.AddLineItem item, CommandContext ctx) {
    if (item.getQuantity() <= 0) {
      ctx.fail("Cannot add negative quantity of to lineItem" + item.getProductId());
    }

    Domain.CartModification modification = state.applyCommand(item).toModification();
    ctx.emit(modification);

    return Empty.getDefaultInstance();
  }

  @CommandHandler
  public Empty removeItem(Shoppingcart.RemoveLineItem item, CommandContext ctx) {
    if (!state.cart.containsKey(item.getProductId())) {
      ctx.fail("Cannot remove item " + item.getProductId() + " because it is not in the cart.");
    }

    Domain.CartModification modification = state.applyCommand(item).toModification();
    ctx.emit(modification);

    return Empty.getDefaultInstance();
  }

  private Shoppingcart.LineItem convert(Domain.LineItem item) {
    return Shoppingcart.LineItem.newBuilder()
            .setProductId(item.getProductId())
            .setName(item.getName())
            .setQuantity(item.getQuantity())
            .build();
  }

  // It would be good to externalize the methods (applyModification, resetTo and toModification) as interface in the language support
  // We would like to deal with the compression of the event because it could very big
  // We would like to deal with the number of events because i think only the last one is important for projection
  private static final class CrudState {

    private final Map<String, Domain.LineItem> cart;

    private CrudState() {
      this(new LinkedHashMap<>());
    }

    private CrudState(Map<String, Domain.LineItem> cart) {
      this.cart = new LinkedHashMap<>(cart);
    }

    private CrudState applyCommand(Shoppingcart.AddLineItem item) {
      Domain.LineItem lineItem =
              Domain.LineItem.newBuilder()
                      .setProductId(item.getProductId())
                      .setName(item.getName())
                      .setQuantity(quantity(item))
                      .build();

      LinkedHashMap<String, Domain.LineItem> map = new LinkedHashMap<>(cart);
      map.put(lineItem.getProductId(), lineItem);
      return new CrudState(map);
    }

    private CrudState applyCommand(Shoppingcart.RemoveLineItem item) {
      LinkedHashMap<String, Domain.LineItem> map = new LinkedHashMap<>(cart);
      map.remove(item.getProductId());
      return new CrudState(map);
    }

    private void applyModification(Domain.CartModification modification) {
      this.cart.clear();
      for (Domain.LineItem item : modification.getItemsList()) {
        this.cart.put(item.getProductId(), item);
      }
    }

    private void resetTo(Domain.Cart cart) {
      this.cart.clear();
      for (Domain.LineItem item : cart.getItemsList()) {
        this.cart.put(item.getProductId(), item);
      }
    }

    private Domain.CartModification toModification() {
      return Domain.CartModification.newBuilder()
              .addAllItems(cart.values().stream().collect(Collectors.toList()))
              .build();
    }

    private int quantity(Shoppingcart.AddLineItem item) {
      Domain.LineItem lineItem = cart.get(item.getProductId());
      return lineItem == null ? item.getQuantity() : lineItem.getQuantity() + item.getQuantity();
    }
  }
}

