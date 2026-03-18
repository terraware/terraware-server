#!/usr/bin/env bash
set -euo pipefail

.buildkite/scripts/install-deps.sh --java

echo "--- :junit: Run tests that may fail if external services are down"

# Some secrets need to be exposed with different names than the values they're stored under
# in the Secrets Manager secret.

export TERRAWARE_ATLASSIAN_ACCOUNT="$TEST_ATLASSIAN_ACCOUNT"
export TERRAWARE_ATLASSIAN_APIHOST="$TEST_ATLASSIAN_HOST"
export TERRAWARE_ATLASSIAN_APITOKEN="$TEST_ATLASSIAN_APITOKEN"
export TERRAWARE_ATLASSIAN_SERVICEDESKKEY="$TEST_ATLASSIAN_SERVICE_DESK_KEY"
export TERRAWARE_MAPBOX_APITOKEN="$TEST_MAPBOX_APITOKEN"

./gradlew --build-cache -x generateJooqClasses test --tests='*ExternalTest'
