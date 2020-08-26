const cloudstate = require("cloudstate");
const entity = new cloudstate.EventSourced("shoppingcart.proto", "example.ShoppingCartService");
entity.setInitial(() => {});
entity.setBehavior(() => {
    return {
        commandHandlers: {},
        eventHandlers: {}
    };
});
module.exports = entity;