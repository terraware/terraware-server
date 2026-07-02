#!/usr/bin/env bash
#
# Ask Graphite's CI optimizer whether this build should run. When Graphite says
# to skip (e.g. this commit isn't at the top of a stack), replace the remaining
# pipeline with an empty one so no further steps execute.
#
# Reimplements withgraphite/graphite-ci-buildkite-plugin so the token can be
# sourced from GRAPHITE_CI_OPTIMIZATION_TOKEN (loaded per-step by the aws-sm
# plugin) rather than passed as YAML plugin config.

set -euo pipefail

if [[ -z "${GRAPHITE_CI_OPTIMIZATION_TOKEN:-}" ]]; then
  echo "GRAPHITE_CI_OPTIMIZATION_TOKEN is not set; running CI." >&2
  exit 0
fi

# The optimizer decides based on stack position, which only exists for PR builds.
# Buildkite sets BUILDKITE_PULL_REQUEST to "false" for push builds; Graphite
# rejects those with a 400.
if [[ "${BUILDKITE_PULL_REQUEST:-false}" == "false" ]]; then
  exit 0
fi

body_file=$(mktemp)
trap 'rm -f "$body_file"' EXIT

request_json=$(jq -n \
  --arg token "$GRAPHITE_CI_OPTIMIZATION_TOKEN" \
  --arg repository "${BUILDKITE_REPO:-}" \
  --arg pr "${BUILDKITE_PULL_REQUEST:-}" \
  --arg commit "${BUILDKITE_COMMIT:-}" \
  --arg ref "${BUILDKITE_BRANCH:-}" \
  --arg pipeline_slug "${BUILDKITE_PIPELINE_SLUG:-}" \
  --arg pipeline_name "${BUILDKITE_PIPELINE_NAME:-}" \
  --arg pipeline_id "${BUILDKITE_PIPELINE_ID:-}" \
  '{
    token: $token,
    caller: { name: "terraware-server-buildkite", version: "1" },
    context: {
      kind: "BUILDKITE",
      repository: $repository,
      pr: $pr,
      commit: $commit,
      ref: $ref,
      pipeline: { slug: $pipeline_slug, name: $pipeline_name, id: $pipeline_id }
    }
  }')

response_code=$(curl -sS -o "$body_file" -w '%{response_code}' \
  --max-time 30 \
  -X POST 'https://api.graphite.dev/api/v1/ci/optimizer' \
  -H 'Accept: application/json' \
  -H 'Content-Type: application/json' \
  --data "$request_json")

if [[ "$response_code" == "401" ]]; then
  echo "Graphite rejected the CI optimizer token (401). Update GRAPHITE_CI_OPTIMIZATION_TOKEN in AWS Secrets Manager." >&2
  exit 1
fi

if [[ "$response_code" != "200" ]]; then
  echo "Graphite CI optimizer returned HTTP $response_code; running CI." >&2
  exit 0
fi

should_skip=$(jq -r '.skip' "$body_file")
reason=$(jq -r '.reason // ""' "$body_file")

if [[ "$should_skip" != "true" ]]; then
  exit 0
fi

echo "Graphite CI optimizer: skipping build. ${reason:-(no reason given)}"
buildkite-agent annotate --style info --context graphite \
  ":graphite: ${reason:-Build skipped by Graphite CI optimizer.} You can always manually trigger CI."
echo 'steps: []' | buildkite-agent pipeline upload --replace
