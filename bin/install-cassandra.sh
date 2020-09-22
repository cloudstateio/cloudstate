#!/usr/bin/env bash
#
# Install single-node Cassandra for testing, using helm

set -e

echo
echo "=== Installing Cassandra ==="
echo

readonly cassandra_chart_version="5.6.1"

# install cassandra via helm chart
helm repo add bitnami https://charts.bitnami.com/bitnami
helm install cassandra bitnami/cassandra --version $cassandra_chart_version --set persistence.enabled=false

# can only kubectl wait when the resource already exists
echo
echo "Waiting for cassandra to be created..."
attempts=10
until kubectl get pod cassandra-0 &> /dev/null || [ $attempts -eq 0 ] ; do
  ((attempts--))
  sleep 1
done
kubectl get pod cassandra-0

# wait for cassandra pod to be ready (can take a while)
echo
echo "Waiting for cassandra to be ready..."
if ! kubectl wait --for=condition=ready --timeout=10m pod/cassandra-0 ; then
  kubectl describe statefulset cassandra
  kubectl logs cassandra-0
  exit 1
fi
kubectl get statefulset cassandra

CASSANDRA_PASSWORD=$(kubectl get secret --namespace default cassandra -o jsonpath="{.data.cassandra-password}" | base64 --decode)
kubectl create secret generic cassandra-credentials \
  --from-literal=username=cassandra --from-literal=password="$CASSANDRA_PASSWORD"
