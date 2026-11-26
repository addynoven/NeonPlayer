#!/usr/bin/env bash
# Bring PixelPlayerHQ/PixelPlayerOSS's latest changes into our app branch.
#
#   scripts/sync-upstream.sh
#
# Working on a throwaway `sync/upstream-*` branch means a merge that turns ugly can
# be abandoned with `git merge --abort` without ever dirtying `main`.
set -euo pipefail

UPSTREAM_REMOTE="${UPSTREAM_REMOTE:-oss}"
UPSTREAM_BRANCH="${UPSTREAM_BRANCH:-main}"
APP_BRANCH="${APP_BRANCH:-main}"

cd "$(dirname "$0")/.."

# Files we intentionally diverge on and that upstream also edits. Every merge that
# touches one of these needs a human look, so print them up front.
HOT_FILES=(
  "app/build.gradle.kts"
  "gradle.properties"
  "gradle/libs.versions.toml"
  "app/src/main/AndroidManifest.xml"
  "app/src/main/java/com/lostf1sh/pixelplayeross/presentation/navigation/AppNavigation.kt"
  "app/src/main/java/com/lostf1sh/pixelplayeross/data/database"
  "app/src/main/java/com/lostf1sh/pixelplayeross/presentation/screens/settings/AboutScreen.kt"
)

if [[ -n "$(git status --porcelain)" ]]; then
  printf "Working tree is dirty. Commit or stash before syncing.\n" >&2
  exit 1
fi

printf "Fetching %s/%s ...\n" "$UPSTREAM_REMOTE" "$UPSTREAM_BRANCH"
git fetch "$UPSTREAM_REMOTE" --tags --prune

behind=$(git rev-list --count "$APP_BRANCH..$UPSTREAM_REMOTE/$UPSTREAM_BRANCH")
if [[ "$behind" -eq 0 ]]; then
  printf "\n%s already contains %s/%s. Nothing to do.\n" \
    "$APP_BRANCH" "$UPSTREAM_REMOTE" "$UPSTREAM_BRANCH"
  exit 0
fi

sync_branch="sync/upstream-$(date +%Y%m%d-%H%M)"
printf "\n%d upstream commit(s) to merge.\n" "$behind"
printf "Upstream changes:\n"
git log --oneline --no-decorate "HEAD..$UPSTREAM_REMOTE/$UPSTREAM_BRANCH" | head -20
printf "\nHot files both sides touched:\n"
git diff --name-only "$APP_BRANCH..$UPSTREAM_REMOTE/$UPSTREAM_BRANCH" -- "${HOT_FILES[@]}" \
  | sed "s/^/  /" || true

git switch -c "$sync_branch" "$APP_BRANCH"

if git merge --no-edit "$UPSTREAM_REMOTE/$UPSTREAM_BRANCH"; then
  printf "\nMerged cleanly onto %s.\n" "$sync_branch"
  printf "Our delta vs upstream now:\n"
  git diff --shortstat "$UPSTREAM_REMOTE/$UPSTREAM_BRANCH...HEAD" | sed "s/^/  /"
  printf "\nNext:\n"
  printf "  ./gradlew :app:assembleDebug :app:testDebugUnitTest\n"
  printf "  git switch %s && git merge --ff-only %s\n" "$APP_BRANCH" "$sync_branch"
  printf "  git push origin %s\n" "$APP_BRANCH"
  printf "\nThen play one YouTube track before releasing: the extractor version in\n"
  printf "gradle/libs.versions.toml is the one line that decides whether playback works.\n"
else
  printf "\nConflicts (branch %s is checked out, resolve then commit):\n" "$sync_branch"
  git diff --name-only --diff-filter=U | sed "s/^/  /"
  printf "\nResolve them, run %s\n" "./gradlew :app:assembleDebug :app:testDebugUnitTest,"
  printf "then: git switch %s && git merge --ff-only %s\n" "$APP_BRANCH" "$sync_branch"
  exit 1
fi