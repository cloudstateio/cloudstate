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
const grpc = require("grpc");
const protoLoader = require("@grpc/proto-loader");

const debug = require("debug")("cloudstate-event-sourcing");
// Bind to stdout
debug.log = console.log.bind(console);
const AnySupport = require("./protobuf-any");
const CommandHelper = require("./command-helper");

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

  serialize(obj, requireJsonType) {
    return AnySupport.serialize(obj, this.options.serializeAllowPrimitives, this.options.serializeFallbackToJson, requireJsonType);
  }

  deserialize(any) {
    return this.anySupport.deserialize(any);
  }

  /**
   * @param call
   * @param init
   * @returns {EventSourcedEntityHandler}
   * @private
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
 * @private
 */
class EventSourcedEntityHandler {

  /**
   * @param {EventSourcedSupport} support
   * @param call
   * @param entityId
   * @private
   */
  constructor(support, call, entityId) {
    this.entity = support;
    this.call = call;
    this.entityId = entityId;

    // The current entity state, serialized to an Any
    this.anyState = null;

    // The current sequence number
    this.sequence = 0;

    this.streamId = Math.random().toString(16).substr(2, 7);

    this.commandHelper = new CommandHelper(this.entityId, support.service, this.streamId, call,
      this.commandHandlerFactory.bind(this), support.allEntities, debug);

    this.streamDebug("Started new stream")
  }

  streamDebug(msg, ...args) {
    debug("%s [%s] - " + msg, ...[this.streamId, this.entityId].concat(args));
  }

  commandHandlerFactory(commandName) {
    return this.withBehaviorAndState((behavior, state) => {

      if (behavior.commandHandlers.hasOwnProperty(commandName)) {

        return (command, ctx) => {

          /**
           * Context for an event sourced command.
           *
           * @interface module:cloudstate.EventSourced.EventSourcedCommandContext
           * @extends module:cloudstate.CommandContext
           */

          ctx.events = [];

          /**
           * Persist an event.
           *
           * The event won't be persisted until the reply is sent to the proxy. Then, the event will be persisted
           * before the reply is sent back to the client.
           *
           * @function module:cloudstate.EventSourced.EventSourcedCommandContext#emit
           * @param {module:cloudstate.Serializable} event The event to emit.
           */
          ctx.context.emit = (event) => {
            ctx.ensureActive();

            const serEvent = this.entity.serialize(event, true);
            ctx.events.push(serEvent);
            ctx.commandDebug("Emitting event '%s'", serEvent.type_url);
          };

          const userReply = behavior.commandHandlers[commandName](command, state, ctx.context);

          // Invoke event handlers first
          let snapshot = false;
          ctx.events.forEach(event => {
            this.handleEvent(event);
            this.sequence++;
            if (this.sequence % this.entity.options.snapshotEvery === 0) {
              snapshot = true;
            }
          });

          if (ctx.events.length > 0) {
            ctx.commandDebug("Emitting %d events", ctx.events.length);
          }
          ctx.reply.events = ctx.events;

          if (snapshot) {
            ctx.commandDebug("Snapshotting current state with type '%s'", this.anyState.type_url);
            ctx.reply.snapshot = this.anyState
          }

          return userReply;
        };
      } else {
        return null;
      }
    });
  }

  handleSnapshot(snapshot) {
    this.sequence = snapshot.snapshotSequence;
    this.streamDebug("Handling snapshot with type '%s' at sequence %s", snapshot.snapshot.type_url, this.sequence);
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

      this.commandHelper.handleCommand(eventSourcedStreamIn.command);

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
    this.anyState = this.entity.serialize(stateObj, false);
  }

  withBehaviorAndState(callback) {
    if (this.anyState == null) {
      this.updateState(this.entity.initial(this.entityId));
    }
    const stateObj = this.entity.deserialize(this.anyState);
    const behavior = this.entity.behavior(stateObj);
    return callback(behavior, stateObj);
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
    const packageDefinition = protoLoader.loadSync(path.join("cloudstate", "event_sourced.proto"), {
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