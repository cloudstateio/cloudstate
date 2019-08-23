package io.cloudstate.javasupport.eventsourced;

/**
 * A snapshot context.
 */
public interface SnapshotContext extends EventSourcedContext {
    /**
     * The sequence number of the last event that this snapshot includes.
     *
     * @return The sequence number.
     */
    long sequenceNumber();
}
