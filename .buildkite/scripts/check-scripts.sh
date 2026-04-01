#!/usr/bin/env bash

set -euo pipefail

PYTHON_VERSION=3.13

.buildkite/scripts/install-deps.sh --python

echo "--- :python: Install packages"

cd scripts

PIP_CACHE="$(pwd)/.pip-cache"
VENV_DIR="$(pwd)/.venv"

echo "Creating venv with $(python$PYTHON_VERSION -V)"
python$PYTHON_VERSION -m venv "$VENV_DIR"

PATH="${VENV_DIR}/bin:$PATH"
export PATH

PIP_CACHE="$PIP_CACHE" pip3 install -r requirements.txt

echo "--- :python: Check formatting"

black --check .

echo "--- :python: Check types"

mypy .
