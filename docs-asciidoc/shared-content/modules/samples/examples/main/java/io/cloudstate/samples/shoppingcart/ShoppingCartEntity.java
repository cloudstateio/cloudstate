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

import com.example.shoppingcart.Shoppingcart;
import com.example.shoppingcart.persistence.Domain;
import com.google.protobuf.Empty;
import io.cloudstate.javasupport.EntityId;
import io.cloudstate.javasupport.eventsourced.*;

import java.util.*;
import java.util.stream.Collectors;

/** An event sourced entity. */
@EventSourcedEntity
public class ShoppingCartEntity {
  private final String entityId;
  private final Map<String, Shoppingcart.LineItem> cart = new LinkedHashMap<>();

  public ShoppingCartEntity(@EntityId String entityId) {
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

  @EventHandler
  public void itemAdded(Domain.ItemAdded itemAdded) {
    Shoppingcart.LineItem item = cart.get(itemAdded.getItem().getProductId());
    if (item == null) {
      item = convert(itemAdded.getItem());
    } else {
      item =
          item.toBuilder()
              .setQuantity(item.getQuantity() + itemAdded.getItem().getQuantity())
              .build();
    }
    cart.put(item.getProductId(), item);
  }

  @EventHandler
  public void itemRemoved(Domain.ItemRemoved itemRemoved) {
    cart.remove(itemRemoved.getProductId());
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
    ctx.emit(
        Domain.ItemAdded.newBuilder()
            .setItem(
                Domain.LineItem.newBuilder()
                    .setProductId(item.getProductId())
                    .setName(item.getName())
                    .setQuantity(item.getQuantity())
                    .build())
            .build());
    return Empty.getDefaultInstance();
  }

  @CommandHandler
  public Empty removeItem(Shoppingcart.RemoveLineItem item, CommandContext ctx) {
    if (!cart.containsKey(item.getProductId())) {
      ctx.fail("Cannot remove item " + item.getProductId() + " because it is not in the cart.");
    }
    ctx.emit(Domain.ItemRemoved.newBuilder().setProductId(item.getProductId()).build());
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
}
