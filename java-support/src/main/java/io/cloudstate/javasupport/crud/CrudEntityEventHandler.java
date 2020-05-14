package io.cloudstate.javasupport.crud;

import com.google.protobuf.Any;

import java.util.Optional;

/**
 * Low level interface for handling events and commands on an entity.
 *
 * <p>Generally, this should not be needed, instead, a class annotated with the {@link
 * EventHandler}, {@link CommandHandler} and similar annotations should be used.
 */
public interface CrudEntityEventHandler {

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
