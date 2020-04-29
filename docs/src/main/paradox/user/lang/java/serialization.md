# Serialization

Cloudstate functions serve gRPC interfaces, and naturally the input and output messages are protobuf messages that get serialized to the protobuf wire format. However, in addition to these messages, there are a number of places where Cloudstate needs to serialize other objects, for persistence and replication. This includes:

* Event sourced @ref[events and snapshots](eventsourced.md#persistence-types-and-serialization).
* CRDT @ref[map keys and set elements](crdt.md#sets-and-maps), and @ref[LWWRegister values](crdt.md#registers).

Cloudstate supports a number of types and serialization options for these values.

## Primitive types

Cloudstate supports serializing the following primitive types:

| Protobuf type | Java type                      |
|---------------|--------------------------------|
| string        | java.lang.String               |
| bytes         | com.google.protobuf.ByteString |
| int32         | java.lang.Integer              |
| int64         | java.lang.Long                 |
| float         | java.lang.Float                |
| double        | java.lang.Double               |
| bool          | java.lang.Boolean              |

The details of how these are serialized can be found @ref[here](../../../developer/language-support/serialization.md#primitive-values).

## JSON

Cloudstate uses [Jackson](https://github.com/FasterXML/jackson) to serialize JSON. Any classes that are annotated with @javadoc[`@Jsonable`](io.cloudstate.javasupport.Jsonable) will be serialized to and from JSON using Jackson.
The serialization of JSON values is described in @ref[Language support / Cloudstate serialization convention](../../../developer/language-support/serialization.md#json-values).

Note that if you are using JSON values in CRDT sets or maps, the serialization of these values **must** be stable. This means you must not use maps or sets in your value, and you should define an explicit ordering for the fields in your objects, using the [`@JsonPropertyOrder`](http://fasterxml.github.io/jackson-annotations/javadoc/2.9/com/fasterxml/jackson/annotation/JsonPropertyOrder.html) annotation. This constraint is explained in @ref[CRDTs / Sets and Maps](crdt.md#sets-and-maps)
