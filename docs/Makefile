# Make Cloudstate documentation

module   := core
upstream := cloudstateio/cloudstate
branch   := docs/current
sources  := src src/shared build/src/managed

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

managed: attributes
	mkdir -p build/src/managed
	cp src/shared/antora.yml build/src/managed/antora.yml

attributes: install
	mkdir -p build/src/managed/modules/ROOT/partials
	${cloudstate_antora} version | xargs -0  printf ":cloudstate-version: %s" \
		> build/src/managed/modules/ROOT/partials/attributes.adoc

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
	${cloudstate_antora} validate --no-xrefs --links --links-config config/validate-links.json

deploy: clean managed
	${cloudstate_antora} deploy --module ${module} --upstream ${upstream} --branch ${branch} ${sources}
