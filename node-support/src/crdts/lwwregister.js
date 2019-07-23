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

const util = require("util");
const protobufHelper = require("../protobuf-helper");
const AnySupport = require("../protobuf-any");

const Clocks = protobufHelper.moduleRoot.lookupEnum("cloudstate.crdt.CrdtClock").values;

function LWWRegister(value, clock = Clocks.DEFAULT, customClockValue = 0) {
  if (value === null || value === undefined) {
    throw new Error("LWWRegister must be instantiated with an initial value.")
  }
  // Make sure the value can be serialized.
  AnySupport.serialize(value, true, true);
  let currentValue = value;
  let currentClock = clock;
  let currentCustomClockValue = customClockValue;
  let delta = {
    value: null,
    clock: null,
    customClockValue: 0
  };

  Object.defineProperty(this, "value", {
    get: function () {
      return currentValue;
    },
    set: function (value) {
      this.setWithClock(value)
    }.bind(this)
  });

  /**
   * Set the the value.
   *
   * @param value The value to set.
   * @param clock The clock.
   * @param customClockValue Ignored if a custom clock isn't specified.
   */
  this.setWithClock = function (value, clock = Clocks.DEFAULT, customClockValue = 0) {
    delta.value = AnySupport.serialize(value, true, true);
    if (clock !== undefined) {
      delta.clock = clock;
      delta.customClockValue = customClockValue;
    }
    currentValue = value;
    currentClock = clock;
    currentCustomClockValue = customClockValue;
    return this;
  };

  this.getAndResetDelta = function () {
    if (delta.value !== null) {
      const toReturn = delta;
      delta = {
        value: null,
        clock: null,
        customClockValue: 0
      };
      return {
        lwwregister: toReturn
      };
    } else {
      return null;
    }
  };

  this.applyDelta = function (delta, anySupport) {
    if (delta.lwwregister === undefined) {
      throw new Error(util.format("Cannot apply delta %o to LWWRegister", delta));
    }
    currentValue = anySupport.deserialize(delta.lwwregister.value);
  };

  this.getStateAndResetDelta = function () {
    delta = {
      value: null,
      clock: null,
      customClockValue: 0
    };
    return {
      lwwregister: {
        value: AnySupport.serialize(currentValue, true, true),
        clock: currentClock,
        customClockValue: currentCustomClockValue
      }
    };
  };

  this.applyState = function (state, anySupport) {
    if (state.lwwregister === undefined) {
      throw new Error(util.format("Cannot apply state %o to ORSet", state));
    }
    currentValue = anySupport.deserialize(state.lwwregister.value);
    currentClock = state.lwwregister.clock;
    currentCustomClockValue = state.lwwregister.customClockValue;
  };

  this.toString = function () {
    return "LWWRegister(" + currentValue + ")";
  };
}

module.exports = LWWRegister;