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
const crdts = require("../../src/crdts");
const LWWRegister = crdts.LWWRegister;
const Clocks = crdts.Clocks;
const protobufHelper = require("../../src/protobuf-helper");
const AnySupport = require("../../src/protobuf-any");

const CrdtDelta = protobufHelper.moduleRoot.cloudstate.crdt.CrdtDelta;

const root = new protobuf.Root();
root.loadSync(path.join(__dirname, "..", "example.proto"));
root.resolveAll();
const Example = root.lookupType("com.example.Example");
const anySupport = new AnySupport(root);

function roundTripDelta(delta) {
  return CrdtDelta.decode(CrdtDelta.encode(delta).finish());
}

function toAny(value) {
  return AnySupport.serialize(value, true, true)
}

function fromAny(value) {
  return anySupport.deserialize(value);
}

describe("LWWRegister", () => {

  it("should be instantiated with a value", () => {
    const register = new LWWRegister(Example.create({ field1: "foo" }));
    register.value.field1.should.equal("foo");
    const initial = roundTripDelta(register.getAndResetDelta()).lwwregister;
    fromAny(initial.value).field1.should.equal("foo");
    initial.clock.should.eql(Clocks.DEFAULT);
  });

  it("should reflect an initial delta", () => {
    const register = new LWWRegister(Example.create({ field1: "bar" }));
    register.applyDelta(roundTripDelta({
      lwwregister: {
        value: toAny(Example.create({ field1: "foo" }))
      }
    }), anySupport);
    register.value.field1.should.equal("foo");
    should.equal(register.getAndResetDelta(), null);
  });

  it("should generate a delta", () => {
    const register = new LWWRegister(Example.create({ field1: "foo" }));
    register.value = Example.create({ field1: "bar" });
    register.value.field1.should.equal("bar");
    const delta = roundTripDelta(register.getAndResetDelta()).lwwregister;
    fromAny(delta.value).field1.should.equal("bar");
    delta.clock.should.eql(Clocks.DEFAULT);
    should.equal(register.getAndResetDelta(), null);
  });

  it("should generate deltas with a custom clock", () => {
    const register = new LWWRegister(Example.create({ field1: "foo" }));
    register.setWithClock(Example.create({ field1: "bar" }), Clocks.CUSTOM, 10);
    register.value.field1.should.equal("bar");
    const delta = roundTripDelta(register.getAndResetDelta()).lwwregister;
    fromAny(delta.value).field1.should.equal("bar");
    delta.clock.should.eql(Clocks.CUSTOM);
    delta.customClockValue.toNumber().should.equal(10);
    should.equal(register.getAndResetDelta(), null);
  });

  it("should reflect a delta update", () => {
    const register = new LWWRegister(Example.create({ field1: "foo" }));
    register.applyDelta(roundTripDelta({
      lwwregister: {
        value: toAny(Example.create({ field1: "bar" }))
      }
    }), anySupport);
    register.value.field1.should.equal("bar");
    should.equal(register.getAndResetDelta(), null);
  });

  it("should work with primitive types", () => {
    const register = new LWWRegister("blah");
    register.value.should.equal("blah");
    register.value = "hello";
    register.value.should.equal("hello");
    const delta = roundTripDelta(register.getAndResetDelta());
    fromAny(delta.lwwregister.value).should.equal("hello");
  });

  it("should work with json types", () => {
    const register = new LWWRegister({ foo: "bar" });
    register.value.foo.should.equal("bar");
    register.value = { foo: "baz" };
    register.value.foo.should.equal("baz");
    const delta = roundTripDelta(register.getAndResetDelta());
    fromAny(delta.lwwregister.value).should.eql({ foo: "baz" });
  });

});
