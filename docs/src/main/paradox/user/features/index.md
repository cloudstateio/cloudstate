# Overview

## High level concepts

### Inversion of state management

In a traditional n-tier architecture, one tier (an application tier) will invoke another tier (a database tier) to retrieve and manipulate its state. This architectural approach has some challenges that grate with the serverless philosophy:

* The application must be aware of the location of the datastore, what technology is being used, how to talk to it etc.
* The application is responsible for handling errors associated with managing state, both infrastructure level failures, as well as domain level errors such as concurrent updates and transactions.

Cloudstate inverts this model. The application code does not call out to the state management system, the state management system calls out to the application code. How to access the data, what technology is being used, etc, becomes 100% the domain of the state management system. Data access errors also become the domain of the state management system - user code never has to concern itself with that. Transactional concerns, such as managing concurrent updates, caching, sharding, routing all become a concern of the state management system, not the user code.

In moving state management concerns out of the domain of user code and into the domain of the infrastructure, we can simplify the code that users need to write, allowing more to be automated, and allowing more in depth monitoring to be achieved out of the box.

To put it another way, when inverting state management, application code does not talk to the database, it's more correct to think of it as the database (or rather, the state management system) talks to the application code. The application code does not make a connection or any calls to the database, the state management system connects to the application code as necessary. The application code does not issue queries for the state it needs, the state management system passes state to the application code as needed to handle commands.

## Glossary of terms

The following diagram illustrates how the different components of a Cloudstate system fit together.

![Diagram showing how different Cloudstate concepts fit together](overview.svg)

### Stateful service

A stateful service is a deployable unit. It is represented in Kubernetes as a `StatefulService` resource. It contains a @ref:[User function](#user-function), and may reference a @ref:[Stateful store](#stateful-store). The Cloudstate operator transforms a stateful service into a Kubernetes Deployment resource. The Cloudstate operator then defines a Kubernetes Service to expose the stateful service to the outside world. The deployment will be augmented by the injection of a Cloudstate @ref:[Proxy](#proxy). 

### Stateful store

A stateful store is an abstraction over a datastore, typically a database. It is represented in Kubernetes as a `StatefulStore` resource. A single store may be used by multiple @ref:[Stateful services](#stateful-service) to store their data, but a stateful service may not necessarily have any store configured - if the stateful service does not have any durable state then it doesn't need a stateful store.

### User function

A user function is the code that the user writes, packaged as a Docker image, and deployed as a @ref:[Stateful service](#stateful-service). The user function exposes a gRPC interface that speaks the Cloudstate @ref:[Protocol](#protocol). The injected Cloudstate @ref:[Proxy](#proxy) speaks to the user function using this protocol. The end-user developer, generally, doesn't need to provide the code implementation of this protocol along with the user function. This burden is delegated to a Cloudstate @ref:[Support library](#support-library), specific to the programming language used to write the user function.

### Protocol

The Cloudstate protocol is an open specification for Cloudstate state management proxies to speak to Cloudstate user functions. The Cloudstate project itself provides a @ref:[Reference implementation](#reference-implementation) of this protocol. The protocol is built upon gRPC, and provides support for multiple different @ref:[Entity types](#entity-type). Cloudstate provides a Technology Compatibility Kit (TCK) that can be used to verify any permutation of @ref:[Proxy](#proxy) and @ref:[Support Library](#support-library) available. More technical details in section [Creating language support libraries](../../developer/language-support/index.html#creating-language-support-libraries)

### Proxy

The Cloudstate proxy is injected as a sidecar into the deployment of each @ref:[Stateful service](#stateful-service). It is responsible for state management, and exposing the @ref:[Entity service](#entity-service) implemented by the @ref:[User function](#user-function) as both gRPC and REST services to the rest of the system, translating the incoming calls to @ref:[Commands](#command) that are sent to the user function using the Cloudstate @ref:[Protocol](#protocol). The proxy will typically form a cluster with other nodes in the same stateful service, allowing advanced distributed state management features such as sharding, replication and addressed communication between nodes of a single stateful service.

### Reference implementation

The Cloudstate reference implementation implements the Cloudstate @ref:[Protocol](#protocol). It is implemented using [Akka](https://akka.io), taking advantage of Akka's cluster features to provide scalable and resilient implementations of Cloudstate's stateful features.

### Support library

While @ref:[User functions](#user-function) can be implemented simply by implementing the gRPC interfaces in the Cloudstate @ref:[Protocol](#protocol), this protocol is somewhat low level, and not particularly well suited for expressing the business logic that typically will reside in a user function. Instead, developers are encouraged to use a Cloudstate support library for their language of choice, if available.

### Command

A command is used to express the intention to alter the state of an @ref:[Entity](#entity). A command is materialized by a message received by a user function. Commands may come from outside of the @ref:[Stateful service](#stateful-service), perhaps from other stateful services, other non Cloudstate services, or the outside world, or they may come from within the service, invoked as a side effect or to forward command handling from another command.

### Entity

A @ref:[User function](#user-function) implements one or more entities. An entity is conceptually equivalent to a class, or a type of state. An entity will have multiple @ref:[instances](#entity-instance) of it which can handle commands. For example, a user function may implement a chat room entity, encompassing the logic associated with chat rooms, and a particular chat room may be an instance of that entity, containing a list of the users currently in the room and a history of the messages sent to it. Each entity has a particular @ref:[Entity type](#entity-type), which defines how the entity's state is persisted, shared, and what its capabilities are.

#### Entity instance

An instance of an @ref:[Entity](#entity). Entity instances are identified by an @ref:[Entity key](#entity-key), which is unique to a given entity. An entity holds state in the @ref:[User function](#user-function), and depending on the @ref:[Entity type](#entity-type) this state is held within the context of a gRPC stream. When a command for a particular entity instance is received, the @ref:[Proxy](#proxy) will make a new streamed gRPC call for that entity instance to the @ref:[User function](#user-function). All subsequent commands received for that entity instance will be sent through that streamed call.

#### Entity service

An entity service is a gRPC service that allows interacting with an @ref:[Entity](#entity). The @ref:[Proxy](#proxy) makes this service available for other Kubernetes services and ingresses to consume, while the @ref:[User function](#user-function) provides the implementation of the entity business logic. Note that the service is not implemented directly, by the user function like a normal gRPC service. Rather, it is implemented through the Cloudstate @ref:[Protocol](#protocol), which enriches the incoming and outgoing gRPC messages with state management capabilities, such as the ability to receive and update state.

#### Entity type

The type of state management that an @ref:[Entity](#entity) uses. Available types include @ref:[Event sourced](#event-sourced) and @ref:[Conflict-free replicated data type](#conflict-free-replicated-data-type). Each type has its own sub protocol as part of the Cloudstate @ref:[Protocol](#protocol) that it uses for state management, to convey state and updates specific to that type.

#### Entity key

A key used to identify instances of an @ref:[Entity](#entity). All @ref:[Commands](#command) must contain the entity key so that the command can be routed to the right instance of the entity that the command is for. The gRPC descriptor for the @ref:[Entity service](#entity-service) annotates the incoming message types for the entity to indicate which field(s) contain the entity key.

### Event sourced

A type of @ref:[Entity](#entity) that stores its state using a journal of events, and restores its state by replaying that journal. These are discussed in more detail in @ref:[Event sourcing](eventsourced.md).

### Conflict-free replicated data type

A type of @ref:[Entity](#entity) that stores its state using a Conflict-free Replicated Data Type (CRDT), which is replicated across different nodes of the service. These are discussed in more detail in @ref:[Conflict-free Replicated Data Types](crdts.md).

@@@ index

* [gRPC descriptors](grpc.md)
* [Event sourcing](eventsourced.md)
* [Conflict-free Replicated Data Types](crdts.md)
* [Forwarding and effects](effects.md)

@@@

