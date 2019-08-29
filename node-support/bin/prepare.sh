#!/bin/bash

# Prepare script for the cloudstate package

# Delete and recreate the proto directory
rm -rf ./proto
mkdir -p ./proto
cp -r ../protocols/frontend/* ../protocols/protocol/* ./proto/

# Generate the protobuf bundle and typescript definitions
pbjs -t static-module -w commonjs -o ./proto/protobuf-bundle.js -p ./proto -p ./protoc/include ./proto/cloudstate/*.proto
pbjs -t static-module -p ./proto -p ./protoc/include proto/cloudstate/*.proto | pbts -o ./proto/protobuf-bundle.d.ts -