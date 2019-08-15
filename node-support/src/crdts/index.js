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

const util = require("util");
const protobufHelper = require("../protobuf-helper");

const GCounter = require("./gcounter");
const PNCounter = require("./pncounter");
const GSet = require("./gset");
const ORSet = require("./orset");
const LWWRegister = require("./lwwregister");
const Flag = require("./flag");
const ORMap = require("./ormap");
const Vote = require("./vote");

const Clocks = protobufHelper.moduleRoot.cloudstate.crdt.CrdtClock;
const Empty = protobufHelper.moduleRoot.google.protobuf.Empty;

/**
 * Instantiate a CRDT for the given wire protobuf state.
 *
 * @param state
 * @returns {Flag|LWWRegister|ORSet|GSet|PNCounter|GCounter|ORMap|Vote}
 */
function createCrdtForState(state) {
  if (state.gcounter) {
    return new GCounter();
  } else if (state.pncounter) {
    return new PNCounter();
  } else if (state.gset) {
    return new GSet();
  } else if (state.orset) {
    return new ORSet();
  } else if (state.lwwregister) {
    // It needs to be initialised with a value
    return new LWWRegister(Empty.create({}));
  } else if (state.flag) {
    return new Flag();
  } else if (state.ormap) {
    return new ORMap();
  } else if (state.vote) {
    return new Vote();
  } else {
    throw new Error(util.format("Unknown CRDT: %o", state))
  }
}

module.exports = {
  createCrdtForState: createCrdtForState,
  GCounter: GCounter,
  PNCounter: PNCounter,
  GSet: GSet,
  ORSet: ORSet,
  LWWRegister: LWWRegister,
  Flag: Flag,
  ORMap: ORMap,
  Vote: Vote,
  Clocks: Clocks
};