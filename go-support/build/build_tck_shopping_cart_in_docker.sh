#!/usr/bin/env bash

set -o nounset
set -o errexit
set -o pipefail

SUFFIX=""
if [ "$GOOS" == "windows" ]; then
  SUFFIX=".exe"
fi

./go-support/build/prebuild_go-support_in_docker.sh
cd go-support/shoppingcart/cmd/shoppingcart/
CGO_ENABLED=0 go build -v -o "tck_shoppingcart${SUFFIX}" && echo "go-support compiled successfully: ${GOOS} on ${GOARCH}"