#!/usr/bin/env bash
#
# Upload release assets to Github
#
# Expects that the release already exists in Github
# Requires a GITHUB_TOKEN for authorization, and a current TRAVIS_TAG
# Requires `jq` to be installed in the Travis build

set -e

readonly tag="$TRAVIS_TAG"
readonly token="$GITHUB_TOKEN"

readonly script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
readonly base_dir="$(cd "$script_dir/.." && pwd)"

readonly owner="cloudstateio"
readonly repo="cloudstate"

readonly version="${tag:1}"
readonly asset="$base_dir/operator/cloudstate-$version.yaml"

readonly release_url="https://api.github.com/repos/$owner/$repo/releases/tags/$tag"
readonly release_id=$(curl -s $release_url | jq -r .id)

readonly auth_header="Authorization: token $token"
readonly asset_url="https://uploads.github.com/repos/$owner/$repo/releases/$release_id/assets?name=$(basename $asset)"

curl -H "$auth_header" -H "Content-Type: application/octet-stream" --data-binary @"$asset" $asset_url
