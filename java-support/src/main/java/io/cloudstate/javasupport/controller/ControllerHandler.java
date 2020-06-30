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

package io.cloudstate.javasupport.controller;

import akka.NotUsed;
import akka.stream.javadsl.Source;
import com.google.protobuf.Any;

import java.util.concurrent.CompletionStage;

/** Low level interface for handling for controller calls. */
public interface ControllerHandler {

  /**
   * Handle a unary call.
   *
   * @param commandName The name of the command this call is for.
   * @param message The message envelope of the message.
   * @param context The controller context.
   * @return A future of the message to return.
   */
  CompletionStage<ControllerReply<Any>> handleUnary(
      String commandName, MessageEnvelope<Any> message, ControllerContext context);

  /**
   * Handle a streamed out call call.
   *
   * @param commandName The name of the command this call is for.
   * @param message The message envelope of the message.
   * @param context The controller context.
   * @return The stream of messages to return.
   */
  Source<ControllerReply<Any>, NotUsed> handleStreamedOut(
      String commandName, MessageEnvelope<Any> message, ControllerContext context);

  /**
   * Handle a streamed in call.
   *
   * @param commandName The name of the command this call is for.
   * @param stream The stream of messages to handle.
   * @param context The controller context.
   * @return A future of the message to return.
   */
  CompletionStage<ControllerReply<Any>> handleStreamedIn(
      String commandName, Source<MessageEnvelope<Any>, NotUsed> stream, ControllerContext context);

  /**
   * Handle a full duplex streamed in call.
   *
   * @param commandName The name of the command this call is for.
   * @param stream The stream of messages to handle.
   * @param context The controller context.
   * @return The stream of messages to return.
   */
  Source<ControllerReply<Any>, NotUsed> handleStreamed(
      String commandName, Source<MessageEnvelope<Any>, NotUsed> stream, ControllerContext context);
}
