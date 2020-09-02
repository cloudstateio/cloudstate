# Make Cloudstate documentation

antora_version      := 2.3.3
antora_docker_image := cloudstateio/cloudstate-antora

docs_dir  := $(shell dirname $(realpath $(lastword $(MAKEFILE_LIST))))
root_dir  := ${docs_dir}/..
build_dir := ${docs_dir}/build
site_dir  := ${build_dir}/site
base_dir  := /antora/$(shell basename ${docs_dir})

build: clean validate-xrefs html apidocs print-site

build-author-mode: clean validate-xrefs html-author-mode apidocs print-site

clean:
	rm -rf ${build_dir}

html:
	docker run \
		-u $(shell id -u):$(shell id -g) \
		-v ${root_dir}:/antora \
		--rm \
		--entrypoint /bin/sh \
		-t ${antora_docker_image}:${antora_version} \
		-c "cd ${base_dir} && antora --cache-dir=.cache/antora --stacktrace site.yml"

html-author-mode:
	docker run \
		-u $(shell id -u):$(shell id -g) \
		-v ${root_dir}:/antora \
		--rm \
		--entrypoint /bin/sh \
		-t ${antora_docker_image}:${antora_version} \
		-c "cd ${base_dir} && antora --cache-dir=.cache/antora --stacktrace author-mode-site.yml"

apidocs:
	cd ${root_dir} && sbt java-support/doc
	cp -r ${root_dir}/java-support/target/api ${site_dir}/docs/current/java/api
	cd ${root_dir}/node-support && npm run jsdoc
	cp -r ${root_dir}/node-support/apidocs ${site_dir}/docs/current/javascript/api

validate-xrefs:
	docker run \
		-v ${root_dir}:/antora \
		--rm \
		--entrypoint /bin/sh \
		-t ${antora_docker_image}:${antora_version} \
		-c 'cd ${base_dir} && NODE_PATH="$$(npm -g root)" antora --generator @antora/xref-validator site.yml'

# Note: variable substitution in links is not currently supported: https://github.com/gaurav-nelson/asciidoc-link-check/issues/16
validate-links:
	docker run \
		-v ${root_dir}:/antora \
		--rm \
		--entrypoint /bin/sh \
		-t ${antora_docker_image}:${antora_version} \
		-c "find ${base_dir} -name '*.adoc' -print0 | xargs -0 -n1 asciidoc-link-check --progress -c ${base_dir}/asciidoc-link-check-config.json"

print-site:
	@echo
	@echo "Cloudstate documentation: ${site_dir}"

preview: build
	@echo
	@echo "Cloudstate documentation: http://localhost:8000/docs/current/"
	@echo
	python3 -m http.server --directory ${site_dir}

preview-author-mode: build-author-mode
	@echo
	@echo "Cloudstate documentation: http://localhost:8000/docs/current/"
	@echo
	python3 -m http.server --directory ${site_dir}
