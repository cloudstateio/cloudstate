# CloudState design

This is a living document, that will be updated as the project evolves.

## High level overview

A CloudState service looks like this:

![](high-level.svg)

* **Ingress** - This can be Istio, Knative, or just regular ClusterIP service communication in Kubernetes. Whatever service approach is used, CloudState expects traffic to be load balanced across its pods randomly and evenly.
* **Akka sidecar** - This sidecar is injected by the CloudState operator. All requests go through it. The sidecars of a single CloudState service form a cluster, communicating directly with each other using Akka remoting. This cluster, and the communication links between the sidecars, allows for sharding and replication of state, along with addressed P2P messaging between pods.
* **Code** - This is the function implemented by the developer. It can be written in any language that supports gRPC. The Akka sidecars communicate with the user functions using a predefined gRPC protocol. This protocol carries both incoming requests and outgoing responses, as well as messages conveying the current state of the system. Typically, CloudState will provide support libraries for each language that adapt the gRPC protocol to an idiomatic API for that language.
* **Distributed Datastore** - When a service needs to persist state, such as when implementing Event Sourced entities, this state will be persisted to a distributed datastore. It is important to note, the user code does not interact directly with the datastore - it interacts with the Akka sidecars, and the Akka sidecars communicate with the datastore. This way, all database communication can be directly managed and monitored by the Akka sidecar, and because this is done so through the provision of high level patterns, assumptions can be made that allow the sidecars to safely cache, shard and replicate data across the cluster.

## Common Intermediate Representation

The gRPC protocol spoken between the Akka sidecar and the user code is a Common Intermediate Representation (IR) as defined by Hellerstein et. al. in [Serverless Computing: One Step Forward, Two Steps Back](http://cidrdb.org/cidr2019/papers/p119-hellerstein-cidr19.pdf). This is used to allow user functions to take advantage of the features afforded by distributed systems technologies such as Akka, without needing to be written in the same language as those technologies. The protocol also allows the sidecar to be implemented using any technology, not just Akka. CloudState's Akka based implementation is provided as a reference implementation.

There are two parts to the IR. The first is discovery. This is where a user function declares what services it wishes to expose, and what stateful features it needs those services to be enriched with. This is done by the sidecar making a call on the user function, using the IR protocol, to request a descriptor that describes it. This descriptor contains a serialized protobuf definition of the services that the user function wishes to expose. Each service is declared to have a particular entity type, supported types include Event Sourcing and CRDTs.

The second part of the IR is a pluggable entity type protocol. Each entity type defines its own gRPC protocol for communicating between the sidecar and the user function. Here is an cut down snippet of the event sourcing protocol:

```proto
service EventSourced {
    rpc handle(stream EventSourcedStreamIn) returns (stream EventSourcedStreamOut) {}
}

message EventSourcedStreamIn {
    oneof message {
        EventSourcedEvent event = 1;
        Command command = 2;
    }
}

message EventSourcedStreamOut {
    oneof message {
        EventSourcedReply reply = 1;
        Failure failure = 2;
    }
}

message EventSourcedReply {
    oneof response {
        Reply reply = 1;
        Forward forward = 2;
    }
    repeated google.protobuf.Any events = 3;
}
```

When a command for an entity arrives, the following messages are sent using this protocol:

1. If there is no existing `handle` stream for that entity, the `handle` streamed call is invoked. This stream will be kept open as long as more commands arrive for that entity, after a period of inactivity, the stream will be shut down.
2. The sidecar loads the event journal for that entity, and passes each event to the user function using the `EventSourcedEvent` message.
3. Once the entities event journal has been replayed, the command is sent to the user function.
4. The user function processes the command, and responds with an `EventSourcedReply`. This contains one of two responses, a `Reply` to send to the orginial source, or a `Forward` to forward processing to another entity. It also contains zero or more events to be persisted. These events will be persisted before the reply or forward is sent.
5. Subsequent commands may be received while the entity stream is still active, these can be processed without replaying the event journal.

The user function is expected to hold the current state of the entity in the context of the streamed function call.

The `Command` message contains, among other things, the name of a gRPC rpc call that is being invoked - this RPC call was declared during the discovery phase. It also contains the payload of that gRPC call, along with an extracted entity id, that identifies which entity the call is for. Entity ids are declared through the use of a Protobuf field extension, here is an example user function message that declares an entity id:

```proto
message AddLineItem {
    string user_id = 1 [(.cloudstate.entity_key) = true];
    string product_id = 2;
    string name = 3;
    int32 quantity = 4;
}
```

## Operator

Deployment of CloudState entities is designed to work either in a stand alone fashion, or integrating with Knative. The design allows for integrations with other serverless technologies to be implemented in future.

An operator is provided that transforms either the CloudState entity CRD, or the Knative Revision, into a kubernetes Deployment, which is configured with the Akka sidecar container injected, and the necessary RBAC permissions are created to allow cluster discovery, bootstrapping and formation, along with autoscaling.

Integration with Knative currently requires a fork of Knative that makes deployers pluggable. The changes necessary can be found in [this pull request](https://github.com/knative/serving/pull/4152).

## Autoscaling

Experiments with the Knative autoscaler have found that it is not suitable for scaling Akka clusters. Problems include:

* Scaling up an Akka cluster is not free. When a new node is added, shards are rebalanced to it, and state is replicated to it. Starting too many nodes at once, or starting nodes only to immediately stop them, will significantly degrade throughput and performance. The Knative autoscaler does not take this into consideration, and happily starts and stops many nodes very frequently.
* Particularly when CPU is constrained, JVM warmup/jitting can take a long time, and this warmup time needs to be taken into consideration before making further scaling decisions. Additionally, it takes time to replicate state and rebalance shards to a newly started node. The JVM warmup/jitting issues we hope to address by using Graal AOT compilation with SubstrateVM.
* The Knative proxy, which the Akka sidecar replaces, is highly coupled to the Knative autoscaler, and the interface between them is not well defined, making reliance on it from the Akka sidecar high risk in terms of future evolution of Knative.

For these reasons, we have implemented our own autoscaler. For simplicity, this autoscaler is implemented as a cluster singleton in the Akka sidecar cluster - Akka cluster remoting makes propagation of metrics from all nodes to the autoscaler singleton trivial. We collect the following metrics:

* Average request concurrency per pod - this is the number of simultaneous requests being handled from outside the service. This includes requests currently being handled by user functions, requests being routed through other nodes for sharding, and requests currently interacting the the database.
* Average user function concurrency per pod - this is the number of simultaneous requests that the user function is handling.
* Average database concurrency per pod - this is the number of simultaneous operations being performed on the database at any one time. This is typically subtracted from request concurrency so that database performance does not impact decisions made based on request concurrency.
* Request rate - this is the rate at which incoming requests are arriving.

In general, scaling decisions are made when user function concurrency and request concurrency exceed or drop below configurable thresholds. The reason for using two metrics is that in sharding situations, request concurrency is highly dependent on the number of nodes. When there is only one node, no requests are forwarded to other nodes, which means latency stays very low, which means request concurrency stays very low. When there are two nodes, on average 50% of requests are forwarded to other nodes, when there are many nodes, this number increases. For this reason, request concurrency is not a good metric to base scaling decisions on when the number of nodes is low, so user function concurrency is used. However, request concurrency is still an important metric because the impact of cluster sharding on the load being handled is non zero, and indeed, if it performs badly compared to the user function, then user function concurrency will stay low, while requests back up in cluster sharding buffers. Hence, request concurrency is used as scaling metric, but set to something high that would never be triggered when there's only one node, but is more likely to be triggered when load is higher.

After a scaling decision has been made, the autoscaler enters a configurable stable waiting period. During this period, no concurrency based scaling decisions will be made - since it can take time for a new node to start and warm up, and therefore it will take time for concurrency to stabilise. Without the stable waiting period, a sudden increase in load will cause concurrency to increase linearly, and the autoscaler will start more and more nodes to handle this increasing concurrency. The new nodes will initially cause performance to degrade, as they warm up and have shards rebalanced to them, causing further scaling, which causes a feedback loop that sees nodes scaled to impractical numbers.

During the waiting period however, load may continue to increase, and we want to be able to respond to that. To detect increases in load, the incoming request rate is recorded when the autoscaler first enters the stable waiting period when scaling up. If this incoming request rate increases by a configurable threshold, further scaling decisions are made.

This request rate based scaling is not used when scaling down, since the request rate when scaling down may be very low (for example, 0), making it impossible to reason about what an appropriate number of nodes to handle that request rate is. Instead, scaling down stable periods are much shorter than scaling up stable periods.

When an upgrade is detected, request rate based scaling decisions are also made, since upgrades cause a temporary degradation in performance as state is replicated and rebalanced to newly upgraded nodes.

At time of writing, the autoscaler only works in standalone mode, which uses one deployment per user function. Support for Knatives one deployment per revision of a user function has not yet been implemented, nor has support in Knative to disable the Knative autoscaler when a custom deployer is used.

## HTTP/JSON

The Akka sidecar supports serving the gRPC user functions services both as gRPC, as well as using HTTP/JSON, using the [gRPC HTTP extensions](https://cloud.google.com/service-infrastructure/docs/service-management/reference/rpc/google.api#http).
