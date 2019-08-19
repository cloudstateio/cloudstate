package io.cloudstate.javasupport.eventsourced;

import java.util.Optional;

/**
 * Low level interface for handling events and commands on an entity.
 *
 * Generally, this should not be needed, instead, a class annotated with the {@link EventHandler},
 * {@link CommandHandler} and similar annotations should be used.
 */
public interface EventSourcedEntityHandler {

    /**
     * Handle the given event.
     *
     * @param event The event to handle.
     * @param context The event context.
     */
    void handleEvent(Object event, EventContext context);

    /**
     * Handle the given command.
     *
     * @param command The command to handle.
     * @param context The command context.
     * @return The reply to the command.
     */
    Object handleCommand(Object command, CommandContext context);

    /**
     * Handle the given snapshot.
     *
     * @param snapshot The snapshot to handle.
     * @param context The snapshot context.
     */
    void handleSnapshot(Object snapshot, SnapshotContext context);

    /**
     * Snapshot the object.
     *
     * @return The current snapshot, if this object supports snapshoting, otherwise empty.
     */
    Optional<Object> snapshot(SnapshotContext context);
}
