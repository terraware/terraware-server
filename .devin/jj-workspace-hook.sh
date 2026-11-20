#!/usr/bin/env bash
# Devin Desktop post_setup_worktree hook. Replaces the git worktree Desktop just
# created with a colocated jj workspace at the same path, based on the same
# snapshot commit. Files added or changed by hooks that ran earlier are carried
# over. If the conversion fails, the original git worktree is put back.
# Does nothing in repos that aren't using jj.
set -Eeuo pipefail

export PATH="/opt/homebrew/bin:/usr/local/bin:$HOME/.cargo/bin:$PATH"

root="${ROOT_WORKSPACE_PATH:?}"
wt="$PWD"

[[ -d "$root/.jj" && -f "$wt/.git" ]] || exit 0

exec >>"$root/.jj/devin-workspace-hook.log" 2>&1
echo "=== $(date '+%Y-%m-%d %H:%M:%S') $wt"

if ! command -v jj >/dev/null; then
  echo "jj not found on PATH; leaving git worktree as is"
  exit 0
fi

name="$(basename "$wt")"
rev="$(git rev-parse HEAD)"
branch="$(git branch --show-current)"
if [[ -z "$branch" ]]; then
  echo "Worktree has no branch; leaving git worktree as is"
  exit 0
fi
setup="$(git status --porcelain --ignored)"
admin="$(git rev-parse --absolute-git-dir)"
[[ "$admin" == */.git/worktrees/* ]] || exit 1
tmp="$(mktemp -d "$(dirname "$wt")/.$name.XXXXXX")"
stash="$tmp/$name"

copy_setup() {
  if [[ -n "$setup" ]]; then
    echo "Copying files from earlier setup"
    rsync -a --delete --exclude=/.git --exclude=/.jj "$stash/" "$wt/"
  fi
}

cd "$root"
mv "$wt" "$stash"

rollback() {
  trap - ERR
  set +e
  echo "Conversion failed; restoring git worktree"
  jj workspace forget "$name"
  rm -rf "$wt"
  if git worktree add -f "$wt" "$branch" && copy_setup; then
    rm -rf "$tmp"
  else
    echo "Could not restore git worktree; original files are in $stash"
  fi
  exit 1
}
trap rollback ERR

rm -rf "$admin"
jj git import
jj workspace forget "$name" 2>/dev/null || true
jj workspace add --colocate --name "$name" -r "$rev" "$wt"
copy_setup

trap - ERR
rm -rf "$tmp"
jj bookmark delete "$branch"

echo "Created jj workspace $name at $wt on top of $rev"
