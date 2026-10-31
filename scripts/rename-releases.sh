#!/usr/bin/env bash
# Renames the fork's v0.2.0-ljm.N releases to semantic versions, v0.2.N (docs/RELEASING.md,
# "Versions"; docs/DECISIONS.md, "Semantic versions"). For each one: a new tag v0.2.N at the same
# commit, the release moved onto it and retitled v0.2.N, the tag names in its notes rewritten, its
# assets renamed (hypernova-v0.2.0-ljm.N.apk -> hypernova-v0.2.N.apk, the server package likewise;
# a plain hypernova.apk keeps its name), and then the old tag deleted. The newest stays the
# latest release until a higher version (v0.3.0) is published.
#
#   scripts/rename-releases.sh            dry run: prints the plan, changes nothing (GETs only)
#   scripts/rename-releases.sh --apply    does it, with `gh` signed in with write access
#   -R owner/repo                         another repository (default ljmerza/Hypernova)
#
# Safe to run again: whatever is already done (the tag made, the release moved, an asset renamed,
# the old tag gone) is skipped, so a run that stopped halfway finishes on the next one.
set -euo pipefail
REPO=ljmerza/Hypernova
apply=no
while [ $# -gt 0 ]; do
  case $1 in
    --apply) apply=yes ;;
    -R) REPO=$2; shift ;;
    -h|--help) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "rename-releases.sh: unknown argument $1" >&2; exit 2 ;;
  esac
  shift
done
command -v gh >/dev/null || { echo "rename-releases.sh: needs the GitHub CLI (gh)" >&2; exit 1; }

# The rewrite of names in notes and asset names: v0.2.0-ljm.N -> v0.2.N, wherever it appears.
rename() { sed 's/v0\.2\.0-ljm\.\([0-9][0-9]*\)/v0.2.\1/g'; }
# One step of the plan: printed, and done only with --apply.
act() { # <what> <command...>
  local what=$1; shift
  if [ "$apply" = yes ]; then echo "  - $what"; "$@" >/dev/null; else echo "  - would $what"; fi
}

[ "$apply" = yes ] && echo "Renaming the releases of $REPO" || echo "Dry run for $REPO (nothing changes; --apply to do it)"
echo

# Tags: name -> "type sha" (a lightweight tag points at its commit, an annotated one at a tag object).
declare -A ref
while IFS=$'\t' read -r name type sha; do
  ref[${name#refs/tags/}]="$type $sha"
done < <(gh api --paginate "repos/$REPO/git/matching-refs/tags/v0.2." --jq '.[] | [.ref, .object.type, .object.sha] | @tsv')
commit_of() { # <tag>: its commit
  local type sha
  read -r type sha <<<"${ref[$1]}"
  if [ "$type" = tag ]; then gh api "repos/$REPO/git/tags/$sha" --jq .object.sha; else echo "$sha"; fi
}

# The releases to rename, oldest number first (so the newest is moved last), whether still under
# the old tag or partly done under the new one: "N id tag name draft prerelease".
plan=$(gh api --paginate "repos/$REPO/releases?per_page=100" \
  --jq '.[] | [.id, .tag_name, .name, .draft, .prerelease] | @tsv' |
  while IFS=$'\t' read -r id tag name draft pre; do
    if [[ $tag =~ ^v0\.2\.0-ljm\.([0-9]+)$ ]] || [[ $tag =~ ^v0\.2\.([1-9][0-9]*)$ ]]; then
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' "${BASH_REMATCH[1]}" "$id" "$tag" "$name" "$draft" "$pre"
    fi
  done | sort -n)
[ -n "$plan" ] || { echo "No v0.2.0-ljm.N or v0.2.N releases on $REPO."; exit 0; }

# The release that must be the latest: the highest published version once renamed (the newest
# v0.2.N until v0.3.0 exists; after that this script leaves "latest" alone).
latest_now=$(gh api "repos/$REPO/releases/latest" --jq .tag_name 2>/dev/null || true)
target=$(gh api --paginate "repos/$REPO/releases?per_page=100" \
  --jq '.[] | select(.draft == false and .prerelease == false) | .tag_name' | rename |
  grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1 || true)

todo=0
# The loops read on fds 3 and 4, so nothing inside them (gh) can eat their input.
while IFS=$'\t' read -r -u 3 n id tag name draft pre; do
  old="v0.2.0-ljm.$n"
  new="v0.2.$n"
  steps=()
  echo "$old -> $new  (release $id, now on $tag$([ "$pre" = true ] && echo ', a pre-release'))"
  [ "$draft" = true ] && echo "  ! a draft: skipped" && continue

  # 1. The new tag, at the old tag's commit.
  if [ -n "${ref[$new]:-}" ]; then
    if [ -n "${ref[$old]:-}" ] && [ "$(commit_of "$old")" != "$(commit_of "$new")" ]; then
      echo "  ! $new already exists at another commit than $old: skipped, sort it out by hand"
      continue
    fi
  elif [ -n "${ref[$old]:-}" ]; then
    sha=$(commit_of "$old")
    steps+=(tag)
    act "create tag $new at ${sha::7}" gh api -X POST "repos/$REPO/git/refs" -f ref="refs/tags/$new" -f sha="$sha"
  else
    echo "  ! neither $old nor $new exists as a tag: skipped"
    continue
  fi

  # 2. The release on the new tag, its title, the names in its notes, and whether it is the latest.
  body=$(gh api "repos/$REPO/releases/$id" --jq .body)
  newbody=$(printf '%s' "$body" | rename)
  if [ "$new" = "$target" ]; then make_latest=true; else make_latest=false; fi
  if [ "$tag" != "$new" ] || [ "$name" != "$new" ] || [ "$body" != "$newbody" ]; then
    changes=$(grep -o 'v0\.2\.0-ljm\.[0-9]*' <<<"$body" | wc -l || true)
    steps+=(release)
    act "move the release to $new, title it $new, rewrite $changes tag name(s) in its notes (make_latest=$make_latest)" \
      gh api -X PATCH "repos/$REPO/releases/$id" -f tag_name="$new" -f name="$new" -f body="$newbody" -f make_latest="$make_latest"
  fi

  # 3. The assets.
  assets=$(gh api --paginate "repos/$REPO/releases/$id/assets?per_page=100" --jq '.[] | [.id, .name] | @tsv')
  while IFS=$'\t' read -r -u 4 aid aname; do
    [ -n "$aid" ] || continue
    anew=$(rename <<<"$aname")
    [ "$anew" = "$aname" ] && continue
    if grep -qxF "$anew" <<<"$(cut -f2 <<<"$assets")"; then
      echo "  ! asset $anew already exists next to $aname: left alone"
      continue
    fi
    steps+=(asset)
    act "rename asset $aname -> $anew" gh api -X PATCH "repos/$REPO/releases/assets/$aid" -f name="$anew"
  done 4<<<"$assets"

  # 4. The old tag, once the release is on the new one (a release whose tag is deleted turns into a draft).
  if [ -n "${ref[$old]:-}" ]; then
    if [ "$apply" = yes ] && [ "$(gh api "repos/$REPO/releases/$id" --jq .tag_name)" != "$new" ]; then
      echo "  ! the release is not on $new yet: $old kept"
    else
      steps+=(untag)
      act "delete tag $old" gh api -X DELETE "repos/$REPO/git/refs/tags/$old"
    fi
  fi
  [ ${#steps[@]} -gt 0 ] && todo=$((todo + 1)) || echo "  done already"
done 3<<<"$plan"

echo
echo "Latest release now: ${latest_now:-none}; it must be: ${target:-none}"
if [ -n "$target" ]; then
  if [ "$apply" = yes ]; then
    now=$(gh api "repos/$REPO/releases/latest" --jq .tag_name 2>/dev/null || true)
    if [ "$now" != "$target" ]; then
      tid=$(gh api "repos/$REPO/releases/tags/$target" --jq .id)
      act "mark $target as the latest release" gh api -X PATCH "repos/$REPO/releases/$tid" -f make_latest=true
    fi
    echo "Latest release: $(gh api "repos/$REPO/releases/latest" --jq .tag_name)"
  else
    echo "  - $target gets make_latest=true with its own edit above (and the run checks it at the end)"
  fi
fi
echo
if [ "$apply" = yes ]; then
  echo "Done. Clones keep the old tags until: git tag -l 'v0.2.0-ljm.*' | xargs -r git tag -d && git fetch --tags origin"
else
  echo "$todo release(s) to change. Run again with --apply to do it."
fi
