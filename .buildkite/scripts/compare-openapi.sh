#!/usr/bin/env bash
set -euo pipefail

.buildkite/scripts/install-deps.sh --java --tools --node

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
