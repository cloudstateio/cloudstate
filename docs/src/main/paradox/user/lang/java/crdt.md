# Conflict-free Replicated Data Types

This page documents how to implement CloudState CRDT entities in Java. For information on what CloudState CRDT entities are, please read the general @ref[Conflict-free Replicated Data Type](../../features/crdts.md) documentation first.

A CRDT can be created by annotating it with the @javadoc[`@CrdtEntity`](io.cloudstate.javasupport.crdt.CrdtEntity) annotation.

@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/crdt/ShoppingCartEntity.java) { #entity-class }

## Accessing and creating an entities CRDT

Each CRDT entity manages one root CRDT. That CRDT will either be supplied to the entity by the proxy when it is started, or, if no CRDT exists for the entity when it is started, it can be created by the entity using a @javadoc[`CrdtFactory`](io.cloudstate.javasupport.crdt.CrdtFactory) extending context.

There are multiple ways that a CRDT entity may access its CRDT. It may have the CRDT injected directly into its constructor or a command handler - the value can be wrapped in an `Optional` to distinguish between entities that have been created and CRDTs that have not yet been created. If not wrapped in `Optional`, the CRDT will be created automatically, according to its type. The CRDT can also be read from any @javadoc[`CrdtContext`](io.cloudstate.javasupport.crdt.CrdtContext) via the @javadoc[`state`](io.cloudstate.javasupport.crdt.CrdtContext#state-java.lang.Class-) method.

An entities CRDT can be created from the entities constructor using the `CrdtFactory` methods on @javadoc[`CrdtCreationContext`](io.cloudstate.javasupport.crdt.CrdtCreationContext), or using the same methods in a command handler using the @javadoc[`CommandContext`](io.cloudstate.javasupport.crdt.CommandContext). Note that the CRDT may only be created once, and only if it hasn't been provided by the CloudState proxy already. Any attempt to create a CRDT when one already exists will throw an `IllegalStateException`.

For most use cases, simply injecting the CRDT directly into the constructor, and storing in a local field, will be the most convenient and straight forward method of using a CRDT. In our shopping cart example, we're going to use an @javadoc[`LWWRegisterMap`](io.cloudstate.javasupport.crdt.LWWRegisterMap), this shows how it may be injected:

@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/crdt/ShoppingCartEntity.java) { #creation }

In addition to the CRDT, the constructor may accept a @javadoc[`CrdtCreationContext`](io.cloudstate.javasupport.crdt.CrdtCreationContext).

## Handling commands

Command handlers can be declared by annotating a method with @javadoc[`@CommandHandler`](io.cloudstate.javasupport.crdt.CommandHandler). They take a context class of type @javadoc[`CommandContext`](io.cloudstate.javasupport.crdt.CommandContext).

By default, the name of the command that the method handles will be the name of the method with the first letter capitalized. So, a method called `getCart` will handle gRPC service call command named `GetCart`. This can be overridden by setting the `name` parameter on the `@CommandHandler` annotation.

The command handler also can take the gRPC service call input type as a parameter, to receive the command message. This is optional, sometimes it's not needed, for example, our `GetCart` service call doesn't need any information from the message, since it's just returning the current state as is. Meanwhile, the `AddItem` service call does need information from the message, since it needs to know the product id, description and quantity to add to the cart.

The return type of the command handler must be the output type for the gRPC service call, this will be sent as the reply.

The following shows the implementation of the `GetCart` command handler. This command handler is a read-only command handler, it doesn't update the CRDT, it just returns some state:

@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/crdt/ShoppingCartEntity.java) { #get-cart }

## Updating a CRDT

Due to CloudStates @ref[take in turns approach](../../features/crdts.md#approach-to-crdts-in-cloudstate), CRDTs may only be updated in command handlers and @ref[stream cancellation callbacks](#responding-to-stream-cancellation).

Here's a command handler for the `AddItem` command that adds the item to the shopping cart:

@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/crdt/ShoppingCartEntity.java) { #add-item }

## Deleting a CRDT

A CRDT can be deleted by invoking @javadoc[`CommandContext.delete`](io.cloudstate.javasupport.crdt.CommandContext#delete--). Once a CRDT is deleted, the entity will be shut down, and all subsequent commands for the entity will be rejected.

Caution should be taken when deleting CRDTs - the Reference Implementation of the proxy needs to maintain tombstones for each CRDT deleted, so over time, if many CRDTs are created and deleted, this will result in not just running out of memory, but increased network usage as the tombstones still need to be gossipped through the cluster for replication.

## Streamed command handlers

Streamed commands can be used to receive and publish updates to the state. If a gRPC service call has a streamed result type, the handler for that call can accept a @javadoc[`StreamedCommandContext`](io.cloudstate.javasupport.crdt.StreamedCommandContext), and use that to register callbacks.

### Responding to changes

If the command handler wishes to publish changes to the stream it can register a callback with @javadoc[`onChange`](io.cloudstate.javasupport.crdt.StreamedCommandContext#onChange-java.util.function.Function-), which will be invoked every time the CRDT changes.

The callback is then able to return a message to be sent to the client (or empty, if it wishes to send no message in response to that particular change). The callback may not modify the CRDT itself, but it may emit effects that may modify the CRDT.

If the shopping cart service had a `WatchCart` call, like this:

```proto
rpc WatchCart(GetShoppingCart) returns (stream Cart);
```

that could be implemented like this:

@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/crdt/ShoppingCartEntity.java) { #watch-cart }

### Ending the stream

The `onChange` callback can end the stream by invoking @javadoc[`endStream`](io.cloudstate.javasupport.crdt.SubscriptionContext#endStream--) on the @javadoc[`SubscriptionContext`](io.cloudstate.javasupport.crdt.SubscriptionContext) it is passed. If it does this, it will not receive an `onCancel` callback.

### Responding to stream cancellation

A streamed command handler may also register an @javadoc[`onCancel`](io.cloudstate.javasupport.crdt.StreamedCommandContext#onCancel-java.util.function.Consumer-) callback to be notified when the stream is cancelled. The cancellation callback handler may update the CRDT. This is useful if the CRDT is being used to track connections, for example, when using @javadoc[`Vote`](io.cloudstate.javasupport.crdt.Vote) CRDTs to track a users online status.

## Types of CRDTs

The CloudState Java support library offers Java classes for each of the @ref[CRDTs available in CloudState](../../features/crdts.md#crdts-available-in-cloudstate).

### Counters and flags

@javadoc[`GCounter`](io.cloudstate.javasupport.crdt.GCounter), @javadoc[`PNCounter`](io.cloudstate.javasupport.crdt.PNCounter) and @javadoc[`Flag`](io.cloudstate.javasupport.crdt.Flag) are available, offering operations relevant to each CRDT.

### Vote

@javadoc[`Vote`](io.cloudstate.javasupport.crdt.Vote) is available for the Vote CRDT. The Vote CRDT allows updating the current nodes vote using the @javadoc[`vote`](io.cloudstate.javasupport.crdt.Vote#vote-boolean-) method, the current nodes vote can be queried using the @javadoc[`getSelfVote`](io.cloudstate.javasupport.crdt.Vote#getSelfVote--) method.
 
 For determining the result of a vote, @javadoc[`getVoters`](io.cloudstate.javasupport.crdt.Vote#getVoters--) and @javadoc[`getVotesFor`](io.cloudstate.javasupport.crdt.Vote#getVotesFor--) can be used to check the total number of nodes, and the number of nodes that have voted for the condition, respectively. In addition, convenience methods are provided for common vote decision approaches, @javadoc[`isAtLeastOne`](io.cloudstate.javasupport.crdt.Vote#isAtLeastOne--) returns true if there is at least one voter for the condition, @javadoc[`isMajority`](io.cloudstate.javasupport.crdt.Vote#isMajority--) returns true if the number of votes for is more than half the number of voters, and @javadoc[`isAll`](io.cloudstate.javasupport.crdt.Vote#isUnanimous--) returns true if the number of votes for equals the number of voters.

### Registers

@javadoc[`LWWRegister`](io.cloudstate.javasupport.crdt.LWWRegister) provides the LWWRegister CRDT. It can be interacted with using the @javadoc[`set`](io.cloudstate.javasupport.crdt.LWWRegister#set-T-) and @javadoc[`get`](io.cloudstate.javasupport.crdt.LWWRegister#get--) methods. If you wish to use a custom clock, you can use the @javadoc[`set`](io.cloudstate.javasupport.crdt.LWWRegister#set-T-io.cloudstate.javasupport.crdt.LWWRegister.Clock-long-) overload that allows passing a custom clock and custom clock value.

@@@ note { title=Important }
Direct mutations to @javadoc[`LWWRegister`](io.cloudstate.javasupport.crdt.LWWRegister) and @javadoc[`LWWRegisterMap`](io.cloudstate.javasupport.crdt.LWWRegisterMap) values will not be replicated to other nodes, only mutations triggered through using the @javadoc[`set`](io.cloudstate.javasupport.crdt.LWWRegister#set-T-) and @javadoc[`put`](io.cloudstate.javasupport.crdt.LWWRegisterMap#put-K-V-) methods will be replicated. Hence, the following update will not be replicated:

```java
myLwwRegister.get().setSomeField("foo");
```

This update however will be replicated:

```java
MyValue myValue = myLwwRegister.get();
myValue.setSomeField("foo");
myLwwRegister.set(myValue);
```

In general, we recommend that these values be immutable, as this will prevent accidentally mutating without realising the update won't be applied. If using protobufs as values, this will be straight forward, since compiled protobuf classes are immutable.
@@@

### Sets and Maps

CloudState Java support provides @javadoc[`GSet`](io.cloudstate.javasupport.crdt.GSet) and @javadoc[`ORSet`](io.cloudstate.javasupport.crdt.ORSet) that implement the `java.util.Set` interface, and @javadoc[`ORMap`](io.cloudstate.javasupport.crdt.ORMap) that implements the `java.util.Map`. However, not all operations are implemented - `GSet` doesn't support any removal operations, and `ORMap` does not support any operations that would replace an existing value in the map.

To insert a value into an `ORMap`, you should use the @javadoc[`getOrCreate`](io.cloudstate.javasupport.crdt.ORMap#getOrCreate-K-java.util.function.Function-) method. The passed in callback will give you a @javadoc[`CrdtFactory`](io.cloudstate.javasupport.crdt.CrdtFactory) that you can use to create the CRDT value that you wish to use.

@@@ note { title=Important }
As with all maps and sets, the values used for map keys and set elements must be immutable, otherwise the elements will be lost, plus in CloudState, any changes made to them will not be replicated to other nodes. Furthermore, their serialized form must be stable. The CloudState proxy uses the serialized form of the values when it's tracking changes, so if the same value serializes to two different sets of bytes on different occasions, they will be treated as different elements in the set or map.

This is particularly relevant when using protobufs. The ordering of map entries in a serialized protobuf is undefined, and very often will be different for two equal maps, hence, maps should never be used in `ORMap` keys or `GSet` or `ORSet` values. For the rest of the protobuf specification, while no guarantees are made on the stability of it by the protobuf specification itself, the Java libraries do produce stable orderings of fields and stable output of non map values. Care though needs to be taken when changing the protobuf structure, many changes that are backwards compatible from a protobuf standpoint do not produce serializations that are stable across the changes.

If using JSON serialization, it is recommended that you explicitly define the field ordering using Jacksons `@JsonPropertyOrder` annotation, and as with protobufs, never use `Map` or `Set` in your JSON objects since the ordering of those is not stable.
@@@

Some wrapper classes are also provided for ORMap. These provide more convenient APIs for working with values of particular CRDT types. They are:

@javadoc[`LWWRegisterMap`](io.cloudstate.javasupport.crdt.LWWRegisterMap)
: A map of LWWRegister values. This exposes the LWWRegister values as values directly in the map.

@javadoc[`PNCounterMap`](io.cloudstate.javasupport.crdt.PNCounterMap)
: A map of PNCounter values. This exposes the current value of the PNCounters directly as values in the map, and offers @javadoc[`increment`](io.cloudstate.javasupport.crdt.PNCounterMap#increment-java.lang.Object-long-) and @javadoc[`decrement`](io.cloudstate.javasupport.crdt.PNCounterMap#decrement-java.lang.Object-long-) methods to update the values.

## Registering the entity

Once you've created your entity, you can register it with the @javadoc[`CloudState`](io.cloudstate.javasupport.CloudState) server, by invoking the @javadoc[`registerCrdtEntity`](io.cloudstate.javasupport.CloudState#registerCrdtEntity-java.lang.Class-com.google.protobuf.Descriptors.ServiceDescriptor-com.google.protobuf.Descriptors.FileDescriptor...-) method. In addition to passing your entity class and service descriptor, if you use protobufs for serialization and the protobufs are not declared in or depended on by the file of your service descriptor, then you'll need to pass those descriptors there too.

@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/crdt/ShoppingCartEntity.java) { #register }
