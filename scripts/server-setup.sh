#!/usr/bin/env bash
# hypernova-server-setup: what the .deb and the Mac's .pkg add to the system's commands. Those
# packages only put the release in ../lib/hypernova-server, as root; this does the normal per-user
# install from that folder, as the user who will run the agents: its own Node.js, its own daemon, its
# own service and shell integration. Run it again after upgrading the package if this server doesn't
# update itself. Options go to the install script (--no-claude, --no-integration, --yes);
# --force also installs over a newer daemon of your own.
set -euo pipefail

die() { printf 'hypernova-server-setup: %s\n' "$*" >&2; exit 1; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="$(cd "$HERE/../lib/hypernova-server" 2>/dev/null && pwd)" || die "there's no server beside this command. Install the package again."
[ -f "$LIB/daemon/package.json" ] || die "there's no server in $LIB. Install the package again."
case "$(uname -s)" in
  Darwin)
    INSTALL="$LIB/scripts/install-macos.sh"
    STATE="${HYPERNOVA_SUPPORT:-$HOME/Library/Application Support/Hypernova}"
    ;;
  *)
    INSTALL="$LIB/scripts/install-linux.sh"
    DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
    case "$DATA" in /*) ;; *) DATA="$HOME/.local/share" ;; esac
    STATE="${HYPERNOVA_SUPPORT:-$DATA/hypernova}"
    ;;
esac
# Everything it installs goes in a person's home, and the agents run as that person.
[ "$(id -u)" -ne 0 ] || die "run it as the user who will run the agents, not root and not under sudo."

FORCE=0
ARGS=()
for a in "$@"; do
  case "$a" in
    --force) FORCE=1 ;;
    -h|--help) sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) ARGS+=("$a") ;;
  esac
done

version_in() { [ -f "$1" ] || return 0; sed -n 's/^ *"version": *"\([^"]*\)".*/\1/p' "$1" | head -n 1; }
# Whether $1 is a higher X.Y.Z than $2.
higher() {
  printf '%s\n%s\n' "$1" "$2" | awk -F. '
    NR == 1 { for (i = 1; i <= 3; i++) a[i] = $i + 0 }
    NR == 2 { for (i = 1; i <= 3; i++) b[i] = $i + 0 }
    END { for (i = 1; i <= 3; i++) { if (a[i] > b[i]) exit 0; if (a[i] < b[i]) exit 1 } exit 1 }'
}
# A server that updated itself is often ahead of the package: going back is never done by accident.
theirs="$(version_in "$STATE/app/daemon/package.json")"
ours="$(version_in "$LIB/daemon/package.json")"
if [ "$FORCE" -eq 0 ] && [ -n "$theirs" ] && [ -n "$ours" ] && higher "$theirs" "$ours"; then
  printf '  Your server is already on %s, newer than this package (%s): nothing changed.\n' "$theirs" "$ours"
  printf '  To go back to %s anyway: hypernova-server-setup --force\n' "$ours"
  exit 0
fi

exec "$INSTALL" --from "$LIB" ${ARGS[@]+"${ARGS[@]}"}
