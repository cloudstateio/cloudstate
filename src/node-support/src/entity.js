

const path = require("path");
const debug = require("debug")("stateserve-event-sourcing");
// Bind to stdout
debug.log = console.log.bind(console);
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

class ContextFailure extends Error {
  constructor(msg) {
    super(msg);
    if (Error.captureStackTrace) {
      Error.captureStackTrace(this, ContextFailure);
    }
    this.name = "ContextFailure";
  }
}

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

    let entityId = null;

    // The current entity state, serialized to an Any
    let anyState;

    // The current state descriptor
    let stateDescriptor;

    // The current sequence number
    let sequence = 0;

    const streamId = Math.random().toString(16).substr(2, 7);

    function streamDebug(msg, ...args) {
      if (entityId) {
        debug("%s [%s] - " + msg, ...[streamId, entityId].concat(args));
      } else {
        debug("%s - " + msg, ...[streamId].concat(args));
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
        let handler = null;
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
            throw new Error("No handler found for event '" + fqName + "'");
          }
        }
        const newState = handler(deserEvent, state);
        updateState(newState);
      });
    }

    function handleCommand(command) {
      function commandDebug(msg, ...args) {
        debug("%s [%s] (%s) - " + msg, ...[streamId, entityId, command.id].concat(args));
      }

      commandDebug("Received command '%s' with type '%s'", command.name, command.payload.type_url);

      if (!entity.service.methods.hasOwnProperty(command.name)) {
        commandDebug("Command '%s' unknown", command.name);
        call.write({
          failure: {
            commandId: command.id,
            description: "Unknown command named " + command.name
          }
        })
      } else {

        try {
          const grpcMethod = entity.service.methods[command.name];

          // todo maybe reconcile whether the command URL of the Any type matches the gRPC response type
          const deserCommand = grpcMethod.resolvedRequestType.decode(command.payload.value);

          withBehaviorAndState((behavior, state) => {

            if (behavior.commandHandlers.hasOwnProperty(command.name)) {

              const events = [];
              let active = true;
              function ensureActive() {
                if (!active) {
                  throw new Error("Command context no longer active!");
                }
              }
              let error = null;
              let reply;

              try {
                reply = behavior.commandHandlers[command.name](deserCommand, state, {
                  emit: (event) => {
                    ensureActive();

                    const serEvent = anySupport.serialize(event);
                    events.push(serEvent);
                    commandDebug("Emitting event '%s'", serEvent.type_url);
                  },
                  fail: (msg) => {
                    ensureActive();
                    // We set it here to ensure that even if the user catches the error, for
                    // whatever reason, we will still fail as instructed.
                    error = new ContextFailure(msg);
                    // Then we throw, to end processing of the command.
                    throw error;
                  }
                });
              } catch (err) {
                if (error == null) {
                  // If the error field isn't null, then that means we were explicitly told
                  // to fail, so we can ignore this thrown error and fail gracefully with a
                  // failure message. Otherwise, we rethrow, and handle by closing the connection
                  // higher up.
                  throw err;
                }
              } finally {
                active = false;
              }

              if (error !== null) {
                commandDebug("Command failed with message '%s'", error.message);
                call.write({
                  failure: {
                    commandId: command.id,
                    description: error.message
                  }
                });
              } else {
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
                  commandDebug("Snapshotting current state with type '%s'", anyState.type_url);
                  msgReply.snapshot = anyState
                }
                commandDebug("Sending reply with %d events and reply type '%s'", msgReply.events.length, anyReply.type_url);
                call.write({
                  reply: msgReply
                });
              }

            } else {
              const msg = "No handler register for command '" + command.name + "'";
              commandDebug(msg);
              call.write({
                failure: {
                  commandId: command.id,
                  description: msg
                }
              })
            }

          });

        } catch (err) {
          const error = "Error handling command '" + command.name + "'";
          commandDebug(error);
          console.error(err);

          call.write({
            failure: {
              commandId: command.id,
              description: error + ": " + err
            }
          });

          call.end();
        }
      }
    }

    function handleEntityStreamIn(entityStreamIn) {
      if (entityStreamIn.init) {
        if (entityId != null) {
          streamDebug("Terminating entity due to duplicate init message.");
          console.error("Terminating entity due to duplicate init message.");
          call.write({
            failure: {
              description: "Init message received twice."
            }
          });
          call.end();
        } else {
          // validate init fields
          entityId = entityStreamIn.init.entityId;

          streamDebug("Received init message");

          if (entityStreamIn.init.snapshot) {
            const snapshot = entityStreamIn.init.snapshot;
            sequence = snapshot.snapshotSequence;
            streamDebug("Handling snapshot with type %s at sequence %s", snapshot.snapshot.type_url, sequence);
            stateDescriptor = anySupport.lookupDescriptor(snapshot.snapshot);
            anyState = snapshot.snapshot;
          }
        }
      } else if (entityStreamIn.event) {

        const event = entityStreamIn.event;
        sequence = event.sequence;
        streamDebug("Received event %s with type '%s'", sequence, event.payload.type_url);
        handleEvent(event.payload);

      } else if (entityStreamIn.command) {

        handleCommand(entityStreamIn.command);

      }
    }

    call.on("data", function (entityStreamIn) {
      try {
        handleEntityStreamIn(entityStreamIn);
      } catch (err) {
        streamDebug("Error handling message, terminating stream: %o", entityStreamIn);
        console.error(err);
        call.end();
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