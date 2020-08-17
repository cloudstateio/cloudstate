#!/usr/bin/env bash
#
# Install Postgres for testing, using helm

set -e

echo
echo "=== Installing Postgres ==="
echo

readonly postgres_chart_version="9.1.4"

# install postgres via helm chart
helm repo add bitnami https://charts.bitnami.com/bitnami
helm install postgres bitnami/postgresql --version $postgres_chart_version --set persistence.enabled=false

# can only kubectl wait when the resource already exists
echo
echo "Waiting for postgres to be created..."
attempts=10
until kubectl get pod postgres-postgresql-0 &> /dev/null || [ $attempts -eq 0 ] ; do
  ((attempts--))
  sleep 1
done
kubectl get pod postgres-postgresql-0

# wait for postgres pod to be ready
echo
echo "Waiting for postgres to be ready..."
if ! kubectl wait --for=condition=ready --timeout=3m pod/postgres-postgresql-0 ; then
  kubectl describe statefulset postgres-postgresql
  kubectl logs postgres-postgresql-0
  exit 1
fi
kubectl get statefulset postgres-postgresql

# write env exports to file for the installed postgres
cat << EOF > postgres-env.sh
export POSTGRES_SERVICE=postgres-postgresql.default.svc.cluster.local
export POSTGRES_DATABASE=postgres
export POSTGRES_USERNAME=postgres
export POSTGRES_PASSWORD=$(kubectl get secret --namespace default postgres-postgresql -o jsonpath="{.data.postgresql-password}" | base64 --decode)
EOF
