# Stateful stores

CloudState depends on databases to provide all durable entity types, such as event sourcing. Multiple different database backends are supported. The configuration for these backends is deployed independently from the services that consume them developers don't need to concern themselves with where or how a database is deployed, and how to connect and authenticate with it, when they deploy their services. This is done using the `StatefulStore` resource. Here is an example resource for a PostgreSQL store:

```yaml
apiVersion: cloudstate.io/v1alpha1
kind: StatefulStore
metadata:
  name: my-postgres-store
spec:
  type: Postgres
  deployment: Unmanaged
  config:
    service: postgresql.default.svc.cluster.local
    credentialsFromSecret:
      name: postgres-credentials
```

`type`
: The type of the store. Currently supported stores are `Cassandra`, `Postgres` and `InMemory`.

`deployment`
: How the store is deployed. Valid values depend on the store type. `Unmanaged` means that the CloudState operator does not manage the deployment of the stateful store, currently, all stores only support unmanaged deployment. In future, support may be added to allow CloudState to deploy the database itself, this will typically rely on a third party cloud database management service.

`config`
: Configuration specific to the type of database and it's mode of deployment. The configuration options available are detailed in the store specific documentation.

## Using a stateful store

A stateful service can use a stateful store by selecting it using the `datastore` field of the stateful service spec, for example, the following would configure the `shopping-cart` stateful service to use the `my-postgres-store` configured above:

```yaml
apiVersion: cloudstate.io/v1alpha1
kind: StatefulService
metadata:
  name: shopping-cart
spec:
  datastore:
    name: my-postgres-store
  containers:
  - image: my-docker-hub-username/shopping-cart:latest
```

## Available stores

@@toc { depth=1 headers=false }

@@@ index

* [Apache Cassandra](cassandra.md)
* [PostgreSQL](postgresql.md)
* [In memory](inmemory.md)

@@@

