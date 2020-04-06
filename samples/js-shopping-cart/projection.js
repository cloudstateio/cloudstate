/*
 * Copyright 2020 Lightbend Inc.
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

const Stateless = require("cloudstate").Stateless;

const projection = new Stateless(
    ["shoppingcart/projection.proto", "shoppingcart/products.proto"],
    "com.example.shoppingcart.ShoppingCartProjection",
    {
      includeDirs: ["../../protocols/example"]
    }
);

const products = projection.root.lookupService("com.example.shoppingcart.ShoppingCartProducts").methods;

projection.commandHandlers = {
  HandleItemAdded: handleItemAdded,
  HandleItemRemoved: handleItemRemoved,
};

function handleItemAdded(itemAdded, ctx) {
  console.log("Projection received itemAdded event for user " +
      ctx.cloudevent.subject + " and product " + itemAdded.item.productId);
  ctx.forward(products.UpdateCartQuantity, {
    productId: itemAdded.item.productId,
    userId: ctx.cloudevent.subject,
    quantity: itemAdded.item.quantity,
  });
}

function handleItemRemoved(itemRemoved, ctx) {
  console.log("Projection received itemRemoved event for user " +
      ctx.cloudevent.subject + " and product " + itemRemoved.productId);
  ctx.forward(products.RemoveProductFromCart, {
    productId: itemRemoved.productId,
    userId: ctx.cloudevent.subject,
  });
}

// Export the entity
module.exports = projection;
