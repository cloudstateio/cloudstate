let protocolPath = "protocol.proto";
let grpc = require("grpc");
let protoLoader = require("@grpc/proto-loader");
let protobuf = require("protobufjs");

function startEntity(options, initial, behavior) {
  let packageDefinition = protoLoader.loadSync(
    protocolPath,
    {
      keepCase: true,
      longs: String,
      enums: String,
      defaults: true,
      oneofs: true
    });
  let descriptor = grpc.loadPackageDefinition(packageDefinition);
  let protocol = protobuf.loadSync(protocolPath);



  let Server = new grpc.Server();

  let InMessageCase = descriptor.EntityStreamIn.MessageCase;

  function stream(call) {

    // The current entity state
    var state;

    // The current sequence number
    var sequence;

    // The snapshot handler
    var snapshotHandler;

    // The event handler
    var eventHandler;

    // The command handler
    var commandHandler;

    let context = {
      getState: function () {
        return state;
      },
      getSequence: function () {
        return sequence;
      },
      setEventHander: function (handler) {
        eventHandler = handler;
      },
      setCommandHandler: function (handler) {
        commandHandler = handler;
      }
    };

    call.on("data", function (entityStreamIn) {

      switch (entityStreamIn.getMessageCase()) {
        case InMessageCase.INIT:
          let init = entityStreamIn.getInit();
          if (init.getSnapshot) {

          }
          break;
        case InMessageCase.EVENT:
          let event = entityStreamIn.getEvent();
          break;
        case InMessageCase.COMMAND:
          let command = entityStreamIn.getCommand();
          break;
      }

    });

    call.on("end", function () {
      call.end();
    });
  }


}

module.exports = class Entity {
  setInitial(callback) {
    this.initial = callback;
  }

  setBehavior(callback) {
    this.behavior = behavior;
  }

  start(options) {
    startEntity(options, this.initial, this.behavior)
  }
};