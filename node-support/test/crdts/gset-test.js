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
const GSet = require("../../src/crdts/gset");
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

describe("GSet", () => {

  it("should have no elements when instantiated", () => {
    const set = new GSet();
    set.size.should.equal(0);
    should.equal(set.getAndResetDelta(), null);
    roundTripState(set.getStateAndResetDelta()).gset.items.should.be.empty;
  });

  it("should reflect a state update", () => {
    const set = new GSet();
    set.applyState(roundTripState({
      gset: {
        items: [toAny("one"), toAny("two")]
      }
    }), anySupport);
    set.size.should.equal(2);
    new Set(set).should.include("one", "two");
    should.equal(set.getAndResetDelta(), null);
    roundTripState(set.getStateAndResetDelta()).gset.items.should.have.lengthOf(2);
  });

  it("should generate an add delta", () => {
    const set = new GSet().add("one");
    set.has("one").should.be.true;
    set.size.should.equal(1);
    const delta1 = roundTripDelta(set.getAndResetDelta());
    delta1.gset.added.should.have.lengthOf(1);
    fromAnys(delta1.gset.added).should.include("one");
    should.equal(set.getAndResetDelta(), null);

    set.add("two").add("three");
    set.size.should.equal(3);
    const delta2 = roundTripDelta(set.getAndResetDelta());
    delta2.gset.added.should.have.lengthOf(2);
    fromAnys(delta2.gset.added).should.include.members(["two", "three"]);
    should.equal(set.getAndResetDelta(), null);
  });

  it("should not generate a delta when an already existing element is added", () => {
    const set = new GSet().add("one");
    set.getAndResetDelta();
    set.add("one").size.should.equal(1);
    should.equal(set.getAndResetDelta(), null);
  });

  it("should reflect a delta add", () => {
    const set = new GSet().add("one");
    set.getAndResetDelta();
    set.applyDelta(roundTripDelta({
      gset: {
        added: [toAny("two")]
      }
    }), anySupport);
    set.size.should.equal(2);
    new Set(set).should.include("one", "two");
    should.equal(set.getAndResetDelta(), null);
    roundTripState(set.getStateAndResetDelta()).gset.items.should.have.lengthOf(2);
  });

  it("should work with protobuf types", () => {
    const set = new GSet().add(Example.create({ field1: "one" }));
    set.getAndResetDelta();
    // Equality check, make sure two equal protobufs are equal.
    set.add(Example.create({ field1: "one" }));
    set.size.should.equal(1);
    // Now add a non equal element and check.
    set.add(Example.create({ field1: "two" }));
    set.size.should.equal(2);
    const delta = roundTripDelta(set.getAndResetDelta());
    delta.gset.added.should.have.lengthOf(1);
    fromAnys(delta.gset.added)[0].field1.should.equal("two");
  });

  it("should work with json types", () => {
    const set = new GSet().add({ foo: "bar" });
    set.getAndResetDelta();
    set.add({ foo: "bar" });
    set.size.should.equal(1);
    set.add({ foo: "baz" });
    set.size.should.equal(2);
    const delta = roundTripDelta(set.getAndResetDelta());
    delta.gset.added.should.have.lengthOf(1);
    fromAnys(delta.gset.added)[0].foo.should.equal("baz");
  });

});