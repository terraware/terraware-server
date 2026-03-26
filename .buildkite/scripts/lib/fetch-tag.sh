#!/usr/bin/env bash
#
# Fetch all the commits between a particular tag and the current HEAD.
#
set -euo pipefail

if [ $# -ne 1 ]; then
    echo "Usage: $0 tag-to-fetch"
    exit 1
fi

SEARCH_TAG="$1"

# Deepen fetch until we find the requested tag
MAX_DEPTH=500
STEP=50
DEPTH=0

HEAD_REF="$(git rev-parse HEAD)"

while true; do
    if git merge-base --is-ancestor "$SEARCH_TAG" HEAD >/dev/null 2>&1; then
        echo "Found history from $SEARCH_TAG to HEAD"
        exit 0
    fi

    if [ "$DEPTH" -ge "$MAX_DEPTH" ]; then
        echo "Could not find history between $SEARCH_TAG and HEAD"
        exit 1
    fi

    DEPTH=$((DEPTH + STEP))

    echo "Fetching with depth=$DEPTH"
    git fetch --depth=$DEPTH origin "$HEAD_REF"
done
