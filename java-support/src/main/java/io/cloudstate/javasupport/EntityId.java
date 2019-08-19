package io.cloudstate.javasupport;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation used to indicate that the annotated parameter is used to pass the id of the entity to a method
 * or constructor. The type of the parameter must be {@link String}.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface EntityId {
}
