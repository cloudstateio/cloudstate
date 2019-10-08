#!/bin/bash

# This script prepares travis to run tasks that require minikube running.

set -e

# Adapted from:
# https://github.com/LiliC/travis-minikube/blob/63e39ca1e25a4cc6727ca21ec03474f2f9b26980/.travis.yml

# Download kubectl, which is a requirement for using minikube.
# We need v1.16.0 of the client to get this fix: https://github.com/kubernetes/kubernetes/pull/74943
curl -Lo kubectl https://storage.googleapis.com/kubernetes-release/release/v1.16.0/bin/linux/amd64/kubectl && chmod +x kubectl && sudo mv kubectl /usr/local/bin/
# Download minikube.
curl -Lo minikube https://storage.googleapis.com/minikube/releases/v1.4.0/minikube-linux-amd64 && chmod +x minikube && sudo mv minikube /usr/local/bin/
mkdir -p $HOME/.kube $HOME/.minikube
touch $HOME/.kube/config

# We use sudo because that allows us to run minikube directly in Docker without a VM, using the none
# driver, which is much lighter weight. See https://minikube.sigs.k8s.io/docs/reference/drivers/none/
# TODO: run against the minimum version of Kubernetes that we support
sudo minikube start --vm-driver=none --kubernetes-version=v1.15.0
sudo chown -R travis: /home/travis/.minikube/

