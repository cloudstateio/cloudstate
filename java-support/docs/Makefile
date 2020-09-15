# Make Cloudstate Java documentation

module   := java
upstream := cloudstateio/cloudstate
branch   := java-support/docs/current
sources  := src build/src/managed

cloudstate_antora := .cache/bin/cloudstate-antora

root_dir := $(shell git rev-parse --show-toplevel)

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

managed: attributes apidocs
	mkdir -p build/src/managed
	cp src/antora.yml build/src/managed/antora.yml

attributes: install
	mkdir -p build/src/managed/modules/java/partials
	${cloudstate_antora} version --prefix "java-support-" | xargs -0  printf ":cloudstate-java-support-version: %s" \
		> build/src/managed/modules/java/partials/attributes.adoc

apidocs:
	cd ${root_dir} && sbt java-support/doc
	mkdir -p build/src/managed/modules/java/attachments
	rsync -a ${root_dir}/java-support/target/api/ build/src/managed/modules/java/attachments/api/

descriptor: install
	mkdir -p build
	${cloudstate_antora} source --preview --upstream ${upstream} ${sources} > build/source.yml

validate: descriptor
	${cloudstate_antora} validate --site-from config/core.yml build/source.yml

html: descriptor
	${cloudstate_antora} build --site-from config/core.yml build/source.yml

preview: build
	${cloudstate_antora} preview

build-author-mode: clean managed validate html-author-mode

html-author-mode: descriptor
	${cloudstate_antora} build --author-mode --site-from config/core.yml build/source.yml

preview-author-mode: build-author-mode
	${cloudstate_antora} preview

validate-links: install
	${cloudstate_antora} validate --no-xrefs --links --links-config config/validate-links.json

deploy: clean managed
	${cloudstate_antora} deploy --module ${module} --upstream ${upstream} --branch ${branch} ${sources}
