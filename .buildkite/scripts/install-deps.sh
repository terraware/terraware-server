#!/bin/bash
# Most scripts start by installing system-level dependencies by running this script. Since each
# build step can potentially run on a freshly-created host, we need to make sure the necessary
# system packages are installed.

set -euo pipefail

JAVA_VERSION=25
YQ_VERSION=4.45.4
NODE_VERSION=22
PYTHON_VERSION=3.13

install_corretto() {
    if [ -f "/usr/lib/jvm/java-${JAVA_VERSION}-amazon-corretto/bin/java" ]; then
        return
    fi

    echo "Installing Amazon Corretto ${JAVA_VERSION}..."
    sudo rpm --import https://yum.corretto.aws/corretto.key
    sudo curl -sL -o /etc/yum.repos.d/corretto.repo https://yum.corretto.aws/corretto.repo
    sudo dnf install -y "java-${JAVA_VERSION}-amazon-corretto-devel"
}

install_jq() {
    if command -v jq &>/dev/null; then
        return
    fi
    echo "Installing jq..."
    sudo dnf install -y jq
}

install_yq() {
    if command -v yq &>/dev/null && yq --version 2>&1 | grep -q "${YQ_VERSION}"; then
        return
    fi
    echo "Installing yq ${YQ_VERSION}..."
    sudo curl -sL -o /usr/local/bin/yq \
        "https://github.com/mikefarah/yq/releases/download/v${YQ_VERSION}/yq_linux_amd64"
    sudo chmod +x /usr/local/bin/yq
}

install_rsync() {
    if command -v rsync >& /dev/null; then
        return
    fi
    echo "Installing rsync..."
    sudo dnf install -y rsync
}

install_node() {
    if command -v node &>/dev/null; then
        return
    fi
    echo "Installing Node.js ${NODE_VERSION}..."
    curl -fsSL "https://rpm.nodesource.com/setup_${NODE_VERSION}.x" | sudo bash -
    sudo dnf install -y nodejs
}

install_python() {
    if command -v python$PYTHON_VERSION > /dev/null; then
        return
    fi
    echo "Installing Python ${PYTHON_VERSION}..."
    sudo dnf install -y python$PYTHON_VERSION
}

echo "--- :linux: Install system packages"

for arg in "$@"; do
    case "$arg" in
        --java)   install_corretto ;;
        --tools)  install_jq && install_yq && install_rsync ;;
        --node)   install_node ;;
        --python) install_python ;;
        *)
            echo "Unknown argument: $arg"
            exit 1
            ;;
    esac
done
