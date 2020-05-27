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
:   <!-- allow empty lines in code block below: https://github.com/lightbend/paradox/issues/161 -->

    @@@vars
    ```xml
    <?xml version="1.0" encoding="UTF-8"?>
    <project xmlns="http://maven.apache.org/POM/4.0.0"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
        <modelVersion>4.0.0</modelVersion>

        <groupId>io.cloudstate</groupId>
        <artifactId>shopping-cart</artifactId>
        <version>0.4.3</version>

        <properties>
            <main.class>com.example.shoppingcart.MainKt</main.class>
            <repo.name>cloudstate/kotlin-shopping-cart</repo.name>
            <tag.version>${project.version}</tag.version>

            <kotlin.version>1.3.61</kotlin.version>
            <kotlin.compiler.jvmTarget>1.8</kotlin.compiler.jvmTarget>
            <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        </properties>

        <dependencies>
            <dependency>
                <groupId>org.jetbrains.kotlin</groupId>
                <artifactId>kotlin-stdlib-jdk8</artifactId>
                <version>${kotlin.version}</version>
            </dependency>

            <dependency>
                <groupId>io.cloudstate</groupId>
                <artifactId>cloudstate-kotlin-support</artifactId>
                <version>$cloudstate.kotlin-support.version$</version>
            </dependency>
        </dependencies>

        <build>
            <sourceDirectory>${project.basedir}/src/main/kotlin</sourceDirectory>
            <testSourceDirectory>${project.basedir}/src/test/kotlin</testSourceDirectory>

            <extensions>
                <extension>
                    <groupId>kr.motd.maven</groupId>
                    <artifactId>os-maven-plugin</artifactId>
                    <version>1.6.0</version>
                </extension>
            </extensions>

            <plugins>
                <plugin>
                    <groupId>org.codehaus.mojo</groupId>
                    <artifactId>build-helper-maven-plugin</artifactId>
                    <executions>
                        <execution>
                            <phase>generate-sources</phase>
                            <goals>
                                <goal>add-source</goal>
                            </goals>
                            <configuration>
                                <sources>
                                    <source>${project.build.directory}/generated-sources/protobuf/java</source>
                                </sources>
                            </configuration>
                        </execution>
                    </executions>
                </plugin>

                <plugin>
                    <groupId>org.jetbrains.kotlin</groupId>
                    <artifactId>kotlin-maven-plugin</artifactId>
                    <version>${kotlin.version}</version>
                    <executions>
                        <execution>
                            <id>compile</id>
                            <goals> <goal>compile</goal> </goals>
                            <configuration>
                                <sourceDirs>
                                    <sourceDir>${project.basedir}/src/main/kotlin</sourceDir>
                                    <sourceDir>${project.basedir}/src/main/java</sourceDir>
                                </sourceDirs>
                            </configuration>
                        </execution>
                        <execution>
                            <id>test-compile</id>
                            <goals> <goal>test-compile</goal> </goals>
                            <configuration>
                                <sourceDirs>
                                    <sourceDir>${project.basedir}/src/test/kotlin</sourceDir>
                                    <sourceDir>${project.basedir}/src/test/java</sourceDir>
                                </sourceDirs>
                            </configuration>
                        </execution>
                    </executions>
                </plugin>

                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.5.1</version>
                    <configuration>
                        <source>1.8</source>
                        <target>1.8</target>
                    </configuration>
                    <executions>
                        <!-- Replacing default-compile as it is treated specially by maven -->
                        <execution>
                            <id>default-compile</id>
                            <phase>none</phase>
                        </execution>
                        <!-- Replacing default-testCompile as it is treated specially by maven -->
                        <execution>
                            <id>default-testCompile</id>
                            <phase>none</phase>
                        </execution>
                        <execution>
                            <id>java-compile</id>
                            <phase>compile</phase>
                            <goals> <goal>compile</goal> </goals>
                        </execution>
                        <execution>
                            <id>java-test-compile</id>
                            <phase>test-compile</phase>
                            <goals> <goal>testCompile</goal> </goals>
                        </execution>
                    </executions>
                </plugin>

                <plugin>
                    <groupId>org.xolstice.maven.plugins</groupId>
                    <artifactId>protobuf-maven-plugin</artifactId>
                    <version>0.6.1</version>
                    <configuration>
                        <protocExecutable>/usr/local/bin/protoc</protocExecutable>
                        <protocArtifact>com.google.protobuf:protoc:3.9.1:exe:${os.detected.classifier}</protocArtifact>
                    </configuration>
                    <executions>
                        <execution>
                            <phase>package</phase>
                            <goals>
                                <goal>compile</goal>
                            </goals>
                        </execution>
                    </executions>
                </plugin>

                <plugin>
                    <groupId>com.google.cloud.tools</groupId>
                    <artifactId>jib-maven-plugin</artifactId>
                    <version>1.7.0</version>
                    <executions>
                        <execution>
                            <phase>package</phase>
                            <goals>
                                <goal>build</goal>
                            </goals>
                        </execution>
                    </executions>
                    <configuration>
                        <to>
                            <image>${repo.name}</image>
                            <credHelper></credHelper>
                            <tags>
                                <tag>${tag.version}</tag>
                            </tags>
                        </to>
                        <container>
                            <mainClass>${main.class}</mainClass>
                            <jvmFlags>
                                <jvmFlag>-XX:+UseG1GC</jvmFlag>
                                <jvmFlag>-XX:+UseStringDeduplication</jvmFlag>
                            </jvmFlags>
                            <ports>
                                <port>8080</port>
                            </ports>
                        </container>
                    </configuration>
                </plugin>
            </plugins>
        </build>

    </project>
    ```
    @@@

Gradle Kts
:   <!-- allow empty lines in code block below: https://github.com/lightbend/paradox/issues/161 -->

    @@@vars
    ```kotlin
    import com.google.protobuf.gradle.*

    plugins {
        kotlin("jvm") version "1.3.72"
        id("com.google.protobuf") version "0.8.12"
        id("com.google.cloud.tools.jib") version "2.2.0"
        idea
    }

    repositories {
        mavenLocal()
        mavenCentral()
    }

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
        implementation("io.cloudstate:cloudstate-kotlin-support:$cloudstate.kotlin-support.version$")
        implementation("com.google.api.grpc:proto-google-common-protos:1.17.0")
        implementation("ch.qos.logback:logback-classic:1.2.3")
    }

    protobuf {
        protoc {
            artifact = "com.google.protobuf:protoc:3.9.0"
        }
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    jib {
        from {
            image = "adoptopenjdk/openjdk8-openj9:alpine-slim"
        }
        to {
            image = "${repo.name}"
            tags = setOf(project.version.toString())
        }
        container {
            mainClass = "${main.class}"
            jvmFlags = listOf("-XshareClasses", "-Xquickstart", "-XX:+UseG1GC", "-XX:+UseStringDeduplication")
            ports = listOf("8080")
            }
    }
    ```
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
