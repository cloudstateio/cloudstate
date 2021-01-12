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

const EventSourced = require("cloudstate").EventSourced;

const tckModel = new EventSourced(
  ["proto/eventsourced.proto"],
  "cloudstate.tck.model.EventSourcedTckModel",
  {
    persistenceId: "event-sourced-tck-model",
    snapshotEvery: 5
  }
);

const Response = tckModel.lookupType("cloudstate.tck.model.Response")
const Persisted = tckModel.lookupType("cloudstate.tck.model.Persisted")

tckModel.initial = entityId => Persisted.create({ value: "" });

tckModel.behavior = state => {
  return {
    commandHandlers: {
      Process: process
    },
    eventHandlers: {
      Persisted: persisted
    }
  };
};

function process(request, state, context) {
  request.actions.forEach(action => {
    if (action.emit) {
      const event = Persisted.create({ value: action.emit.value });
      context.emit(event);
      // FIXME: events are not emitted immediately, so we also update the function local state directly for responses
      state = persisted(event, state);
    } else if (action.forward) {
      context.thenForward(two.service.methods.Call, { id: action.forward.id });
    } else if (action.effect) {
      context.effect(two.service.methods.Call, { id: action.effect.id }, action.effect.synchronous);
    } else if (action.fail) {
      context.fail(action.fail.message);
    }
  });
  return Response.create(state.value ? { message: state.value } : {});
}

function persisted(event, state) {
  state.value += event.value;
  return state;
}

const two = new EventSourced(
  ["proto/eventsourced.proto"],
  "cloudstate.tck.model.EventSourcedTwo"
);

two.initial = entityId => Persisted.create({ value: "" });

two.behavior = state => {
  return {
    commandHandlers: {
      Call: call
    }
  };
};

function call(request) {
  return Response.create({});
}

module.exports.tckModel = tckModel;
module.exports.two = two;
