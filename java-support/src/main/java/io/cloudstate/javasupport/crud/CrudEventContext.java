package io.cloudstate.javasupport.crud;

/** Context for an event. */
public interface CrudEventContext extends CrudContext {
  /**
   * The sequence number of the current event being processed.
   *
   * @return The sequence number.
   */
  long sequenceNumber();
}
