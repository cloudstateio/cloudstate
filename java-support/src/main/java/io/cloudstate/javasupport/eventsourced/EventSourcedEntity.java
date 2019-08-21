package io.cloudstate.javasupport.eventsourced;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An event sourced entity.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventSourcedEntity {
    /**
     * The name of the persistence id.
     * <p/>
     * If not specifed, defaults to the entities unqualified classname. It's strongly recommended that you specify it
     * explicitly.
     */
    String persistenceId() default "";
}
