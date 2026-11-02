#!/usr/bin/env bash
# Hypernova's server in one line, on Ubuntu or Debian (x86_64 or arm64) or on a Mac (Apple silicon or
# Intel), as the user who will run the agents (not root):
#
#   curl -fsSL https://ljmerza.github.io/Hypernova/get.sh | bash
#
# It downloads the latest release's server package from GitHub
# (hypernova-server-<tag>-linux.tar.gz, or on a Mac hypernova-server-<tag>-macos-<arch>.tar.gz) and
# runs its install script (scripts/install-linux.sh or install-macos.sh), which installs Node.js 24,
# the daemon, Claude Code and the service, and on Linux the system packages too (with sudo). Options
# after `bash -s --` go to that script (--no-claude, --yes):
#
#   curl -fsSL https://ljmerza.github.io/Hypernova/get.sh | bash -s -- --yes
#
# HYPERNOVA_TAG=v0.3.0 picks a release instead of the latest. Run again to update.
# Written for the bash a Mac ships, 3.2 (scripts/test-install-macos.sh runs it under that).
set -euo pipefail

REPO=ljmerza/Hypernova
die() { printf 'get.sh: %s\n' "$*" >&2; exit 1; }

case "$(uname -s)" in
  Linux)
    ASSET='-linux\.tar\.gz'
    SCRIPT='install-linux.sh'
    MISSING="no server package on ${HYPERNOVA_TAG:-the latest release} of $REPO."
    TOOL_HINT='sudo apt-get install -y'
    ;;
  Darwin)
    # A Terminal under Rosetta says x86_64 on Apple silicon: ask the hardware.
    if [ "$(sysctl -n hw.optional.arm64 2>/dev/null || echo 0)" = 1 ]; then ARCH=arm64; else ARCH=x64; fi
    ASSET="-macos-$ARCH\\.tar\\.gz"
    SCRIPT='install-macos.sh'
    MISSING="no server package for a Mac ($ARCH) on ${HYPERNOVA_TAG:-the latest release} of $REPO. A release gets its Mac packages some minutes after it's published: try again soon, or see https://github.com/$REPO/releases."
    TOOL_HINT='install'
    ;;
  *) die "Hypernova's server runs on Linux and on a Mac (see docs/INSTALL.md for the other ways)." ;;
esac
[ "$(id -u)" -ne 0 ] || die "run it as the user who will run the agents, not root (it asks sudo for packages, on Linux)."
command -v curl >/dev/null || die "curl is missing: $TOOL_HINT curl"
command -v tar >/dev/null || die "tar is missing: $TOOL_HINT tar"

if [ -n "${HYPERNOVA_TAG:-}" ]; then
  api="https://api.github.com/repos/$REPO/releases/tags/$HYPERNOVA_TAG"
else
  api="https://api.github.com/repos/$REPO/releases/latest"
fi
url=$(curl -fsSL -H 'Accept: application/vnd.github+json' "$api" \
  | grep -o "\"browser_download_url\": *\"[^\"]*hypernova-server-[^\"]*$ASSET\"" \
  | head -1 | sed 's/.*"\(https[^"]*\)"$/\1/') || true
[ -n "$url" ] || die "$MISSING"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
printf '  Downloading %s\n' "${url##*/}"
curl -fsSL "$url" -o "$tmp/server.tar.gz"
tar -xzf "$tmp/server.tar.gz" -C "$tmp"
install=$(ls "$tmp"/hypernova-*/scripts/$SCRIPT 2>/dev/null | head -1)
[ -n "$install" ] || die "the package has no scripts/$SCRIPT."

# Piped into bash, this script's standard input is the script itself: the installer's questions
# read the terminal instead, when there is one.
if [ -t 0 ] || ! { : </dev/tty; } 2>/dev/null; then
  "$install" "$@"
else
  "$install" "$@" </dev/tty
fi

[ "$SCRIPT" = install-macos.sh ] && printf '\n  Open a new terminal first: that is what puts hypernova and claude on your PATH.\n'
cat <<'EOF2'

  Next:
    claude            sign in to Claude Code once
    hypernova pair    a QR code for the phone (the client: hypernova-client-<tag>-android.apk on the releases page)
EOF2
