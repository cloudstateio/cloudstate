# Forwarding and effects

This page documents how to use Cloudstate CRDT effects and forwarding in Java. For high level information on what Cloudstate effects and forwarding is, please read the general @ref[Forwarding and effects](../../features/effects.md) documentation first.

## Looking up service call references

To forward a command or emit an effect, a reference to the service call that will be invoked needs to be looked up. This can be done using the @javadoc[ServiceCallFactory](io.cloudstate.javasupport.ServiceCallFactory) interface, which is accessible on any context object via the @javadoc[`serviceCallFactory()`](io.cloudstate.javasupport.Context#serviceCallFactory--) method.

For example, if a user function serves two entity types, a shopping cart, and a CRDT that tracks which items are currently in hot demand, it might want to invoke the `ItemAddedToCart` command on `example.shoppingcart.HotItems` as a side effect of the `AddItem` shopping cart command. This reference can be looked up like so:

@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/effects/ShoppingCartEntity.java) { #lookup }

This could be looked up in the constructor of the entity, for later use, so it doesn't have to be looked up each time it's needed.

## Forwarding command

The `CommandContext` for each entity type implements `ClientActionContext` to allow forwarding a command by invoking @javadoc[`ClientActionContext.forward()`](io.cloudstate.javasupport.ClientActionContext#forward-io.cloudstate.javasupport.ServiceCall-). For example, if the item being processed in the `addItem` command is a "hot" item, we can make the `HotItems` entity aware of that item by forwarding a command:

@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/effects/ShoppingCartEntity.java) { #forward }

## Emitting an effect

The `CommandContext` for each entity type implements `EffectContext` to allow emitting an effect by invoking @javadoc[`EffectContext.effect()`](io.cloudstate.javasupport.EffectContext#effect-io.cloudstate.javasupport.ServiceCall-boolean-). For example, upon successful completion of the `addItem` command by `ShoppingCartEntity`, if we also want to emit an effect on the `HotItems` entity, we would invoke the effectful service call as:

@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/effects/ShoppingCartEntity.java) { #effect }

Please note that, contrary to command forwarding, the result of the effect is ignored by the current command `addItem`. More details in the common section @ref[Forwarding and effects](../../features/effects.md#forwarding-and-effects)
