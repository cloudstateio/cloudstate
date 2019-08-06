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

const fs = require("fs");
const protobufHelper = require("./protobuf-helper");
const grpc = require("grpc");
const protoLoader = require("@grpc/proto-loader");
const CloudState = require("./cloudstate");
const crdts = require("./crdts");
const support = require("./crdt-support");

const crdtServices = new support.CrdtServices();

class Crdt {

  constructor(desc, serviceName, options) {

    this.options = {
      ...{
        includeDirs: ["."],
      },
      ...options
    };

    const allIncludeDirs = protobufHelper.moduleIncludeDirs
      .concat(this.options.includeDirs);

    this.root = protobufHelper.loadSync(desc, allIncludeDirs);

    this.serviceName = serviceName;
    // Eagerly lookup the service to fail early
    this.service = this.root.lookupService(serviceName);

    if (!fs.existsSync("user-function.desc"))
      throw new Error("No 'user-function.desc' file found in application root folder.");

    const packageDefinition = protoLoader.loadSync(desc, {
      includeDirs: allIncludeDirs
    });
    this.grpc = grpc.loadPackageDefinition(packageDefinition);

    this.onStateSet = (state, entityId) => undefined;
    this.defaultValue = (entityId) => null;
    this.onStateChange = (ctx) => undefined;
    this.onStreamCancelled = (ctx) => undefined;
  }

  entityType() {
    return crdtServices.entityType();
  }

  /**
   * Lookup a Protobuf message type.
   */
  lookupType(messageType) {
    return this.root.lookupType(messageType);
  }

  /**
   * Set the command handlers for this CRDT service.
   *
   * @param handlers An object, with keys equalling the names of the commands (as they appear in the protobuf file)
   *        and values being functions that take the command, and context.
   */
  setCommandHandlers(handlers) {
    this.commandHandlers = handlers;
  }

  /**
   * Set a callback which is invoked anytime the current state of the entity is set.
   *
   * This can be used to initialise the CRDT with transient state, such as a default value generator for ORMap.
   *
   * Events that can trigger this:
   *   - Setting the state manually on the context, via ctx.state = ...
   *   - The proxy pushing the current state on init.
   *   - The proxy pushing a new state to replace the current state.
   *
   * It is not called when deltas are received.
   *
   * @param handler A function that takes the state, and the entity id.
   */
  setOnStateSet(handler) {
    this.onStateSet = handler;
  }

  /**
   * Set a callback for generating the default CRDT when no state is set.
   *
   * This can be used to ensure that command handlers always have a state to work with, eliminating the need for
   * null checks.
   *
   * @param callback A function that takes the current entity id, and returns a CRDT.
   */
  setDefaultValue(callback) {
    this.defaultValue = callback;
  }

  /**
   * Set a callback for handling state change events.
   *
   * This will be invoked whenever there are any active subscribers, and allows updates to be pushed to those
   * subscribers.
   *
   * The CRDT must not be modified by this handler, doing so will trigger an error.
   */
  setOnStateChange(handler) {
    this.onStateChange = handler;
  }

  /**
   * Set a callback for handling stream cancelled events.
   *
   * This will be invoked whenever a streamed call cancels.
   *
   * The CRDT state may be modified by the handler.
   */
  setOnStreamCancelled(handler) {
    this.onStreamCancelled = handler;
  }

  register(allEntities) {
    crdtServices.addService(this, allEntities);
    return crdtServices;
  }

  start(options) {
    const server = new CloudState();
    server.addEntity(this);

    return server.start(options);
  }
}

module.exports = {
  Crdt: Crdt,
  GCounter: crdts.GCounter,
  PNCounter: crdts.PNCounter,
  GSet: crdts.GSet,
  ORSet: crdts.ORSet,
  LWWRegister: crdts.LWWRegister,
  Flag: crdts.Flag,
  Clocks: crdts.Clocks
};