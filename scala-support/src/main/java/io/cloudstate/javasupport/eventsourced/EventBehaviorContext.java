package io.cloudstate.javasupport.eventsourced;

/**
 * Event handler context that allows changing behavior. This can be passed to all {@link
 * EventHandler} annotated methods.
 */
public interface EventBehaviorContext extends EventContext, BehaviorContext {}
