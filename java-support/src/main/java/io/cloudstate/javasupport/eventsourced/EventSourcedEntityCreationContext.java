package io.cloudstate.javasupport.eventsourced;

/**
 * Creation context for {@link EventSourcedEntity} annotated entities.
 * <p>
 * This may be accepted as an argument to the constructor of an event sourced entity.
 */
public interface EventSourcedEntityCreationContext extends EventSourcedContext, BehaviorContext {
}
