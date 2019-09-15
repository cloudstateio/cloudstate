#!/usr/bin/env bash

echo "building go TCK shoppingcart for OS: ${GOOS} on ${GOARCH}"

SUFFIX=""
if [ "$GOOS" == "windows" ]; then
  SUFFIX=".exe"
fi

cd shoppingcart/cmd/shoppingcart/ && CGO_ENABLED=0 go build -v -o "tck_shoppingcart${SUFFIX}"