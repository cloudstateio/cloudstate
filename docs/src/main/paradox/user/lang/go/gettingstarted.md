# Getting started with stateful services in Go

## Prerequisites

Go version
: Cloudstate Go support requires at least Go $cloudstate.go.version$

Build tool
: Cloudstate does not require any particular build tool, you can select your own.

protoc
: Since Cloudstate is based on gRPC, you need a protoc compiler to compile gRPC protobuf descriptors. This can be done manually through the [Protocol Buffer Compiler project](https://github.com/protocolbuffers/protobuf#protocol-compiler-installation). 

docker
: Cloudstate runs in Kubernetes with [Docker](https://www.docker.com), hence you will need Docker to build a container that you can deploy to Kubernetes. Most popular build tools have plugins that assist in building Docker images.

In addition to the above, you will need to install the Cloudstate Go support library by issuing `go get -u github.com/cloudstateio/go-support/cloudstate` or with Go module support let the dependency be downloaded by `go [build|run|test]`. 

By using the Go module support your go.mod file will reference the latest version of the support library or you can define which version you like to use.

go get
: @@@vars
```text
go get -u github.com/cloudstateio/go-support/cloudstate
```
@@@

import path
: @@@vars
```text
import "github.com/cloudstateio/go-support/cloudstate"
```
@@@

go.mod
: @@@vars
```
module example.com/yourpackage
    require (
        github.com/cloudstateio/go-support $cloudstate.go-support.version$
    )
go $cloudstate.go.version$
```
@@@

## Protobuf files

The Cloudstate Go Support Library provides no dedicated tool beside the protoc compiler to build your protobuf files. The Cloudstate protocol protobuf files as well as the shopping cart example application protobuf files are provided by the Cloudstate Repository.

In addition to the `protoc` compiler, the gRPC Go plugin is needed to compile the protobuf file to *.pb.go files. Please follow the instructions at the [Go support for Protocol Buffers](https://github.com/golang/protobuf) project page to install the protoc compiler as well as the `protoc-gen-go` plugin which also includes the Google standard protobuf types.

To build the example shopping cart application shown earlier in @ref:[gRPC descriptors](../../features/grpc.md), you could simply paste that protobuf into `protos/shoppingcart.proto`. You may wish to also define the Go package using the `go_package` proto option, to ensure the package name used conforms to Go package naming conventions

```proto
option go_package = "example/shoppingcart";
```

Now if you place your protobuf files under protobuf/ and run `protoc --go_out=. --proto_path=protobuf ./protobuf/*.proto`, you'll find your generated protobuf files in `example/shoppingcart`.

## Creating a main package

Your main package will be responsible for creating the Cloudstate gRPC server, registering the entities for it to serve, and starting it. To do this, you can use the CloudState server type, for example:

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/shoppingcart.go) { #shopping-cart-main }

We will see more details on registering entities in the coming pages.

## Interfaces to be implemented

Cloudstate entities in Go work by implementing interfaces and composing types. 

To get support for the Cloudstate event emission the Cloudstate entity should embed the `cloudstate.EventEmitter` type. The EventEmitter allows the entity to emit events during the handling of commands.

Second, during registration of the entity, an entity factory function has to be provided so Cloudstate gets to know how to create and initialize an event sourced entity. 

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/eventsourced.go) { #event-sourced-entity-func }

This entity factory function returns an `cloudstate.Entity` which itself is a composite interface of a `cloudstate.CommandHandler` and a `cloudstate.EventHandler`. Every event sourced entity has to implement these two interfaces.
