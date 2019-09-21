#!/usr/bin/env bash

set -e

apt-get update -y && apt-get install unzip -y
curl -L https://github.com/google/protobuf/releases/download/v3.9.1/protoc-3.9.1-linux-x86_64.zip -o /tmp/protoc-3.9.1-linux-x86_64.zip
unzip /tmp/protoc-3.9.1-linux-x86_64.zip -d "$HOME"/protoc
# set GOOS and GOARCH to host values for this one line as protoc-gen-go has to be compiled.
GOOS=linux GOARCH=amd64 go get -u github.com/golang/protobuf/protoc-gen-go

cd go-support
PATH=$HOME/protoc/bin:/$GOPATH/bin:$PATH ./build/compile_protobufs.sh
