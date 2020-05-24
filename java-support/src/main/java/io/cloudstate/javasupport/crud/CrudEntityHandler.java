package io.cloudstate.javasupport.crud;

import com.google.protobuf.Any;

import java.util.Optional;

/**
 * Low level interface for handling events (which represents the persistent state) and commands on
 * an crud entity.
 *
 * <p>Generally, this should not be needed, instead, a class annotated with the {@link
 * SnapshotHandler}, {@link CommandHandler} and similar annotations should be used.
 */
public interface CrudEntityHandler {

  /**
   * Handle the given command.
   *
   * @param command The command to handle.
   * @param context The command context.
   * @return The reply to the command, if the command isn't being forwarded elsewhere.
   */
  Optional<Any> handleCommand(Any command, CommandContext context);

  /**
   * Handle the given state.
   *
   * @param state The state to handle.
   * @param context The state context.
   */
  void handleState(Any state, SnapshotContext context);
}
