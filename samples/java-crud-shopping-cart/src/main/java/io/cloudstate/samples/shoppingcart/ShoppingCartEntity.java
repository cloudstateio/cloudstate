/*
 * Copyright 2019 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.cloudstate.samples.shoppingcart;

import com.example.crud.shoppingcart.Shoppingcart;
import com.example.crud.shoppingcart.persistence.Domain;
import com.google.protobuf.Empty;
import io.cloudstate.javasupport.EntityId;
import io.cloudstate.javasupport.crud.CommandContext;
import io.cloudstate.javasupport.crud.CommandHandler;
import io.cloudstate.javasupport.crud.CrudEntity;
import io.cloudstate.javasupport.crud.DeleteStateHandler;
import io.cloudstate.javasupport.crud.UpdateStateHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** A CRUD entity. */
@CrudEntity
public class ShoppingCartEntity {
  private final String entityId;
  private final Map<String, Shoppingcart.LineItem> cart = new LinkedHashMap<>();

  public ShoppingCartEntity(@EntityId String entityId) {
    this.entityId = entityId;
  }

  @UpdateStateHandler
  public void handleUpdateState(Domain.Cart cart) {
    this.cart.clear();
    for (Domain.LineItem item : cart.getItemsList()) {
      this.cart.put(item.getProductId(), convert(item));
    }
  }

  @DeleteStateHandler
  public void handleDeleteState() {
    this.cart.clear();
  }

  @CommandHandler
  public Shoppingcart.Cart getCart() {
    return Shoppingcart.Cart.newBuilder().addAllItems(cart.values()).build();
  }

  @CommandHandler
  public Empty addItem(Shoppingcart.AddLineItem item, CommandContext<Domain.Cart> ctx) {
    if (item.getQuantity() <= 0) {
      ctx.fail("Cannot add negative quantity of to item " + item.getProductId());
    }

    Shoppingcart.LineItem lineItem = cart.get(item.getProductId());
    if (lineItem == null) {
      lineItem =
          Shoppingcart.LineItem.newBuilder()
              .setProductId(item.getProductId())
              .setName(item.getName())
              .setQuantity(item.getQuantity())
              .build();
    } else {
      lineItem =
          lineItem.toBuilder().setQuantity(lineItem.getQuantity() + item.getQuantity()).build();
    }

    ctx.updateEntity(Domain.Cart.newBuilder().addAllItems(addItem(item, lineItem)).build());
    return Empty.getDefaultInstance();
  }

  @CommandHandler
  public Empty removeItem(Shoppingcart.RemoveLineItem item, CommandContext<Domain.Cart> ctx) {
    if (!cart.containsKey(item.getProductId())) {
      ctx.fail("Cannot remove item " + item.getProductId() + " because it is not in the cart.");
    }

    List<Domain.LineItem> lineItems =
        cart.values().stream()
            .filter(lineItem -> !lineItem.getProductId().equals(item.getProductId()))
            .map(this::convert)
            .collect(Collectors.toList());

    ctx.updateEntity(Domain.Cart.newBuilder().addAllItems(lineItems).build());
    return Empty.getDefaultInstance();
  }

  @CommandHandler
  public Empty removeCart(
      Shoppingcart.RemoveShoppingCart cartItem, CommandContext<Domain.Cart> ctx) {
    if (!entityId.equals(cartItem.getUserId())) {
      ctx.fail("Cannot remove unknown cart " + cartItem.getUserId());
    }

    ctx.deleteEntity();
    return Empty.getDefaultInstance();
  }

  private List<Domain.LineItem> addItem(
      Shoppingcart.AddLineItem addItem, Shoppingcart.LineItem lineItem) {
    Stream<Shoppingcart.LineItem> stream =
        cart.values().stream().filter(li -> !li.getProductId().equals(addItem.getProductId()));
    return Stream.concat(stream, Stream.of(lineItem))
        .map(this::convert)
        .collect(Collectors.toList());
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
}
