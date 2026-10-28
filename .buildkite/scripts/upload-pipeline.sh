#!/usr/bin/env bash
#
# Upload a pipeline to Buildkite. The specific pipeline that gets uploaded is controlled by
# the UPLOAD_PIPELINE_FILE environment variable.
#

set -euo pipefail

if [[ "$BUILDKITE_BRANCH" == "$BUILDKITE_PIPELINE_DEFAULT_BRANCH" ]]; then
    export BUILDKITE_GIT_DIFF_BASE="HEAD~1"
fi

pipeline_file="${UPLOAD_PIPELINE_FILE:-.buildkite/pipeline.yml}"

echo "--- :buildkite: Using pipeline file $pipeline_file"

# Keep each build's checkout separate from other upload jobs on the same agent.
export PER_BUILD_CHECKOUT_PATH="${BUILDKITE_BUILD_CHECKOUT_PATH}-build-${BUILDKITE_BUILD_NUMBER}"

echo "--- :git: Prepare per-build checkout"

find "$(dirname "$BUILDKITE_BUILD_CHECKOUT_PATH")" -maxdepth 1 -type d \
    -name "$(basename "$BUILDKITE_BUILD_CHECKOUT_PATH")-build-*" -mmin +360 -exec rm -rf {} +
rm -rf "$PER_BUILD_CHECKOUT_PATH"
cp -a "$BUILDKITE_BUILD_CHECKOUT_PATH" "$PER_BUILD_CHECKOUT_PATH"
touch "$PER_BUILD_CHECKOUT_PATH"

buildkite-agent pipeline upload "$pipeline_file"
