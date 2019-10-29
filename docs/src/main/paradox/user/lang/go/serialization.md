# Serialization

Cloudstate functions serve gRPC interfaces, and naturally the input messages and output messages are protobuf messages that get serialized to the protobuf wire format. However, in addition to these messages, there are a number of places where Cloudstate needs to serialize other objects, for persistence and replication. This includes:

* Event sourced @ref[events and snapshots](eventsourced.md#persistence-types-and-serialization).
* CRDT @ref[map keys and set elements](crdt.md), and @ref[LWWRegister values](crdt.md).

Cloudstate supports a number of types and serialization options for these values.

## Primitive types

Cloudstate supports serializing the following primitive types:

| Protobuf type | Go type     |
|---------------|-------------|
| string        | string      |
| bytes         | []byte      |
| int32         | int32       |
| int64         | int64       |
| float         | float32     |
| double        | float64     |
| bool          | bool        |

The details of how these are serialized can be found @ref[here](../../../developer/language-support/serialization.md#primitive-values).

@@@ note { title=Important }
Go has a set of [predeclared numeric](https://golang.org/ref/spec#Numeric_types) types with implementation-specific sizes. One of them is `int` which would be an int64 on 64-bit systems CPU architectures. Cloudstate does not support implicit conversion between an `int` and the corresponding `int64` as an input type for the serialization. The main reason not to support it is, that an `int` is not the same type as an `int64` and therefore a de-serialized value would have to be converted back to an `int` as it is of type `int64` during its serialized state.    
@@@

## JSON

Cloudstate uses the standard library package [`encoding/json`](https://golang.org/pkg/encoding/json/) to serialize JSON. Any type that has a field declared with a string literal tag ``json:"fieldname"`` will be serialized to and from JSON using the [Marshaller and Unmarshaller](https://golang.org/pkg/encoding/json/#Marshal) from the Go standard library package `encoding/json`.

The details of how these are serialized can be found @ref[here](../../../developer/language-support/serialization.md#json-values).

Note that if you are using JSON values in CRDT sets or maps, the serialization of these values **must** be stable. This means you must not use maps or sets in your value, and you should define an explicit ordering for the fields in your objects.
**(TODO: mention the ordering of fields here by the Go standard library implementation).**
