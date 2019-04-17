#!/usr/bin/env bash

set -e

if [ "$DYN_JAVA_OPTS" != "" ]; then
  export JAVA_OPTS="$(eval "echo $DYN_JAVA_OPTS") $JAVA_OPTS"
fi

exec "$@"
