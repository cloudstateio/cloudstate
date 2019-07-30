# CloudState CRDTs on Node.js

CloudState CRDTs


The CRDT support can be imported like so:

```javascript
const crdt = require("cloudstate").crdt;

const entity = new crdt.Crdt(
  "crdts/crdt-example.proto",
  "com.example.crdts.CrdtExample"
);
```

