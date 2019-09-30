/**
 * Event Sourcing support.
 *
 * <p>Event sourced entities can be annotated with the {@link
 * io.cloudstate.javasupport.eventsourced.EventSourcedEntity @EventSourcedEntity} annotation, and
 * supply command handlers using the {@link
 * io.cloudstate.javasupport.eventsourced.CommandHandler @CommandHandler} annotation.
 *
 * <p>In addition, {@link io.cloudstate.javasupport.eventsourced.EventHandler @EventHandler}
 * annotated methods should be defined to handle events, and {@link
 * io.cloudstate.javasupport.eventsourced.Snapshot @Snapshot} and {@link
 * io.cloudstate.javasupport.eventsourced.SnapshotHandler @SnapshotHandler} annotated methods should
 * be defined to produce and handle snapshots respectively.
 */
package io.cloudstate.javasupport.eventsourced;
