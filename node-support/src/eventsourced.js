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
const fs = require("fs");
const protobuf = require("protobufjs");
const grpc = require("grpc");
const protoLoader = require("@grpc/proto-loader");
const EventSourcedServices = require("./eventsourced-support");
const CloudState = require("./cloudstate");

const eventSourcedServices = new EventSourcedServices();

module.exports = class EventSourced {

  constructor(desc, serviceName, options) {

    this.options = {
      ...{
        persistenceId: "entity",
        snapshotEvery: 100,
        includeDirs: ["."],
      },
      ...options
    };

    const allIncludeDirs = [
      path.resolve(__dirname, "..", "proto"),
      path.resolve(__dirname, "..", "protoc", "include")
    ].concat(this.options.includeDirs);

    this.root = new protobuf.Root();
    this.root.resolvePath = function (origin, target) {
      for (let i = 0; i < allIncludeDirs.length; i++) {
        const directory = allIncludeDirs[i];
        const fullPath = path.resolve(directory, target);
        try {
          fs.accessSync(fullPath, fs.constants.R_OK);
          return fullPath;
        } catch (err) {
        }
      }
      return null;
    };

    this.root.loadSync(desc);
    this.root.resolveAll();

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

  lookupType(messageType) {
    return this.root.lookupType(messageType);
  }

  setInitial(callback) {
    this.initial = callback;
  }

  setBehavior(callback) {
    this.behavior = callback;
  }

  register(allEntities) {
    eventSourcedServices.addService(this, allEntities);
    return eventSourcedServices;
  }

  start(options) {
    const server = new CloudState();
    server.addEntity(this);

    return server.start(options);
  }
};