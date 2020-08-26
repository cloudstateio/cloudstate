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

import com.google.protobuf.Any;

import java.util.Optional;

/**
 * Low level interface for handling events (which represents the persistent state) and commands on
 * an CRUD entity.
 *
 * <p>Generally, this should not be needed, instead, a class annotated with the {@link
 * StateHandler}, {@link CommandHandler} and similar annotations should be used.
 */
public interface CrudEntityHandler {

  /**
   * Handle the given command.
   *
   * @param command The command to handle.
   * @param context The command context.
   * @return The reply to the command, if the command isn't being forwarded elsewhere.
   */
  Optional<Any> handleCommand(Any command, CommandContext<Any> context);

  /**
   * Handle the given state.
   *
   * @param state The state to handle.
   * @param context The state context.
   */
  void handleUpdate(Any state, StateContext context);

  /**
   * Handle the state deletion.
   *
   * @param context The state context.
   */
  void handleDelete(StateContext context);
}
