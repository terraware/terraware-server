#!/bin/bash

set -euo pipefail

commit_sha="${GITHUB_SHA:0:12}"
docker_image='terraware/terraware-server'

# Define tier based on branch ref
if [[ "$GITHUB_REF" =~ refs/tags/v[0-9]+\.[0-9.]+ ]]; then
    TIER=PROD
    IS_CD=true
elif [[ "$GITHUB_REF" == refs/heads/main ]]; then
    TIER=STAGING
    IS_CD=true
else
    TIER=STAGING
    IS_CD=true
    #TIER=CI
    #IS_CD=false
fi

(
    echo "IS_CD=$IS_CD"
    echo "COMMIT_SHA=$commit_sha"
    echo "AWS_REGION_SECRET_NAME=${TIER}_AWS_REGION"
    echo "AWS_ROLE_SECRET_NAME=${TIER}_AWS_ROLE"

    if [[ "$IS_CD" == true ]]; then
        echo "DOCKER_TAGS=${docker_image}:$commit_sha,${docker_image}:${TIER}"
        echo "TIER=$TIER"

        # Define secret names based on the tier
        echo "SSH_KEY_SECRET_NAME=${TIER}_SSH_KEY"
        echo "SSH_USER_SECRET_NAME=${TIER}_SSH_USER"
    fi
) >> "$GITHUB_ENV"
