package io.cloudstate.javasupport.crdt;

import io.cloudstate.javasupport.EntityContext;

import java.util.Optional;

public interface CrdtContext extends EntityContext {
    Optional<? extends Crdt> state();
}
