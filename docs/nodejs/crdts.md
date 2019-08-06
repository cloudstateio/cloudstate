# CloudState CRDTs on Node.js

CloudState Conflict-free Replicated Data Type (CDRT) support provides in memory data structures for your user function. These structures are eventually consistent, and allow your user function to main state without relying on a database, eliminating that single point of failure and bottleneck.

The CRDTs themselves live in the proxy. The proxy sends deltas to the user function as the CRDTs are updated in other pods. The Node.js `cloudstate` module handles these deltas for you, giving a simple API for working with these data types.

## Defining a CRDT service

A CloudState CRDT service is a gRPC service. Each 


The CRDT support can be imported like so:

```javascript
const crdt = require("cloudstate").crdt;

const entity = new crdt.Crdt(
  "crdts/crdt-example.proto",
  "com.example.crdts.CrdtExample"
);
```

## 

