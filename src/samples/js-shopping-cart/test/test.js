const path = require("path");
const should = require('chai').should();
const grpc = require("grpc");
const protoLoader = require("@grpc/proto-loader");

const protobuf = require("protobufjs");
require("protobufjs/ext/descriptor");

const ssesPath = path.dirname(require.resolve("stateful-serverless-event-sourcing"));
const packageDefinition = protoLoader.loadSync("protocol.proto", {
  includeDirs: [path.join(ssesPath, "proto"), path.join(ssesPath, "proto-ext")]
});
const descriptor = grpc.loadPackageDefinition(packageDefinition);

const shoppingcart = protobuf.loadSync("shoppingcart.proto");
shoppingcart.resolveAll();

const domain = protobuf.loadSync("domain.proto");
domain.resolveAll();
const ItemAdded = domain.lookupType("com.example.shoppingcart.persistence.ItemAdded");
const ItemRemoved = domain.lookupType("com.example.shoppingcart.persistence.ItemRemoved");
const Cart = domain.lookupType("com.example.shoppingcart.persistence.Cart");

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
          root: protobuf.Root.fromDescriptor({
            file: [descriptor.proto]
          }),
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
    msg.reply.decodedPayload = decodeAny(shoppingcart, msg.reply.payload);
    if (msg.reply.events) {
      msg.reply.decodedEvents = msg.reply.events.map(event => {
        return decodeAny(domain, event);
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
  return sendCommand(call, "GetCart", shoppingcart.lookupType("com.example.shoppingcart.GetCart").create({
    userId: "123"
  }));
}

function addItem(call, item) {
  return sendCommand(call, "AddItem", shoppingcart.lookupType("com.example.shoppingcart.AddLineItem").create(item));
}

describe("shopping cart", () => {

  before("start shopping cart server", () => {
    const port = server.start();
    client = new descriptor.com.lightbend.statefulserverless.grpc.Entity("localhost:" + port, grpc.credentials.createInsecure());
  });

  after("shutdown shopping cart server", () => {
    server.shutdown();
  });

  it("should return descriptor when ready", () => {
    return invokeReady().then(spec => {
      spec.serviceName.should.equal("com.example.shoppingcart.ShoppingCart");
      const service = spec.root.lookupService(spec.serviceName);
      service.should.have.property("name", "ShoppingCart");
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