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

const Empty = protobufHelper.moduleRoot.google.protobuf.Empty;

/**
 * All CRDTs and CRDT support classes.
 *
 * @namespace module:cloudstate.crdt
 */

/**
 * A Conflict-free Replicated Data Type.
 *
 * @interface module:cloudstate.crdt.CrdtState
 */

/**
 * A clock that may be used by {@link module:cloudstate.crdt.LWWRegister}.
 *
 * @typedef module:cloudstate.crdt.Clock
 */

/**
 * An enum of all clocks that can be used by {@link module:cloudstate.crdt.LWWRegister}.
 *
 * @name module:cloudstate.crdt.Clocks
 * @enum {module:cloudstate.crdt.Clock}
 * @property DEFAULT The default clock, uses the machines system time.
 * @property REVERSE A reverse clock, for achieving first-write-wins semantics.
 * @property CUSTOM A custom clock.
 * @property CUSTOM_AUTO_INCREMENT A custom clock that automatically increments if the current clock value
 * is less than the existing clock value.
 */
const Clocks = protobufHelper.moduleRoot.cloudstate.crdt.CrdtClock;

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