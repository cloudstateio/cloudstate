package io.cloudstate.javasupport.crudtwo;

import com.google.protobuf.Any;
import java.util.Optional;

/**
 * Low level interface for handling events and commands on an entity.
 *
 * <p>Generally, this should not be needed, instead, a class annotated with the {@link
 * EventHandler}, {@link CommandHandler} and similar annotations should be used.
 */
public interface CrudEntityHandler {

  Optional<Any> handleCommand(Any command, CommandContext context);

  /**
   * Handle the given state.
   *
   * @param state The state to handle.
   * @param context The snapshot context.
   */
  void handleState(Any state, SnapshotContext context);
}
