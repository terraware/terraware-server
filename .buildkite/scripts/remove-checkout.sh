#!/usr/bin/env bash
#
# Delete the contents of the per-build checkout. The directory itself stays in place because the
# agent's exit hooks run there.
#
# Cleanup is best-effort: a lingering process can recreate files while they're being deleted, so
# anything that can't be removed is left in place rather than failing the build.

set -euo pipefail

if [[ -d "$GRADLE_USER_HOME/daemon" ]]; then
    ./gradlew --stop || true
fi

if ! find "$BUILDKITE_BUILD_CHECKOUT_PATH" -mindepth 1 -maxdepth 1 -exec rm -rf {} +; then
    echo "Some files in the checkout couldn't be removed; leaving them in place."
fi
