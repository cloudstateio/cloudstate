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

function mapIterator(iter, f) {
  const mapped = {
    [Symbol.iterator]: () => mapped,
    next: () => {
      const next = iter.next();
      if (next.done) {
        return {
          done: true
        };
      } else {
        return {
          value: f(next.value),
          done: false
        };
      }
    }
  };
  return mapped;
}

function ORMap() {
  // Map of a comparable form (that compares correctly using ===) to an object that holds the
  // actual key and the value.
  let currentValue = new Map();
  let delta = {
    added: new Map(),
    removed: new Map(),
    cleared: false
  };

  /**
   * Generator for default values.
   *
   * This is invoked by get when the current map has no CRDT defined for the key.
   *
   * If this returns a CRDT, it will be added to the map.
   *
   * Care should be taken when using this, since it means that the get method can trigger elements to be created.
   * If using default values, the get method should not be used in queries where an empty value for the CRDT means
   * the value is not present.
   *
   * @param key The key the default value is being generated for.
   * @returns The default value, or undefined if no default value should be returned.
   */
  this.defaultValue = (key) => undefined;

  this.has = function(key) {
    return currentValue.has(AnySupport.toComparable(key));
  };

  Object.defineProperty(this, "size", {
    get: function () {
      return currentValue.size;
    }
  });

  this.forEach = function(callback) {
    return currentValue.forEach((value, key) => callback(value.value, value.key, this));
  };

  this.entries = function() {
    // For some reason, these arrays are key, value, even though callbacks are passed value, key
    return mapIterator(currentValue.values(), value => [value.key, value.value]);
  };

  this[Symbol.iterator] = function() {
    return entries();
  };

  this.values = function() {
    return mapIterator(currentValue.values(), value => value.value);
  };

  this.keys = function() {
    return mapIterator(currentValue.values(), value => value.key);
  };

  this.get = (key) => {
    const value = currentValue.get(AnySupport.toComparable(key));
    if (value !== undefined) {
      return value.value;
    } else {
      const maybeDefault = this.defaultValue(key);
      if (maybeDefault !== undefined) {
        this.set(key, maybeDefault)
      }
      return maybeDefault;
    }
  };

  /**
   * Special representation that works with string keys
   */
  const asObject = new Proxy({}, {
    get: (target, key) => this.get(key),
    set: (target, key, value) => this.set(key, value),
    deleteProperty: (target, key) => this.delete(key),
    ownKeys: (target) => {
      const keys = [];
      this.forEach((value, key) => {
        if (typeof key === "string") {
          keys.push(key);
        }
      });
      return keys;
    },
    has: (target, key) => this.has(key),
    defineProperty: () => {
      throw new Error("ORMap.asObject does not support defining properties");
    },
    getOwnPropertyDescriptor: (target, key) => {
      const value = this.get(key);
      return value ? {
        value: value,
        writable: true,
        enumerable: true,
        configurable: true
      } : undefined;
    }
  });
  Object.defineProperty(this, "asObject", {
    get: () => asObject
  });

  this.set = function(key, value) {
    if (!value.hasOwnProperty("getStateAndResetDelta")) {
      throw new Error(util.format("Cannot add %o with value %o to ORMap, only CRDTs may be added as values.", key, value))
    }
    const comparable = AnySupport.toComparable(key);
    const serializedKey = AnySupport.serialize(key, true, true);
    if (!currentValue.has(comparable)) {
      if (delta.removed.has(comparable)) {
        debug("Removing then adding key [%o] in the same operation can have unintended effects, as the old value may end up being merged with the new.", key);
      }
    } else if (!delta.added.has(comparable)) {
      debug("Setting an existing key [%o] to a new value can have unintended effects, as the old value may end up being merged with the new.", key);
      delta.removed.set(comparable, serializedKey);
    }
    // We'll get the actual state later
    delta.added.set(comparable, serializedKey);
    currentValue.set(comparable, {
      key: key,
      value: value
    });
    return this;
  };

  this.delete = function(element) {
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

  this.clear = function() {
    if (currentValue.size > 0) {
      delta.cleared = true;
      delta.added.clear();
      delta.removed.clear();
      currentValue.clear();
    }
    return this;
  };

  this.getAndResetDelta = function() {
    const updateDeltas = [];
    const addedStates = [];
    currentValue.forEach((value, key) => {
      if (delta.added.has(key)) {
        addedStates.push({
          key: delta.added.get(key),
          value: value.value.getStateAndResetDelta()
        });
      } else {
        const entryDelta = value.value.getAndResetDelta();
        if (entryDelta !== null) {
          updateDeltas.push({
            key: AnySupport.serialize(value.key, true, true),
            delta: entryDelta
          });
        }
      }
    });

    if (delta.cleared || delta.removed.size > 0 || updateDeltas.length > 0 || addedStates.length > 0) {
      const crdtDelta = {
        ormap: {
          cleared: delta.cleared,
          removed: Array.from(delta.removed.values()),
          added: addedStates,
          updated: updateDeltas
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

  this.applyDelta = function(delta, anySupport, createCrdtForState) {
    if (delta.ormap === undefined) {
      throw new Error(util.format("Cannot apply delta %o to ORMap", delta));
    }
    if (delta.ormap.cleared) {
      currentValue.clear();
    }
    if (delta.ormap.removed !== undefined) {
      delta.ormap.removed.forEach(key => {
        const deserializedKey = anySupport.deserialize(key);
        const comparable = AnySupport.toComparable(deserializedKey);
        if (currentValue.has(comparable)) {
          currentValue.delete(comparable);
        } else {
          debug("Delta instructed to delete key [%o], but it wasn't in the ORMap.", deserializedKey)
        }
      });
    }
    if (delta.ormap.added !== undefined) {
      delta.ormap.added.forEach(entry => {
        const state = createCrdtForState(entry.value);
        state.applyState(entry.value);
        const key = anySupport.deserialize(entry.key);
        const comparable = AnySupport.toComparable(key);
        if (currentValue.has(comparable)) {
          debug("Delta instructed to add key [%o], but it's already present in the ORMap.", key);
        } else {
          currentValue.set(comparable, {
            key: key,
            value: state
          });
        }
      });
    }
    if (delta.ormap.updated !== undefined) {
      delta.ormap.updated.forEach(entry => {
        const key = anySupport.deserialize(entry.key);
        const comparable = AnySupport.toComparable(key);
        if (currentValue.has(comparable)) {
          currentValue.get(comparable).value.applyDelta(entry.delta, anySupport, createCrdtForState);
        } else {
          debug("Delta instructed to update key [%o], but it's not present in the ORMap.", key);
        }
      });
    }
  };

  this.getStateAndResetDelta = function() {
    delta.cleared = false;
    delta.removed.clear();
    delta.added.clear();
    const entries = [];
    currentValue.forEach((value) => {
      const key = AnySupport.serialize(value.key, true, true);
      entries.push({
        key: key,
        value: value.value.getStateAndResetDelta()
      });
    });
    return {
      ormap: {
        entries: entries
      }
    };
  };

  this.applyState = function(state, anySupport, createCrdtForState) {
    if (state.ormap === undefined) {
      throw new Error(util.format("Cannot apply state %o to ORMap", state));
    }
    currentValue.clear();
    if (state.ormap.entries !== undefined) {
      state.ormap.entries.forEach(entry => {
        const key = anySupport.deserialize(entry.key);
        const comparable = AnySupport.toComparable(key);
        const state = createCrdtForState(entry.value);
        state.applyState(entry.value);
        currentValue.set(comparable, {
          key: key,
          value: state
        });
      });
    }
  };

  this.toString = function() {
    return "ORMap(" + Array.from(currentValue.values())
      .map(entry => entry.key + " -> " + entry.value.toString())
      .join(",") + ")";
  };
}

module.exports = ORMap;