package io.cloudstate.javasupport.crud;

import io.cloudstate.javasupport.eventsourced.EventContext;
import io.cloudstate.javasupport.impl.CloudStateAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an event handler.
 *
 * <p>This method will be invoked whenever an event matching this event handlers event class is
 * either replayed on entity recovery, by a command handler.
 *
 * <p>The method may take the event object as a parameter.
 *
 * <p>Methods annotated with this may take an {@link EventContext}.
 */
@CloudStateAnnotation
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventHandler {
  /**
   * The event class. Generally, this will be determined by looking at the parameter of the event
   * handler method, however if the event doesn't need to be passed to the method (for example,
   * perhaps it contains no data), then this can be used to indicate which event this handler
   * handles.
   */
  Class<?> eventClass() default Object.class;
}
