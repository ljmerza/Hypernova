#!/usr/bin/env bash
# scripts/get-client.sh without a release: its names against client-names.sh, its options, and what it decides on each
# system, under the bash a Mac ships (3.2), with stand-ins for curl, uname, sudo, apt, hdiutil and the like and only the
# standard tools on PATH. It catches what bash 3.2 refuses and what the script decides. It proves nothing about apt, a
# Mac or Windows themselves: `scripts/test-client-package.sh --get-client` does that, against the real packages.
#
#   scripts/test-get-client.sh            with the bash it's run by
#   scripts/test-get-client.sh --docker   under bash 3.2, in Docker's bash:3.2 image
#
# Touches nothing but its scratch folder (a stand-in HOME and a stand-in /Applications), and reaches no network.
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ "${1:-}" = --docker ]; then
  exec docker run --rm --network none --user 1000:1000 -e HOME=/tmp -v "$ROOT:/repo:ro" bash:3.2 bash /repo/scripts/test-get-client.sh
fi
SCRIPT="$ROOT/scripts/get-client.sh"
NAMES="$ROOT/android/desktop/packaging/client-names.sh"
PAGES="$ROOT/.github/workflows/pages.yml"
WORKFLOW="$ROOT/.github/workflows/client-packages.yml"

fails=0
pass=0
ok() { pass=$((pass + 1)); printf '  ok   %s\n' "$1"; }
bad() { fails=$((fails + 1)); printf '  FAIL %s\n' "$1"; [ -n "${2:-}" ] && printf '%s\n' "$2" | sed 's/^/       /'; }
has() { # <what> <text> <needle>
  case "$2" in *"$3"*) ok "$1" ;; *) bad "$1: no [$3]" "$2" ;; esac
}
hasnt() {
  case "$2" in *"$3"*) bad "$1: has [$3]" "$2" ;; *) ok "$1" ;; esac
}
stopped() { # <what>
  if [ "$RC" -ne 0 ]; then ok "$1"; else bad "$1: exit 0" "$OUT"; fi
}
ran() { # <what>
  if [ "$RC" -eq 0 ]; then ok "$1"; else bad "$1: exit $RC" "$OUT"; fi
}
one_line_error() { # <what>
  if [ "$(printf '%s\n' "$OUT" | grep -c '^get-client.sh: ')" = 1 ]; then ok "$1"; else bad "$1: not one error line" "$OUT"; fi
}
echo "bash ${BASH_VERSION}"

echo "the names"
name_in() { sed -n "s/^$2='\(.*\)'\$/\1/p" "$1"; }
for n in CLIENT_PACKAGE CLIENT_TITLE; do
  want="$(name_in "$NAMES" "$n")"
  got="$(name_in "$SCRIPT" "$n")"
  [ -n "$want" ] && [ "$got" = "$want" ] && ok "$n is the same as client-names.sh: $want" || bad "$n: get-client.sh says '$got', client-names.sh says '$want'. A rename changes both."
done
[ "$(grep -c "^CLIENT_PACKAGE=\|^CLIENT_TITLE=" "$SCRIPT")" = 2 ] && ok "each is set once" || bad "the names are set more than once"
hasnt "and neither is written out again in what it runs" "$(grep -v "^CLIENT_PACKAGE=\|^CLIENT_TITLE=" "$SCRIPT" | grep -v '^ *#' | grep -n 'hypernova-client\|Hypernova Client')" 'ypernova'
# The files it asks for are the ones the workflow attaches and the notes list.
PACKAGE="$(name_in "$NAMES" CLIENT_PACKAGE)"
TITLE="$(name_in "$NAMES" CLIENT_TITLE)"
NOT_BUILT="$(cat "$ROOT/scripts/release-not-built.sh")"
for suffix in linux-amd64.deb linux-arm64.deb linux-x64.tar.gz macos-arm64.dmg macos-x64.dmg windows-x64.msi; do
  has "release-not-built.sh knows $suffix too" "$NOT_BUILT" "\$CLIENT_PACKAGE-\$tag-$suffix"
done

echo "where it's served, and what proves it"
pages="$(cat "$PAGES")"
has "pages serves get-client.sh" "$pages" 'cp scripts/get-client.sh _site/get-client.sh'
has "pages still serves get.sh" "$pages" 'cp scripts/get.sh _site/get.sh'
has "and the same file as get-server.sh" "$pages" 'cp scripts/get.sh _site/get-server.sh'
has "a change to it deploys" "$pages" "- 'scripts/get-client.sh'"
workflow="$(cat "$WORKFLOW")"
has "client-packages runs on a change to it" "$workflow" "- 'scripts/get-client.sh'"
has "and on a change to this" "$workflow" "- 'scripts/test-get-client.sh'"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
TOOLS="$WORK/tools"     # Real tools, and no others: what it may rely on.
FAKES="$WORK/fakes"     # curl, uname, id and the like, always there.
APT="$WORK/apt"         # There on a system with apt.
SUDO="$WORK/sudo"
MACTOOLS="$WORK/mac"
SHA256="$WORK/sha256sum"
SHASUM="$WORK/shasum"
mkdir -p "$TOOLS" "$FAKES" "$APT" "$SUDO" "$MACTOOLS" "$SHA256" "$SHASUM"
REAL_SHA="$(command -v sha256sum || true)"
for t in awk basename cat chmod cp grep gzip head ln mkdir mktemp mv pwd readlink rm sed sleep tail tar touch tr yes; do
  real="$(command -v "$t" || true)"
  [ -z "$real" ] || case "$real" in /*) ln -s "$real" "$TOOLS/$t" ;; esac
done
# What a Mac has instead of sha256sum, standing in where there's neither (and on a Mac, where there's no sha256sum, the real one).
if [ -n "$REAL_SHA" ]; then
  ln -s "$REAL_SHA" "$SHA256/sha256sum"
  printf '#!/bin/sh\n[ "$1" = -a ] && [ "$2" = 256 ] || exit 9\necho shasum >> "$FAKE_LOG"\nexec "%s"\n' "$REAL_SHA" > "$SHASUM/shasum"
else
  ln -s "$(command -v shasum)" "$SHASUM/shasum"
  printf '#!/bin/sh\nexec "%s" -a 256\n' "$(command -v shasum)" > "$SHA256/sha256sum"
fi
sum_of() { "$SHA256/sha256sum" <"$1" | awk '{print $1}'; }

cat > "$FAKES/uname" <<'EOF'
#!/bin/sh
case "$1" in -s) echo "${FAKE_OS:-Linux}" ;; -m) echo "${FAKE_MACHINE:-x86_64}" ;; *) echo Linux ;; esac
EOF
cat > "$FAKES/id" <<'EOF'
#!/bin/sh
echo "${FAKE_UID:-1000}"
EOF
# curl: by the URL's last word, from the stand-in GitHub.
cat > "$FAKES/curl" <<'EOF'
#!/bin/sh
out="" url=""
while [ $# -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2 ;;
    -H) shift 2 ;;
    -*) shift ;;
    *) url="$1"; shift ;;
  esac
done
echo "curl $url" >> "$FAKE_LOG"
if [ -n "${FAKE_HOLD:-}" ] && [ -n "$out" ] && [ "${out##*/}" != SHA256SUMS ]; then
  : > "$FAKE_HOLD"
  sleep 2
fi
f="$FAKE_DIST/${url##*/}"
[ -f "$f" ] || exit 22
if [ -n "$out" ]; then cp "$f" "$out"; else cat "$f"; fi
EOF
printf '#!/bin/sh\necho "sudo $*" >> "$FAKE_LOG"\nexec "$@"\n' > "$SUDO/sudo"
# apt: what it can see of the file, as its own user would: a mode that lets others in, all the way down.
cat > "$APT/apt" <<'EOF'
#!/bin/sh
echo "apt $*" >> "$FAKE_LOG"
last=""
for a in "$@"; do last="$a"; done
case "$last" in /*|./*) echo "a path, so a file" >> "$FAKE_LOG" ;; esac
ls -ld "$(dirname "$last")" "$last" | cut -c1-10 | tr '\n' ' ' >> "$FAKE_LOG"
echo >> "$FAKE_LOG"
echo "$(dirname "$last")" > "$FAKE_LOG.aptdir"
# Standard input isn't the script's: nothing here could eat it.
if [ -t 0 ]; then :; else cat >> "$FAKE_LOG.aptstdin"; fi
[ "${FAKE_APT_FAIL:-0}" = 0 ] || { echo "E: Unable to correct problems" >&2; exit 100; }
EOF
printf '#!/bin/sh\necho "${FAKE_DPKG_ARCH:-amd64}"\n' > "$APT/dpkg"
printf '#!/bin/sh\necho "1.2.3"\n' > "$APT/dpkg-query"
cp "$(command -v dirname)" "$APT/dirname" 2>/dev/null || ln -s "$(command -v dirname)" "$APT/dirname"
for t in ls cut; do ln -s "$(command -v "$t")" "$APT/$t"; done
# A Mac's. The image's folder is FAKE_IMAGE; "attached" means copied to the mountpoint.
cat > "$MACTOOLS/sysctl" <<'EOF'
#!/bin/sh
echo "${FAKE_ARM64:-1}"
EOF
cat > "$MACTOOLS/hdiutil" <<'EOF'
#!/bin/sh
echo "hdiutil $*" >> "$FAKE_LOG"
case "$1" in
  attach)
    for a in "$@"; do [ "$prev" = -mountpoint ] && m="$a"; prev="$a"; done
    [ "${FAKE_NO_APP:-0}" = 1 ] || cp -R "$FAKE_IMAGE/." "$m/"
    echo "attached $m"
    ;;
  detach) rm -rf "$2"/* ;;
esac
EOF
cat > "$MACTOOLS/ditto" <<'EOF'
#!/bin/sh
echo "ditto" >> "$FAKE_LOG"
[ "${FAKE_DITTO_FAIL:-0}" = 0 ] || exit 1
mkdir -p "$2" && cp -R "$1/." "$2/"
EOF
cat > "$MACTOOLS/pgrep" <<'EOF'
#!/bin/sh
echo "pgrep $*" >> "$FAKE_LOG"
[ "${FAKE_RUNNING:-0}" = 1 ]
EOF
# It must never touch either of these.
for t in xattr spctl; do printf '#!/bin/sh\necho "%s $*" >> "$FAKE_LOG"\n' "$t" > "$MACTOOLS/$t"; done
chmod +x "$FAKES"/* "$APT"/* "$SUDO"/* "$MACTOOLS"/* "$SHASUM"/* "$SHA256"/* 2>/dev/null

# The stand-in release.
DIST="$WORK/dist"
mkdir -p "$DIST"
T=v1.2.3
pkg="$WORK/pack/$PACKAGE"
mkdir -p "$pkg/bin" "$pkg/lib/app"
printf '#!/bin/sh\necho "the app, new"\n' > "$pkg/bin/$PACKAGE"
chmod +x "$pkg/bin/$PACKAGE"
echo one > "$pkg/lib/app/file"
tar -czf "$DIST/$PACKAGE-$T-linux-x64.tar.gz" -C "$WORK/pack" "$PACKAGE"
mkdir -p "$WORK/nolauncher/$PACKAGE/lib"
tar -czf "$WORK/nolauncher.tar.gz" -C "$WORK/nolauncher" "$PACKAGE"
for f in linux-amd64.deb linux-arm64.deb macos-arm64.dmg macos-x64.dmg windows-x64.msi; do printf '%s' "the $f" > "$DIST/$PACKAGE-$T-$f"; done
printf '%s' apk > "$DIST/$PACKAGE-$T-android.apk"
: > "$WORK/empty"
write_sums() { ( cd "$DIST" && for f in "$PACKAGE"-*; do printf '%s  %s\n' "$(sum_of "$f")" "$f"; done ) > "$DIST/SHA256SUMS"; }
write_sums
write_api() { # <file to answer> <tag>
  {
    printf '{ "tag_name": "%s", "name": "x", "body": "not this: \\"tag_name\\": \\"v0.0.0\\"",\n  "assets": [\n' "$2"
    for f in "$DIST"/*; do
      case "${f##*/}" in latest|v*|SHA256SUMS.keep) continue ;; esac
      printf '  { "name": "%s", "browser_download_url": "https://github.test/dl/%s/%s" },\n' "${f##*/}" "$2" "${f##*/}"
    done
    printf '  { "name": "end" } ] }\n'
  } > "$1"
}
write_api "$DIST/latest" "$T"
# The tag's request ends in the tag.
write_api "$DIST/$T" "$T"

IMAGE="$WORK/image"
mkdir -p "$IMAGE/$TITLE.app/Contents/MacOS"
printf '#!/bin/sh\n' > "$IMAGE/$TITLE.app/Contents/MacOS/$TITLE"
chmod +x "$IMAGE/$TITLE.app/Contents/MacOS/$TITLE"
echo new > "$IMAGE/$TITLE.app/version"

BASH_BIN="$(command -v bash)"
newhome() {
  HOMEDIR="$WORK/home-$RANDOM$RANDOM"
  mkdir -p "$HOMEDIR/tmp" "$HOMEDIR/Apps"
  LOG="$HOMEDIR/log"
  : > "$LOG"
  A="$HOMEDIR/Apps/$TITLE.app"
}
# <extra PATH folders, with colons> <env...> -- <args...>: the script as a file under the stand-ins.
run() {
  local extra="$1"
  shift
  local envs=()
  while [ $# -gt 0 ] && [ "$1" != -- ]; do envs+=("$1"); shift; done
  shift
  OUT="$(env -i HOME="$HOMEDIR" TMPDIR="$HOMEDIR/tmp" PATH="$extra:$FAKES:$TOOLS" FAKE_LOG="$LOG" FAKE_DIST="$DIST" FAKE_IMAGE="$IMAGE" HYPERNOVA_CLIENT_APPLICATIONS="$HOMEDIR/Apps" ${envs[@]+"${envs[@]}"} "$BASH_BIN" "$SCRIPT" "$@" 2>&1 </dev/null)"
  RC=$?
}
UBUNTU="$APT:$SUDO:$SHA256"   # Linux with apt.
FEDORA="$SUDO:$SHA256"        # Linux with no apt.
MAC="$MACTOOLS:$SHASUM"       # A Mac: shasum, not sha256sum.
DEB_A="$PACKAGE-$T-linux-amd64.deb"
TGZ="$PACKAGE-$T-linux-x64.tar.gz"

echo "options"
newhome
run "$UBUNTU" -- --nonsense
[ "$RC" -eq 2 ] && ok "an unknown option is refused" || bad "unknown option: exit $RC" "$OUT"
has "and named" "$OUT" 'what is --nonsense?'
run "$UBUNTU" -- stray
[ "$RC" -eq 2 ] && ok "so is a word that's no option" || bad "stray: exit $RC" "$OUT"
run "$UBUNTU" -- --help
ran "--help"
has "--help says the one line" "$OUT" 'curl -fsSL https://ljmerza.github.io/Hypernova/get-client.sh | bash'
has "and --tarball" "$OUT" '--tarball'
hasnt "--help downloads nothing" "$(cat "$LOG")" 'curl'
run "$UBUNTU" HYPERNOVA_TAG='../../x y' --
stopped "a tag with anything strange in it is refused"
hasnt "before anything is asked" "$(cat "$LOG")" 'curl'
run "$UBUNTU" FAKE_OS=FreeBSD --
stopped "another system is told"
has "what there is" "$OUT" 'Linux, a Mac and Windows'

echo "Linux with apt"
newhome
run "$UBUNTU" --
ran "it installs"
log="$(cat "$LOG")"
has "the amd64 .deb, from the latest" "$log" "curl https://github.test/dl/$T/$DEB_A"
has "from the release's own list, the latest's" "$log" 'curl https://api.github.com/repos/ljmerza/Hypernova/releases/latest'
has "with sudo and apt" "$log" 'sudo apt install -y /tmp/'
has "an absolute path, so apt takes it for a file" "$log" 'a path, so a file'
has "folder and file readable by apt's own user" "$log" 'drwxr-xr-x -rw-r--r--'
has "it says what's installed" "$OUT" "Installed $TITLE $T"
has "how to open it" "$OUT" "/opt/$PACKAGE/bin/$PACKAGE"
has "the next step" "$OUT" '`hypernova pair` on the server'
has "that there's no display here" "$OUT" 'has no display'
[ ! -e "$(cat "$LOG.aptdir")" ] && ok "its temp folder is gone" || bad "the temp folder stayed"
newhome
OUT="$(env -i HOME="$HOMEDIR" TMPDIR="$HOMEDIR/tmp" PATH="$UBUNTU:$FAKES:$TOOLS" FAKE_LOG="$LOG" FAKE_DIST="$DIST" "$BASH_BIN" -c "umask 077; '$BASH_BIN' '$SCRIPT'" 2>&1 </dev/null)"; RC=$?
ran "with a umask of 077 too"
has "apt can still read it" "$(cat "$LOG")" 'drwxr-xr-x -rw-r--r--'
newhome
run "$UBUNTU" FAKE_DPKG_ARCH=arm64 FAKE_MACHINE=aarch64 --
has "arm64 gets its .deb" "$(cat "$LOG")" "$PACKAGE-$T-linux-arm64.deb"
newhome
run "$UBUNTU" FAKE_DPKG_ARCH=i386 FAKE_MACHINE=x86_64 --
stopped "dpkg's word counts, as a 32-bit system on a 64-bit kernel can't take the .deb"
has "and it says so" "$OUT" 'i386'
newhome
run "$APT:$SHA256" FAKE_UID=0 --
ran "root needs no sudo"
hasnt "and doesn't use it" "$(cat "$LOG")" 'sudo'
newhome
run "$APT:$SHA256" --
stopped "no sudo and not root"
has "says so" "$OUT" 'sudo is missing'
hasnt "before downloading anything" "$(cat "$LOG")" 'curl'
newhome
run "$UBUNTU" FAKE_APT_FAIL=1 --
stopped "apt's failure stops it"
has "with the systems it needs" "$OUT" 'Ubuntu 24.04 or Debian 13'
has "and the tarball's line" "$OUT" 'bash -s -- --tarball'
one_line_error "in one line of its own"
[ ! -e "$(cat "$LOG.aptdir")" ] && ok "its temp folder is gone after a failure too" || bad "the temp folder stayed"
newhome
run "$UBUNTU" FAKE_APT_FAIL=1 FAKE_DPKG_ARCH=arm64 --
hasnt "arm64 isn't offered a tarball there is none of" "$OUT" 'bash -s -- --tarball'

echo "the release's stages"
mv "$DIST/SHA256SUMS" "$WORK/sums.keep"
write_api "$DIST/latest" "$T"
newhome
run "$UBUNTU" --
stopped "no SHA256SUMS yet stops it"
has "says so" "$OUT" "$T has no SHA256SUMS yet"
has "and to try again" "$OUT" 'try again in a few minutes'
one_line_error "in one line of its own"
hasnt "nothing large is downloaded for it" "$(cat "$LOG")" "$DEB_A"
hasnt "and apt never runs" "$(cat "$LOG")" 'apt install'
mv "$WORK/sums.keep" "$DIST/SHA256SUMS"
mv "$DIST/$DEB_A" "$WORK/deb.keep"
write_api "$DIST/latest" "$T"
newhome
run "$UBUNTU" --
stopped "the file not on the release stops it"
has "says which" "$OUT" "$DEB_A isn't on $T"
has "and to try again" "$OUT" 'try again in a few minutes'
one_line_error "in one line of its own"
mv "$WORK/deb.keep" "$DIST/$DEB_A"
cp "$DIST/SHA256SUMS" "$WORK/sums.keep"
grep -v "$DEB_A" "$WORK/sums.keep" > "$DIST/SHA256SUMS"
write_api "$DIST/latest" "$T"
newhome
run "$UBUNTU" --
stopped "a SHA256SUMS from before the file came stops it"
has "says so" "$OUT" "doesn't list $DEB_A yet"
hasnt "and apt never runs" "$(cat "$LOG")" 'apt install'
sed "s/^\([0-9a-f]*\)  /\1 */" "$WORK/sums.keep" > "$DIST/SHA256SUMS"
newhome
run "$UBUNTU" --
ran "a SHA256SUMS in binary mode reads the same"
printf '%064d  %s\n' 0 "$DEB_A" > "$DIST/SHA256SUMS"
grep -v "$DEB_A" "$WORK/sums.keep" >> "$DIST/SHA256SUMS"
newhome
run "$UBUNTU" --
stopped "a wrong checksum stops it"
has "says so" "$OUT" "doesn't match $T's SHA256SUMS"
one_line_error "in one line of its own"
hasnt "and apt never runs" "$(cat "$LOG")" 'apt install'
cp "$WORK/sums.keep" "$DIST/SHA256SUMS"
write_api "$DIST/latest" "$T"

echo "which release"
newhome
run "$UBUNTU" HYPERNOVA_TAG=$T --
ran "HYPERNOVA_TAG picks one"
has "by its tag's address" "$(cat "$LOG")" "releases/tags/$T"
hasnt "and doesn't ask for the latest" "$(cat "$LOG")" 'releases/latest'
newhome
run "$UBUNTU" HYPERNOVA_TAG=v9.9.9 --
stopped "a tag GitHub doesn't know stops it"
has "with what it asked" "$OUT" 'release v9.9.9'
one_line_error "in one line of its own"
mv "$DIST/latest" "$WORK/latest.keep"
newhome
run "$UBUNTU" --
stopped "GitHub not answering stops it"
has "says so" "$OUT" "couldn't read the latest release"
mv "$WORK/latest.keep" "$DIST/latest"

echo "the checksum's tools"
newhome
run "$APT:$SUDO:$SHASUM" --
ran "shasum does where sha256sum isn't"
has "and is what ran" "$(cat "$LOG")" 'shasum'
newhome
run "$APT:$SUDO" --
stopped "with neither it stops"
has "says so" "$OUT" 'install sha256sum or shasum'
hasnt "before downloading anything" "$(cat "$LOG")" 'curl'

if mkdir "$WORK/mvT" && "$TOOLS/ln" -s a "$WORK/mvT/x" && "$TOOLS/ln" -s b "$WORK/mvT/y" && "$TOOLS/mv" -T "$WORK/mvT/y" "$WORK/mvT/x" 2>/dev/null; then
  echo "Linux without apt, and --tarball"
  newhome
  mkdir -p "$HOMEDIR/.local/share/$PACKAGE/prefs"
  echo paired > "$HOMEDIR/.local/share/$PACKAGE/prefs/hypernova.json"
  run "$FEDORA" --
  ran "no apt: the tarball, with no sudo"
  hasnt "no sudo" "$(cat "$LOG")" 'sudo'
  has "the x64 tarball" "$(cat "$LOG")" "$TGZ"
  BASE="$HOMEDIR/.local/share/$PACKAGE-app"
  LINK="$HOMEDIR/.local/bin/$PACKAGE"
  [ "$("$LINK")" = "the app, new" ] && ok "the link in ~/.local/bin opens it" || bad "the link doesn't run" "$(ls -laR "$HOMEDIR/.local")"
  has "it says how" "$OUT" "Open it: $LINK"
  has "that ~/.local/bin isn't on PATH here" "$OUT" "isn't on your PATH"
  has "and the next step" "$OUT" '`hypernova pair` on the server'
  [ "$(cat "$HOMEDIR/.local/share/$PACKAGE/prefs/hypernova.json")" = paired ] && ok "the client's own folder, with its key and pairing, isn't touched" || bad "the data folder changed"
  hasnt "nothing of the app goes in that folder" "$(ls "$HOMEDIR/.local/share/$PACKAGE")" 'bin'
  [ -z "$(ls -A "$HOMEDIR/tmp")" ] && ok "its temp folder is gone" || bad "left in temp: $(ls -A "$HOMEDIR/tmp")"
  first="$(readlink "$BASE/current")"

  printf '#!/bin/sh\necho "the app, newer"\n' > "$pkg/bin/$PACKAGE"
  tar -czf "$DIST/$TGZ" -C "$WORK/pack" "$PACKAGE"
  write_sums
  run "$FEDORA" --
  ran "again: it updates"
  [ "$("$LINK")" = "the app, newer" ] && ok "the link opens the new one" || bad "the link: $("$LINK")"
  [ "$(readlink "$BASE/current")" != "$first" ] && ok "in a new folder" || bad "same folder"
  [ ! -e "$BASE/$first" ] && [ ! -e "$BASE/${first%%/*}" ] && ok "the older copy is gone" || bad "the older one stayed" "$(ls -la "$BASE")"
  [ "$(cat "$HOMEDIR/.local/share/$PACKAGE/prefs/hypernova.json")" = paired ] && ok "the pairing is still there" || bad "the data folder changed"

  now="$(readlink "$BASE/current")"
  cp "$DIST/$TGZ" "$WORK/tgz.keep"
  cp "$DIST/SHA256SUMS" "$WORK/sums.keep"
  cp "$WORK/nolauncher.tar.gz" "$DIST/$TGZ"
  write_sums
  run "$FEDORA" --
  stopped "a tarball with no launcher stops it"
  [ "$(readlink "$BASE/current")" = "$now" ] && [ "$("$LINK")" = "the app, newer" ] && ok "and the copy there is as it was" || bad "the copy changed"
  [ "$(ls "$BASE" | wc -l | tr -d ' ')" = 2 ] && ok "with nothing left beside it" || bad "left beside it" "$(ls -la "$BASE")"
  printf 'not a tarball' > "$DIST/$TGZ"
  write_sums
  run "$FEDORA" --
  stopped "a file tar can't read stops it"
  [ "$(readlink "$BASE/current")" = "$now" ] && [ "$(ls "$BASE" | wc -l | tr -d ' ')" = 2 ] && ok "and the copy there is as it was" || bad "the copy changed" "$(ls -la "$BASE")"
  cp "$WORK/tgz.keep" "$DIST/$TGZ"
  printf 'one byte more' >> "$DIST/$TGZ"
  cp "$WORK/sums.keep" "$DIST/SHA256SUMS"
  run "$FEDORA" --
  stopped "a wrong checksum stops it"
  [ "$(readlink "$BASE/current")" = "$now" ] && [ "$(ls "$BASE" | wc -l | tr -d ' ')" = 2 ] && ok "and the copy there is as it was" || bad "the copy changed" "$(ls -la "$BASE")"
  cp "$WORK/tgz.keep" "$DIST/$TGZ"

  # What a run killed outright leaves goes the next time.
  mkdir -p "$BASE/new.stale/half"
  run "$FEDORA" --
  ran "after a run that was killed"
  [ ! -e "$BASE/new.stale" ] && ok "what it left is cleared" || bad "new.stale stayed"

  if [ -r /proc/self/exe ]; then
    # A copy still open: a copy of bash as its launcher, so that's what /proc says is running.
    was="$(cd "$BASE/current" && pwd -P)"
    cp -f "$BASH_BIN" "$was/bin/$PACKAGE.running"
    "$was/bin/$PACKAGE.running" -c 'while :; do sleep 1; done' >/dev/null 2>&1 &
    open_pid=$!
    sleep 1
    run "$FEDORA" --
    ran "with an older copy open it still updates"
    has "and says to quit it" "$OUT" 'An older copy is still open'
    [ -x "$was/bin/$PACKAGE.running" ] && ok "whose folder stays under it" || bad "the open copy's folder went"
    kill "$open_pid" 2>/dev/null
    wait "$open_pid" 2>/dev/null
    run "$FEDORA" --
    [ ! -e "$was" ] && ok "and goes the next time" || bad "still there: $was"
  fi

  newhome
  mkdir -p "$HOMEDIR/.local/bin"
  echo mine > "$HOMEDIR/.local/bin/$PACKAGE"
  run "$FEDORA" --
  stopped "a file of someone's own at the link's place stops it"
  has "says which" "$OUT" "$HOMEDIR/.local/bin/$PACKAGE is already there"
  hasnt "before anything is downloaded" "$(cat "$LOG")" 'curl'
  [ "$(cat "$HOMEDIR/.local/bin/$PACKAGE")" = mine ] && ok "and leaves it" || bad "it was replaced"

  newhome
  run "$UBUNTU" -- --tarball
  ran "--tarball with apt there"
  has "takes the tarball" "$(cat "$LOG")" "$TGZ"
  hasnt "with neither apt nor sudo" "$(cat "$LOG")" 'apt install'
  hasnt "not even sudo" "$(cat "$LOG")" 'sudo'
  newhome
  run "$FEDORA" FAKE_UID=0 --
  stopped "root is refused for the tarball"
  has "as get.sh says it" "$OUT" 'not root'
  hasnt "before anything" "$(cat "$LOG")" 'curl'
  newhome
  run "$UBUNTU" FAKE_UID=0 -- --tarball
  stopped "root's --tarball too"

  # Stopped in the download: nothing stays. Its exit is the signal's, and the folder goes.
  newhome
  env -i HOME="$HOMEDIR" TMPDIR="$HOMEDIR/tmp" PATH="$FEDORA:$FAKES:$TOOLS" FAKE_LOG="$LOG" FAKE_DIST="$DIST" FAKE_HOLD="$HOMEDIR/holding" "$BASH_BIN" "$SCRIPT" >/dev/null 2>&1 </dev/null &
  held=$!
  i=0
  while [ ! -e "$HOMEDIR/holding" ] && [ "$i" -lt 100 ]; do sleep 0.1; i=$((i + 1)); done
  kill -TERM "$held"
  wait "$held"
  rc=$?
  [ "$rc" -eq 143 ] && ok "a TERM in the middle of the download stops it" || bad "a TERM: exit $rc"
  [ -z "$(ls -A "$HOMEDIR/tmp")" ] && ok "and the temp folder is gone" || bad "left in temp: $(ls -A "$HOMEDIR/tmp")"
  [ ! -e "$HOMEDIR/.local/bin/$PACKAGE" ] && ok "with nothing installed" || bad "something installed"
else
  echo "(no mv -T here, as on a Mac: the tarball's cases are Linux's, and run there)"
fi

echo "Linux on arm64 without apt"
newhome
run "$FEDORA" FAKE_MACHINE=aarch64 --
stopped "arm64 with no apt stops"
has "and says there's no package" "$OUT" 'no desktop client for Linux on arm64 without apt'
one_line_error "in one line of its own"
hasnt "having downloaded nothing" "$(cat "$LOG")" 'curl'
newhome
run "$UBUNTU" FAKE_MACHINE=aarch64 FAKE_DPKG_ARCH=arm64 -- --tarball
stopped "--tarball on arm64 stops"
has "there's none" "$OUT" 'no tarball for Linux on arm64'
newhome
run "$FEDORA" FAKE_MACHINE=riscv64 --
stopped "another processor stops"
has "and is named" "$OUT" 'riscv64'

echo "a Mac"
newhome
run "$MAC" FAKE_OS=Darwin FAKE_MACHINE=arm64 --
ran "Apple silicon"
log="$(cat "$LOG")"
has "gets its .dmg" "$log" "$PACKAGE-$T-macos-arm64.dmg"
has "checked with shasum" "$log" 'shasum'
has "attached without browsing" "$log" '-nobrowse'
has "and detached" "$log" 'hdiutil detach'
[ "$(cat "$A/version" 2>/dev/null)" = new ] && ok "the app's in Applications" || bad "no app" "$(ls -laR "$HOMEDIR/Apps")"
[ -z "$(ls -A "$HOMEDIR/tmp")" ] && ok "its temp folder is gone" || bad "left in temp: $(ls -A "$HOMEDIR/tmp")"
[ "$(ls -A "$HOMEDIR/Apps" | wc -l | tr -d ' ')" = 1 ] && ok "and nothing is left beside the app" || bad "left beside it" "$(ls -la "$HOMEDIR/Apps")"
has "it says where" "$OUT" "Installed $TITLE $T in $HOMEDIR/Apps"
has "how to open it" "$OUT" "open \"$A\""
has "what macOS shows, plainly" "$OUT" "doesn't add the download mark"
has "that nobody has tried it on a person's Mac" "$OUT" "nobody has tried this on a person's own Mac"
has "what's next" "$OUT" '`hypernova pair` on the server'
hasnt "quarantine and Gatekeeper's settings aren't touched" "$log" 'xattr'
hasnt "nor assessed" "$log" 'spctl'
hasnt "nor anything of root" "$log" 'sudo'
newhome
run "$MAC" FAKE_OS=Darwin FAKE_ARM64=0 FAKE_MACHINE=x86_64 --
has "an Intel Mac gets its own" "$(cat "$LOG")" "$PACKAGE-$T-macos-x64.dmg"
newhome
run "$MAC" FAKE_OS=Darwin FAKE_ARM64=1 FAKE_MACHINE=x86_64 --
has "Rosetta's x86_64 on Apple silicon still gets arm64" "$(cat "$LOG")" "$PACKAGE-$T-macos-arm64.dmg"
newhome
mkdir -p "$HOMEDIR/Apps/$TITLE.app"
echo old > "$HOMEDIR/Apps/$TITLE.app/version"
echo stale > "$HOMEDIR/Apps/$TITLE.app/stale"
run "$MAC" FAKE_OS=Darwin --
ran "again: it updates"
[ "$(cat "$A/version")" = new ] && [ ! -e "$A/stale" ] && ok "a whole new copy, none of the old" || bad "a mix" "$(ls -la "$A")"
newhome
mkdir -p "$HOMEDIR/Apps/$TITLE.app"
echo old > "$HOMEDIR/Apps/$TITLE.app/version"
run "$MAC" FAKE_OS=Darwin FAKE_RUNNING=1 --
stopped "the app open: it refuses"
has "and says to quit it" "$OUT" 'quit it'
one_line_error "in one line of its own"
hasnt "before downloading it" "$(cat "$LOG")" 'macos-arm64.dmg'
[ "$(cat "$HOMEDIR/Apps/$TITLE.app/version")" = old ] && ok "and the copy there stays" || bad "replaced while open"
newhome
chmod 555 "$HOMEDIR/Apps"
if [ -w "$HOMEDIR/Apps" ]; then
  ok "(this user can write anywhere: the unwritable case isn't tried)"
else
  run "$MAC" FAKE_OS=Darwin --
  ran "Applications not writable"
  [ "$(cat "$HOMEDIR/Applications/$TITLE.app/version")" = new ] && ok "it goes in ~/Applications" || bad "not in ~/Applications"
  has "and says so" "$OUT" "isn't writable by you"
fi
chmod 755 "$HOMEDIR/Apps"
newhome
mkdir -p "$HOMEDIR/Applications/$TITLE.app"
run "$MAC" FAKE_OS=Darwin --
has "a second copy elsewhere is mentioned" "$OUT" 'another copy'
newhome
run "$MAC" FAKE_OS=Darwin FAKE_NO_APP=1 --
stopped "an image with no app stops"
has "and it's detached" "$(cat "$LOG")" 'hdiutil detach'
[ ! -e "$A" ] && ok "with nothing in Applications" || bad "something in Applications"
[ -z "$(ls -A "$HOMEDIR/tmp")" ] && ok "and nothing in temp" || bad "left in temp: $(ls -A "$HOMEDIR/tmp")"
newhome
mkdir -p "$HOMEDIR/Apps/$TITLE.app"
echo old > "$HOMEDIR/Apps/$TITLE.app/version"
run "$MAC" FAKE_OS=Darwin FAKE_DITTO_FAIL=1 --
stopped "a copy that fails stops"
has "and it's detached" "$(cat "$LOG")" 'hdiutil detach'
[ "$(cat "$HOMEDIR/Apps/$TITLE.app/version")" = old ] && ok "with the older copy as it was" || bad "the older copy changed"
[ "$(ls -A "$HOMEDIR/Apps" | wc -l | tr -d ' ')" = 1 ] && ok "and nothing beside it" || bad "left beside it" "$(ls -la "$HOMEDIR/Apps")"
cp "$DIST/SHA256SUMS" "$WORK/sums.keep"
printf '%064d  %s\n' 0 "$PACKAGE-$T-macos-arm64.dmg" > "$DIST/SHA256SUMS"
newhome
run "$MAC" FAKE_OS=Darwin --
stopped "a wrong checksum stops"
hasnt "before the image is even attached" "$(cat "$LOG")" 'hdiutil'
cp "$WORK/sums.keep" "$DIST/SHA256SUMS"
newhome
run "$MAC" FAKE_OS=Darwin FAKE_UID=0 --
stopped "root is refused on a Mac"
has "as get.sh says it" "$OUT" 'not root'
hasnt "before anything" "$(cat "$LOG")" 'curl'
newhome
run "$MAC" FAKE_OS=Darwin -- --tarball
stopped "--tarball on a Mac"
has "is for Linux" "$OUT" '--tarball is for Linux'

echo "Windows and WSL"
MSI="$PACKAGE-$T-windows-x64.msi"
newhome
run "$SHA256" FAKE_OS=MINGW64_NT-10.0-26100 --
ran "Git Bash"
has "gets the .msi's address" "$OUT" "https://github.test/dl/$T/$MSI"
has "and says nothing was installed" "$OUT" 'nothing was installed'
has "and what's next" "$OUT" '`hypernova pair` on the server'
hasnt "and fetches no large file" "$(cat "$LOG")" "curl https://github.test/dl/$T/$MSI"
for os in MSYS_NT-10.0-26100 CYGWIN_NT-10.0-26100; do
  newhome
  run "$SHA256" FAKE_OS="$os" --
  has "$os too" "$OUT" "$MSI"
done
newhome
mv "$DIST/$MSI" "$WORK/msi.keep"
write_api "$DIST/latest" "$T"
run "$SHA256" FAKE_OS=MINGW64_NT-10.0-26100 --
stopped "with no .msi yet"
has "it says so" "$OUT" "$MSI isn't on $T"
mv "$WORK/msi.keep" "$DIST/$MSI"
write_api "$DIST/latest" "$T"
newhome
run "$UBUNTU" WSL_DISTRO_NAME=Ubuntu --
ran "WSL"
has "gets a plain note" "$OUT" 'This is WSL'
has "and where the Windows app is" "$OUT" '.msi'
has "and is otherwise Linux" "$(cat "$LOG")" 'apt install'

echo "the test-only folder"
newhome
FOLDER="$WORK/folder"
mkdir -p "$FOLDER"
cp "$DIST/$DEB_A" "$FOLDER/"
( cd "$FOLDER" && printf '%s  %s\n' "$(sum_of "$DEB_A")" "$DEB_A" > SHA256SUMS )
run "$UBUNTU" HYPERNOVA_CLIENT_DIR="$FOLDER" HYPERNOVA_TAG=$T --
ran "HYPERNOVA_CLIENT_DIR installs from it"
has "says it's for testing" "$OUT" 'For testing'
hasnt "and no curl" "$(cat "$LOG")" 'curl'
newhome
run "$UBUNTU" HYPERNOVA_CLIENT_DIR="$FOLDER" --
stopped "it wants the tag"
printf '%064d  %s\n' 0 "$DEB_A" > "$FOLDER/SHA256SUMS"
newhome
run "$UBUNTU" HYPERNOVA_CLIENT_DIR="$FOLDER" HYPERNOVA_TAG=$T --
stopped "the same checksum check holds"
hasnt "and apt never runs" "$(cat "$LOG")" 'apt install'
rm "$FOLDER/SHA256SUMS"
newhome
run "$UBUNTU" HYPERNOVA_CLIENT_DIR="$FOLDER" HYPERNOVA_TAG=$T --
stopped "as does its absence"

echo "piped, as curl | bash"
piped() { # <the script's bytes on standard input>
  OUT="$(env -i HOME="$HOMEDIR" TMPDIR="$HOMEDIR/tmp" PATH="$UBUNTU:$FAKES:$TOOLS" FAKE_LOG="$LOG" FAKE_DIST="$DIST" "$BASH_BIN" -s 2>&1)"
  RC=$?
}
newhome
piped <"$SCRIPT"
ran "the whole script, on standard input"
has "installs" "$(cat "$LOG")" 'apt install'
[ ! -s "$LOG.aptstdin" ] && ok "and nothing it runs is handed the script as input" || bad "apt read: $(cat "$LOG.aptstdin")"
# A connection dropped anywhere: nothing runs but what's harmless. Every 400 bytes, and each of the last lines' bytes.
size="$(wc -c <"$SCRIPT" | tr -d ' ')"
ran_anything=''
n=1
while [ "$n" -lt "$size" ]; do
  newhome
  head -c "$n" "$SCRIPT" | { piped; }
  if [ -s "$LOG" ]; then ran_anything="$ran_anything $n"; fi
  if [ "$n" -lt $((size - 16)) ]; then n=$((n + 400)); else n=$((n + 1)); fi
  rm -rf "$HOMEDIR"
done
[ -z "$ran_anything" ] && ok "cut off at any point, it does nothing" || bad "cut off at bytes$ran_anything it did something"
[ "$(tail -n 1 "$SCRIPT")" = 'main "$@"' ] && ok "the last line is what starts it" || bad "the last line: $(tail -n 1 "$SCRIPT")"

echo
echo "$pass passed, $fails failed."
[ "$fails" -eq 0 ]
