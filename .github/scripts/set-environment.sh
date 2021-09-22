#!/bin/bash

set -e

if [[ "$GITHUB_REF" =~ refs/tags/v[0-9].[0-9]+.[0-9]+ ]]: then
  echo "TIER=prod" >> $GITHUB_ENV
else
  echo "TIER=staging" >> $GITHUB_ENV
fi

echo "COMMIT_SHA=${GITHUB_REF:0:12}" >> $GITHUB_ENV
