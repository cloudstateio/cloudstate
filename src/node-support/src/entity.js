

const path = require("path");
const debug = require("debug")("stateserve-event-sourcing");
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
    debug("Received ready, sending descriptor with service name '" + entity.serviceName + "' and persistenceId '" + entity.options.persistenceId + "'");
    callback(null, {
      proto: DescriptorSupport.serviceToDescriptor(entity.root, entity.serviceName),
      serviceName: entity.serviceName,
      persistenceId: entity.options.persistenceId
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

    const streamId = Math.random().toString(16).substr(2, 7);

    function streamDebug(msg) {
      if (entityId) {
        debug(streamId + " [" + entityId + "] - " + msg);
      } else {
        debug(streamId + " - " + msg);
      }
    }

    streamDebug("Received new stream");

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

        streamDebug("Received init message");

        if (entityStreamIn.init.snapshot) {
          const snapshot = entityStreamIn.init.snapshot;
          sequence = snapshot.snapshotSequence;
          streamDebug("Handling snapshot with type " + snapshot.snapshot.type_url + " at sequence " + sequence);
          // todo handle error if type not found
          stateDescriptor = anySupport.lookupDescriptor(snapshot.snapshot);
          anyState = snapshot.snapshot;
        }
      } else if (entityStreamIn.event) {

        // todo validate
        const event = entityStreamIn.event;
        sequence = event.sequence;

        streamDebug("Received event " + sequence + " with type '" + event.payload.type_url + "'");

        handleEvent(event.payload);

      } else if (entityStreamIn.command) {

        // todo validate
        const command = entityStreamIn.command;

        function commandDebug(msg) {
          debug(streamId + " [" + entityId + "] (" + command.id + ") - " + msg);
        }

        commandDebug("Received command '" + command.name + "' with type '" + command.payload.type_url + "'");

        if (!entity.service.methods.hasOwnProperty(command.name)) {
          commandDebug("Command '" + command.name + "' unknown.");
          call.write({
            failure: {
              commandId: command.id,
              description: "Unknown command named " + command.name
            }
          })
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
                  commandDebug("Emitting event '" + serEvent.type_url + "'");
                }
              });
              active = false;

              const anyReply = anySupport.serialize(grpcMethod.resolvedResponseType.create(reply));

              // Invoke event handlers first
              let snapshot = false;
              events.forEach(event => {
                handleEvent(event);
                sequence++;
                if (sequence % entity.options.snapshotEvery === 0) {
                  snapshot = true;
                }
              });

              const msgReply = {
                commandId: command.id,
                payload: anyReply,
                events: events
              };
              if (snapshot) {
                commandDebug("Snapshotting current state with type '" + anyState.type_url + "'");
                msgReply.snapshot = anyState
              }
              commandDebug("Sending reply with " + msgReply.events.length + " events and reply type '" + anyReply.type_url + "'");
              call.write({
                reply: msgReply
              });

            } else {
              // todo error
            }

          });
        }
      }

    });

    call.on("end", function () {
      streamDebug("Stream terminating");
      call.end();
    });
  }

  return server;
}


module.exports = class Entity {

  constructor(desc, serviceName, options) {

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

    this.options = {
      ...{
        persistenceId: "entity",
        snapshotEvery: 100
      },
      ...options
    };
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