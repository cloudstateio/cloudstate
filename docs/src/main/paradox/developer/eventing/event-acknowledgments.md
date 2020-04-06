# Event Acknowledgement

One of the hardest challenges with a developer friendly event processing API is providing for simple, intuitive event acknowledgement, at the time that the developer means to acknowledge the event. This page seeks to define the problem, and propose solutions.

## Problem definition

The simplest event processor is one in which there is a one to one mapping of events in to events out. In this use case, it's dead simple to know and understand when an incoming event should be acknowledged as processed - when the outgoing event is published, the incoming event should be acknowledged.

We are going to assume that the processing guarantees we want are at-least-once - so, the publish of the outgoing event and acknowledgment of the incoming event don't need to be atomic, after an outgoing event is published, and acknowledgment that that publish is complete (eg, synced to disk or replicated to a minimum number of nodes in the outgoing receiver), the incoming event can be acknowledged in a separate operation. If this acknowledgement fails, then the incoming message will be retried at some point later, resulting in a duplicate outgoing event being published, and so on, until the incoming message is acknowledged. Thus, at least once guarantees are maintained. For now, we will not consider either at most once, or strategies for achieving effectively exactly once processing.

Where it gets more challenging is when processing of events is not one to one. One to many is very common. Many to one is not as common in microservices (though in streaming data aggregation solutions is very common), but we will discuss it nevertheless.

### Common use cases

We will assume a fairly well understood problem domain to frame our use cases in. We have an event sourced entity that represents a user, which is used to provide a user management service. This user entity might have events such as `UserCreated`, `PasswordChanged`, `EmailChanged`. These events are how the user entity internally represents a user, and their schema should be able to change freely to accommodate the needs of the user entity without affecting any downstream services that might consume these events. Migrations may be defined on them to evolve these events over time. For this reason, this stream is not published directly to external services, rather, an anti-corruption layer is used, that translates these events to an external representation of the same events.

Unlike the internal events from the event log, the external events cannot be changed freely, their schema forms a contract with the downstream services that consumes them. When the internal events change in their structure, the external events must not change in a way that will break backwards compatibility.

Note that in the use cases below, we're not trying to build a coherent model of a user management service, nor are we trying to design the perfect user management service. Rather, we're saying "let's assume that the business has requirement X", and then discuss how we might provide the mechanism to implement that. These requirements are a given, and hopefully are intuitively relatable, even if that's not how you would necessarily design a system to function. The point is, it's up to the business to make its own decisions, and Cloudstate to provide the mechanisms to make those decisions possible to implement, whatever they are.

#### Filtering

Not all events in the internal model will necessarily map to events in the external model. For example, a business decision might be that a users password might be the domain of the user management service and the user management service alone. When a `PasswordChanged` event is emitted from the event log, no event should then be published externally. Effectively, the `PasswordChanged` event must be filtered from the event stream.

The challenge in acknowledgement here is when should the `PasswordChanged` event be acknowledged, and how should the user function indicate this? Since no event is being output, the output of an event cannot be used to indicate that the `PasswordChanged` event should be acknowledged. Rather, it's the absence of an output event that should indicate that a `PasswordChanged` event should be acknowledged. But how do we differentiate between on output event, and slow processing of the input event? Does the Cloudstate proxy say "well, you haven't output anything for 10 seconds, so I'm going to acknowledge?". This puts a 10 second latency on the processing of all `PasswordChanged` events, so that solution doesn't work. Do we have a specific ack signal, which accompanies no event?

#### Unfolding

Sometimes, one input event may need to be unfolded into multiple events. In Akka Streams parlance, this would be a `mapConcat`. Consider the case where in the internal event store, your `UserCreated` event contains an email address, but a business decision is that the external event published when a user created does not contain an email, rather, it should be followed by an email changed event. So, when we process a `UserCreated` internal event, we need to output two external events, a `UserCreated` and an `EmailChanged` event.

In this case, we do not want to acknowledge the `UserCreated` internal event until both the external events are published. If just the first one is published, and the second one fails, the internal event should not be acknowledged, and it should be reprocessed later.

So again, the challenge is communicating on which event should the internal event be acknowledged. The mere signal of an output event does not convey enough information to indicate this, it's only the successful publish of the last event from the expanding list of events that should trigger acknowledgement.

#### Folding

Sometimes, multiple events need to be folded into a single event. Note here that we're not talking about folding an entire stream into a single value, we're talking about combining multiple events in a stream into a single value. For a use case, the converse of the unfolding scenario applies, perhaps internally, you have a `UserCreated` and `EmailChanged` event, but a business decision is that the external `UserCreated` event should combine these too.

The challenge with folding is that it is inherently stateful. You need to track events that are in the process of being folded, and delay acknowledging them until the fold is complete.

However, doing this can be dangerous, and lead to deadlocks. The reason being that typically, the number of unacknowledged events must be limited because unacknowledged events require buffering to track, so that once the head of the queue gets acknowledged, the rest too can be acknowledged. This does depend on message broker though, for message brokers that track acknowledgements to each message independently, it may be fine, but for offset based message brokers like Kafka, it's a problem. Consider the case where a single entity may emit a `UserCreated` and then an `EmailChanged` event, but concurrently, many other entities emit events, so between those two events in the stream for all user events there may be tens, hundreds or thousands of events. If the number of events interleaved here is greater than the configured max outstanding events, then a deadlock will be reached, where the `UserCreated` event can't be acknowledged because the `EmailChanged` event has not yet been received, but the `EmailChanged` event is not being received because the limit of unacknowledged events has been reached, which is being blocked by the waiting `UserCreated` event acknowledgement.

For this reason, such stateful folds are best done with a persistent save point in the middle, such that the first event can be acknowledged immediately. In this case, what we are essentially doing is a filter with a side effect to a persistent store - when we receive the `UserCreated` event, we persist that we've received it, and emit no event, then when we receive the `EmailChanged` event, we load the persisted state that was caused by the `UserCreated` event, and emit the corresponding folded event.



## Developer experience

