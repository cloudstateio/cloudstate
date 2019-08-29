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


const crdt = require("cloudstate").crdt;

const entity = new crdt.Crdt(
  "crdts/crdt-example.proto",
  "com.example.crdts.CrdtExample",
  {
    includeDirs: ["../../protocols/example"]
  }
);

entity.commandHandlers = {
  IncrementGCounter: incrementGCounter,
  GetGCounter: getGCounter,
  UpdatePNCounter: updatePNCounter,
  GetPNCounter: getPNCounter,
  MutateGSet: mutateGSet,
  GetGSet: getGSet,
  MutateORSet: mutateORSet,
  GetORSet: getORSet,
  Connect: connect,
  Monitor: monitor
};

function incrementGCounter(update, ctx) {
  if (update.value < 0) {
    ctx.fail("Cannot decrement gcounter");
  }

  if (ctx.state === null) {
    ctx.state = new crdt.GCounter();
  }

  if (update.value > 0) {
    ctx.state.increment(update.value);
  }
  return {
    value: ctx.state.value
  };
}

function getGCounter(get, ctx) {
  if (ctx.state === null) {
    ctx.state = new crdt.GCounter();
  }

  return {
    value: ctx.state.value
  };
}

function updatePNCounter(update, ctx) {
  if (ctx.state === null) {
    ctx.state = new crdt.PNCounter();
  }

  if (update.value !== 0) {
    ctx.state.increment(update.value);
  }
  return {
    value: ctx.state.value
  };
}

function getPNCounter(get, ctx) {
  if (ctx.state === null) {
    ctx.state = new crdt.PNCounter();
  }

  return {
    value: ctx.state.value
  };
}

function mutateGSet(update, ctx) {
  if (ctx.state === null) {
    ctx.state = new crdt.GSet();
  }

  update.add.forEach(value => {
    ctx.state.add(value)
  });

  return {
    size: ctx.state.size
  }
}

function getGSet(get, ctx) {
  if (ctx.state === null) {
    ctx.state = new crdt.GSet();
  }

  return {
    items: Array.from(ctx.state)
  };
}

function mutateORSet(update, ctx) {
  if (ctx.state === null) {
    ctx.state = new crdt.ORSet();
  }

  if (update.clear) {
    ctx.state.clear();
  }
  update.remove.forEach(value => {
    ctx.state.delete(value);
  });
  update.add.forEach(value => {
    ctx.state.add(value);
  });

  return {
    size: ctx.state.size
  }
}

function getORSet(get, ctx) {
  if (ctx.state === null) {
    ctx.state = new crdt.ORSet();
  }

  return {
    items: Array.from(ctx.state)
  };
}

/**
 * User presence feature.
 *
 * This is a streamed call. As long as a user (id given by the entity id) is connected
 * to it, they are considered to be online.
 *
 * Here we use a Vote CRDT, which if at least one node votes is true, will be true.
 * So when the user connects, we invoke the connect() method (which we have defined
 * by enriching the CRDT in onStateSet), which will manage our vote accordingly.
 *
 * When they disconnect, the onStreamCancel callback is invoked, and we disconnect,
 * removing our vote if this is the last connection to this CRDT.
 */
function connect(user, ctx) {
  if (ctx.state === null) {
    ctx.state = new crdt.Vote();
  }
  if (ctx.streamed) {
    ctx.onStreamCancel = state => {
      state.disconnect();
    };
    ctx.state.connect();
  }
}

/**
 * User presence monitoring call.
 *
 * This is a streamed call. We add a onStateChange callback, so that whenever the CRDT
 * changes, if the online status has changed, we return it.
 */
function monitor(user, ctx) {
  if (ctx.state === null) {
    ctx.state = new crdt.Vote();
  }
  let online = ctx.state.atLeastOne;
  if (ctx.streamed) {
    ctx.onStateChange = state => {
      if (online !== state.atLeastOne) {
        online = state.atLeastOne;
        return {online};
      }
    };
  }
  return {online};
}

/**
 * This is invoked whenever a new state is created, either by setting
 * ctx.state = myCrdt, or when the server pushes a new state. This is provided to allow
 * us to configure the CRDT, or enrich it with additional non replicated state, in this
 * case, for the vote CRDT, we add the number of users connected to this node to it,
 * so that only remove our vote when that number goes down to zero.
 */
entity.onStateSet = state => {
  if (state instanceof crdt.Vote) {
    state.users = 0;
    // Enrich the state with callbacks for users connected
    state.connect = () => {
      state.users += 1;
      if (state.users === 1) {
        state.vote = true;
      }
    };
    state.disconnect = () => {
      state.users -= 1;
      if (state.users === 0) {
        state.vote = false;
      }
    };
  }
};

// Export the entity
module.exports = entity;
