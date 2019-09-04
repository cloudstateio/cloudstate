package docs.user.eventsourced.behavior;

import com.example.Domain;
import com.example.Shoppingcart;
import com.google.protobuf.Empty;
import io.cloudstate.javasupport.EntityId;
import io.cloudstate.javasupport.eventsourced.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;


// #content
@EventSourcedEntity
public class ShoppingCartEntity {
    private final Map<String, Shoppingcart.LineItem> cart = new LinkedHashMap<>();
    private boolean checkedout = false;

    public ShoppingCartEntity(EventSourcedEntityCreationContext ctx) {
        ctx.become(new Open(), this);
    }

    class Open {
        @CommandHandler
        public Empty addItem(Shoppingcart.AddLineItem item, CommandContext ctx) {
            if (item.getQuantity() <= 0) {
                ctx.fail("Cannot add negative quantity of to item" + item.getProductId());
            }
            ctx.emit(Domain.ItemAdded.newBuilder().setItem(
                    Domain.LineItem.newBuilder()
                            .setProductId(item.getProductId())
                            .setName(item.getName())
                            .setQuantity(item.getQuantity())
                            .build()
            ).build());
            return Empty.getDefaultInstance();
        }

        @CommandHandler
        public Empty checkout(CommandContext ctx) {
            ctx.emit(Domain.CheckedOut.getDefaultInstance());
            return Empty.getDefaultInstance();
        }

        @EventHandler
        public void itemAdded(Domain.ItemAdded itemAdded) {
            Shoppingcart.LineItem item = cart.get(itemAdded.getItem().getProductId());
            if (item == null) {
                item = convert(itemAdded.getItem());
            } else {
                item = item.toBuilder()
                        .setQuantity(item.getQuantity() + itemAdded.getItem().getQuantity())
                        .build();
            }
            cart.put(item.getProductId(), item);
        }

        @EventHandler(eventClass = Domain.CheckedOut.class)
        public void checkedOut(EventBehaviorContext ctx) {
            checkedout = true;
            ctx.become(new CheckedOut(), this);
        }
    }

    class CheckedOut {
        @CommandHandler
        public Empty addItem(CommandContext ctx) {
            throw ctx.fail("Can't add more items to an already checked out shopping cart");
        }

        @CommandHandler
        public Empty checkout(CommandContext ctx) {
            throw ctx.fail("Shopping cart is already checked out");
        }
    }

    @CommandHandler
    public Shoppingcart.Cart getCart() {
        return Shoppingcart.Cart.newBuilder()
                .addAllItems(cart.values())
                .build();
    }

    @Snapshot
    public Domain.Cart snapshot() {
        return Domain.Cart.newBuilder()
                .addAllItems(
                        cart.values().stream()
                                .map(this::convert)
                                .collect(Collectors.toList())
                ).build();
    }

    @SnapshotHandler
    public void handleSnapshot(Domain.Cart cart, SnapshotBehaviorContext ctx) {
        this.cart.clear();
        for (Domain.LineItem item : cart.getItemsList()) {
            this.cart.put(item.getProductId(), convert(item));
        }
        this.checkedout = cart.getCheckedout();
        if (this.checkedout) {
            ctx.become(new CheckedOut(), this);
        }
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
// #content
