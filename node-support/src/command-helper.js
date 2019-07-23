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
    const commandDebug = (msg, ...args) => {
      this.debug("%s [%s] (%s) - " + msg, ...[this.streamId, this.entityId, command.id].concat(args));
    };

    if (!this.service.methods.hasOwnProperty(command.name)) {
      commandDebug("Command '%s' unknown", command.name);
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

        const handler = this.handlerFactory(command.name);

        if (handler !== null) {

          const effects = [];

          let active = true;
          const ensureActive = () => {
            if (!active) {
              throw new Error("Command context no longer active!");
            }
          };

          let error = null;
          const reply = {};
          let userReply = null;
          let forward = null;

          const ctx = {
            entityId: this.entityId,
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
              ensureActive();
              forward = this.serializeEffect(method, message);
            }
          };

          try {
            userReply = handler(deserCommand, ctx, reply, ensureActive, commandDebug);
          } catch (err) {
            if (error === null) {
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

            reply.commandId = command.id;
            reply.sideEffects = effects;

            if (forward !== null) {
              reply.forward = forward;
              commandDebug("Sending forward to %s.%s with %d side effects.", forward.serviceName, forward.commandName, effects.length);
            } else {
              reply.reply = {
                payload: AnySupport.serialize(grpcMethod.resolvedResponseType.create(userReply), false, false)
              };
              commandDebug("Sending reply with type [%s] with %d side effects.", reply.reply.payload.type_url, effects.length);
            }

            this.call.write({
              reply: reply
            });
          }

        } else {
          const msg = "No handler registered for command '" + command.name + "', we have: " + Object.keys(this.entity.commandHandlers);
          commandDebug(msg);
          this.call.write({
            failure: {
              commandId: command.id,
              description: msg
            }
          })
        }
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

}