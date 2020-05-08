import 'package:cloudstate/cloudstate.dart';

import 'generated/google/protobuf/empty.pb.dart';
// ignore: library_prefixes
import 'generated/persistence/domain.pb.dart' as Domain;
// ignore: library_prefixes
import 'generated/shoppingcart.pb.dart' as Shoppingcart;

// #constructing
ShoppingCartEntity.create(@EntityId() String entityId, Context context) {
  this.entityId = entityId;
  this.context = context;
}
// #constructing

// #entity-class
@EventSourcedEntity()
class ShoppingCartEntity {
// #entity-class

    // #entity-state
    final Map<String, Shoppingcart.LineItem> _cart = {};
    // #entity-state

    // #snapshot
    @Snapshot()
    Domain.Cart snapshot() {
        return Domain.Cart.create()
            ..items.addAll(_cart.values.map((e) => convertShoppingItem(e)).toList());
    }

    Domain.LineItem convertShoppingItem(Shoppingcart.LineItem item) {
        return Domain.LineItem.create()
            ..productId = item.productId
            ..name = item.name
            ..quantity = item.quantity;
    }
    // #snapshot

    // #handle-snapshot
    @SnapshotHandler()
    void handleSnapshot(Domain.Cart cart) {
        _cart.clear();
        for (var item in cart.items) {
            _cart[item.productId] = convert(item);
        }
    }
    // #handle-snapshot

    // #item-added
    @EventHandler()
    void itemAdded(Domain.ItemAdded itemAdded) {
        var item = _cart[itemAdded.item.productId];
        if (item == null) {
            item = convert(itemAdded.item);
        } else {
            item = item..quantity = item.quantity + itemAdded.item.quantity;
        }
        _cart[item.productId] = item;
    }

    Shoppingcart.LineItem convert(Domain.LineItem item) {
        return Shoppingcart.LineItem.create()
            ..productId = item.productId
            ..name = item.name
            ..quantity = item.quantity;
    }
    // #item-added

    // #item-removed
    @EventHandler()
    void itemRemoved(Domain.ItemRemoved itemRemoved) {
        _cart.remove(itemRemoved.productId);
    }
    // #item-removed

    // #get-cart
    @EventSourcedCommandHandler()
    Shoppingcart.Cart getCart() {
        return Shoppingcart.Cart.create()..items.addAll(_cart.values);
    }
    // #get-cart

    // #add-item
    @EventSourcedCommandHandler()
    Empty addItem(Shoppingcart.AddLineItem item, CommandContext ctx) {
        if (item.quantity <= 0) {
            ctx.fail('Cannot add negative quantity of to item ${item.productId}');
        }

        var lineIem = Domain.LineItem.create()
            ..productId = item.productId
            ..name = item.name
            ..quantity = item.quantity;

        ctx.emit(Domain.ItemAdded.create()..item = lineIem);
        return Empty.getDefault();
    }
    // #add-item

    // #command-remove-item
    @EventSourcedCommandHandler()
    Empty removeItem(Shoppingcart.RemoveLineItem item, CommandContext ctx) {
        if (!_cart.containsKey(item.productId)) {
            ctx.fail(
                'Cannot remove item ${item.productId} because it is not in the cart.');
        }
        ctx.emit(Domain.ItemRemoved.create()..productId = item.productId);
        return Empty.getDefault();
    }
    // #command-remove-item

    // #register
    import 'package:cloudstate/cloudstate.dart';
    import 'package:shopping_cart/src/eventsourced_entity.dart';

    void main() {
        Cloudstate()
            ..port = 8089
            ..address = 'localhost'
            ..registerEventSourcedEntity(
                'com.example.shoppingcart.ShoppingCart', ShoppingCartEntity)
            ..start();
    }
    // #register

}
