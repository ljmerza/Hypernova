#!/usr/bin/env bash
# Packs Hypernova's server for a Mac. Its path is the last line printed. A release attaches it as
# hypernova-server-<tag>-macos-<arch>.tar.gz or .pkg (.github/workflows/server-packages.yml).
#
#   scripts/pack-macos.sh <arm64|x64>         dist/macos/hypernova-server-<version>-macos-<arch>.tar.gz
#   scripts/pack-macos.sh <arm64|x64> --pkg   dist/macos/hypernova-server-<version>-macos-<arch>.pkg (a Mac only)
#
# The tarball is the Linux one (scripts/pack-linux.sh) with install-macos.sh for install-linux.sh and
# the install guide as its README. No Node and no node_modules: the install fetches both for the Mac.
# The two processors' tarballs differ in name only; each is named for the runner that tested it.
#
# The .pkg puts the same files in /usr/local/lib/hypernova-server and adds
# /usr/local/bin/hypernova-server-setup, which a user runs for the normal per-user install from there,
# as the .deb does: installing it touches no service and no home folder (docs/DECISIONS.md, "The
# server's packages"). It's unsigned: docs/RELEASING.md, "The Mac packages", says what signing it needs.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARCH="${1:-}"
case "$ARCH" in
  arm64|x64) ;;
  *) echo "usage: pack-macos.sh <arm64|x64> [--pkg]" >&2; exit 2 ;;
esac
KIND="${2:-tar}"
case "$KIND" in
  tar|--pkg) ;;
  *) echo "pack-macos.sh: unknown option $KIND" >&2; exit 2 ;;
esac

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
TGZ="$("$ROOT/scripts/pack-linux.sh" | tail -n 1)"
mkdir "$STAGE/tree"
tar -xzf "$TGZ" -C "$STAGE/tree" --no-same-owner
SRC="$(find "$STAGE/tree" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
NAME="$(basename "$SRC")"
VERSION="${NAME#hypernova-}"
rm "$SRC/scripts/install-linux.sh"
cp "$ROOT/scripts/install-macos.sh" "$SRC/scripts/"
chmod 0755 "$SRC/scripts/install-macos.sh"
cp "$ROOT/docs/INSTALL.md" "$SRC/README.md"
OUT="$ROOT/dist/macos"
mkdir -p "$OUT"
BASE="$OUT/hypernova-server-$VERSION-macos-$ARCH"

if [ "$KIND" = tar ]; then
  # As scripts/pack-linux.sh: no macOS metadata, and files belong to root, as in any release tarball.
  TARFLAGS=(--owner=0 --group=0 --numeric-owner)
  tar --version 2>/dev/null | grep -q bsdtar && TARFLAGS=(--no-xattrs --no-mac-metadata --uid 0 --gid 0 --uname root --gname root)
  COPYFILE_DISABLE=1 tar ${TARFLAGS[@]+"${TARFLAGS[@]}"} -czf "$BASE.tar.gz" -C "$STAGE/tree" "$NAME"
  echo "$BASE.tar.gz"
  exit 0
fi

command -v pkgbuild >/dev/null && command -v productbuild >/dev/null || { echo "pack-macos.sh: --pkg needs pkgbuild and productbuild, on a Mac." >&2; exit 1; }
PAYLOAD="$STAGE/payload"
mkdir -p "$PAYLOAD/usr/local/lib" "$PAYLOAD/usr/local/bin" "$STAGE/pkgs" "$STAGE/resources"
mv "$SRC" "$PAYLOAD/usr/local/lib/hypernova-server"
install -m 0755 "$ROOT/scripts/server-setup.sh" "$PAYLOAD/usr/local/bin/hypernova-server-setup"
chmod -R a+rX "$PAYLOAD"
ID=com.prudnikweb.hypernova.server
COPYFILE_DISABLE=1 pkgbuild --root "$PAYLOAD" --identifier "$ID" --version "$VERSION" --install-location / "$STAGE/pkgs/server.pkg" >/dev/null
# The product around it: the one place the Installer can say what to do next, and it refuses the other processor.
case "$ARCH" in arm64) HOST=arm64 ;; x64) HOST=x86_64 ;; esac
cat > "$STAGE/resources/conclusion.txt" <<'EOF'
Hypernova's server is unpacked, not set up yet.

Open Terminal and, as the user who will run the agents, run:

    hypernova-server-setup

That installs Node.js, the daemon, Claude Code and the service for your user. Nothing else on this Mac has changed.
EOF
cat > "$STAGE/distribution.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<installer-gui-script minSpecVersion="2">
  <title>Hypernova server</title>
  <conclusion file="conclusion.txt" mime-type="text/plain"/>
  <options customize="never" require-scripts="false" hostArchitectures="$HOST"/>
  <choices-outline>
    <line choice="server"/>
  </choices-outline>
  <choice id="server" visible="false">
    <pkg-ref id="$ID"/>
  </choice>
  <pkg-ref id="$ID" version="$VERSION" onConclusion="none">server.pkg</pkg-ref>
</installer-gui-script>
EOF
productbuild --distribution "$STAGE/distribution.xml" --resources "$STAGE/resources" --package-path "$STAGE/pkgs" "$BASE.pkg" >/dev/null
echo "$BASE.pkg"
