# Make Antora docker image with validation tools

antora_version := 2.3.3

build:
	docker build --build-arg version=${antora_version} -t cloudstateio/cloudstate-antora:${antora_version} .
	docker push cloudstateio/cloudstate-antora:${antora_version}
