# Make Cloudstate JavaScript documentation

module   := javascript
upstream := cloudstateio/cloudstate
branch   := node-support/docs/current
sources  := src build/src/managed

cloudstate_antora := .cache/bin/cloudstate-antora

.SILENT:

build: clean managed validate html

install:
	[ -f ${cloudstate_antora} ] || ( \
		mkdir -p .cache/bin ;\
		curl -Lo ${cloudstate_antora} https://github.com/cloudstateio/cloudstate-antora/raw/master/cloudstate-antora ;\
		chmod +x ${cloudstate_antora} )

clean-cache:
	rm -rf .cache

update: clean-cache install

clean: install
	${cloudstate_antora} clean
	# avoid node_modules being indexed by antora
	rm -rf src/modules/javascript/examples/node_modules

managed: attributes apidocs
	mkdir -p build/src/managed
	cp src/antora.yml build/src/managed/antora.yml

attributes: install
	mkdir -p build/src/managed/modules/javascript/partials
	${cloudstate_antora} version --prefix "node-support-" | xargs -0  printf ":cloudstate-node-support-version: %s" \
		> build/src/managed/modules/javascript/partials/attributes.adoc

apidocs:
	npm install && npm run jsdoc
	mkdir -p build/src/managed/modules/javascript/attachments
	rsync -a ../apidocs/ build/src/managed/modules/javascript/attachments/api/

descriptor: install
	[ -f "build/site.yml" ] || ( \
		mkdir -p build ;\
		${cloudstate_antora} source --preview --upstream ${upstream} ${sources} > build/source.yml ;\
		${cloudstate_antora} site --preview --exclude ${module} build/source.yml > build/site.yml )

validate: descriptor
	${cloudstate_antora} validate build/site.yml

html: descriptor
	${cloudstate_antora} build build/site.yml

preview: build
	${cloudstate_antora} preview

build-author-mode: clean managed validate html-author-mode

html-author-mode: descriptor
	${cloudstate_antora} build --author-mode build/site.yml

preview-author-mode: build-author-mode
	${cloudstate_antora} preview

validate-links: install
	${cloudstate_antora} validate --links --no-xrefs

deploy: clean managed
	${cloudstate_antora} deploy --module ${module} --upstream ${upstream} --branch ${branch} ${sources}
