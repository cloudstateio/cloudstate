package io.cloudstate.javasupport.test;

import io.cloudstate.javasupport.EntityId;
import io.cloudstate.javasupport.eventsourced.EventSourcedEntity;

@EventSourcedEntity
public class ShoppingCartEntity {
    private final String entityId;
    private ShoppingCart shoppingCart;

    public ShoppingCartEntity(@EntityId String entityId) {
        this.entityId = entityId;
    }


}
