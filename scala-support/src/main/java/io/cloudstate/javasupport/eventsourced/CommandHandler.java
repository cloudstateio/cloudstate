/*
 * Copyright 2019 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.cloudstate.javasupport.eventsourced;

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
