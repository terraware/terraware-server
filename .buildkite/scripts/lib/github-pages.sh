#!/bin/bash
# Deploy docs to GitHub Pages.

set -euo pipefail

DEPLOY_DIR="docs"
DEPLOY_BRANCH="gh-pages"
DEPLOY_USER="terraformation-deploy"
REPO_URL=${BUILDKITE_REPO/github.com/$DEPLOY_USER:$GITHUB_TOKEN@github.com}

checkout_docs() {
    # Set up a temporary directory for the gh-pages branch
    TEMP_DIR=$(mktemp -d)

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

    echo "$TEMP_DIR"
}

deploy_docs() {
    if [ $# -ne 1 ]; then
        echo "Usage: $0 docs-working-copy-dir" 1>&2
        exit 1
    fi

    cd "$1"

    git add -A

    if git diff --cached --quiet; then
        echo "No changes to deploy."
    else
        echo "--- :github: Deploy to GitHub Pages"

        git config user.name "Buildkite CI"
        git config user.email "nobody@terraformation.com"
        git commit -m "Deploy docs from ${BUILDKITE_COMMIT:0:12}"
        git push origin "$DEPLOY_BRANCH"

        echo "Docs deployed to GitHub Pages."
    fi
}
