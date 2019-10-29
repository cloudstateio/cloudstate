#!/bin/bash

set -e

# Build it
sbt -Ddocker.username=cloudstatedev -Ddocker.tag=dev java-shopping-cart/docker:publishLocal

# Deploy it
kubectl apply -f - <<YAML
apiVersion: cloudstate.io/v1alpha1
kind: StatefulStore
metadata:
  name: inmemory
spec:
  type: InMemory
---
apiVersion: cloudstate.io/v1alpha1
kind: StatefulService
metadata:
  name: shopping-cart
spec:
  datastore:
    name: inmemory
  containers:
    - image: cloudstatedev/java-shopping-cart:dev
YAML

# Wait a few seconds for the operator to create the deployment
sleep 2

# Wait for the corresponding deployment to be available
if ! kubectl wait --for=condition=available --timeout=60s deployment/shopping-cart-deployment
then
    echo
    echo "=== Operator logs: ==="
    echo
    kubectl logs -l app=cloudstate-operator -n cloudstate --tail=-1
    echo
    echo "=== Deployment description: ==="
    echo
    kubectl describe deployment/shopping-cart-deployment
    echo
    echo "=== Proxy logs: ==="
    echo
    kubectl logs -l app=shopping-cart -c akka-sidecar --tail=-1
    echo
    echo "=== User container logs: ==="
    echo
    kubectl logs -l app=shopping-cart -c user-container --tail=-1
    exit 1
fi

# Expose it
kubectl --namespace=cloudstate expose deployment shopping-cart-deployment --port=8013 --type=NodePort

# Get the URL to it
URL=$(minikube service shopping-cart-deployment --url)

# Now we use the REST interface to test it (because it's easier to use curl than a grpc
# command line client)
EMPTY_CART='{"items":[]}'
POST='{"productId":"foo","name":"A foo","quantity":10}'
NON_EMPTY_CART='{"items":[{"productId":"foo","name":"A foo","quantity":10}]}'

INITIAL=$(curl -s $URL/carts/test)
if [[ "$EMPTY_CART" != "$INITIAL" ]]
then
    echo "Expected '$EMPTY_CART'"
    echo "But got '$INITIAL'"
    exit 1
else
    echo Initial request succeeded.
fi

curl -s -X POST $URL/cart/test/items/add -H "Content-Type: application/json" -d "$POST" > /dev/null

NEW_CART=$(curl -s $URL/carts/test)
if [[ "$NON_EMPTY_CART" != "$NEW_CART" ]]
then
    echo "Expected '$NON_EMPTY_CART'"
    echo "But got '$NEW_CART'"
    exit 1
else
    echo Shopping cart update succeeded.
fi


