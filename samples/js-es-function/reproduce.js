const grpc = require("grpc");
const protoLoader = require("@grpc/proto-loader");

const packageDefinition = protoLoader.loadSync("reproduce.proto", {});
const descriptor = grpc.loadPackageDefinition(packageDefinition);

const server = new grpc.Server();
server.addService(descriptor.foo.Foo.service, {
  bar: (empty, callback) => {
    callback(null, {any: { value: Buffer.from("the bytes", "utf8"), type_url: "foo"}, string: "the string"});
  }
});
server.bind("0.0.0.0:8080", grpc.ServerCredentials.createInsecure());
server.start();

const client = new descriptor.foo.Foo("localhost:8080", grpc.credentials.createInsecure());
client.bar({}, (err, rsp) => {
  console.log("string: " + rsp.string);
  console.log("any.typeUrl: " + rsp.any.type_url);
  console.log("any.value: " + rsp.any.value);
});

