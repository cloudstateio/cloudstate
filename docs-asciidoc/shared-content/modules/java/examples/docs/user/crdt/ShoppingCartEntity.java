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

package docs.user.crdt;

import com.example.Shoppingcart;
import com.google.protobuf.Empty;
import io.cloudstate.javasupport.CloudState;
import io.cloudstate.javasupport.crdt.*;

import java.util.Optional;

// #entity-class tag::entity-class[]
@CrdtEntity
public class ShoppingCartEntity {
  // #entity-class end::entity-class[]

  // #creation tag::creation[]
  private final LWWRegisterMap<String, Shoppingcart.LineItem> items;

  public ShoppingCartEntity(LWWRegisterMap<String, Shoppingcart.LineItem> items) {
    this.items = items;
  }
  // #creation end::creation[]

  // #get-cart tag::get-cart[]
  @CommandHandler
  public Shoppingcart.Cart getCart() {
    return Shoppingcart.Cart.newBuilder().addAllItems(items.values()).build();
  }
  // #get-cart end::get-cart[]

  // #add-item tag::add-item[]
  @CommandHandler
  public Empty addItem(Shoppingcart.AddLineItem item, CommandContext ctx) {
    if (item.getQuantity() <= 0) {
      ctx.fail("Cannot add a negative quantity of items.");
    }
    if (items.containsKey(item.getProductId())) {
      items.computeIfPresent(
          item.getProductId(),
          (id, old) -> old.toBuilder().setQuantity(old.getQuantity() + item.getQuantity()).build());
    } else {
      items.put(
          item.getProductId(),
          Shoppingcart.LineItem.newBuilder()
              .setProductId(item.getProductId())
              .setName(item.getName())
              .setQuantity(item.getQuantity())
              .build());
    }
    return Empty.getDefaultInstance();
  }
  // #add-item end::add-item[]

  // #watch-cart tag::watch-cart[]
  @CommandHandler
  public Shoppingcart.Cart watchCart(StreamedCommandContext<Shoppingcart.Cart> ctx) {

    ctx.onChange(subscription -> Optional.of(getCart()));

    return getCart();
  }
  // #watch-cart end::watch-cart[]

  // #register tag::register[]
  public static void main(String... args) {
    new CloudState()
        .registerCrdtEntity(
            ShoppingCartEntity.class,
            Shoppingcart.getDescriptor().findServiceByName("ShoppingCartService"))
        .start();
  }
  // #register end::register[]

}
