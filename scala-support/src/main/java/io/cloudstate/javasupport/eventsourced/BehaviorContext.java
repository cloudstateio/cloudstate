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

/** Superinterface of all contexts that allow changing the current entities behavior behavior. */
public interface BehaviorContext extends EventSourcedContext {

  /**
   * Become the new behavior specified by the given behavior objects.
   *
   * <p>More than one object may be passed to allow composing behaviors from multiple objects. If
   * two objects define a handler for the same event or command, the one that comes earlier in the
   * supplied array of objects is the one that is used.
   *
   * <p>Note that event and snapshot handlers, where handlers are matched on a given behavior object
   * by specificity (ie, a handler for a child class will take precedence over a handler for a
   * parent class), this precedence is not honored across multiple behaviors. So, if the first
   * behavior defines an event handler for {@link Object}, that handler will always win, regardless
   * of what handlers are defined on subsequent behaviors.
   *
   * @param behaviors The behaviors to use for subsequent commands and events.
   */
  void become(Object... behaviors);
}
