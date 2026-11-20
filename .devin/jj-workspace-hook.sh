#!/usr/bin/env bash
# Devin Desktop post_setup_worktree hook. Replaces the git worktree Desktop just
# created with a jj workspace at the same path, based on the same snapshot commit.
# Does nothing in repos that aren't using jj.
set -euo pipefail

export PATH="/opt/homebrew/bin:/usr/local/bin:$HOME/.cargo/bin:$PATH"

root="${ROOT_WORKSPACE_PATH:?}"
wt="$PWD"

[[ -d "$root/.jj" && -f "$wt/.git" ]] || exit 0

exec >>"$root/.jj/devin-workspace-hook.log" 2>&1
echo "=== $(date '+%Y-%m-%d %H:%M:%S') $wt"

name="$(basename "$wt")"
rev="$(git rev-parse HEAD)"
branch="$(git branch --show-current)"

cd "$root"
git worktree remove --force "$wt"
jj git import
jj workspace forget "$name" 2>/dev/null || true
jj workspace add --name "$name" -r "$rev" "$wt"
if [[ -n "$branch" ]]; then
  jj bookmark delete "$branch"
fi

echo "Created jj workspace $name at $wt on top of $rev"
