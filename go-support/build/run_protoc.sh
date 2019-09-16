#!/usr/bin/env bash

protoc --go_out=plugins=grpc:. --proto_path=./protos/frontend/ --proto_path=./protos/protocol/ --proto_path=./protos/proxy/ --proto_path=./protos/example/ protos/protocol/cloudstate/entity.proto
protoc --go_out=plugins=grpc,paths=source_relative:. --proto_path=./protos/frontend/ --proto_path=./protos/protocol/ --proto_path=./protos/proxy/ --proto_path=./protos/example/ protos/frontend/cloudstate/entity_key.proto
protoc --go_out=plugins=grpc:. --proto_path=./protos/frontend/ --proto_path=./protos/protocol/ --proto_path=./protos/proxy/ --proto_path=./protos/example/ protos/protocol/cloudstate/event_sourced.proto

protoc --go_out=plugins=grpc:. --proto_path=./protos/frontend/ --proto_path=./protos/protocol/ --proto_path=./protos/proxy/ --proto_path=./protos/example/ protos/protocol/cloudstate/function.proto

protoc --go_out=plugins=grpc:. --proto_path=./protos/frontend/ --proto_path=./protos/protocol/ --proto_path=./protos/proxy/ --proto_path=./protos/example/ protos/example/shoppingcart/shoppingcart.proto
protoc --go_out=plugins=grpc:. --proto_path=./protos/frontend/ --proto_path=./protos/protocol/ --proto_path=./protos/proxy/ --proto_path=./protos/example/ protos/example/shoppingcart/persistence/domain.proto