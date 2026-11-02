#!/usr/bin/env bash
# Packs Hypernova's server as a Debian package: dist/deb/hypernova-server_<version>_<arch>.deb, for
# Ubuntu and Debian. Its path is the last line printed. A release attaches it as
# hypernova-server-<tag>-linux-<arch>.deb (.github/workflows/server-packages.yml).
#
#   scripts/pack-deb.sh <amd64|arm64> [--version X.Y.Z]
#
# --version gives the package and the daemon inside it another version (scripts/test-server-deb.sh
# makes two, to test an upgrade); without it, daemon/package.json's.
#
# It holds what the Linux tarball holds (scripts/pack-linux.sh), in /usr/lib/hypernova-server, and adds
# /usr/bin/hypernova-server-setup, which a user runs for the normal per-user install from there. So it
# has no native code of its own (node-pty is built, and Node.js fetched, by each user's install); the
# architecture says which machines it's for and which runner tested it. Its scripts only say what to
# do next: no service, no user, no home folder is touched by installing, upgrading, removing or
# purging it (docs/DECISIONS.md, "The server's packages").
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARCH="${1:-}"
case "$ARCH" in
  amd64|arm64) shift ;;
  *) echo "usage: pack-deb.sh <amd64|arm64> [--version X.Y.Z]" >&2; exit 2 ;;
esac
VERSION=""
while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="${2:?--version needs a version}"; shift 2 ;;
    *) echo "pack-deb.sh: unknown option $1" >&2; exit 2 ;;
  esac
done

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
TGZ="$("$ROOT/scripts/pack-linux.sh" | tail -n 1)"
tar -xzf "$TGZ" -C "$STAGE" --no-same-owner
SRC="$(find "$STAGE" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
if [ -n "$VERSION" ]; then
  sed -i.orig "s/^  \"version\": \"[^\"]*\"/  \"version\": \"$VERSION\"/" "$SRC/daemon/package.json"
  rm -f "$SRC/daemon/package.json.orig"
else
  VERSION="$(sed -n 's/^  "version": "\([^"]*\)".*/\1/p' "$SRC/daemon/package.json" | head -n 1)"
fi

PKG="$STAGE/pkg"
mkdir -p "$PKG/DEBIAN" "$PKG/usr/lib" "$PKG/usr/bin" "$PKG/usr/share/doc/hypernova-server"
mv "$SRC" "$PKG/usr/lib/hypernova-server"
install -m 0755 "$ROOT/scripts/server-setup.sh" "$PKG/usr/bin/hypernova-server-setup"
chmod 0755 "$PKG/usr/lib/hypernova-server/scripts/install-linux.sh"
# Debian's own place for the licence; the full texts travel in /usr/lib/hypernova-server too.
cat > "$PKG/usr/share/doc/hypernova-server/copyright" <<'EOF'
Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/
Upstream-Name: Hypernova
Source: https://github.com/ljmerza/Hypernova

Files: *
License: Apache-2.0
 See /usr/lib/hypernova-server/LICENSE, NOTICE and THIRD-PARTY-NOTICES.md,
 and on Debian systems /usr/share/common-licenses/Apache-2.0.
EOF

# What install-linux.sh would otherwise ask sudo for: its own list, command by command.
cat > "$PKG/DEBIAN/control" <<EOF
Package: hypernova-server
Version: $VERSION
Architecture: $ARCH
Maintainer: Hypernova <ljmerza@users.noreply.github.com>
Section: admin
Priority: optional
Installed-Size: $(du -sk "$PKG/usr" | cut -f1)
Depends: bash, ca-certificates, coreutils, curl, tar, gzip, openssl, git, build-essential, python3, procps, lsof
Homepage: https://github.com/ljmerza/Hypernova
Description: Hypernova's server, a control room for Claude Code and Codex
 Installs the release in /usr/lib/hypernova-server and adds hypernova-server-setup.
 Each user who will run agents runs hypernova-server-setup once: that is the
 normal per-user install (its own Node.js, service and shell integration) from
 this package. Installing, upgrading or removing this package touches no service
 and no home folder. The client, the Android app, is a separate download.
EOF
cat > "$PKG/DEBIAN/postinst" <<'EOF'
#!/bin/sh
# Only says what's next: the install itself is each user's, and a server already installed keeps running.
set -e
if [ "$1" = configure ]; then
  if [ -n "$2" ]; then
    cat <<'WORDS'

  Hypernova's server package is upgraded. Servers already running on this machine aren't touched, and
  they update themselves. If yours doesn't, run hypernova-server-setup as your user.

WORDS
  else
    cat <<'WORDS'

  Hypernova's server is unpacked, not set up yet. As the user who will run the agents (not root):

      hypernova-server-setup

WORDS
  fi
fi
exit 0
EOF
cat > "$PKG/DEBIAN/postrm" <<'EOF'
#!/bin/sh
# Never a home folder: a user's server, its paired phones and settings are that user's.
set -e
case "$1" in
  remove)
    cat <<'WORDS'

  Hypernova's server package is gone. A server you set up from it keeps running, with its paired phones
  and settings: to remove that too, run hypernova uninstall as that user (--purge for phones and settings).

WORDS
    ;;
esac
exit 0
EOF
chmod 0755 "$PKG/DEBIAN/postinst" "$PKG/DEBIAN/postrm"
# Whatever this machine's umask made of them: dpkg-deb wants these modes, and they're what's installed.
chmod 0755 "$PKG" "$PKG/DEBIAN"
chmod -R u=rwX,go=rX "$PKG/usr"

OUT="$ROOT/dist/deb"
mkdir -p "$OUT"
DEB="$OUT/hypernova-server_${VERSION}_${ARCH}.deb"
dpkg-deb --root-owner-group --build "$PKG" "$DEB" >/dev/null
echo "$DEB"
