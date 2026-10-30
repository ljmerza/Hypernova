#!/usr/bin/env bash
# Hypernova's systemd user service, end to end, in Docker (docs/LINUX.md, "The service"): an Ubuntu
# 24.04 machine with systemd as its first process and a normal user with sudo, like a fresh VPS.
# It installs from a tarball (scripts/pack-linux.sh) the documented way, as that user and with no
# login session of his own (as `sudo -u` or a script would), then checks that:
#   - lingering is on and the service runs on loopback only, and `status` and `doctor` say so;
#   - the daemon, killed outright, is started again by systemd (Restart=on-failure);
#   - a session the daemon started keeps running through a service restart and an update (the
#     install run again), and the new daemon finds it again (KillMode=process);
#   - after a reboot the daemon is back with nobody logged in (lingering);
#   - `hypernova uninstall` takes the service out and says lingering stays on.
# Claude Code is a stand-in that prints its arguments and waits: nothing signs in. The container is
# privileged, because systemd manages its own cgroups, and publishes nothing to the network.
#
#   scripts/test-linux-systemd.sh [--keep]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${HYPERNOVA_SYSTEMD_IMAGE:-hypernova-systemd-test}"
NAME="hypernova-systemd-$$"
KEEP=0
for a in "$@"; do
  case "$a" in
    --keep) KEEP=1 ;;
    *) echo "test-linux-systemd.sh: unknown option $a" >&2; exit 2 ;;
  esac
done

PASS=0 FAIL=0
ok() { PASS=$((PASS + 1)); printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf '  \033[31m✗\033[0m %s\n' "$1"; [ -n "${2:-}" ] && printf '%s\n' "$2" | sed 's/^/      /' | head -40; return 0; }
# expect <what> <text> <pattern...>: every pattern (grep -E) is in the text.
expect() {
  local what="$1" text="$2" missing=""
  shift 2
  for p in "$@"; do printf '%s' "$text" | grep -qE -- "$p" || missing="$missing [$p]"; done
  if [ -z "$missing" ]; then ok "$what"; else bad "$what: missing$missing" "$text"; fi
}
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1], {'d': d}))" "$1"; }

# A VPS image: systemd, logind (lingering), sudo for the install's packages, curl. No Node, no
# compilers: the install script must bring what it needs.
echo "Building ${IMAGE}…"
docker build -q -t "$IMAGE" - >/dev/null <<'DOCKERFILE'
FROM ubuntu:24.04
ARG DEBIAN_FRONTEND=noninteractive
ENV PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
RUN apt-get update && apt-get install -y --no-install-recommends systemd systemd-sysv dbus dbus-user-session libpam-systemd sudo ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*
# A normal user with sudo and no password, as on many VPS images.
RUN useradd --create-home --shell /bin/bash ada && echo 'ada ALL=(ALL) NOPASSWD: ALL' > /etc/sudoers.d/ada && chmod 440 /etc/sudoers.d/ada
STOPSIGNAL SIGRTMIN+3
CMD ["/sbin/init"]
DOCKERFILE

cleanup() { [ "$KEEP" -eq 1 ] && echo "Kept: docker exec -it -u ada $NAME bash" || docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker run -d --name "$NAME" --hostname vps-test --privileged --cgroupns=host -v /sys/fs/cgroup:/sys/fs/cgroup:rw --tmpfs /run --tmpfs /run/lock "$IMAGE" >/dev/null

booted() {
  local s
  for _ in $(seq 1 90); do
    s="$(docker exec "$NAME" systemctl is-system-running 2>/dev/null || true)"
    case "$s" in running|degraded) return 0 ;; esac
    sleep 1
  done
  return 1
}
booted && ok "systemd runs as the machine's first process" || bad "systemd didn't finish starting"
IP="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$NAME")"

# As ada, the way a script or `sudo -u ada` runs things: no login session, no XDG_RUNTIME_DIR.
as_ada() { docker exec -u ada -w /home/ada "$NAME" "$@"; }
HN=/home/ada/.local/bin/hypernova
health() { docker exec "$NAME" curl -fsS --max-time 2 "http://127.0.0.1:$1/v1/health" 2>/dev/null || true; }
wait_health() { for _ in $(seq 1 "${1:-60}"); do [ -n "$(health 47321)" ] && [ -n "$(health 47322)" ] && return 0; sleep 1; done; return 1; }
user_systemctl() { as_ada env XDG_RUNTIME_DIR="/run/user/$(docker exec "$NAME" id -u ada)" systemctl --user "$@"; }
main_pid() { user_systemctl show hypernova.service --property=MainPID --value 2>/dev/null | tr -d '\r' || true; }
alive() { docker exec "$NAME" kill -0 "$1" 2>/dev/null; }

# ── Install, as the docs say ───────────────────────────────────────────────
TAR="$(bash "$ROOT/scripts/pack-linux.sh")"
docker cp "$TAR" "$NAME:/home/ada/" >/dev/null
docker exec "$NAME" chown ada:ada "/home/ada/$(basename "$TAR")"
# The stand-in for Claude Code, where Anthropic's installer puts claude: it answers its version and
# "not signed in". Started as a session (--session-id), it writes the registry entry Claude Code
# writes (~/.claude/sessions/<pid>.json), which is how a restarted daemon finds a session still
# running in its terminal, then prints its arguments and waits, like a session waiting for you.
# Anything else (the Agent SDK listing commands) just ends.
docker exec -i -u ada -w /home/ada "$NAME" sh -c 'mkdir -p ~/.local/bin ~/proj && cat > ~/.local/bin/claude && chmod +x ~/.local/bin/claude' <<'CLAUDE'
#!/bin/sh
case "$1" in
  --version) echo "2.1.0 (Claude Code stand-in)"; exit 0 ;;
  auth) echo '{"loggedIn":false}'; exit 1 ;;
esac
sid="" prev=""
for a in "$@"; do [ "$prev" = "--session-id" ] && sid="$a"; prev="$a"; done
[ -n "$sid" ] || exit 0
mkdir -p "$HOME/.claude/sessions"
printf '{"pid":%s,"sessionId":"%s","cwd":"%s","startedAt":%s000,"kind":"interactive","status":"idle"}\n' "$$" "$sid" "$PWD" "$(date +%s)" > "$HOME/.claude/sessions/$$.json"
echo "stand-in claude: $*"
exec sleep 900
CLAUDE

inst="$(as_ada bash -c 'tar -xzf hypernova-*.tar.gz && hypernova-*/scripts/install-linux.sh --yes' 2>&1)" && rc=0 || rc=$?
[ "$rc" -eq 0 ] && ok "the install script exits 0" || bad "the install script exited $rc" "$inst"
expect "it installs packages and Node.js 24, builds the daemon, finds Claude Code and starts the service" "$inst" \
  'Installing system packages: .*build-essential' 'Downloading Node\.js v25\.' \
  '✓ The daemon installed in ~/\.local/share/hypernova/app/daemon · shared terminals ready \(terminal module built\)' \
  '✓ Claude Code 2\.1\.0 · ~/\.local/bin/claude' '✓ The service: hypernova\.service, running, starts at boot with nobody logged in' \
  '✓ Hooks and the status line in Claude Code’s settings; the claude function in ~/\.bashrc' 'Sign in to Claude: run `claude` once'
if printf '%s' "$inst" | grep -qE 'LIBARCHIVE|Ignoring unknown extended header'; then bad "unpacking the tarball warns about macOS metadata"; else ok "the tarball unpacks without macOS metadata"; fi
if printf '%s' "$inst" | grep -qE 'claude\.ai/install\.sh \| bash'; then bad "the installer ran though Claude Code was there"; else ok "Claude Code already there: Anthropic's installer isn't run"; fi

expect "lingering is on for ada" "$(docker exec "$NAME" loginctl show-user ada --property=Linger --value)" '^yes$'
unit="$(as_ada cat .config/systemd/user/hypernova.service 2>&1)"
expect "the unit runs the installed daemon with its Node, restarts on failure, leaves holders alone" "$unit" \
  '^ExecStart=".*/hypernova/node/v25\.[0-9.]+/bin/node" .*"/home/ada/\.local/share/hypernova/app/daemon/src/main\.ts"$' \
  '^Restart=on-failure$' '^KillMode=process$' '^WantedBy=default\.target$'

# ── Running, on loopback only ──────────────────────────────────────────────
wait_health 30 || true
expect "the phone API answers on 127.0.0.1:47321" "$(health 47321)" '"ok":true' '"name":"vps-test"'
expect "the local API answers on 127.0.0.1:47322" "$(health 47322)" '"ok":true'
if docker exec "$NAME" curl -s --max-time 3 -o /dev/null "http://$IP:47321/v1/health"; then bad "the phone API is reachable on $IP:47321"; else ok "the phone API isn't reachable on the machine's address"; fi
expect "status: the service runs and starts at boot" "$(as_ada "$HN" status 2>&1 || true)" 'Daemon +running \(pid [0-9]+\), hypernova\.service active, starts at boot'
doc="$(as_ada "$HN" doctor 2>&1 || true)"
expect "doctor: the service, lingering, loopback only, the terminal module, and the missing sign-in" "$doc" \
  '✓ hypernova\.service running \(pid [0-9]+\), starts at boot' '✓ Lingering on: runs with nobody logged in' \
  '✓ Phone API and local API on loopback only' '✓ Shared terminals: node-pty works' '✗ Claude Code isn’t signed in\.' 'Run claude once on this server'

# ── Restarted when it dies ─────────────────────────────────────────────────
p1="$(main_pid)"
docker exec "$NAME" kill -9 "$p1"
p2=""
for _ in $(seq 1 30); do
  sleep 1
  p2="$(main_pid)"
  [ -n "$p2" ] && [ "$p2" != "0" ] && [ "$p2" != "$p1" ] && [ -n "$(health 47321)" ] && break
done
if [ -n "$p2" ] && [ "$p2" != "0" ] && [ "$p2" != "$p1" ] && [ -n "$(health 47321)" ]; then ok "killed outright (pid $p1), the daemon is started again by systemd (pid $p2)"; else bad "the daemon wasn't restarted after kill -9 (pid $p1, now '$p2')" "$(user_systemctl status hypernova.service 2>&1 | head -20)"; fi
expect "systemd counts the restart" "$(user_systemctl show hypernova.service --property=NRestarts --value 2>&1)" '^[1-9]'

# ── A session the daemon started outlives its restart and an update ────────
TOKEN="$(as_ada cat .local/share/hypernova/admin-token)"
made="$(docker exec "$NAME" curl -sS --max-time 15 -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' -d '{"projectPath":"/home/ada/proj","prompt":"systemd-hello"}' http://127.0.0.1:47321/v1/sessions 2>&1 || true)"
sid="$(printf '%s' "$made" | json "d['session']['id']" 2>/dev/null || true)"
[ -n "$sid" ] && ok "a session starts in a shared terminal of the daemon's" || bad "the session didn't start" "$made"
holder=""
for _ in $(seq 1 20); do
  holder="$(as_ada sh -c 'cat ~/.local/share/hypernova/terms/*.json 2>/dev/null' | json "d['pid']" 2>/dev/null || true)"
  [ -n "$holder" ] && break
  sleep 0.5
done
in_service="$(docker exec "$NAME" cat "/proc/${holder:-0}/cgroup" 2>/dev/null || true)"
expect "its holder (pid ${holder:-none}) runs in the service's own cgroup" "$in_service" 'hypernova\.service'
# What the holder shows anyone joining: the stand-in, run through node-pty with the phone's prompt.
probe='
const { readdirSync } = await import("node:fs")
const { connect } = await import("node:net")
const shared = await import(process.env.HOME + "/.local/share/hypernova/app/daemon/src/term/shared.ts")
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
NODE="$(as_ada cat .local/share/hypernova/node-path)"
screen() { as_ada sh -c "cd ~/.local/share/hypernova/app/daemon && \"$NODE\" --input-type=module -e '$probe'" 2>&1 || true; }
# The screen is 80 columns wide, so the stand-in's line wraps: its start and the prompt apart.
expect "the holder's screen shows the stand-in Claude Code with the prompt" "$(screen)" 'stand-in claude: --session-id' 'systemd-hello'
terms() { as_ada "$HN" status 2>&1 | sed -n 's/^ *Sessions *//p'; }
found=""
for _ in $(seq 1 20); do found="$(terms)"; printf '%s' "$found" | grep -q '1 in shared terminals' && break; sleep 0.5; done
expect "the daemon lists the session in its shared terminal" "$found" '1 in shared terminals'

user_systemctl restart hypernova.service
wait_health 30 || true
if [ -n "$holder" ] && alive "$holder"; then ok "systemctl --user restart leaves the holder running"; else bad "the holder ended with the service's restart"; fi
found=""
for _ in $(seq 1 20); do found="$(terms)"; printf '%s' "$found" | grep -q '1 in shared terminals' && break; sleep 0.5; done
expect "the restarted daemon finds the session in its shared terminal again" "$found" '1 in shared terminals'
printf '%s' "$found" | grep -q '1 in shared terminals' || as_ada "$HN" logs -n 30 2>&1 | sed 's/^/      log: /'

upd="$(as_ada bash -c 'hypernova-*/scripts/install-linux.sh --yes' 2>&1)" && rc=0 || rc=$?
[ "$rc" -eq 0 ] && ok "the install run again (an update) exits 0" || bad "the update exited $rc" "$upd"
expect "the update swaps the daemon in and restarts the service" "$upd" '✓ The daemon installed in ~/\.local/share/hypernova/app/daemon' '✓ The service: hypernova\.service, running'
if [ -n "$holder" ] && alive "$holder"; then ok "the update leaves the holder running"; else bad "the holder ended with the update"; fi
found=""
for _ in $(seq 1 20); do found="$(terms)"; printf '%s' "$found" | grep -q '1 in shared terminals' && break; sleep 0.5; done
expect "the updated daemon finds the session again" "$found" '1 in shared terminals'
expect "and its screen is still there" "$(screen)" 'stand-in claude: --session-id' 'systemd-hello'

# ── A reboot, with nobody logged in ────────────────────────────────────────
docker restart "$NAME" >/dev/null
booted || bad "systemd didn't start again after the reboot"
if wait_health 90; then ok "after a reboot the daemon is back with nobody logged in"; else bad "the daemon didn't come back after a reboot" "$(docker exec "$NAME" loginctl list-users 2>&1)"; fi
expect "no login session was needed for that" "$(docker exec "$NAME" loginctl list-sessions --no-legend 2>&1 | grep -c ada || true)" '^0$'

# ── Uninstall ──────────────────────────────────────────────────────────────
un="$(as_ada "$HN" uninstall --yes 2>&1)" && rc=0 || rc=$?
[ "$rc" -eq 0 ] && ok "hypernova uninstall exits 0" || bad "hypernova uninstall exited $rc" "$un"
expect "uninstall removes the service and says lingering stays on" "$un" '✓ hypernova\.service stopped and removed' 'Lingering stays on for ada .*sudo loginctl disable-linger ada'
sleep 1
if [ -z "$(health 47322)" ]; then ok "nothing answers on the daemon's ports any more"; else bad "the daemon still answers after uninstall"; fi
gone="$(as_ada sh -c 'ls ~/.config/systemd/user/hypernova.service ~/.local/bin/hypernova ~/.local/share/hypernova/app 2>&1; ls ~/.local/share/hypernova' 2>&1 || true)"
expect "the unit, the command and the daemon are gone; the state stays" "$gone" "hypernova\\.service'?: No such file" "bin/hypernova'?: No such file" "app'?: No such file" 'admin-token'
expect "systemd no longer knows the service" "$(user_systemctl is-enabled hypernova.service 2>&1 || true)" 'not-found|No such file|does not exist'

echo
echo "$PASS passed, $FAIL failed."
[ "$FAIL" -eq 0 ]
