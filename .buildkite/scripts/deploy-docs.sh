#!/bin/bash
#
# Deploy docs to GitHub Pages.
# Replaces JamesIves/github-pages-deploy-action from GHA.

set -euo pipefail

.buildkite/scripts/install-deps.sh --java --python --tools

# Allow generated docs to be committed to gh-pages branch
rm -f docs/.gitignore

echo "--- :gradle: Generate schema docs"

mkdir -p docs/schema
export SCHEMA_DOCS_DIR=docs/schema
export LOGGING_LEVEL_ORG_SPRINGFRAMEWORK=ERROR
./gradlew --build-cache -x generateJooqClasses test --tests SchemaDocsGenerator --info

echo "--- :gradle: Generate license report"

./gradlew --build-cache -x generateJooqClasses generateLicenseReport

echo "--- :git: Generate unreleased commits log"

git fetch --tags --depth=1
LAST_TAG=$(git tag --list --sort=creatordate 'v[0-9]*' | tail -n1)

.buildkite/scripts/lib/fetch-tag.sh "$LAST_TAG"

git log "${LAST_TAG}..HEAD" --oneline > docs/unreleased.log

echo "--- :python: Generate basic list of notifications"

python3 ./.github/scripts/notifications.py

# This section is a port of GHA's github-pages-deploy-action.

echo "--- :github: Prepare files for deployment"

DEPLOY_DIR="docs"
DEPLOY_BRANCH="gh-pages"
DEPLOY_USER="terraformation-deploy"

REPO_URL=${BUILDKITE_REPO/github.com/$DEPLOY_USER:$GITHUB_TOKEN@github.com}

# Set up a temporary directory for the gh-pages branch
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT

git clone --branch "$DEPLOY_BRANCH" --single-branch --depth 1 \
    "$REPO_URL" \
    "$TEMP_DIR" || {
        # If gh-pages doesn't exist yet, create it as an orphan
        git init "$TEMP_DIR"
        cd "$TEMP_DIR"
        git checkout --orphan "$DEPLOY_BRANCH"
        git remote add origin "$REPO_URL"
        cd -
    }

# Sync docs into the deploy directory
rsync -a --exclude=.git --delete "$DEPLOY_DIR/" "$TEMP_DIR/"

cd "$TEMP_DIR"
git add -A

if git diff --cached --quiet; then
    echo "No changes to deploy."
    exit 0
fi

echo "--- :github: Deploy to GitHub Pages"

git config user.name "Buildkite CI"
git config user.email "nobody@terraformation.com"
git commit -m "Deploy docs from ${BUILDKITE_COMMIT:0:12}"
git push origin "$DEPLOY_BRANCH"

echo "Docs deployed to GitHub Pages."
