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
const debug = require("debug")("cloudstate-crdt");
const util = require("util");
const grpc = require("grpc");
const protoLoader = require("@grpc/proto-loader");
const protoHelper = require("./protobuf-helper")
const AnySupport = require("./protobuf-any");
const crdts = require("./crdts");
const CommandHelper = require("./command-helper");

class CrdtServices {
  constructor() {
    this.services = {};
    this.includeDirs = [
      path.join(__dirname, "..", "proto"),
      path.join(__dirname, "..", "protoc", "include")
    ];
  }

  addService(entity, allEntities) {
    this.services[entity.serviceName] = new CrdtSupport(entity.root, entity.service, {
      commandHandlers: entity.commandHandlers,
      onStateSet: entity.onStateSet,
      defaultValue: entity.defaultValue
    }, allEntities);
  }

  entityType() {
    return "cloudstate.crdt.Crdt";
  }

  register(server) {
    const packageDefinition = protoLoader.loadSync(path.join("cloudstate", "crdt.proto"), {
      includeDirs: this.includeDirs
    });
    const grpcDescriptor = grpc.loadPackageDefinition(packageDefinition);

    const entityService = grpcDescriptor.cloudstate.crdt.Crdt.service;

    server.addService(entityService, {
      handle: this.handle.bind(this)
    });
  }

  handle(call) {
    let service;

    call.on("data", crdtStreamIn => {
      // cycle through the CrdtStreamIn type, this will ensure default values are initialised
      crdtStreamIn = protoHelper.moduleRoot.cloudstate.crdt.CrdtStreamIn.fromObject(crdtStreamIn);

      if (crdtStreamIn.init) {
        if (service != null) {
          service.streamDebug("Terminating entity due to duplicate init message.");
          console.error("Terminating entity due to duplicate init message.");
          call.write({
            failure: {
              description: "Init message received twice."
            }
          });
          call.end();
        } else if (crdtStreamIn.init.serviceName in this.services) {
          service = this.services[crdtStreamIn.init.serviceName].create(call, crdtStreamIn.init);
        } else {
          console.error("Received command for unknown CRDT service: '%s'", crdtStreamIn.init.serviceName);
          call.write({
            failure: {
              description: "CRDT service '" + crdtStreamIn.init.serviceName + "' unknown."
            }
          });
          call.end();
        }
      } else if (service != null) {
        service.onData(crdtStreamIn);
      } else {
        console.error("Unknown message received before init %o", crdtStreamIn);
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
}

class CrdtSupport {

  constructor(root, service, handlers, allEntities) {
    this.root = root;
    this.service = service;
    this.anySupport = new AnySupport(this.root);
    this.commandHandlers = handlers.commandHandlers;
    this.onStateSet = handlers.onStateSet;
    this.defaultValue = handlers.defaultValue;
    this.allEntities = allEntities;
  }

  create(call, init) {
    const handler = new CrdtHandler(this, call, init.entityId);
    if (init.state) {
      handler.handleState(init.state)
    }
    return handler;
  }
}

/**
 * Handler for a single CRDT entity.
 */
class CrdtHandler {

  /**
   * @param {CrdtSupport} support
   * @param call
   * @param entityId
   */
  constructor(support, call, entityId) {
    this.entity = support;
    this.call = call;
    this.entityId = entityId;

    this.currentState = null;

    this.streamId = Math.random().toString(16).substr(2, 7);

    this.commandHelper = new CommandHelper(this.entityId, support.service, this.streamId, call,
      this.commandHandlerFactory.bind(this), support.allEntities, debug);

    this.streamDebug("Started new stream");

    this.subscribers = new Map();
    this.cancelledCallbacks = new Map();
  }

  commandHandlerFactory(commandName, grpcMethod) {
    if (this.entity.commandHandlers.hasOwnProperty(commandName)) {

      return (command, ctx) => {

        this.addStateManagementToContext(ctx);

        ctx.subscribed = false;
        Object.defineProperty(ctx.context, "onStateChange", {
          set: (handler) => {
            ctx.ensureActive();
            if (!ctx.streamed) {
              throw new Error("Cannot subscribe to updates from non streamed command")
            }
            this.subscribers.set(ctx.commandId.toString(), {
              commandId: ctx.commandId,
              handler: handler,
              grpcMethod: grpcMethod
            });
            ctx.subscribed = true;
          }
        });
        Object.defineProperty(ctx.context, "onStreamCancel", {
          set: (handler) => {
            ctx.ensureActive();
            if (!ctx.streamed) {
              throw new Error("Cannot receive stream cancelled from non streamed command")
            }
            this.cancelledCallbacks.set(ctx.commandId.toString(), {
              commandId: command.id,
              handler: handler,
              grpcMethod: grpcMethod
            });
            ctx.subscribed = true;
          }
        });
        Object.defineProperty(ctx.context, "streamed", {
          get: () => ctx.streamed === true
        });

        const userReply = this.entity.commandHandlers[commandName](command, ctx.context);
        if (ctx.streamed && ctx.subscription === null) {
          // todo relax this requirement
          throw new Error("Streamed commands must be subscribed to using ctx.subscribe()");
        }

        this.setStateActionOnReply(ctx);

        if (ctx.subscribed) {
          ctx.reply.streamed = true;
        }

        return userReply;
      };
    } else {
      return null;
    }
  }

  setStateActionOnReply(ctx) {
    if (ctx.deleted) {
      ctx.commandDebug("Deleting entity");
      ctx.reply.stateAction = {
        delete: {}
      };
      this.currentState = null;
      this.handleStateChange();
    } else if (this.currentState !== null && ctx.noState) {
      if (ctx.defaultValue && this.currentState.getAndResetDelta() === null) {
        // No action, the entity wasn't touched from its default
      } else {
        ctx.commandDebug("Creating entity");
        ctx.reply.stateAction = {
          create: this.currentState.getStateAndResetDelta()
        };
        this.handleStateChange();
      }
    } else if (this.currentState !== null) {
      const delta = this.currentState.getAndResetDelta();
      if (delta != null) {
        ctx.commandDebug("Updating entity");
        ctx.reply.stateAction = {
          update: delta
        };
        this.handleStateChange();
      }
    }
  }

  addStateManagementToContext(ctx) {
    ctx.deleted = false;
    ctx.noState = this.currentState === null;
    ctx.defaultValue = false;
    if (ctx.noState) {
      this.currentState = this.entity.defaultValue();
      if (this.currentState !== null) {
        this.entity.onStateSet(this.currentState, this.entityId);
        ctx.defaultValue = true;
      }
    }

    ctx.context.delete = () => {
      ctx.ensureActive();
      if (this.currentState === null) {
        throw new Error("Can't delete entity that hasn't been created.");
      } else if (ctx.noState) {
        this.currentState = null;
      } else {
        ctx.deleted = true;
      }
    };

    Object.defineProperty(ctx.context, "state", {
      get: () => {
        ctx.ensureActive();
        return this.currentState;
      },
      set: (state) => {
        ctx.ensureActive();
        if (this.currentState !== null) {
          throw new Error("Cannot create a new CRDT after it's been created.")
        } else if (typeof state.getAndResetDelta !== "function") {
          throw new Error(util.format("%o is not a CRDT", state));
        } else {
          this.currentState = state;
          this.entity.onStateSet(this.currentState, this.entityId);
        }
      }
    });
  }

  streamDebug(msg, ...args) {
    debug("%s [%s] - " + msg, ...[this.streamId, this.entityId].concat(args));
  }

  handleState(state) {
    this.streamDebug("Handling state %s", state.state);
    if (this.currentState === null) {
      this.currentState = crdts.createCrdtForState(state);
    }
    this.currentState.applyState(state, this.entity.anySupport, crdts.createCrdtForState);
    this.entity.onStateSet(this.currentState, this.entityId);
  }

  onData(crdtStreamIn) {
    try {
      this.handleCrdtStreamIn(crdtStreamIn);
    } catch (err) {
      this.streamDebug("Error handling message, terminating stream: %o", crdtStreamIn);
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

  handleStateChange() {
    this.subscribers.forEach((subscriber, key) => {
      const ctx = this.commandHelper.createContext(subscriber.commandId);
      Object.defineProperty(ctx.context, "state", {
        get: () => {
          return this.currentState;
        }
      });
      ctx.context.end = () => {
        ctx.reply.endStream = true;
        this.subscribers.delete(key);
        this.cancelledCallbacks.delete(key);
      };

      try {
        this.commandHelper.invokeHandler(() => {
          const userReply = subscriber.handler(this.currentState, ctx.context);
          if (this.currentState.getAndResetDelta() !== null) {
            throw new Error("State change handler attempted to modify state");
          }
          return userReply;
        }, ctx, subscriber.grpcMethod, msg => {
          if (ctx.effects.length > 0 || ctx.reply.endStream === true || ctx.reply.clientAction !== undefined) {
            return {
              streamedMessage: msg
            };
          }
        })
      } catch (e) {
        this.call.write({
          failure: {
            commandId: subscriber.commandId,
            description: util.format("Error: %o", e)
          }
        });
        this.call.end();
        // Probably rethrow?
      }
    });
  }

  handleStreamCancelled(cancelled) {
    const subscriberKey = cancelled.id.toString();
    this.subscribers.delete(subscriberKey);

    if (this.cancelledCallbacks.has(subscriberKey)) {
      const subscriber = this.cancelledCallbacks.get(subscriberKey);

      const ctx = this.commandHelper.createContext(cancelled.id);
      ctx.reply = {
        commandId: cancelled.id
      };
      this.addStateManagementToContext(ctx);

      try {
        subscriber.handler(this.currentState, ctx.context);
        this.setStateActionOnReply(ctx);
        ctx.commandDebug("Sending streamed cancelled response");

        this.call.write({
          streamCancelledResponse: ctx.reply
        });

      } catch (e) {
        this.call.write({
          failure: {
            commandId: cancelled.id,
            description: util.format("Error: %o", e)
          }
        });
        this.call.end();
      }
    }
  }

  handleCrdtStreamIn(crdtStreamIn) {
    if (crdtStreamIn.state) {
      this.handleState(crdtStreamIn.state);
      this.handleStateChange();
    } else if (crdtStreamIn.changed) {
      this.streamDebug("Received delta for CRDT type %s", crdtStreamIn.changed.delta);
      this.currentState.applyDelta(crdtStreamIn.changed, this.entity.anySupport, crdts.createCrdtForState);
      this.handleStateChange();
    } else if (crdtStreamIn.deleted) {
      this.streamDebug("CRDT deleted");
      this.currentState = null;
      this.handleStateChange();
    } else if (crdtStreamIn.command) {
      this.commandHelper.handleCommand(crdtStreamIn.command);
    } else if (crdtStreamIn.streamCancelled) {
      this.handleStreamCancelled(crdtStreamIn.streamCancelled)
    } else {
      this.call.write({
        failure: {
          commandId: 0,
          description: util.format("Unknown message: %o", crdtStreamIn)
        }
      });
      this.call.end();
    }
  }

  onEnd() {
    this.streamDebug("Stream terminating");
  }

}

module.exports = {
  CrdtServices: CrdtServices,
  CrdtSupport: CrdtSupport,
  CrdtHandler: CrdtHandler
};