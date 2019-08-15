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

/**
 * Creates the base for context objects.
 */
module.exports = class CommandHelper {

  constructor(entityId, service, streamId, call, handlerFactory, allEntities, debug) {
    this.entityId = entityId;
    this.service = service;
    this.streamId = streamId;
    this.call = call;
    this.allEntities = allEntities;
    this.debug = debug;
    this.handlerFactory = handlerFactory;
  }

  /**
   * Handle a command.
   *
   * @param command The command to handle.
   */
  handleCommand(command) {
    const ctx = this.createContext(command.id);

    if (!this.service.methods.hasOwnProperty(command.name)) {
      ctx.commandDebug("Command '%s' unknown", command.name);
      this.call.write({
        failure: {
          commandId: command.id,
          description: "Unknown command named " + command.name
        }
      })
    } else {

      try {
        const grpcMethod = this.service.methods[command.name];

        // todo maybe reconcile whether the command URL of the Any type matches the gRPC response type
        let commandBuffer = command.payload.value;
        if (typeof commandBuffer === "undefined") {
          commandBuffer = new Buffer(0)
        }
        const deserCommand = grpcMethod.resolvedRequestType.decode(commandBuffer);

        const handler = this.handlerFactory(command.name, grpcMethod);

        if (handler !== null) {

          ctx.streamed = command.streamed;

          this.invokeHandler(() => handler(deserCommand, ctx), ctx, grpcMethod, reply => { return {reply}; }, "Command");

        } else {
          const msg = "No handler registered for command '" + command.name + "', we have: " + Object.keys(this.entity.commandHandlers);
          ctx.commandDebug(msg);
          this.call.write({
            failure: {
              commandId: command.id,
              description: msg
            }
          })
        }
      } catch (err) {
        const error = "Error handling command '" + command.name + "'";
        ctx.commandDebug(error);
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

  invokeHandler(handler, ctx, grpcMethod, createReply, desc) {
    ctx.reply = {};
    let userReply = null;
    try {
      userReply = handler();
    } catch (err) {
      if (ctx.error === null) {
        // If the error field isn't null, then that means we were explicitly told
        // to fail, so we can ignore this thrown error and fail gracefully with a
        // failure message. Otherwise, we rethrow, and handle by closing the connection
        // higher up.
        throw err;
      }
    } finally {
      ctx.active = false;
    }

    if (ctx.error !== null) {
      ctx.commandDebug("%s failed with message '%s'", desc, ctx.error.message);
      this.call.write({
        failure: {
          commandId: ctx.commandId,
          description: ctx.error.message
        }
      });
    } else {
      ctx.reply.commandId = ctx.commandId;
      ctx.reply.sideEffects = ctx.effects;
      ctx.reply.sideEffects = ctx.effects;

      if (ctx.forward !== null) {
        ctx.reply.clientAction = {
          forward: ctx.forward
        };
        ctx.commandDebug("%s forward to %s.%s with %d side effects.", desc, ctx.forward.serviceName, ctx.forward.commandName, ctx.effects.length);
      } else if (userReply !== undefined) {
        ctx.reply.clientAction = {
          reply: {
            payload: AnySupport.serialize(grpcMethod.resolvedResponseType.create(userReply), false, false)
          }
        };
        ctx.commandDebug("%s reply with type [%s] with %d side effects.", desc, ctx.reply.clientAction.reply.payload.type_url, ctx.effects.length);
      } else {
        ctx.commandDebug("%s no reply with %d side effects.", desc, ctx.effects.length);
      }

      const reply = createReply(ctx.reply);
      if (reply !== undefined) {
        this.call.write(reply);
      }
    }
  }

  commandDebug(msg, ...args) {
    this.debug("%s [%s] (%s) - " + msg, ...[this.streamId, this.entityId].concat(args));
  }

  createContext(commandId) {
    const accessor = {};

    accessor.commandDebug = (msg, ...args) => {
      this.commandDebug(msg, ...[commandId].concat(args));
    };

    accessor.commandId = commandId;
    accessor.effects = [];
    accessor.active = true;
    accessor.ensureActive = () => {
      if (!accessor.active) {
        throw new Error("Command context no longer active!");
      }
    };
    accessor.error = null;
    accessor.forward = null;

    accessor.context = {
      entityId: this.entityId,
      commandId: commandId,
      effect: (method, message, synchronous = false) => {
        accessor.ensureActive();
        accessor.effects.push(this.serializeSideEffect(method, message, synchronous))
      },
      thenForward: (method, message) => {
        accessor.ensureActive();
        accessor.forward = this.serializeEffect(method, message);
      },
      fail: (msg) => {
        accessor.ensureActive();
        // We set it here to ensure that even if the user catches the error, for
        // whatever reason, we will still fail as instructed.
        accessor.error = new ContextFailure(msg);
        // Then we throw, to end processing of the command.
        throw error;
      },
    };
    return accessor;
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

    const service = this.allEntities[serviceName];

    if (service !== undefined) {
      const command = service.methods[commandName];
      if (command !== undefined) {
        const payload = AnySupport.serialize(command.resolvedRequestType.create(message), false, false);
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

};