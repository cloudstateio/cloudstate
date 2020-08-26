#!/usr/bin/env bash
#
# Prepare minikube in linux CI environments
# Adapted from: https://github.com/LiliC/travis-minikube

set -e

echo
echo "=== Installing Minikube for CI ==="
echo

# TODO: run against the minimum version of Kubernetes that we support
readonly kubernetes_version="1.18.6"
readonly minikube_version="1.12.2"
readonly helm_version="3.3.0"

# download kubectl
curl -Lo kubectl "https://storage.googleapis.com/kubernetes-release/release/v${kubernetes_version}/bin/linux/amd64/kubectl" && chmod +x kubectl && sudo mv kubectl /usr/local/bin/

# download minikube
curl -Lo minikube "https://storage.googleapis.com/minikube/releases/v${minikube_version}/minikube-linux-amd64" && chmod +x minikube && sudo mv minikube /usr/local/bin/
mkdir -p $HOME/.kube $HOME/.minikube
touch $HOME/.kube/config

# download helm
curl -Lo helm.tgz "https://get.helm.sh/helm-v${helm_version}-linux-amd64.tar.gz" && tar -xzvf helm.tgz && sudo mv linux-amd64/helm /usr/local/bin/

# Run minikube directly on docker: https://minikube.sigs.k8s.io/docs/drivers/docker/
# minikube start --driver=docker --kubernetes-version=v$kubernetes_version
# minikube docker-env --shell=bash > docker-env.sh

# Run minikube with no driver: https://minikube.sigs.k8s.io/docs/drivers/none/
sudo minikube start --driver=none --kubernetes-version=v$kubernetes_version
sudo chown -R $USER $HOME/.minikube/
