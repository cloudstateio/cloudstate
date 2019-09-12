package docs.user.crdt;

import com.example.Shoppingcart;
import com.google.protobuf.Empty;
import io.cloudstate.javasupport.CloudState;
import io.cloudstate.javasupport.crdt.*;

import java.util.Optional;

// #entity-class
@CrdtEntity
public class ShoppingCartEntity {
  // #entity-class

  // #creation
  private final LWWRegisterMap<String, Shoppingcart.LineItem> items;

  public ShoppingCartEntity(LWWRegisterMap<String, Shoppingcart.LineItem> items) {
    this.items = items;
  }
  // #creation

  // #get-cart
  @CommandHandler
  public Shoppingcart.Cart getCart() {
    return Shoppingcart.Cart.newBuilder().addAllItems(items.values()).build();
  }
  // #get-cart

  // #add-item
  @CommandHandler
  public Empty addItem(Shoppingcart.AddLineItem item, CommandContext ctx) {
    if (item.getQuantity() <= 0) {
      ctx.fail("Cannot add a negative quantity of items.");
    }
    if (items.containsKey(item.getProductId())) {
      items.computeIfPresent(
          item.getProductId(),
          (id, old) -> old.toBuilder().setQuantity(old.getQuantity() + item.getQuantity()).build());
    } else {
      items.put(
          item.getProductId(),
          Shoppingcart.LineItem.newBuilder()
              .setProductId(item.getProductId())
              .setName(item.getName())
              .setQuantity(item.getQuantity())
              .build());
    }
    return Empty.getDefaultInstance();
  }
  // #add-item

  // #watch-cart
  @CommandHandler
  public Shoppingcart.Cart watchCart(StreamedCommandContext<Shoppingcart.Cart> ctx) {

    ctx.onChange(subscription -> Optional.of(getCart()));

    return getCart();
  }
  // #watch-cart

  // #register
  public static void main(String... args) {
    new CloudState()
        .registerCrdtEntity(
            ShoppingCartEntity.class,
            Shoppingcart.getDescriptor().findServiceByName("ShoppingCartService"))
        .start();
  }
  // #register

}
