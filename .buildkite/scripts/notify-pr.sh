#!/bin/bash
#
# Post a comment on the merged PR to notify that the staging deployment is complete.
#

set -euo pipefail

if [[ -z "${MERGED_PR_NUMBER:-}" ]]; then
    echo "No merged PR number found; skipping comment."
    exit 0
fi

echo "Posting comment on PR #${MERGED_PR_NUMBER}"

curl -s -X POST \
    -H "Authorization: token ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github.v3+json" \
    "https://api.github.com/repos/terraware/terraware-server/issues/${MERGED_PR_NUMBER}/comments" \
    -d '{"body": "Staging deployment complete."}'
