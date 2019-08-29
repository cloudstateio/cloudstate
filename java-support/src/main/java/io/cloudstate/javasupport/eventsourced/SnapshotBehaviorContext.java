package io.cloudstate.javasupport.eventsourced;

/**
 * Snapshot context that allows changing behavior.
 * </p>
 * This may be passed to any {@link SnapshotHandler} annotated methods.
 */
public interface SnapshotBehaviorContext extends SnapshotContext, BehaviorContext {
}
