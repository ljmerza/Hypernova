#!/usr/bin/env bash
# Apache-2.0 section 4(b): a modified file carries a notice that it was changed (docs/DECISIONS.md,
# "The Apache licence's notices"). This finds every file of the original, prudnik-web/Hypernova at the
# fork point, that differs now, moved ones included, and looks for the one-line notice in it.
#
#   scripts/license-notices.sh --check   list what's missing; exit 1 if anything is (CI runs this)
#   scripts/license-notices.sh --fix     add what's missing; running it again changes nothing
#   scripts/license-notices.sh --list    every file it looks at, by kind (the counts, CHANGES.md's list)
#
# A file counts as the original's when git says it was changed or renamed from one (40% alike or more),
# or when it has the same name and parent folder as a file that's gone from where it was (a file moved
# to android/shared and then rewritten). A new file gets no notice. Third-party code, the synced skill
# and test data stay as they are, and a file that can't hold a comment is covered by CHANGES.md. A text
# file of a type this doesn't know fails, so that someone decides, rather than getting skipped.
#
# It also keeps the APK's assets/legal/ the same as the root's NOTICE, LICENSE and
# THIRD-PARTY-NOTICES.md and third_party/'s licences, and fails on a file in the APK's assets that
# asset_licence doesn't know, so a new third-party file can't go in without its licence.
# Checked by scripts/test-license-notices.sh. Needs the full history (CI: fetch-depth: 0).
set -euo pipefail
export LC_ALL=C
ROOT="${LICENSE_NOTICES_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
BASE="${LICENSE_NOTICES_BASE:-c64bbf9a448b54cbc145d3c005b10a4b86f3f1f0}"
TEXT="Modified by Leonardo Merza, 2026. See NOTICE."
LEGAL="android/app/src/main/assets/legal"

usage() { sed -n '6,8p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2; exit 2; }
[ $# -eq 1 ] || usage
MODE="$1"
case "$MODE" in --check|--fix|--list) ;; *) usage ;; esac
cd "$ROOT"
git cat-file -e "$BASE^{commit}" 2>/dev/null || { echo "license-notices.sh: no commit $BASE here: it needs the full history." >&2; exit 2; }

# The paths, as they're called now, of the original's files that differ now.
derived_files() {
  git -c core.quotepath=off diff -M40% -l0 --name-status "$BASE" | awk -F'\t' '
    function tail2(p, a, n) { n = split(p, a, "/"); return n > 1 ? a[n - 1] "/" a[n] : a[n] }
    $1 ~ /^[MT]/ { print $2 }
    $1 ~ /^R/    { print $3 }
    $1 == "D"    { gone[tail2($2)] = 1 }
    $1 == "A"    { added[$2] = 1 }
    END { for (p in added) if (tail2(p) in gone) print p }
  ' | sort -u
}

# What a path is: a comment syntax, or why it gets no comment.
kind_of() {
  local path="$1" name="${1##*/}"
  case "$path" in
    third_party/*|skills/hyper-cosmos/*) echo keep; return ;;
    "$LEGAL"/LICENSE*|"$LEGAL"/OFL-*) echo keep; return ;;
    android/app/src/main/assets/term/xterm.*|android/app/src/main/assets/term/addon-*) echo keep; return ;;
    android/app/src/main/assets/term-ghostty/ghostty-web.js|android/app/src/main/assets/mermaid/mermaid.min.js) echo keep; return ;;
    protocol/fixtures/*|*/test/fixtures/*|android/app/src/test/snapshots/*|android/app/src/test/resources/*) echo keep; return ;;
    LICENSE) echo keep; return ;;
    NOTICE|"$LEGAL"/NOTICE) echo plain; return ;;
  esac
  if [ -L "$path" ]; then echo binary; return; fi
  case "$name" in
    *.kt|*.kts|*.ts|*.tsx|*.js|*.mjs|*.cjs|*.java|*.swift|*.go|*.gradle) echo slash ;;
    *.sh|*.bash|*.yml|*.yaml|*.toml|*.pro|*.properties|*.py|.gitignore|.dockerignore|.gitattributes|Dockerfile|Makefile|gradlew) echo hash ;;
    *.xml|*.html|*.md) echo angle ;;
    *.css) echo star ;;
    *.json|*.jsonl) echo json ;;
    *.svg|*.png|*.jpg|*.jpeg|*.gif|*.webp|*.ico|*.ttf|*.otf|*.woff|*.woff2|*.jar|*.wasm|*.mp3|*.wav|*.gz|*.zip|*.aar) echo binary ;;
    *.txt) echo plain ;;
    *) if [ -s "$path" ] && ! LC_ALL=C grep -Iq . "$path"; then echo binary; else echo unknown; fi ;;
  esac
}

notice_for() {
  case "$1" in
    slash) printf '// %s' "$TEXT" ;;
    hash)  printf '# %s' "$TEXT" ;;
    angle) printf '<!-- %s -->' "$TEXT" ;;
    star)  printf '/* %s */' "$TEXT" ;;
  esac
}

# How many of the file's lines stay above the notice.
lines_above() {
  local path="$1" kind="$2" first
  IFS= read -r first < "$path" || true
  case "$kind" in
    hash)
      # A script that prints its own header as its help (sed -n '2,19p' "$0"): below the header, so
      # the help stays the same. Docker's parser directives have to stay the first lines.
      if grep -Eq "sed -n '[0-9]+,[0-9]+p' \"\\\$0\"" "$path"; then
        awk '!/^#/ { print NR - 1; found = 1; exit } END { if (!found) print NR }' "$path"
      elif [ "${path##*/}" = Dockerfile ]; then
        awk '!/^# *(syntax|escape|check)=/ { print NR - 1; found = 1; exit } END { if (!found) print NR }' "$path"
      else
        case "$first" in '#!'*) echo 1 ;; *) echo 0 ;; esac
      fi ;;
    slash) case "$first" in '#!'*) echo 1 ;; *) echo 0 ;; esac ;;
    star)  case "$first" in '@charset'*) echo 1 ;; *) echo 0 ;; esac ;;
    angle)
      case "$first" in
        '<?xml'*|'<!doctype'*|'<!DOCTYPE'*) echo 1 ;;
        # Front matter stays first: below its closing line.
        ---) awk 'NR > 1 && /^---[[:space:]]*$/ { print NR; found = 1; exit } END { if (!found) print 0 }' "$path" ;;
        *) echo 0 ;;
      esac ;;
  esac
}

has_notice() { grep -Fxq -- "$(notice_for "$2")" "$1"; }

add_notice() {
  local path="$1" kind="$2" above tmp
  above="$(lines_above "$path" "$kind")"
  tmp="$(mktemp)"
  { [ "$above" -eq 0 ] || head -n "$above" "$path"; notice_for "$kind"; echo; tail -n "+$((above + 1))" "$path"; } > "$tmp"
  # Written over, not moved: the file keeps its mode.
  cat "$tmp" > "$path"
  rm -f "$tmp"
}

# What assets/legal/ has to hold a copy of.
legal_sources() {
  local name
  for name in NOTICE LICENSE THIRD-PARTY-NOTICES.md; do [ ! -e "$name" ] || echo "$name"; done
  git ls-files -co --exclude-standard -- third_party | grep -E '/(LICENSE|OFL)[^/]*$' || true
}

# What a file in the APK's assets answers to: this project's own, or its licences in assets/legal/.
asset_licence() {
  local name="${1##*/}"
  case "$1" in
    legal/*|term/index.html|term-ghostty/index.html|mermaid/index.html|mermaid/render.js|mermaid/frames.js) echo own ;;
    term/xterm.js|term/xterm.css) echo LICENSE-xterm ;;
    term/addon-unicode-graphemes.js) echo LICENSE-addon-unicode-graphemes LICENSES-xterm-bundled.txt ;;
    term/addon-*.js) echo "LICENSE-${name%.js}" ;;
    term/JetBrainsMono.ttf) echo OFL-JetBrainsMono.txt ;;
    term-ghostty/ghostty-web.js) echo LICENSE-ghostty-web LICENSE-ghostty ;;
    mermaid/mermaid.min.js) echo LICENSE-mermaid LICENSES-mermaid-bundled.txt ;;
    *) echo unknown ;;
  esac
}

case "$MODE" in
  --list)
    derived_files | while IFS= read -r path; do printf '%s\t%s\n' "$(kind_of "$path")" "$path"; done
    exit 0 ;;
  --fix)
    # Adding a line can change what git pairs up, so it goes round until nothing more needs adding.
    for _ in 1 2 3 4 5; do
      added=0
      while IFS= read -r path; do
        kind="$(kind_of "$path")"
        case "$kind" in slash|hash|angle|star) ;; *) continue ;; esac
        has_notice "$path" "$kind" && continue
        add_notice "$path" "$kind"
        echo "added: $path"
        added=1
      done < <(derived_files)
      [ "$added" -eq 1 ] || break
    done
    while IFS= read -r source; do
      copy="$LEGAL/${source##*/}"
      if ! cmp -s "$source" "$copy"; then cp "$source" "$copy"; echo "copied: $source to $LEGAL/"; fi
    done < <(legal_sources) ;;
esac

# Both --check and what --fix leaves behind.
missing=0
while IFS= read -r path; do
  kind="$(kind_of "$path")"
  case "$kind" in
    slash|hash|angle|star)
      if ! has_notice "$path" "$kind"; then echo "missing a notice: $path"; missing=1; fi ;;
    unknown)
      echo "can't tell how to comment this type (add it to kind_of in scripts/license-notices.sh): $path"; missing=1 ;;
  esac
done < <(derived_files)
while IFS= read -r source; do
  copy="$LEGAL/${source##*/}"
  if ! cmp -s "$source" "$copy"; then echo "differs from $source, or is missing: $copy"; missing=1; fi
done < <(legal_sources)
while IFS= read -r asset; do
  asset="${asset#android/app/src/main/assets/}"
  for licence in $(asset_licence "$asset"); do
    case "$licence" in
      own) ;;
      unknown) echo "whose is this asset? Say in asset_licence (scripts/license-notices.sh): $asset"; missing=1 ;;
      *) if [ ! -e "$LEGAL/$licence" ]; then echo "no licence for $asset: $LEGAL/$licence"; missing=1; fi ;;
    esac
  done
done < <(git ls-files -co --exclude-standard -- android/app/src/main/assets)
if [ "$missing" -ne 0 ]; then
  [ "$MODE" = --fix ] || echo "Run scripts/license-notices.sh --fix." >&2
  exit 1
fi
[ "$MODE" = --fix ] || echo "license notices: all there."
