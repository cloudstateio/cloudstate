package io.cloudstate.javasupport.crdt;

/**
 * Low level interface for handling commands for CRDTs.
 * <p>
 * Generally, this should not be used, rather, a {@link CrdtEntity} annotated class should be used.
 */
public interface CrdtEntityFactory {
    /**
     * Create a CRDT entity handler for the given context.
     * <p>
     * This will be invoked each time a new CRDT entity stream from the proxy is established, for handling commands\
     * for a single CRDT.
     *
     * @param context The creation context.
     * @return The handler to handle commands.
     */
    CrdtEntityHandler create(CrdtCreationContext context);
}
