# About counterstate.proto
`counterstate.proto` defines the proto messages for the internal stateful representation of the entity.

The states defined here are used to generate state-specific handler helpers for commands and events.

Our counter has three states:
* Uninitialized
* Active
* Stopped

We also have three corresponding handler implementations that implement our domain logic:
* ActiveCounterHandlerImpl
* StoppedCounterHandlerImpl
* UninitializedCounterImpl

# About counterevent.proto
`counterevent.proto` defines the proto messages for the internal representation of the events pertaining to the entity.

Our counter has four events:
* Initialized
* Started
* Incremented
* Stopped


