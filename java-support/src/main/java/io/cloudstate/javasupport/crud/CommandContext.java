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

package io.cloudstate.javasupport.crud;

import io.cloudstate.javasupport.ClientActionContext;
import io.cloudstate.javasupport.EffectContext;

/**
 * An CRUD command context.
 *
 * <p>Methods annotated with {@link CommandHandler} may take this is a parameter. It allows updating
 * or deleting the entity state in response to a command, along with forwarding the result to other
 * entities, and performing side effects on other entities.
 */
public interface CommandContext extends CrudContext, ClientActionContext, EffectContext {
  /**
   * The current sequence number of state in this entity.
   *
   * @return The current sequence number.
   */
  long sequenceNumber();

  /**
   * The name of the command being executed.
   *
   * @return The name of the command.
   */
  String commandName();

  /**
   * The id of the command being executed.
   *
   * @return The id of the command.
   */
  long commandId();

  /**
   * Update the entity with the new state. The state will be persisted.
   *
   * @param state The state to persist.
   */
  void update(Object state);

  /** Delete the entity. */
  void delete();
}
