package io.cloudstate.javasupport.crudtwo;

/** A snapshot context. */
public interface SnapshotContext extends CrudContext {
  /**
   * The sequence number of the last event that this snapshot includes.
   *
   * @return The sequence number.
   */
  long sequenceNumber();
}
