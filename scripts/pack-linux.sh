#!/usr/bin/env bash
# Packs Hypernova's daemon for a Linux server: dist/linux/hypernova-<version>.tar.gz, holding the
# daemon's sources and lockfile (no node_modules: the install builds them for the server), the font
# it draws the terminal with, and the install script. Then, on the server:
#   tar -xzf hypernova-<version>.tar.gz && hypernova-<version>/scripts/install-linux.sh
# or: scripts/install-linux.sh --from hypernova-<version>.tar.gz   (docs/LINUX.md)
# A release (.github/workflows/android-release.yml) attaches it as hypernova-server-<tag>-linux.tar.gz.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="$(sed -n 's/^export const VERSION = .\([0-9.]*\).*/\1/p' "$ROOT/daemon/src/util.ts")"
NAME="hypernova-$VERSION"
OUT="$ROOT/dist/linux"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/$NAME/daemon" "$STAGE/$NAME/Fonts" "$STAGE/$NAME/scripts" "$OUT"
cp -R "$ROOT/daemon/src" "$ROOT/daemon/package.json" "$ROOT/daemon/package-lock.json" "$STAGE/$NAME/daemon/"
# The built-in Tailscale helper for Linux servers (daemon/tsnet, Go; docs/PROTOCOL.md "Tailscale
# built in"): built here when Go is around, else whatever an earlier build left in daemon/bin.
if command -v go >/dev/null 2>&1 || [ -x /opt/homebrew/bin/go ]; then "$ROOT/daemon/tsnet/build.sh" >/dev/null; fi
if ls "$ROOT"/daemon/bin/hypernova-tsnet-linux-* >/dev/null 2>&1; then
  mkdir -p "$STAGE/$NAME/daemon/bin"
  cp "$ROOT"/daemon/bin/hypernova-tsnet-linux-* "$STAGE/$NAME/daemon/bin/"
else
  echo "pack-linux.sh: no daemon/bin/hypernova-tsnet-linux-*: this package has no built-in Tailscale (install Go and run daemon/tsnet/build.sh)" >&2
fi
cp "$ROOT/android/app/src/main/res/font/jetbrains_mono.ttf" "$STAGE/$NAME/Fonts/JetBrainsMono.ttf"
cp "$ROOT/third_party/fonts/OFL-JetBrainsMono.txt" "$STAGE/$NAME/Fonts/"
# The licence, the notice and the third-party notices travel with the package.
cp "$ROOT/LICENSE" "$ROOT/NOTICE" "$ROOT/THIRD-PARTY-NOTICES.md" "$STAGE/$NAME/"
cp "$ROOT/scripts/install-linux.sh" "$STAGE/$NAME/scripts/"
[ -f "$ROOT/docs/LINUX.md" ] && cp "$ROOT/docs/LINUX.md" "$STAGE/$NAME/README.md"
# No macOS metadata in a tarball meant for Linux: no ._ files (COPYFILE_DISABLE), and with macOS's
# tar no extended attributes either (com.apple.provenance), which GNU tar warns about on unpacking.
# Nor the packer's own user and group names: files belong to root, as in any release tarball.
TARFLAGS=(--owner=0 --group=0 --numeric-owner)
tar --version 2>/dev/null | grep -q bsdtar && TARFLAGS=(--no-xattrs --no-mac-metadata --uid 0 --gid 0 --uname root --gname root)
COPYFILE_DISABLE=1 tar ${TARFLAGS[@]+"${TARFLAGS[@]}"} -czf "$OUT/$NAME.tar.gz" -C "$STAGE" "$NAME"
echo "$OUT/$NAME.tar.gz"
