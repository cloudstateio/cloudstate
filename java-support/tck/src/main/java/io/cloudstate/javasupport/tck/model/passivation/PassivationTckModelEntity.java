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

package io.cloudstate.javasupport.tck.model.passivation;

import io.cloudstate.javasupport.entity.CommandContext;
import io.cloudstate.javasupport.entity.CommandHandler;
import io.cloudstate.javasupport.entity.Entity;
import io.cloudstate.tck.model.entitypassivation.Entitypassivation.*;

import java.util.Optional;

@Entity(persistenceId = "entity-passivation-tck-model")
public class PassivationTckModelEntity {

  private final String state = "state";

  @CommandHandler
  public Optional<Response> activate(Request request, CommandContext<Persisted> context) {
    return Optional.of(Response.newBuilder().setMessage(state).build());
  }
}
