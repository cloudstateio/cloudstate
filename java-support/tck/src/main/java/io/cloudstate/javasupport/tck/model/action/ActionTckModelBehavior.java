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

package io.cloudstate.javasupport.tck.model.action;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import io.cloudstate.javasupport.Context;
import io.cloudstate.javasupport.ServiceCall;
import io.cloudstate.javasupport.action.*;
import io.cloudstate.javasupport.action.Effect;
import io.cloudstate.tck.model.Action.*;
import io.cloudstate.tck.model.ActionTwo;
import java.util.concurrent.CompletionStage;

@Action
public class ActionTckModelBehavior {

  private final ActorSystem system = ActorSystem.create("ActionTckModel");

  public ActionTckModelBehavior() {}

  @CallHandler
  public CompletionStage<ActionReply<Response>> processUnary(
      Request request, ActionContext context) {
    return Source.single(request).via(responses(context)).runWith(singleResponse(), system);
  }

  @CallHandler
  public CompletionStage<ActionReply<Response>> processStreamedIn(
      Source<Request, NotUsed> requests, ActionContext context) {
    return requests.via(responses(context)).runWith(singleResponse(), system);
  }

  @CallHandler
  public Source<ActionReply<Response>, NotUsed> processStreamedOut(
      Request request, ActionContext context) {
    return Source.single(request).via(responses(context));
  }

  @CallHandler
  public Source<ActionReply<Response>, NotUsed> processStreamed(
      Source<Request, NotUsed> requests, ActionContext context) {
    return requests.via(responses(context));
  }

  private Flow<Request, ActionReply<Response>, NotUsed> responses(ActionContext context) {
    return Flow.of(Request.class)
        .flatMapConcat(request -> Source.from(request.getGroupsList()))
        .map(group -> response(group, context));
  }

  private ActionReply<Response> response(ProcessGroup group, ActionContext context) {
    ActionReply<Response> reply = ActionReply.noReply();
    for (ProcessStep step : group.getStepsList()) {
      switch (step.getStepCase()) {
        case REPLY:
          reply =
              ActionReply.message(
                      Response.newBuilder().setMessage(step.getReply().getMessage()).build())
                  .withEffects(reply.effects());
          break;
        case FORWARD:
          reply =
              ActionReply.<Response>forward(serviceTwoRequest(context, step.getForward().getId()))
                  .withEffects(reply.effects());
          break;
        case EFFECT:
          SideEffect effect = step.getEffect();
          reply =
              reply.withEffects(
                  Effect.of(serviceTwoRequest(context, effect.getId()), effect.getSynchronous()));
          break;
        case FAIL:
          reply =
              ActionReply.<Response>failure(step.getFail().getMessage())
                  .withEffects(reply.effects());
      }
    }
    return reply;
  }

  private Sink<ActionReply<Response>, CompletionStage<ActionReply<Response>>> singleResponse() {
    return Sink.fold(
        ActionReply.noReply(),
        (reply, next) ->
            next.isEmpty() ? reply.withEffects(next.effects()) : next.withEffects(reply.effects()));
  }

  private ServiceCall serviceTwoRequest(Context context, String id) {
    return context
        .serviceCallFactory()
        .lookup(ActionTwo.name, "Call", OtherRequest.class)
        .createCall(OtherRequest.newBuilder().setId(id).build());
  }
}
