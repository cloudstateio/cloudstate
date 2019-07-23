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

const should = require("chai").should();
const protobuf = require("protobufjs");
const path = require("path");
const Long = require("long");
const support = require("../src/crdt-support");
const crdts = require("../src/crdts");
const AnySupport = require("../src/protobuf-any");
const protobufHelper = require("../src/protobuf-helper");

const root = new protobuf.Root();
root.loadSync(path.join(__dirname, "example.proto"));
root.resolveAll();
const anySupport = new AnySupport(root);

const In = root.lookupType("com.example.In");
const Out = root.lookupType("com.example.Out");
const ExampleService = root.lookupService("com.example.ExampleService");

const CrdtStreamIn = protobufHelper.moduleRoot.lookupType("cloudstate.crdt.CrdtStreamIn");
const CrdtStreamOut = protobufHelper.moduleRoot.lookupType("cloudstate.crdt.CrdtStreamOut");
const CrdtInit = protobufHelper.moduleRoot.lookupType("cloudstate.crdt.CrdtInit");

const outMsg = {
  field: "ok"
};
const inMsg = {
  field: "ok"
};

class MockCall {
  constructor() {
    this.written = [];
    this.ended = false;
  }

  write(msg) {
    this.written.push(msg);
  }

  end() {
    this.ended = true;
  }

  get() {
    if (this.written.length === 0) {
      throw new Error("No messages in call written buffer!")
    } else {
      return this.written.shift()
    }
  }

  expectNoWrites() {
    this.written.should.be.empty;
  }
}

const call = new MockCall();
function createHandler(commandHandler, state = undefined) {
   const entity = new support.CrdtSupport(root, ExampleService, {
     DoSomething: commandHandler
   }, {});
   return entity.create(call, CrdtInit.decode(CrdtInit.encode({
     entityId: "foo",
     state: state
   }).finish()));
}

// This ensures we have the field names right, rather than just matching them between
// the tests and the code.
function roundTripCrdtStreamIn(msg) {
  return CrdtStreamIn.decode(CrdtStreamIn.encode(msg).finish());
}

function handleCommand(handler, command) {
  const response = doHandleCommand(handler, command);
  if (response.failure !== null) {
    throw new Error(response.failure.description)
  }
  const reply = response.reply;
  reply.commandId.should.eql(Long.fromNumber(10));
  return reply;
}

function doHandleCommand(handler, command) {
  send(handler, {
    command: {
      entityId: "foo",
      id: 10,
      name: "DoSomething",
      payload: AnySupport.serialize(In.create(command))
    }
  });
  return CrdtStreamOut.decode(CrdtStreamOut.encode(call.get()).finish());
}

function handleFailedCommand(handler, command) {
  const response = doHandleCommand(handler, command);
  if (response.failure !== null) {
    return response.failure;
  } else {
    response.reply.should.be.null;
  }
}

function send(handler, streamIn) {
  handler.onData(roundTripCrdtStreamIn(streamIn));
}

function assertHasNoAction(reply) {
  should.equal(reply.create, null);
  should.equal(reply.update, null);
  should.equal(reply.delete, null);
}

describe("CrdtHandler", () => {

  it("should start with no state", () => {
    const handler = createHandler((cmd, ctx) => {
      should.equal(ctx.state, null);
      return outMsg;
    });

    const reply = handleCommand(handler, inMsg);
    assertHasNoAction(reply);
    anySupport.deserialize(reply.reply.payload).field.should.equal(outMsg.field);
  });

  it("should populate state with the state from init if present", () => {
    const handler = createHandler((cmd, ctx) => {
      ctx.state.value.should.equal(5);
      return outMsg;
    }, {
      gcounter: {
        value: 5
      }
    });

    assertHasNoAction(handleCommand(handler, inMsg));
  });

  it("should create state a new state is set", () => {
    const handler = createHandler((cmd, ctx) => {
      ctx.state = new crdts.GCounter();
      return outMsg;
    });

    const reply = handleCommand(handler, inMsg);
    reply.create.gcounter.value.toNumber().should.equal(0);
  });

  it("should send an update when the state is updated", () => {
    const handler = createHandler((cmd, ctx) => {
      ctx.state.increment(3);
      return outMsg;
    }, {
      gcounter: {
        value: 5
      }
    });
    const reply = handleCommand(handler, inMsg);
    reply.update.gcounter.increment.toNumber().should.equal(3);
  });

  it("should set the state when it receives a state message", () => {
    const handler = createHandler((cmd, ctx) => {
      ctx.state.value.should.equal(5);
      return outMsg;
    });
    send(handler, {
      state: {
        gcounter: {
          value: 5
        }
      }
    });
    assertHasNoAction(handleCommand(handler, inMsg));
  });

  it("should replace the state when it receives a state message", () => {
    const handler = createHandler((cmd, ctx) => {
      ctx.state.value.should.equal(5);
      return outMsg;
    }, {
      gcounter: {
        value: 2
      }
    });
    send(handler, {
      state: {
        gcounter: {
          value: 5
        }
      }
    });
    assertHasNoAction(handleCommand(handler, inMsg));
  });

  it("should update the state when it receives a changed message", () => {
    const handler = createHandler((cmd, ctx) => {
      ctx.state.value.should.equal(7);
      return outMsg;
    }, {
      gcounter: {
        value: 2
      }
    });
    send(handler, {
      changed: {
        gcounter: {
          increment: 5
        }
      }
    });
    assertHasNoAction(handleCommand(handler, inMsg));
  });

  it("should allow deleting an entity", () => {
    const handler = createHandler((cmd, ctx) => {
      ctx.delete();
      return outMsg;
    }, {
      gcounter: {
        value: 2
      }
    });
    const reply = handleCommand(handler, inMsg);
    reply.delete.should.not.be.null;
  });

  it("should not allow deleting an entity that hasn't been created", () => {
    const handler = createHandler((cmd, ctx) => {
      ctx.delete();
      return outMsg;
    });
    handleFailedCommand(handler, inMsg);
  })

});