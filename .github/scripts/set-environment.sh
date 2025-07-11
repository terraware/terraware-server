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
    TIER=CI
    IS_CD=false
fi

# If this commit's title has a suffix like (#123), it's probably a merged PR.
# Extract the PR number from its title.
merged_pr_number=$(git log -1 --pretty=%s | sed -En 's/.*\(#([0-9]+)\)$/\1/p')

(
    echo "IS_CD=$IS_CD"
    echo "COMMIT_SHA=$commit_sha"
    echo "AWS_REGION_SECRET_NAME=${TIER}_AWS_REGION"
    echo "AWS_ROLE_SECRET_NAME=${TIER}_AWS_ROLE"
    echo "ECS_CLUSTER_VAR_NAME=${TIER}_ECS_CLUSTER"
    echo "ECS_SERVICE_VAR_NAME=${TIER}_ECS_SERVICE"
    echo "MERGED_PR_NUMBER=$merged_pr_number"

    if [[ "$IS_CD" == true ]]; then
        echo "DOCKER_TAGS=${docker_image}:$commit_sha,${docker_image}:${TIER}"
        echo "TIER=$TIER"

        # Define secret names based on the tier
        echo "SSH_KEY_SECRET_NAME=${TIER}_SSH_KEY"
        echo "SSH_USER_SECRET_NAME=${TIER}_SSH_USER"
    fi
) >> "$GITHUB_ENV"
