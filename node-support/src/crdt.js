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

/**
 * Options for creating a CRDT entity.
 *
 * @typedef cloudstate.crdt.Crdt~options
 * @property {array<string>} includeDirs The directories to include when looking up imported protobuf files.
 */

/**
 * A command handler callback.
 *
 * @callback cloudstate.crdt.Crdt~commandHandler
 * @param {Object} command The command message, this will be of the type of the gRPC service call input type.
 * @param {cloudstate.crdt.CrdtCommandContext} context The command context.
 * @returns {undefined|Object} The message to reply with, it must match the gRPC service call output type for this
 * command.
 */

/**
 * A state set handler callback.
 *
 * This is invoked whenever a new state is set on the CRDT, to allow the state to be enriched with domain specific
 * properties and methods. This may be due to the state being set explicitly from a command handler on the command
 * context, or implicitly as the default value, or implicitly when a new state is received from the proxy.
 *
 * @callback cloudstate.crdt.Crdt~onStateSetCallback
 * @param {cloudstate.crdt.CrdtState} state The state that was set.
 * @param {string} entityId The id of the entity.
 */

/**
 * A callback that is invoked to create a default value if the CloudState proxy doesn't send an existing one.
 *
 * @callback cloudstate.crdt.Crdt~defaultValueCallback
 * @param {string} entityId The id of the entity.
 * @returns {Object} The default value to use for this entity.
 */

/**
 * A CRDT entity.
 *
 * @memberOf cloudstate.crdt
 * @extends cloudstate.Entity
 */
class Crdt {

  /**
   * Create a CRDT entity.
   *
   * @param desc {string|string[]} The file name of a protobuf descriptor or set of descriptors containing the
   * CRDT service.
   * @param serviceName {string} The fully qualified name of the gRPC service that this CRDT implements.
   * @param options {cloudstate.crdt.Crdt~options=} The options.
   */
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

    /**
     * The command handlers.
     *
     * The names of the properties must match the names of the service calls specified in the gRPC descriptor for this
     * CRDTs service.
     *
     * @type {Object.<string, cloudstate.crdt.Crdt~commandHandler>}
     */
    this.commandHandlers = {};

    /**
     * A callback that is invoked whenever the state is set for this CRDT.
     *
     * This is invoked whenever a new state is set on the CRDT, to allow the state to be enriched with domain specific
     * properties and methods. This may be due to the state being set explicitly from a command handler on the command
     * context, or implicitly as the default value, or implicitly when a new state is received from the proxy.
     *
     * @member {cloudstate.crdt.Crdt~onStateSetCallback} cloudstate.crdt.Crdt#onStateSet
     */
    this.onStateSet = (state, entityId) => undefined;

    /**
     * A callback that is invoked to create a default value if the CloudState proxy doesn't send an existing one.
     *
     * @member {cloudstate.crdt.Crdt~defaultValueCallback} cloudstate.crdt.Crdt#defaultValue
     */
    this.defaultValue = (entityId) => null;
  }

  entityType() {
    return crdtServices.entityType();
  }

  /**
   * Lookup a Protobuf message type.
   *
   * This is provided as a convenience to lookup protobuf message types for use, for example, as values in sets and
   * maps.
   *
   * @param {string} messageType The fully qualified name of the type to lookup.
   */
  lookupType(messageType) {
    return this.root.lookupType(messageType);
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
  ORMap: crdts.ORMap,
  Vote: crdts.Vote,
  Clocks: crdts.Clocks
};