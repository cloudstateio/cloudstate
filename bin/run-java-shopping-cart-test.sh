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

  [ -f postgres-env.sh ] && source postgres-env.sh

  kubectl apply -f - <<YAML
apiVersion: cloudstate.io/v1alpha1
kind: StatefulStore
metadata:
  name: $statefulstore
spec:
  type: Postgres
  deployment: Unmanaged
  config:
    service: $POSTGRES_SERVICE
    credentials:
      database: $POSTGRES_DATABASE
      username: $POSTGRES_USERNAME
      password: $POSTGRES_PASSWORD
---
apiVersion: cloudstate.io/v1alpha1
kind: StatefulService
metadata:
  name: $statefulservice
spec:
  datastore:
    name: $statefulstore
  containers:
    - image: cloudstatedev/java-shopping-cart:dev
YAML
;;

  cassandra ) # deploy the shopping-cart with cassandra store

  statefulstore="cassandra"
  statefulservice="shopping-cart-$statefulstore"

  [ -f cassandra-env.sh ] && source cassandra-env.sh

  kubectl apply -f - <<YAML
apiVersion: cloudstate.io/v1alpha1
kind: StatefulStore
metadata:
  name: $statefulstore
spec:
  type: Cassandra
  deployment: Unmanaged
  config:
    service: $CASSANDRA_SERVICE
    credentials:
      username: $CASSANDRA_USERNAME
      password: $CASSANDRA_PASSWORD
---
apiVersion: cloudstate.io/v1alpha1
kind: StatefulService
metadata:
  name: $statefulservice
spec:
  datastore:
    name: $statefulstore
    config:
      keyspace: shoppingcart
  containers:
    - image: cloudstatedev/java-shopping-cart:dev
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
  type: InMemory
---
apiVersion: cloudstate.io/v1alpha1
kind: StatefulService
metadata:
  name: $statefulservice
spec:
  datastore:
    name: $statefulstore
  containers:
    - image: cloudstatedev/java-shopping-cart:dev
YAML
;;

esac

deployment="$statefulservice-deployment"

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
  kubectl logs -l app=cloudstate-operator -n cloudstate --tail=-1
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
  kubectl logs -l app=$statefulservice -c akka-sidecar --tail=-1
  echo
  echo "=== User container logs ==="
  echo
  kubectl logs -l app=$statefulservice -c user-container --tail=-1
  exit 1
}

# Wait for the deployment to be available
echo
echo "Waiting for deployment to be ready..."
kubectl wait --for=condition=available --timeout=1m deployment/$deployment || fail_with_details
kubectl get deployment $deployment

# Scale up the deployment, to test with akka clustering
# Travis can fail with 3 nodes, only scale to 2
echo
echo "Scaling deployment..."
kubectl scale --replicas=2 deployment/$deployment
kubectl get deployment $deployment

# Wait for the scaled deployment to be available
echo
echo "Waiting for deployment to be ready..."
kubectl wait --for=condition=available --timeout=5m deployment/$deployment || fail_with_details
kubectl get deployment $deployment

# Expose the shopping-cart service
kubectl expose deployment $deployment --port=8013 --type=NodePort

# Get the URL for the shopping-cart service
url=$(minikube service $deployment --url)

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
  kubectl logs -l app=$statefulservice -c akka-sidecar --tail=-1
fi

# Delete shopping-cart
if [ "$delete" == true ] ; then
  echo
  echo "Deleting $statefulservice ..."
  kubectl delete service $deployment
  kubectl delete statefulservice $statefulservice
  kubectl delete statefulstore $statefulstore
fi
