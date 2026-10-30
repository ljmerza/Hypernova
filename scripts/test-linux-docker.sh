#!/usr/bin/env bash
# Hypernova on Linux, end to end, in Docker (docs/LINUX.md): builds the image from this checkout
# (the Dockerfile runs scripts/install-linux.sh in it), starts the daemon as the container's command,
# and checks from inside the container what a server's owner would use: `hypernova status`,
# `doctor`, `pair`, the phone API's health, pairing a stand-in phone, the Mac-only features answering
# "not here" with their reasons, a shared terminal started by the `claude` shell function (running a
# harmless stand-in for Claude Code), `hypernova logs` and `hypernova uninstall`.
#
#   scripts/test-linux-docker.sh [--no-build] [--no-claude] [--keep]
#
# --no-build uses the image as it is; --no-claude builds without Claude Code (faster, offline from
# claude.ai); --keep leaves the container running afterwards. Nothing is published to the network,
# and nothing signs in to Claude: Claude Code is only asked its version and whether it's signed in.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${HYPERNOVA_E2E_IMAGE:-hypernova-e2e}"
PREFIX="${HYPERNOVA_E2E_PREFIX:-hypernova-e2e}"
NAME="$PREFIX-$$"
BUILD=1 CLAUDE=yes KEEP=0
for a in "$@"; do
  case "$a" in
    --no-build) BUILD=0 ;;
    --no-claude) CLAUDE=no ;;
    --keep) KEEP=1 ;;
    *) echo "test-linux-docker.sh: unknown option $a" >&2; exit 2 ;;
  esac
done

PASS=0 FAIL=0
VOL="$PREFIX-$$-home"
ok() { PASS=$((PASS + 1)); printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf '  \033[31m✗\033[0m %s\n' "$1"; [ -n "${2:-}" ] && printf '%s\n' "$2" | sed 's/^/      /' | head -40; return 0; }
# expect <what> <text> <pattern...>: every pattern (grep -E) is in the text.
expect() {
  local what="$1" text="$2" missing=""
  shift 2
  for p in "$@"; do printf '%s' "$text" | grep -qE -- "$p" || missing="$missing [$p]"; done
  if [ -z "$missing" ]; then ok "$what"; else bad "$what: missing$missing" "$text"; fi
}
in_box() { docker exec "$NAME" "$@"; }
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1], {'d': d}))" "$1"; }

if [ "$BUILD" -eq 1 ]; then
  echo "Building $IMAGE (Claude Code: $CLAUDE)…"
  docker build -q -t "$IMAGE" --build-arg CLAUDE="$CLAUDE" "$ROOT" >/dev/null
fi
cleanup() {
  if [ "$KEEP" -eq 1 ]; then echo "Kept: docker exec -it $NAME bash"; return; fi
  docker rm -f "$NAME" "$NAME-b" "$NAME-up" >/dev/null 2>&1 || true
  docker volume rm "$VOL" "$VOL-b" >/dev/null 2>&1 || true
  docker image rm "$IMAGE-up" >/dev/null 2>&1 || true
}
trap cleanup EXIT
# The documented way to run it: a volume for the state, a restart policy. The name service is off
# (no real DNS records or certificates from a test), as in every automated run.
docker run -d --name "$NAME" --hostname hypernova-e2e --restart unless-stopped -e HYPERNOVA_NAMES_URL=off -v "$VOL:/home/hypernova" "$IMAGE" >/dev/null
IP="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$NAME")"
echo "Container $NAME at $IP"

# ── The daemon comes up ────────────────────────────────────────────────────
up=""
for _ in $(seq 1 60); do
  up="$(in_box curl -fsS --max-time 2 http://127.0.0.1:47321/v1/health 2>/dev/null || true)"
  [ -n "$up" ] && break
  sleep 1
done
expect "the phone API answers its health check on 127.0.0.1:47321" "$up" '"ok":true' '"name":"hypernova-e2e"'
expect "the local API answers on 127.0.0.1:47322" "$(in_box curl -fsS --max-time 2 http://127.0.0.1:47322/v1/health || true)" '"ok":true'
expect "the daemon's log names the system" "$(docker logs "$NAME" 2>&1 | head -5)" 'starting on linux'

# A container is no phone's network: the local route is off until the owner turns it on, and the
# image carries no identity of its own (each volume makes its own at its first start).
expect "the local route is off by default in a container" "$(in_box hypernova status 2>&1)" 'Local route +off'
expect "the daemon runs the image's program, not a copy in the volume" "$(in_box hypernova version 2>&1)" '^hypernova [0-9]'
expect "the program is in the image (/opt/hypernova), the state in the volume" "$(in_box sh -c 'ls /opt/hypernova/app/daemon/src/main.ts; ls ~/.local/share/hypernova/daemon.json ~/.local/share/hypernova/admin-token; ls ~/.local/share/hypernova/app 2>&1' 2>&1)" 'main\.ts' 'daemon\.json' 'admin-token' 'No such file'
in_box hypernova lan on >/dev/null 2>&1 || true
# Nothing but the LAN listener (device certificates) faces the container's network.
if in_box curl -s --max-time 3 -o /dev/null "http://$IP:47321/v1/health"; then bad "the phone API is reachable on $IP:47321"; else ok "the phone API isn't reachable on the container's address"; fi
if in_box curl -s --max-time 3 -o /dev/null "http://$IP:47322/v1/health"; then bad "the local API is reachable on $IP:47322"; else ok "the local API isn't reachable on the container's address"; fi
expect "the LAN listener answers over HTTPS on the container's address" "$(in_box curl -sk --max-time 3 "https://$IP:47323/v1/health" || true)" '"ok":true'

# ── The command line ───────────────────────────────────────────────────────
st="$(in_box hypernova status 2>&1)" && rc=0 || rc=$?
[ "$rc" -eq 0 ] && ok "hypernova status exits 0" || bad "hypernova status exited $rc" "$st"
expect "hypernova status says what runs and how phones reach it" "$st" 'Daemon +running' 'Phone API +127\.0\.0\.1:47321' "Local route +port 47323 on $IP" 'Integration +hooks, status line, claude function' 'Devices +none yet'

doc="$(in_box hypernova doctor 2>&1 || true)"
expect "hypernova doctor checks every piece, each with its fix" "$doc" \
  '✓ Linux on (x64|arm64)' '✓ Node\.js v[0-9]+\.' '✓ The daemon in /opt/hypernova/app/daemon' '✓ Shared terminals: node-pty works' \
  '! No systemd user service here' '✓ Daemon answering as “hypernova-e2e”' '✓ Phone API and local API on loopback only' \
  '✓ Hooks and the status line' '✓ `claude` in your shell starts a shared terminal' 'Local network route on port 47323'
if [ "$CLAUDE" = yes ]; then
  expect "doctor: Claude Code installed by Anthropic's installer, not signed in, and how to sign in" "$doc" '✓ Claude Code [0-9]+\.[0-9]+\.[0-9]+ \(~/\.local/bin/claude\)' '✗ Claude Code isn’t signed in\.' 'Run claude once on this server'
fi

pairing="$(in_box hypernova pair --no-wait 2>&1)"
link="$(printf '%s\n' "$pairing" | grep -oE 'https://hypernova\.observer/pair#[^ ]+' | head -n 1 || true)"
expect "hypernova pair prints the QR code, the https link with the local route, and when it expires" "$pairing" '█' 'https://hypernova\.observer/pair#v=1&' "lan=$IP" 'It works once, for [0-9]+ minutes \(until [0-9:]+\)'
# --no-wait must not hang.
start=$SECONDS
in_box hypernova pair --no-wait >/dev/null 2>&1 || true
[ $((SECONDS - start)) -lt 60 ] && ok "hypernova pair --no-wait returns at once" || bad "hypernova pair --no-wait took $((SECONDS - start)) s"

# ── A stand-in phone pairs with that code and asks for the Mac-only features ──
code="$(printf '%s' "$link" | sed -n 's/.*[#&]c=\([^&]*\).*/\1/p')"
paired="$(in_box curl -fsS --max-time 5 -H 'content-type: application/json' -d "{\"code\":\"$code\",\"deviceName\":\"E2E phone\",\"model\":\"Docker\",\"appVersion\":\"0\"}" http://127.0.0.1:47321/v1/pair 2>&1 || true)"
token="$(printf '%s' "$paired" | json "d['token']" 2>/dev/null || true)"
if [ -n "$token" ]; then ok "the pairing code pairs a phone"; else bad "pairing failed" "$paired"; fi
snap="$(in_box curl -fsS --max-time 5 -H "authorization: Bearer $token" http://127.0.0.1:47321/v1/snapshot || true)"
expect "the snapshot says Linux, no battery, and each Mac-only feature with its reason" \
  "$(printf '%s' "$snap" | json "' '.join([d['mac']['platform'], str(d['mac']['battery']), d['mac']['awake']['mode']] + sorted(d['mac']['unavailable'].keys()))" 2>&1)" \
  '^linux None off appWindow awake battery macVoice openWindow simulator$'
awake="$(in_box curl -sS --max-time 5 -w ' HTTP %{http_code}' -H "authorization: Bearer $token" -H 'content-type: application/json' -d '{"mode":"working"}' http://127.0.0.1:47321/v1/mac/awake || true)"
expect "Stay awake answers 501 unavailable, with the reason to show" "$awake" '"code":"unavailable"' 'Stay awake is for a Mac' 'HTTP 501'
lanSnap="$(in_box curl -sk --max-time 3 -w ' HTTP %{http_code}' -H "authorization: Bearer $token" "https://$IP:47323/v1/snapshot" || true)"
expect "over the LAN listener even a paired phone's token needs its device certificate" "$lanSnap" 'device_cert_required' 'HTTP 401'
expect "the phone shows up in hypernova status" "$(in_box hypernova status 2>&1)" 'Devices +1 paired \(E2E phone\)'

# ── A shared terminal, started the way an owner would: `claude` in an interactive bash ──
# The stand-in for Claude Code prints its arguments and waits; the real one is never run here.
in_box sh -c 'mkdir -p /tmp/fake && printf "#!/bin/sh\necho \"stand-in claude: \$*\"\nsleep 120\n" > /tmp/fake/claude && chmod +x /tmp/fake/claude'
# script(1) gives bash a terminal; `sleep | …` keeps its input open, as a person at the keyboard would.
docker exec -d "$NAME" sh -c "sleep 150 | script -qfec \"env PATH=/tmp/fake:\\\$PATH bash -ic 'claude e2e-hello'\" /dev/null >/tmp/term.out 2>&1"
HN=/home/hypernova/.local/share/hypernova/bin/hn
ls_out=""
for _ in $(seq 1 20); do
  ls_out="$(in_box "$HN" ls 2>&1 || true)"
  printf '%s' "$ls_out" | grep -q 'open here in a terminal' && break
  sleep 0.5
done
expect "hn ls lists the shared terminal the claude function started" "$ls_out" '^[0-9a-f]{10}  hypernova +[0-9]+s  open here in a terminal'
# What the holder shows anyone joining (the phone gets the same screen): asked over its socket.
probe='
const { readdirSync } = await import("node:fs")
const { join } = await import("node:path")
const { connect } = await import("node:net")
const shared = await import(process.env.HYPERNOVA_APP + "/daemon/src/term/shared.ts")
const id = readdirSync(shared.TERMS).find((f) => f.endsWith(".json")).slice(0, -5)
const s = connect(shared.sockPath(id))
const frames = new shared.Frames((type, p) => {
  if (type !== shared.MSG) return
  const m = JSON.parse(p.toString("utf8"))
  if (m.t === "hello") s.write(shared.msg({ t: "screen" }))
  if (m.t === "screen") { console.log(m.text); process.exit(0) }
})
s.on("data", (b) => frames.push(b))
s.on("connect", () => s.write(shared.msg({ t: "hello", v: 1, role: "control" })))
setTimeout(() => process.exit(3), 5000)
'
NODE="$(in_box cat /opt/hypernova/node-path)"
screen="$(in_box sh -c "cd /opt/hypernova/app/daemon && \"$NODE\" --input-type=module -e '$probe'" 2>&1 || true)"
expect "the holder's screen shows the stand-in, run through node-pty with the arguments given" "$screen" 'stand-in claude: e2e-hello'

expect "hypernova logs shows the daemon's log" "$(in_box hypernova logs -n 50 2>&1)" 'api on 127\.0\.0\.1:47321' 'local api on 127\.0\.0\.1:47322' 'lan api on'

# ── No shared identity: a second container from the same image has its own ──
docker run -d --name "$NAME-b" --hostname hypernova-e2e-b -e HYPERNOVA_NAMES_URL=off -v "$VOL-b:/home/hypernova" "$IMAGE" >/dev/null
for _ in $(seq 1 60); do docker exec "$NAME-b" curl -fsS --max-time 2 http://127.0.0.1:47321/v1/health >/dev/null 2>&1 && break; sleep 1; done
S=/home/hypernova/.local/share/hypernova
id_a="$(in_box cat $S/daemon.json)"; id_b="$(docker exec "$NAME-b" cat $S/daemon.json)"
tok_a="$(in_box cat $S/admin-token)"; tok_b="$(docker exec "$NAME-b" cat $S/admin-token)"
hk_a="$(in_box cat $S/hook-secret)"; hk_b="$(docker exec "$NAME-b" cat $S/hook-secret)"
if [ -n "$id_a" ] && [ "$id_a" != "$id_b" ] && [ -n "$tok_a" ] && [ "$tok_a" != "$tok_b" ] && [ -n "$hk_a" ] && [ "$hk_a" != "$hk_b" ]; then ok "two containers from one image have different computer ids, admin tokens and hook secrets"; else bad "two containers share an identity" "$id_a / $id_b"; fi
expect "the hooks in Claude Code's settings carry this volume's own secret" "$(in_box cat /home/hypernova/.claude/settings.json)" "$hk_a"
docker rm -f "$NAME-b" >/dev/null 2>&1 || true

# ── A rebuilt image with the same volume runs the new daemon ──
printf 'FROM %s\nRUN sed -i "s/^export const VERSION = .*/export const VERSION = \x279.9.9\x27/" /opt/hypernova/app/daemon/src/util.ts\n' "$IMAGE" | docker build -q -t "$IMAGE-up" - >/dev/null
docker stop -t 3 "$NAME" >/dev/null
docker rm "$NAME" >/dev/null
docker run -d --name "$NAME-up" --hostname hypernova-e2e -e HYPERNOVA_NAMES_URL=off -v "$VOL:/home/hypernova" "$IMAGE-up" >/dev/null
for _ in $(seq 1 60); do docker exec "$NAME-up" curl -fsS --max-time 2 http://127.0.0.1:47321/v1/health >/dev/null 2>&1 && break; sleep 1; done
expect "after a rebuild with the same volume the new daemon runs, with the old state" "$(docker exec "$NAME-up" hypernova version 2>&1; docker exec "$NAME-up" hypernova status 2>&1)" 'hypernova 9\.9\.9' 'Hypernova 9\.9\.9' 'Devices +1 paired \(E2E phone\)'
[ "$(docker exec "$NAME-up" cat $S/daemon.json)" = "$id_a" ] && ok "the volume's identity survives the upgrade" || bad "the identity changed across the upgrade"
# A killed daemon is started again by the restart policy.
docker update --restart unless-stopped "$NAME-up" >/dev/null
docker exec "$NAME-up" sh -c 'kill -9 $(pgrep -f "cli/hypernova.ts run" | head -n 1)' >/dev/null 2>&1 || true
back=""
for _ in $(seq 1 60); do back="$(docker exec "$NAME-up" curl -fsS --max-time 2 http://127.0.0.1:47321/v1/health 2>/dev/null || true)"; [ -n "$back" ] && break; sleep 1; done
expect "the container comes back after its daemon is killed (--restart)" "$back" '"ok":true'
NAME="$NAME-up"

# ── Uninstall ──────────────────────────────────────────────────────────────
un="$(in_box hypernova uninstall --yes 2>&1)" && rc=0 || rc=$?
[ "$rc" -eq 0 ] && ok "hypernova uninstall exits 0" || bad "hypernova uninstall exited $rc" "$un"
expect "uninstall says what it removed and what stays" "$un" 'Hooks, status line and the claude function removed' 'Kept: paired phones and settings' 'Claude Code and its sign-in stay'
left="$(in_box sh -c 'grep -c "hypernova shared terminals" ~/.bashrc; grep -c "/hooks/" ~/.claude/settings.json; ls /opt/hypernova/app /opt/hypernova/bin/hypernova 2>&1; ls ~/.local/share/hypernova' 2>&1 || true)"
expect "nothing of the integration, the command or the daemon is left; the state is" "$left" '^0$' 'No such file' 'devices\.json|identity|admin-token'

echo
echo "$PASS passed, $FAIL failed."
[ "$FAIL" -eq 0 ]
