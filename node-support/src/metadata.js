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

const CloudEvents = require("./cloudevents");

function valueFromEntry(entry) {
  if (entry.bytesValue !== undefined) {
    return entry.bytesValue;
  } else {
    return entry.stringValue;
  }
}

/**
 * A metadata value. Can either be a string or a buffer.
 *
 * @typedef module:cloudstate.MetadataValue
 * @type {string|Buffer}
 */

/**
 * @classdesc Cloudstate metadata.
 *
 * Metadata is treated as case insensitive on lookup, and case sensitive on set. Multiple values per key are supported,
 * setting a value will add it to the current values for that key. You should delete first if you wish to replace a
 * value.
 *
 * Values can either be strings or byte buffers. If a non string or byte buffer value is set, it will be converted to
 * a string using toString.
 *
 * @interface module:cloudstate.Metadata
 * @param {array} entries The list of entries
 */
function Metadata(entries) {
  if (entries) {
    this.entries = entries;
  } else {
    this.entries = [];
  }

  /**
   * The metadata expressed as an object.
   *
   * The object keys are case insensitive, ie, `metadata.foo` and `metadata.Foo` both return the same value. If there
   * are multiple values for a given key, the first one set for that key will be returned. Setting a value will add it
   * to the list of existing values for that key.
   *
   * @name module:cloudstate.Metadata#getMap
   * @type {Object<String, module:cloudstate.MetadataValue>}
   */
  this.getMap = new Proxy({}, {
    get: (target, key) => {
      for (const idx in entries) {
        const entry = entries[idx];
        if (key.toLowerCase() === entry.key.toLowerCase()) {
          return valueFromEntry(entry);
        }
      }
    },
    set: (target, key, value) => {
      this.set(key, value)
    },
    deleteProperty: (target, key) => this.delete(key),
    ownKeys: (target) => {
      const keys = [];
      entries.forEach(entry => {
        keys.push(entry.key);
      });
      return keys;
    },
    has: (target, key) => this.has(key),
    defineProperty: () => {
      throw new Error("Metadata.getMap does not support defining properties");
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

  /**
   * Get all the values for the given key.
   *
   * The key is case insensitive.
   *
   * @function module:cloudstate.Metadata#get
   * @param {string} key The key to get.
   * @returns {Array<module:cloudstate.MetadataValue>} All the values, or an empty array if no values exist for the key.
   */
  this.get = key => {
    const values = [];
    entries.forEach(entry => {
      if (key.toLowerCase() === entry.key.toLowerCase()) {
        values.push(valueFromEntry(entry));
      }
    });
    return values;
  };

  /**
   * Set a given key value.
   *
   * This will append the key value to the metadata, it won't replace any existing values for existing keys.
   *
   * @function module:cloudstate.Metadata#set
   * @param {string} key The key to set.
   * @param {module:cloudstate.MetadataValue} value The value to set.
   */
  this.set = (key, value) => {
    const entry = {key};
    if (typeof value === "string") {
      entry.stringValue = value;
    } else if (Buffer.isBuffer(value)) {
      entry.bytesValue = value;
    } else {
      entry.stringValue = value.toString();
    }
    entries.push(entry);
  };

  /**
   * Delete all values with the given key.
   *
   * The key is case insensitive.
   *
   * @function module:cloudstate.Metadata#delete
   * @param {string} key The key to delete.
   */
  this.delete = key => {
    let idx = 0;
    while (idx < entries.length) {
      const entry = entries[idx];
      if (key.toLowerCase() !== entry.key.toLowerCase()) {
        idx++;
      } else {
        entries.splice(idx, 1);
      }
    }
  };

  /**
   * Whether there exists a metadata value for the given key.
   *
   * The key is case insensitive.
   *
   * @function module:cloudstate.Metadata#has
   * @param {string} key The key to check.
   */
  this.has = key => {
    for (const idx in entries) {
      const entry = entries[idx];
      if (key.toLowerCase() === entry.key.toLowerCase()) {
        return true;
      }
    }
  };

  /**
   * Clear the metadata.
   *
   * @function module:cloudstate.Metadata#clear
   */
  this.clear = () => {
    entries.splice(0, entries.length);
  };

  /**
   * The metadata, expressed as a CloudEvent.
   *
   * @name module:cloudstate.Metadata#cloudevent
   * @type {module:cloudstate.CloudEvent}
   */
  this.cloudevent = CloudEvents.toCloudevent(this.getMap);
}

module.exports = Metadata;
