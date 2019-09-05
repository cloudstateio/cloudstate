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
const EventSourcedServices = require("./eventsourced-support");
const CloudState = require("./cloudstate");

const eventSourcedServices = new EventSourcedServices();

/**
 * An event sourced command handler.
 *
 * @callback module:cloudstate.EventSourced~commandHandler
 * @param {Object} command The command message, this will be of the type of the gRPC service call input type.
 * @param {module:cloudstate.Serializable} state The entity state.
 * @param {module:cloudstate.EventSourced.EventSourcedCommandContext} context The command context.
 * @returns {undefined|Object} The message to reply with, it must match the gRPC service call output type for this
 * command.
 */

/**
 * An event sourced event handler.
 *
 * @callback module:cloudstate.EventSourced~eventHandler
 * @param {module:cloudstate.Serializable} event The event.
 * @param {module:cloudstate.Serializable} state The entity state.
 * @returns {module:cloudstate.Serializable} The new entity state.
 */

/**
 * An event sourced entity behavior.
 *
 * @typedef module:cloudstate.EventSourced~behavior
 * @property {Object<String, module:cloudstate.EventSourced~commandHandler>} commandHandlers The command handlers.
 *
 * The names of the properties must match the names of the service calls specified in the gRPC descriptor for this
 * event sourced entities service.
 * @property {Object<String, module:cloudstate.EventSourced~eventHandler>} eventHandlers The event handlers.
 *
 * The names of the properties must match the short names of the events.
 */

/**
 * An event sourced entity behavior callback.
 *
 * This callback takes the current entity state, and returns a set of handlers to handle commands and events for it.
 *
 * @callback module:cloudstate.EventSourced~behaviorCallback
 * @param {module:cloudstate.Serializable} state The entity state.
 * @returns {module:cloudstate.EventSourced~behavior} The new entity state.
 */

/**
 * Initial state callback.
 *
 * This is invoked if the entity is started with no snapshot.
 *
 * @callback module:cloudstate.EventSourced~initialCallback
 * @param {string} entityId The entity id.
 * @returns {module:cloudstate.Serializable} The entity state.
 */

/**
 * Options for an event sourced entity.
 *
 * @typedef module:cloudstate.EventSourced~options
 * @property {string} [persistenceId="entity"] A persistence id for all event source entities of this type. This will be prefixed
 * onto the entityId when storing the events for this entity.
 * @property {number} [snapshotEvery=100] A snapshot will be persisted every time this many events are emitted.
 * @property {array<string>} [includeDirs=["."]] The directories to include when looking up imported protobuf files.
 * @property {boolean} [serializeAllowPrimitives=false] Whether serialization of primitives should be supported when
 * serializing events and snapshots.
 * @property {boolean} [serializeFallbackToJson=false] Whether serialization should fallback to using JSON if an event
 * or snapshot can't be serialized as a protobuf.
 */

/**
 * An event sourced entity.
 *
 * @memberOf module:cloudstate
 * @extends module:cloudstate.Entity
 */
class EventSourced {

  /**
   * Create a new event sourced entity.
   *
   * @param {string|string[]} desc A descriptor or list of descriptors to parse, containing the service to serve.
   * @param {string} serviceName The fully qualified name of the service that provides this entities interface.
   * @param {module:cloudstate.EventSourced~options=} options The options for this event sourced entity
   */
  constructor(desc, serviceName, options) {

    this.options = {
      ...{
        persistenceId: "entity",
        snapshotEvery: 100,
        includeDirs: ["."],
        serializeAllowPrimitives: false,
        serializeFallbackToJson: false
      },
      ...options
    };

    const allIncludeDirs = protobufHelper.moduleIncludeDirs
      .concat(this.options.includeDirs);

    this.root = protobufHelper.loadSync(desc, allIncludeDirs);

    this.serviceName = serviceName;
    // Eagerly lookup the service to fail early
    this.service = this.root.lookupService(serviceName);

    if(!fs.existsSync("user-function.desc"))
      throw new Error("No 'user-function.desc' file found in application root folder.");

    const packageDefinition = protoLoader.loadSync(desc, {
      includeDirs: allIncludeDirs
    });
    this.grpc = grpc.loadPackageDefinition(packageDefinition);
  }

  entityType() {
    return eventSourcedServices.entityType();
  }

  /**
   * Lookup a protobuf message type.
   *
   * This is provided as a convenience to lookup protobuf message types for use with events and snapshots.
   *
   * @param {string} messageType The fully qualified name of the type to lookup.
   */
  lookupType(messageType) {
    return this.root.lookupType(messageType);
  }

  /**
   * The initial state callback.
   *
   * @member module:cloudstate.EventSourced#initial
   * @type module:cloudstate.EventSourced~initialCallback
   */

  /**
   * Set the initial state callback.
   *
   * @param {module:cloudstate.EventSourced~initialCallback} callback The initial state callback.
   * @return {module:cloudstate.EventSourced} This entity.
   */
  setInitial(callback) {
    this.initial = callback;
    return this;
  }

  /**
   * The behavior callback.
   *
   * @member module:cloudstate.EventSourced#behavior
   * @type module:cloudstate.EventSourced~behaviorCallback
   */

  /**
   * Set the behavior callback.
   *
   * @param {module:cloudstate.EventSourced~behaviorCallback} callback The behavior callback.
   * @return {module:cloudstate.EventSourced} This entity.
   */
  setBehavior(callback) {
    this.behavior = callback;
    return this;
  }

  register(allEntities) {
    eventSourcedServices.addService(this, allEntities);
    return eventSourcedServices;
  }

  start(options) {
    if (this.server !== undefined) {
      throw new Error("Server already started!")
    }
    this.server = new CloudState();
    this.server.addEntity(this);

    return this.server.start(options);
  }

  shutdown() {
    if (this.server === undefined) {
      throw new Error("Server not started!")
    }
    this.server.shutdown();
    delete this.server;
  }

}

module.exports = EventSourced;