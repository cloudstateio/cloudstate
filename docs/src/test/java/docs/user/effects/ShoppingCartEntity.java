package docs.user.effects;

import com.example.Hotitems;
import com.example.Shoppingcart;
import com.google.protobuf.Empty;
import io.cloudstate.javasupport.Context;
import io.cloudstate.javasupport.ServiceCallRef;
import io.cloudstate.javasupport.eventsourced.CommandContext;
import io.cloudstate.javasupport.eventsourced.CommandHandler;

public class ShoppingCartEntity {

  // #lookup
  private final ServiceCallRef<Hotitems.Item> itemAddedToCartRef;

  public ShoppingCartEntity(Context ctx) {
    itemAddedToCartRef =
        ctx.serviceCallFactory()
            .lookup(
                "example.shoppingcart.ShoppingCartService", "ItemAddedToCart", Hotitems.Item.class);
  }
  // #lookup

  class CommandHandlerWithForward {
    // #forward
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
    // #forward
  }

  class CommandHandlerWithEffect {
    // #effect
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
    // #effect
  }
}
