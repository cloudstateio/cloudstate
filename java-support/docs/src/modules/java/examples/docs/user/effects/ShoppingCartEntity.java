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

package docs.user.effects;

import com.example.Hotitems;
import com.example.Shoppingcart;
import com.google.protobuf.Empty;
import io.cloudstate.javasupport.Context;
import io.cloudstate.javasupport.ServiceCallRef;
import io.cloudstate.javasupport.eventsourced.CommandContext;
import io.cloudstate.javasupport.eventsourced.CommandHandler;

public class ShoppingCartEntity {

  // tag::lookup[]
  private final ServiceCallRef<Hotitems.Item> itemAddedToCartRef;

  public ShoppingCartEntity(Context ctx) {
    itemAddedToCartRef =
        ctx.serviceCallFactory()
            .lookup(
                "example.shoppingcart.ShoppingCartService", "ItemAddedToCart", Hotitems.Item.class);
  }
  // end::lookup[]

  class CommandHandlerWithForward {
    // tag::forward[]
    @CommandHandler
    public void addItem(Shoppingcart.AddLineItem item, CommandContext ctx) {
      // ... Validate and emit event

      ctx.forward(
          itemAddedToCartRef.createCall(
              Hotitems.Item.newBuilder()
                  .setProductId(item.getProductId())
                  .setName(item.getName())
                  .setQuantity(item.getQuantity())
                  .build()));
    }
    // end::forward[]
  }

  class CommandHandlerWithEffect {
    // tag::effect[]
    @CommandHandler
    public Empty addItem(Shoppingcart.AddLineItem item, CommandContext ctx) {
      // ... Validate and emit event

      ctx.effect(
          itemAddedToCartRef.createCall(
              Hotitems.Item.newBuilder()
                  .setProductId(item.getProductId())
                  .setName(item.getName())
                  .setQuantity(item.getQuantity())
                  .build()));

      return Empty.getDefaultInstance();
    }
    // end::effect[]
  }
}
