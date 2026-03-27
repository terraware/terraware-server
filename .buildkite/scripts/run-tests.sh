#!/usr/bin/env bash
set -euo pipefail

.buildkite/scripts/install-deps.sh --java --tools

echo "--- :openapi: Generate OpenAPI docs to test that server can start up"
./gradlew generateOpenApiDocs

echo "--- :openapi: Diff OpenAPI docs against staging"
if curl -f -s https://staging.terraware.io/v3/api-docs.yaml > staging.yaml; then
  for f in openapi.yaml staging.yaml; do
    yq -i '
      .info.version = null |
      .servers[0].url = null |
      .components.securitySchemes.openId.openIdConnectUrl = null' "$f"
  done

  # Indent the diff output so the "---" in the diff header isn't treated as a section header.
  diff -u staging.yaml openapi.yaml | sed 's/^/ /' || true
else
  echo "Unable to fetch OpenAPI schema from staging"
fi

echo "--- :gradle: Compile tests"
./gradlew testClasses

echo "--- :junit: Run tests"

# Suppress some debug log messages that add up to megabytes of test output and slow down annotating
# the test results.
export LOGGING_LEVEL_COM_TERRAFORMATION_BACKEND_SEARCH=INFO

./gradlew test

# We don't run the tests that depend on external services here. We want failures in that test suite
# to show up as soft failures in the build status, which requires that we run them in a separate
# build step.
