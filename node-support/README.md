# CloudState stateful service support

This package provides support for writing CloudState stateful functions.

An example event sourced function:

```javascript
const EventSourced = require("cloudstate").EventSourced;

const entity = new EventSourced(
  ["shoppingcart/shoppingcart.proto", "shoppingcart/persistence/domain.proto"],
  "example.shoppingcart.ShoppingCart",
  {
    persistenceId: "shopping-cart"
  }
);

const pkg = "example.shoppingcart.persistence.";
const ItemAdded = entity.lookupType(pkg + "ItemAdded");
const ItemRemoved = entity.lookupType(pkg + "ItemRemoved");
const Cart = entity.lookupType(pkg + "Cart");

entity.setInitial(userId => Cart.create({items: []}));

entity.setBehavior(cart => {
  return {
    commandHandlers: {
      AddItem: addItem,
      RemoveItem: removeItem,
      GetCart: getCart
    },
    eventHandlers: {
      ItemAdded: itemAdded,
      ItemRemoved: itemRemoved
    }
  };
});

function addItem(addItem, cart, ctx) {
  if (addItem.quantity < 1) {
    ctx.fail("Cannot add negative quantity to item " + addItem.productId);
  } else {
    const itemAdded = ItemAdded.create({
      item: {
        productId: addItem.productId,
        name: addItem.name,
        quantity: addItem.quantity
      }
    });
    ctx.emit(itemAdded);
    return {};
  }
}

function removeItem(removeItem, cart, ctx) {
  const existing = cart.items.find(item => {
    return item.productId === removeItem.productId;
  });

  if (!existing) {
    ctx.fail("Item " + removeItem.productId + " not in cart");
  } else {
    const itemRemoved = ItemRemoved.create({
      productId: removeItem.productId
    });
    ctx.emit(itemRemoved);
    return {};
  }
}

function getCart(request, cart) {
  return cart;
}

function itemAdded(added, cart) {
  const existing = cart.items.find(item => {
    return item.productId === added.item.productId;
  });

  if (existing) {
    existing.quantity = existing.quantity + added.item.quantity;
  } else {
    cart.items.push(added.item);
  }

  return cart;
}

function itemRemoved(removed, cart) {
  cart.items = cart.items.filter(item => {
    return item.productId !== removed.productId;
  });

  return cart;
}

entity.start();
```


For more information see https://cloudstate.io/docs/lang/javascript/.
