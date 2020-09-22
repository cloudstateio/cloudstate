
const should = require('chai').should();

describe("The CloudState class", () => {

    it("should allow creating and starting a server", () => {

        // tag::start[]
        const cloudstate = require("cloudstate");
        const shoppingcart = require("./shoppingcart");

        const server = new cloudstate.CloudState();
        server.addEntity(shoppingcart);
        server.start();
        // end::start[]

        server.shutdown();
    });

    it("should allow using a custom descriptor name", () => {
        const cloudstate = require("cloudstate");

        // tag::custom-desc[]
        const server = new cloudstate.CloudState({
            descriptorSetPath: "my-descriptor.desc"
        });
        // end::custom-desc[]

    })
});
