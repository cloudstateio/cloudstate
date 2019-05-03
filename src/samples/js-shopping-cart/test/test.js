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
const should = require('chai').should();
const grpc = require("grpc");
const protoLoader = require("@grpc/proto-loader");

const protobuf = require("protobufjs");
require("protobufjs/ext/descriptor");

const ssesPath = path.dirname(require.resolve("stateful-serverless-event-sourcing"));
const packageDefinition = protoLoader.loadSync("lightbend/serverless/entity.proto", {
  includeDirs: [path.join(ssesPath, "proto"), path.join(ssesPath, "proto-ext")]
});
const descriptor = grpc.loadPackageDefinition(packageDefinition);

const root = new protobuf.Root();
root.resolvePath = function (origin, target) {
  return path.join("proto", target);
};
root.loadSync("shoppingcart.proto");
root.loadSync("domain.proto");
root.resolveAll();

const ItemAdded = root.lookupType("com.example.shoppingcart.persistence.ItemAdded");
const ItemRemoved = root.lookupType("com.example.shoppingcart.persistence.ItemRemoved");
const Cart = root.lookupType("com.example.shoppingcart.persistence.Cart");

// Start server
const server = require("../shoppingcart.js");

let client;

let commandId = 0;

// Work around https://github.com/dcodeIO/protobuf.js/issues/1196
function filterOneofIndex(obj) {
  Object.keys(obj).forEach(key => {
    const value = obj[key];
    if (key === "oneofIndex") {
      if (value === 0) {
        delete obj[key];
      }
    } else if (typeof value === "object") {
      filterOneofIndex(value);
    } else if (Array.isArray(value)) {
      value.forEach(item => {
        if (typeof item === "object") {
          filterOneofIndex(item)
        }
      })
    }
  });
}

function invokeReady() {
  return new Promise((resolve, reject) => {
    client.ready({}, (err, descriptor) => {
      if (err) {
        reject(err);
      } else {
        filterOneofIndex(descriptor.proto);
        resolve({
          proto: descriptor.proto,
          root: protobuf.Root.fromDescriptor({
            file: [descriptor.proto]
          }).resolveAll(),
          serviceName: descriptor.serviceName
        });
      }
    });
  });
}

function callAndInit(snapshot) {
  const call = client.handle();
  call.write({
    init: {
      entityKey: "123",
      snapshot: snapshot
    }
  });
  return call;
}

function nextMessage(call) {
  let done;
  return new Promise((resolve, reject) => {
    call.on("data", msg => {
      done = true;
      resolve(msg);
    });
    call.on("end", () => {
      if (!done) {
        reject("Stream finished before next data was received");
      }
    });
  });
}

function fullNameOf(descriptor) {
  function namespace(desc) {
    if (desc.name === "") {
      return "";
    } else {
      return namespace(desc.parent) + desc.name + ".";
    }
  }
  return namespace(descriptor.parent) + descriptor.name;
}

function stripHostName(url) {
  const idx = url.indexOf("/");
  if (url.indexOf("/") >= 0) {
    return url.substr(idx + 1);
  } else {
    // fail?
    return url;
  }
}

function decodeAny(root, any) {
  let bytes = any.value;
  if (bytes === undefined) {
    // An empty buffer is falsey and so disappears, we need to make it reappear.
    bytes = new Buffer(0);
  }
  return root.lookupType(stripHostName(any.type_url)).decode(bytes);
}

function sendCommand(call, name, payload) {
  const cid = ++commandId;
  call.write({
    command: {
      id: cid,
      name: name,
      payload: {
        value: payload.constructor.encode(payload).finish(),
        url: "type.googleapis.com/" + fullNameOf(payload.constructor.$type)
      }
    }
  });
  return nextMessage(call).then(msg => {
    should.exist(msg.reply);
    msg.reply.commandId.toNumber().should.equal(cid);
    msg.reply.decodedPayload = decodeAny(root, msg.reply.payload);
    if (msg.reply.events) {
      msg.reply.decodedEvents = msg.reply.events.map(event => {
        return decodeAny(root, event);
      });
    }
    return msg.reply;
  });
}

function sendEvent(call, sequence, event) {
  call.write({
    "event": {
      sequence: sequence,
      payload: {
        type_url: "type.googleapis.com/" + fullNameOf(event.constructor.$type),
        value: event.constructor.encode(event).finish()
      }
    }
  })
}

function getCart(call) {
  return sendCommand(call, "GetCart", root.lookupType("com.example.shoppingcart.GetShoppingCart").create({
    userId: "123"
  }));
}

function addItem(call, item) {
  return sendCommand(call, "AddItem", root.lookupType("com.example.shoppingcart.AddLineItem").create(item));
}

describe("shopping cart", () => {

  before("start shopping cart server", () => {
    const port = server.start({
      bindPort: 0
    });
    client = new descriptor.lightbend.serverless.Entity("127.0.0.1:" + port, grpc.credentials.createInsecure());
  });

  after("shutdown shopping cart server", () => {
    server.shutdown();
  });

  it("should return descriptor when ready", () => {
    return invokeReady().then(spec => {
      spec.serviceName.should.equal("com.example.shoppingcart.ShoppingCart");
      const service = spec.root.lookupService(spec.serviceName);
      service.should.have.property("name", "ShoppingCart");
      const GetShoppingCart = spec.root.lookupType("com.example.shoppingcart.GetShoppingCart");
      GetShoppingCart.fields.should.have.property("userId");
      // protobuf.js doesn't transfer extension options when converting the protobuf descriptor to a protobuf.js root
      // object, so we have to extract the field out of the protobuf descriptor
      const userIdField = spec.proto.messageType.find(mt => mt.name === "GetShoppingCart")
        .field.find(f => f.name === "userId");
      userIdField.options.should.have.property(".lightbend.serverless.entityKey", true);
    });
  });

  it("should respond to commands", () => {
    const call = callAndInit();
    return getCart(call)
      .then(reply => {
        should.not.exist(reply.events);
        should.not.exist(reply.snapshot);
        reply.decodedPayload.items.should.be.empty;
        call.end();
      });
  });

  it("should accept events", () => {
    const call = callAndInit();
    sendEvent(call, 1, ItemAdded.create({
      item: {
        productId: "abc",
        name: "Some product",
        quantity: 10
      }
    }));
    return getCart(call)
      .then(reply => {
        reply.decodedPayload.items[0].should.include({productId: "abc", name: "Some product", quantity: 10});
        call.end();
      });
  });

  it("should emit events", () => {
    const call = callAndInit();
    return addItem(call, {
      userId: "123",
      productId: "abc",
      name: "Some product",
      quantity: 10
    }).then(reply => {
      reply.events.should.have.lengthOf(1);
      reply.events[0].type_url.should.equal("type.googleapis.com/com.example.shoppingcart.persistence.ItemAdded");
      reply.decodedEvents[0].item.should.include({productId: "abc", name: "Some product", quantity: 10});
      return getCart(call);
    }).then(reply => {
      reply.decodedPayload.items[0].should.include({productId: "abc", name: "Some product", quantity: 10});
      call.end();
    });
  });

});
