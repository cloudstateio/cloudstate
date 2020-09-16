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

// #entity-class tag::entity-class[]
const EventSourced = require("cloudstate").EventSourced;

const entity = new EventSourced(
    ["shoppingcart.proto", "domain.proto"],
    "example.shoppingcart.ShoppingCartService",
    {
        persistenceId: "shopping-cart",
        snapshotEvery: 100
    }
);
// #entity-class end::entity-class[]

// #lookup-type tag::lookup-type[]
const pkg = "example.shoppingcart.domain.";
const ItemAdded = entity.lookupType(pkg + "ItemAdded");
const ItemRemoved = entity.lookupType(pkg + "ItemRemoved");
const Cart = entity.lookupType(pkg + "Cart");
// #lookup-type end::lookup-type[]

// #initial tag::initial[]
entity.initial = entityId => Cart.create({items: []});
// #initial end::initial[]

// #get-cart tag::get-cart[]
function getCart(request, cart) {
    return cart;
}
// #get-cart end::get-cart[]

// #add-item tag::add-item[]
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
// #add-item end::add-item[]

function removeItem() {}

// #item-added tag::item-added[]
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
// #item-added end::item-added[]

function itemRemoved() {}

// #behavior tag::behavior[]
entity.behavior = cart => {
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
};
// #behavior end::behavior[]

const CheckedOut = entity.lookupType(pkg + "CheckedOut");

// #multiple-behaviors tag::multiple-behaviors[]
function checkout(checkout, cart, ctx) {
    ctx.emit(CheckedOut.create({}));
    return {};
}

function checkedOut(checkedOut, cart) {
    cart.checkedOut = true;
    return cart;
}

function alreadyCheckedOut(cmd, state, ctx) {
    ctx.fail("Cart is already checked out!");
}

entity.behavior = cart => {
    if (cart.checkedout) {
        return {
            commandHandlers: {
                AddItem: alreadyCheckedOut,
                RemoveItem: alreadyCheckedOut,
                Checkout: alreadyCheckedOut,
                GetCart: getCart
            },
            eventHandlers : {}
        };
    } else {
        return {
            commandHandlers: {
                AddItem: addItem,
                RemoveItem: removeItem,
                Checkout: checkout,
                GetCart: getCart
            },
            eventHandlers: {
                ItemAdded: itemAdded,
                ItemRemoved: itemRemoved,
                CheckedOut: checkedOut
            }
        };
    }
};
// #multiple-behaviors end::multiple-behaviors[]

describe("The Eventsourced class", () => {
    it("should allow starting the entity", () => {
        // #start tag::start[]
        entity.start();
        // #start end::start[]
        entity.shutdown();
    });
    it("should allow adding the entity to the CloudState server", () => {
        // #add-entity tag::add-entity[]
        const CloudState = require("cloudstate").CloudState;
        const server = new CloudState();
        server.addEntity(entity);
        // #add-entity end::add-entity[]
    })
});
