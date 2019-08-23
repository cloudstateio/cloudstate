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

const should = require("chai").should();
const protobuf = require("protobufjs");
const path = require("path");
const AnySupport = require("../src/protobuf-any");
const Long = require("long");

const root = new protobuf.Root();
root.loadSync(path.join(__dirname, "example.proto"));

const anySupport = new AnySupport(root);
const Example = root.lookupType("com.example.Example");
const PrimitiveLike = root.lookupType("com.example.PrimitiveLike");

describe("AnySupport", () => {

  it("should support serializing strings", () => {
    const serialized = AnySupport.serialize("foo", true, false);
    serialized.type_url.should.equal("p.cloudstate.io/string");
    anySupport.deserialize(serialized).should.equal("foo");
  });

  it("should support serializing long", () => {
    const value = Long.fromNumber(2789);
    const serialized = AnySupport.serialize(value, true, false);
    serialized.type_url.should.equal("p.cloudstate.io/int64");
    anySupport.deserialize(serialized).should.eql(value);
  });

  it("should support serializing bytes", () => {
    const bytes = new Buffer("foo");
    const serialized = AnySupport.serialize(bytes, true, false);
    serialized.type_url.should.equal("p.cloudstate.io/bytes");
    anySupport.deserialize(serialized).should.eql(bytes);
  });

  it("should support serializing booleans", () => {
    const serialized = AnySupport.serialize(true, true, false);
    serialized.type_url.should.equal("p.cloudstate.io/bool");
    anySupport.deserialize(serialized).should.equal(true);
  });

  it("should support serializing numbers", () => {
    const serialized = AnySupport.serialize(1.2345, true, false);
    serialized.type_url.should.equal("p.cloudstate.io/double");
    anySupport.deserialize(serialized).should.equal(1.2345);
  });

  it("should support serializing valid protobufs", () => {
    const obj = {
      field1: "foo",
      field2: "bar"
    };
    const serialized = AnySupport.serialize(Example.create(obj), false, false);
    serialized.type_url.should.equal("type.googleapis.com/com.example.Example");
    const deserialized = anySupport.deserialize(serialized);
    deserialized.should.include(obj);
  });

  it("should not support serializing primitives when false", () => {
    (() => AnySupport.serialize("foo", false, false)).should.throw();
  });

  it("should not support serializing primitives when false and fallback to json is true", () => {
    (() => AnySupport.serialize("foo", false, true)).should.throw();
  });

  it("should not support serializing ordinary objects when primitives false", () => {
    (() => AnySupport.serialize({field1: "foo"}, false, false)).should.throw();
  });

  it("should not support serializing ordinary objects when primitives true and fallback to JSON is not", () => {
    (() => AnySupport.serialize({field1: "foo"}, true, false)).should.throw();
  });

  it("should support deserializing primitives when the field in not present", () => {
    anySupport.deserialize({
      type_url: "p.cloudstate.io/string",
      value: PrimitiveLike.encode({}).finish()
    }).should.equal("");
  });

  it("should support deserializing primitives when other fields are present", () => {
    anySupport.deserialize({
      type_url: "p.cloudstate.io/string",
      value: PrimitiveLike.encode({
        field1: "one",
        field2: "two",
        field3: "three"
      }).finish()
    }).should.equal("one");
  });

  it("should support serialization json when allowed", () => {
    const obj = {
      type: "MyType",
      field1: "foo",
      field2: "bar"
    };
    const serialized = AnySupport.serialize(obj, false, true);
    serialized.type_url.should.equal("json.cloudstate.io/MyType");
    anySupport.deserialize(serialized).should.include(obj);
  });

  it("should support serialization json with no type property", () => {
    const obj = {
      field1: "foo",
      field2: "bar"
    };
    const serialized = AnySupport.serialize(obj, false, true);
    serialized.type_url.should.equal("json.cloudstate.io/object");
    anySupport.deserialize(serialized).should.include(obj);
  });

  it("should not support serializing to JSON when not configured", () => {
    (() => AnySupport.serialize({type: "MyType", field1: "foo"}, false, false)).should.throw();
  });

  it("should fail to serialize to JSON when no type property is present but is required", () => {
    (() => AnySupport.serialize({field1: "foo"}, false, true, true)).should.throw();
  });

});
