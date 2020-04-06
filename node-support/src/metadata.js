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

function Metadata(entries) {
  this.getMap = new Proxy({}, {
    get: (target, key) => {
      for (const idx in entries) {
        const entry = entries[idx];
        if (key.toLowerCase() === entry.key.toLowerCase()) {
          return entry.value;
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

  this.get = key => {
    const values = [];
    entries.forEach(entry => {
      if (key.toLowerCase() === entry.key.toLowerCase()) {
        values.push(entry.value);
      }
    });
    return values;
  };


  this.set = (key, value) => {
    entries.push({key, value})
  };

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

  this.has = key => {
    for (const idx in entries) {
      const entry = entries[idx];
      if (key.toLowerCase() === entry.key.toLowerCase()) {
        return true;
      }
    }
  };

  this.clear = () => {
    entries.splice(0, entries.length);
  };
}

module.exports = Metadata;