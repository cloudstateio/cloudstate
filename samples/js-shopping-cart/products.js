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


const crdt = require("cloudstate").crdt;

const entity = new crdt.Crdt(
  "shoppingcart/products.proto",
  "com.example.shoppingcart.ShoppingCartProducts",
  {
    includeDirs: ["../../protocols/example"]
  }
);

entity.commandHandlers = {
  UpdateCartQuantity: updateCartQuantity,
  RemoveProductFromCart: removeProductFromCart,
  GetProduct: getProduct,
};

// The default value is an ORMap, whose default values are GCounters, so essentially, this is a map of user ids to
// the quantity of items that that user has in their cart.
// There are some problems with using this though, if a user removes an item from their cart, and then immediately
// adds it back, then it's possible when that's replicated that the old counter value will survive, this is due to how
// ORMap works.
entity.defaultValue = () => new crdt.ORMap();

entity.onStateSet = map => {
  map.defaultValue = () => new crdt.GCounter();
};

function updateCartQuantity(request, ctx) {
  console.log("Product entity received update cart quantity for product " + request.productId);
  ctx.state.get(request.userId).increment(request.quantity);
  return {};
}

function removeProductFromCart(request, ctx) {
  console.log("Product entity received remove product from cart for product " + request.productId);
  ctx.state.delete(request.userId);
  return {};
}

function getProduct(request, ctx) {
  let totalQuantity = 0;
  for (const cart of ctx.state.values()) {
    totalQuantity += cart.value;
  }
  return {
    totalQuantities: totalQuantity,
    totalCarts: ctx.state.size
  }
}


// Export the entity
module.exports = entity;
