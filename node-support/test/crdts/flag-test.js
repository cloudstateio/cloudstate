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
const Flag = require("../../src/crdts/flag");
const protobufHelper = require("../../src/protobuf-helper");

const CrdtDelta = protobufHelper.moduleRoot.cloudstate.crdt.CrdtDelta;

function roundTripDelta(delta) {
  return CrdtDelta.decode(CrdtDelta.encode(delta).finish());
}

describe("Flag", () => {

  it("should be disabled when instantiated", () => {
    const flag = new Flag();
    flag.value.should.be.false;
    should.equal(flag.getAndResetDelta(), null);
  });

  it("should reflect an initial delta", () => {
    const flag = new Flag();
    flag.applyDelta(roundTripDelta({
      flag: {
        value: true
      }
    }));
    flag.value.should.be.true;
  });

  it("should reflect a delta update", () => {
    const flag = new Flag();
    flag.applyDelta(roundTripDelta({
      flag: {
        value: true
      }
    }));
    flag.value.should.be.true;
  });

  it("should generate deltas", () => {
    const flag = new Flag();
    should.equal(flag.getAndResetDelta(), null);
    flag.enable();
    roundTripDelta(flag.getAndResetDelta()).flag.value.should.be.true;
    should.equal(flag.getAndResetDelta(), null);
  });

  it("should support empty initial deltas (for ORMap added)", () => {
    const flag = new Flag();
    flag.value.should.be.false;
    should.equal(flag.getAndResetDelta(), null);
    roundTripDelta(flag.getAndResetDelta(/* initial = */ true)).flag.value.should.be.false;
  });

});
