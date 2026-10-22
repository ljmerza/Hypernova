#!/bin/bash
# Builds every file of a GitHub release from this checkout into dist/release/, under the names the
# download links use (https://github.com/prudnik-web/Hypernova/releases/latest/download/<name>):
#
#   Hypernova.dmg             the Mac app (mac/build.sh --release, then mac/dmg.sh)
#   Hypernova.apk             the Android app, release-signed (./gradlew :app:distRelease)
#   Hypernova-linux.tar.gz    the Linux server package (scripts/pack-linux.sh)
#   Hypernova-Lens.ehpk       the glasses app for the Even Hub (npm run pack in lens/)
#   SHA256SUMS.txt            their checksums, for `shasum -a 256 -c`
#   latest.json               version, date, sizes and checksums, for the project site
#
# Then it looks inside every file for what must never ship: the builder's home folder and user name.
# Needs the release signing key (docs/RELEASING.md), Android Studio's JDK, Go (for the Tailscale
# helpers) and npm. Nothing is uploaded: docs/RELEASING.md says how to publish.
#
#   scripts/release.sh                build from a clean checkout
#   scripts/release.sh --allow-dirty  build with uncommitted changes too (a rehearsal)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$ROOT/dist"
OUT="$DIST/release"
ALLOW_DIRTY=""
case "${1:-}" in
  "") ;;
  --allow-dirty) ALLOW_DIRTY=1 ;;
  *) echo "usage: scripts/release.sh [--allow-dirty]" >&2; exit 64 ;;
esac

say() { printf '\033[38;2;127;255;160m▸\033[0m %s\n' "$*"; }
fail() { echo "release: $*" >&2; exit 1; }

# One version everywhere.
V_DAEMON="$(sed -n "s/^export const VERSION = '\([0-9.]*\)'.*/\1/p" "$ROOT/daemon/src/util.ts")"
V_PACKAGE="$(sed -n 's/^ *"version": *"\([^"]*\)".*/\1/p' "$ROOT/daemon/package.json" | head -1)"
V_ANDROID="$(sed -n 's/^val appVersion = "\([0-9.]*\)".*/\1/p' "$ROOT/android/app/build.gradle.kts")"
[ -n "$V_DAEMON" ] || fail "no VERSION in daemon/src/util.ts"
[ "$V_DAEMON" = "$V_PACKAGE" ] && [ "$V_DAEMON" = "$V_ANDROID" ] \
  || fail "the versions disagree: daemon/src/util.ts $V_DAEMON, daemon/package.json $V_PACKAGE, android appVersion $V_ANDROID"
VERSION="$V_DAEMON"

[ -z "$(git -C "$ROOT" status --porcelain --untracked-files=no)" ] || [ -n "$ALLOW_DIRTY" ] \
  || fail "uncommitted changes: commit them, or rehearse with --allow-dirty"
[ -z "${HYPERNOVA_TSNET_AUTHKEY:-}" ] || fail "HYPERNOVA_TSNET_AUTHKEY is set: an APK built now would carry a Tailscale pre-auth key"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

say "Hypernova $VERSION"
rm -rf "$OUT"
mkdir -p "$OUT"

say "Android: release-signed APK"
rm -f "$DIST/hypernova.apk" "$DIST/hypernova.json"
(cd "$ROOT/android" && ./gradlew :app:distRelease)
[ -f "$DIST/hypernova.apk" ] || fail "distRelease left no dist/hypernova.apk"

say "Mac: the app, then its disk image"
"$ROOT/mac/build.sh" --release
"$ROOT/mac/dmg.sh" "$DIST/Hypernova.app" "$DIST/Hypernova.dmg"

say "Linux: the server package"
"$ROOT/scripts/pack-linux.sh" >/dev/null
[ -f "$DIST/linux/hypernova-$VERSION.tar.gz" ] || fail "pack-linux left no dist/linux/hypernova-$VERSION.tar.gz"

say "Lens: the Even Hub package"
(cd "$ROOT/lens" && npm ci --no-audit --no-fund >/dev/null && npm run pack >/dev/null)
[ -f "$ROOT/lens/out.ehpk" ] || fail "npm run pack left no lens/out.ehpk"

cp "$DIST/Hypernova.dmg" "$OUT/Hypernova.dmg"
cp "$DIST/hypernova.apk" "$OUT/Hypernova.apk"
cp "$DIST/linux/hypernova-$VERSION.tar.gz" "$OUT/Hypernova-linux.tar.gz"
cp "$ROOT/lens/out.ehpk" "$OUT/Hypernova-Lens.ehpk"

say "Looking inside for the builder's name and folders"
LEAKS="$(cd "$(mktemp -d -t hypernova-leaks)" && pwd -P)"     # the real path, as `mount` prints it
cleanup() {
  if mount | grep -qF " on $LEAKS/dmg "; then hdiutil detach -quiet "$LEAKS/dmg" -force || true; fi
  rm -rf "$LEAKS"
}
trap cleanup EXIT
mkdir "$LEAKS/dmg" "$LEAKS/tgz"
hdiutil attach -quiet -nobrowse -readonly -mountpoint "$LEAKS/dmg" "$OUT/Hypernova.dmg" >/dev/null
tar -xzf "$OUT/Hypernova-linux.tar.gz" -C "$LEAKS/tgz"
found=0
for needle in "$HOME" "$USER"; do
  [ -n "$needle" ] && [ "$needle" != "/" ] || continue
  hits="$(LC_ALL=C grep -a -r -l -F -- "$needle" "$LEAKS/dmg" "$LEAKS/tgz" "$ROOT/lens/dist" 2>/dev/null | head -5 | sed "s#$LEAKS/##" || true)"
  # An APK is a zip of compressed files, and two of its resources differ only in letter case, so a
  # macOS unzip would lose one: read every entry in memory instead.
  apk_hits="$(python3 - "$OUT/Hypernova.apk" "$needle" <<'PY'
import sys, zipfile
apk, needle = sys.argv[1], sys.argv[2].encode()
with zipfile.ZipFile(apk) as z:
    for i in z.infolist():
        if needle in z.read(i):
            print("Hypernova.apk:" + i.filename)
PY
)"
  if [ -n "$hits$apk_hits" ]; then
    echo "release: \"$needle\" is inside the build:" >&2
    printf '%s\n' "$hits" "$apk_hits" | grep . | head -8 >&2
    found=1
  fi
done
[ "$found" = 0 ] || fail "see above: fix the build, don't publish this"

say "Checksums"
(cd "$OUT" && shasum -a 256 Hypernova.dmg Hypernova.apk Hypernova-linux.tar.gz Hypernova-Lens.ehpk > SHA256SUMS.txt)
node -e '
const fs = require("fs"), path = require("path");
const [out, version] = process.argv.slice(1);
const sums = Object.fromEntries(fs.readFileSync(path.join(out, "SHA256SUMS.txt"), "utf8").trim().split("\n").map(l => { const [h, n] = l.split(/\s+/); return [n, h]; }));
const files = {};
for (const n of [...Object.keys(sums), "SHA256SUMS.txt"]) {
  const p = path.join(out, n);
  files[n] = { size: fs.statSync(p).size, ...(sums[n] ? { sha256: sums[n] } : {}) };
}
const note = { version, date: new Date().toISOString().slice(0, 10), files };
fs.writeFileSync(path.join(out, "latest.json"), JSON.stringify(note, null, 2) + "\n");
' "$OUT" "$VERSION"
# The Mac's own check, from the folder a visitor would save the files in.
(cd "$OUT" && shasum -a 256 -c SHA256SUMS.txt)

say "dist/release:"
ls -la "$OUT"
