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

function GCounter() {
  let currentValue = Long.UZERO;
  let delta = Long.UZERO;

  Object.defineProperty(this, "longValue", {
    get: function () {
      return currentValue;
    }
  });

  Object.defineProperty(this, "value", {
    get: function () {
      return currentValue.toNumber();
    }
  });


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
    if (delta.gcounter === undefined) {
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
    if (state.gcounter === undefined) {
      throw new Error(util.format("Cannot apply state %o to GCounter", state));
    }
    currentValue = Long.fromValue(state.gcounter.value);
  };

  this.toString = function () {
    return "GCounter(" + currentValue + ")";
  };
}

module.exports = GCounter;