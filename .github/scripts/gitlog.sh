#!/bin/bash

LAST_TAG_DESCRIBE=`for tag in $(git tag | grep -E 'v[0-9]+\.[0-9]'); do
    git describe --tags --long --match="$tag"
done | sort -k2 -t"-" -n | head -n1`

LAST_TAG=${LAST_TAG_DESCRIBE%%-*}  # retain the part before the first dash
HEAD=${LAST_TAG_DESCRIBE##*g}

git log $LAST_TAG..HEAD --oneline > docs/unreleased.log
