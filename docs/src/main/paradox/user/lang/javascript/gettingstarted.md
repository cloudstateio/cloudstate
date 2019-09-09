# Getting started with stateful services in JavaScript

## Prerequisites

Node version
: CloudState uses the [grpc node package](https://github.com/grpc/grpc-node), which compiles a native gRPC implementation using node-gyp. It requires a minimum of node 4.0.

Build tool
: CloudState doesn't require any particular build tool, though npm install scripts do need to be run.

protoc
: CloudState requires using the protoc compiler to serialize your protobuf definitions into the protobuf binary descriptor format. Helper scripts are provided to automatically download the protoc compiler for your platform and do this compilation.

docker
: CloudState runs in Kubernetes with [Docker], hence you will need Docker to build a container that you can deploy to Kubernetes.

Once you have the above, you need to add the `cloudstate` package to your project, which can be done by running:

```
npm install cloudstate --save
```

## Generating the protobuf descriptor set

A CloudState user function needs to report to the CloudState proxy the gRPC descriptor that its serving, serialized to binary using the Protobuf [`FileDescriptorSet` message type](https://developers.google.com/protocol-buffers/docs/techniques#self-description). While there is some support for this in the [protobufjs](https://www.npmjs.com/package/protobufjs) library that is used by CloudState to load descriptors, it is somewhat incomplete and buggy. Hence, CloudState requires that you precompile this descriptor using `protoc` instead.

CloudState provides a utility that does this for you, downloading the `protoc` binary for your platform, and running it with the necessary arguments and include paths. This can be run manually by running `node_modules/cloudstate/bin/compile-descriptor.js`, or we recommend adding it as a `prestart` script to your npm build:

```json
{
  "scripts": {
    "prestart": "compile-descriptor my-descriptor.proto"
  }
}
```

Multiple protobuf files can be passed, in addition, any arguments accepted by `protoc` can be passed, for example, if you are importing files from other directories, you can add those directories as an include path by adding `-Ipath/to/protobuf/dir`.

By default, the descriptor is written to `user-function.desc`, if you wish to change this, you can set `--descriptor_set_out=my-descriptor.desc`. Note that if you output the descriptor to a different path, you will also need to pass that custom path to the constructor of the `CloudState` class when you got to instantiate it.

## package.json example

A minimal `package.json` for a shopping cart example is shown below:

@@@vars
```json
{
  "name": "shopping-cart",
  "version": "0.1.0",
  "dependencies": {
    "cloudstate": "^$cloudstate.node-support.version$"
  },
  "scripts": {
    "prestart": "compile-descriptor shoppingcart.proto",
    "start": "node index.js"
  }
}
```
@@@

## Protobuf files

You can place protobuf files in your project wherever you like, for example, in the root directory, or in a directory named `protos`. In the `package.json` above we've placed the shopping cart application example shown earlier in @ref:[gRPC descriptors](../../features/grpc.md) in a file in the root folder called `shoppingcart.proto`.

## Creating and starting a server

There are two ways to create and start a CloudState gRPC server. The first is to create an @extref:[`Entity`](jsdoc:Entity.html), and invoke @extref:[`start`](jsdoc:Entity.html#start) on it. This allows creating a server that serves a single entity, with the default options. We'll look at this more in the subsequent pages. Alternatively, you can use the @extref:[`CloudState`](jsdoc:CloudState.html) class, add one or more entities to it, and then invoke @extref:[`start`](jsdoc:CloudState.html#start), like so:

@@snip [index.js](/docs/src/test/js/test/gettingstarted/index.js) { #start }

If you created your protobuf file descriptor set at a different location to the default of `user-function.desc`, you can configure that here:

@@snip [index.js](/docs/src/test/js/test/gettingstarted/index.js) { #custom-desc }


For the full range of options available on the `CloudState` class, see @extref:[`CloudState~options`](jsdoc:CloudState.html#~options).

