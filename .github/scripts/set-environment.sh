#!/bin/bash

set -euo pipefail

# Define tier based on branch ref
if [[ "$GITHUB_REF" =~ refs/tags/v[0-9].[0-9]+.[0-9]+ ]]; then
  export \
    TIER=prod \
    IS_CD=true
elif [[ "$GITHUB_REF" == refs/heads/main ]]; then
  export \
    TIER=staging \
    IS_CD=true
else
  export IS_CD=false
  exit
fi

docker_image='terraware/terraware-server'
docker_tags="${docker_image}:${GITHUB_REF:0:12}"

if [[ -n "$TIER" ]]; then
  docker_tags="${docker_tags}\n${docker_image}:${TIER}"
fi

# Define secret names based on the tier
cat >> $GITHUB_ENV <<-EOF
  TIER=$TIER
  IS_CD=$IS_CD
  SSH_HOST_SECRET_NAME=${TIER}_SSH_HOST
  SSH_KEY_SECRET_NAME=${TIER}_SSH_KEY
  SSH_USER_SECRET_NAME=${TIER}_SSH_USER
  AWS_ACCESS_KEY_ID_SECRET_NAME=${TIER}_AWS_ACCESS_KEY_ID
  AWS_SECRET_ACCESS_KEY_SECRET_NAME=${TIER}_AWS_SECRET_ACCESS_KEY
  AWS_REGION_SECRET_NAME=${TIER}_AWS_REGION
  DOCKER_TAGS=$docker_tags
EOF
