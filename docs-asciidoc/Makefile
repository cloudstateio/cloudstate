# Make Cloudstate documentation

antora_version      := 2.3.3
antora_docker_image := antora/antora

docs_dir  := $(shell dirname $(realpath $(lastword $(MAKEFILE_LIST))))
root_dir  := ${docs_dir}/..
build_dir := ${docs_dir}/build
site_dir  := ${build_dir}/site
base_dir  := /antora/$(shell basename ${docs_dir})

build: clean install html print-site

clean:
	rm -rf ${build_dir}

# note: we can't currently use package lock with the @djencks dependencies
install:
	docker run \
		-u $(shell id -u):$(shell id -g) \
		-v ${root_dir}:/antora \
		--rm \
		--entrypoint /bin/sh \
		-t ${antora_docker_image}:${antora_version} \
		-c "cd ${base_dir} && rm -f package-lock.json && XDG_CONFIG_HOME=.cache/config npm install --cache=.cache/npm"

html:
	docker run \
		-u $(shell id -u):$(shell id -g) \
		-v ${root_dir}:/antora \
		--rm \
		--entrypoint /bin/sh \
		-t ${antora_docker_image}:${antora_version} \
		-c "cd ${base_dir} && XDG_CONFIG_HOME=.cache/config npm run build"

html-author-mode:
	docker run \
		-u $(shell id -u):$(shell id -g) \
		-v ${root_dir}:/antora \
		--rm \
		--entrypoint /bin/sh \
		-t ${antora_docker_image}:${antora_version} \
		-c "cd ${base_dir} && XDG_CONFIG_HOME=.cache/config npm run build-author-mode"

print-site:
	@echo
	@echo "Cloudstate documentation: ${site_dir}"

preview: html-author-mode
	@echo
	@echo "Cloudstate documentation: http://localhost:8000/docs/current/"
	@echo
	python3 -m http.server --directory ${site_dir}
