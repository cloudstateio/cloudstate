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
const PNCounter = require("../../src/crdts/pncounter");
const protobufHelper = require("../../src/protobuf-helper");

const CrdtDelta = protobufHelper.moduleRoot.cloudstate.crdt.CrdtDelta;

function roundTripDelta(delta) {
  return CrdtDelta.decode(CrdtDelta.encode(delta).finish());
}

describe("PNCounter", () => {

  it("should have a value of zero when instantiated", () => {
    const counter = new PNCounter();
    counter.value.should.equal(0);
    should.equal(counter.getAndResetDelta(), null);
  });

  it("should reflect a delta update", () => {
    const counter = new PNCounter();
    counter.applyDelta(roundTripDelta({
      pncounter: {
        change: 10
      }
    }));
    counter.value.should.equal(10);
    // Try incrementing it again
    counter.applyDelta(roundTripDelta({
      pncounter: {
        change: -3
      }
    }));
    counter.value.should.equal(7);
  });

  it("should generate deltas", () => {
    const counter = new PNCounter();
    counter.increment(10);
    counter.value.should.equal(10);
    roundTripDelta(counter.getAndResetDelta()).pncounter.change.toNumber().should.equal(10);
    should.equal(counter.getAndResetDelta(), null);
    counter.decrement(3);
    counter.value.should.equal(7);
    counter.decrement(4);
    counter.value.should.equal(3);
    roundTripDelta(counter.getAndResetDelta()).pncounter.change.toNumber().should.equal(-7);
    should.equal(counter.getAndResetDelta(), null);
  });

  it("should support long values", () => {
    const impossibleDouble = Long.ZERO.add(Number.MAX_SAFE_INTEGER).add(1);
    const counter = new PNCounter();
    counter.increment(Number.MAX_SAFE_INTEGER);
    counter.increment(1);
    counter.longValue.should.eql(impossibleDouble);
    roundTripDelta(counter.getAndResetDelta()).pncounter.change.should.eql(impossibleDouble);
  });

  it("should support incrementing by long values", () => {
    const impossibleDouble = Long.ZERO.add(Number.MAX_SAFE_INTEGER).add(1);
    const counter = new PNCounter();
    counter.increment(impossibleDouble);
    counter.longValue.should.eql(impossibleDouble);
    roundTripDelta(counter.getAndResetDelta()).pncounter.change.should.eql(impossibleDouble);
  });

  it("should support empty initial deltas (for ORMap added)", () => {
    const counter = new PNCounter();
    counter.value.should.equal(0);
    should.equal(counter.getAndResetDelta(), null);
    roundTripDelta(counter.getAndResetDelta(/* initial = */ true)).pncounter.change.toNumber().should.equal(0);
  });

});
