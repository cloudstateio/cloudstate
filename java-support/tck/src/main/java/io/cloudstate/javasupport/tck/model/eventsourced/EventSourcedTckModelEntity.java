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

package io.cloudstate.javasupport.tck.model.eventsourced;

import io.cloudstate.javasupport.Context;
import io.cloudstate.javasupport.ServiceCall;
import io.cloudstate.javasupport.ServiceCallRef;
import io.cloudstate.javasupport.eventsourced.*;
import io.cloudstate.tck.model.Eventsourced.*;
import java.util.Optional;

@EventSourcedEntity(persistenceId = "event-sourced-tck-model", snapshotEvery = 5)
public class EventSourcedTckModelEntity {

  private final ServiceCallRef<Request> serviceTwoCall;

  private String state = "";

  public EventSourcedTckModelEntity(Context context) {
    serviceTwoCall =
        context
            .serviceCallFactory()
            .lookup("cloudstate.tck.model.EventSourcedTwo", "Call", Request.class);
  }

  @Snapshot
  public Persisted snapshot() {
    return Persisted.newBuilder().setValue(state).build();
  }

  @SnapshotHandler
  public void handleSnapshot(Persisted snapshot) {
    state = snapshot.getValue();
  }

  @EventHandler
  public void handleEvent(Persisted event) {
    state += event.getValue();
  }

  @CommandHandler
  public Optional<Response> process(Request request, CommandContext context) {
    boolean forwarding = false;
    for (RequestAction action : request.getActionsList()) {
      switch (action.getActionCase()) {
        case EMIT:
          context.emit(Persisted.newBuilder().setValue(action.getEmit().getValue()).build());
          break;
        case FORWARD:
          forwarding = true;
          context.forward(serviceTwoRequest(action.getForward().getId()));
          break;
        case EFFECT:
          Effect effect = action.getEffect();
          context.effect(serviceTwoRequest(effect.getId()), effect.getSynchronous());
          break;
        case FAIL:
          context.fail(action.getFail().getMessage());
          break;
      }
    }
    return forwarding
        ? Optional.empty()
        : Optional.of(Response.newBuilder().setMessage(state).build());
  }

  private ServiceCall serviceTwoRequest(String id) {
    return serviceTwoCall.createCall(Request.newBuilder().setId(id).build());
  }
}
