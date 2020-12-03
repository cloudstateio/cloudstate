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

import com.google.protobuf.Empty;
import io.cloudstate.javasupport.eventsourced.CommandHandler;
import io.cloudstate.javasupport.eventsourced.CommandContext;
import io.cloudstate.javasupport.eventsourced.EventHandler;
import io.cloudstate.javasupport.eventsourced.EventSourcedEntity;
import io.cloudstate.tck.model.Eventlogeventing;

@EventSourcedEntity(persistenceId = "eventlogeventing-one")
public class EventSourcedEntityOne {
  @CommandHandler
  public Empty emitEvent(Eventlogeventing.EmitEventRequest event, CommandContext ctx) {
    if (event.hasEventOne()) {
      ctx.emit(event.getEventOne());
    } else {
      ctx.emit(event.getEventTwo());
    }
    return Empty.getDefaultInstance();
  }

  @EventHandler
  public void handle(Eventlogeventing.EventOne event) {}

  @EventHandler
  public void handle(Eventlogeventing.EventTwo event) {}
}
