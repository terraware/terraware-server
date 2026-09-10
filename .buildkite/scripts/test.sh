#!/usr/bin/env bash
set -euo pipefail

.buildkite/scripts/install-deps.sh --java --tools --node

echo "--- :junit: Run tests"

# Suppress some debug log messages that add up to megabytes of test output and slow down annotating
# the test results.
export LOGGING_LEVEL_COM_TERRAFORMATION_BACKEND_SEARCH=INFO

./gradlew test

# We don't run the tests that depend on external services here. We want failures in that test suite
# to show up as soft failures in the build status, which requires that we run them in a separate
# build step.
