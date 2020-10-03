#!/usr/bin/env bash
#
# Run the Java shopping cart sample as a test, with a given persistence store.
#
# run-java-shopping-cart-test.sh [inmemory|postgres|cassandra]

set -e

echo
echo "=== Running java-shopping-cart test ==="
echo

# process arguments
shopt -s nocasematch
build=true
logs=false
delete=true
declare -a residual
while [[ $# -gt 0 ]] ; do
  case "$1" in
    --no-build ) build=false; shift ;;
    --logs ) logs=true; shift ;;
    --no-delete ) delete=false; shift ;;
    *) residual=("${residual[@]}" "$1"); shift ;;
  esac
done
set -- "${residual[@]}"
store="$1"

# Build the java shopping cart
if [ "$build" == true ] ; then
  echo
  echo "Building java-shopping-cart ..."
  [ -f docker-env.sh ] && source docker-env.sh
  sbt -Ddocker.username=cloudstatedev -Ddocker.tag=dev java-shopping-cart/docker:publishLocal
fi

statefulstore="inmemory"
statefulservice="shopping-cart-$statefulstore"

case "$store" in

  postgres ) # deploy the shopping-cart with postgres store

  statefulstore="postgres"
  statefulservice="shopping-cart-$statefulstore"

  kubectl apply -f - <<YAML
apiVersion: cloudstate.io/v1alpha1
kind: StatefulStore
metadata:
  name: $statefulstore
spec:
  postgres:
    host: postgres-postgresql.default.svc.cluster.local
    credentials:
      secret:
        name: postgres-credentials
---
apiVersion: cloudstate.io/v1alpha1
kind: StatefulService
metadata:
  name: $statefulservice
spec:
  storeConfig:
    statefulStore:
      name: $statefulstore
  containers:
    - image: cloudstateio/java-shopping-cart:latest
      imagePullPolicy: Never
      name: user-function
YAML
;;

  cassandra ) # deploy the shopping-cart with cassandra store

  statefulstore="cassandra"
  statefulservice="shopping-cart-$statefulstore"

  kubectl apply -f - <<YAML
apiVersion: cloudstate.io/v1alpha1
kind: StatefulStore
metadata:
  name: $statefulstore
spec:
  cassandra:
    host: cassandra.default.svc.cluster.local
    credentials:
      secret:
        name: cassandra-credentials
---
apiVersion: cloudstate.io/v1alpha1
kind: StatefulService
metadata:
  name: $statefulservice
spec:
  storeConfig:
    statefulStore:
      name: $statefulstore
    database: shoppingcart
  containers:
    - image: cloudstateio/java-shopping-cart:latest
      imagePullPolicy: Never
      name: user-function
YAML
;;

  inmemory | * ) # deploy the shopping-cart with in-memory store

  statefulstore="inmemory"
  statefulservice="shopping-cart-$statefulstore"

  kubectl apply -f - <<YAML
apiVersion: cloudstate.io/v1alpha1
kind: StatefulStore
metadata:
  name: $statefulstore
spec:
  inMemory: true
---
apiVersion: cloudstate.io/v1alpha1
kind: StatefulService
metadata:
  name: $statefulservice
spec:
  storeConfig:
    statefulStore:
      name: $statefulstore
  containers:
    - image: cloudstateio/java-shopping-cart:latest
      imagePullPolicy: Never
      name: user-function
YAML
;;

esac

deployment="$statefulservice"

# Can only kubectl wait when the resource already exists
echo
echo "Waiting for deployment to be created..."
attempts=10
until kubectl get deployment $deployment &> /dev/null || [ $attempts -eq 0 ] ; do
  ((attempts--))
  sleep 1
done
kubectl get deployment $deployment

function fail_with_details {
  echo
  echo "=== Operator logs ==="
  echo
  kubectl logs -l control-plane=controller-manager -n cloudstate-system -c manager --tail=-1
  echo
  echo "=== Deployment description ==="
  echo
  kubectl describe deployment/$deployment
  echo
  echo "=== Pods description ==="
  echo
  kubectl describe pods
  echo
  echo "=== Proxy logs ==="
  echo
  kubectl logs -l cloudstate.io/stateful-service=$statefulservice -c cloudstate-sidecar --tail=-1
  echo
  echo "=== User function logs ==="
  echo
  kubectl logs -l cloudstate.io/stateful-service=$statefulservice -c user-function --tail=-1
  exit 1
}

# Wait for the deployment to be available
echo
echo "Waiting for deployment to be ready..."
kubectl wait --for=condition=available --timeout=1m deployment/$deployment || fail_with_details
kubectl get deployment $deployment

# Scale up the deployment, to test with akka clustering
echo
echo "Scaling deployment..."
kubectl scale --replicas=3 deployment/$deployment
kubectl get deployment $deployment

# Wait for the scaled deployment to be available
echo
echo "Waiting for deployment to be ready..."
kubectl wait --for=condition=available --timeout=5m deployment/$deployment || fail_with_details
kubectl get deployment $deployment

# Expose the shopping-cart service
nodeport="$deployment-node-port"
kubectl expose deployment $deployment --name=$nodeport --port=8013 --type=NodePort

# Get the URL for the shopping-cart service
url=$(minikube service $nodeport --url)

# Now we use the REST interface to test it (because it's easier to use curl than a grpc
# command line client)
empty_cart='{"items":[]}'
post='{"productId":"foo","name":"A foo","quantity":10}'
non_empty_cart='{"items":[{"productId":"foo","name":"A foo","quantity":10}]}'

# Iterate over multiple entities to be routed to different nodes
for i in {1..9} ; do
  cart_id="test$i"
  echo
  echo "Testing shopping cart $cart_id ..."

  initial_cart=$(curl -s $url/carts/$cart_id)
  if [[ "$empty_cart" != "$initial_cart" ]]
  then
      echo "Expected '$empty_cart'"
      echo "But got '$initial_cart'"
      fail_with_details
  else
      echo "Initial request for $cart_id succeeded."
  fi

  curl -s -X POST $url/cart/$cart_id/items/add -H "Content-Type: application/json" -d "$post" > /dev/null

  new_cart=$(curl -s $url/carts/$cart_id)
  if [[ "$non_empty_cart" != "$new_cart" ]]
  then
      echo "Expected '$non_empty_cart'"
      echo "But got '$new_cart'"
      fail_with_details
  else
      echo "Shopping cart update for $cart_id succeeded."
  fi
done

# Print proxy logs
if [ "$logs" == true ] ; then
  echo
  echo "=== Proxy logs ==="
  echo
  kubectl logs -l cloudstate.io/stateful-service=$statefulservice -c cloudstate-sidecar --tail=-1
fi

# Delete shopping-cart
if [ "$delete" == true ] ; then
  echo
  echo "Deleting $statefulservice ..."
  kubectl delete service $deployment
  kubectl delete service $nodeport
  kubectl delete statefulservice $statefulservice
  kubectl delete statefulstore $statefulstore
fi
