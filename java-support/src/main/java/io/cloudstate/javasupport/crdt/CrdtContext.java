package io.cloudstate.javasupport.crdt;

import io.cloudstate.javasupport.EntityContext;

import java.util.Optional;

/**
 * Root context for all CRDT contexts.
 */
public interface CrdtContext extends EntityContext {
    /**
     * The current CRDT, if it's been created.
     *
     * @param crdtClass The type of the CRDT that is expected.
     * @return The current CRDT, or empty if none has been created yet.
     * @throws IllegalStateException If the current CRDT does not match the passed in <code>crdtClass</code> type.
     */
    <T extends Crdt> Optional<T> state(Class<T> crdtClass) throws IllegalStateException;
}
