#!/usr/bin/env bash

# bump these to update the version
kubebuilder_version=2.3.1
controller_gen_version=v0.4.0
kustomize_version=v3.4.0

os=linux
arch=amd64

do_sudo() {
   # Use sudo if non-root (like on a machine type), otherwise don't use sudo (like on a build container)
    if [[ "$(whoami)" == root ]]; then
        $@
    else
        sudo $@
    fi
}

install_kubebuilder() {
    local tmpdir=$(mktemp -d)
    curl -sL "https://go.kubebuilder.io/dl/${kubebuilder_version}/${os}/${arch}" > $tmpdir/kubebuilder.tar.gz
    echo "79820786964eaecba1e90c413d8399600fde7917dfd1b0b74d6536b33f020077 $tmpdir/kubebuilder.tar.gz" -c
    tar -xz -C "$tmpdir/" -f $tmpdir/kubebuilder.tar.gz
    do_sudo mv "$tmpdir/kubebuilder_${kubebuilder_version}_${os}_${arch}" /usr/local/kubebuilder
    do_sudo ln -sf /usr/local/kubebuilder/bin/kubebuilder /usr/local/bin/
    echo "installed kubebuilder $kubebuilder_version to /usr/local/kubebuilder"
    rm -rf "$tmpdir"
}

install_kustomize() {
    local tmpdir=$(mktemp -d)
    curl -sL https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize%2F${kustomize_version}/kustomize_${kustomize_version}_${os}_${arch}.tar.gz > $tmpdir/kustomize.tar.gz
    echo "eabfa641685b1a168c021191e6029f66125be94449b60eb12843da8df3b092ba $tmpdir/kustomize.tar.gz" | sha256sum -c
    tar -xz -C "$tmpdir/" -f $tmpdir/kustomize.tar.gz
    do_sudo mv "$tmpdir/kustomize" /usr/local/bin/kustomize
    echo "installed kustomize $kustomize_version to /usr/local/bin/kustomize"
    rm -rf "$tmpdir"
}

install_controller_gen() {
    GO111MODULE=on go get "sigs.k8s.io/controller-tools/cmd/controller-gen@${controller_gen_version}"
    echo "installed controller-gen $controller_gen_version to GOPATH"
}

main() {
    set -euo pipefail
    if [[ -n "${KUBEBUILDER:-}" ]]; then
        install_kubebuilder
    fi
    if [[ -n "${KUSTOMIZE:-}" ]]; then
        install_kustomize
    fi
    if [[ -n "${CONTROLLER_GEN:-}" ]]; then
        install_controller_gen
    fi
}

# only run main if not sourced
(return 0 2>/dev/null) || main
