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
const Long = require("long");
const GCounter = require("../../src/crdts/gcounter");
const protobufHelper = require("../../src/protobuf-helper");

const CrdtDelta = protobufHelper.moduleRoot.lookupType("cloudstate.crdt.CrdtDelta");
const CrdtState = protobufHelper.moduleRoot.lookupType("cloudstate.crdt.CrdtState");

function roundTripDelta(delta) {
  return CrdtDelta.decode(CrdtDelta.encode(delta).finish());
}

function roundTripState(state) {
  return CrdtState.decode(CrdtState.encode(state).finish());
}

describe("GCounter", () => {

  it("should have a value of zero when instantiated", () => {
    const counter = new GCounter();
    counter.value.should.equal(0);
  });

  it("should reflect a state update", () => {
    const counter = new GCounter();
    counter.applyState(roundTripState({
      gcounter: {
        value: 10
      }
    }));
    counter.value.should.equal(10);
    // Try changing it again
    counter.applyState(roundTripState({
      gcounter: {
        value: 5
      }
    }));
    counter.value.should.equal(5);
  });

  it("should reflect a delta update", () => {
    const counter = new GCounter();
    counter.applyDelta(roundTripDelta({
      gcounter: {
        increment: 10
      }
    }));
    counter.value.should.equal(10);
    // Try incrementing it again
    counter.applyDelta(roundTripDelta({
      gcounter: {
        increment: 5
      }
    }));
    counter.value.should.equal(15);
  });

  it("should generate deltas", () => {
    const counter = new GCounter();
    counter.increment(10);
    counter.value.should.equal(10);
    roundTripDelta(counter.getAndResetDelta()).gcounter.increment.toNumber().should.equal(10);
    should.equal(counter.getAndResetDelta(), null);
    counter.increment(3);
    counter.value.should.equal(13);
    counter.increment(4);
    counter.value.should.equal(17);
    roundTripDelta(counter.getAndResetDelta()).gcounter.increment.toNumber().should.equal(7);
    should.equal(counter.getAndResetDelta(), null);
  });

  it("should return its state", () => {
    const counter = new GCounter();
    counter.increment(10);
    counter.value.should.equal(10);
    roundTripState(counter.getStateAndResetDelta()).gcounter.value.toNumber().should.equal(10);
    should.equal(counter.getAndResetDelta(), null);
  });

  it("should not allow decrementing", () => {
    const counter = new GCounter();
    (() => counter.increment(-10)).should.throw();
  });

  it("should support long values", () => {
    const impossibleDouble = Long.UZERO.add(Number.MAX_SAFE_INTEGER).add(1);
    const counter = new GCounter();
    counter.increment(Number.MAX_SAFE_INTEGER);
    counter.increment(1);
    counter.longValue.should.eql(impossibleDouble);
    roundTripState(counter.getStateAndResetDelta()).gcounter.value.should.eql(impossibleDouble);
  });

  it("should support incrementing by long values", () => {
    const impossibleDouble = Long.UZERO.add(Number.MAX_SAFE_INTEGER).add(1);
    const counter = new GCounter();
    counter.increment(impossibleDouble);
    counter.longValue.should.eql(impossibleDouble);
    roundTripDelta(counter.getAndResetDelta()).gcounter.increment.should.eql(impossibleDouble);
    roundTripState(counter.getStateAndResetDelta()).gcounter.value.should.eql(impossibleDouble);
  });

});