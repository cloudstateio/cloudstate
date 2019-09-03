package io.cloudstate.javasupport.crdt;

/**
 * Context for CRDT creation.
 * <p>
 * This is available for injection into the constructor of a CRDT.
 */
public interface CrdtCreationContext extends CrdtContext, CrdtFactory {
}
