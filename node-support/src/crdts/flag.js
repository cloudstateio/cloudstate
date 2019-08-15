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

function Flag() {
  let currentValue = false;
  let delta = false;

  Object.defineProperty(this, "value", {
    get: function () {
      return currentValue;
    }
  });

  this.enable = function () {
    if (!currentValue) {
      currentValue = true;
      delta = true;
    }
    return this;
  };

  this.getAndResetDelta = function () {
    if (delta) {
      delta = false;
      return {
        flag: {
          value: true
        }
      };
    } else {
      return null;
    }
  };

  this.applyDelta = function (delta) {
    if (!delta.flag) {
      throw new Error(util.format("Cannot apply delta %o to Flag", delta));
    }
    currentValue = currentValue || delta.flag.value;
  };

  this.getStateAndResetDelta = function () {
    delta = false;
    return {
      flag: {
        value: currentValue
      }
    };
  };

  this.applyState = function (state) {
    if (!state.flag) {
      throw new Error(util.format("Cannot apply state %o to Flag", state));
    }
    currentValue = state.flag.value;
  };

  this.toString = function () {
    return "Flag(" + currentValue + ")";
  };
}

module.exports = Flag;