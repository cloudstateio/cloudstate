package io.cloudstate.javasupport.eventsourced;

/**
 * Context for an event.
 */
public interface EventContext extends EventSourcedContext {
    long sequenceNumber();
}
