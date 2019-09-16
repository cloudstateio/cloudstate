#!/usr/bin/env bash

SUFFIX=""
if [ "$GOOS" == "windows" ]; then
  SUFFIX=".exe"
fi

cd shoppingcart/cmd/shoppingcart/ && CGO_ENABLED=0 go build -v -o "tck_shoppingcart${SUFFIX}" && echo "go-support compiled successfully: ${GOOS} on ${GOARCH}"