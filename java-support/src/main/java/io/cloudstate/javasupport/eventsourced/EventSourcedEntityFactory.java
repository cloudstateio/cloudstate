package io.cloudstate.javasupport.eventsourced;

/**
 * Low level interface for handling events and commands on an entity.
 * <p/>
 * Generally, this should not be needed, instead, a class annotated with the {@link EventHandler},
 * {@link CommandHandler} and similar annotations should be used.
 */
public interface EventSourcedEntityFactory {
    /**
     * Create an entity handler for the given context.
     *
     * @param context The context.
     * @return The handler for the given context.
     */
    EventSourcedEntityHandler create(EventSourcedContext context);
}
