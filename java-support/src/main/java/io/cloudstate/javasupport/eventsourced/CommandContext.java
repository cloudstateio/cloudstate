package io.cloudstate.javasupport.eventsourced;

import io.cloudstate.javasupport.ClientActionContext;
import io.cloudstate.javasupport.EffectContext;

/**
 * An event sourced command context.
 *
 * <p>Methods annotated with {@link CommandHandler} may take this is a parameter. It allows emitting
 * new events in response to a command, along with forwarding the result to other entities, and
 * performing side effects on other entities.
 */
public interface CommandContext extends EventSourcedContext, ClientActionContext, EffectContext {
  /**
   * The current sequence number of events in this entity.
   *
   * @return The current sequence number.
   */
  long sequenceNumber();

  /**
   * The name of the command being executed.
   *
   * @return The name of the command.
   */
  String commandName();

  /**
   * The id of the command being executed.
   *
   * @return The id of the command.
   */
  long commandId();

  /**
   * Emit the given event. The event will be persisted, and the handler of the event defined in the
   * current behavior will immediately be executed to pick it up.
   *
   * @param event The event to emit.
   */
  void emit(Object event);
}
