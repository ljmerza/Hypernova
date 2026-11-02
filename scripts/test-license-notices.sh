#!/usr/bin/env bash
# Self-tests for scripts/license-notices.sh, in a scratch git repository (deleted afterwards), then a
# look inside the Linux tarball that the .deb and the Mac's packages are made from.
# Run from anywhere: scripts/test-license-notices.sh
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/license-notices.sh"
repo=$(mktemp -d)
trap 'rm -rf "$repo"' EXIT
cd "$repo"
git init -q -b main
git config user.name test
git config user.email test@example.com
git config commit.gpgsign false

fails=0
ok() { echo "ok    $1"; }
bad() { echo "FAIL  $1"; fails=$((fails + 1)); }
same() { # <name> <want> <got>
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: want '$2', got '$3'"; fi
}
body() { # <lines>: lines git can tell apart, so that a rename is seen as one
  local i=1
  while [ "$i" -le "$1" ]; do echo "line number $i of the file"; i=$((i + 1)); done
}

# ── The original ────────────────────────────────────────────────────────────
mkdir -p app/data scripts third_party/x android/app/src/main/assets/legal res/values docs skills/hyper-cosmos daemon/test/fixtures img
{ echo 'package a.b'; echo; body 20; } > app/data/Moved.kt
{ echo 'package a.b'; echo; body 20; } | sed 's/line number/another line/' > app/data/MovedAndChanged.kt
{ echo 'package a.b'; echo; body 40; } | sed 's/line number/old text/' > app/data/Rewritten.kt
{ echo 'package a.b'; echo; body 20; } | sed 's/line number/same text/' > app/data/Untouched.kt
{ echo '#!/usr/bin/env bash'; echo '# what it does'; echo 'echo hi'; } > scripts/plain.sh
{ echo '#!/usr/bin/env bash'; echo '# help one'; echo '# help two'; echo 'set -e'; echo "case \"\$1\" in -h) sed -n '2,3p' \"\$0\" ;; esac"; } > scripts/helpful.sh
chmod +x scripts/plain.sh scripts/helpful.sh
{ echo '<?xml version="1.0" encoding="utf-8"?>'; echo '<resources>'; echo '</resources>'; } > res/values/a.xml
{ echo '<resources>'; echo '</resources>'; } > res/values/b.xml
{ echo '<!doctype html>'; echo '<html></html>'; } > page.html
{ echo '---'; echo 'title: x'; echo '---'; echo '# Heading'; } > docs/front.md
{ echo '# Heading'; echo 'words'; } > docs/plain.md
{ echo 'FROM ubuntu'; } > Dockerfile
mkdir -p d2 && { echo '# syntax=docker/dockerfile:1'; echo 'FROM ubuntu'; } > d2/Dockerfile
{ echo 'name: x'; } > ci.yml
{ echo 'a { color: red }'; } > a.css
{ echo '{"a": 1}'; } > package.json
printf '\211PNG\r\n\032\n\000\000\000' > img/pic.png
echo 'third party' > third_party/x/lib.js
echo 'a fixture' > daemon/test/fixtures/data.ts
echo 'the skill' > skills/hyper-cosmos/SKILL.md
echo 'THE NOTICE' > NOTICE
echo 'THE LICENSE' > LICENSE
cp NOTICE LICENSE android/app/src/main/assets/legal/
echo 'gone for good' > app/data/Gone.kt
git add -A
git commit -qm original
export LICENSE_NOTICES_ROOT="$repo" LICENSE_NOTICES_BASE="$(git rev-parse HEAD)"

# ── Nothing changed: nothing to do ──────────────────────────────────────────
same "a tree like the original passes" "license notices: all there." "$("$SCRIPT" --check)"

# ── This fork's changes ─────────────────────────────────────────────────────
mkdir -p shared/data
git mv app/data/Moved.kt shared/data/Moved.kt                     # only moved
git mv app/data/MovedAndChanged.kt shared/data/Renamed.kt         # moved, renamed and changed
echo 'one more line' >> shared/data/Renamed.kt
git mv app/data/Rewritten.kt shared/data/Rewritten.kt             # moved, then mostly rewritten
{ echo 'package a.b'; echo; body 35; } | sed 's/line number/a new line/' > shared/data/Rewritten.kt
{ echo 'package a.b'; echo; body 20; } | sed 's/line number/never in the original/' > shared/data/BrandNew.kt
for f in scripts/plain.sh scripts/helpful.sh res/values/a.xml res/values/b.xml page.html docs/front.md \
         docs/plain.md Dockerfile d2/Dockerfile ci.yml a.css package.json img/pic.png third_party/x/lib.js \
         daemon/test/fixtures/data.ts skills/hyper-cosmos/SKILL.md; do
  echo '# changed' >> "$f"
done
echo 'THE NOTICE, and more' > NOTICE
git rm -q app/data/Gone.kt
git add -A
git commit -qm 'the fork'
help_before="$(bash scripts/helpful.sh -h)"

# ── --check finds them ──────────────────────────────────────────────────────
out="$("$SCRIPT" --check 2>&1)" && rc=0 || rc=$?
same "--check fails when notices are missing" 1 "$rc"
for f in shared/data/Moved.kt shared/data/Renamed.kt shared/data/Rewritten.kt scripts/plain.sh res/values/a.xml page.html docs/plain.md Dockerfile; do
  case "$out" in *"missing a notice: $f"*) ok "--check names $f" ;; *) bad "--check doesn't name $f" ;; esac
done
case "$out" in *'BrandNew'*|*'Untouched'*|*'lib.js'*|*'SKILL.md'*|*'pic.png'*|*'package.json'*|*'fixtures'*) bad "--check names something it should leave alone: $out" ;; *) ok "--check leaves out new, unchanged, third-party, test data, images and JSON" ;; esac
case "$out" in *"differs from the root's NOTICE"*) ok "--check sees the APK's NOTICE out of date" ;; *) bad "--check misses the APK's NOTICE" ;; esac

# ── --fix adds them, in the right place ─────────────────────────────────────
"$SCRIPT" --fix > /dev/null
same "--check passes after --fix" "license notices: all there." "$("$SCRIPT" --check)"
S='// Modified by Leonardo Merza, 2026. See NOTICE.' H='# Modified by Leonardo Merza, 2026. See NOTICE.' A='<!-- Modified by Leonardo Merza, 2026. See NOTICE. -->'
same "Kotlin: before package, moved file"   "$S" "$(sed -n 1p shared/data/Moved.kt)"
same "Kotlin: package comes next"           "package a.b" "$(sed -n 2p shared/data/Moved.kt)"
same "Kotlin: moved, renamed and changed"   "$S" "$(sed -n 1p shared/data/Renamed.kt)"
same "Kotlin: moved and rewritten"          "$S" "$(sed -n 1p shared/data/Rewritten.kt)"
same "shell: the shebang stays first"       '#!/usr/bin/env bash' "$(sed -n 1p scripts/plain.sh)"
same "shell: then the notice"               "$H" "$(sed -n 2p scripts/plain.sh)"
same "shell: stays executable"              yes "$([ -x scripts/plain.sh ] && echo yes || echo no)"
same "shell that prints its header: below it" "$H" "$(sed -n 4p scripts/helpful.sh)"
same "shell that prints its header: same help" "$help_before" "$(bash scripts/helpful.sh -h)"
same "XML: the declaration stays first"     '<?xml version="1.0" encoding="utf-8"?>' "$(sed -n 1p res/values/a.xml)"
same "XML: then the notice"                 "$A" "$(sed -n 2p res/values/a.xml)"
same "XML with no declaration: first"       "$A" "$(sed -n 1p res/values/b.xml)"
same "HTML: the doctype stays first"        '<!doctype html>' "$(sed -n 1p page.html)"
same "HTML: then the notice"                "$A" "$(sed -n 2p page.html)"
same "Markdown: front matter stays first"   '---' "$(sed -n 1p docs/front.md)"
same "Markdown: below its closing line"     "$A" "$(sed -n 4p docs/front.md)"
same "Markdown: first"                      "$A" "$(sed -n 1p docs/plain.md)"
same "Dockerfile: first"                    "$H" "$(sed -n 1p Dockerfile)"
same "Dockerfile: a parser directive stays first" '# syntax=docker/dockerfile:1' "$(sed -n 1p d2/Dockerfile)"
same "Dockerfile: then the notice"          "$H" "$(sed -n 2p d2/Dockerfile)"
same "YAML: first"                          "$H" "$(sed -n 1p ci.yml)"
same "CSS: first"                           '/* Modified by Leonardo Merza, 2026. See NOTICE. */' "$(sed -n 1p a.css)"
same "NOTICE's copy made the same"          "$(cat NOTICE)" "$(cat android/app/src/main/assets/legal/NOTICE)"

# ── What it must leave alone ────────────────────────────────────────────────
for f in shared/data/BrandNew.kt app/data/Untouched.kt third_party/x/lib.js daemon/test/fixtures/data.ts skills/hyper-cosmos/SKILL.md package.json img/pic.png NOTICE LICENSE; do
  if grep -aq 'Modified by' "$f"; then bad "$f got a notice"; else ok "$f left alone"; fi
done

# ── Idempotent ──────────────────────────────────────────────────────────────
git add -A
git commit -qm notices
same "--fix again adds nothing" "" "$("$SCRIPT" --fix)"
same "--fix again changes no file" "" "$(git status --porcelain)"

# ── A new branch brings a file back to missing ──────────────────────────────
{ echo 'package a.b'; echo; body 3; } > shared/data/Moved.kt
"$SCRIPT" --check > /dev/null 2>&1 && bad "--check passes with a notice removed" || ok "--check fails when a notice goes away"
git checkout -q -- shared/data/Moved.kt

# ── A type it doesn't know is someone's decision, not a silent skip ─────────
echo 'text' > odd.xyz; git add -A; git commit -qm 'odd' -q
export LICENSE_NOTICES_BASE="$(git rev-parse HEAD)"
echo 'more' >> odd.xyz; git add -A; git commit -qm 'odd changed' -q
out="$("$SCRIPT" --check 2>&1)" && rc=0 || rc=$?
same "--check fails on an unknown text type" 1 "$rc"
case "$out" in *"odd.xyz"*) ok "--check names it" ;; *) bad "--check doesn't name it" ;; esac

# ── A commit it can't find: no silent pass ──────────────────────────────────
LICENSE_NOTICES_BASE=0000000000000000000000000000000000000000 "$SCRIPT" --check > /dev/null 2>&1 && rc=0 || rc=$?
same "no such base commit: exit 2" 2 "$rc"

# ── The real Linux tarball: the .deb and the Mac's tarball and .pkg are made from it ─────────────
unset LICENSE_NOTICES_ROOT LICENSE_NOTICES_BASE
listing="$(tar -tzf "$("$HERE/pack-linux.sh" | tail -n 1)")"
for f in LICENSE NOTICE THIRD-PARTY-NOTICES.md CHANGES.md; do
  if printf '%s\n' "$listing" | grep -Eq "^[^/]+/$f\$"; then ok "the Linux tarball holds $f"; else bad "the Linux tarball has no $f"; fi
done

echo
if [ "$fails" -eq 0 ]; then echo "All passed."; else echo "$fails failed."; exit 1; fi
