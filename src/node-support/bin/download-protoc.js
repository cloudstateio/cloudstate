#!/usr/bin/env node

const request = require("request");
const unzipper = require("unzipper");
const mkdirp = require("mkdirp");
const path = require("path");
const fs = require("fs");

const downloadUrlPrefix = "https://github.com/protocolbuffers/protobuf/releases/download/v";
const protocVersion = "3.8.0";
function makeDownloadUrl(platformArch) {
  return downloadUrlPrefix + protocVersion + "/protoc-" + protocVersion + "-" + platformArch + ".zip";
}
function determineDownloadUrl() {
  switch (process.platform) {
    case "linux":
      switch (process.arch) {
        case "arm64":
          return makeDownloadUrl("linux-aarch_64");
        case "ppc64":
          return makeDownloadUrl("linux-ppcle_64");
        case "x32":
          return makeDownloadUrl("linux-x86_32");
        case "x64":
          return makeDownloadUrl("linux-x86_64");
      }
      break;
    case "win32":
      switch (process.arch) {
        case "x32":
          return makeDownloadUrl("win32");
        case "x64":
          return makeDownloadUrl("win64");
      }
      break;
    case "darwin":
      switch (process.arch) {
        case "x32":
          return makeDownloadUrl("osx-x86_32");
        case "x64":
          return makeDownloadUrl("osx-x86_64");
      }
      break;
  }
  throw new Error("There is no protoc compiler available for the current platform/arch combination: " + process.platform + "/" + process.arch)
}

const protocBin = path.join(__dirname, "..", "protoc", "bin", "protoc" + (process.platform === "win32" ? ".exe" : ""));
const downloadUrl = determineDownloadUrl();

console.log("Downloading protoc from " + downloadUrl);

request(downloadUrl)
  .pipe(unzipper.Parse())
  .on("entry", function(entry) {
    const extractPath = path.join(__dirname, "..", "protoc", entry.path);
    const extractDirectory = "Directory" === entry.type ? extractPath : path.dirname(extractPath);

    mkdirp(extractDirectory, function(err) {
      if (err) throw err;
      if ("File" === entry.type) {
        entry.pipe(fs.createWriteStream(extractPath))
          .on("finish", function() {
            if (protocBin === extractPath) {
              fs.chmod(extractPath, 0o755, function(err) {
                if (err) throw err;
              });
            }
          });
      }
    });
  });
