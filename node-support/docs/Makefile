# Make Cloudstate JavaScript documentation

module   := javascript
upstream := cloudstateio/cloudstate
branch   := node-support/docs/current
sources  := src build/src/managed

cloudstate_antora_download := https://github.com/cloudstateio/cloudstate-antora/raw/master/cloudstate-antora
cloudstate_antora := .cache/bin/cloudstate-antora
descriptor := build/site.yml
src_managed := build/src/managed
managed_attachments := ${src_managed}/modules/javascript/attachments
managed_partials := ${src_managed}/modules/javascript/partials

.SILENT:

build: clean managed validate html

${cloudstate_antora}:
	mkdir -p $$(dirname ${cloudstate_antora})
	curl -Lo ${cloudstate_antora} ${cloudstate_antora_download}
	chmod +x ${cloudstate_antora}

clean-cache:
	rm -rf .cache

update: clean-cache ${cloudstate_antora}

clean: ${cloudstate_antora}
	${cloudstate_antora} clean
	# avoid node_modules being indexed by antora
	rm -rf src/modules/javascript/examples/node_modules

managed: attributes apidocs
	mkdir -p "${src_managed}"
	cp src/antora.yml "${src_managed}/antora.yml"

attributes: ${cloudstate_antora}
	mkdir -p "${managed_partials}"
	${cloudstate_antora} version --prefix "node-support-" | xargs -0  printf ":cloudstate-node-support-version: %s" \
		> "${managed_partials}/attributes.adoc"

apidocs:
	npm install && npm run jsdoc
	mkdir -p "${managed_attachments}"
	rsync -a ../apidocs/ "${managed_attachments}/api/"

${descriptor}: ${cloudstate_antora}
	mkdir -p $$(dirname ${descriptor})
	${cloudstate_antora} source --preview --upstream ${upstream} ${sources} > build/source.yml
	${cloudstate_antora} site --preview --exclude ${module} build/source.yml > ${descriptor}

validate: ${descriptor}
	${cloudstate_antora} validate ${descriptor}

html: ${descriptor}
	${cloudstate_antora} build ${descriptor}

build-author-mode: clean managed validate html-author-mode

html-author-mode: ${descriptor}
	${cloudstate_antora} build --author-mode ${descriptor}

validate-links: ${cloudstate_antora}
	${cloudstate_antora} validate --links --no-xrefs

deploy: clean managed
	${cloudstate_antora} deploy --module ${module} --upstream ${upstream} --branch ${branch} ${sources}
