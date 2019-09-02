# Event sourcing

Event sourcing is a method of persistence that offers ACID semantics, with horizontal scaling across entities and failure isolation.

Rather than persisting the current state of an entity, an event sourced entity persists all of the events that led to the entity reaching its current state. These events are stored in a journal. When the current state of the entity is needed, the journal is read, and each event is replayed, to compute the current state of the entity.

One of the biggest advantages of event sourcing in a distributed system is that the state of the entity can be reliably replicated to other services and views. Unlike a CRUD based entity, where there's no inherent way to know whether a particular update has been replicated elsewhere, event sourced entities can take advantage of the fact that their events are persisted to a log, and use offset tracking in that log to track which systems have replicated which events. This is a fundamental building block necessary to implement Command Query Responsibility Segregation (CQRS), as it allows read side views to be kept up to date by consuming the event log.

Event sourced entities also allow temporal querying, where the state of an entity can be recreated at any point in history. This is useful both for audit purposes, as the event log can serve as an audit log, as well as for debugging purposes.

## Consistency guarantees

Event sourced entities offer strong consistency guarantees. Event sourced entities are sharded across every node in a stateful service deployment - at any given time, each entity will live on exactly one node. If a command arrives on a particular node for an entity that lives on a different node, that command is forwarded by the proxy to the node where the entity lives on. This forwarding is done transparently, the user function has no knowledge of it occurring.

Because each entity lives on exactly one node, that node can handle commands for each entity sequentially. Hence, there are no concurrency concerns relating to event sourced entities, each entity handles one command at a time.

## Terminology

CloudState uses the following terminology in relation to event sourcing:

### State

The state is the current state on an event sourced entity instance. It is held in memory by an event sourced entity.

### Command

A command is a message addressed to a particular entity to do something. A command comes from a sender, and a reply may be sent to the sender.

### Command handler

A command handler is the code that handles a command. It may validate the command using the current state, and may emit new events as part of its processing. A command handler **must not** update the state of the entity, except by emitting new events. If a command handler does update the state, then when the entity is passivated, those updates will be lost.

### Event

An event is a piece of data that gets persisted indicating something that happened to the entity. Events are stored in a journal, and are read and replayed each time the entity is reloaded by the state management system.

### Event handler

An event handler is the only thing that is allowed to update the state of the entity. It receives events, and, according to the event, updates the state.

### Snapshot

A snapshot is a snapshot of the state, persisted periodically (eg, every 100 events), as an optimization so that when the entity is reloaded from the journal, the entire journal doesn't need to be replayed.

## Language support

For detailed documentation on implementing event sourced entities in your favourite language, follow the links below:

* @ref:[JavaScript](../lang/javascript/eventsourced.md)
* @ref:[Java](../lang/java/eventsourced.md)