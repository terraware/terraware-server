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

buildkite-agent pipeline upload "$pipeline_file"
