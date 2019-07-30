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

    this.streamDebug("Started new stream")
  }

  commandHandlerFactory(commandName) {
    if (this.entity.commandHandlers.hasOwnProperty(commandName)) {

      return (command, ctx, reply, ensureActive, commandDebug) => {

        let deleted = false;
        const noState = this.currentState === null;
        let defaultValue = false;
        if (noState) {
          this.currentState = this.entity.defaultValue();
          if (this.currentState !== null) {
            this.entity.onStateSet(this.currentState, this.entityId);
            defaultValue = true;
          }
        }

        ctx.delete = () => {
          ensureActive();
          if (this.currentState === null) {
            throw new Error("Can't delete entity that hasn't been created.");
          } else if (noState) {
            this.currentState = null;
          } else {
            deleted = true;
          }
        };

        Object.defineProperty(ctx, "state", {
          get: () => {
            ensureActive();
            return this.currentState;
          },
          set: (state) => {
            ensureActive();
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

        const userReply = this.entity.commandHandlers[commandName](command, ctx);

        if (deleted) {
          commandDebug("Deleting entity");
          reply.delete = {};
        } else if (this.currentState !== null && noState) {
          if (defaultValue && this.currentState.getAndResetDelta() === null) {
            // No action, the entity wasn't touched from its default
          } else {
            commandDebug("Creating entity");
            reply.create = this.currentState.getStateAndResetDelta();
          }
        } else if (this.currentState !== null) {
          const delta = this.currentState.getAndResetDelta();
          if (delta != null) {
            commandDebug("Updating entity");
            reply.update = delta;
          }
        }
        return userReply;
      };
    } else {
      return null;
    }
  }

  streamDebug(msg, ...args) {
    debug("%s [%s] - " + msg, ...[this.streamId, this.entityId].concat(args));
  }

  handleState(state) {
    this.streamDebug("Handling state %s", Object.keys(state).concat(["<none>"])[0]);
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

  handleCrdtStreamIn(crdtStreamIn) {
    if (crdtStreamIn.state) {
      this.handleState(crdtStreamIn.state);
    } else if (crdtStreamIn.changed) {
      this.streamDebug("Received delta for CRDT type %s", crdtStreamIn.changed.delta);
      this.currentState.applyDelta(crdtStreamIn.changed, this.entity.anySupport, crdts.createCrdtForState);
    } else if (crdtStreamIn.deleted) {
      this.streamDebug("CRDT deleted");
    } else if (crdtStreamIn.command) {
      this.commandHelper.handleCommand(crdtStreamIn.command);
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