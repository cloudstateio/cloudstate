ARG version
FROM antora/antora:${version}

RUN apk add git
RUN npm install -g gitlab:antora/xref-validator
RUN npm install -g asciidoc-link-check

LABEL description="antora with xref and link validation tools"
