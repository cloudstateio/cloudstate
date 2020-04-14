package io.cloudstate.samples.shoppingcart;

import akka.util.ByteString;
import com.example.shoppingcart.Shoppingcart;
import com.example.shoppingcart.crud.persistence.Domain;
import com.google.protobuf.InvalidProtocolBufferException;
import io.cloudstate.javasupport.crud.CrudEntity;
import io.cloudstate.javasupport.crud.KeyValue;
import io.cloudstate.javasupport.crud.KeyValue.Key;
import io.cloudstate.javasupport.eventsourced.CommandContext;
import io.cloudstate.javasupport.eventsourced.CommandHandler;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static io.cloudstate.javasupport.crud.KeyValue.keyOf;

@CrudEntity
public class ShoppingCartCrudEntity extends KeyValue.KeyValueEntity {

  @CommandHandler
  public com.google.protobuf.Empty addItem(Shoppingcart.AddLineItem item, CommandContext ctx) {
    if (item.getQuantity() <= 0) {
      ctx.fail("Cannot add negative quantity of to item" + item.getProductId());
    }

    Key<String> userId = keyOf(item.getUserId(), ByteString::utf8String, ByteString::fromString);
    if (!state().get(userId).isPresent()) {
      Domain.Cart cart =
          Domain.Cart.newBuilder()
              .addItems(
                  Domain.LineItem.newBuilder()
                      .setProductId(item.getProductId())
                      .setName(item.getName())
                      .setQuantity(item.getQuantity())
                      .build())
              .build();

      state().set(userId, cart.toByteString().toStringUtf8());
    } else {
      Domain.Cart cart = deserialize(userId);
      Domain.LineItem lineItem =
          Domain.LineItem.newBuilder()
              .setProductId(item.getProductId())
              .setName(item.getName())
              .setQuantity(quantity(cart, item))
              .build();
      List<Domain.LineItem> items =
          cart.getItemsList().stream()
              .filter(i -> !i.getProductId().equals(item.getProductId()))
              .collect(Collectors.toList());
      Domain.Cart modifiedCart =
          Domain.Cart.newBuilder().addAllItems(items).addItems(lineItem).build();
      state().set(userId, modifiedCart.toByteString().toStringUtf8());
    }

    ctx.emit(state().toProtoModification());

    return com.google.protobuf.Empty.getDefaultInstance();
  }

  @CommandHandler
  public com.google.protobuf.Empty removeItem(
      Shoppingcart.RemoveLineItem item, CommandContext ctx) {
    Key<String> userId = keyOf(item.getUserId(), ByteString::utf8String, ByteString::fromString);
    if (!state().get(userId).isPresent()) {
      ctx.fail(
          "Cannot remove item " + item.getProductId() + " for unknown user " + item.getUserId());
    }

    Domain.Cart cart = deserialize(userId);
    if (!containsItem(cart, item)) {
      ctx.fail("Cannot remove item " + item.getProductId() + " because it is not in the cart.");
    }

    List<Domain.LineItem> items =
        cart.getItemsList().stream()
            .filter(lineItem -> !lineItem.getProductId().equals(item.getProductId()))
            .collect(Collectors.toList());
    Domain.Cart modifiedCart = Domain.Cart.newBuilder().addAllItems(items).build();

    state().set(userId, modifiedCart.toByteString().toStringUtf8());
    ctx.emit(state().toProtoModification());

    return com.google.protobuf.Empty.getDefaultInstance();
  }

  @CommandHandler
  public com.google.protobuf.Empty removeCart(
      Shoppingcart.RemoveShoppingCart cart, CommandContext ctx) {
    Key<String> userId = keyOf(cart.getUserId(), ByteString::utf8String, ByteString::fromString);
    if (!state().get(userId).isPresent()) {
      ctx.fail("Cannot remove cart " + cart.getUserId() + " because it is unknown");
    }

    state().remove(userId);
    ctx.emit(state().toProtoModification());

    return com.google.protobuf.Empty.getDefaultInstance();
  }

  @CommandHandler
  public Shoppingcart.Cart getCart(Shoppingcart.GetShoppingCart cartId, CommandContext ctx) {
    Key<String> userId = keyOf(cartId.getUserId(), ByteString::utf8String, ByteString::fromString);
    if (!state().get(userId).isPresent()) {
      return Shoppingcart.Cart.newBuilder().build();
    } else {
      Domain.Cart cart = deserialize(userId);
      Collection<Shoppingcart.LineItem> lineItems =
          cart.getItemsList().stream().map(this::convert).collect(Collectors.toList());
      return Shoppingcart.Cart.newBuilder().addAllItems(lineItems).build();
    }
  }

  // it should be externalize
  private Domain.Cart deserialize(Key<String> userId) {
    try {
      return Domain.Cart.parseFrom(
          com.google.protobuf.ByteString.copyFromUtf8(state().get(userId).get()));
    } catch (InvalidProtocolBufferException e) {
      return null;
    }
  }

  private int quantity(Domain.Cart cart, Shoppingcart.AddLineItem item) {
    return cart.getItemsList().stream()
        .filter(lineItem -> lineItem.getProductId().equals(item.getProductId()))
        .findFirst()
        .map(lineItem -> lineItem.getQuantity() + item.getQuantity())
        .orElse(item.getQuantity());
  }

  private boolean containsItem(Domain.Cart cart, Shoppingcart.RemoveLineItem item) {
    return cart.getItemsList().stream()
        .filter(lineItem -> lineItem.getProductId().equals(item.getProductId()))
        .findFirst()
        .isPresent();
  }

  private Shoppingcart.LineItem convert(Domain.LineItem item) {
    return Shoppingcart.LineItem.newBuilder()
        .setProductId(item.getProductId())
        .setName(item.getName())
        .setQuantity(item.getQuantity())
        .build();
  }
}
