#!/usr/bin/env bash
# Hypernova's server in one line, on Ubuntu or Debian (x86_64 or arm64), as the user who will run
# the agents (not root):
#
#   curl -fsSL https://ljmerza.github.io/Hypernova/get.sh | bash
#
# It downloads the latest release's server package (hypernova-server-<tag>-linux.tar.gz) from
# GitHub and runs its scripts/install-linux.sh, which installs the system packages (with sudo),
# Node.js 24, the daemon, Claude Code and the service. Options after `bash -s --` go to that
# script (--no-claude, --yes):
#
#   curl -fsSL https://ljmerza.github.io/Hypernova/get.sh | bash -s -- --yes
#
# HYPERNOVA_TAG=v0.3.0 picks a release instead of the latest. Run again to update.
set -euo pipefail

REPO=ljmerza/Hypernova
die() { printf 'get.sh: %s\n' "$*" >&2; exit 1; }

[ "$(uname -s)" = Linux ] || die "this is for Linux (see docs/INSTALL.md for the other ways)."
[ "$(id -u)" -ne 0 ] || die "run it as the user who will run the agents, not root (it asks sudo for packages)."
command -v curl >/dev/null || die "curl is missing: sudo apt-get install -y curl"
command -v tar >/dev/null || die "tar is missing: sudo apt-get install -y tar"

if [ -n "${HYPERNOVA_TAG:-}" ]; then
  api="https://api.github.com/repos/$REPO/releases/tags/$HYPERNOVA_TAG"
else
  api="https://api.github.com/repos/$REPO/releases/latest"
fi
url=$(curl -fsSL -H 'Accept: application/vnd.github+json' "$api" \
  | grep -o '"browser_download_url": *"[^"]*hypernova-server-[^"]*-linux\.tar\.gz"' \
  | head -1 | sed 's/.*"\(https[^"]*\)"$/\1/') || true
[ -n "$url" ] || die "no server package on ${HYPERNOVA_TAG:-the latest release} of $REPO."

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
printf '  Downloading %s\n' "${url##*/}"
curl -fsSL "$url" -o "$tmp/server.tar.gz"
tar -xzf "$tmp/server.tar.gz" -C "$tmp"
install=$(ls "$tmp"/hypernova-*/scripts/install-linux.sh 2>/dev/null | head -1)
[ -n "$install" ] || die "the package has no scripts/install-linux.sh."

# Piped into bash, this script's standard input is the script itself: the installer's questions
# read the terminal instead, when there is one.
if [ -t 0 ] || ! { : </dev/tty; } 2>/dev/null; then
  "$install" "$@"
else
  "$install" "$@" </dev/tty
fi

cat <<'EOF'

  Next:
    claude            sign in to Claude Code once
    hypernova pair    a QR code for the phone (the app: hypernova-<tag>.apk on the releases page)
EOF
