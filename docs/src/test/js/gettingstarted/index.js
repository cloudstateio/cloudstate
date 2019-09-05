
function start() {

    // #start
    const cloudstate = require("cloudstate");
    const shoppingcart = require("./shoppingcart");

    const server = new cloudstate.CloudState();
    server.addEntity(shoppingcart);
    server.start();
    // #start
}

function customDesc() {
    const cloudstate = require("cloudstate");

    // #custom-desc
    const server = new cloudstate.CloudState({
       descriptorSetPath: "my-descriptor.desc"
    });
    // #custom-desc
}
