# Image URL to use for all building/pushing image targets
VERSION ?= latest
IMG ?= cloudstateio/cloudstate-operator:${VERSION}

# controller-gen generator and options
CRD_OPTIONS ?= crd

# Get the currently used golang install path (in GOPATH/bin, unless GOBIN is set)
ifeq (,$(shell go env GOBIN))
    GOBIN=$(shell go env GOPATH)/bin
else
    GOBIN=$(shell go env GOBIN)
endif

all: manager

# Run tests
test: generate fmt vet manifests
	go test ./... -coverprofile cover.out

# Build manager binary
manager: generate fmt vet
	go build -o bin/manager cmd/main.go

# Run against the configured Kubernetes cluster in ~/.kube/config
run: generate fmt vet manifests install
	go run ./cmd/main.go

# Install CRDs into cluster.
install: manifests
	kubectl apply -f manifests/crds.yaml

# Deploy the operator.
#    - The envsubst bit mimics what we do in deploy-k8s.sh for configmap.yaml
deploy: install docker-build
	kubectl apply -f manifests/cloudstate-operator.yaml

deploy-native: install docker-build
	kubectl apply -f manifests/cloudstate-operator-native.yaml

kind-deploy: install docker-build
	kind load docker-image ${IMG}
	kubectl apply -f manifests/cloudstate-operator.yaml

# Generate manifests e.g. CRD, RBAC etc.
.PHONY: manifests
manifests:
	controller-gen $(CRD_OPTIONS) rbac:roleName=manager-role webhook paths="./..." output:webhook:artifacts:config=config/webhook output:crd:artifacts:config=config/crd/bases
	kustomize build config/crd > manifests/crds.yaml
	kustomize build config/default > manifests/cloudstate-operator.yaml
	kustomize build config/native-image > manifests/cloudstate-operator-native.yaml

# Run go fmt against code
fmt:
	go fmt ./...

# Run go vet against code
vet:
	go vet ./...

# Generate code
generate:
	controller-gen object:headerFile=./hack/boilerplate.go.txt paths="./..."

# Build the docker image. Use vendoring to speed up CI.
docker-build:
	go mod vendor
	docker build . -t ${IMG}

# Push the docker image
docker-push:
	docker push ${IMG}
