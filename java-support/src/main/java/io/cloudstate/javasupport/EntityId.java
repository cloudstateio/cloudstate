package io.cloudstate.javasupport;

import io.cloudstate.javasupport.impl.CloudStateAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation used to indicate that the annotated parameter accepts an entity id.
 *
 * <p>This parameter may appear on handler methods and constructors for any class that provides
 * behavior for stateful service entity.
 *
 * <p>The type of the parameter must be {@link String}.
 */
@CloudStateAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.FIELD})
public @interface EntityId {}
