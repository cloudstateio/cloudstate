# About counterapi.proto
`counterapi.proto` defines the gRPC service and associated messages for the counter entity.

Our service defines five commands:
* Initialize
* Start
* Increment
* Stop
* GetCounter

This API adopts the following patterns:
* A unique response type exists defined for each service rpc
* Command responses are resource-style, and return a stateful representation of the entity
