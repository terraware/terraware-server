#!/usr/bin/env bash
#
# Keep fetching older and older commits until we find a particular tag.
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

while true; do
    if git rev-parse "$SEARCH_TAG" >/dev/null 2>&1; then
        echo "Found tag $SEARCH_TAG"
        exit 0
    fi

    if [ "$DEPTH" -ge "$MAX_DEPTH" ]; then
        echo "Could not find tag $SEARCH_TAG"
        exit 1
    fi

    DEPTH=$((DEPTH + STEP))

    echo "Fetching with depth=$DEPTH"
    git fetch --tags --depth=$DEPTH
done
