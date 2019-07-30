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
const path = require("path");
const protobuf = require("protobufjs");

module.exports.loadSync = function(desc, includeDirs) {
  const root = new protobuf.Root();
  root.resolvePath = function (origin, target) {
    for (let i = 0; i < includeDirs.length; i++) {
      const directory = includeDirs[i];
      const fullPath = path.resolve(directory, target);
      try {
        fs.accessSync(fullPath, fs.constants.R_OK);
        return fullPath;
      } catch (err) {
      }
    }
    return null;
  };

  root.loadSync(desc);
  root.resolveAll();
  return root;
};

module.exports.moduleIncludeDirs = [
  path.join(__dirname, "..", "proto"),
  path.join(__dirname, "..", "protoc", "include")
];

module.exports.moduleRoot = require("../proto/protobuf-bundle");