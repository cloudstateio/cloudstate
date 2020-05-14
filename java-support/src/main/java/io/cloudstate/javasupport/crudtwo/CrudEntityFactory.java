package io.cloudstate.javasupport.crudtwo;

import io.cloudstate.javasupport.eventsourced.CommandHandler;
import io.cloudstate.javasupport.eventsourced.EventHandler;

/**
 * Low level interface for handling events and commands on an entity.
 *
 * <p>Generally, this should not be needed, instead, a class annotated with the {@link
 * EventHandler}, {@link CommandHandler} and similar annotations should be used.
 */
public interface CrudEntityFactory {
  /**
   * Create an entity handler for the given context.
   *
   * @param context The context.
   * @return The handler for the given context.
   */
  CrudEntityHandler create(CrudContext context);
}
