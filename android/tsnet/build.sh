#!/bin/bash
# Builds the embedded Tailscale node (android/tsnet, Go) into android/app/libs/tsnet.aar with
# gomobile: arm64 for phones and x86_64 for the emulator (a release build splits by ABI anyway).
# Needs Go, gomobile (go install golang.org/x/mobile/cmd/gomobile@latest) and the Android NDK
# from the SDK. Gradle calls this when the AAR is missing (app/build.gradle.kts, buildTsnet).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../app/libs"
export PATH="/opt/homebrew/bin:/usr/local/go/bin:$HOME/go/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# gomobile needs javac: Android Studio's JDK when no other is set.
if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
  export ANDROID_NDK_HOME
fi
command -v go >/dev/null || { echo "build.sh: go is not installed (brew install go)" >&2; exit 1; }
command -v gomobile >/dev/null || { echo "build.sh: gomobile is missing (go install golang.org/x/mobile/cmd/gomobile@latest)" >&2; exit 1; }
[ -d "$ANDROID_NDK_HOME" ] || { echo "build.sh: no Android NDK at $ANDROID_NDK_HOME (install one in Android Studio's SDK Manager)" >&2; exit 1; }
mkdir -p "$OUT"
TARGETS="${TSNET_TARGETS:-android/arm64,android/amd64}"
echo "▸ tsnet.aar ($TARGETS)"
# Built from a copy in a neutral folder: gomobile's generated go.mod replaces this module with its
# folder's absolute path, which the library's Go build info keeps even with -trimpath, so a build in
# the checkout would put the builder's home folder (and user name) into every APK. The copy's path
# is a random /tmp one instead.
WORK="$(mktemp -d /tmp/hypernova-tsnet.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
cp "$HERE"/*.go "$HERE"/go.mod "$HERE"/go.sum "$WORK/"
cd "$WORK"
# The Java package is the node's own (com.prudnikweb.hypernova.go.tsnet): gomobile's AAR keeps that
# package from R8, and it must not be the app's. golang.org/x/mobile/bind is pinned in go.mod.
gomobile bind -target "$TARGETS" -androidapi 29 -javapkg com.prudnikweb.hypernova.go -ldflags="-s -w" -trimpath -o "$OUT/tsnet.aar" .
rm -f "$OUT/tsnet-sources.jar"
ls -la "$OUT/tsnet.aar"
