package docs.user.gettingstarted;

// #shopping-cart-main
import com.example.Shoppingcart;
import io.cloudstate.javasupport.CloudState;

public class ShoppingCartMain {

  public static void main(String... args) {
    new CloudState()
        .registerEventSourcedEntity(
            ShoppingCartEntity.class,
            Shoppingcart.getDescriptor().findServiceByName("ShoppingCart"))
        .start();
  }
}
// #shopping-cart-main

class ShoppingCartEntity {}
