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
const grpc = require("grpc");
const protoLoader = require("@grpc/proto-loader");

const debug = require("debug")("cloudstate-event-sourcing");
// Bind to stdout
debug.log = console.log.bind(console);
const AnySupport = require("./protobuf-any");

class ContextFailure extends Error {
  constructor(msg) {
    super(msg);
    if (Error.captureStackTrace) {
      Error.captureStackTrace(this, ContextFailure);
    }
    this.name = "ContextFailure";
  }
}

class EventSourcedSupport {

  constructor(root, service, behavior, initial, options, allEntities) {
    this.root = root;
    this.service = service;
    this.behavior = behavior;
    this.initial = initial;
    this.options = options;
    this.anySupport = new AnySupport(this.root);
    this.allEntities = allEntities;
  }

  serialize(obj) {
    return this.anySupport.serialize(obj);
  }

  lookupDescriptor(any) {
    return this.anySupport.lookupDescriptor(any);
  }

  deserialize(any) {
    return this.anySupport.deserialize(any);
  }

  /**
   * @param call
   * @param init
   * @returns {EventSourcedEntityHandler}
   */
  create(call, init) {
    const handler = new EventSourcedEntityHandler(this, call, init.entityId);
    if (init.snapshot) {
      handler.handleSnapshot(init.snapshot)
    }
    return handler;
  }
}

/**
 * Handler for a single event sourced entity.
 */
class EventSourcedEntityHandler {

  /**
   * @param {EventSourcedSupport} support
   * @param call
   * @param entityId
   */
  constructor(support, call, entityId) {
    this.entity = support;
    this.call = call;
    this.entityId = entityId;

    // The current entity state, serialized to an Any
    this.anyState = null;

    // The current state descriptor
    this.stateDescriptor = null;

    // The current sequence number
    this.sequence = 0;

    this.streamId = Math.random().toString(16).substr(2, 7);

    this.streamDebug("Started new stream")
  }

  streamDebug(msg, ...args) {
    debug("%s [%s] - " + msg, ...[this.streamId, this.entityId].concat(args));
  }

  handleSnapshot(snapshot) {
    this.sequence = snapshot.snapshotSequence;
    this.streamDebug("Handling snapshot with type '%s' at sequence %s", snapshot.snapshot.type_url, this.sequence);
    this.stateDescriptor = this.entity.lookupDescriptor(snapshot.snapshot);
    this.anyState = snapshot.snapshot;
  }

  onData(eventSourcedStreamIn) {
    try {
      this.handleEventSourcedStreamIn(eventSourcedStreamIn);
    } catch (err) {
      this.streamDebug("Error handling message, terminating stream: %o", eventSourcedStreamIn);
      console.error(err);
      this.call.write({
        failure: {
          commandId: 0,
          description: "Fatal error handling message, check user container logs."
        }
      });
      this.call.end();
    }
  }

  handleEventSourcedStreamIn(eventSourcedStreamIn) {
    if (eventSourcedStreamIn.event) {

      const event = eventSourcedStreamIn.event;
      this.sequence = event.sequence;
      this.streamDebug("Received event %s with type '%s'", this.sequence, event.payload.type_url);
      this.handleEvent(event.payload);

    } else if (eventSourcedStreamIn.command) {

      this.handleCommand(eventSourcedStreamIn.command);

    }
  }

  handleEvent(event) {
    const deserEvent = this.entity.deserialize(event);
    this.withBehaviorAndState((behavior, state) => {
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
      this.updateState(newState);
    });
  }

  updateState(stateObj) {
    this.stateDescriptor = stateObj.constructor;
    this.anyState = this.entity.serialize(stateObj);
  }

  withBehaviorAndState(callback) {
    if (this.stateDescriptor == null) {
      this.updateState(this.entity.initial(this.entityId));
    }
    // If the state is empty, anyState.value could be null or undefined, handle that
    let buffer = this.anyState.value;
    if (typeof buffer === "undefined") {
      buffer = new Buffer(0)
    }
    const stateObj = this.stateDescriptor.decode(buffer);
    const behavior = this.entity.behavior(stateObj);
    callback(behavior, stateObj);
  }

  serializeEffect(method, message) {
    let serviceName, commandName;
    // We support either the grpc method, or a protobufjs method being passed
    if (typeof method.path === "string") {
      const r = new RegExp("^/([^/]+)/([^/]+)$").exec(method.path);
      if (r == null) {
        throw new Error(util.format("Not a valid gRPC method path '%s' on object '%o'", method.path, method));
      }
      serviceName = r[1];
      commandName = r[2];
    } else if (method.type === "rpc") {
      serviceName = method.parent.name;
      commandName = method.name;
    }

    const service = this.entity.allEntities[serviceName];

    if (service !== undefined) {
      const command = service.methods[commandName];
      if (command !== undefined) {
        const payload = this.entity.serialize(command.resolvedRequestType.create(message));
        return {
          serviceName: serviceName,
          commandName: commandName,
          payload: payload
        };
      } else {
        throw new Error(util.format("Command [%s] unknown on service [%s].", commandName, serviceName))
      }
    } else {
      throw new Error(util.format("Service [%s] has not been registered as an entity in this user function, and so can't be used as a side effect or forward.", service))
    }
  }

  serializeSideEffect(method, message, synchronous) {
    const msg = this.serializeEffect(method, message);
    msg.synchronous = synchronous;
    return msg;
  }

  handleCommand(command) {
    const commandDebug = (msg, ...args) => {
      debug("%s [%s] (%s) - " + msg, ...[this.streamId, this.entityId, command.id].concat(args));
    };

    commandDebug("Received command '%s' with type '%s'", command.name, command.payload.type_url);

    if (!this.entity.service.methods.hasOwnProperty(command.name)) {
      commandDebug("Command '%s' unknown", command.name);
      this.call.write({
        failure: {
          commandId: command.id,
          description: "Unknown command named " + command.name
        }
      })
    } else {

      try {
        const grpcMethod = this.entity.service.methods[command.name];

        // todo maybe reconcile whether the command URL of the Any type matches the gRPC response type
        let commandBuffer = command.payload.value;
        if (typeof commandBuffer === "undefined") {
          commandBuffer = new Buffer(0)
        }
        const deserCommand = grpcMethod.resolvedRequestType.decode(commandBuffer);

        this.withBehaviorAndState((behavior, state) => {

          if (behavior.commandHandlers.hasOwnProperty(command.name)) {

            const events = [];
            const effects = [];
            let active = true;
            const ensureActive = () =>  {
              if (!active) {
                throw new Error("Command context no longer active!");
              }
            };
            let error = null;
            let reply;
            let forward = null;

            try {
              reply = behavior.commandHandlers[command.name](deserCommand, state, {
                entityId: this.entityId,
                emit: (event) => {
                  ensureActive();

                  const serEvent = this.entity.serialize(event);
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
                },
                effect: (method, message, synchronous = false) => {
                  ensureActive();
                  effects.push(this.serializeSideEffect(method, message, synchronous))
                },
                thenForward: (method, message) => {
                  forward = this.serializeEffect(method, message);
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
              this.call.write({
                failure: {
                  commandId: command.id,
                  description: error.message
                }
              });
            } else {

              // Invoke event handlers first
              let snapshot = false;
              events.forEach(event => {
                this.handleEvent(event);
                this.sequence++;
                if (this.sequence % this.entity.options.snapshotEvery === 0) {
                  snapshot = true;
                }
              });

              const msgReply = {
                commandId: command.id,
                events: events,
                sideEffects: effects
              };

              if (snapshot) {
                commandDebug("Snapshotting current state with type '%s'", this.anyState.type_url);
                msgReply.snapshot = this.anyState
              }

              if (forward != null) {
                msgReply.forward = forward;
                commandDebug("Sending reply with %d events, %d side effects and forwarding to '%s.%s'",
                  msgReply.events.length, msgReply.sideEffects.length, forward.serviceName, forward.commandName);
              } else {
                msgReply.reply = {
                  payload: this.entity.serialize(grpcMethod.resolvedResponseType.create(reply))
                };
                commandDebug("Sending reply with %d events, %d side effects and reply type '%s'",
                  msgReply.events.length, msgReply.sideEffects.length, msgReply.reply.payload.typeUrl);
              }

              this.call.write({
                reply: msgReply
              });
            }

          } else {
            const msg = "No handler register for command '" + command.name + "'";
            commandDebug(msg);
            this.call.write({
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

        this.call.write({
          failure: {
            commandId: command.id,
            description: error + ": " + err
          }
        });

        this.call.end();
      }
    }
  }


  onEnd() {
    this.streamDebug("Stream terminating");
    this.call.end();
  }

}


module.exports = class EventSourcedServices {

  constructor() {
    this.services = {};
  }

  addService(entity, allEntities) {
    this.services[entity.serviceName] = new EventSourcedSupport(entity.root, entity.service, entity.behavior,
      entity.initial, entity.options, allEntities);
  }

  entityType() {
    return "cloudstate.eventsourced.EventSourced";
  }

  register(server) {
    const includeDirs = [
      path.join(__dirname, "..", "proto"),
      path.join(__dirname, "..", "protoc", "include")
    ];
    const packageDefinition = protoLoader.loadSync(path.join("cloudstate", "eventsourced.proto"), {
      includeDirs: includeDirs
    });
    const grpcDescriptor = grpc.loadPackageDefinition(packageDefinition);

    const entityService = grpcDescriptor.cloudstate.eventsourced.EventSourced.service;

    server.addService(entityService, {
      handle: this.handle.bind(this)
    });
  }

  handle(call) {
    let service;

    call.on("data", eventSourcedStreamIn => {
      if (eventSourcedStreamIn.init) {
        if (service != null) {
          service.streamDebug("Terminating entity due to duplicate init message.");
          console.error("Terminating entity due to duplicate init message.");
          call.write({
            failure: {
              description: "Init message received twice."
            }
          });
          call.end();
        } else if (eventSourcedStreamIn.init.serviceName in this.services) {
          service = this.services[eventSourcedStreamIn.init.serviceName].create(call, eventSourcedStreamIn.init);
        } else {
          console.error("Received command for unknown service: '%s'", eventSourcedStreamIn.init.serviceName);
          call.write({
            failure: {
              description: "Service '" + eventSourcedStreamIn.init.serviceName + "' unknown."
            }
          });
          call.end();
        }
      } else if (service != null) {
        service.onData(eventSourcedStreamIn);
      } else {
        console.error("Unknown message received before init %o", eventSourcedStreamIn);
        call.write({
          failure: {
            description: "Unknown message received before init"
          }
        });
        call.end();
      }
    });

    call.on("end", () => {
      if (service != null) {
        service.onEnd();
      } else {
        call.end();
      }
    });
  }
};