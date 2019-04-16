const protocolPath = "protocol.proto";
const grpc = require("grpc");
const protoLoader = require("@grpc/proto-loader");

const protobuf = require("protobufjs");

const packageDefinition = protoLoader.loadSync(protocolPath, {});
const descriptor = grpc.loadPackageDefinition(packageDefinition);
const protocol = protobuf.loadSync(protocolPath);

const Any = protocol.lookupType("google.protobuf.Any");

// Support for doing this exists here:
// https://github.com/protobufjs/protobuf.js/tree/master/ext/descriptor
// However that has been found to be quite buggy, so we're doing it manually.
function serviceToDescriptor(root, serviceName) {
  const service = root.lookupService(serviceName);
  const pkg = fullNameOf(service.parent);
  const typeNames = new Set(Object.keys(service.methods).map(key => {
    const requestType = service.methods[key].requestType;
    if (requestType.includes(".")) {
      return requestType;
    } else if (pkg) {
      return pkg + "." + requestType;
    } else {
      return requestType;
    }
  }));

  const types = Array.from(typeNames).map(name => {
    const type = root.lookupType(name);

    // Only include the entity_key fields, that's all that's needed
    const fieldKeys = Object.keys(type.fields).filter(key => {
      return type.fields[key].options && type.fields[key].options.hasOwnProperty("entity_key");
    });

    function toLabel(rule) {
      switch (rule) {
        case "repeated": return 3;
        case "required": return 2;
        default: return 1;
      }
    }

    function toType(type) {
      switch (type) {
        case "double": return 1;
        case "float": return 2;
        case "int64": return 3;
        case "uint64": return 4;
        case "int32": return 5;
        case "fixed64": return 6;
        case "fixed32": return 7;
        case "bool": return 8;
        case "string": return 9;
        case "bytes": return 12;
        case "uint32": return 13;
        case "sfixed32": return 15;
        case "sfixed64": return 16;
        case "sint32": return 17;
        case "sint64": return 18;
      }
    }

    // DescriptorProto
    return {
      name: type.name,
      field: fieldKeys.map(key => {
        const field = type.fields[key];

        // FieldDescriptorProto
        return {
          name: field.name,
          number: field.id,
          label: toLabel(field.rule),
          type: toType(field.type),
          defaultValue: field.defaultValue,
          options: {
            ".com.lightbend.statefulserverless.grpc.entityKey": true
          }
        }
      })
    };

  });

  const fdpDesc = protocol.lookupType("google.protobuf.FileDescriptorProto");

  // FileDescriptorProto
  const fdp = {
    name: service.filename,
    package: pkg,
    // ServiceDescriptorProto
    service: [{
      name: service.name,
      method: Object.keys(service.methods).map(key => {
        const method = service.methods[key];
        // MethodDescriptorProto
        return {
          name: method.name,
          inputType: method.requestType,
          outputType: "google.protobuf.Empty",
          clientStreaming: method.requestStream,
          serverStreaming: method.responseStream
        };
      })
    }],
    messageType: types
  };

  const error = fdpDesc.verify(fdp);
  if (error) {
    throw new Error(error);
  }

  return fdp;
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

function setup(entity) {

  // Get the service
  const server = new grpc.Server();
  server.addService(descriptor.com.lightbend.statefulserverless.grpc.Entity.service, {
    ready: ready,
    handle: handle
  });

  function ready(call, callback) {
    callback(null, {
      proto: serviceToDescriptor(entity.root, entity.serviceName),
      serviceName: entity.serviceName
    });
  }

  function handle(call) {

    let entityId;

    // The current entity state, serialized to an Any
    let anyState;

    // The current state descriptor
    let stateDescriptor;

    // The current sequence number
    let sequence = 0;

    function serializeAny(any) {
      // todo validate that its a protobuf object
      const msg = any.constructor;
      return Any.create({
        type_url: "type.googleapis.com/" + fullNameOf(msg.$type),
        value: msg.encode(any).finish()
      });
    }

    function updateState(stateObj) {
      stateDescriptor = stateObj.constructor;
      anyState = serializeAny(stateObj);
    }

    function withBehaviorAndState(callback) {
      if (stateDescriptor == null) {
        updateState(entity.initial(entityId));
      }
      const stateObj = stateDescriptor.decode(anyState.value);
      const behavior = entity.behavior(stateObj);
      callback(behavior, stateObj);
    }

    function lookupDescriptorForAny(any) {
      return entity.root.lookupType(stripHostName(any.type_url));
    }

    function deserializeAny(any) {
      const desc = lookupDescriptorForAny(any);
      let bytes = any.value;
      if (!bytes) {
        bytes = new Buffer(0);
      }
      return desc.decode(bytes);
    }

    function handleEvent(event) {
      const deserEvent = deserializeAny(event);
      withBehaviorAndState((behavior, state) => {
        const fqName = stripHostName(event.type_url);
        let handler;
        if (behavior.eventHandlers.hasOwnProperty(fqName)) {
          handler = behavior.eventHandlers[fqName];
        } else {
          const idx = fqName.lastIndexOf(".");
          let name;
          if (idx >= 0) {
            name = fqName.substring(idx + 1);
          } else {
            name = fqName;
          }
          if (behavior.eventHandlers.hasOwnProperty(name)) {
            handler = behavior.eventHandlers[name];
          } else {
            // todo error
          }
        }
        const newState = handler(deserEvent, state);
        updateState(newState);

      });
    }

    call.on("data", function (entityStreamIn) {

      if (entityStreamIn.init) {
        // todo validate that we have not already received init
        // validate init fields
        entityId = entityStreamIn.init.entityId;
        if (entityStreamIn.init.snapshot) {
          const snapshot = entityStreamIn.init.snapshot;
          // todo handle error if type not found
          stateDescriptor = lookupDescriptorForAny(any);
          anyState = snapshot.snapshot;
          sequence = snapshot.snapshotSequence;
        }
      } else if (entityStreamIn.event) {

        // todo validate
        const event = entityStreamIn.event;
        sequence = event.sequence;
        handleEvent(event.payload);

      } else if (entityStreamIn.command) {

        // todo validate
        const command = entityStreamIn.command;

        if (!entity.service.methods.hasOwnProperty(command.name)) {
          // todo error
        } else {

          const grpcMethod = entity.service.methods[command.name];

          // todo maybe reconcile whether the command URL of the Any type matches the gRPC response type
          const deserCommand = grpcMethod.resolvedRequestType.decode(command.payload.value);

          withBehaviorAndState((behavior, state) => {

            if (behavior.commandHandlers.hasOwnProperty(command.name)) {

              const events = [];
              let active = true;

              const reply = behavior.commandHandlers[command.name](deserCommand, state, {
                emit: (event) => {

                  if (!active) {
                    throw new Error("Command context no longer active!");
                  }

                  const serEvent = serializeAny(event);
                  events.push(serEvent);
                }
              });
              active = false;

              const serReply = grpcMethod.resolvedResponseType.encode(reply).finish();
              const anyReply = {
                type_url: "type.googleapis.com/" + fullNameOf(grpcMethod.resolvedResponseType),
                value: serReply
              };

              // Invoke event handlers first
              events.forEach(handleEvent);
              sequence += events.length;

              // todo snapshot handling
              call.write({
                reply: {
                  commandId: command.id,
                  payload: anyReply,
                  events: events
                }
              });

            } else {
              // todo error
            }

          });
        }
      }

    });

    call.on("end", function () {
      call.end();
    });
  }

  return server;
}


module.exports = class Entity {

  constructor(desc, serviceName, protobufLoadOptions) {

    // Protobuf may be one of the following:
    //  * A protobufjs root object
    //  * A a single protobuf file
    //  * Multiple protobuf files
    let root;
    if (Array.isArray(desc) || typeof desc === "string") {
      root = protobuf.loadSync(desc, protobufLoadOptions).resolveAll();
    } else if (typeof desc === "object") {
      root = desc.resolveAll();
    } else {
      throw new Error("Unknown descriptor type: " + typeof desc)
    }

    this.root = root;
    this.serviceName = serviceName;
    // Eagerly lookup the service to fail early
    this.service = root.lookupService(serviceName);
  }

  setInitial(callback) {
    this.initial = callback;
  }

  setBehavior(callback) {
    this.behavior = callback;
  }

  start(options) {
    options = {
      ...{
        bindAddress: "0.0.0.0",
        bindPort: 0
      },
      ...options
    };

    this.server = setup(this);
    const boundPort = this.server.bind(options.bindAddress + ":" + options.bindPort, grpc.ServerCredentials.createInsecure());
    this.server.start();
    return boundPort;
  }

  shutdown() {
    this.server.forceShutdown();
  }
};