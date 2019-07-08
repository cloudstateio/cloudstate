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

const Entity = require("cloudstate-event-sourcing");

const entity = new Entity(
  ["shoppingcart/shoppingcart.proto", "shoppingcart/persistence/domain.proto"],
  "com.example.shoppingcart.ShoppingCart",
  {
    persistenceId: "shopping-cart",
    snapshotEvery: 5, // Usually you wouldn't snapshot this frequently, but this helps to demonstrate snapshotting
    includeDirs: ["../../protocols/example"]
  }
);

/*
 * Here we load the Protobuf types. When emitting events or setting state, we need to return
 * protobuf message objects, not just ordinary JavaScript objects, so that the framework can
 * know how to serialize these objects when they are persisted.
 *
 * Note this shows loading them dynamically, they could also be compiled and statically loaded.
 */
const pkg = "com.example.shoppingcart.persistence.";
const ItemAdded = entity.lookupType(pkg + "ItemAdded");
const ItemRemoved = entity.lookupType(pkg + "ItemRemoved");
const Cart = entity.lookupType(pkg + "Cart");


/*
 * Set a callback to create the initial state. This is what is created if there is no
 * snapshot to load.
 *
 * We can ignore the userId parameter if we want, it's the id of the entity, which is
 * automatically associated with all events and state for this entity.
 */
entity.setInitial(userId => Cart.create({items: []}));

/*
 * Set a callback to create the behavior given the current state. Since there is no state
 * machine like behavior transitions for our shopping cart, we just return one behavior, but
 * this could inspect the cart, and return a different set of handlers depending on the
 * current state of the cart - for example, if the cart supported being checked out, then
 * if the cart was checked out, it might return AddItem and RemoveItem command handlers that
 * always fail because the cart is checked out.
 *
 * This callback will be invoked after each time that an event is handled to get the current
 * behavior for the current state.
 */
entity.setBehavior(cart => {
  return {
    // Command handlers. The name of the command corresponds to the name of the rpc call in
    // the gRPC service that this entity offers.
    commandHandlers: {
      AddItem: addItem,
      RemoveItem: removeItem,
      GetCart: getCart
    },
    // Event handlers. The name of the event corresponds to the (unqualified) name of the
    // persisted protobuf message.
    eventHandlers: {
      ItemAdded: itemAdded,
      ItemRemoved: itemRemoved
    }
  };
});

/**
 * Handler for add item commands.
 */
function addItem(addItem, cart, ctx) {
  // Validation:
  // Make sure that it is not possible to add negative quantities
  if (addItem.quantity < 1) {
    ctx.fail("Cannot add negative quantity to item " + addItem.productId);
  } else {
  // Create the event.
    const itemAdded = ItemAdded.create({
      item: {
        productId: addItem.productId,
        name: addItem.name,
        quantity: addItem.quantity
      }
    });
    // Emit the event.
    ctx.emit(itemAdded);
    return {};
  }
}

/**
 * Handler for remove item commands.
 */
function removeItem(removeItem, cart, ctx) {
  // Validation:
  // Check that the item that we're removing actually exists.
  const existing = cart.items.find(item => {
    return item.productId === removeItem.productId;
  });

  // If not, fail the command.
  if (!existing) {
    ctx.fail("Item " + removeItem.productId + " not in cart");
  } else {
    // Otherwise, emit an item removed event.
    const itemRemoved = ItemRemoved.create({
      productId: removeItem.productId
    });
    ctx.emit(itemRemoved);
    return {};
  }
}

/**
 * Handler for get cart commands.
 */
function getCart(request, cart) {
  // Simply return the shopping cart as is.
  return cart;
}

/**
 * Handler for item added events.
 */
function itemAdded(added, cart) {
  // If there is an existing item with that product id, we need to increment its quantity.
  const existing = cart.items.find(item => {
    return item.productId === added.item.productId;
  });

  if (existing) {
    existing.quantity = existing.quantity + added.item.quantity;
  } else {
    // Otherwise, we just add the item to the existing list.
    cart.items.push(added.item);
  }

  // And return the new state.
  return cart;
}

/**
 * Handler for item removed events.
 */
function itemRemoved(removed, cart) {
  // Filter the removed item from the items by product id.
  cart.items = cart.items.filter(item => {
    return item.productId !== removed.productId;
  });

  // And return the new state.
  return cart;
}

// Export the entity
module.exports = entity;