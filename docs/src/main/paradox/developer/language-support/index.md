# Language support

Cloudstate is a polyglot platform for developers.
This is achieved by having a gRPC based protocol between the Proxy and the User function. Since every language wants to be able to offer a language-idiomatic Application Programming Interface (API) Cloudstate offers Support libraries for the following languages:

## Current language support libraries

* Java
* JavaScript
* Go

## Creating language support libraries

In order to implement a Support Library for a language, the Cloudstate protocol needs to be implemented as a gRPC server which is started when a Stateful Service is started. This gRPC server will then relay state and commands to the underlying User function.

To obtain the necessary Cloudstate Protobuf descriptors which needs to be implemented, your build can be set up to fetch the following compressed archives and extract the contents. The archives are available from version `v0.6.0` and forwards.

  * The Cloudstate protocols
      - `https://raw.githubusercontent.com/cloudstateio/cloudstate/<VERSION_TAG>/protocols/cloudstate-protocols.zip`
  * The Cloudstate TCK protocols
      - `https://raw.githubusercontent.com/cloudstateio/cloudstate/<VERSION_TAG>/protocols/cloudstate-tck-protocols.zip`

It is also possible to take a look at the various protobuf messages available in the [project's `protocols` folder](https://github.com/cloudstateio/cloudstate/tree/master/protocols). In this folder, you will see 4 sub-folders divided as follow: 

- `example` is for implementing the example application, which is also used by the TCK for third-party language (and proxy implementation) validation.
- `frontend` is what is used by developers of services when they define their proto interfaces.
- `protocol` is the protocol between the proxy and what we call a Language Support, i.e. a bridge library which speaks with the proxy and exposes a native API for some programming language.
- `proxy` contains the protocols which the proxy itself speaks with the outside world.

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