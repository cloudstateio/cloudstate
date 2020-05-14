package io.cloudstate.javasupport.crudtwo;

import io.cloudstate.javasupport.crud.CrudContext;

/** Context for an event. */
public interface CrudEventContext extends CrudContext {
  /**
   * The sequence number of the current event being processed.
   *
   * @return The sequence number.
   */
  long sequenceNumber();
}
