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

package io.cloudstate.javasupport.tck.model.eventlogeventing;

import akka.NotUsed;
import akka.stream.javadsl.Source;
import io.cloudstate.javasupport.CloudEvent;
import io.cloudstate.javasupport.action.Action;
import io.cloudstate.javasupport.action.ActionContext;
import io.cloudstate.javasupport.action.ActionReply;
import io.cloudstate.javasupport.action.CallHandler;
import io.cloudstate.tck.model.EventLogSubscriberModel;
import io.cloudstate.tck.model.Eventlogeventing;

@Action
public class EventLogSubscriber {

  @CallHandler
  public ActionReply<Eventlogeventing.Response> processEventOne(
      ActionContext context, CloudEvent cloudEvent, Eventlogeventing.EventOne eventOne) {
    return convert(context, cloudEvent, eventOne.getStep());
  }

  @CallHandler
  public Source<ActionReply<Eventlogeventing.Response>, NotUsed> processEventTwo(
      ActionContext context, CloudEvent cloudEvent, Eventlogeventing.EventTwo eventTwo) {
    return Source.from(eventTwo.getStepList()).map(step -> convert(context, cloudEvent, step));
  }

  @CallHandler
  public Eventlogeventing.Response effect(Eventlogeventing.EffectRequest request) {
    return Eventlogeventing.Response.newBuilder()
        .setId(request.getId())
        .setMessage(request.getMessage())
        .build();
  }

  @CallHandler
  public Eventlogeventing.Response processAnyEvent(JsonMessage jsonMessage, CloudEvent cloudEvent) {
    return Eventlogeventing.Response.newBuilder()
        .setId(cloudEvent.subject().orElse(""))
        .setMessage(jsonMessage.message)
        .build();
  }

  private ActionReply<Eventlogeventing.Response> convert(
      ActionContext context, CloudEvent cloudEvent, Eventlogeventing.ProcessStep step) {
    String id = cloudEvent.subject().orElse("");
    if (step.hasReply()) {
      return ActionReply.message(
          Eventlogeventing.Response.newBuilder()
              .setId(id)
              .setMessage(step.getReply().getMessage())
              .build());
    } else if (step.hasForward()) {
      return ActionReply.forward(
          context
              .serviceCallFactory()
              .lookup(EventLogSubscriberModel.name, "Effect", Eventlogeventing.EffectRequest.class)
              .createCall(
                  Eventlogeventing.EffectRequest.newBuilder()
                      .setId(id)
                      .setMessage(step.getForward().getMessage())
                      .build()));
    } else {
      throw new RuntimeException("No reply or forward");
    }
  }
}
