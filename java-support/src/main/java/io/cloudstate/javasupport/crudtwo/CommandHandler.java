package io.cloudstate.javasupport.crudtwo;

import io.cloudstate.javasupport.impl.CloudStateAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a command handler.
 *
 * <p>This method will be invoked whenever the service call with name that matches this command
 * handlers name is invoked.
 *
 * <p>The method may take the command object as a parameter, its type must match the gRPC service
 * input type.
 *
 * <p>The return type of the method must match the gRPC services output type.
 *
 * <p>The method may also take a {@link CommandContext}, and/or a {@link
 * io.cloudstate.javasupport.EntityId} annotated {@link String} parameter.
 */
@CloudStateAnnotation
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CommandHandler {

  /**
   * The name of the command to handle.
   *
   * <p>If not specified, the name of the method will be used as the command name, with the first
   * letter capitalized to match the gRPC convention of capitalizing rpc method names.
   *
   * @return The command name.
   */
  String name() default "";
}
