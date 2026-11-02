#!/usr/bin/env bash
# Hypernova's server on a Mac (Apple silicon or Intel, macOS 13.5 or later, which Node.js 24 needs),
# command line only, for Claude Code, Codex or both. See docs/INSTALL.md, "On a Mac".
# Written for the bash a Mac ships, 3.2: scripts/test-install-macos.sh runs it under that.
#
#   scripts/install-macos.sh                        from the repository or package this script is in
#   scripts/install-macos.sh --from <dir>           a checkout, or an unpacked package
#   scripts/install-macos.sh --from <file.tar.gz>   a package (scripts/pack-macos.sh makes one)
#
# Options: --no-claude (don't install Claude Code: Codex alone), --no-integration (leave the hooks and
# the shell function for later), --yes (no questions).
#
# What it does, as the user who will run Claude Code (never root, and it needs no sudo):
#   1. Node.js 24 from nodejs.org, checked against nodejs.org's SHASUMS256.txt, into
#      ~/Library/Application Support/Hypernova/node (nothing outside Hypernova's folder);
#   2. then `hypernova setup`, which installs the daemon, Claude Code (Anthropic's installer), the
#      hooks, the shell integration and the LaunchAgent.
# node-pty, the terminal module, comes prebuilt for both of a Mac's processors, so nothing is
# compiled. Apple's Command Line Tools are only needed for git, or if that prebuilt module won't load.
# Nothing listens on a public port: phones use the local route (47323, mutual TLS) on your network.
set -euo pipefail

FROM=""
SETUP_ARGS=()
YES=0
while [ $# -gt 0 ]; do
  case "$1" in
    --from) FROM="${2:?--from needs a path}"; shift 2 ;;
    --from=*) FROM="${1#--from=}"; shift ;;
    --no-claude) SETUP_ARGS+=(--no-claude); shift ;;
    --no-integration) SETUP_ARGS+=(--no-integration); shift ;;
    --yes|-y) YES=1; shift ;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "install-macos.sh: unknown option $1 (try --help)" >&2; exit 2 ;;
  esac
done

say() { printf '  %s\n' "$*"; }
die() { printf 'install-macos.sh: %s\n' "$*" >&2; exit 1; }

# ── The machine ────────────────────────────────────────────────────────────
[ "$(uname -s)" = "Darwin" ] || die "this script is for a Mac. On Linux, use scripts/install-linux.sh."
# A Terminal under Rosetta says x86_64 on Apple silicon: ask the hardware, so Node.js is the native one.
if [ "$(sysctl -n hw.optional.arm64 2>/dev/null || echo 0)" = 1 ]; then
  ARCH=arm64
else
  case "$(uname -m)" in
    x86_64) ARCH=x64 ;;
    arm64) ARCH=arm64 ;;
    *) die "Hypernova is built for x86_64 and arm64; this Mac is $(uname -m)." ;;
  esac
fi
# A LaunchAgent belongs to the person at the screen, and the daemon and Claude Code run as that person.
[ "$(id -u)" -ne 0 ] || die "run this as the user who will run the agents, without sudo: a Mac's install needs no root."

# Apple's Command Line Tools. Not needed for the terminal module, which comes prebuilt, so the install
# goes on either way; but /usr/bin/git only offers to install them until they're there.
if ! xcode-select -p >/dev/null 2>&1 && [ ! -x /opt/homebrew/bin/git ] && [ ! -x /usr/local/bin/git ]; then
  say "Apple's Command Line Tools aren't installed. Hypernova doesn't need them to install (its terminal"
  say "module comes prebuilt), but git does, and Claude Code and the app's git views use git. To add them:"
  say "    xcode-select --install"
  say "They'd also be what's needed to build the terminal module, if the prebuilt one ever won't load."
  echo
fi

SUPPORT="${HYPERNOVA_SUPPORT:-$HOME/Library/Application Support/Hypernova}"
NODE_ROOT="${HYPERNOVA_NODE_ROOT:-$SUPPORT/node}"
NODE_PATH_FILE="${HYPERNOVA_NODE_PATH:-$SUPPORT/node-path}"
mkdir -p "$SUPPORT"
chmod 700 "$SUPPORT"

# ── 1. Node.js 24 ──────────────────────────────────────────────────────────
NODE_MAJOR=24
DIST="${HYPERNOVA_NODE_DIST:-https://nodejs.org/dist}"
node_ok() { [ -x "$1" ] && "$1" -e "process.exit(Number(process.versions.node.split('.')[0]) >= $NODE_MAJOR ? 0 : 1)" 2>/dev/null; }
NODE=""
if [ -f "$NODE_PATH_FILE" ] && node_ok "$(cat "$NODE_PATH_FILE")"; then
  NODE="$(cat "$NODE_PATH_FILE")"
elif command -v node >/dev/null 2>&1 && node_ok "$(command -v node)"; then
  NODE="$(command -v node)"
fi
if [ -z "$NODE" ]; then
  # nodejs.org keeps the newest of each line in latest-v24.x; its SHASUMS256.txt names the files.
  sums="$(curl -fsSL "$DIST/latest-v$NODE_MAJOR.x/SHASUMS256.txt")" || die "couldn't reach nodejs.org: check this Mac's internet connection."
  file="$(printf '%s\n' "$sums" | awk -v a="darwin-$ARCH.tar.gz" '$2 ~ ("^node-v[0-9.]+-" a "$") {print $2; exit}')"
  [ -n "$file" ] || die "nodejs.org has no Node.js $NODE_MAJOR for darwin-$ARCH."
  want="$(printf '%s\n' "$sums" | awk -v f="$file" '$2 == f {print $1; exit}')"
  version="${file#node-}"; version="${version%-darwin-*}"
  say "Downloading Node.js $version from nodejs.org…"
  tmp="$(mktemp -d)"
  curl -fsSL "$DIST/latest-v$NODE_MAJOR.x/$file" -o "$tmp/$file"
  got="$(shasum -a 256 "$tmp/$file" | awk '{print $1}')"
  if [ "$got" != "$want" ]; then
    rm -rf "$tmp"
    die "the Node.js download didn't match nodejs.org's checksum, so it was thrown away. Try again."
  fi
  mkdir -p "$NODE_ROOT/$version" "$(dirname "$NODE_PATH_FILE")"
  tar -xzf "$tmp/$file" -C "$NODE_ROOT/$version" --strip-components=1 --no-same-owner
  rm -rf "$tmp"
  NODE="$NODE_ROOT/$version/bin/node"
  node_ok "$NODE" || die "Node.js $version doesn't run on this Mac: it needs macOS 13.5 or later (this is $(sw_vers -productVersion 2>/dev/null || echo unknown))."
  printf '%s\n' "$NODE" > "$NODE_PATH_FILE"
  chmod 600 "$NODE_PATH_FILE"
fi
say "Node.js $("$NODE" --version) at $NODE"

# ── 2. The daemon's files, then setup ──────────────────────────────────────
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -z "$FROM" ]; then
  if [ -f "$HERE/../daemon/package.json" ]; then
    FROM="$(cd "$HERE/.." && pwd)"
  else
    die "say where the daemon is: --from <repository folder or package> (a release's hypernova-server-<tag>-macos-$ARCH.tar.gz: https://github.com/ljmerza/Hypernova/releases/latest)."
  fi
fi
case "$FROM" in
  *.tar.gz|*.tgz)
    [ -f "$FROM" ] || die "no file $FROM"
    unpack="$(mktemp -d)"
    tar -xzf "$FROM" -C "$unpack" --no-same-owner
    # The package holds one folder (hypernova-<version>/).
    FROM="$(find "$unpack" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
    ;;
esac
[ -f "$FROM/daemon/package.json" ] || [ -f "$FROM/package.json" ] || die "$FROM doesn't hold Hypernova's server."
DAEMON="$FROM/daemon"
[ -f "$DAEMON/package.json" ] || DAEMON="$FROM"
# A package saved by a browser carries macOS's quarantine flag, and its copy would keep it. Never
# fatal: a folder only root can write to (the .pkg's) has no flag to take off.
xattr -dr com.apple.quarantine "$FROM" 2>/dev/null || true

[ "$YES" -eq 1 ] && SETUP_ARGS+=(--yes)
echo
# Bash 3.2 calls an empty array unbound under `set -u`, hence the guard.
exec "$NODE" --disable-warning=ExperimentalWarning "$DAEMON/src/cli/hypernova.ts" setup --from "$DAEMON" ${SETUP_ARGS[@]+"${SETUP_ARGS[@]}"}
