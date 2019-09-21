#!/usr/bin/env bash

set -o nounset
set -o errexit
set -o pipefail

docker run --rm -v "$PWD":/usr/src/myapp -w /usr/src/myapp golang:1.13 go test -count 1 -race ./...
