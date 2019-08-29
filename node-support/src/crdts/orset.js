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

const debug = require("debug")("cloudstate-crdt");
const util = require("util");
const AnySupport = require("../protobuf-any");

function ORSet() {
  // Map of a comparable form (that compares correctly using ===) of the elements to the elements
  let currentValue = new Map();
  let delta = {
    added: new Map(),
    removed: new Map(),
    cleared: false
  };

  this.has = function (element) {
    return currentValue.has(AnySupport.toComparable(element));
  };

  Object.defineProperty(this, "size", {
    get: function () {
      return currentValue.size;
    }
  });

  this.forEach = function (callback) {
    return currentValue.forEach((value, key) => callback(value));
  };

  this[Symbol.iterator] = function () {
    return currentValue.values();
  };

  /**
   * Add an element to this set.
   *
   * @param element The element.
   */
  this.add = function (element) {
    const comparable = AnySupport.toComparable(element);
    if (!currentValue.has(comparable)) {
      if (delta.removed.has(comparable)) {
        delta.removed.delete(comparable)
      } else {
        const serializedElement = AnySupport.serialize(element, true, true);
        delta.added.set(comparable, serializedElement);
      }
      currentValue.set(comparable, element);
    }
    return this;
  };

  this.delete = function (element) {
    const comparable = AnySupport.toComparable(element);
    if (currentValue.has(comparable)) {
      if (currentValue.size === 1) {
        this.clear();
      } else {
        currentValue.delete(comparable);
        if (delta.added.has(comparable)) {
          delta.added.delete(comparable);
        } else {
          const serializedElement = AnySupport.serialize(element, true, true);
          delta.removed.set(comparable, serializedElement);
        }
      }
    }
    return this;
  };

  this.clear = function () {
    if (currentValue.size > 0) {
      delta.cleared = true;
      delta.added.clear();
      delta.removed.clear();
      currentValue.clear();
    }
    return this;
  };

  this.getAndResetDelta = function () {
    if (delta.cleared || delta.added.size > 0 || delta.removed.size > 0) {
      const crdtDelta = {
        orset: {
          cleared: delta.cleared,
          removed: Array.from(delta.removed.values()),
          added: Array.from(delta.added.values())
        }
      };
      delta.cleared = false;
      delta.added.clear();
      delta.removed.clear();
      return crdtDelta;
    } else {
      return null;
    }
  };

  this.applyDelta = function (delta, anySupport) {
    if (!delta.orset) {
      throw new Error(util.format("Cannot apply delta %o to ORSet", delta));
    }
    if (delta.orset.cleared) {
      currentValue.clear();
    }
    if (delta.orset.removed !== undefined) {
      delta.orset.removed.forEach(element => {
        const value = anySupport.deserialize(element);
        const comparable = AnySupport.toComparable(value);
        if (currentValue.has(comparable)) {
          currentValue.delete(comparable);
        } else {
          debug("Delta instructed to delete element [%o], but it wasn't in the ORSet.", comparable)
        }
      });
    }
    if (delta.orset.added !== undefined) {
      delta.orset.added.forEach(element => {
        const value = anySupport.deserialize(element);
        const comparable = AnySupport.toComparable(value);
        if (currentValue.has(comparable)) {
          debug("Delta instructed to add value [%o], but it's already present in the ORSet", comparable);
        } else {
          currentValue.set(comparable, value);
        }
      });
    }
  };

  this.getStateAndResetDelta = function () {
    delta.cleared = false;
    delta.removed.clear();
    delta.added.clear();
    const items = [];
    currentValue.forEach((value, key) => {
      items.push(AnySupport.serialize(value, true, true));
    });
    return {
      orset: {
        items: items
      }
    };
  };

  this.applyState = function (state, anySupport) {
    if (!state.orset) {
      throw new Error(util.format("Cannot apply state %o to ORSet", state));
    }
    currentValue.clear();
    if (state.orset.items !== undefined) {
      state.orset.items.forEach(element => {
        const value = anySupport.deserialize(element);
        const comparable = AnySupport.toComparable(value);
        currentValue.set(comparable, value);
      });
    }
  };

  this.toString = function () {
    return "ORSet(" + Array.from(currentValue).join(",") + ")";
  };
}

module.exports = ORSet;