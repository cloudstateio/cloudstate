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
 * @classdesc A Positive-Negative Counter CRDT.
 *
 * A counter that can be incremented and decremented.
 *
 * The value is stored as a 64-bit signed long, hence values over `2^63 - 1` and less than `2^63` can't be represented.
 *
 * @constructor module:cloudstate.crdt.PNCounter
 * @implements module:cloudstate.crdt.CrdtState
 */
function PNCounter() {
  let currentValue = Long.ZERO;
  let delta = Long.ZERO;

  /**
   * The value as a long.
   *
   * @name module:cloudstate.crdt.PNCounter#longValue
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
   * representation of the value. If you expect it to exceed `2^53`, {@link module:cloudstate.crdt.PNCounter#longValue}
   * should be used instead.
   *
   * @name module:cloudstate.crdt.PNCounter#value
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
   * @function module:cloudstate.crdt.PNCounter#increment
   * @param {Long|number} increment The amount to increment the counter by. If negative, it will be decremented instead.
   * @returns {module:cloudstate.crdt.PNCounter} This counter.
   */
  this.increment = function (increment) {
    currentValue = currentValue.add(increment);
    delta = delta.add(increment);
    return this;
  };

  /**
   * Decrement the counter by the given number.
   *
   * @function module:cloudstate.crdt.PNCounter#decrement
   * @param {Long|number} decrement The amount to decrement the counter by. If negative, it will be incremented instead.
   * @returns {module:cloudstate.crdt.PNCounter} This counter.
   */
  this.decrement = function (decrement) {
    currentValue = currentValue.subtract(decrement);
    delta = delta.subtract(decrement);
    return this;
  };

  this.getAndResetDelta = function (initial) {
    if (!delta.isZero() || initial) {
      const crdtDelta = {
        pncounter: {
          change: delta
        }
      };
      delta = Long.ZERO;
      return crdtDelta;
    } else {
      return null;
    }
  };

  this.applyDelta = function (delta) {
    if (!delta.pncounter) {
      throw new Error(util.format("Cannot apply delta %o to PNCounter", delta));
    }
    currentValue = currentValue.add(delta.pncounter.change);
  };

  this.toString = function () {
    return "PNCounter(" + currentValue + ")";
  };
}

module.exports = PNCounter;
