# Proxy Sidecar GraalVM Native Image

## GraalVM Installation

* Download and install GraalVM 19.1.1 CE
* set the GRAALVM_HOME and GRAALVM_VERSION ENV vars:
  Example for MacOS:
    export GRAALVM_VERSION=graalvm-ce-19.1.1
    export GRAALVM_HOME=<installation-parent-dir>/$GRAALVM_VERSION/Contents/Home

## Building the native image

Switch to GraalVM 19.1.1 as your current JRE, and add its binaries (in /bin) to $PATH. You *MUST* do this otherwise you'll get weird warnings since the GraalVM Substitution mechanism won't work.

Your `java -version` should report something like:

```bash
openjdk version "1.8.0_222"
OpenJDK Runtime Environment (build 1.8.0_222-20190711112007.graal.jdk8u-src-tar-gz-b08)
OpenJDK 64-Bit GraalVM CE 19.1.1 (build 25.222-b08-jvmci-19.1-b01, mixed mode)
```

Also, verify that you've added GraalVM correctly by checking that `native-image` is available as a command.

Then either start creating the binary with the in-memory storage:

```bash
sbt proxy-core/graalvm-native-image:packageBin
```

or the Cassandra-client based storage binary:

```bash
sbt proxy-cassandra/graalvm-native-image:packageBin
```

Example output:

```bash
…
[info] [cloudstate-proxy-core:34956]    classlist:  23,867.63 ms
[info] [cloudstate-proxy-core:34956]        (cap):   1,402.66 ms
[info] [cloudstate-proxy-core:34956]        setup:   3,183.02 ms
[info] [cloudstate-proxy-core:34956]   (typeflow): 106,110.90 ms
[info] [cloudstate-proxy-core:34956]    (objects):  62,919.78 ms
[info] [cloudstate-proxy-core:34956]   (features):   8,452.19 ms
[info] [cloudstate-proxy-core:34956]     analysis: 185,664.30 ms
[info] [cloudstate-proxy-core:34956]     (clinit):   4,669.41 ms
[info] [cloudstate-proxy-core:34956]     universe:   7,296.91 ms
[info] [cloudstate-proxy-core:34956]      (parse):   9,460.94 ms
[info] [cloudstate-proxy-core:34956]     (inline):  11,308.08 ms
[info] [cloudstate-proxy-core:34956]    (compile):  43,680.43 ms
[info] [cloudstate-proxy-core:34956]      compile:  68,467.83 ms
[info] [cloudstate-proxy-core:34956]        image:   5,779.23 ms
[info] [cloudstate-proxy-core:34956]        write:   1,930.98 ms
[info] [cloudstate-proxy-core:34956]      [total]: 296,677.26 ms
[success] Total time: 304 s, completed Aug 6, 2019 4:00:02 PM
```

The executable generated is located here:
*../cloudstate/proxy/core/target/graalvm-native-image/./cloudstate-proxy-core*

## Running a generated executable

The binary will have to dynamically link to a *SunEC* provider, and needs to source it either from the present working dir, or via the **java.library.path**, this is achieved by passing in the following property when executing the binary:  *-Djava.library.path=<path-to-JRE>/lib*

Example: **-Djava.library.path=/Library/Java/JavaVirtualMachines/adoptopenjdk-11.jdk/Contents/Home/lib**

Supplying the runtime configuration, for the simplest experience, you can give it the pre-packaged dev-mode.conf, example: *-Dconfig.resource=dev-mode.conf*

Full example of running the in-memory storage executable: 

```bash
cloudstate/proxy/core/target/graalvm-native-image/./cloudstate-proxy-core -Djava.library.path=/Library/Java/JavaVirtualMachines/adoptopenjdk-11.jdk/Contents/Home/lib -Dconfig.resource=dev-mode.conf
```

Or with the Cassandra client storage:

```bash
cloudstate/proxy/cassandra/target/graalvm-native-image/./cloudstate-proxy-cassandra -Djava.library.path=/Library/Java/JavaVirtualMachines/adoptopenjdk-11.jdk/Contents/Home/lib
```