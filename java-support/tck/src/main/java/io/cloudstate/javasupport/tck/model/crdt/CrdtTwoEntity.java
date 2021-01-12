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

package io.cloudstate.javasupport.tck.model.crdt;

import io.cloudstate.javasupport.crdt.*;
import io.cloudstate.tck.model.Crdt.*;

@CrdtEntity
public class CrdtTwoEntity {
  // create a CRDT to be able to call delete
  public CrdtTwoEntity(GCounter counter) {}

  @CommandHandler
  public Response call(Request request, CommandContext context) {
    for (RequestAction action : request.getActionsList()) {
      if (action.hasDelete()) context.delete();
    }
    return Response.getDefaultInstance();
  }
}
