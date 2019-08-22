package io.cloudstate.javasupport.eventsourced;

import io.cloudstate.javasupport.impl.CloudStateAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a snapshot method.
 * </p>
 * An event sourced behavior may have at most one of these. When provided, it will be periodically (every
 * <em>n</em> events emitted) be invoked to retrieve a snapshot of the current state, to be persisted, so that the
 * event log can be loaded without replaying the entire history.
 * </p>
 * The method must return the current state of the entity.
 * </p>
 * The method may accept a {@link SnapshotContext} parameter.
 */
@CloudStateAnnotation
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Snapshot {
}
