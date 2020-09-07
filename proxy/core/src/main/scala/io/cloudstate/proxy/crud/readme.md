### What have been done:
- validating the protocol definition :white_check_mark:
- validating the implementation of the user facing interface :white_check_mark:
- validating the protocol implementation based on event sourcing :white_check_mark:
- write tests for the annotation support and the entity based on event sourcing :white_check_mark:
- provide an sample in a dedicated project for CRUD :white_check_mark:

### What should be reviewed:
- native CRUD support based on Slick
- In memory CRUD support added
- the protocol implementation based on native CRUD
- remove the snapshot from the GRPC protocol
- remove the sequence number from the GRPC protocol

### What are the next steps:
- add Postgres native CRUD support
- remove the sequence number from the protocol from the implementation :white_check_mark:
- add generic type for io.cloudstate.javasupport.crud.CommandContext :white_check_mark:
- deal with null value for io.cloudstate.javasupport.crud.CommandContext#updateEntity
- deal with the order of call for io.cloudstate.javasupport.crud.CommandContext#deleteEntity and io.cloudstate.javasupport.crud.CommandContext#updateEntity
- write tests
- extend the TCK