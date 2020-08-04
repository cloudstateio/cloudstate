#!/usr/bin/env bash

set -o nounset
set -o errexit
set -o pipefail

CMD=${1:-image}
DOCKER_BUILDKIT=1 docker build . -t cloudstateio/cloudstate-devcontainer:latest
if [ "$CMD" == "push" ]; then
  docker push cloudstateio/cloudstate-devcontainer:latest
fi
