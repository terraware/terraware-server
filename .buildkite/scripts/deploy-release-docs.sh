#!/bin/bash
# Deploy docs to GitHub Pages for production releases.

set -euo pipefail

.buildkite/scripts/install-deps.sh --java --python --tools

. .buildkite/scripts/lib/github-pages.sh

echo "--- :gradle: Generate schema docs"

mkdir -p docs/schema
export SCHEMA_DOCS_DIR=docs/schema
export LOGGING_LEVEL_ORG_SPRINGFRAMEWORK=ERROR
./gradlew -x generateJooqClasses test --tests SchemaDocsGenerator --info

echo "--- :gradle: Generate license report"

./gradlew -x generateJooqClasses generateLicenseReport

echo "--- :python: Generate basic list of notifications"

python3 ./.github/scripts/notifications.py

echo "--- :github: Clear unreleased commits log"

cp /dev/null docs/unreleased.log

echo "--- :github: Check out GitHub Pages branch"

TEMP_DIR=$(checkout_docs)
trap 'rm -rf "$TEMP_DIR"' EXIT

REPO_URL=${BUILDKITE_REPO/github.com/$DEPLOY_USER:$GITHUB_TOKEN@github.com}

# Sync docs into the deploy directory
rsync -a --exclude=.git --delete \
    "docs/schema" \
    "docs/license-report" \
    "docs/notifications.html" \
    "docs/unreleased.log" \
    "$TEMP_DIR/"

deploy_docs "$TEMP_DIR"
