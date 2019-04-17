# JavaScript Event Sourced Function example

This is a hypothetical example of what an event sourced function implemented in JavaScript might look like. Since the event sourced function framework doesn't exist, this code doesn't run anywhere, it's provided to demonstrate the user experience of how using this framework will look like, as well as to flesh out some of the ideas in a more concrete setting.

The main business logic is in [`index.js`](index.js). Meanwhile, [`index.js`](library.js) implements a hypothetical event sourced function support library that would be provided for JavaScript users. This library implements the gRPC protocol and provides a higher level abstraction for JavaScript users to code to, use of such a library would not be required, but would be helpful, and the library itself is provided in this repo to demonstrate how the user code maps to the defined gRPC protocol.

There are two protobuf files, one is [`protocol.proto`](protocol.proto), this describes the gRPC interface that is provided by the Event Sourced Function framework itself. The other is [`domain.proto`](domain.proto), this would be written by the JavaScript user to describe their domain events and commands that get embedded inside the gRPC protocol as [`Any`](https://developers.google.com/protocol-buffers/docs/proto3#any) typed messages.

Finally, `js-es-function.yaml` is a Kubernetes spec that shows how this might be deployed, using a hypothetical event sourced function CRD.