#!/usr/bin/env bash
# Self-tests for scripts/next-version.sh and scripts/release-notes.sh, in a scratch git repository
# (deleted afterwards). Run from anywhere: scripts/test-next-version.sh
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NEXT="$HERE/next-version.sh"
NOTES="$HERE/release-notes.sh"
repo=$(mktemp -d)
trap 'rm -rf "$repo"' EXIT
cd "$repo"
git init -q -b main
git config user.name test
git config user.email test@example.com
git config commit.gpgsign false
git config tag.gpgsign false

fails=0
commit() { git commit -q --allow-empty -m "$1" ${2:+-m "$2"}; }
check() { # <name> <expected> <command...>
  local name=$1 want=$2 got; shift 2
  got=$("$@" 2>&1) || got="(exit $?) $got"
  if [ "$got" = "$want" ]; then echo "ok    $name"; else echo "FAIL  $name: want '$want', got '$got'"; fails=$((fails + 1)); fi
}

commit "Initial commit"
check "no tag: the first version" 0.3.0 "$NEXT"
check "no tag: --last is empty" "" "$NEXT" --last

git tag v0.2.0
git tag v0.2.0-ljm.5
commit "Some plain message"
check "only upstream's v0.2.0: never under 0.3.0" 0.3.0 "$NEXT"
check "--last skips pre-release tags" v0.2.0 "$NEXT" --last

git tag v0.2.27
commit "fix: something small"
check "v0.2.27 then a fix: still 0.3.0 (the floor)" 0.3.0 "$NEXT"

git tag v0.3.0
check "no commits since the tag: a patch" 0.3.1 "$NEXT"
commit "fix(daemon): a crash on start"
commit "docs: a typo"
commit "A message without a type"
check "fix, docs, plain: patch" 0.3.1 "$NEXT"
commit "feat(android): a new card"
check "feat: minor" 0.4.0 "$NEXT"
commit "FEAT: shouted"
check "feat in capitals: still minor" 0.4.0 "$NEXT"

git tag v0.4.0
commit "refactor!: drop the old route"
check "type!: major" 1.0.0 "$NEXT"
git tag v1.0.0
commit "feat(scope)!: breaking with a scope"
check "type(scope)!: major" 2.0.0 "$NEXT"
git tag v2.0.0
commit "fix: the protocol" "BREAKING CHANGE: the phone must update first"
check "BREAKING CHANGE footer: major" 3.0.0 "$NEXT"
git tag v3.0.0
commit "chore: mention of BREAKING CHANGE: in the subject only"
check "BREAKING CHANGE only in a subject: patch" 3.0.1 "$NEXT"

# Commits a merge brings in count; the merge's own message doesn't.
git tag v3.0.1
git checkout -q -b side
commit "feat: from a branch"
git checkout -q main
commit "fix: on main"
git merge -q --no-ff -m "Merge branch 'side'" side
check "a merged branch's feat: minor" 3.1.0 "$NEXT"
git tag v3.1.0
git checkout -q -b side2
commit "fix: from another branch"
git checkout -q main
git merge -q --no-ff -m "feat!: a merge message is ignored" side2
check "a merge's own message is ignored: patch" 3.1.1 "$NEXT"
check "--last after a merge" v3.1.0 "$NEXT" --last

# The highest version wins, not the newest tag, and a tag the commit doesn't contain doesn't count.
git tag v3.2.0
git tag v3.1.9
check "--last is the highest version" v3.2.0 "$NEXT" --last
git checkout -q -b elsewhere
commit "feat: only on elsewhere"
git tag v9.0.0
git checkout -q main
check "a tag on another branch is ignored" v3.2.0 "$NEXT" --last
check "an explicit commit" 9.0.1 "$NEXT" elsewhere
check "an unknown commit fails" "(exit 2) next-version.sh: no commit nope" "$NEXT" nope

# Release notes: grouped, prefixes off.
git tag v3.3.0
commit "feat(android): Home cards show what is running"
commit "fix: a crash on start"
commit "docs(releasing): the new scheme"
commit "Plain message: kept whole"
commit "perf!: faster, breaking"
notes=$("$NOTES" v3.4.0 v3.3.0 HEAD)
want='## Features

- Home cards show what is running

## Fixes

- A crash on start

## Other

- The new scheme
- Plain message: kept whole
- Faster, breaking'
count() { grep -c -F -- "$1" || true; }
check "notes: grouped and stripped" "$want" sed -n '/^## Install/q;p' <<<"$notes"
check "notes: the client's name" 1 count 'hypernova-client-v3.4.0-android.apk' <<<"$notes"
check "notes: the old names, once each, for older servers" 2 count 'only there for older servers' <<<"$(sed -n 's/`hypernova-v3.4.0.apk`.*`hypernova-v3.4.0.json`.*only there for older servers.*/only there for older servers\nonly there for older servers/p' <<<"$notes")"
check "notes: a Client heading" 1 count '### Client' <<<"$notes"
check "notes: a Server heading" 1 count '### Server' <<<"$notes"
for f in linux-amd64.deb linux-arm64.deb macos-arm64.tar.gz macos-x64.tar.gz macos-arm64.pkg macos-x64.pkg; do
  check "notes: the server's $f" 1 count "\`hypernova-server-v3.4.0-$f\`" <<<"$notes"
done
check "notes: the checksums" 1 count 'SHA256SUMS' <<<"$notes"
check "notes: the server's name" 3 count 'hypernova-server-v3.4.0-linux.tar.gz' <<<"$notes"
check "notes: the short install line" 2 count 'curl -fsSL https://ljmerza.github.io/Hypernova/get.sh |' <<<"$notes"
check "notes: no server" 0 count 'hypernova-server' <<<"$("$NOTES" v3.4.0 v3.3.0 HEAD --no-server)"
check "notes: no server, the client still" 1 count 'hypernova-client-v3.4.0-android.apk' <<<"$("$NOTES" v3.4.0 v3.3.0 HEAD --no-server)"
notes=$("$NOTES" v3.4.1 HEAD HEAD)
check "notes: nothing new" "- No app or server changes; rebuilt from $(git rev-parse --short=7 HEAD)." sed -n 3p <<<"$notes"

echo
if [ "$fails" -eq 0 ]; then echo "all passed"; else echo "$fails failed"; exit 1; fi
