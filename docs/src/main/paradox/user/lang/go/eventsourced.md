# Event sourcing

This page documents how to implement CloudState event sourced entities in Go. For information on what CloudState event sourced entities are, please read the general @ref[Event sourcing](../../features/eventsourced.md) documentation first.

An event sourced entity can be created by embedding the `cloudstate.EventEmitter` type and also implementing the `cloudstate.EntityInitializer` interface.

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/shoppingcart.go) { #entity-type }

Then by composing the CloudState entity with an `cloudstate.EventSourcedEntity` and register it with `cloudState.Register()`, your entity gets configured to be an event sourced entity and handled by the CloudState instance for now on.

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/eventsourced.go) { #event-sourced-entity-type }

The `PersistenceID` is used to namespace events in the journal, useful for when you share the same database between multiple entities. It defaults to the simple name for the entity type (in this case, `ShoppingCart`), it's good practice to select one explicitly, this means your database isn't depend on type names in your code.

The `SnapshotEvery` parameter controls how often snapshots are taken, so that the entity doesn't need to be recovered from the whole journal each time it's loaded. If left unset, it defaults to 100. Setting it to a negative number will result in snapshots never being taken.

## Persistence types and serialization

Event sourced entities persist events and snapshots, and these need to be serialized when persisted. The most straight forward way to persist events and snapshots is to use protobufs. CloudState will automatically detect if an emitted event is a protobuf, and serialize it as such. For other serialization options, including JSON, see @ref:[Serialization](serialization.md).

While protobufs are the recommended format for persisting events, it is recommended that you do not persist your services protobuf messages, rather, you should create new messages, even if they are identical to the services. While this may introduce some overhead in needing to convert from one type to the other, the reason for doing this is that it will allow the services public interface to evolve independently from its data storage format, which should be private.

For our shopping cart example, we'll create a new file called `domain.proto`, the name domain is selected to indicate that these are my applications domain objects:

@@snip [domain.proto](/docs/src/test/proto/domain.proto)

## State

Each entity should store its state locally in a mutable variable, either a mutable field or a multiple structure such as an array type or slice. For our shopping cart, the state is a slice of products, so we'll create a slice of LineItems to contain that:

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/shoppingcart.go) { #entity-state }

## Constructing

The CloudState Go Support Library needs to know how to construct and initialize entities. For this, an entity has to implement the `cloudstate.EntityInitializer` interface.

(TODO: provide: The constructor below shows having the entity id injected)

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/shoppingcart.go) { #constructing }

## Handling commands

Command handlers are declared by implementing the gRPC ShoppingCartServer interface which is generated from the protobuf definitions. The CloudState Go Support library together with the registered ServiceName in the `cloudstate.EventSourcedEntity` is then able to dispatch commands it gets from the CloudState proxy.

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

This command handler also validates the command, ensuring the quantity items added is greater than zero. Returning a `error` fails the command and the support library takes care of signaling that back to the requesting proxy as a `Failure` reply.  

## Handling events

Event handlers are invoked at two points, when restoring entities from the journal, before any commands are handled, and each time a new event is emitted. An event handlers responsibility is to update the state of the entity according to the event. Event handlers are the only place where its safe to mutate the state of the entity at all.

Event handlers are declared by either implementing the `cloudstate.EventHandler` interface

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/event.go) { #event-handler }

or implementing an unary method that matches the type of the event to be handled. Event handlers are differentiated by the type of event they handle. By default, the type of event an event handler handles will be determined by looking for a single argument that the event handler takes. If for any reason this needs to be overridden, or if the event handler method doesn't exists at all, the event is handed over to the `cloudstate.EventHandler` `Handle` method when the entity implements that interface. The by implementing the `HandleEvent(event interface{}) (handled bool, err error)` method, a event handler indicates if he handled the event or if any occurred, returns an error. The returned error has precedent and the handled flag would not be considered.  

Here's an example event handler for the `ItemAdded` event.

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/shoppingcart.go) { #item-added }

## Producing and handling snapshots

## Multiple behaviors

Multiple behaviors are not supported yet by the Go support library. 

## Registering the entity

Once you've created your entity, you can register it with the `cloudstate.CloudState` server, by invoking the `Register` method of an CloudState instance. In addition to passing your entity type and service name, you also need to pass any descriptors that you use for persisting events, for example, the `domain.proto` descriptor.

During registration the oprtional ServiceName and the ServiceVersion can be configured as Options.

@@snip [shoppingcart.go](/docs/src/main/paradox/user/lang/go/src/shoppingcart.go) { #register }