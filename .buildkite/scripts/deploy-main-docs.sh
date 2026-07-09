#!/bin/bash
# Deploy docs to GitHub Pages for changes to the main branch.

set -euo pipefail

. .buildkite/scripts/lib/github-pages.sh

echo "--- :github: Check out GitHub Pages branch"

TEMP_DIR=$(checkout_docs)
trap 'rm -rf "$TEMP_DIR"' EXIT

echo "--- :git: Generate unreleased commits log"

git fetch --tags --depth=1
LAST_TAG=$(git tag --list --sort=creatordate 'v[0-9]*' | tail -n1)

.buildkite/scripts/lib/fetch-tag.sh "$LAST_TAG"

git log "${LAST_TAG}..HEAD" --oneline > "docs/unreleased.log"

rsync -a "docs/" "$TEMP_DIR/"

deploy_docs "$TEMP_DIR"
