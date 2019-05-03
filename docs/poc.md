# Proof of Concept Implementation

We are actively working on a Proof of Concept for stateful serverless event sourcing. This document describes the PoC, how to run, test and develop it. This document will likely change frequently as the PoC progresses.

## Current state

Currently, we have the Akka stateful serverless event sourcing backend component implemented and a JavaScript user function. The Akka component uses an in memory journal and snapshot store. The JavaScript user function demonstrates a shopping cart.

## Components

The following components exist:

* [Backend](../src/backend/core) - The Akka backend.
* [Node support](../src/node-support) - Node.js support library for writing user functions in Node.js.
* [JavaScript shopping-cart example](../src/samples/js-shopping-cart) - Shopping cart user function implemented in JavaScript.
* [Akka shopping-cart client](../src/samples/akka-js-shopping-cart-client) - Client for the shopping cart user function, implemented using akka-grpc, for testing.

## Running

You will need:

* [JDK8](https://adoptopenjdk.net/)
* [sbt](https://www.scala-sbt.org/)
* [Node.js](https://nodejs.org/) - we've tested with 8.11.4
* [npm](https://www.npmjs.com/get-npm) - these days is bundled with Node.js

You will need multiple terminals to run the different components.

1. From the `src/node-support` directory, run:
    
    ```
    npm install
    ```

    This step is needed because it copies the protobuf files necessary to implement the protocol. Needs to be rerun each time they change.
    
2. From the `src/samples/js-shopping-cart` directory, run:

    ```
    npm install
    npm test
    ```
    
    This downloads the dependencies, as well as runs the tests to ensure the Node.js component is working on your system.
    
3. Start the JavaScript shopping cart user function, from the `src/samples/js-shopping-cart` directory, run:

    ```
    DEBUG=stateserv-event-sourcing node index.js
    ```
    
    This starts the user function listening on port 8080. It's a gRPC service that implements the [stateful serverless event sourcing protocol](../src/backend/core/src/main/proto/protocol.proto). Debug is enabled so you can see the events, commands and replies sent through the protocol.
    
4. In a new terminal, from the `src` directory, run:

    ```
    sbt backend-core/run
    ```

    This starts the Akka backend server running as a single node cluster on port 9000. When it starts up it asks the user function started previously for the gRPC interface that it wants to provide (which in our case is [shoppingcart.proto](../src/samples/js-shopping-cart/proto/shoppingcart.proto)) and dynamically provides an implementation of it, proxying the requests as commands through the stateful serverless event sourcing protocol.

5. In a new terminal, from the `src` directory, run:

    ```
    sbt akka-client/run
    ```
    
    This will start a client and run a few commands, outputting the results. Alternatively, if you wish for a more interactive session, run:
    
    ```
    sbt akka-client/console
    ```
    
    This will drop you into the Scala REPL, and from there you can instantiate the client and use it, eg:
    
    ```scala
    val client = new com.example.Client("localhost", 9000)
    client.addItem("user-1", "123", "Eggs", 2)
    client.addItem("user-1", "456", "Flour", 4)
    client.addItem("user-1", "456", "Flour", 2)
    client.addItem("user-1", "789", "Milk", 1)
    client.addItem("user-1", "789", "Milk", 2)
    client.removeItem("user-1", "456")
    client.getCart("user-1")
    ```
    
    By default, entities will be passivated after 30 seconds of inactivity, so if you wait 30 seconds, then run more commands, you will notice in the debug logs that a new stream is created, snapshots loaded, and events replayed. Snapshotting has been configured to be done every 5 events, which is quite low, but makes it easy to demonstrate it in action.
    
## Points of interest

We'll start with the user function, which can be found in [`src/samples/js-shopping-cart`](../src/samples/js-shopping-cart). The following files are interesting:

* [`shoppingcart.proto`](../src/samples/js-shopping-cart/proto/shoppingcart.proto) - This is the gRPC interface that is exposed to the rest of the world. The user function doesn't implement this directly, it passes it to the Akka backend, that implements it, and then proxies all requests to the user function through an event sourcing specific protocol. Note the use of the `lightbend.serverless.entity_key` field option extension, this is used to indicate which field(s) form the entity key, which the Akka backend will use to identify entities and shard them.
* [`domain.proto`](../src/samples/js-shopping-cart/proto/domain.proto) - These are the protobuf message definitions for the domain events and state. They are used to serialize and deserialize events stored in the journal, as well as being used to store the current state which gets serialized and deserialized as snapshots when snapshotting is used.
* [`shoppingcart.js`](../src/samples/js-shopping-cart/shoppingcart.js) - This is the JavaScript code for implementing the shopping cart entity user function. It defines handlers for events and commands. It uses the `stateful-serverless-event-sourcing` Node.js module to actually implement the event sourcing protocol.

Onto the `stateful-serverless-event-sourcing` Node module, which can be found in [`src/node-support`](../src/node-support). While there's no reason why the user function couldn't implement the event sourcing protocol directly, it is a little low level. This library provides an idiomatic JavaScript API binding to the protocol. It is expected that such a library would be provided for all support languages.

* [`protocol.proto`](../src/backend/core/src/main/proto/protocol.proto) - This is the protocol that is implemented by the library, and invoked by the Akka backend. Commands, replies, events and snapshots are serialized into `google.protobuf.Any` - the command payloads and reply payloads are the gRPC input and output messages, while the event and snapshot payloads are what gets stored to persistence. The `ready` rpc method on the `Entity` service is used by the Akka backend to ask the user function for the gRPC protobuf descriptor it wishes to be served, this uses `google.protobuf.FileDescriptorProto` to serialize the descriptor.
* [`entity.js`](../src/node-support/src/entity.js) - This is the implementation of the protocol, which adapts the protocol to the API used by the user function.

Next we'll take a look at the Akka backend, which can be found in [`src/backend/core`](../src/backend/core).

* [`Serve.scala`](../src/stateful-serverless-backend/src/main/scala/com/lightbend/statefulserverless/Serve.scala) - This provides the dynamically implemented gRPC interface as specified by the user function. Requests are forwarded as commands to the cluster sharded persistent entities.
* [`StateManager.scala`](../src/backend/core/src/main/scala/com/lightbend/statefulserverless/StateManager.scala) - This is an Akka persistent actor that talks to the user function via the event sourcing gRPC protocol.
* [`StatefulServerlessServer.scala`](../src/backend/core/src/main/scala/com/lightbend/statefulserverless/StatefulServerlessServer.scala) - This pulls everything together, starting the Akka gRPC server, cluster sharding, and persistence.

## Deploying to Kubernetes

A version of the Akka backend that uses a Cassandra journal has been provided. To use it, provision Cassandra, for example, as described [here](https://github.com/GoogleCloudPlatform/click-to-deploy/tree/master/k8s/cassandra) on GKE.

You'll need a docker registry that you can deploy to that your Kubernetes cluster can access. Assuming that registry is `gcr.io` with a project name of `stateserv`, run the following to deploy the Docker image for the sidecar and the shopping cart example.

To deploy the Akka backend, from the `src` directory, run:

```
sbt -Ddocker.registry=gcr.io -Ddocker.username=stateserv backend-cassandra/docker:deploy
```

To deploy the shopping cart user function, from the `src` directory, run:

```
docker build -t js-shopping-cart -f Dockerfile.js-shopping-cart .
docker tag js-shopping-cart:latest gcr.io/stateserv/js-shopping-cart:latest
docker push gcr.io/stateserv/js-shopping-cart:latest
```

Now you need to update [`src/samples/js-shopping-cart/deploy.yaml`](../src/samples/js-shopping-cart/deploy.yaml) to match your deployment requirements - update the docker images according to your registry/username, update namespaces accordingly, update the Cassandra contact points environment variable to match your Cassandra, etc. Then `kubectl apply -f deploy.yaml`, and you should have a 3 node cluster running, using Cassandra for persistence.
