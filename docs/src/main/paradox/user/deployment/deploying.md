# Deploying a stateful service

Cloudstate services are deployed to Kubernetes using the `StatefulService` resource. Here is a minimal example:

```yaml
apiVersion: cloudstate.io/v1alpha1
kind: StatefulService
metadata:
  name: shopping-cart
spec:
  containers:
  - image: my-docker-hub-username/shopping-cart:latest
```

Upon deploying this, the Cloudstate operator will create a Kubernetes `Deployment` that contains the container configured in the descriptor, along with a sidecar container for the Cloudstate proxy. The operator will also deploy a Kubernetes `Service` to allow other services to consume the Cloudstate service.

## Customizing the container

The container specification is modeled off the container specification in the `Pod` resource. Cloudstate will inject some additional configuration:

* If no ports are specified, in the container, a port of `8080` will be created, otherwise, the first declared port will be taken and assumed to be the port that the proxy should use to communicate with the user function.
* The port that the user function should use will be available in the environment variable `PORT`.
* The name of the container will be ignored, and is hardcoded to `user-container`.

## Customizing the stateful service

The following additional fields are available to customize the service:

`datastore`
: Configures the datastore. This is discussed in detail in @ref[Stateful stores](stores/index.md). If not configured, no store will be used, and support for any entity types that require a store will not be available on the proxy.

`autoscaling`
: Configures autoscaling. This is discussed in detail in @ref[Autoscaling](autoscaling.md). This field is likely to change.

`serviceAccountName`
: The name of the service account that the pod should run using. Defaults to the default service account.

`volumes`
: A list of [volumes to configure for pods](https://kubernetes.io/docs/concepts/storage/volumes/).

`sidecarResources`
: Resource requirements to set for the sidecar. Uses the same structure as a container resources field. This field is likely to change.

`sidecarJvmMemory`
: Heap space to allocate for the sidecar, using the `-Xmx` option. This currently has [no effect for native images](https://github.com/cloudstateio/cloudstate/issues/112), and is likely to change.

`nodeSelector`
: Used to specify the [Pod nodeSelector](https://kubernetes.io/docs/concepts/configuration/assign-pod-node/#nodeselector). This field is likely to change.

`tolerations`
: Used to specify the [Pod tolerations](https://kubernetes.io/docs/concepts/configuration/taint-and-toleration/). This field is likely to change.