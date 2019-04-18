const path = require("path");
const protobuf = require("protobufjs");
const AnySupport = require ("./protobuf-any");

const FileDescriptorProto = protobuf
  .loadSync(path.join(__dirname, "..", "proto-ext", "google", "protobuf", "descriptor.proto"))
  .lookupType("google.protobuf.FileDescriptorProto");


// Support for doing this exists here:
// https://github.com/protobufjs/protobuf.js/tree/master/ext/descriptor
// However that has been found to be quite buggy, so we're doing it manually.
module.exports.serviceToDescriptor = function serviceToDescriptor(root, serviceName) {
  const service = root.lookupService(serviceName);
  const pkg = AnySupport.fullNameOf(service.parent);
  const typeNames = new Set(Object.keys(service.methods).map(key => {
    const requestType = service.methods[key].requestType;
    if (requestType.includes(".")) {
      return requestType;
    } else if (pkg) {
      return pkg + "." + requestType;
    } else {
      return requestType;
    }
  }));

  const types = Array.from(typeNames).map(name => {
    const type = root.lookupType(name);

    // Only include the entity_key fields, that's all that's needed
    const fieldKeys = Object.keys(type.fields).filter(key => {
      return type.fields[key].options &&
        type.fields[key].options.hasOwnProperty("(com.lightbend.statefulserverless.grpc.entity_key)");
    });

    function toLabel(rule) {
      switch (rule) {
        case "repeated": return 3;
        case "required": return 2;
        default: return 1;
      }
    }

    function toType(type) {
      switch (type) {
        case "double": return 1;
        case "float": return 2;
        case "int64": return 3;
        case "uint64": return 4;
        case "int32": return 5;
        case "fixed64": return 6;
        case "fixed32": return 7;
        case "bool": return 8;
        case "string": return 9;
        case "bytes": return 12;
        case "uint32": return 13;
        case "sfixed32": return 15;
        case "sfixed64": return 16;
        case "sint32": return 17;
        case "sint64": return 18;
      }
    }

    // DescriptorProto
    return {
      name: type.name,
      field: fieldKeys.map(key => {
        const field = type.fields[key];

        // FieldDescriptorProto
        return {
          name: field.name,
          number: field.id,
          label: toLabel(field.rule),
          type: toType(field.type),
          defaultValue: field.defaultValue,
          options: {
            ".com.lightbend.statefulserverless.grpc.entityKey": true
          }
        }
      })
    };

  });

  types.push(
    // DescriptorProto
    {
      name: "Empty",
      field: []
    }
  );

  // Empty output type
  let outputType = "Empty";
  if (pkg) {
    outputType = pkg + ".Empty";
  }

  // FileDescriptorProto
  const fdp = {
    name: service.filename,
    package: pkg,
    // ServiceDescriptorProto
    service: [{
      name: service.name,
      method: Object.keys(service.methods).map(key => {
        const method = service.methods[key];
        // MethodDescriptorProto
        return {
          name: method.name,
          inputType: method.requestType,
          outputType: outputType,
          clientStreaming: method.requestStream,
          serverStreaming: method.responseStream
        };
      })
    }],
    messageType: types
  };

  const error = FileDescriptorProto.verify(fdp);
  if (error) {
    throw new Error(error);
  }

  return fdp;
};
