#!/bin/bash

set -euo pipefail

case "$TIER" in
    PROD)
        ECS_CLUSTER=prod-terraware-cluster
        ECS_SERVICE=prod-terraware-backend
        ASSUME_ROLE="${PROD_AWS_ROLE}"
        ;;
    STAGING)
        ECS_CLUSTER=staging-terraware-cluster
        ECS_SERVICE=staging-terraware-backend
        ASSUME_ROLE="${STAGING_AWS_ROLE}"
        ;;
    *)
        echo "--- :amazon-ecs: No ECS deploy needed for ${TIER} builds."
        exit 0
        ;;
esac

.buildkite/scripts/install-deps.sh --tools

echo "--- :aws: Assuming role in target tier"

credentials=$(aws sts assume-role --role-arn "$ASSUME_ROLE" --role-session-name buildkite-deploy)
AWS_ACCESS_KEY_ID=$(echo "$credentials" | jq -r '.Credentials.AccessKeyId');\
AWS_SECRET_ACCESS_KEY=$(echo "$credentials" | jq -r '.Credentials.SecretAccessKey');\
AWS_SESSION_TOKEN=$(echo "$credentials" | jq -r '.Credentials.SessionToken');
export AWS_ACCESS_KEY_ID
export AWS_SECRET_ACCESS_KEY
export AWS_SESSION_TOKEN

echo "--- :amazon-ecs: Deploying to ECS cluster=${ECS_CLUSTER} service=${ECS_SERVICE}"

aws ecs update-service \
    --cluster "$ECS_CLUSTER" \
    --service "$ECS_SERVICE" \
    --force-new-deployment

echo "--- :amazon-ecs: Waiting for ECS deployment to stabilize"
aws ecs wait services-stable \
    --cluster "$ECS_CLUSTER" \
    --service "$ECS_SERVICE"

echo "Deployment stable."
