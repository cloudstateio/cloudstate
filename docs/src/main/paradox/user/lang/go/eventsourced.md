# Event sourcing

This page documents how to implement Cloudstate event sourced entities in Go. For information on what Cloudstate event sourced entities are, please read the general @ref[Event sourcing](../../features/eventsourced.md) documentation first.

An event sourced entity can be created by embedding the `cloudstate.EventEmitter` type and also implementing the `cloudstate.Entity` interface.

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/shoppingcart.go) { #entity-type }

Then by composing the Cloudstate entity with a `cloudstate.EventSourcedEntity` and register it with `CloudState.Register()`, your entity gets configured to be an event sourced entity and handled by the Cloudstate instance from now on.

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/eventsourced.go) { #event-sourced-entity-type }

The `ServiceName` is the fully qualified name of the gRPC service that implements this entity's interface. Setting it is mandatory.

The `PersistenceID` is used to namespace events in the journal, useful for when you share the same database between multiple entities. It is recommended to be the name for the entity type (in this case, `ShoppingCart`). Setting it is mandatory.

The `SnapshotEvery` parameter controls how often snapshots are taken, so that the entity doesn't need to be recovered from the whole journal each time it's loaded. If left unset, it defaults to 100. Setting it to a negative number will result in snapshots never being taken.

The `EntityFunc` is a factory method which generates a new Entity whenever Cloudstate has to initialize a new entity. 

## Persistence types and serialization

Event sourced entities persist events and snapshots, and these need to be serialized when persisted. The most straightforward way to persist events and snapshots is to use protobufs. Cloudstate will automatically detect if an emitted event is a protobuf, and serialize it as such. For other serialization options, including JSON, see @ref:[Serialization](serialization.md).

While protobufs are the recommended format for persisting events, it is recommended that you do not persist your service's protobuf messages, rather, you should create new messages, even if they are identical to the service's. While this may introduce some overhead in needing to convert from one type to the other, the reason for doing this is that it will allow the service's public interface to evolve independently from its data storage format, which should be private.

For our shopping cart example, we'll create a new file called `domain.proto`, the name domain is selected to indicate that these are my application's domain objects:

@@snip [domain.proto](/docs/src/test/proto/domain.proto)

## State

Each entity should store its state locally in a mutable variable, either a mutable field or a multiple structure such as an array type or slice. For our shopping cart, the state is a slice of products, so we'll create a slice of LineItems to contain that:

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/shoppingcart.go) { #entity-state }

## Constructing

The Cloudstate Go Support Library needs to know how to construct and initialize entities. For this, an entity has to provide a factory function, `EntityFunc`, which is set during registration of the event sourced entity.

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/shoppingcart.go) { #register }

The entity factory function returns a `cloudstate.Entity` which is composed of two interfaces to handle commands and events.

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/shoppingcart.go) { #constructing }

## Handling commands

An event sourced entity implements the composed `cloudstate.Entity` interface. `cloudstate.Entity` embeds the `cloudstate.EventHandler` interface and therefore entities implementing it get commands from Cloudstate through the event handler's `HandleCommand` method.

The command types received by an event sourced entity are declared by the gRPC Server interface which is generated from the protobuf definitions. The Cloudstate Go Support library together with the registered `cloudstate.EventSourcedEntity` is then able to dispatch commands it gets from the Cloudstate proxy to the event sourced entity.

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/shoppingcart.go) { #handle-command } 

The return type of the command handler is by definition of the service interface, the output type for the gRPC service call, this will be sent as the reply.

The following shows the implementation of the `GetCart` command handler. This command handler is a read-only command handler, it doesn't emit any events, it just returns some state:

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/shoppingcart.go) { #get-cart }

### Emitting events

Commands that modify the state may do so by emitting events.

@@@ warning
The **only** way a command handler may modify its state is by emitting an event. Any modifications made directly to the state from the command handler will not be persisted, and when the entity is passivated and next reloaded, those modifications will not be present.
@@@

A command handler may emit an event by using the embedded `cloudstate.EventEmitter` and invoking the `Emit` method on it. Calling `Emit` will immediately invoke the associated event handler for that event - this both validates that the event can be applied to the current state, as well as updates the state so that subsequent processing in the command handler can use it.

Here's an example of a command handler that emits an event:

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/shoppingcart.go) { #add-item }

This command handler also validates the command, ensuring the quantity of items added is greater than zero. Returning an `error` fails the command and the support library takes care of signaling that back to the requesting proxy as a `Failure` reply.  

## Handling events

Event handlers are invoked at two points, when restoring entities from the journal, before any commands are handled, and each time a new event is emitted. An event handler's responsibility is to update the state of the entity according to the event. Event handlers are the only place where it's safe to mutate the state of the entity at all.

Event handlers are declared by implementing the `cloudstate.EventHandler` interface.

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/event.go) { #event-handler }

Events emitted by command handlers get dispatched to the implemented event handler which then decides how to proceed with the event. 

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/shoppingcart.go) { #handle-event } 

Here's an example of a concrete event handler for the `ItemAdded` event.

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/shoppingcart.go) { #item-added }

## Producing and handling snapshots

Snapshots are an important optimisation for event sourced entities that may contain many events, to ensure that they can be loaded quickly even when they have very long journals. To produce a snapshot, the `cloudstate.Snapshotter` interface has to be implemented that must return a snapshot of the current state in serializable form. 

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/event.go) { #snapshotter }

Here is an example of the TCK shopping cart example creating snapshots for the current `domain.Cart` state of the shopping cart.

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/shoppingcart.go) { #snapshotter }

When the entity is loaded again, the snapshot will first be loaded before any other events are received, and passed to a snapshot handler. Snapshot handlers are declared by implementing the `cloudstate.SnapshotHandler` interface.

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/event.go) { #snapshot-handler }

A snapshot handler then can type-switch over types the corresponding `cloudstate.Snapshotter` interface has implemented.  

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/shoppingcart.go) { #handle-snapshot }

## Registering the entity

Once you've created your entity, you can register it with the `cloudstate.Cloudstate` server, by invoking the `Register` method of a Cloudstate instance. In addition to passing your entity type and service name, you also need to pass any descriptors that you use for persisting events, for example, the `domain.proto` descriptor.

During registration the optional ServiceName and the ServiceVersion can be configured.
(TODO: give an example on how to pick values for these after the spec defines semantics )

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/shoppingcart.go) { #register }
