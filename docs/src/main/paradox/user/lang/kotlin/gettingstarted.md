# Getting started with stateful services in Kotlin

## Prerequisites

Kotlin version
: Cloudstate Kotlin support requires at least Java 8, though we recommend using Java 11, which has better support for running in containers. While it is possible to build a GraalVM native image for Cloudstate Kotlin user functions, at this stage Cloudstate offers no specific assistance or configuration to aid in doing that.

Build tool
: Cloudstate does not require any particular build tool, you can select your own. Or use the [Cloudstate CLI](https://github.com/sleipnir/cloudstate-cli)

protoc
: Since Cloudstate is based on gRPC, you need a protoc compiler to compile gRPC protobuf descriptors. While this can be done by downloading, [installing and running protoc manually](https://github.com/protocolbuffers/protobuf#protocol-compiler-installation), most popular build tools have a protoc plugin which will automatically compile protobuf descriptors during your build for you.

docker
: Cloudstate runs in Kubernetes with [Docker](https://www.docker.com/), hence you will need Docker to build a container that you can deploy to Kubernetes. Most popular build tools have plugins that assist in building Docker images.

In addition to the above, you will need to install the Cloudstate Kotlin support library, which can be done as follows:
    
Maven
: @@@vars
```xml
<dependency>
  <groupId>io.cloudstate</groupId>
  <artifactId>cloudstate-kotlin-support</artifactId>
  <version>$cloudstate.kotlin-support.version$</version>
</dependency>
```
@@@
  
sbt
: @@@vars
```scala
libraryDependencies += "io.cloudstate" % "cloudstate-kotlin-support" % "$cloudstate.kotlin-support.version$"
```
@@@

gradle
: @@@vars
```gradle
compile group: 'io.cloudstate', name: 'cloudstate-kotlin-support', version: '$cloudstate.kotlin-support.version$'
```
@@@

kotlin
: @@@vars
```kotlin
dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("io.cloudstate:cloudstate-kotlin-support:$cloudstate.kotlin-support.version$")
    implementation("com.google.api.grpc:proto-google-common-protos:1.17.0")
}
```
@@@

## Simple example

A minimal Kotlin example using the [Google Jib Docker Maven Plugin](https://github.com/GoogleContainerTools/jib/) 
to generate the [Docker](https://www.docker.com/) image for a shopping cart service, is shown below:

Maven
: @@@vars
@@snip [Main.kt](/docs/src/test/kotlin/docs/user/gettingstarted/pom.xml) { #pom-xml }
@@@

Gradle Kts
: @@@vars
@@snip [Main.kt](/docs/src/test/kotlin/docs/user/gettingstarted/build.gradle.kts) { #build.kts }
@@@

@@@ note { title=Important }
Remember to change the values of the **main.class**, **repo.name**, and **version** tags to their respective values
@@@

Subsequent source locations and build commands will assume the above Maven project, and may need to be adapted to your particular build tool and setup.

## Protobuf files

The Xolstice Maven plugin assumes a location of `src/main/proto` for your protobuf files. In addition, it includes any protobuf files from your Kotlin dependencies in the protoc include path, so there's nothing you need to do to pull in either the Cloudstate protobuf types, or any of the Google standard protobuf types, they are all automatically available for import.

So, if you were to build the example shopping cart application shown earlier in @ref:[gRPC descriptors](../../features/grpc.md), you could simply paste that protobuf into `src/main/proto/shoppingcart.proto`. You may wish to also define the Kotlin package, to ensure the package name used conforms to Kotlin package naming conventions:

```proto
option java_package = "com.example.shoppingcart";
```

Now if you run `mvn compile`, you'll find your generated protobuf files in `target/generated-sources/protobuf/java`.

## Creating a main function

Your main class will be responsible for creating the Cloudstate gRPC server, registering the entities for it to serve, and starting it. To do this, you can use the `cloudstate` function server builder, for example:

@@snip [Main.kt](/docs/src/test/kotlin/docs/user/gettingstarted/Main.kt) { #shopping-cart-main }

We will see more details on registering entities in the coming pages.

## Parameter injection

Cloudstate entities work by annotating classes and functions to be instantiated and invoked by the Cloudstate server. The functions and constructors invoked by the server can be injected with parameters of various types from the context of the invocation. For example, an `@CommandHandler` annotated function may take an argument for the message type for that gRPC service call, in addition it may take a `CommandContext` parameter.

Exactly which context parameters are available depend on the type of entity and the type of handler, in subsequent pages we'll detail which parameters are available in which circumstances. The order of the parameters in the function signature can be anything, parameters are matched by type and sometimes by annotation. The following context parameters are available in every context:

| Type                                | Annotation   | Description           |
|-------------------------------------|--------------|-----------------------|
| `io.cloudstate.javasupport.Context` |              | The super type of all Cloudstate contexts. Every invoker makes a subtype of this available for injection, and method or constructor may accept that sub type, or any super type of that subtype that is a subtype of `Context`. |
| `java.lang.String`                  | `@EntityId`  | The ID of the entity. |  


Cloudstate Kotlin support allows you to use both annotations from the java-support library and your own kotlin annotations contained in the io.cloudstate.kotlinsupport.api.* package
