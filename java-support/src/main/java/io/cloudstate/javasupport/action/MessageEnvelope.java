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
import io.cloudstate.javasupport.impl.action.MessageEnvelopeImpl;

/** A message envelope. */
public interface MessageEnvelope<T> {
  /**
   * The metadata associated with the message.
   *
   * @return The metadata.
   */
  Metadata metadata();

  /**
   * The payload of the message.
   *
   * @return The payload.
   */
  T payload();

  /**
   * Create a message.
   *
   * @param payload The payload of the message.
   * @return The message.
   */
  static <T> MessageEnvelope<T> of(T payload) {
    return new MessageEnvelopeImpl<>(payload, Metadata.EMPTY);
  }

  /**
   * Create a message.
   *
   * @param payload The payload of the message.
   * @param metadata The metadata associated with the message.
   * @return The message.
   */
  static <T> MessageEnvelope<T> of(T payload, Metadata metadata) {
    return new MessageEnvelopeImpl<>(payload, metadata);
  }
}
