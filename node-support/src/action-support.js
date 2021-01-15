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
const debug = require("debug")("cloudstate-action");
// Bind to stdout
debug.log = console.log.bind(console);
const AnySupport = require("./protobuf-any");
const EffectSerializer = require("./effect-serializer");
const Metadata = require("./metadata");
const CloudEvents = require("./cloudevents");

class ActionSupport {
  constructor(root, service, commandHandlers, allEntities) {
    this.root = root;
    this.service = service;
    this.commandHandlers = commandHandlers;
    this.anySupport = new AnySupport(this.root);
    this.effectSerializer = new EffectSerializer(allEntities);
  }
}

class ActionHandler {

  constructor(support, grpcMethod, commandHandler, call, grpcCallback, metadata) {
    this.support = support;
    this.grpcMethod = grpcMethod;
    this.commandHandler = commandHandler;
    this.call = call;
    this.grpcCallback = grpcCallback;

    this.streamId = Math.random().toString(16).substr(2, 7);
    this.streamDebug("Started new call");
    this.supportedEvents = [];
    this.callbacks = {};
    this.ctx = this.createContext(metadata);
  }

  streamDebug(msg, ...args) {
    debug("%s [%s.%s] - " + msg, ...[this.streamId, this.support.service.name, this.grpcMethod.name].concat(args));
  }

  /**
   * Context for an action command.
   *
   * @interface module:cloudstate.Action.ActionCommandContext
   * @extends module:cloudstate.CommandContext
   * @property {boolean} cancelled Whether the client is still connected.
   * @property {module:cloudstate.Metadata} metadata The metadata associated with the command.
   * @property {module:cloudstate.CloudEvent} cloudevent The CloudEvents metadata associated with the command.
   */
  createContext(metadata) {
    /**
     * Write a message.
     *
     * @function module:cloudstate.Action.ActionCommandContext#write
     * @param {Object} message The protobuf message to write.
     * @param {module:cloudstate.Metadata} metadata The metadata associated with the message.
     */

    const call = this.call;
    let metadataObject = new Metadata([]);
    if (metadata && metadata.entries) {
      metadataObject = new Metadata(metadata.entries);
    }
    const cloudevent = CloudEvents.toCloudevent(metadataObject.getMap);
    const ctx = {
      get cancelled() {
        return call.cancelled;
      },
      get metadata() {
        return metadata;
      },
      get cloudevent() {
        return cloudevent;
      }
    };

    /**
     * Register an event handler.
     *
     * @function module:cloudstate.Action.ActionCommandContext#on
     * @param {string} eventType The type of the event.
     * @param {function} callback The callback to handle the event.
     */
    ctx.on = (eventType, callback) => {
      if (this.supportedEvents.includes(eventType)) {
        this.callbacks[eventType] = callback;
      } else {
        throw new Error("Unknown event type: " + eventType);
      }
    };
    return ctx;
  }

  invokeCallback(eventType, ...args) {
    if (this.callbacks.hasOwnProperty(eventType)) {
      this.invokeUserCallback(eventType + " event", this.callbacks[eventType], ...args)
    }
  }

  ensureNotCancelled() {
    if (this.call.cancelled) {
      throw new Error("Already replied to unary command, cannot interact further.")
    }
  }

  /**
   * Context for a unary action command.
   *
   * @interface module:cloudstate.Action.UnaryCommandContext
   * @extends module:cloudstate.Action.ActionCommandContext
   */
  handleUnary() {
    this.setupUnaryOutContext();
    const deserializedCommand = this.grpcMethod.resolvedRequestType.decode(this.call.request.payload.value);
    const userReturn = this.invokeUserCallback("command", this.commandHandler, deserializedCommand, this.ctx);
    if (userReturn !== undefined) {
      if (this.call.cancelled) {
        this.streamDebug("Unary command handler for command %s.%s both sent a reply through the context and returned a value, ignoring return value.", this.support.service.name, this.grpcMethod.name)
      } else {
        if (typeof userReturn.then === "function") {
          userReturn.then(this.ctx.write, this.ctx.fail)
        } else {
          this.ctx.write(userReturn);
        }
      }
    }
  }

  /**
   * Context for a streamed in action command.
   *
   * @interface module:cloudstate.Action.StreamedInCommandContext
   * @extends module:cloudstate.Action.StreamedInContext
   * @extends module:cloudstate.Action.ActionCommandContext
   */
  handleStreamedIn() {
    this.setupUnaryOutContext();
    this.setupStreamedInContext();
    const userReturn = this.invokeUserCallback("command", this.commandHandler, this.ctx);
    if (userReturn !== undefined) {
      if (this.call.cancelled) {
        this.streamDebug("Streamed command handler for command %s.%s both sent a reply through the context and returned a value, ignoring return value.", this.support.service.name, this.grpcMethod.name)
      } else {
        if (typeof userReturn.then === "function") {
          userReturn.then(this.ctx.write, this.ctx.fail)
        } else {
          this.ctx.write(userReturn);
        }
      }
    }
  }

  /**
   * Context for a streamed out action command.
   *
   * @interface module:cloudstate.Action.StreamedOutCommandContext
   * @extends module:cloudstate.Action.StreamedOutContext
   */
  handleStreamedOut() {
    this.setupStreamedOutContext();
    const deserializedCommand = this.grpcMethod.resolvedRequestType.decode(this.call.request.payload.value);
    this.invokeUserCallback("command", this.commandHandler, deserializedCommand, this.ctx);
  }

  /**
   * Context for a streamed action command.
   *
   * @interface module:cloudstate.Action.StreamedCommandContext
   * @extends module:cloudstate.Action.StreamedInContext
   * @extends module:cloudstate.Action.StreamedOutContext
   */
  handleStreamed() {
    this.setupStreamedInContext();
    this.setupStreamedOutContext();
    this.invokeUserCallback("command", this.commandHandler, this.ctx);
  }

  setupUnaryOutContext() {
    const effects = [];

    this.ctx.thenForward = (method, message, metadata) => {
      this.ensureNotCancelled();
      this.streamDebug("Forwarding to %s", method);
      const forward = this.support.effectSerializer.serializeEffect(method, message, metadata);
      this.grpcCallback(null, {
        forward: forward,
        sideEffects: effects
      });
    };

    this.ctx.write = (message, metadata) => {
      this.ensureNotCancelled();
      this.streamDebug("Sending reply");
      const messageProto = this.grpcMethod.resolvedResponseType.fromObject(message);
      const replyPayload = AnySupport.serialize(messageProto, false, false, false);
      let replyMetadata = null;
      if (metadata && metadata.entries) {
        replyMetadata = {
          entries: metadata.entries
        };
      }
      this.grpcCallback(null, {
        reply: {
          payload: replyPayload,
          metadata: replyMetadata
        },
        sideEffects: effects
      });
    };

    this.ctx.effect = (method, message, synchronous, metadata) => {
      this.ensureNotCancelled();
      this.streamDebug("Emitting effect to %s", method);
      effects.push(this.support.effectSerializer.serializeEffect(method, message, synchronous, metadata));
    };

    this.ctx.fail = error => {
      this.ensureNotCancelled();
      this.streamDebug("Failing with %s", error);
      this.grpcCallback(null, {
        failure: {
          description: error
        },
      });
    };
  }

  /**
   * Context for a action command that returns a streamed message out.
   *
   * @interface module:cloudstate.Action.StreamedOutContext
   * @extends module:cloudstate.Action.ActionCommandContext
   */
  setupStreamedOutContext() {

    /**
     * A cancelled event.
     *
     * @event module:cloudstate.Action.StreamedOutContext#cancelled
     */
    this.supportedEvents.push("cancelled");

    this.call.on("cancelled", () => {
      this.streamDebug("Received stream cancelled");
      this.invokeCallback("cancelled", this.ctx);
    });

    /**
     * Terminate the outgoing stream of messages.
     *
     * @function module:cloudstate.Action.StreamedOutContext#end
     */
    this.ctx.end = () => {
      if (this.call.cancelled) {
        this.streamDebug("end invoked when already cancelled.");
      } else {
        this.streamDebug("Ending stream out");
        this.call.end();
      }
    };

    this.ctx.thenForward = (method, message, metadata) => {
      this.ensureNotCancelled();
      this.streamDebug("Forwarding to %s", method);
      const forward = this.support.effectSerializer.serializeEffect(method, message, metadata);
      this.call.write({
        forward: forward
      });
    };

    this.ctx.write = (message, metadata) => {
      this.ensureNotCancelled();
      this.streamDebug("Sending reply");
      const messageProto = this.grpcMethod.resolvedResponseType.fromObject(message);
      const replyPayload = AnySupport.serialize(messageProto, false, false, false);
      let replyMetadata = null;
      if (metadata && metadata.entries) {
        replyMetadata = {
          entries: metadata.entries
        };
      }
      this.call.write({
        reply: {
          payload: replyPayload,
          metadata: replyMetadata
        }
      });
    };

    this.ctx.effect = (method, message, synchronous, metadata) => {
      this.ensureNotCancelled();
      this.streamDebug("Emitting effect to %s", method);
      this.call.write({
        sideEffects: [this.support.effectSerializer.serializeSideEffect(method, message, synchronous, metadata)]
      });
    };

    this.ctx.fail = error => {
      this.ensureNotCancelled();
      this.streamDebug("Failing with %s", error);
      this.call.write({
        failure: {
          description: error
        },
      });
      this.call.end();
    };
  }

  /**
   * Context for a action command that handles streamed messages in.
   *
   * @interface module:cloudstate.Action.StreamedInContext
   * @extends module:cloudstate.Action.ActionCommandContext
   */
  setupStreamedInContext() {
    /**
     * A data event.
     *
     * Emitted when a new message arrives.
     *
     * @event module:cloudstate.Action.StreamedInContext#data
     * @type {Object}
     */
    this.supportedEvents.push("data");

    /**
     * A stream end event.
     *
     * Emitted when the input stream terminates.
     *
     * @event module:cloudstate.Action.StreamedInContext#end
     */
    this.supportedEvents.push("end");

    this.call.on("data", (data) => {
      this.streamDebug("Received data in");
      const deserializedCommand = this.grpcMethod.resolvedRequestType.decode(data.payload.value);
      this.invokeCallback("data", deserializedCommand, this.ctx);
    });

    this.call.on("end", () => {
      this.streamDebug("Received stream end");
      this.invokeCallback("end", this.ctx);
    });

    /**
     * Cancel the incoming stream of messages.
     *
     * @function module:cloudstate.Action.StreamedInContext#cancel
     */
    this.ctx.cancel = () => {
      if (this.call.cancelled) {
        this.streamDebug("cancel invoked when already cancelled.");
      } else {
        this.call.cancel();
      }
    }
  }

  invokeUserCallback(callbackName, callback, ...args) {
    try {
      return callback.apply(null, args);
    } catch (err) {
      const error = "Error handling " + callbackName;
      this.streamDebug(error);
      console.error(err);
      if (!this.call.cancelled) {
        const failure = {
          failure: {
            description: error
          },
        };
        if (this.grpcCallback != null) {
          this.grpcCallback(null, failure);
        } else {
          this.call.write(failure);
          this.call.end();
        }
      }
    }
  }
}

module.exports = class ActionServices {

  constructor() {
    this.services = {};
  }

  addService(entity, allEntities) {
    this.services[entity.serviceName] = new ActionSupport(entity.root, entity.service,
        entity.commandHandlers, allEntities);
  }

  entityType() {
    return "cloudstate.action.ActionProtocol";
  }

  register(server) {
    const includeDirs = [
      path.join(__dirname, "..", "proto"),
      path.join(__dirname, "..", "protoc", "include")
    ];
    const packageDefinition = protoLoader.loadSync(path.join("cloudstate", "action.proto"), {
      includeDirs: includeDirs
    });
    const grpcDescriptor = grpc.loadPackageDefinition(packageDefinition);

    const actionService = grpcDescriptor.cloudstate.action.ActionProtocol.service;

    server.addService(actionService, {
      handleUnary: this.handleUnary.bind(this),
      handleStreamedIn: this.handleStreamedIn.bind(this),
      handleStreamedOut: this.handleStreamedOut.bind(this),
      handleStreamed: this.handleStreamed.bind(this),
    });
  }

  createHandler(call, callback, data) {
    const service = this.services[data.serviceName];
    if (service && service.service.methods.hasOwnProperty(data.name)) {
      if (service.commandHandlers.hasOwnProperty(data.name)) {
        return new ActionHandler(service, service.service.methods[data.name], service.commandHandlers[data.name], call, callback, data.metadata)
      } else {
        this.reportError("Service call " + data.serviceName + "." + data.name + " not implemented", call, callback)
      }
    } else {
      this.reportError("No service call named " + data.serviceName + "." + data.name + " found", call, callback)
    }
  }

  reportError(error, call, callback) {
    console.warn(error);
    const failure = {
      failure: {
        description: error
      }
    };
    if (callback !== null) {
      callback(null, failure);
    } else {
      call.write(failure);
      call.end();
    }
  }

  handleStreamed(call) {
    call.on("data", data => {
      // Ignore the remaining data by default
      call.on("data", () => {});
      const handler = this.createHandler(call, null, data);
      if (handler) {
        handler.handleStreamed();
      }
    });
  }

  handleStreamedOut(call) {
    const handler = this.createHandler(call, null, call.request);
    if (handler) {
      handler.handleStreamedOut();
    }
  }

  handleStreamedIn(call, callback) {
    call.on("data", data => {
      // Ignore the remaining data by default
      call.on("data", () => {});
      const handler = this.createHandler(call, callback, data);
      if (handler) {
        handler.handleStreamedIn();
      }
    });
  }

  handleUnary(call, callback) {
    const handler = this.createHandler(call, callback, call.request);
    if (handler) {
      handler.handleUnary();
    }
  }

};
