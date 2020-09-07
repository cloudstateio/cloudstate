### What have been done:
- [x] validating the protocol definition
- [x] validating the implementation of the user facing interface
- [x] validating the protocol implementation based on event sourcing
- [x] write tests for the annotation support and the entity based on event sourcing
- [x] provide an sample in a dedicated project for CRUD

### What should be reviewed:
- [ ] native CRUD support based on Slick
- [ ] In memory CRUD support added
- [ ] the protocol implementation based on native CRUD
- [ ] remove the snapshot from the GRPC protocol
- [ ] remove the sequence number from the GRPC protocol

### What are the next steps:
- [ ] add Postgres native CRUD support
- [ ] remove the sequence number from the protocol from the implementation
- [ ] add generic type for io.cloudstate.javasupport.crud.CommandContext
- [ ] deal with null value for io.cloudstate.javasupport.crud.CommandContext#updateEntity
- [ ] deal with the order of call for io.cloudstate.javasupport.crud.CommandContext#deleteEntity and io.cloudstate.javasupport.crud.CommandContext#updateEntity
- [ ] write tests
- [ ] extend the TCK