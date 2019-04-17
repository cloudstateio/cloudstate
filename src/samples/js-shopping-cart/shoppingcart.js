let protobuf = require("protobufjs");
let Entity = require("stateful-serverless-event-sourcing");

let domain = protobuf.loadSync(["domain.proto", "shoppingcart.proto"]);

/*
 * Here we load the Protobuf types. When emitting events or setting state, we need to return
 * protobuf message objects, not just ordinary JavaScript objects, so that the framework can
 * know how to serialize these objects when they are persisted.
 *
 * Note this shows loading them dynamically, they could also be compiled and statically loaded.
 */
let pkg = "com.example.shoppingcart.persistence.";
let ItemAdded = domain.lookupType(pkg + "ItemAdded");
let ItemRemoved = domain.lookupType(pkg + "ItemRemoved");
let Cart = domain.lookupType(pkg + "Cart");

let entity = new Entity(domain, "com.example.shoppingcart.ShoppingCart");

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
  // Create the event.
  let itemAdded = ItemAdded.create({
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

/**
 * Handler for remove item commands.
 */
function removeItem(removeItem, cart, ctx) {
  // Validation:
  // Check that the item that we're removing actually exists.
  let existing = cart.items.find(item => {
    return item.productId === removeItem.productId;
  });

  // If not, fail the command.
  if (!existing) {
    ctx.fail("Item " + removeItem.productId + " not in cart");
  } else {
    // Otherwise, emit an item removed event.
    let itemRemoved = ItemRemoved.create({
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
  let existing = cart.items.find(item => {
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
    return item.productId === removed.productId;
  });

  // And return the new state.
  return cart;
}

// Export the entity
module.exports = entity;