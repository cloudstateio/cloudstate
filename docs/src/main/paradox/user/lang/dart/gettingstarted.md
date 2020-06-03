# Getting started with stateful services in Dart

## Prerequisites

### Dart version
Cloudstate Dart support requires Dart sdk >=2.7.0 <3.0.0.

### Build tool
Use Dart [Pub](https://dart.dev/tools/pub/cmd) tool for build Cloudstate Dart projects.

### Protoc
Since Cloudstate is based on gRPC, you need a protoc compiler to compile gRPC protobuf descriptors. While this can be done by downloading, [installing and running protoc manually](https://github.com/protocolbuffers/protobuf#user-content-protocol-compiler-installation). You will also need to add a plugin to compile the proto files for Dart classes.

```shell script
pub global activate protoc_plugin
```

The compiler plugin, protoc-gen-dart, is installed in `$HOME/.pub-cache/bin`. It must be in your PATH for the protocol compiler, protoc, to find it.

```shell script
export PATH=$PATH:$HOME/.pub-cache/bin
```

### Docker
Cloudstate runs in Kubernetes with [Docker](https://www.docker.com/), hence you will need Docker to build a container that you can deploy to Kubernetes. Below is a Dockerfile file with Dart support:

```shell script
FROM google/dart

WORKDIR /app

ADD pubspec.yaml /app/
RUN pub get
ADD . /app
RUN pub get --offline
RUN dart --snapshot-kind=kernel --snapshot=bin/main.dart.snapshot bin/main.dart

CMD []
ENTRYPOINT ["/usr/bin/dart", "--enable-asserts",  "--enable-vm-service:8181", "bin/main.dart.snapshot"]
```

In addition to the above, you will need to install the Cloudstate Dart support library, which can be done as follows:

@@@vars
```yaml
dependencies:
  cloudstate: ^$cloudstate.dart-support.version$
  async: ^2.2.0
  grpc: ^2.1.3
  protobuf: ^1.0.1
```
@@@

## Protobuf files

You will need to create a folder to store the .proto definition files for your project. Create a folder called `protos` at the root of your project.

So, if you were to build the example shopping cart application shown earlier in @ref:[gRPC descriptors](../../features/grpc.md) and assuming you created a project called shopping_cart, you could simply paste that protobuf into `shopping_cart/protos/shoppingcart.proto`.

Now run `protoc` in the root folder of your project to generate Dart files:

```shell script
protoc --include_imports \
     --descriptor_set_out=user-function.desc \
     -I protos/persistence/domain.proto protos/shoppingcart.proto \
     --dart_out=grpc:lib/src/generated
```

@@@ note { title=Important }
Using the `descriptor_set_out = user-function.desc` option exactly as presented is mandatory since the Dart library will look for the `user-function.desc` file at the root of your project during the startup phase.
@@@

## Creating a main function

Your main class will be responsible for creating the Cloudstate gRPC server, registering the entities for your placement and starting it. To do this, you can use the `Cloudstate` class server builder, for example, in the `bin/` folder create a file called `main.dart` with the following content:

@@snip [main.dart](/docs/src/test/dart/docs/user/gettingstarted/main.dart) { #shopping-cart-main }

We will see more details on registering entities in the coming pages.