#!/bin/bash
# Packs dist/Hypernova.app into dist/Hypernova.dmg with Command Line Tools only (hdiutil, no Finder
# or AppleScript): the app, a link to /Applications to drag it onto, and "Read me first.txt" with the
# first-launch steps for an app Apple hasn't notarized. The volume is named "Hypernova" and wears
# the app's icon. No background picture: that needs a Finder-written .DS_Store, which isn't
# deterministic.
#
#   mac/dmg.sh                       dist/Hypernova.app → dist/Hypernova.dmg
#   mac/dmg.sh <app> <out.dmg>       any app bundle, anywhere (a scratch copy)
#
# Compressed (UDZO), file dates set to the commit's time (SOURCE_DATE_EPOCH wins), checked after
# (attached read-only, the app's seal verified), then its SHA-256 printed in SHA256SUMS form.
# Build the app first: mac/build.sh --release.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
APP="${1:-$ROOT/dist/Hypernova.app}"
OUT="${2:-$ROOT/dist/Hypernova.dmg}"
VOLUME="Hypernova"

if [ -t 1 ]; then
  say() { printf '\033[38;2;127;255;160m▸\033[0m %s\n' "$*"; }
else
  say() { printf '> %s\n' "$*"; }
fi
fail() { echo "dmg: $*" >&2; exit 1; }

[ -d "$APP/Contents/MacOS" ] || fail "no app at $APP (build it with mac/build.sh --release)"
codesign --verify --deep --strict "$APP" 2>/dev/null || fail "$APP isn't sealed: build it again with mac/build.sh"
VERSION="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$APP/Contents/Info.plist" 2>/dev/null || echo "")"
EPOCH="${SOURCE_DATE_EPOCH:-$(git -C "$ROOT" log -1 --format=%ct 2>/dev/null || date +%s)}"
STAMP="$(TZ=UTC date -r "$EPOCH" +%Y%m%d%H%M.%S)"

WORK="$(mktemp -d -t hypernova-dmg)"
MOUNT="$WORK/mnt"
cleanup() {
  if [ -d "$MOUNT" ] && mount | grep -qF " on $MOUNT "; then hdiutil detach -quiet "$MOUNT" -force || true; fi
  rm -rf "$WORK"
}
trap cleanup EXIT

say "Staging $(basename "$APP") ${VERSION:+$VERSION }"
STAGE="$WORK/$VOLUME"
mkdir -p "$STAGE"
# ditto keeps the bundle exactly as signed; no extended attributes come along.
ditto --noextattr --noqtn "$APP" "$STAGE/Hypernova.app"
ln -s /Applications "$STAGE/Applications"
# The volume's icon: the app's own.
cp "$APP/Contents/Resources/AppIcon.icns" "$STAGE/.VolumeIcon.icns"
cat > "$STAGE/Read me first.txt" <<README
Hypernova ${VERSION} for macOS 14 or later, on a Mac with Apple silicon.

1. Drag Hypernova onto Applications.

2. Open Hypernova from Applications. The first time, macOS stops it:
   Hypernova is open source and signed on the machine that built it, but
   not notarized by Apple, so macOS can't vouch for it.

   macOS 15 Sequoia and macOS 26 Tahoe:
     It says Apple could not verify "Hypernova" is free of malware.
     Click Done. Open System Settings > Privacy & Security, scroll down
     to Security, and click Open Anyway next to "Hypernova" was blocked.
     macOS asks for your password or Touch ID, and once more whether to
     open Hypernova: click Open Anyway.

   macOS 14 Sonoma:
     It says the developer cannot be verified. Click Cancel. In
     Applications, Control-click Hypernova, choose Open, then Open.
     (Privacy & Security > Open Anyway works too.)

   Or, in Terminal, on any of them:
     xattr -dr com.apple.quarantine /Applications/Hypernova.app

   macOS asks once. After that Hypernova opens like any app.

3. Hypernova appears in the menu bar and Setup opens. It walks you
   through Claude Code or Codex, the background service, Tailscale
   and pairing your phone.

The install guide: https://hypernova.nikbraun.tech
README
# One date for everything, so two packings of the same build differ only where hdiutil differs.
find "$STAGE" -exec touch -h -t "$STAMP" {} +

say "Making the image"
# Read-write first: the volume's custom-icon flag can only be set on a mounted volume.
hdiutil create -quiet -volname "$VOLUME" -fs HFS+ -format UDRW -srcfolder "$STAGE" "$WORK/rw.dmg"
mkdir -p "$MOUNT"
hdiutil attach -quiet -readwrite -noverify -noautoopen -nobrowse -mountpoint "$MOUNT" "$WORK/rw.dmg"
# Finder info on the volume's root: kHasCustomIcon (0x0400), so Finder shows .VolumeIcon.icns.
xattr -wx com.apple.FinderInfo "0000000000000000040000000000000000000000000000000000000000000000" "$MOUNT"
touch -h -t "$STAMP" "$MOUNT"
hdiutil detach -quiet "$MOUNT"

say "Compressing"
mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
hdiutil convert -quiet "$WORK/rw.dmg" -format UDZO -imagekey zlib-level=9 -o "$OUT"

say "Checking"
hdiutil verify -quiet "$OUT"
hdiutil attach -quiet -readonly -noverify -noautoopen -nobrowse -mountpoint "$MOUNT" "$OUT"
codesign --verify --deep --strict "$MOUNT/Hypernova.app" || fail "the app on the image doesn't verify"
[ -L "$MOUNT/Applications" ] || fail "no Applications link on the image"
hdiutil detach -quiet "$MOUNT"

SIZE="$(du -h "$OUT" | cut -f1)"
SUM="$(shasum -a 256 "$OUT" | cut -d' ' -f1)"
say "Done: $OUT ($SIZE)"
echo "$SUM  $(basename "$OUT")"
