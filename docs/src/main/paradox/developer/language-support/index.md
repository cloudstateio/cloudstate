# Language support

CloudState is a polyglot platform for developers.
This is achieved by having a gRPC based protocol between the Proxy and the User function. Since every language wants to be able to offer a language-idiomatic Application Programming Interface (API) CloudState offers Support libraries for the following languages:

## Current language support libraries

* Java
* JavaScript

## Creating language support libraries

In order to implement a Support Library for a language, the CloudState protocol needs to be implemented as a gRPC server which is started when a Stateful Service is started. This gRPC server will then relay state and commands to the underlying User function.

When implementing a Support Library, the implementation can be verified using the TCK. In order to run the TCK, one must create an implementation of the Shopping Cart application using the newly created Support Library which is to be verified.

After that, the TCK [application.conf](https://github.com/cloudstateio/cloudstate/blob/master/tck/src/it/resources/application.conf) needs to be modified with a new section to instruct what combination of Proxy implementation will be verified against which implementation of the Shopping Cart application.

Then in order to run the TCK you need to instruct sbt to run the TCK, but make sure that you have built the Shopping Cart application first.

```bash
// This means: "sbt please run the integration tests for the TCK project"
sbt tck/it:test
```

The output of that indicates if the combination of Proxy and User Support libraries yield the expected results.

@@@ index

* [Serialization](serialization.md)

@@@