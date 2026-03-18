#!/usr/bin/env bash

set -euo pipefail

PYTHON_VERSION=3.13

.buildkite/scripts/install-deps.sh --python

echo "--- :python: Install packages"

cd scripts

VENV_DIR="$(pwd)/.venv"

if [ -f "${VENV_DIR}/bin/python" ]; then
    venv_python_version=$("${VENV_DIR}/bin/python" -V | cut -d' ' -f2 | cut -d. -f1-2)

    if [ "$venv_python_version" != "$PYTHON_VERSION" ]; then
        echo "venv Python version is ${venv_python_version}; want ${PYTHON_VERSION}"
        rm -rf "$VENV_DIR"
    fi
fi

if [ ! -d "$VENV_DIR" ]; then
    echo "Creating venv with $(python$PYTHON_VERSION -V)"
    python$PYTHON_VERSION -m venv "$VENV_DIR"
fi

PATH="${VENV_DIR}/bin:$PATH"
export PATH

pip3 install -r requirements.txt

echo "--- :python: Check formatting"

black --check .

echo "--- :python: Check types"

mypy .
