#!/bin/bash

# Builds and deploys CloudState to minikube (or any Kubernetes cluster that runs off the Docker
# repo pointed to by the DOCKER_HOST envar).

set -e

# We use a custom, non existant docker hub username so that we don't pull down the wrong thing
# accidentally. Here we build the operator, all non native proxies, and we compile the K8s
# descriptors to use these.
sbt -Ddocker.username=cloudstatedev -Ddocker.tag=dev -Duse.native.builds=false operator/docker:publishLocal operator/compileK8sDescriptors "dockerBuildAllNonNative publishLocal"

# Now deploy CloudState
kubectl create namespace cloudstate
kubectl apply -n cloudstate -f operator/cloudstate-dev.yaml
if ! kubectl wait --for=condition=available --timeout=60s -n cloudstate deployment/cloudstate-operator
then
    kubectl describe -n cloudstate deployment/cloudstate-operator
    kubectl logs -l app=cloudstate-operator -n cloudstate
    exit 1
fi
