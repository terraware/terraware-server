#!/usr/bin/env bash
set -euo pipefail

.buildkite/scripts/install-deps.sh --java --tools --node

echo "--- :gradle: Download dependencies"

# We don't care about build caches from previous runs.
if [ -d "$GRADLE_USER_HOME/caches/build-cache-1" ]; then
    rm -rf "$GRADLE_USER_HOME/caches/build-cache-1"
fi

./gradlew downloadDependencies yarn

echo "--- :gradle: Generate jOOQ classes"
./gradlew generateJooqClasses

echo "--- :gradle: Check code style"
./gradlew spotlessCheck

echo "--- :gradle: Compile main"
./gradlew classes
