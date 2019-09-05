#!/usr/bin/env node

const path = require("path");
const { execFileSync } = require('child_process');
const fs = require("fs");

const protoc = path.join(__dirname, "..", "protoc", "bin", "protoc" + (process.platform === "win32" ? ".exe" : ""));
const builtInPaths = [
  path.join(__dirname, "..", "proto"),
  path.join(__dirname, "..", "protoc", "include")
];

const userArgs = process.argv.slice(2);

const protocArgs = [
  "--include_imports"
].concat(builtInPaths.map(path => "--proto_path=" + path));

if (!userArgs.some(arg => arg.startsWith("--descriptor_set_out"))) {
    protocArgs.push("--descriptor_set_out=user-function.desc");
}

// We need to ensure that the files passed in is on the proto path. The user may have already ensured this by passing
// their own proto path, but if not, we detect that, and add the files parent directory as a proto path.
const filesToCompile = userArgs.filter(arg => !arg.startsWith("-") && !arg.startsWith("@"));
const protoPaths = userArgs
  .filter(arg => arg.startsWith("-I") || arg.startsWith("--proto_path="))
  .map(arg => {
    if (arg.startsWith("-I")) {
      return arg.substring(2);
    } else {
      return arg.substring("--proto_path=".length);
    }
  });
const notOnProtoPath = filesToCompile.filter(file => {
  // We mark this file as not on the proto path if it doesn't start with any of the passed in proto paths,
  // and it exists.
  return protoPaths.findIndex(p => file.startsWith(p + path.sep)) === -1 &&
    fs.existsSync(file);
});
const additionalProtoPaths = [...new Set(notOnProtoPath.map(file => "--proto_path=" + path.dirname(file)))];
protocArgs.push(...additionalProtoPaths);

protocArgs.push(...userArgs);

console.log("Compiling descriptor with command: " + protoc + " " + protocArgs.join(" "));
execFileSync(protoc, protocArgs);