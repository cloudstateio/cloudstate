# Releasing Cloudstate node-support

1. Bump the node-support version with `npm version [major|minor|patch]` and commit
2. Create a `node-support-x.y.z` tag and [new release](https://github.com/cloudstateio/cloudstate/releases/new) for the new version
3. Travis will automatically publish the [cloudstate package](https://www.npmjs.com/package/cloudstate) to npm based on the tag
