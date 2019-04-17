

const path = require("path");
const grpc = require("grpc");
const protoLoader = require("@grpc/proto-loader");
const protobuf = require("protobufjs");
const AnySupport = require("./protobuf-any");
const DescriptorSupport = require("./protobuf-descriptor");

const includeDirs = [
  path.join(__dirname, "..", "proto"),
  path.join(__dirname, "..", "proto-ext")
];
const packageDefinition = protoLoader.loadSync("protocol.proto", {
  includeDirs: includeDirs
});
const grpcDescriptor = grpc.loadPackageDefinition(packageDefinition);

const protocol = new protobuf.Root();
// Copied from @grpc/proto-loader
protocol.resolvePath = function (origin, target) {
  for (let i = 0; i < includeDirs.length; i++) {
    const directory = includeDirs[i];
    const fullPath = path.join(directory, target);
    try {
      fs.accessSync(fullPath, fs.constants.R_OK);
      return fullPath;
    } catch (err) {
    }
  }
  return null;
};
protocol.loadSync("protocol.proto");
protocol.resolveAll();

function setup(entity) {

  const anySupport = new AnySupport(entity.root);

  // Get the service
  const server = new grpc.Server();
  server.addService(grpcDescriptor.com.lightbend.statefulserverless.grpc.Entity.service, {
    ready: ready,
    handle: handle
  });

  function ready(call, callback) {
    callback(null, {
      proto: DescriptorSupport.serviceToDescriptor(entity.root, entity.serviceName),
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


    function updateState(stateObj) {
      stateDescriptor = stateObj.constructor;
      anyState = anySupport.serialize(stateObj);
    }

    function withBehaviorAndState(callback) {
      if (stateDescriptor == null) {
        updateState(entity.initial(entityId));
      }
      const stateObj = stateDescriptor.decode(anyState.value);
      const behavior = entity.behavior(stateObj);
      callback(behavior, stateObj);
    }


    function handleEvent(event) {
      const deserEvent = anySupport.deserialize(event);
      withBehaviorAndState((behavior, state) => {
        const fqName = AnySupport.stripHostName(event.type_url);
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
          stateDescriptor = anySupport.lookupDescriptor(any);
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

                  const serEvent = anySupport.serialize(event);
                  events.push(serEvent);
                }
              });
              active = false;

              const anyReply = anySupport.serialize(grpcMethod.resolvedResponseType.create(reply));

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

  constructor(desc, serviceName) {

    // Protobuf may be one of the following:
    //  * A protobufjs root object
    //  * A a single protobuf file
    //  * Multiple protobuf files
    let root;
    if (Array.isArray(desc) || typeof desc === "string") {
      root = protobuf.loadSync(desc).resolveAll();
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
    console.log("Entity gRPC server started on " + options.bindAddress + ":" + boundPort);

    return boundPort;
  }

  shutdown() {
    this.server.forceShutdown();
  }
};