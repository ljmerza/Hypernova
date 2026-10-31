#!/usr/bin/env bash
# The next release's version, from the commits since the last one (docs/RELEASING.md, "Versions").
#
#   scripts/next-version.sh [<commit>, default HEAD]          prints the next version: 0.3.1
#   scripts/next-version.sh --last [<commit>, default HEAD]   prints the last release's tag: v0.3.0
#                                                             (nothing when there is none)
#
# The last release is the highest vX.Y.Z tag that <commit> contains; tags with anything after the
# numbers (v0.2.0-ljm.5, v1.0.0-rc.1) don't count. The commits after it, merges left out (the
# commits a merge brought in are counted, each on its own), decide the step:
#   a `BREAKING CHANGE:` footer, or a `!` before the colon (`feat!: …`)  -> major  (1.0.0 -> 2.0.0)
#   a `feat:` (or `feat(scope):`)                                       -> minor  (0.3.0 -> 0.4.0)
#   anything else: fix, perf, docs, chore, a message without a type     -> patch  (0.3.0 -> 0.3.1)
# Never below 0.3.0, the first release with these versions (the 0.2.x before it are the renamed
# v0.2.0-ljm.N builds, and v0.2.0 is upstream's): with no vX.Y.Z tag, or a step that would land
# under it, the answer is 0.3.0.
set -euo pipefail
FIRST=0.3.0

last=no
if [ "${1:-}" = "--last" ]; then last=yes; shift; fi
ref=${1:-HEAD}
git rev-parse -q --verify "$ref^{commit}" >/dev/null || { echo "next-version.sh: no commit $ref" >&2; exit 2; }

tag=$(git tag --merged "$ref" --list 'v*' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1 || true)
if [ "$last" = yes ]; then
  [ -n "$tag" ] && echo "$tag"
  exit 0
fi

if [ -z "$tag" ]; then echo "$FIRST"; exit 0; fi

# Read whole first: `git log | grep -q` can end in SIGPIPE, which pipefail counts as no match.
subjects=$(git log --no-merges --format=%s "$tag..$ref")
bodies=$(git log --no-merges --format=%b "$tag..$ref")
bump=patch
if grep -qE '^[A-Za-z]+(\([^)]*\))?!: ' <<<"$subjects" || grep -qE '^BREAKING[ -]CHANGE: ' <<<"$bodies"; then
  bump=major
elif grep -qiE '^feat(\([^)]*\))?: ' <<<"$subjects"; then
  bump=minor
fi

IFS=. read -r major minor patch <<<"${tag#v}"
case $bump in
  major) next="$((major + 1)).0.0" ;;
  minor) next="$major.$((minor + 1)).0" ;;
  patch) next="$major.$minor.$((patch + 1))" ;;
esac
# Under the first version: the first version.
[ "$(printf '%s\n%s\n' "$next" "$FIRST" | sort -V | tail -1)" = "$FIRST" ] && next=$FIRST
echo "$next"
