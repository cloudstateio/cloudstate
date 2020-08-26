#!/usr/bin/env bash
#
# Build and deploy Cloudstate to Kubernetes.
# Can be minikube, or any Kubernetes cluster by using the DOCKER_HOST env var.
#
# deploy-cloudstate.sh [--native] [all|inmemory|postgres|cassandra]

set -e

echo
echo "=== Building Cloudstate docker images and deploying operator ==="
echo

# process arguments
shopt -s nocasematch
native=false
all=false
declare -a build
while [ $# -gt 0 ] ; do
  case "$1" in
    --native ) native=true; shift ;;
    all ) all=true; shift ;;
    inmemory ) build=("${build[@]}" "InMemory"); shift ;;
    postgres ) build=("${build[@]}" "Postgres"); shift ;;
    cassandra ) build=("${build[@]}" "Cassandra"); shift ;;
    * ) echo "Unknown image to build: $1"; shift ;;
  esac
done

[ ${#build[@]} -eq 0 ] && all=true

declare -a build_commands
if [ "$all" == true ] ; then
  [ "$native" == true ] && build_commands=("dockerBuildAllNative publishLocal") || build_commands=("dockerBuildAllNonNative publishLocal")
else
  [ "$native" == true ] && native_build="Native" || native_build=""
  for image in "${build[@]}" ; do
    build_commands=("${build_commands[@]}" "dockerBuild${native_build}${image} publishLocal")
  done
fi


# Use a custom, non-existant docker hub username so that we don't pull down the wrong thing accidentally.
# Build the operator, required proxies, and compile the K8s descriptors.
[ -f docker-env.sh ] && source docker-env.sh
sbt -Ddocker.username=cloudstatedev -Ddocker.tag=dev -Duse.native.builds=$native operator/docker:publishLocal operator/compileK8sDescriptors "${build_commands[@]}"

# Deploy Cloudstate operator
kubectl create namespace cloudstate
kubectl apply -n cloudstate -f operator/cloudstate-dev.yaml
echo
echo "Waiting for operator deployment to be ready..."
if ! kubectl wait --for=condition=available --timeout=2m -n cloudstate deployment/cloudstate-operator
then
    kubectl describe -n cloudstate deployment/cloudstate-operator
    kubectl logs -l app=cloudstate-operator -n cloudstate
    exit 1
fi
