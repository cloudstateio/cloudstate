package io.cloudstate.javasupport.crudtwo;

import com.google.protobuf.Any;
import io.cloudstate.javasupport.ClientActionContext;
import io.cloudstate.javasupport.EffectContext;

/**
 * An crud command context.
 *
 * <p>Methods annotated with {@link CommandHandler} may take this is a parameter. It allows emitting
 * new events (which represents the new persistent state) in response to a command, along with
 * forwarding the result to other entities, and performing side effects on other entities.
 */
public interface CommandContext extends CrudContext, ClientActionContext, EffectContext {
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
   * Emit the given event which represents the new persistent state. The event will be persisted.
   *
   * @param event The event to emit.
   */
  void emit(Object event);

  /**
   * The persisted state of the entity on which the command is being executed
   *
   * @return The state of the entity
   */
  Any state();
}
