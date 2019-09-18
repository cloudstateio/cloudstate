# Getting started with stateful services in Go

## Prerequisites

Go version
: CloudState Go support requires at least Go $cloudstate.go.version$

Build tool
: CloudState does not require any particular build tool, you can select your own.

protoc
: Since CloudState is based on gRPC, you need a protoc compiler to compile gRPC protobuf descriptors. While this can be done by downloading, installing and running protoc manually.

docker
: CloudState runs in Kubernetes with [Docker](https://www.docker.com), hence you will need Docker to build a container that you can deploy to Kubernetes. Most popular build tools have plugins that assist in building Docker images.

In addition to the above, you will need to install the CloudState Go support library by issuing `go get -u cloudstate.io/cloudstate` or with module support let the dependency downloaded by `go [build|run]`. 

By using the Go module support your go.mod file will reference the latest version of the support library or you can define which version you like to use.

go get
: @@@vars
```text
go get -u cloudstate.io/cloudstate
```
@@@

import path
: @@@vars
```text
import "cloudstate.io/cloudstate"
```
@@@

go.mod
: @@@vars
```
module example.com/yourpackage
    require (
        cloudstate.io/cloudstate $cloudstate.go-support.version$
    )
go $cloudstate.go.version$
```
@@@

## Protobuf files

The CloudState Go Support Library provides no dedicated tool beside the protoc compiler to build your protobuf files. The CloudState protocol protobuf files as well as the shopping cart example application protobuf files are provided by the CloudState Protocol Repository (TODO: whats this?).

In addition to the protoc compiler, the gRPC Go plugin is needed to generate *.pb.go files. Please follow the instructions at the [Go support for Protocol Buffers](https://github.com/golang/protobuf) project page to install the protoc compiler as well as the `protoc-gen-go` plugin which also includes the Google standard protobuf types.

To build the example shopping cart application shown earlier in @ref:[gRPC descriptors](../../features/grpc.md), you could simply paste that protobuf into `protos/shoppingcart.proto`. You may wish to also define the Go package using the `go_package` proto option, to ensure the package name used conforms to Go package naming conventions:

```proto
option go_package = "example.com/shoppingcart";
```

Now if you place your protobuf files under protos/ and run `protoc --go_out=. *.proto`, you'll find your generated protobuf files in `TODO`.

## Creating and starting a server

## Creating a main package

Your main package will be responsible for creating the CloudState gRPC server, registering the entities for it to serve, and starting it. To do this, you can use the CloudState server type, for example:

@@snip [shoppingcart.go](/docs/src/test/go/docs/user/eventsourced/shoppingcart.go) { #shopping-cart-main }

We will see more details on registering entities in the coming pages.

## Interfaces to be implemented

CloudState entities in Go work by implementing interfaces and composing types. 

To get support for the CloudState event emission the CloudState entity should embed the `cloudstate.EventEmitter` type. The EventEmitter allows the entity to emit events during the handling of commands.

Second, by implementing the `EntityInitializer` interface with its method `New() interface{}`, a CloudState instance gets to know how to create and initialize an event sourced entity.

@@snip [shoppingcart.go](/docs/src/test/go/docs/user/eventsourced/shoppingcart.go) { #compose-entity }
