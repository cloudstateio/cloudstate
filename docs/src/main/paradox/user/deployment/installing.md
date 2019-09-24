# Installing

## Prerequisites

* Kubernetes 1.12 or later. Earlier versions of Kubernetes may work but have not been tested.
* (Optional) Istio 1.2.0 or later.

## Installation requirements

Installing CloudState requires cluster admin, so that the necessary Custom Resource Definitions (CRD) can be installed, and the necessary roles and role bindings can be created.

## Operator requirements

The CloudState operator typically gets deployed to its own namespace, for the rest of this guide we will assume that is called `cloudstate`, and needs permissions to manage deployments, services, roles and role bindings in every namespace that it manages CloudState services in. CloudState provides a single deployment descriptor, available from the [CloudState GitHub release page](https://github.com/cloudstateio/cloudstate/releases).

The deployment descriptor is built from the descriptors found in the [operator `deploy` directory](https://github.com/cloudstateio/cloudstate/blob/master/operator/deploy/), and can be modified accordingly if, for example, more fine grained access controls wish to be granted.

## Operator configuration

The operator is configured using a `ConfigMap` called `cloudstate-operator-config` deployed to the same namespace as the operator runs in. This config map contains a [HOCON](https://github.com/lightbend/config/blob/master/HOCON.md) configuration file.

To edit the operator configuration, once deployed, run:

```
kubectl edit -n cloudstate configmap/cloudstate-operator-config
```

Changes to the config map will be picked up immediately by the operator, and if necessary, will be applied to all CloudState stateful service deployments immediately.

### Configuring namespaces to watch

The namespaces that the operator should watch are configured using the `cloudstate.operator.watch.namespaces` list. If any of the namespaces in the list is `*`, then all namespaces will be watched. Otherwise, just the namespaces specified in the list are watched. To watch the namespaces `my-namespcae-1` and `my-namespace-2`, it can be configured like so:

```hocon
cloudstate.operator.watch.namespaces = ["my-namespace-1", "my-namespace-2"]
```

### Configuring proxy images

CloudState selects a proxy image based on what type of store is being used, if any. To customize the image used for a particular store, for example, to use a custom build, or a different version, these can be modified using `cloudstate.operator.proxy.image.<store-name>`. For example, to use the non-native build of the Cassandra proxy:

@@@vars
```hocon
cloudstate.operator.proxy.image.cassandra = "cloudstateio/cloudstate-proxy-cassandra:$cloudstate.version$"
```
@@@

## Installing the operator

To install the operator, first create the namespace it is to be deployed to:

```
kubectl create namespace cloudstate
```

Now install CloudState into that namespace:

@@@vars
```
kubectl apply -n cloudstate -f https://github.com/cloudstateio/cloudstate/releases/download/v$cloudstate.version$/cloudstate-$cloudstate.version$.yaml
```
@@@

You should now be ready to start using CloudState.
