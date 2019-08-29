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
const ORMap = crdts.ORMap;
const protobufHelper = require("../../src/protobuf-helper");
const AnySupport = require("../../src/protobuf-any");

const CrdtDelta = protobufHelper.moduleRoot.cloudstate.crdt.CrdtDelta;
const CrdtState = protobufHelper.moduleRoot.cloudstate.crdt.CrdtState;

const root = new protobuf.Root();
root.loadSync(path.join(__dirname, "..", "example.proto"));
root.resolveAll();
const Example = root.lookupType("com.example.Example");
const anySupport = new AnySupport(root);

function roundTripDelta(delta) {
  return CrdtDelta.decode(CrdtDelta.encode(delta).finish());
}

function roundTripState(state) {
  return CrdtState.decode(CrdtState.encode(state).finish());
}

function toAny(value) {
  return AnySupport.serialize(value, true, true)
}

function fromAnys(values) {
  return values.map(any => anySupport.deserialize(any));
}

function fromEntries(entries) {
  return entries.map(entry => {
    return {
      key: anySupport.deserialize(entry.key),
      value: entry.value,
      delta: entry.delta
    }
  });
}

function toMapCounterEntry(key, value) {
  return { key: toAny(key), value: { gcounter: { value : value } } };
}

describe("ORMap", () => {

  it("should have no elements when instantiated", () => {
    const map = new ORMap();
    map.size.should.equal(0);
    should.equal(map.getAndResetDelta(), null);
    roundTripState(map.getStateAndResetDelta()).ormap.entries.should.be.empty;
  });

  it("should reflect a state update", () => {
    const map = new ORMap();
    map.applyState(roundTripState({
      ormap: {
        entries: [toMapCounterEntry("one", 5), toMapCounterEntry("two", 7)]
      }
    }), anySupport, crdts.createCrdtForState);
    map.size.should.equal(2);
    new Set(map.keys()).should.include("one", "two");
    map.asObject.one.value.should.equal(5);
    map.asObject.two.value.should.equal(7);
    should.equal(map.getAndResetDelta(), null);
    roundTripState(map.getStateAndResetDelta()).ormap.entries.should.have.lengthOf(2);
  });

  it("should generate an add delta", () => {
    const map = new ORMap().set("one", new crdts.GCounter());
    map.has("one").should.be.true;
    map.size.should.equal(1);
    const delta1 = roundTripDelta(map.getAndResetDelta());
    delta1.ormap.added.should.have.lengthOf(1);
    const entry = fromEntries(delta1.ormap.added)[0];
    entry.key.should.equal("one");
    entry.value.gcounter.value.toNumber().should.equal(0);
    should.equal(map.getAndResetDelta(), null);

    map.asObject.two = new crdts.GCounter();
    map.asObject.two.increment(10);
    map.size.should.equal(2);
    const delta2 = roundTripDelta(map.getAndResetDelta());
    delta2.ormap.added.should.have.lengthOf(1);
    const entry2 = fromEntries(delta2.ormap.added)[0];
    entry2.key.should.equal("two");
    entry2.value.gcounter.value.toNumber().should.equal(10);
    should.equal(map.getAndResetDelta(), null);

    delta2.ormap.updated.should.have.lengthOf(0);
  });

  it("should generate a remove delta", () => {
    const map = new ORMap()
      .set("one", new crdts.GCounter())
      .set("two", new crdts.GCounter())
      .set("three", new crdts.GCounter());
    map.getAndResetDelta();
    map.has("one").should.be.true;
    map.has("two").should.be.true;
    map.has("three").should.be.true;
    map.size.should.equal(3);
    map.delete("one").delete("two");
    map.size.should.equal(1);
    map.has("three").should.be.true;
    map.has("one").should.be.false;
    map.has("two").should.be.false;
    const delta = roundTripDelta(map.getAndResetDelta());
    delta.ormap.removed.should.have.lengthOf(2);
    fromAnys(delta.ormap.removed).should.include.members(["one", "two"]);
    should.equal(map.getAndResetDelta(), null);
  });

  it("should generate an update delta", () => {
    const map = new ORMap().set("one", new crdts.GCounter());
    map.getAndResetDelta();
    map.get("one").increment(5);
    const delta = roundTripDelta(map.getAndResetDelta());
    delta.ormap.updated.should.have.lengthOf(1);
    const entry = fromEntries(delta.ormap.updated)[0];
    entry.key.should.equal("one");
    entry.delta.gcounter.increment.toNumber().should.equal(5);
    should.equal(map.getAndResetDelta(), null);
  });

  it("should generate a clear delta", () => {
    const map = new ORMap()
      .set("one", new crdts.GCounter())
      .set("two", new crdts.GCounter());
    map.getAndResetDelta();
    map.clear().size.should.equal(0);
    const delta = roundTripDelta(map.getAndResetDelta());
    delta.ormap.cleared.should.be.true;
    should.equal(map.getAndResetDelta(), null);
  });

  it("should generate a clear delta when everything is removed", () => {
    const map = new ORMap()
      .set("one", new crdts.GCounter())
      .set("two", new crdts.GCounter());
    map.getAndResetDelta();
    map.delete("one").delete("two").size.should.equal(0);
    const delta = roundTripDelta(map.getAndResetDelta());
    delta.ormap.cleared.should.be.true;
    should.equal(map.getAndResetDelta(), null);
  });

  it("should not generate a delta when an added element is removed", () => {
    const map = new ORMap()
      .set("one", new crdts.GCounter());
    map.getAndResetDelta();
    map.set("two", new crdts.GCounter()).delete("two").size.should.equal(1);
    should.equal(map.getAndResetDelta(), null);
  });

  it("should generate a delta when a removed element is added", () => {
    const map = new ORMap()
      .set("one", new crdts.GCounter())
      .set("two", new crdts.GCounter());
    map.getAndResetDelta();
    map.delete("two").set("two", new crdts.GCounter()).size.should.equal(2);
    const delta = roundTripDelta(map.getAndResetDelta());
    delta.ormap.removed.should.have.lengthOf(1);
    delta.ormap.added.should.have.lengthOf(1);
    delta.ormap.updated.should.have.lengthOf(0);
  });

  it("should generate a delta when an already existing element is set", () => {
    const map = new ORMap()
      .set("one", new crdts.GCounter());
    map.getAndResetDelta();
    map.set("one", new crdts.GCounter()).size.should.equal(1);
    const delta = roundTripDelta(map.getAndResetDelta());
    delta.ormap.removed.should.have.lengthOf(1);
    delta.ormap.added.should.have.lengthOf(1);
    delta.ormap.updated.should.have.lengthOf(0);
  });

  it("should not generate a delta when a non existing element is removed", () => {
    const map = new ORMap()
      .set("one", new crdts.GCounter());
    map.getAndResetDelta();
    map.delete("two").size.should.equal(1);
    should.equal(map.getAndResetDelta(), null);
  });

  it("should generate a delta when an already existing element is set", () => {
    const map = new ORMap()
      .set("one", new crdts.GCounter());
    map.getAndResetDelta();
    map.set("one", new crdts.GCounter()).size.should.equal(1);
    const delta = roundTripDelta(map.getAndResetDelta());
    delta.ormap.removed.should.have.lengthOf(1);
    delta.ormap.added.should.have.lengthOf(1);
    delta.ormap.updated.should.have.lengthOf(0);
  });

  it("clear all other deltas when the set is cleared", () => {
    const map = new ORMap().set("one", new crdts.GCounter())
      .set("two", new crdts.GCounter());
    map.getAndResetDelta();
    map.asObject.two.increment(10);
    map.set("one", new crdts.GCounter()).clear().size.should.equal(0);
    const delta = roundTripDelta(map.getAndResetDelta());
    delta.ormap.cleared.should.be.true;
    delta.ormap.added.should.have.lengthOf(0);
    delta.ormap.removed.should.have.lengthOf(0);
    delta.ormap.updated.should.have.lengthOf(0);
  });

  it("should reflect a delta add", () => {
    const map = new ORMap()
      .set("one", new crdts.GCounter());
    map.getAndResetDelta();
    map.applyDelta(roundTripDelta({
      ormap: {
        added: [ { key: toAny("two"), value : {
          gcounter: { value: 4 }
        } } ]
      }
    }), anySupport, crdts.createCrdtForState);
    map.size.should.equal(2);
    new Set(map.keys()).should.include("one", "two");
    map.asObject.two.value.should.equal(4);
    should.equal(map.getAndResetDelta(), null);
    roundTripState(map.getStateAndResetDelta()).ormap.entries.should.have.lengthOf(2);
  });

  it("should reflect a delta remove", () => {
    const map = new ORMap()
      .set("one", new crdts.GCounter())
      .set("two", new crdts.GCounter());
    map.getAndResetDelta();
    map.applyDelta(roundTripDelta({
      ormap: {
        removed: [toAny("two")]
      }
    }), anySupport);
    map.size.should.equal(1);
    new Set(map.keys()).should.include("one");
    should.equal(map.getAndResetDelta(), null);
    roundTripState(map.getStateAndResetDelta()).ormap.entries.should.have.lengthOf(1);
  });

  it("should reflect a delta clear", () => {
    const map = new ORMap()
      .set("one", new crdts.GCounter())
      .set("two", new crdts.GCounter());
    map.getAndResetDelta();
    map.applyDelta(roundTripDelta({
      ormap: {
        cleared: true
      }
    }), anySupport);
    map.size.should.equal(0);
    should.equal(map.getAndResetDelta(), null);
    roundTripState(map.getStateAndResetDelta()).ormap.entries.should.have.lengthOf(0);
  });

  it("should work with protobuf keys", () => {
    const map = new ORMap()
      .set(Example.create({ field1: "one" }), new crdts.GCounter())
      .set(Example.create({ field1: "two" }), new crdts.GCounter());
    map.getAndResetDelta();
    map.delete(Example.create({ field1: "one" }));
    map.size.should.equal(1);
    const delta = roundTripDelta(map.getAndResetDelta());
    delta.ormap.removed.should.have.lengthOf(1);
    fromAnys(delta.ormap.removed)[0].field1.should.equal("one");
  });

  it("should work with json types", () => {
    const map = new ORMap()
      .set({ foo: "bar" }, new crdts.GCounter())
      .set({ foo: "baz" }, new crdts.GCounter());
    map.getAndResetDelta();
    map.delete({ foo: "bar" });
    map.size.should.equal(1);
    const delta = roundTripDelta(map.getAndResetDelta());
    delta.ormap.removed.should.have.lengthOf(1);
    fromAnys(delta.ormap.removed)[0].foo.should.equal("bar");
  });

});