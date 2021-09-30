#!/bin/bash

set -euo pipefail

# Define tier based on branch ref
if [[ "$GITHUB_REF" =~ refs/tags/v[0-9].[0-9]+.[0-9]+ ]]; then
  export \
    TIER=PROD \
    IS_CD=true
elif [[ "$GITHUB_REF" == refs/heads/main ]]; then
  export \
    TIER=STAGING \
    IS_CD=true
else
  export IS_CD=false
  exit
fi

commit_sha="${GITHUB_SHA:0:12}"
docker_image='terraware/terraware-server'
docker_tags="${docker_image}:$commit_sha,${docker_image}:${TIER}"

# Define secret names based on the tier
echo "TIER=$TIER
IS_CD=$IS_CD
SSH_HOST_SECRET_NAME=${TIER}_SSH_HOST
SSH_KEY_SECRET_NAME=${TIER}_SSH_KEY
SSH_USER_SECRET_NAME=${TIER}_SSH_USER
AWS_ACCESS_KEY_ID_SECRET_NAME=${TIER}_AWS_ACCESS_KEY_ID
AWS_SECRET_ACCESS_KEY_SECRET_NAME=${TIER}_AWS_SECRET_ACCESS_KEY
AWS_REGION_SECRET_NAME=${TIER}_AWS_REGION
COMMIT_SHA=$commit_sha
DOCKER_TAGS=$docker_tags" >> $GITHUB_ENV
