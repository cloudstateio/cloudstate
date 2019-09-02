# Language support

CloudState is a polyglot platform for developers.
This is achieved by having a gRPC based @ref:[protocol](#protocol) between the @ref:[Proxy](#proxy) and the @ref:[User function](#user-function). Since every language wants to be able to offer a language-idiomatic Application Programming Interface (API) CloudState offers @ref:[Support libraries](#support-library) for the following languages:

## Current language support libraries

* @ref:[Java](#java)
* @ref:[JavaScript](#javascript)

## Creating language support libraries

In order to implement a @ref:[Support Library](#support-library) for a language, the CloudState protocol needs to be implemented as a gRPC server which is started when a @ref:[Stateful Service](#stateful-service) is started. This gRPC server will then relay state and commands to the underlying @ref:[User function](#user-function).

When implementing a @ref:[Support Library](#support-library), the implementation can be verified using the TCK. In order to run the TCK, one must create an implementation of the Shopping Cart application using the newly created @ref:[Support Library](#support-library) which is to be verified.

After that, the TCK [application.conf](https://github.com/cloudstateio/cloudstate/blob/master/tck/src/it/resources/application.conf) needs to be modified with a new section to instruct what combination of @ref:[Proxy](#proxy) implementation will be verified against which implementation of the Shopping Cart application.

Then in order to run the TCK you need to instruct sbt to run the TCK, but make sure that you have built the Shopping Cart application first.

```bash
// This means: "sbt please run the integration tests for the TCK project"
sbt tck/it:test
```

The output of that indicates if the combination of Proxy and User Support libraries yield the expected results.