# The Cloudstate Build

The Cloudstate Build uses `sbt` as its build tool.

## Installation instructions

* Install the `git` source code manager from [here](https://git-scm.com/).
* Clone the Cloudstate repository using `git`: `git clone git@github.com:cloudstateio/cloudstate.git`
* Install `sbt`, follow [these instructions](https://www.scala-sbt.org/download.html).

To build Native Images, you'll need the GraalVM CE as your JRE and [`clang`](https://clang.llvm.org/) as your `cc`. The GraalVM CE provides the `native-image` command, which must be on the $PATH when building Native Images. See [GraalVM Integration](https://github.com/cloudstateio/cloudstate/blob/master/README.md#graal-integration) in the project README for further info.

To build node.js related samples, you'll need `nvm` to install the corresponding node.js version by running command `nvm install` and `nvm use`, then run `npm install` to install required modules.

To build the documentation using the `paradox` target, you'll need to use `npm` to install required modules from the `node-support` folder with `cd node-support && nvm install && nvm use && npm install && cd ..`

## Getting started

It is possible to run `sbt` either on a command-by-command basis by running `sbt <command> parameters`. It is also possible to run `sbt` in interactive mode by running `sbt`, then you can interact with the build interactively by typing in commands and having them executed by pressing RETURN.

The following is a list of sbt commands and use-cases:

| Command        | Use-case                                     |
| -------------: | -------------------------------------------- |
| projects       | Prints a list of all projects in the build |
| project <NAME> | Makes <NAME> the current project |
| clean          | Deletes all generated files and compilation results for the current project and the projects it depends on |
| compile        | Compiles all non-test sources for the current project and the projects it depends on |
| test:compile   | Compiles all sources for the current project and the projects it depends on |
| test | Executes all "regular" tests for the current project and the projects it depends on |
| it:test | Executes all integration tests for the current project and the projects it depends on |
| exit | Exits the interactive mode of `sbt` |

For more documentation about `sbt`, see [this page](https://www.scala-sbt.org/1.x/docs/index.html).

## Running Cloudstate in Minikube

Cloudstate can be easily run in Minikube. If you wish to use Istio, be aware that it can be quite resource intensive, we recommend not using Istio in development unless you have specific requirements to test with Istio.

### Installing a local build of Cloudstate

To install a local build of Cloudstate to Minikube, first setup your docker environment to use the Minikube Docker registry:

```
eval $(minikube docker-env)
```

Now, select a tag to deploy, we recommend `dev`. Do *not* use `latest`, Kubernetes will default the `imagePullPolicy` to `IfNotPresent`, unless the tag of the image you're using is `latest`, then it will use `Always`. The problem with `Always` is that it means your locally built docker images will never be used, Kubernetes will always attempt a pull first, which will either overwrite whatever you've built locally, or fail if no image with that tag can be found remotely.

Start sbt passing the selected tag as a system property:

```
sbt -Ddocker.tag=dev
```

Now build the operator image:

```
operator/docker:publishLocal
```

Now build one or more proxy images. Cloudstate has a different image for each database backend, and in most cases, is able to build either a native image, or an image that runs a regular JVM. It takes at least 5 minutes to compile the native images, so for most development purposes, we recommend using the regular JVM images. For example, to compile the `InMemory` proxy image, run:

```
dockerBuildInMemory publishLocal
```

The following commands are available:

* `dockerBuildInMemory`
* `dockerBuildNativeInMemory`
* `dockerBuildCassandra`
* `dockerBuildNativeCassandra`
* `dockerBuildNoStore`
* `dockerBuildNativeNoStore`
* `dockerBuildPostgres`
* `dockerBuildNativePostgres`
* `dockerBuildAllNonNative`
* `dockerBuildAllNative`

To each of these, `publishLocal` can be passed which will build the image locally, while `publish` will build and then push the image. If you wish to configure the docker hub username to push to, pass `-Ddocker.username=my-username` to the sbt command. If you wish to configure the Docker registry to push to, pass `-Ddocker.registry=gcr.io` for example to publish to the Google Container Registry. However, for most Minikube based development, there should be no need to push the images anywhere.

Now compile the Kubernetes deployment descriptor:

```
operator/compileK8sDescriptors
```

The task will tell you where it generated the deployment descriptor to. You can now install this in Minikube, first creating a `cloudstate` namespace for it:

```
kubectl create namespace cloudstate
kubectl apply -n cloudstate -f operator/cloudstate-dev.yaml
```

This installs Cloudstate locally. You can tail the logs of the operator by running:

```
kubectl logs -n cloudstate -l app=cloudstate-operator -f
```

There is one more thing that needs to be done, assuming you did not build the native images, the default configuration for the Cloudstate operator is to use the native images. Update the config map it uses to tell it to use the regular JVM images:

```
kubectl edit -n cloudstate configmap cloudstate-operator-config
```

In the config, you should ensure the proxy image configuration looks like this:

```hocon
proxy {
  image {
    cassandra = "cloudstateio/cloudstate-proxy-cassandra:dev"
    postgres =  "cloudstateio/cloudstate-proxy-postgres:dev"
    no-store = "cloudstateio/cloudstate-proxy-no-store:dev"
    in-memory = "cloudstateio/cloudstate-proxy-in-memory:dev"
  }
}
```

Once you have Cloudstate running, you will presumably want to deploy a stateful function to it. If your function needs a stateful store, then either install the necessary database along with a `StatefulStore` descriptor to point to it, or deploy an in memory store if you don't need to test any particular database:

```yaml
apiVersion: cloudstate.io/v1alpha1
kind: StatefulStore
metadata:
  name: inmemory
spec:
  type: InMemory
```

Now you can deploy a function, for example, the shopping cart:

```yaml
apiVersion: cloudstate.io/v1alpha1
kind: StatefulService
metadata:
  name: shopping-cart
spec:
  datastore:
    name: inmemory
  containers:
  - image: cloudstateio/samples-js-shopping-cart:latest
```

The Cloudstate operator should now create the necessary deployment for the shopping cart. There are a few ways it can be accessed, one is to port forward into the pod, but perhaps the simpler way is to create a `NodePort` service for it, by running:

```
kubectl expose deployment shopping-cart-deployment --port=8013 --type=NodePort
```

Now, you can see the hostname/port to access it on by running:

```
$ minikube service shopping-cart-deployment --url
http://192.168.39.186:32121
```

Using a tool like [`grpcurl`](https://github.com/fullstorydev/grpcurl), you can now inspect the services on it:

```
$ ./grpcurl -plaintext 192.168.39.186:32121 describe
com.example.shoppingcart.ShoppingCart is a service:
service ShoppingCart {
  rpc AddItem ( .com.example.shoppingcart.AddLineItem ) returns ( .google.protobuf.Empty ) {
    option (.google.api.http) = { post:"/cart/{user_id}/items/add" body:"*" };
  }
  rpc GetCart ( .com.example.shoppingcart.GetShoppingCart ) returns ( .com.example.shoppingcart.Cart ) {
    option (.google.api.http) = { get:"/carts/{user_id}" additional_bindings:<get:"/carts/{user_id}/items" response_body:"items"> };
  }
  rpc RemoveItem ( .com.example.shoppingcart.RemoveLineItem ) returns ( .google.protobuf.Empty ) {
    option (.google.api.http) = { post:"/cart/{user_id}/items/{product_id}/remove" };
  }
}
```

For the shopping cart app, there is an Akka based client that can be used from a Scala REPL, here's an example session:

```
sbt:cloudstate> akka-client/console
...
scala> val client = new io.cloudstate.samples.ShoppingCartClient("192.168.39.186", 32121)
Connecting to 192.168.39.186:32121
client: io.cloudstate.samples.ShoppingCartClient = io.cloudstate.samples.ShoppingCartClient@2c11e42a

scala> client.getCart("foo")
res0: com.example.shoppingcart.shoppingcart.Cart = Cart(Vector())

scala> client.addItem("foo", "item-id-1", "Eggs", 12)
res1: com.google.protobuf.empty.Empty = Empty()

scala> client.addItem("foo", "item-id-2", "Milk", 3)
res2: com.google.protobuf.empty.Empty = Empty()

scala> client.getCart("foo")
res3: com.example.shoppingcart.shoppingcart.Cart = Cart(Vector(LineItem(item-id-1,Eggs,12), LineItem(item-id-2,Milk,3)))
```

### Development loops for the proxy

Once you've installed Cloudstate and got a user function running, the proxy can be iterated on by running the corresponding `dockerBuild*` command for the proxy backend you're using, for example, for the in memory proxy:

``` 
sbt:cloudstate> dockerBuildInMemory publishLocal
```

Now, after each time you make changes and rebuild the docker image, the simplest way to ensure your Cloudstate functions pick it up is to delete the pods for it and let the deployment recreate them, eg:

```
kubectl delete pods --all
```

### Development loops for the operator

The easiest way to iterate on the operator is to run it locally, from an IDE or from sbt, rather than deploying it to Minikube/Kubernetes. The only advantage to deploying to Kubernetes is that it will verify that the RBAC permissions that the operator has are correct. The Cloudstate operator uses [Skuber](https://github.com/doriordan/skuber), and when it runs outside of a Kubernetes container, it will use the credentials configured for `kubectl`. In the case of using Minikube, this will be the default cluster admin account.

To use a locally running operator, first shutdown the operator running in Kubernetes, by scaling its deployment down to zero:

```
kubectl scale -n cloudstate deployment/cloudstate-operator --replicas 0
```

Now run the operator locally. You need to tell the operator which namespace it should be running in so that it knows which namespace to consume its config map from, this can be done by setting the `NAMESPACE` environment variable. To run it using sbt, run:

```
NAMESPACE=cloudstate sbt operator/run
```

Alternatively, run it in an IDE, ensuring to configure the `NAMESPACE` environment variable, by running the `io.cloudstate.operator.OperatorMain` class in the operator sub project.
