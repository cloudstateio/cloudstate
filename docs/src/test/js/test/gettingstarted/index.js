
const should = require('chai').should();

describe("The CloudState class", () => {

    it("should allow creating and starting a server", () => {

        // #start
        const cloudstate = require("cloudstate");
        const shoppingcart = require("./shoppingcart");

        const server = new cloudstate.CloudState();
        server.addEntity(shoppingcart);
        server.start();
        // #start

        server.shutdown();
    });

    it("should allow using a custom descriptor name", () => {
        const cloudstate = require("cloudstate");

        // #custom-desc
        const server = new cloudstate.CloudState({
            descriptorSetPath: "my-descriptor.desc"
        });
        // #custom-desc

    })
});
