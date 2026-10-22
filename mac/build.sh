#!/bin/bash
# Builds dist/Hypernova.app with SwiftPM and Command Line Tools only (no Xcode).
#
#   mac/build.sh            release build → dist/Hypernova.app (ad-hoc signed)
#   mac/build.sh --open     … and launch it
#   mac/build.sh --release  a build to hand out (or HYPERNOVA_RELEASE=1): never asks anything, and
#                           stops instead of warning: no debug-signed APK, no missing APK, Tailscale
#                           helper or node_modules, no builder's path left in the binary. The build
#                           number defaults to the commit's time (SOURCE_DATE_EPOCH wins), so the
#                           same commit gives the same Info.plist. Then mac/dmg.sh makes the .dmg.
#   HYPERNOVA_DIST=<dir>    build there instead of ../dist (a scratch copy)
#
# The bundle carries the fonts, an icon rendered from the Nova mark, and the daemon (../daemon
# without .git, tests and development packages, plus the Android app from ../dist for "Get the
# app": the newest release-signed APK when there is one, else the newest debug one, with a warning,
# since a phone that installs a debug build can't update to a release one without uninstalling and
# pairing again). It never carries Claude Code's Agent SDK: Setup fetches that from npm.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
DIST="${HYPERNOVA_DIST:-$ROOT/dist}"
APP="$DIST/Hypernova.app"
CONTENTS="$APP/Contents"
RES="$CONTENTS/Resources"
WORK="$(mktemp -d -t hypernova-build)"
trap 'rm -rf "$WORK"' EXIT

OPEN=""
for a in "$@"; do
  case "$a" in
    --open) OPEN=1 ;;
    --release) HYPERNOVA_RELEASE=1 ;;
    *) echo "usage: mac/build.sh [--open] [--release]" >&2; exit 64 ;;
  esac
done
RELEASE="${HYPERNOVA_RELEASE:-}"

# Colours only for a terminal: a release log stays plain text.
if [ -t 1 ]; then
  say() { printf '\033[38;2;127;255;160m▸\033[0m %s\n' "$*"; }
  warn() { printf '\033[38;2;232;179;65m!\033[0m %s\n' "$*"; }
else
  say() { printf '> %s\n' "$*"; }
  warn() { printf '! %s\n' "$*"; }
fi
# A warning, or for a release the reason it stops.
need() { if [ -n "$RELEASE" ]; then echo "release build stopped: $*" >&2; exit 1; else warn "$*"; fi; }

DAEMON_VERSION="$(sed -n 's/^ *"version": *"\([^"]*\)".*/\1/p' "$ROOT/daemon/package.json" 2>/dev/null | head -1 || true)"
VERSION="${HYPERNOVA_VERSION:-${DAEMON_VERSION:-0.1.0}}"
if [ -n "$RELEASE" ]; then
  # The commit's time (or SOURCE_DATE_EPOCH), in UTC: one commit, one build number.
  EPOCH="${SOURCE_DATE_EPOCH:-$(git -C "$ROOT" log -1 --format=%ct 2>/dev/null || date +%s)}"
  BUILD="${HYPERNOVA_BUILD:-$(TZ=UTC date -r "$EPOCH" +%Y%m%d%H%M)}"
else
  BUILD="${HYPERNOVA_BUILD:-$(date +%Y%m%d%H%M)}"
fi

say "Compiling (release, arm64)"
# Source paths written into the binary (#file in a fatalError) start at Sources, not at the
# builder's home. (Only the sources: mapping the whole repo would also move .build's module cache.)
SWIFT=(swift build -c release --arch arm64 --package-path "$HERE" -Xswiftc -file-prefix-map -Xswiftc "$HERE/Sources=Sources")
# The log is kept whole, so a failed compile stops here (never bundles the last good binary).
if ! "${SWIFT[@]}" >"$WORK/swift-build.log" 2>&1; then
  cat "$WORK/swift-build.log" >&2
  echo "build failed: swift build exited with an error" >&2
  exit 1
fi
grep -vE '^\[[0-9]+/[0-9]+\] (Compiling|Emitting|Write|Planning)' "$WORK/swift-build.log" || true
BIN_DIR="$("${SWIFT[@]}" --show-bin-path)"
[ -x "$BIN_DIR/Hypernova" ] || { echo "build failed: no binary at $BIN_DIR/Hypernova" >&2; exit 1; }

say "Assembling $APP"
# Quit a running copy from dist first, so the bundle can be replaced cleanly.
pkill -f "$APP/Contents/MacOS/Hypernova" 2>/dev/null || true
rm -rf "$APP"
mkdir -p "$CONTENTS/MacOS" "$RES/Fonts"
cp "$BIN_DIR/Hypernova" "$CONTENTS/MacOS/Hypernova"
# Debug symbols carry every source and object file's full path (the builder's home and name):
# out, with the local symbols. A crash report still names the app's own functions by address.
strip -S -x "$CONTENTS/MacOS/Hypernova"
if LC_ALL=C grep -a -q -F "$HOME" "$CONTENTS/MacOS/Hypernova"; then
  need "the binary still holds $HOME (a path from this Mac)"
fi

# Fonts (variable TTFs, OFL) and their licences.
cp "$ROOT/android/app/src/main/res/font/manrope.ttf" "$RES/Fonts/Manrope.ttf"
cp "$ROOT/android/app/src/main/res/font/jetbrains_mono.ttf" "$RES/Fonts/JetBrainsMono.ttf"
cp "$ROOT"/third_party/fonts/OFL-*.txt "$RES/Fonts/" 2>/dev/null || true
# The licence, the notice and the third-party notices travel with the app.
cp "$ROOT/LICENSE" "$ROOT/NOTICE" "$ROOT/THIRD-PARTY-NOTICES.md" "$RES/"

# App icon, drawn by the app itself from the Nova mark.
say "Rendering the app icon"
"$CONTENTS/MacOS/Hypernova" --render-iconset "$WORK/AppIcon.iconset"
iconutil -c icns "$WORK/AppIcon.iconset" -o "$RES/AppIcon.icns"

# The daemon: plain TypeScript run directly by Node, plus node_modules.
if [ -f "$ROOT/daemon/src/main.ts" ]; then
  say "Bundling the daemon ($DAEMON_VERSION)"
  # Shared terminals need node-pty's macOS build; without node_modules the service can't start.
  [ -d "$ROOT/daemon/node_modules/node-pty/prebuilds/darwin-arm64" ] || need "no daemon/node_modules (run npm ci in daemon/)"
  # A copy to trim first: the production packages only (npm prune works offline, from the lock).
  rsync -a --copy-unsafe-links --exclude '/.git' --exclude '/bin' --exclude '/tsnet' --exclude '/test' --exclude '/tests' --exclude '.DS_Store' \
    "$ROOT/daemon/" "$WORK/daemon/"
  if command -v npm >/dev/null 2>&1; then
    (cd "$WORK/daemon" && npm_config_update_notifier=false npm prune --omit=dev --ignore-scripts --no-audit --no-fund >/dev/null) || need "npm prune --omit=dev failed in the daemon's copy"
    # The scope folders the pruned packages leave behind (@types, @typescript), empty.
    find "$WORK/daemon/node_modules" -type d -empty -delete 2>/dev/null || true
  else
    need "no npm here to leave the daemon's development packages (TypeScript, types) out of the bundle"
  fi
  # Left out as well: node-pty's Windows and Intel builds and its C++ sources (this app is arm64
  # only), the files only a developer reads, and Claude Code's Agent SDK. Its JavaScript package
  # (@anthropic-ai/claude-agent-sdk) is Anthropic's ("All rights reserved") and not ours to hand
  # out, so Setup fetches the version the lockfile pins from npm, on the user's Mac, into the
  # installed daemon (Core/ClaudeSDK.swift; the daemon runs without it until then, daemon/src/sdk.ts).
  # Its own Claude Code binaries (claude-agent-sdk-<platform>, about 230 MB) are never needed: the
  # daemon always runs the claude installed on the Mac (pathToClaudeCodeExecutable). The lockfile
  # ships, so that fetch takes exactly the pinned package and checks it against the lock's
  # integrity; npm's record of the tree it built (node_modules/.package-lock.json, which lists the
  # SDK) doesn't.
  rsync -a --delete --exclude '/tsconfig.json' --exclude '/.gitignore' --exclude '/node_modules/.package-lock.json' \
    --exclude 'node-pty/prebuilds/win32-*' --exclude 'node-pty/prebuilds/darwin-x64' --exclude 'node-pty/deps' --exclude 'node-pty/third_party' --exclude 'node-pty/src' \
    --exclude '/node_modules/@anthropic-ai/claude-agent-sdk/' --exclude '@anthropic-ai/claude-agent-sdk-*' \
    "$WORK/daemon/" "$RES/daemon/"
  # Nothing of the SDK may be in the app, whatever the build.
  SDK_LEAK="$(find "$RES/daemon" -path '*claude-agent-sdk*' -print -quit)"
  if [ -n "$SDK_LEAK" ]; then echo "build stopped: Claude Code's Agent SDK is in the bundle ($SDK_LEAK); it must stay out (Setup fetches it)." >&2; exit 1; fi
  # The SDK version Setup fetches, from the lockfile, as one line (ClaudeSDK.swift and sdk.ts read it).
  SDK_VERSION="$(awk -v k='"node_modules/@anthropic-ai/claude-agent-sdk":' 'index($0, k) { f = 1; next } f && /"version"/ { sub(/.*"version": *"/, ""); sub(/".*/, ""); print; exit }' "$ROOT/daemon/package-lock.json" 2>/dev/null || true)"
  if [ -n "$SDK_VERSION" ]; then
    echo "$SDK_VERSION" > "$RES/daemon/sdk-version"
    say "Left out Claude Code's SDK $SDK_VERSION: Setup fetches it from npm"
  else
    need "no @anthropic-ai/claude-agent-sdk in daemon/package-lock.json: Setup won't know which SDK to fetch"
  fi
  # Shared terminals start claude through node-pty's helper; npm doesn't always keep it executable.
  chmod +x "$RES/daemon/node_modules/node-pty/prebuilds/"darwin-*/spawn-helper 2>/dev/null || true
  # A release-signed APK first; a debug one only when there's none (and never for a release).
  APK="$(ls -t "$ROOT/dist"/*.apk 2>/dev/null | grep -v -- '-debug' | head -1 || true)"
  if [ -z "$APK" ]; then
    APK="$(ls -t "$ROOT/dist"/*.apk 2>/dev/null | head -1 || true)"
    if [ -n "$APK" ]; then
      if [ -n "$RELEASE" ]; then
        echo "release build stopped: dist/ has only a debug-signed APK ($(basename "$APK")): build the release APK first (cd android && ./gradlew :app:distRelease; docs/RELEASING.md)." >&2
        exit 1
      fi
      warn "Bundling a debug-signed APK ($(basename "$APK")): phones that install it can't update to a release build without uninstalling."
    fi
  fi
  if [ -n "$APK" ]; then
    mkdir -p "$RES/daemon/android"
    cp "$APK" "$RES/daemon/android/hypernova.apk"
    # Its version note (from `gradlew :app:dist`): the phone compares it with its own.
    if [ -f "${APK%.apk}.json" ]; then cp "${APK%.apk}.json" "$RES/daemon/android/hypernova.json"; fi
    say "Bundled $(basename "$APK") as daemon/android/hypernova.apk"
  else
    need "no APK in dist/: the \"Get the app\" download will 404 until one is bundled."
  fi
  # Tailscale built in (daemon/tsnet): the helper for this Mac, built here when Go is around, else the
  # one a previous build left in daemon/bin. Without it the daemon falls back to the Tailscale app.
  if command -v go >/dev/null 2>&1 || [ -x /opt/homebrew/bin/go ]; then
    say "Building the Tailscale helper"
    "$ROOT/daemon/tsnet/build.sh" --host >/dev/null
  fi
  if [ -x "$ROOT/daemon/bin/hypernova-tsnet-darwin-arm64" ]; then
    mkdir -p "$RES/daemon/bin"
    cp "$ROOT/daemon/bin/hypernova-tsnet-darwin-arm64" "$RES/daemon/bin/"
    say "Bundled hypernova-tsnet-darwin-arm64 ($(du -h "$ROOT/daemon/bin/hypernova-tsnet-darwin-arm64" | cut -f1))"
  else
    need "no Tailscale helper in daemon/bin: built-in Tailscale will be off (install Go and run daemon/tsnet/build.sh)."
  fi
  # A stamp of the daemon's sources, APK and package lock: the app offers an update when it differs from the installed copy.
  STAMP="$( (cd "$RES/daemon" && find src bin package.json android -type f 2>/dev/null | LC_ALL=C sort | xargs shasum -a 256 2>/dev/null; shasum -a 256 < "$ROOT/daemon/package-lock.json" 2>/dev/null) | shasum -a 256 | cut -c1-16)"
  echo "$VERSION-$BUILD $STAMP" > "$RES/daemon/.hypernova-build"
else
  need "no daemon at $ROOT/daemon: this build can't install the background service."
fi

cat > "$CONTENTS/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key><string>com.prudnikweb.hypernova</string>
  <key>CFBundleName</key><string>Hypernova</string>
  <key>CFBundleDisplayName</key><string>Hypernova</string>
  <key>CFBundleExecutable</key><string>Hypernova</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
  <key>CFBundleShortVersionString</key><string>$VERSION</string>
  <key>CFBundleVersion</key><string>$BUILD</string>
  <key>CFBundleDevelopmentRegion</key><string>en</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSUIElement</key><true/>
  <key>LSApplicationCategoryType</key><string>public.app-category.developer-tools</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSPrincipalClass</key><string>NSApplication</string>
  <key>NSHumanReadableCopyright</key><string>© 2026 Nikita Prudnikov. A control room for Claude Code and Codex.</string>
  <key>NSAppTransportSecurity</key>
  <dict><key>NSAllowsLocalNetworking</key><true/></dict>
</dict>
</plist>
PLIST
plutil -lint "$CONTENTS/Info.plist" >/dev/null

say "Signing (ad hoc)"
# No extended attributes (a Finder tag or a quarantine flag would break the seal), no timestamp: an
# ad-hoc signature is then a pure function of the files, so the same files sign the same way.
xattr -cr "$APP"
find "$APP" -name .DS_Store -delete
codesign --force --deep --timestamp=none -s - "$APP" 2>&1 | grep -v "replacing existing signature" || true
codesign --verify --deep --strict "$APP"

SIZE="$(du -sh "$APP" | cut -f1)"
say "Done: $APP ($SIZE, version $VERSION build $BUILD)"

if [ -n "$OPEN" ]; then open "$APP"; fi
