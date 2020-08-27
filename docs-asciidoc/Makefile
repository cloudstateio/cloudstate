# Make Cloudstate documentation

antora_version      := 2.3.3
antora_docker_image := antora/antora

docs_dir  := $(shell dirname $(realpath $(lastword $(MAKEFILE_LIST))))
root_dir  := ${docs_dir}/..
build_dir := ${docs_dir}/build
site_dir  := ${build_dir}/site
base_dir  := /antora/$(shell basename ${docs_dir})

build: clean html apidocs print-site

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

print-site:
	@echo
	@echo "Cloudstate documentation: ${site_dir}"

preview: html-author-mode
	@echo
	@echo "Cloudstate documentation: http://localhost:8000/docs/current/"
	@echo
	python3 -m http.server --directory ${site_dir}
