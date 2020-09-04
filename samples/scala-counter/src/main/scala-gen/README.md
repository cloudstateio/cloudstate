# About src/main/scala-gen
This directory contains straw-man code that would be generated from the protobuf definitions. The idea is to illustrate
how a protobuf generator might create traits, objects, and case classes that the implementation would build from, 
and how those traits, objects, and cases classes might nest within each other in a manner that's usable but also
durable against name collisions in cases where names for events and states might overlap, or state needs to be 
mapped to the value of a response object.

We see three types of code generated here:
* case classes for command request and response objects, and traits to abstract them
* case classes for events and states, and traits to abstract them
* Base handler implementations

The handlers are designed to allow logic to be organized by the entity state.

Rather than mutating state and explicitly implementing the snapshot handling and loading, this api injects state
into command and event handlers, and expects event handlers to return the new state. Snapshot handling is assumed
to be provided under-the-hood.

Also provided under-the-hood is generation of resource-style value-based responses. For these to work we provide
implmentations of a `mapDomainCounterToApiCounter` in our state handlers. 