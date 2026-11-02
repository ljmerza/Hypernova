#!/usr/bin/env bash
# The Mac's shell scripts, without a Mac: install-macos.sh, server-setup.sh and get.sh, run under the
# bash a Mac ships (3.2) with stand-ins for uname, sysctl, shasum, xcode-select, curl and node, in a
# scratch home. It catches what bash 3.2 refuses and what the scripts decide; it doesn't prove anything
# about macOS itself, which only scripts/test-macos-smoke.sh does.
#
#   scripts/test-install-macos.sh            with the bash it's run by
#   scripts/test-install-macos.sh --docker   under bash 3.2, in Docker's bash:3.2 image
#
# Touches nothing but its scratch folder (a stand-in HOME), and reaches no network.
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ "${1:-}" = --docker ]; then
  exec docker run --rm --network none --user 1000:1000 -e HOME=/tmp -v "$ROOT/scripts:/scripts:ro" bash:3.2 bash /scripts/test-install-macos.sh
fi
SCRIPTS="$ROOT/scripts"
[ -d "$SCRIPTS" ] || SCRIPTS=/scripts
[ -f "$SCRIPTS/install-macos.sh" ] || SCRIPTS=/scripts
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
echo "bash ${BASH_VERSION}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
BIN="$WORK/bin"
mkdir -p "$BIN"
# The stand-ins' answers come from the environment, each run its own.
cat > "$BIN/uname" <<'EOF'
#!/bin/sh
case "$1" in -s) echo "${FAKE_OS:-Darwin}" ;; -m) echo "${FAKE_MACHINE:-arm64}" ;; *) echo Darwin ;; esac
EOF
cat > "$BIN/sysctl" <<'EOF'
#!/bin/sh
echo "${FAKE_ARM64:-1}"
EOF
cat > "$BIN/xcode-select" <<'EOF'
#!/bin/sh
[ "${FAKE_CLT:-1}" = 1 ] && { echo /Library/Developer/CommandLineTools; exit 0; }
echo "xcode-select: error: unable to get active developer directory" >&2; exit 2
EOF
cat > "$BIN/xattr" <<'EOF'
#!/bin/sh
echo "xattr $*" >> "$FAKE_LOG"
EOF
cat > "$BIN/sw_vers" <<'EOF'
#!/bin/sh
echo 12.7
EOF
# curl: files from the stand-in nodejs.org and GitHub, by the URL's last word.
cat > "$BIN/curl" <<'EOF'
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
f="$FAKE_DIST/${url##*/}"
[ -f "$f" ] || exit 22
if [ -n "$out" ]; then cp "$f" "$out"; else cat "$f"; fi
EOF
# shasum: the checksum the stand-in nodejs.org lists, unless this run changes it.
cat > "$BIN/shasum" <<'EOF'
#!/bin/sh
echo "${FAKE_SUM:-aaaa}  $3"
EOF
chmod +x "$BIN"/*

# A stand-in nodejs.org with one darwin build whose node says its version and logs what it was asked.
DIST="$WORK/dist"
mkdir -p "$DIST"
for a in arm64 x64; do
  n="node-v24.9.0-darwin-$a"
  mkdir -p "$WORK/n/$n/bin"
  cat > "$WORK/n/$n/bin/node" <<EOF
#!/bin/sh
case "\$1" in
  -e) exit \${FAKE_NODE_E:-0} ;;
  --version) echo v24.9.0; exit 0 ;;
esac
echo "node-$a \$*" >> "\$FAKE_LOG"
EOF
  chmod +x "$WORK/n/$n/bin/node"
  tar -czf "$DIST/$n.tar.gz" -C "$WORK/n" "$n"
done
printf 'aaaa  node-v24.9.0-darwin-arm64.tar.gz\naaaa  node-v24.9.0-darwin-x64.tar.gz\naaaa  node-v24.9.0-linux-x64.tar.gz\n' > "$DIST/SHASUMS256.txt"

# A package: the install script and a daemon's package.json, as pack-macos.sh lays them out.
PKG="$WORK/pkg/hypernova-1.2.3"
mkdir -p "$PKG/scripts" "$PKG/daemon/src/cli"
cp "$SCRIPTS/install-macos.sh" "$PKG/scripts/"
chmod +x "$PKG/scripts/install-macos.sh"
printf '{\n  "name": "hypernova-daemon",\n  "version": "1.2.3"\n}\n' > "$PKG/daemon/package.json"
: > "$PKG/daemon/src/cli/hypernova.ts"

# Where this bash is (not a machine's /usr/local/bin in general: a runner has a Node.js there).
BASH_DIR="$(dirname "$(command -v bash)")"
newhome() { HOMEDIR="$WORK/home-$RANDOM$RANDOM"; mkdir -p "$HOMEDIR"; LOG="$HOMEDIR/log"; : > "$LOG"; }
# <script> <args...>: under the stand-ins, with no node on PATH. Its words and its exit in OUT and RC.
run() {
  OUT="$(env HOME="$HOMEDIR" PATH="$BIN:/usr/bin:/bin:$BASH_DIR" FAKE_LOG="$LOG" FAKE_DIST="$DIST" HYPERNOVA_NODE_DIST=https://nodejs.test/dist "$@" 2>&1 </dev/null)"
  RC=$?
}

SUPPORT_REL='Library/Application Support/Hypernova'

echo "install-macos.sh"
newhome
run bash "$SCRIPTS/install-macos.sh" --from "$PKG"
[ "$RC" -eq 0 ] && ok "with no flags at all it runs: an empty array under bash 3.2's set -u" || bad "no flags: exit $RC" "$OUT"
has "Node.js comes from nodejs.org, for this processor" "$OUT" 'Downloading Node.js v24.9.0'
log="$(cat "$LOG")"
has "the arm64 tarball" "$log" 'node-v24.9.0-darwin-arm64.tar.gz'
has "setup runs from the package, with Hypernova's Node" "$log" "node-arm64 --disable-warning=ExperimentalWarning $PKG/daemon/src/cli/hypernova.ts setup --from $PKG/daemon"
[ -x "$HOMEDIR/$SUPPORT_REL/node/v24.9.0/bin/node" ] && ok "Node.js goes in Hypernova's own folder, spaces and all" || bad "no node in the support folder" "$(find "$HOMEDIR" | head -20)"
[ "$(cat "$HOMEDIR/$SUPPORT_REL/node-path")" = "$HOMEDIR/$SUPPORT_REL/node/v24.9.0/bin/node" ] && ok "and is recorded for the service" || bad "node-path wrong"
hasnt "no word of the Command Line Tools when they're there" "$OUT" 'xcode-select'
has "a browser's quarantine flag comes off the package" "$log" "xattr -dr com.apple.quarantine $PKG"

: > "$LOG"
run bash "$SCRIPTS/install-macos.sh" --from "$PKG" --yes --no-claude
has "flags go to setup" "$(cat "$LOG")" "setup --from $PKG/daemon --no-claude --yes"
hasnt "a second run keeps the Node.js it has" "$(cat "$LOG")" 'curl'

newhome
run env FAKE_ARM64=0 FAKE_MACHINE=x86_64 bash "$SCRIPTS/install-macos.sh" --from "$PKG"
has "an Intel Mac gets the x64 tarball" "$(cat "$LOG")" 'node-v24.9.0-darwin-x64.tar.gz'
newhome
run env FAKE_ARM64=1 FAKE_MACHINE=x86_64 bash "$SCRIPTS/install-macos.sh" --from "$PKG"
has "Rosetta's x86_64 on Apple silicon still gets arm64" "$(cat "$LOG")" 'node-v24.9.0-darwin-arm64.tar.gz'

newhome
run env FAKE_CLT=0 bash "$SCRIPTS/install-macos.sh" --from "$PKG"
[ "$RC" -eq 0 ] && ok "without the Command Line Tools the install goes on" || bad "no CLT: exit $RC" "$OUT"
# A runner's own Homebrew git counts as having git, and that's checked by its real path.
if [ -x /opt/homebrew/bin/git ] || [ -x /usr/local/bin/git ]; then ok "(this machine has another git: no word needed)"; else has "and says plainly what they're for" "$OUT" 'xcode-select --install'; fi

newhome
run env FAKE_SUM=bbbb bash "$SCRIPTS/install-macos.sh" --from "$PKG"
[ "$RC" -ne 0 ] && ok "a download that fails its checksum stops the install" || bad "checksum: exit 0" "$OUT"
has "and says so" "$OUT" "didn't match nodejs.org's checksum"
[ ! -e "$HOMEDIR/$SUPPORT_REL/node/v24.9.0" ] && ok "nothing of it stays" || bad "the bad download stayed"

newhome
run env FAKE_NODE_E=1 bash "$SCRIPTS/install-macos.sh" --from "$PKG"
[ "$RC" -ne 0 ] && ok "a Node.js that won't run stops the install" || bad "bad node: exit 0" "$OUT"
has "and names the macOS it needs" "$OUT" 'macOS 13.5 or later (this is 12.7)'

newhome
run env FAKE_OS=Linux bash "$SCRIPTS/install-macos.sh" --from "$PKG"
has "on Linux it points to the Linux script" "$OUT" 'install-linux.sh'

newhome
run bash "$SCRIPTS/install-macos.sh" --nonsense
[ "$RC" -eq 2 ] && ok "an unknown option is refused" || bad "unknown option: exit $RC" "$OUT"
run bash "$SCRIPTS/install-macos.sh" --help
has "--help says how" "$OUT" 'scripts/install-macos.sh --from <file.tar.gz>'

newhome
( cd "$WORK/pkg" && tar -czf "$WORK/server.tar.gz" hypernova-1.2.3 )
run bash "$SCRIPTS/install-macos.sh" --from "$WORK/server.tar.gz"
has "it takes a package as a file too" "$(cat "$LOG")" '/hypernova-1.2.3/daemon/src/cli/hypernova.ts setup --from'

echo "server-setup.sh"
# As the .pkg lays it out: bin/ beside lib/hypernova-server.
PREFIX="$WORK/usr-local"
mkdir -p "$PREFIX/bin" "$PREFIX/lib/hypernova-server/scripts" "$PREFIX/lib/hypernova-server/daemon"
cp "$SCRIPTS/server-setup.sh" "$PREFIX/bin/hypernova-server-setup"
printf '{\n  "version": "1.2.3"\n}\n' > "$PREFIX/lib/hypernova-server/daemon/package.json"
for s in install-macos.sh install-linux.sh; do
  printf '#!/bin/sh\necho "%s $*" >> "$FAKE_LOG"\n' "$s" > "$PREFIX/lib/hypernova-server/scripts/$s"
  chmod +x "$PREFIX/lib/hypernova-server/scripts/$s"
done
chmod +x "$PREFIX/bin/hypernova-server-setup"
newhome
run bash "$PREFIX/bin/hypernova-server-setup"
[ "$RC" -eq 0 ] && ok "no flags, and nothing installed yet: it runs" || bad "setup: exit $RC" "$OUT"
has "on a Mac, the Mac's script, from the folder beside it" "$(cat "$LOG")" "install-macos.sh --from $PREFIX/lib/hypernova-server"
: > "$LOG"
run bash "$PREFIX/bin/hypernova-server-setup" --yes --no-claude
has "flags go on" "$(cat "$LOG")" 'install-macos.sh --from '"$PREFIX"'/lib/hypernova-server --yes --no-claude'
: > "$LOG"
run env FAKE_OS=Linux bash "$PREFIX/bin/hypernova-server-setup"
has "on Linux, the Linux script" "$(cat "$LOG")" 'install-linux.sh --from'
mkdir -p "$HOMEDIR/$SUPPORT_REL/app/daemon"
printf '{\n  "version": "1.3.0"\n}\n' > "$HOMEDIR/$SUPPORT_REL/app/daemon/package.json"
: > "$LOG"
run bash "$PREFIX/bin/hypernova-server-setup"
[ "$RC" -eq 0 ] && has "a newer server of your own isn't gone back on" "$OUT" 'already on 1.3.0, newer than this package (1.2.3)' || bad "newer: exit $RC" "$OUT"
[ ! -s "$LOG" ] && ok "and the install doesn't run" || bad "the install ran" "$(cat "$LOG")"
run bash "$PREFIX/bin/hypernova-server-setup" --force
has "--force does" "$(cat "$LOG")" 'install-macos.sh --from'
printf '{\n  "version": "1.2.3"\n}\n' > "$HOMEDIR/$SUPPORT_REL/app/daemon/package.json"
: > "$LOG"
run bash "$PREFIX/bin/hypernova-server-setup"
has "the same version installs again" "$(cat "$LOG")" 'install-macos.sh --from'
printf '{\n  "version": "1.10.0"\n}\n' > "$HOMEDIR/$SUPPORT_REL/app/daemon/package.json"
: > "$LOG"
run bash "$PREFIX/bin/hypernova-server-setup"
has "1.10.0 is newer than 1.2.3, as numbers" "$OUT" 'already on 1.10.0'

echo "get.sh"
GH="$DIST/latest"
A=https://github.test/dl
cat > "$GH" <<EOF
{ "assets": [
  { "browser_download_url": "$A/hypernova-client-v1.2.3-android.apk" },
  { "browser_download_url": "$A/hypernova-server-v1.2.3-linux-amd64.deb" },
  { "browser_download_url": "$A/hypernova-server-v1.2.3-linux.tar.gz" },
  { "browser_download_url": "$A/hypernova-server-v1.2.3-macos-arm64.pkg" },
  { "browser_download_url": "$A/hypernova-server-v1.2.3-macos-arm64.tar.gz" },
  { "browser_download_url": "$A/hypernova-server-v1.2.3-macos-x64.tar.gz" }
] }
EOF
# Each package holds a script that says which it is, so the right one running is what's seen.
for name in macos-arm64 macos-x64 linux; do
  d="$WORK/get-$name/hypernova-1.2.3/scripts"
  mkdir -p "$d"
  s=install-macos.sh; [ "$name" = linux ] && s=install-linux.sh
  printf '#!/bin/sh\necho "%s %s args:$*" >> "$FAKE_LOG"\n' "$name" "$s" > "$d/$s"
  chmod +x "$d/$s"
  tar -czf "$DIST/hypernova-server-v1.2.3-$name.tar.gz" -C "$WORK/get-$name" hypernova-1.2.3
done
newhome
run bash "$SCRIPTS/get.sh"
[ "$RC" -eq 0 ] && ok "get.sh runs with no options" || bad "get.sh: exit $RC" "$OUT"
has "a Mac on Apple silicon gets its package and script" "$(cat "$LOG")" 'macos-arm64 install-macos.sh args:'
has "it asks for a new terminal" "$OUT" 'Open a new terminal'
has "and calls the app the client, by its name" "$OUT" 'hypernova-client-<tag>-android.apk'
newhome
run env FAKE_ARM64=0 bash "$SCRIPTS/get.sh" --yes
has "an Intel Mac gets its own, and the options go on" "$(cat "$LOG")" 'macos-x64 install-macos.sh args:--yes'
newhome
run env FAKE_OS=Linux bash "$SCRIPTS/get.sh"
has "Linux gets the tarball its name has always been, not a .deb" "$(cat "$LOG")" 'linux install-linux.sh args:'
hasnt "and no word of a new terminal" "$OUT" 'Open a new terminal'
newhome
run env FAKE_OS=FreeBSD bash "$SCRIPTS/get.sh"
has "another system is told what's supported" "$OUT" 'runs on Linux and on a Mac'
newhome
mv "$GH" "$GH.keep"
grep -v 'macos-x64' "$GH.keep" > "$GH"
run env FAKE_ARM64=0 bash "$SCRIPTS/get.sh"
[ "$RC" -ne 0 ] && has "a release without this Mac's package says so" "$OUT" 'no server package for a Mac (x64)' || bad "no package: exit 0" "$OUT"
mv "$GH.keep" "$GH"

echo
echo "$pass passed, $fails failed."
[ "$fails" -eq 0 ]
