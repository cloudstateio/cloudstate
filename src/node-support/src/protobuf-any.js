/*
 * Support for working with protobuf Any types.
 */

const path = require("path");
const protobuf = require("protobufjs");
const Root = protobuf.Root;

const Any = protobuf
  .loadSync(path.join(__dirname, "..", "proto-ext", "google", "protobuf", "any.proto"))
  .lookupType("google.protobuf.Any");


module.exports = class AnySupport {
  /**
   * @param {Root} root The root to do all serialization from.
   */
  constructor(root) {
    /**
     * @type {Root}
     */
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

  /**
   * Serialize a protobuf object to a google.protobuf.Any.
   *
   * @param obj The object to serialize. It must be a protobufjs created object.
   */
  serialize(obj) {
    if (!obj.constructor || typeof obj.constructor.encode !== "function" || !obj.constructor.$type) {
      throw new Error("Object " + JSON.stringify(obj) +
        " is not a protobuf object, and hence can't be dynamically serialized. Try passing the object to the " +
        "protobuf classes create function.")
    }
    return Any.create({
      type_url: "type.googleapis.com/" + AnySupport.fullNameOf(obj.constructor.$type),
      value: obj.constructor.encode(obj).finish()
    });
  }

  /**
   * Lookup the descriptor for the given any from the given protobufjs root object.
   *
   * @param any The any.
   */
  lookupDescriptor(any) {
    return this.root.lookupType(AnySupport.stripHostName(any.type_url));
  }

  /**
   * Deserialize an any using the given protobufjs root object.
   *
   * @param any The any.
   */
  deserialize(any) {
    const desc = this.lookupDescriptor(any);
    let bytes = any.value;
    if (typeof bytes === "undefined") {
      bytes = new Buffer(0);
    }
    return desc.decode(bytes);
  }
};
