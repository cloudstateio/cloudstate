# Event sourcing

This page documents how to implement CloudState event sourced entities in Java. For information on what CloudState event sourced entities are, please read the general @ref[Event sourcing](../../features/eventsourced.md) documentation first.

An event sourced entity can be created by annotating it with the @javadoc[`@EventSourcedEntity`](io.cloudstate.javasupport.eventsourced.EventSourcedEntity) annotation.

@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/eventsourced/ShoppingCartEntity.java) { #entity-class }

The `persistenceId` is used to namespace events in the journal, useful for when you share the same database between multiple entities. It defaults to the simple name for the entity class (in this case, `ShoppingCartEntity`), it's good practice to select one explicitly, this means your database isn't depend on classnames in your code.

The `snapshotEvery` parameter controls how often snapshots are taken, so that the entity doesn't need to be recovered from the whole journal each time it's loaded. If left unset, it defaults to 100. Setting it to a negative number will result in snapshots never being taken.

## Persistence types and serialization

Event sourced entities persist events and snapshots, and these need to be serialized when persisted. The most straight forward way to persist events and snapshots is to use protobufs. CloudState will automatically detect if an emitted event is a protobuf, and serialize it as such. For other serialization options, including JSON, see @ref:[Serialization](serialization.md).

While protobufs are the recommended format for persisting events, it is recommended that you do not persist your services protobuf messages, rather, you should create new messages, even if they are identical to the services. While this may introduce some overhead in needing to convert from one type to the other, the reason for doing this is that it will allow the services public interface to evolve independently from its data storage format, which should be private.

For our shopping cart example, we'll create a new file called `domain.proto`, the name domain is selected to indicate that these are my applications domain objects:

@@snip [domain.proto](/docs/src/test/proto/domain.proto)

## State

Each entity should store its state locally in a mutable variable, either a mutable field or a multiple structure such as a collection. For our shopping cart, the state is a map of product ids to products, so we'll create a map to contain that:

@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/eventsourced/ShoppingCartEntity.java) { #entity-state }

## Constructing

The context available for injection into the constructor is a @javadoc[`EventSourcedEntityCreationContext`](io.cloudstate.javasupport.eventsourced.EventSourcedEntityCreationContext). While you don't necessarily need to define a constructor, you can define one and have that context injected in. The constructor below shows having the entity id injected:

@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/eventsourced/ShoppingCartEntity.java) { #constructing }

## Handling commands

Command handlers can be declared by annotating a method with @javadoc[`@CommandHandler`](io.cloudstate.javasupport.eventsourced.CommandHandler). They take a context class of type @javadoc[`CommandContext`](io.cloudstate.javasupport.eventsourced.CommandContext).

By default, the name of the command that the method handles will be the name of the method with the first letter capitalized. So, a method called `getCart` will handle gRPC service call command named `GetCart`. This can be overridden by setting the `name` parameter on the `@CommandHandler` annotation.

The command handler also can take the gRPC service call input type as a parameter, to receive the command message. This is optional, sometimes it's not needed, for example, our `GetCart` service call doesn't need any information from the message, since it's just returning the current state as is. Meanwhile, the `AddItem` service call does need information from the message, since it needs to know the product id, description and quantity to add to the cart.

The return type of the command handler must be the output type for the gRPC service call, this will be sent as the reply.

The following shows the implementation of the `GetCart` command handler. This command handler is a read-only command handler, it doesn't emit any events, it just returns some state:

@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/eventsourced/ShoppingCartEntity.java) { #get-cart }

### Emitting events

Commands that modify the state may do so by emitting events.

@@@ warning
The **only** way a command handler may modify its state is by emitting an event. Any modifications made directly to the state from the command handler will not be persisted, and when the entity is passivated and next reloaded, those modifications will not be present.
@@@

A command handler may emit an event by taking in a @javadoc[`CommandContext`](io.cloudstate.javasupport.eventsourced.CommandContext) parameter, and invoking the `emit` method on it. Invoking `emit` will immediately invoke the associated event handler for that event - this both validates that the event can be applied to the current state, as well as updates the state so that subsequent processing in the command handler can use it.

Here's an example of a command handler that emits an event:

@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/eventsourced/ShoppingCartEntity.java) { #add-item }

This command handler also validates the command, ensuring the quantity items added is greater than zero. Invoking `ctx.fail` fails the command - this method throws so there's no need to explicitly throw an exception.

## Handling events

Event handlers are invoked at two points, when restoring entities from the journal, before any commands are handled, and each time a new event is emitted. An event handlers responsibility is to update the state of the entity according to the event. Event handlers are the only place where its safe to mutate the state of the entity at all.

Event handlers are declared by annotating a method with @javadoc[`@EventHandler`](io.cloudstate.javasupport.eventsourced.EventHandler). They take a context class of type @javadoc[`EventContext`](io.cloudstate.javasupport.eventsourced.EventContext).

Event handlers are differentiated by the type of event they handle. By default, the type of event an event handler handles will be determined by looking for a single non context parameter that the event handler takes. If for any reason this needs to be overridden, or if the event handler method doesn't take any non context parameter (because the event type may be all that needs to be known to handle the event), the type of event the handler handles can be specified using the `eventClass` parameter on the `@EventHandler` annotation.

Event handlers may be declared for a superclass or interface of the types they handle, for example an event handler that declares an `Object` parameter will handle all events. In the case where multiple event handlers match, CloudState will match the most specific event handler, which is decided by walking up the superclass tree, and matching all interfaces along the way.

Here's an example event handler for the `ItemAdded` event. A utility method, `convert` is also defined to assist it.

@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/eventsourced/ShoppingCartEntity.java) { #item-added }

## Producing and handling snapshots

Snapshots are an important optimisation for event sourced entities that may contain many events, to ensure that they can be loaded quickly even when they have very long journals. To produce a snapshot, a method annotated with @javadoc[`@Snapshot`](io.cloudstate.javasupport.eventsourced.Snapshot) must be declared. It takes a context class of type @javadoc[`SnapshotContext`](io.cloudstate.javasupport.eventsourced.SnapshotContext), and must return a snapshot of the current state in serializable form. 

@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/eventsourced/ShoppingCartEntity.java) { #snapshot }

When the entity is loaded again, the snapshot will first be loaded before any other events are received, and passed to a snapshot handler. Snapshot handlers are declare by annotating a method with @javadoc[`@SnapshotHandler`](io.cloudstate.javasupport.eventsourced.SnapshotHandler), and it can take a context class of type @javadoc[`SnapshotBehaviorContext`](io.cloudstate.javasupport.eventsourced.SnapshotBehaviorContext).

Multiple snapshot handlers may be defined to handle multiple different types of snapshots, the type matching is done in the same way as for events.

@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/eventsourced/ShoppingCartEntity.java) { #handle-snapshot }

## Multiple behaviors

In the examples above, our shopping cart entity only has one behavior, and that is declared through the annotations on the entity class. An entity may have different states, where command and event handling may differ according to the state it is currently in. While this could be implemented using if statements in the handlers, CloudState also provides multiple behavior support, so that an entity can change its behavior. This multiple behavior support allows implementing entities as finite state machines.

Entity behavior can be changed when the entity is created, when it handles a snapshot, and when it handles an event, using the `become` method on their respective contexts. The become method accepts an array of objects. Each object declares event handlers and command handlers for that particular behavior, and the handlers on that object will be used going forward for all subsequent commands and events.

Behaviors can also be composed by supplying multiple behavior objects to the `become` method. Behavior objects are checked in order for a behavior, the first behavior that has a matching command or event handler will be invoked.

Typically, behaviors will be implemented as inner classes of the entity, so that they can access the entities state. Alternatively, they can be implemented independently, and can in fact even hold the state immutably themselves - such that behaviors can be used to update the state of the entity.

In the example below, we show a shopping cart that also has a checkout command. Once checked out, the shopping cart no longer accepts any commands to add or remove items, its behavior changes. Handlers that are shared between behaviors are declared on the entity itself, and it is passed as one of the behaviors on each call to change the behavior:

@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/eventsourced/behavior/ShoppingCartEntity.java) { #content }

## Registering the entity

Once you've created your entity, you can register it with the @javadoc[`CloudState`](io.cloudstate.javasupport.CloudState) server, by invoking the `registerEventSourcedEntity` method. In addition to passing your entity class and service descriptor, you also need to pass any descriptors that you use for persisting events, for example, the `domain.proto` descriptor.

@@snip [ShoppingCartEntity.java](/docs/src/test/java/docs/user/eventsourced/ShoppingCartEntity.java) { #register }
