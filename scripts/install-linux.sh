#!/usr/bin/env bash
# Hypernova's daemon on a Linux server or PC (Ubuntu or Debian, x86_64 or arm64), command line only,
# for Claude Code, Codex or both. See docs/INSTALL.md and docs/LINUX.md.
#
#   scripts/install-linux.sh                        from the repository this script is in
#   scripts/install-linux.sh --from <dir>           a checkout, or an unpacked tarball
#   scripts/install-linux.sh --from <file.tar.gz>   a tarball (scripts/pack-linux.sh makes one)
#
# Options: --no-claude (don't install Claude Code: Codex alone), --no-integration (leave the hooks
# and the shell function for the first start: a container image), --yes (no questions).
#
# What it does, as the user who will run Claude Code (not root; it asks sudo for packages only):
#   1. the system packages it needs: curl, tar, openssl, git, and build tools for the terminal
#      module (node-pty has no prebuilt Linux binary), plus ps and lsof for Telescope;
#   2. Node.js 24 from nodejs.org, checked against nodejs.org's SHASUMS256.txt, into
#      ~/.local/share/hypernova/node (nothing outside Hypernova's folder);
#   3. then `hypernova setup`, which installs the daemon, Claude Code (Anthropic's installer), the
#      hooks, the shell integration and the systemd service.
# Nothing listens on a public port: the phone API is on 127.0.0.1, behind `tailscale serve`.
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
    -h|--help) sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "install-linux.sh: unknown option $1 (try --help)" >&2; exit 2 ;;
  esac
done

say() { printf '  %s\n' "$*"; }
die() { printf 'install-linux.sh: %s\n' "$*" >&2; exit 1; }

# ── The machine ────────────────────────────────────────────────────────────
[ "$(uname -s)" = "Linux" ] || die "this script is for Linux. On a Mac, use the Hypernova menu bar app."
case "$(uname -m)" in
  x86_64|amd64) ARCH=x64 ;;
  aarch64|arm64) ARCH=arm64 ;;
  *) die "Hypernova is built for x86_64 and arm64; this machine is $(uname -m)." ;;
esac
if [ -r /etc/os-release ]; then
  . /etc/os-release
  case " ${ID:-} ${ID_LIKE:-} " in
    *" debian "*|*" ubuntu "*) ;;
    *) say "This script knows Ubuntu and Debian; ${PRETTY_NAME:-this system} may need its packages installed by hand." ;;
  esac
fi
# Under sudo, everything would land in root's home and run as root: the daemon and Claude Code
# belong to the person who uses them.
if [ -n "${SUDO_USER:-}" ] && [ "$(id -u)" -eq 0 ]; then
  die "run this as ${SUDO_USER}, without sudo: it asks sudo for the system packages itself."
fi
if [ "$(id -u)" -eq 0 ]; then
  say "Running as root: Claude Code's sessions would have the whole server, and Claude Code refuses to skip permissions as root, so phone sessions run without the Bypass mode."
  say "Safer: adduser claude; loginctl enable-linger claude; su - claude, and install there."
fi

DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
case "$DATA" in /*) ;; *) DATA="$HOME/.local/share" ;; esac
SUPPORT="${HYPERNOVA_SUPPORT:-$DATA/hypernova}"
# Where Node lives and which file names it: beside the state by default; a container keeps them in
# its image (Dockerfile) so the state's volume never hides a newer program.
NODE_ROOT="${HYPERNOVA_NODE_ROOT:-$SUPPORT/node}"
NODE_PATH_FILE="${HYPERNOVA_NODE_PATH:-$SUPPORT/node-path}"
mkdir -p "$SUPPORT"
chmod 700 "$SUPPORT"

# ── 1. System packages ─────────────────────────────────────────────────────
# command → Debian package. build-essential brings make and g++.
need=()
for pair in curl:curl tar:tar gzip:gzip openssl:openssl git:git make:build-essential g++:build-essential python3:python3 ps:procps lsof:lsof sha256sum:coreutils; do
  cmd="${pair%%:*}"; pkg="${pair#*:}"
  command -v "$cmd" >/dev/null 2>&1 || need+=("$pkg")
done
# ca-certificates has no command to look for.
[ -e /etc/ssl/certs/ca-certificates.crt ] || need+=(ca-certificates)
if [ ${#need[@]} -gt 0 ]; then
  pkgs=$(printf '%s\n' "${need[@]}" | sort -u | tr '\n' ' ')
  say "Installing system packages: $pkgs"
  if ! command -v apt-get >/dev/null 2>&1; then
    die "no apt-get here: install these yourself, then run this again: $pkgs"
  fi
  SUDO=""
  if [ "$(id -u)" -ne 0 ]; then
    command -v sudo >/dev/null 2>&1 || die "installing packages needs root, and there's no sudo: as root, run apt-get install -y $pkgs"
    SUDO="sudo"
  fi
  $SUDO env DEBIAN_FRONTEND=noninteractive apt-get update -qq
  # shellcheck disable=SC2086
  $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends $pkgs >/dev/null
fi

# ── 2. Node.js 24 ──────────────────────────────────────────────────────────
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
  sums="$(curl -fsSL "$DIST/latest-v$NODE_MAJOR.x/SHASUMS256.txt")" || die "couldn't reach nodejs.org: check this server's internet connection."
  file="$(printf '%s\n' "$sums" | awk -v a="linux-$ARCH.tar.gz" '$2 ~ ("^node-v[0-9.]+-" a "$") {print $2; exit}')"
  [ -n "$file" ] || die "nodejs.org has no Node.js $NODE_MAJOR for linux-$ARCH."
  want="$(printf '%s\n' "$sums" | awk -v f="$file" '$2 == f {print $1; exit}')"
  version="${file#node-}"; version="${version%-linux-*}"
  say "Downloading Node.js $version from nodejs.org…"
  tmp="$(mktemp -d)"
  curl -fsSL "$DIST/latest-v$NODE_MAJOR.x/$file" -o "$tmp/$file"
  got="$(sha256sum "$tmp/$file" | awk '{print $1}')"
  if [ "$got" != "$want" ]; then
    rm -rf "$tmp"
    die "the Node.js download didn't match nodejs.org's checksum, so it was thrown away. Try again."
  fi
  mkdir -p "$NODE_ROOT/$version" "$(dirname "$NODE_PATH_FILE")"
  tar -xzf "$tmp/$file" -C "$NODE_ROOT/$version" --strip-components=1 --no-same-owner
  rm -rf "$tmp"
  NODE="$NODE_ROOT/$version/bin/node"
  printf '%s\n' "$NODE" > "$NODE_PATH_FILE"
  chmod 600 "$NODE_PATH_FILE"
fi
say "Node.js $("$NODE" --version) at $NODE"

# ── 3. The daemon's files, then setup ──────────────────────────────────────
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -z "$FROM" ]; then
  if [ -f "$HERE/../daemon/package.json" ]; then
    FROM="$(cd "$HERE/.." && pwd)"
  else
    die "say where the daemon is: --from <repository folder or tarball> (the release's Hypernova-linux.tar.gz: https://github.com/prudnik-web/Hypernova/releases/latest)."
  fi
fi
case "$FROM" in
  *.tar.gz|*.tgz)
    [ -f "$FROM" ] || die "no file $FROM"
    unpack="$(mktemp -d)"
    tar -xzf "$FROM" -C "$unpack" --no-same-owner
    # The tarball holds one folder (hypernova-<version>/).
    FROM="$(find "$unpack" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
    ;;
esac
[ -f "$FROM/daemon/package.json" ] || [ -f "$FROM/package.json" ] || die "$FROM doesn't hold Hypernova's daemon."
DAEMON="$FROM/daemon"
[ -f "$DAEMON/package.json" ] || DAEMON="$FROM"

[ "$YES" -eq 1 ] && SETUP_ARGS+=(--yes)
echo
exec "$NODE" --disable-warning=ExperimentalWarning "$DAEMON/src/cli/hypernova.ts" setup --from "$DAEMON" "${SETUP_ARGS[@]}"
