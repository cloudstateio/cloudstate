/**
 * CRUD support.
 *
 * <p>Event sourced entities can be annotated with the {@link
 * io.cloudstate.javasupport.crud.CrudEntity @CrudEntity} annotation, and supply command handlers
 * using the {@link io.cloudstate.javasupport.crud.CommandHandler @CommandHandler} annotation.
 *
 * <p>In addition, {@link io.cloudstate.javasupport.crud.EventHandler @EventHandler} annotated
 * methods should be defined to handle events, and {@link
 * io.cloudstate.javasupport.crud.Snapshot @Snapshot} and {@link
 * io.cloudstate.javasupport.crud.SnapshotHandler @SnapshotHandler} annotated methods should be
 * defined to produce and handle snapshots respectively.
 */
package io.cloudstate.javasupport.crud;
