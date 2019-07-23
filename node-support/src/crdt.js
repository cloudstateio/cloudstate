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
  }

  entityType() {
    return crdtServices.entityType();
  }

  lookupType(messageType) {
    return this.root.lookupType(messageType);
  }

  setCommandHandlers(handlers) {
    this.commandHandlers = handlers;
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