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

import akka.NotUsed;
import akka.stream.javadsl.Source;
import com.google.protobuf.Any;

import java.util.concurrent.CompletionStage;

/** Low level interface for handling for action calls. */
public interface ActionHandler {

  /**
   * Handle a unary call.
   *
   * @param commandName The name of the command this call is for.
   * @param message The message envelope of the message.
   * @param context The action context.
   * @return A future of the message to return.
   */
  CompletionStage<ActionReply<Any>> handleUnary(
      String commandName, MessageEnvelope<Any> message, ActionContext context);

  /**
   * Handle a streamed out call call.
   *
   * @param commandName The name of the command this call is for.
   * @param message The message envelope of the message.
   * @param context The action context.
   * @return The stream of messages to return.
   */
  Source<ActionReply<Any>, NotUsed> handleStreamedOut(
      String commandName, MessageEnvelope<Any> message, ActionContext context);

  /**
   * Handle a streamed in call.
   *
   * @param commandName The name of the command this call is for.
   * @param stream The stream of messages to handle.
   * @param context The action context.
   * @return A future of the message to return.
   */
  CompletionStage<ActionReply<Any>> handleStreamedIn(
      String commandName, Source<MessageEnvelope<Any>, NotUsed> stream, ActionContext context);

  /**
   * Handle a full duplex streamed in call.
   *
   * @param commandName The name of the command this call is for.
   * @param stream The stream of messages to handle.
   * @param context The action context.
   * @return The stream of messages to return.
   */
  Source<ActionReply<Any>, NotUsed> handleStreamed(
      String commandName, Source<MessageEnvelope<Any>, NotUsed> stream, ActionContext context);
}
