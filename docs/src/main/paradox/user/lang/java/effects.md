# Forwarding and effects

This page documents how to use CloudState CRDT effects and forwarding in Java. For high level information on what CloudState effects and forwarding is, please read the general @ref[Forwarding and effects](../../features/effects.md) documentation first.

## Looking up service call references

To forward a command or emit an effect, a reference to the service call that will be invoked needs to be looked up. This can be done using the @javadoc[ServiceCallFactory](io.cloudstate.javasupport.ServiceCallFactory) interface, which is accessible on any context object via the @javadoc[`serviceCallFactory()`](io.cloudstate.javasupport.Context#serviceCallFactory--) method.

For example, if a user function serves two entity types, a shopping cart, and a CRDT that tracks which items are currently in hot demand, it might want to invoke the `ItemAddedToCart` command on `example.shoppingcart.HotItems` as a side effect of the `AddItem` shopping cart command. This reference can be looked up like so:

@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/effects/ShoppingCartEntity.java) { #lookup }

This could be looked up in the constructor of the entity, for later use, so it doesn't have to be looked up each time it's needed.

## Forwarding command

To forward a command, @javadoc[`ClientActionContext.forward()`](io.cloudstate.javasupport.ClientActionContext#forward-io.cloudstate.javasupport.ServiceCall-) may be invoked. The `CommandContext` for each entity type implements `ClientActionContext` and so offers this function. If the hot items effect service call above were to be invoked as a forward, it would might look like this:

@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/effects/ShoppingCartEntity.java) { #forward }

## Emitting an effect

To emit an effect, @javadoc[`EffectContext.effect()`](io.cloudstate.javasupport.EffectContext#effect-io.cloudstate.javasupport.ServiceCall-boolean-) may be invoked. The `CommandContext` for each entity type implements `EffectContext` and so offers this function. If the hot items effect service call above were to be invoked as an effect, it would might look like this:
                   
@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/effects/ShoppingCartEntity.java) { #effect }