package io.cloudstate.javasupport.crudtwo;

import io.cloudstate.javasupport.ClientActionContext;
import io.cloudstate.javasupport.EffectContext;

/**
 * An event sourced command context.
 *
 * <p>Methods annotated with {@link CommandHandler} may take this is a parameter. It allows emitting
 * new events in response to a command, along with forwarding the result to other entities, and
 * performing side effects on other entities.
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
   * The state of the entity on which the command is being executed
   *
   * @return The state of the entity
   */
  Object state();
}
