FROM node:8.15

# install deps
run mkdir -p /tmp/samples/js-shopping-cart
ADD samples/js-shopping-cart/package.json /tmp/samples/js-shopping-cart/package.json
COPY ./node-support /tmp/node-support
RUN cd /tmp/samples/js-shopping-cart && npm install

# Copy deps
RUN mkdir -p /opt/samples/js-shopping-cart && cp -a /tmp/samples/js-shopping-cart/node_modules /opt/samples/js-shopping-cart && cp -a /tmp/node-support /opt

# Setup workdir
WORKDIR /opt/samples/js-shopping-cart
COPY samples/js-shopping-cart /opt/samples/js-shopping-cart

# run
EXPOSE 8080
CMD ["npm", "start"]
