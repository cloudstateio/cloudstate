package io.cloudstate.javasupport.eventsourced;

public interface SnapshotContext extends EventSourcedContext {
    long sequenceNumber();
}
