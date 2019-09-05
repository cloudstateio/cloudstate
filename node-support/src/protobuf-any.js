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

const path = require("path");
const util = require("util");
const protobufHelper = require("./protobuf-helper");
const protobuf = require("protobufjs");
const Long = require("long");
const stableJsonStringify = require("json-stable-stringify");

const Any = protobufHelper.moduleRoot.google.protobuf.Any;

// To allow primitive types to be stored, CloudState defines a number of primitive type URLs, based on protobuf types.
// The serialized values are valid protobuf messages that contain a value of that type as their single field at index
// 15.
const CloudStatePrimitive = "p.cloudstate.io/";
// Chosen because it reduces the likelihood of clashing with something else.
const CloudStatePrimitiveFieldNumber = 1;
const CloudStatePrimitiveFieldNumberEncoded = CloudStatePrimitiveFieldNumber << 3; // 8
const CloudStateSupportedPrimitiveTypes = new Set();
["string", "bytes", "int64", "bool", "double"].forEach(CloudStateSupportedPrimitiveTypes.add.bind(CloudStateSupportedPrimitiveTypes));

const CloudStateJson = "json.cloudstate.io/";


/**
 * This is any type that has been returned by the protobufjs Message.create method.
 *
 * It should have a encode() method on it.
 *
 * @typedef module:cloudstate.SerializableProtobufMessage
 * @type {Object}
 */

/**
 * Any type that has a type property on it can be serialized as JSON, with the value of the type property describing
 * the type of the value.
 *
 * @typedef module:cloudstate.TypedJson
 * @type {Object}
 * @property {string} type The type of the object.
 */

/**
 * A type that is serializable.
 *
 * @typedef module:cloudstate.Serializable
 * @type {module:cloudstate.SerializableProtobufMessage|module:cloudstate.TypedJson|Object|string|number|boolean|Long|Buffer}
 */


module.exports = class AnySupport {
  constructor(root) {
    this.root = root;
  }

  static fullNameOf(descriptor) {
    function namespace(desc) {
      if (desc.name === "") {
        return "";
      } else {
        return namespace(desc.parent) + desc.name + ".";
      }
    }
    return namespace(descriptor.parent) + descriptor.name;
  }

  static stripHostName(url) {
    const idx = url.indexOf("/");
    if (url.indexOf("/") >= 0) {
      return url.substr(idx + 1);
    } else {
      // fail?
      return url;
    }
  }

  static serializePrimitiveValue(obj, type) {
    const writer = new protobuf.Writer();
    // First write the field key.
    // Field index is always 15, which gets shifted left by 3 bits (ie, 120).
    writer.uint32((CloudStatePrimitiveFieldNumberEncoded | protobuf.types.basic[type]) >>> 0);
    // Now write the primitive
    writer[type](obj);
    return writer.finish();
  }

  static serializePrimitive(obj, type) {
    return Any.create({
      // I have *no* idea why it's type_url and not typeUrl, but it is.
      type_url: CloudStatePrimitive + type,
      value: this.serializePrimitiveValue(obj, type)
    });
  }

  /**
   * Create a comparable version of obj for use in sets and maps.
   *
   * The returned value guarantees === equality (both positive and negative) for the following types:
   *
   * - strings
   * - numbers
   * - booleans
   * - Buffers
   * - Longs
   * - any protobufjs types
   * - objects (based on stable JSON serialization)
   * @private
   */
  static toComparable(obj) {
    // When outputting strings, we prefix with a letter for the type, to guarantee uniqueness of different types.
    if (typeof obj === "string") {
      return "s" + obj;
    } else if (typeof obj === "number") {
      return obj;
    } else if (Buffer.isBuffer(obj)) {
      return "b" + obj.toString("base64");
    } else if (typeof obj === "boolean") {
      return obj;
    } else if (Long.isLong(obj)) {
      return "l" + obj.toString();
    } else if (obj.constructor && typeof obj.constructor.encode === "function" && obj.constructor.$type) {
      return "p" + obj.constructor.encode(obj).finish().toString("base64");
    } else if (typeof obj === "object") {
      return "j" + stableJsonStringify(obj);
    } else {
      throw new Error(util.format("Object %o is not a protobuf object, object or supported primitive type, and " +
        "hence can't be dynamically serialized.", obj));
    }
  }

  /**
   * Serialize a protobuf object to a google.protobuf.Any.
   *
   * @param obj The object to serialize. It must be a protobufjs created object.
   * @param allowPrimitives Whether primitives should be allowed to be serialized.
   * @param fallbackToJson Whether serialization should fallback to JSON if the object
   *        is not a protobuf, but defines a type property.
   * @param requireJsonType If fallbackToJson is true, then if this is true, a property
   *        called type is required.
   * @private
   */
  static serialize(obj, allowPrimitives, fallbackToJson, requireJsonType = false) {
    if (allowPrimitives) {
      if (typeof obj === "string") {
        return this.serializePrimitive(obj, "string");
      } else if (typeof obj === "number") {
        return this.serializePrimitive(obj, "double");
      } else if (Buffer.isBuffer(obj)) {
        return this.serializePrimitive(obj, "bytes");
      } else if (typeof obj === "boolean") {
        return this.serializePrimitive(obj, "bool");
      } else if (Long.isLong(obj)) {
        return this.serializePrimitive(obj, "int64");
      }
    }
    if (obj.constructor && typeof obj.constructor.encode === "function" && obj.constructor.$type) {
      return Any.create({
        // I have *no* idea why it's type_url and not typeUrl, but it is.
        type_url: "type.googleapis.com/" + AnySupport.fullNameOf(obj.constructor.$type),
        value: obj.constructor.encode(obj).finish()
      });
    } else if (fallbackToJson && typeof obj === "object") {
      let type = obj.type;
      if (type === undefined) {
        if (requireJsonType) {
          throw new Error(util.format("Fallback to JSON serialization supported, but object does not define a type property: %o", obj));
        } else {
          type = "object";
        }
      }
      return Any.create({
        type_url: CloudStateJson + type,
        value: this.serializePrimitiveValue(stableJsonStringify(obj), "string")
      });
    } else {
      throw new Error(util.format("Object %o is not a protobuf object, and hence can't be dynamically " +
        "serialized. Try passing the object to the protobuf classes create function.", obj));
    }
  }

  /**
   * Deserialize an any using the given protobufjs root object.
   *
   * @param any The any.
   * @private
   */
  deserialize(any) {
    const url = any.type_url;
    const idx = url.indexOf("/");
    let hostName = "";
    let type = url;
    if (url.indexOf("/") >= 0) {
      hostName = url.substr(0, idx + 1);
      type = url.substr(idx + 1);
    }

    let bytes = any.value;
    if (typeof bytes === "undefined") {
      bytes = new Buffer(0);
    }

    if (hostName === CloudStatePrimitive) {
      return AnySupport.deserializePrimitive(bytes, type);
    }

    if (hostName === CloudStateJson) {
      const json = AnySupport.deserializePrimitive(bytes, "string");
      return JSON.parse(json);
    }

    const desc = this.root.lookupType(type);
    return desc.decode(bytes);
  }

  static deserializePrimitive(bytes, type) {
    if (!CloudStateSupportedPrimitiveTypes.has(type)) {
      throw new Error("Unsupported CloudState primitive Any type: " + type);
    }
    const reader = new protobuf.Reader(bytes);
    let fieldNumber = 0;
    let pType = 0;

    while (reader.pos < reader.len) {
      const key = reader.uint32();
      pType = key & 7;
      fieldNumber = key >>> 3;
      if (fieldNumber !== CloudStatePrimitiveFieldNumber) {
        reader.skipType(pType);
      } else {
        if (pType !== protobuf.types.basic[type]) {
          throw new Error("Unexpected protobuf type " + pType + ", was expecting " + protobuf.types.basic[type] + " for decoding a " + type);
        }
        return reader[type]();
      }
    }

    // We didn't find the field, just return the default.
    return protobuf.types.defaults[type];
  }
};
