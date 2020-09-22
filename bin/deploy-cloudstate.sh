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

[ -f docker-env.sh ] && source docker-env.sh

if [ -n "$build_commands" ]
then
  sbt "$build_commands"
fi

kubectl apply --validate=false -f https://github.com/jetstack/cert-manager/releases/download/v0.16.1/cert-manager.yaml
kubectl wait --for=condition=available --timeout=2m -n cert-manager deployment/cert-manager-webhook
make -C cloudstate-operator deploy
echo "Waiting for operator deployment to be ready..."
if ! kubectl wait --for=condition=available --timeout=2m -n cloudstate-system deployment/cloudstate-controller-manager
then
    kubectl describe -n cloudstate-system deployment/cloudstate-controller-manager
    kubectl describe -n cloudstate-system pods -l control-plane=controller-manager
    kubectl logs -l control-plane=controller-manager -n cloudstate-system -c manager
    exit 1
fi
