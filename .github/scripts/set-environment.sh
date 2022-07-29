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
elif [[ "$GITHUB_REF" == refs/heads/qa ]]; then
    TIER=QA
    IS_CD=true
else
    IS_CD=false
fi

(
    echo "IS_CD=$IS_CD"
    echo "COMMIT_SHA=$commit_sha"

    if [[ "$IS_CD" == true ]]; then
        echo "TIER=$TIER"
        echo "DOCKER_TAGS=${docker_image}:$commit_sha,${docker_image}:${TIER}"

        # Define secret names based on the tier
        echo "AWS_REGION_SECRET_NAME=${TIER}_AWS_REGION"
        echo "AWS_ROLE_SECRET_NAME=${TIER}_AWS_ROLE"
        echo "SSH_HOST_SECRET_NAME=${TIER}_SSH_HOST"
        echo "SSH_KEY_SECRET_NAME=${TIER}_SSH_KEY"
        echo "SSH_USER_SECRET_NAME=${TIER}_SSH_USER"
    fi

    # Build Docker images for the species branch (TODO: Remove this before merging to main)
    if [[ "$GITHUB_REF" == refs/heads/species ]]; then
        echo "DOCKER_TAGS=${docker_image}:species"
    fi
) >> "$GITHUB_ENV"

