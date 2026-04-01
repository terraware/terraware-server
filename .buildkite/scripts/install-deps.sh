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

install_ecr_credential_helper() {
    if command -v docker-credential-ecr-login &>/dev/null; then
        return
    fi

    echo "Installing Amazon ECR Docker Credential Helper..."

    local version="0.12.0"
    local arch
    case "$(uname -m)" in
        aarch64) arch="arm64" ;;
        x86_64)  arch="amd64" ;;
        *)
            echo "Unsupported architecture: $(uname -m)"
            exit 1
            ;;
    esac

    local url="https://amazon-ecr-credential-helper-releases.s3.us-east-2.amazonaws.com/${version}/linux-${arch}/docker-credential-ecr-login"
    curl -fsSL "$url" -o /tmp/docker-credential-ecr-login
    sudo install -m 755 /tmp/docker-credential-ecr-login /usr/local/bin/docker-credential-ecr-login
    rm -f /tmp/docker-credential-ecr-login
}

echo "--- :linux: Install system packages"

for arg in "$@"; do
    case "$arg" in
        --java)   install_corretto ;;
        --tools)  install_jq && install_yq && install_rsync ;;
        --node)   install_node ;;
        --python) install_python ;;
        --ecr)    install_ecr_credential_helper ;;
        *)
            echo "Unknown argument: $arg"
            exit 1
            ;;
    esac
done
