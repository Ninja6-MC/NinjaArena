#!/usr/bin/env bash
#
# Cuts a release tag and pushes it, or dispatches Publish Release for a tag that already
# exists.
#
# Usage: scripts/release.sh <version> --candidate <candidate-run-id> [--dry-run]
#        scripts/release.sh publish <version> <candidate-run-id>
#                           [--destinations github|shared-state] [--dry-run]
#
#   scripts/release.sh 0.5.0-rc.1 --candidate 123456789
#   scripts/release.sh 0.5.0 --candidate 123456789 --dry-run
#   scripts/release.sh publish 0.5.0-rc.1 123456789
#   scripts/release.sh publish 0.5.0 123456789 --destinations shared-state --dry-run
#
# Pass the version without the leading "v"; the tag gets it. Both modes check that the
# candidate run is a successful first-attempt Release Candidate run on main for the commit
# being tagged or already tagged, so a tag never points away from its candidate. `--destinations` defaults to
# github. `shared-state` alone recovers the GitHub latest marker for a tag that is already
# published, without uploading anything. RELEASE_PROCESS.md has the full procedure and the
# retry table.
#
# Why this exists rather than `git tag && git push`: a tag is the only irreversible step
# taken outside a gated workflow. The checks below are the accidents that are easy to have
# and expensive to undo - tagging from a linked worktree parked on an old commit, tagging a
# stale main, tagging with uncommitted work in the tree, or tagging a stable version whose
# changelog section does not exist. Publish Release repeats the checks that matter after
# the tag is public; this finds out before.

set -euo pipefail

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RESET=$'\033[0m'

fail() { echo "${RED}FAIL${RESET} $*" >&2; exit 1; }
ok()   { echo "${GREEN}ok${RESET}   $*"; }
warn() { echo "${YELLOW}warn${RESET} $*"; }

REPOSITORY=Ninja6-MC/NinjaArena
TAG_PATTERN='^v0\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(alpha|beta|rc)\.[1-9][0-9]*)?$'

usage() {
  echo "Usage: scripts/release.sh <version> --candidate <candidate-run-id> [--dry-run]" >&2
  echo "       scripts/release.sh publish <version> <candidate-run-id> [--destinations github|shared-state] [--dry-run]" >&2
  exit 2
}

# ---------------------------------------------------------------------------
# The candidate run must be a successful first-attempt Release Candidate dispatch on
# main whose commit is the one being tagged. Publish Release checks this again.
# ---------------------------------------------------------------------------
check_candidate() {
  local run_id="$1" commit="$2" actual
  actual="$(gh api "repos/$REPOSITORY/actions/runs/$run_id" \
    --jq '[.name, .path, .event, .head_branch, .status, .conclusion, .run_attempt, .head_sha] | map(tostring) | join(" ")')" \
    || fail "could not read run $run_id from $REPOSITORY."
  [ "$actual" = "Release Candidate .github/workflows/release-candidate.yml workflow_dispatch main completed success 1 $commit" ] \
    || fail "run $run_id is not a successful first-attempt Release Candidate run on main for ${commit:0:7}.
     It reports: $actual"
  ok "candidate run $run_id built ${commit:0:7}"
}

# ---------------------------------------------------------------------------
# Dispatch Publish Release for a tag already on origin.
# ---------------------------------------------------------------------------
publish() {
  [ $# -ge 2 ] || usage
  local version="$1" run_id="$2" destinations=github dry_run=0
  shift 2
  while [ $# -gt 0 ]; do
    case "$1" in
      --destinations) [ $# -ge 2 ] || usage; destinations="$2"; shift 2 ;;
      --dry-run) dry_run=1; shift ;;
      *) fail "unknown option '$1'" ;;
    esac
  done
  local tag="v$version"
  [[ "$tag" =~ $TAG_PATTERN ]] || fail "'$version' is not a valid version."
  [[ "$run_id" =~ ^[1-9][0-9]*$ ]] || fail "'$run_id' is not a Release Candidate run ID."
  case "$destinations" in
    github|shared-state) ;;
    *) fail "destinations must be 'github' or 'shared-state' alone, not '$destinations'." ;;
  esac
  ok "publishing $tag to $destinations from candidate run $run_id"

  git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null 2>&1 \
    || fail "tag '$tag' is not on origin. Cut it first: scripts/release.sh $version"
  ok "tag '$tag' exists on origin"
  local commit
  commit="$(git ls-remote origin "refs/tags/$tag" "refs/tags/$tag^{}" | tail -n 1 | cut -f1)"
  check_candidate "$run_id" "$commit"

  local nonce
  nonce="$(date -u +%Y%m%dT%H%M%SZ)-$RANDOM"
  local args=(workflow run release.yml -R "$REPOSITORY" --ref "$tag" -f tag="$tag"
    -f candidate_run_id="$run_id" -f destinations="$destinations" -f nonce="$nonce")
  if [ "$dry_run" -eq 1 ]; then
    echo "${YELLOW}Dry run: nothing was dispatched.${RESET} The command would be:"
    echo "  gh ${args[*]}"
    exit 0
  fi
  gh "${args[@]}"
  ok "dispatched Publish Release [$nonce]"
  echo
  echo "Find the run by its nonce, review its plan summary, then approve the release environment in the browser:"
  echo "  gh run list -R $REPOSITORY --workflow release.yml --json databaseId,displayTitle --jq '.[] | select(.displayTitle | contains(\"$nonce\"))'"
  exit 0
}

if [ "${1:-}" = "publish" ]; then
  shift
  publish "$@"
fi

VERSION="${1:-}"
[ -n "$VERSION" ] || usage
shift
DRY_RUN=0
CANDIDATE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --candidate) [ $# -ge 2 ] || usage; CANDIDATE="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    *) usage ;;
  esac
done

TAG="v$VERSION"

# ---------------------------------------------------------------------------
# 1. The tag grammar Release Candidate and Publish Release enforce.
# ---------------------------------------------------------------------------
if [[ ! "$TAG" =~ $TAG_PATTERN ]]; then
  fail "'$VERSION' is not a valid version.
     Expected 0.MINOR.PATCH with an optional -alpha.N, -beta.N or -rc.N suffix (N from 1).
     For example: 0.5.0, 0.5.0-rc.1"
fi
ok "version '$VERSION' is well formed"
[[ "$CANDIDATE" =~ ^[1-9][0-9]*$ ]] || fail "--candidate needs the successful Release Candidate run ID for this commit."

# ---------------------------------------------------------------------------
# 2. Refuse to run from a linked worktree. Those sit on their own branches at
#    whatever commit they were created from.
# ---------------------------------------------------------------------------
GIT_DIR="$(git rev-parse --git-dir)"
COMMON_DIR="$(git rev-parse --git-common-dir)"
if [ "$GIT_DIR" != "$COMMON_DIR" ]; then
  fail "this is a linked worktree ($(git rev-parse --show-toplevel)).
     Release from the main checkout instead."
fi
ok "running in the main checkout"

# ---------------------------------------------------------------------------
# 3. On main, and nowhere else.
# ---------------------------------------------------------------------------
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[ "$BRANCH" = "main" ] || fail "on branch '$BRANCH', not 'main'. Run: git checkout main"
ok "on main"

# ---------------------------------------------------------------------------
# 4. Nothing uncommitted. A tag names a commit, so anything still in the working
#    tree is not in the release.
# ---------------------------------------------------------------------------
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo
  git status --short | sed 's/^/     /'
  echo
  fail "working tree has uncommitted changes."
fi
ok "working tree is clean"

UNTRACKED="$(git ls-files --others --exclude-standard | head -5)"
if [ -n "$UNTRACKED" ]; then
  warn "untracked files present (they will not be in the release):"
  echo "$UNTRACKED" | sed 's/^/       /'
fi

# ---------------------------------------------------------------------------
# 5. Exactly in step with origin/main. Behind means the release misses merged
#    work; ahead means it contains commits nobody has reviewed.
# ---------------------------------------------------------------------------
git fetch origin main --tags --quiet
LOCAL="$(git rev-parse HEAD)"
REMOTE="$(git rev-parse origin/main)"
if [ "$LOCAL" != "$REMOTE" ]; then
  BEHIND="$(git rev-list --count HEAD..origin/main)"
  AHEAD="$(git rev-list --count origin/main..HEAD)"
  fail "main is not in step with origin/main ($AHEAD ahead, $BEHIND behind).
     Run: git pull origin main"
fi
ok "main matches origin/main at ${LOCAL:0:7}"

# ---------------------------------------------------------------------------
# 6. The tag must not already exist, locally or remotely.
# ---------------------------------------------------------------------------
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  fail "tag '$TAG' already exists locally. Delete it first: git tag -d $TAG"
fi
if git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then
  fail "tag '$TAG' already exists on origin. That version has been released."
fi
ok "tag '$TAG' is unused"

# The candidate must have been built from exactly the commit about to be tagged.
check_candidate "$CANDIDATE" "$LOCAL"

# ---------------------------------------------------------------------------
# 7. The changelog gate. A stable release needs its own section; a pre-release
#    falls back to its base version and may go without.
# ---------------------------------------------------------------------------
BASE_VERSION="${VERSION%%-*}"
PRERELEASE=0
[ "$VERSION" != "$BASE_VERSION" ] && PRERELEASE=1

extract_notes() {
  awk -v header="## [$1]" '
    index($0, header) == 1 { found = 1; next }
    found && /^## / { exit }
    found { print }
  ' CHANGELOG.md
}

NOTES="$(extract_notes "$VERSION")"
[ -z "${NOTES//[[:space:]]/}" ] && NOTES="$(extract_notes "$BASE_VERSION")"

if [ -z "${NOTES//[[:space:]]/}" ]; then
  if [ "$PRERELEASE" -eq 0 ]; then
    fail "CHANGELOG.md has no '## [$BASE_VERSION]' section.
     A stable release must document what changed. Move the [Unreleased] entries
     under that heading, merge to main, and try again."
  fi
  warn "no changelog section for $VERSION; the release notes will say it is a pre-release"
else
  ok "changelog section found"
fi

# ---------------------------------------------------------------------------
# 8. Show what is about to happen, then require it to be typed out. Everything
#    above this line is reversible; everything below is not.
# ---------------------------------------------------------------------------
if [ "$PRERELEASE" -eq 1 ]; then
  CHANNEL="pre-release (GitHub pre-release; never marked latest)"
else
  CHANNEL="stable (GitHub release; marked latest only when it is the highest stable version)"
fi

echo
echo "  tag      $TAG"
echo "  commit   $(git log -1 --format='%h %s')"
echo "  channel  $CHANNEL"
echo
echo "  release notes:"
if [ -n "${NOTES//[[:space:]]/}" ]; then
  printf '%s\n' "$NOTES" | grep -v '^[[:space:]]*$' | head -8 | sed 's/^/    /'
  TOTAL="$(printf '%s\n' "$NOTES" | grep -cv '^[[:space:]]*$')"
  [ "$TOTAL" -gt 8 ] && echo "    ... and $((TOTAL - 8)) more lines"
else
  echo "    (none)"
fi
echo

if [ "$DRY_RUN" -eq 1 ]; then
  echo "${YELLOW}Dry run: every check passed. Nothing was tagged or pushed.${RESET}"
  exit 0
fi

echo "Pushing the tag publishes nothing; Publish Release is dispatched separately"
echo "with candidate run $CANDIDATE."
echo
read -r -p "Type the tag to confirm: " CONFIRM
[ "$CONFIRM" = "$TAG" ] || fail "confirmation did not match. Nothing was tagged."

# ---------------------------------------------------------------------------
# 9. Tag, then push. The tag is created locally first so that a failed push
#    leaves something to inspect and delete rather than a half-published state.
# ---------------------------------------------------------------------------
git tag -a "$TAG" -m "NinjaArena $TAG"
ok "created $TAG at ${LOCAL:0:7}"

git push origin "$TAG"
ok "pushed $TAG"

echo
echo "Publish it with the Release Candidate run ID:"
echo "  scripts/release.sh publish $VERSION $CANDIDATE"
echo "See RELEASE_PROCESS.md for verification and retry rules."
