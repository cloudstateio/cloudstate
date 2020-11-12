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
const ORSet = require("../../src/crdts/orset");
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

function fromAnys(values) {
  return values.map(any => anySupport.deserialize(any));
}

describe("ORSet", () => {

  it("should have no elements when instantiated", () => {
    const set = new ORSet();
    set.size.should.equal(0);
    should.equal(set.getAndResetDelta(), null);
  });

  it("should reflect an initial delta", () => {
    const set = new ORSet();
    should.equal(set.getAndResetDelta(), null);
    set.applyDelta(roundTripDelta({
      orset: {
        added: [toAny("one"), toAny("two")]
      }
    }), anySupport);
    set.size.should.equal(2);
    new Set(set).should.include("one", "two");
    should.equal(set.getAndResetDelta(), null);
  });

  it("should generate an add delta", () => {
    const set = new ORSet().add("one");
    set.has("one").should.be.true;
    set.size.should.equal(1);
    const delta1 = roundTripDelta(set.getAndResetDelta());
    delta1.orset.added.should.have.lengthOf(1);
    fromAnys(delta1.orset.added).should.include("one");
    should.equal(set.getAndResetDelta(), null);

    set.add("two").add("three");
    set.size.should.equal(3);
    const delta2 = roundTripDelta(set.getAndResetDelta());
    delta2.orset.added.should.have.lengthOf(2);
    fromAnys(delta2.orset.added).should.include.members(["two", "three"]);
    should.equal(set.getAndResetDelta(), null);
  });

  it("should generate a remove delta", () => {
    const set = new ORSet().add("one").add("two").add("three");
    set.getAndResetDelta();
    set.has("one").should.be.true;
    set.has("two").should.be.true;
    set.has("three").should.be.true;
    set.size.should.equal(3);
    set.delete("one").delete("two");
    set.size.should.equal(1);
    set.has("three").should.be.true;
    set.has("one").should.be.false;
    set.has("two").should.be.false;
    const delta = roundTripDelta(set.getAndResetDelta());
    delta.orset.removed.should.have.lengthOf(2);
    fromAnys(delta.orset.removed).should.include.members(["one", "two"]);
    should.equal(set.getAndResetDelta(), null);
  });

  it("should generate a clear delta", () => {
    const set = new ORSet().add("one").add("two");
    set.getAndResetDelta();
    set.clear().size.should.equal(0);
    const delta = roundTripDelta(set.getAndResetDelta());
    delta.orset.cleared.should.be.true;
    should.equal(set.getAndResetDelta(), null);
  });

  it("should generate a clear delta when everything is removed", () => {
    const set = new ORSet().add("one").add("two");
    set.getAndResetDelta();
    set.delete("one").delete("two").size.should.equal(0);
    const delta = roundTripDelta(set.getAndResetDelta());
    delta.orset.cleared.should.be.true;
    should.equal(set.getAndResetDelta(), null);
  });

  it("should not generate a delta when an added element is removed", () => {
    const set = new ORSet().add("one");
    set.getAndResetDelta();
    set.add("two").delete("two").size.should.equal(1);
    should.equal(set.getAndResetDelta(), null);
  });

  it("should not generate a delta when a removed element is added", () => {
    const set = new ORSet().add("one").add("two");
    set.getAndResetDelta();
    set.delete("two").add("two").size.should.equal(2);
    should.equal(set.getAndResetDelta(), null);
  });

  it("should not generate a delta when an already existing element is added", () => {
    const set = new ORSet().add("one");
    set.getAndResetDelta();
    set.add("one").size.should.equal(1);
    should.equal(set.getAndResetDelta(), null);
  });

  it("should not generate a delta when a non existing element is removed", () => {
    const set = new ORSet().add("one");
    set.getAndResetDelta();
    set.delete("two").size.should.equal(1);
    should.equal(set.getAndResetDelta(), null);
  });

  it("clear all other deltas when the set is cleared", () => {
    const set = new ORSet().add("one");
    set.getAndResetDelta();
    set.add("two").delete("one").clear().size.should.equal(0);
    const delta = roundTripDelta(set.getAndResetDelta());
    delta.orset.cleared.should.be.true;
    delta.orset.added.should.have.lengthOf(0);
    delta.orset.removed.should.have.lengthOf(0);
  });

  it("should reflect a delta add", () => {
    const set = new ORSet().add("one");
    const delta1 = roundTripDelta(set.getAndResetDelta());
    delta1.orset.added.should.have.lengthOf(1);
    fromAnys(delta1.orset.added).should.include("one");
    set.applyDelta(roundTripDelta({
      orset: {
        added: [toAny("two")]
      }
    }), anySupport);
    set.size.should.equal(2);
    new Set(set).should.include("one", "two");
    should.equal(set.getAndResetDelta(), null);
  });

  it("should reflect a delta remove", () => {
    const set = new ORSet().add("one").add("two");
    const delta1 = roundTripDelta(set.getAndResetDelta());
    delta1.orset.added.should.have.lengthOf(2);
    fromAnys(delta1.orset.added).should.include("one", "two");
    set.applyDelta(roundTripDelta({
      orset: {
        removed: [toAny("two")]
      }
    }), anySupport);
    set.size.should.equal(1);
    new Set(set).should.include("one");
    should.equal(set.getAndResetDelta(), null);
  });

  it("should reflect a delta clear", () => {
    const set = new ORSet().add("one").add("two");
    const delta1 = roundTripDelta(set.getAndResetDelta());
    delta1.orset.added.should.have.lengthOf(2);
    fromAnys(delta1.orset.added).should.include("one", "two");
    set.applyDelta(roundTripDelta({
      orset: {
        cleared: true
      }
    }), anySupport);
    set.size.should.equal(0);
    should.equal(set.getAndResetDelta(), null);
  });

  it("should work with protobuf types", () => {
    const set = new ORSet().add(Example.create({ field1: "one" })).add(Example.create({ field1: "two" }));
    set.getAndResetDelta();
    set.delete(Example.create({ field1: "one" }));
    set.size.should.equal(1);
    const delta = roundTripDelta(set.getAndResetDelta());
    delta.orset.removed.should.have.lengthOf(1);
    fromAnys(delta.orset.removed)[0].field1.should.equal("one");
  });

  it("should work with json types", () => {
    const set = new ORSet().add({ foo: "bar" }).add({ foo: "baz" });
    set.getAndResetDelta();
    set.delete({ foo: "bar" });
    set.size.should.equal(1);
    const delta = roundTripDelta(set.getAndResetDelta());
    delta.orset.removed.should.have.lengthOf(1);
    fromAnys(delta.orset.removed)[0].foo.should.equal("bar");
  });

  it("should support empty initial deltas (for ORMap added)", () => {
    const set = new ORSet();
    set.size.should.equal(0);
    should.equal(set.getAndResetDelta(), null);
    roundTripDelta(set.getAndResetDelta(/* initial = */ true)).orset.added.should.have.lengthOf(0);
  });

});
