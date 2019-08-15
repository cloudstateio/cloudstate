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

function PNCounter() {
  let currentValue = Long.ZERO;
  let delta = Long.ZERO;

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
    currentValue = currentValue.add(increment);
    delta = delta.add(increment);
    return this;
  };

  this.decrement = function (decrement) {
    currentValue = currentValue.subtract(decrement);
    delta = delta.subtract(decrement);
    return this;
  };

  this.getAndResetDelta = function () {
    if (!delta.isZero()) {
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


  this.getStateAndResetDelta = function () {
    delta = Long.ZERO;
    return {
      pncounter: {
        value: currentValue
      }
    };
  };

  this.applyState = function (state) {
    if (!state.pncounter) {
      throw new Error(util.format("Cannot apply state %o to PNCounter", state));
    }
    currentValue = Long.fromValue(state.pncounter.value);
  };

  this.toString = function () {
    return "PNCounter(" + currentValue + ")";
  };
}

module.exports = PNCounter;