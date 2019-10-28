package io.cloudstate.javasupport.eventsourced;

import com.google.protobuf.Any;

import java.util.Optional;

/**
 * Low level interface for handling events and commands on an entity.
 *
 * <p>Generally, this should not be needed, instead, a class annotated with the {@link
 * EventHandler}, {@link CommandHandler} and similar annotations should be used.
 */
public interface EventSourcedEntityHandler {

  /**
   * Handle the given event.
   *
   * @param event The event to handle.
   * @param context The event context.
   */
  void handleEvent(Any event, EventContext context);

  /**
   * Handle the given command.
   *
   * @param command The command to handle.
   * @param context The command context.
   * @return The reply to the command, if the command isn't being forwarded elsewhere.
   */
  Optional<Any> handleCommand(Any command, CommandContext context);

  /**
   * Handle the given snapshot.
   *
   * @param snapshot The snapshot to handle.
   * @param context The snapshot context.
   */
  void handleSnapshot(Any snapshot, SnapshotContext context);

  /**
   * Snapshot the object.
   *
   * @return The current snapshot, if this object supports snapshoting, otherwise empty.
   */
  Optional<Any> snapshot(SnapshotContext context);
}
