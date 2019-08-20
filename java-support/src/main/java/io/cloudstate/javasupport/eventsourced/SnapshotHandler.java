package io.cloudstate.javasupport.eventsourced;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a snapshot handler.
 * <p/>
 * If, when recovering an entity, that entity has a snapshot, the snapshot will be passed to a corresponding snapshot
 * handler method whose argument matches its type. The entity must set its current state to that snapshot.
 * <p/>
 * An entity may declare more than one snapshot handler if it wants different handling for different types.
 * <p/>
 * The snapshot handler method may additionally accept a {@link SnapshotBehaviorContext} parameter, allowing it to
 * access context for the snapshot, and potentially change behavior based on the state from the snapshot, if required.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SnapshotHandler {
}
