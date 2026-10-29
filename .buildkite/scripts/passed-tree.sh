#!/usr/bin/env bash
#
# Check for a passing tree record or write one for the cache plugin to save.

set -euo pipefail

record() {
  tree=$(git rev-parse 'HEAD^{tree}')
  jq -n \
    --arg tree "$tree" \
    --arg build_url "${BUILDKITE_BUILD_URL:?}" \
    --arg build_number "${BUILDKITE_BUILD_NUMBER:?}" \
    --arg commit "${BUILDKITE_COMMIT:?}" \
    --arg recorded_at "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" \
    '{
      tree: $tree,
      build_url: $build_url,
      build_number: $build_number,
      commit: $commit,
      recorded_at: $recorded_at
    }' > .passed-tree.json
}

check() {
  if [[ "${BUILDKITE_SOURCE:-}" != "webhook" ]]; then
    echo "Not a webhook build; running CI."
    return 0
  fi

  if [[ -n "${BUILDKITE_REBUILT_FROM_BUILD_ID:-}" ]]; then
    echo "This is a rebuilt build; running CI."
    return 0
  fi

  if [[ ! -f .passed-tree.json ]]; then
    echo "No passing build recorded for this branch"
    return 0
  fi

  tree=$(git rev-parse 'HEAD^{tree}')
  if ! recorded_tree=$(jq -er '.tree | select(type == "string")' .passed-tree.json 2>/dev/null); then
    echo "Could not read the passing build record; running CI."
    return 0
  fi

  if [[ "$recorded_tree" != "$tree" ]]; then
    echo "The recorded tree does not match this code; running CI."
    return 0
  fi

  short_tree="${tree:0:12}"
  if ! annotation=$(jq -er --arg tree "$short_tree" '
    if (.build_url | type) == "string" and
      ((.build_number | type) == "string" or (.build_number | type) == "number") and
      (.commit | type) == "string" then
      ":white_check_mark: Skipped CI: this code (tree `\($tree)`) already passed in [build #\(.build_number)](\(.build_url)) (commit `\(.commit[0:12])`). Start a new build from Buildkite to run CI anyway."
    else
      empty
    end
  ' .passed-tree.json 2>/dev/null); then
    echo "The passing build record is incomplete; running CI."
    return 0
  fi

  echo "$annotation"
  buildkite-agent annotate --style success --context passed-tree "$annotation"
  PER_BUILD_CHECKOUT_PATH="$BUILDKITE_BUILD_CHECKOUT_PATH" \
    buildkite-agent pipeline upload --replace .buildkite/pipeline-skip.yml
}

case "${1:-}" in
  record)
    record
    ;;
  check)
    check
    ;;
  *)
    echo "Usage: $0 {record|check}" >&2
    exit 2
    ;;
esac
