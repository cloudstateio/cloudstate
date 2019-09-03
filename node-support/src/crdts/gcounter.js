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
const Long = require("long");

/**
 * @classdesc A Grow-only counter CRDT.
 *
 * As the name suggests, a grow only counter can be incremented, but not decremented.
 *
 * The value is stored as a 64-bit unsigned long, hence values over `2^64` can't be represented.
 *
 * @constructor cloudstate.crdt.GCounter
 * @implements cloudstate.crdt.CrdtState
 */
function GCounter() {
  let currentValue = Long.UZERO;
  let delta = Long.UZERO;

  /**
   * The value as a long.
   *
   * @name cloudstate.crdt.GCounter#longValue
   * @type {Long}
   * @readonly
   */
  Object.defineProperty(this, "longValue", {
    get: function () {
      return currentValue;
    }
  });

  /**
   * The value as a number. Note that once the value exceeds `2^53`, this will not be an accurate
   * representation of the value. If you expect it to exceed `2^53`, {@link cloudstate.crdt.GCounter#longValue} should be
   * used instead.
   *
   * @name cloudstate.crdt.GCounter#value
   * @type {number}
   * @readonly
   */
  Object.defineProperty(this, "value", {
    get: function () {
      return currentValue.toNumber();
    }
  });

  /**
   * Increment the counter by the given number.
   *
   * @function cloudstate.crdt.GCounter#increment
   * @param {Long|number} increment The amount to increment the counter by.
   * @returns {cloudstate.crdt.GCounter} This counter.
   * @throws If the increment is less than zero.
   */
  this.increment = function (increment) {
    if (Long.ZERO.comp(increment) === 1) {
      throw new Error("Cannot decrement a GCounter");
    }
    currentValue = currentValue.add(increment);
    delta = delta.add(increment);
    return this;
  };

  this.getAndResetDelta = function () {
    if (delta.greaterThan(0)) {
      const crdtDelta = {
        gcounter: {
          increment: delta
        }
      };
      delta = Long.UZERO;
      return crdtDelta;
    } else {
      return null;
    }
  };

  this.applyDelta = function (delta) {
    if (!delta.gcounter) {
      throw new Error(util.format("Cannot apply delta %o to GCounter", delta));
    }
    currentValue = currentValue.add(delta.gcounter.increment);
  };

  this.getStateAndResetDelta = function () {
    delta = Long.UZERO;
    return {
      gcounter: {
        value: currentValue
      }
    };
  };

  this.applyState = function (state) {
    if (!state.gcounter) {
      throw new Error(util.format("Cannot apply state %o to GCounter", state));
    }
    currentValue = Long.fromValue(state.gcounter.value);
  };

  this.toString = function () {
    return "GCounter(" + currentValue + ")";
  };
}

module.exports = GCounter;