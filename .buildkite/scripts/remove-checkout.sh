#!/usr/bin/env bash
#
# Delete the contents of the per-build checkout. The directory itself stays in place because the
# agent's exit hooks run there.

set -euo pipefail

daemon_dir="$(realpath -m "$GRADLE_USER_HOME/daemon")"

# Gradle daemons run with their working directories under $GRADLE_USER_HOME/daemon.
gradle_daemon_pids() {
    local cwd_link pid
    for cwd_link in /proc/[0-9]*/cwd; do
        if [[ "$(readlink "$cwd_link" 2>/dev/null)" == "$daemon_dir"/* ]]; then
            pid="${cwd_link#/proc/}"
            echo "${pid%/cwd}"
        fi
    done
}

# Daemons keep writing to $GRADLE_USER_HOME for a little while after "gradlew --stop" returns, so
# wait for them to exit before deleting anything.
if [[ -d "$daemon_dir" ]]; then
    ./gradlew --stop

    deadline=$((SECONDS + 60))
    while pids=$(gradle_daemon_pids) && [[ -n "$pids" ]]; do
        if ((SECONDS >= deadline)); then
            echo "Killing Gradle daemons that are still running after 60 seconds: $pids"
            # shellcheck disable=SC2086
            kill -KILL $pids 2>/dev/null || true
            sleep 1
            break
        fi
        sleep 0.5
    done
fi

find "$BUILDKITE_BUILD_CHECKOUT_PATH" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
