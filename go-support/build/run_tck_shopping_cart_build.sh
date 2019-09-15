#!/usr/bin/env bash

cd go-support
docker run --rm -v "$PWD":/usr/src/myapp -w /usr/src/myapp -e GOOS=${1} -e GOARCH=${2} \
golang:1.13 ./build/build_tck_shopping_cart_in_docker.sh