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

package io.cloudstate.javasupport.action;

import io.cloudstate.javasupport.Metadata;
import io.cloudstate.javasupport.ServiceCall;
import io.cloudstate.javasupport.impl.action.ForwardReplyImpl;
import io.cloudstate.javasupport.impl.action.MessageReplyImpl;
import io.cloudstate.javasupport.impl.action.NoReply;

import java.util.Collection;

/**
 * An action reply.
 *
 * <p>Action replies allow returning forwards and attaching effects to messages.
 *
 * @param <T> The type of the message that must be returned by this action call.
 */
public interface ActionReply<T> {
  /**
   * The effects attached to this reply.
   *
   * @return The effects.
   */
  Collection<Effect> effects();

  /**
   * Attach the given effects to this reply.
   *
   * @param effects The effects to attach.
   * @return A new reply with the attached effects.
   */
  ActionReply<T> withEffects(Effect... effects);

  /**
   * Create a message reply.
   *
   * @param payload The payload of the reply.
   * @return A message reply.
   */
  static <T> MessageReply<T> message(T payload) {
    return message(payload, Metadata.EMPTY);
  }

  /**
   * Create a message reply.
   *
   * @param payload The payload of the reply.
   * @param metadata The metadata for the message.
   * @return A message reply.
   */
  static <T> MessageReply<T> message(T payload, Metadata metadata) {
    return new MessageReplyImpl<>(payload, metadata);
  }

  /**
   * Create a forward reply.
   *
   * @param serviceCall The service call representing the forward.
   * @return A forward reply.
   */
  static <T> ForwardReply<T> forward(ServiceCall serviceCall) {
    return new ForwardReplyImpl<>(serviceCall);
  }

  /**
   * Create a reply that contains neither a message nor a forward.
   *
   * <p>This may be useful for emitting effects without sending a message.
   *
   * @return The reply.
   */
  static <T> ActionReply<T> noReply() {
    return NoReply.apply();
  }
}
