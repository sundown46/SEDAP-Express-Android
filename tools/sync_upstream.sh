#!/usr/bin/env bash
# Imports a filtered upstream snapshot as one commit on the branch vendor/upstream.
# Merge that branch into main afterwards: git merge vendor/upstream
#
# Usage: tools/sync_upstream.sh <upstream-commit> [extra git-commit arguments]
set -euo pipefail

UPSTREAM_URL=https://github.com/UNIITY-Team/SEDAP-Express.git
BRANCH=vendor/upstream
SOURCE_DIR=SEDAPExpress/src/main/java

commit=${1:?usage: tools/sync_upstream.sh <upstream-commit> [git-commit args]}
shift
repo=$(git rev-parse --show-toplevel)
work=$(mktemp -d)
cleanup() {
  git -C "$repo" worktree remove --force "$work/vendor" 2>/dev/null || true
  rm -rf "$work"
}
trap cleanup EXIT

git clone --quiet --filter=blob:none --no-checkout "$UPSTREAM_URL" "$work/upstream"
git -C "$work/upstream" sparse-checkout set --no-cone "/$SOURCE_DIR/" /LICENSE.txt
git -C "$work/upstream" checkout --quiet "$commit"
full=$(git -C "$work/upstream" rev-parse HEAD)
summary=$(git -C "$work/upstream" log -1 --format=%s)

if git -C "$repo" show-ref --verify --quiet "refs/heads/$BRANCH"; then
  git -C "$repo" worktree add --quiet "$work/vendor" "$BRANCH"
else
  git -C "$repo" worktree add --quiet --detach "$work/vendor"
  git -C "$work/vendor" switch --quiet --orphan "$BRANCH"
fi

rm -rf "$work/vendor/upstream"
mkdir -p "$work/vendor/upstream/$SOURCE_DIR"
cp -R "$work/upstream/$SOURCE_DIR/." "$work/vendor/upstream/$SOURCE_DIR/"
cp "$work/upstream/LICENSE.txt" "$work/vendor/upstream/LICENSE.txt"
printf '%s\n' "$full" > "$work/vendor/upstream/UPSTREAM_COMMIT"
# Upstream bytes stay exactly as they are, including mixed line endings.
printf '* -text\n' > "$work/vendor/upstream/.gitattributes"

git -C "$work/vendor" add upstream
if git -C "$work/vendor" diff --cached --quiet; then
  echo "Already at $full"
  exit 0
fi
git -C "$work/vendor" commit --quiet -m "Import upstream ${full:0:7}" -m "$summary" "$@"
echo "$BRANCH now holds upstream ${full:0:7}. Next: git merge $BRANCH"
