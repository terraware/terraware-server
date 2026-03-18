#!/usr/bin/env bash
set -euo pipefail

.buildkite/scripts/install-deps.sh --java --tools --node

echo "--- :gradle: Download dependencies"

# We don't care about build caches from previous runs.
if [ -d "$GRADLE_USER_HOME/caches/build-cache-1" ]; then
    rm -rf "$GRADLE_USER_HOME/caches/build-cache-1"
fi

./gradlew --build-cache downloadDependencies yarn

echo "--- :gradle: Generate jOOQ classes"
./gradlew --build-cache generateJooqClasses

echo "--- :gradle: Check code style"
./gradlew --build-cache spotlessCheck

echo "--- :gradle: Compile main"
./gradlew --build-cache classes

echo "--- :openapi: Generate OpenAPI docs to test that server can start up"
./gradlew --build-cache generateOpenApiDocs

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
./gradlew --build-cache testClasses

echo "--- :junit: Run tests"
./gradlew --build-cache test

# We don't run the tests that depend on external services here. We want failures in that test suite
# to show up as soft failures in the build status, which requires that we run them in a separate
# build step.
