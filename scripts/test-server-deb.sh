#!/usr/bin/env bash
# The server's .deb, end to end, in Docker: a machine with systemd as its first process and a normal
# user with sudo, with neither curl nor a compiler, so the package's Depends have to bring them. For
# each image it installs the package with apt, then checks that:
#   - installing touches no service and no home folder, and says what's next;
#   - root is refused, and a user's hypernova-server-setup does the normal per-user install from it,
#     asking sudo for no package;
#   - upgrading the package leaves the running server and a session's holder as they are, and setup
#     then moves that user's copy on, with the holder still running;
#   - setup won't go back from a server that's newer than the package, unless --force;
#   - removing and purging take the system's files and leave the running server, its state and its
#     session alone; `hypernova uninstall` still works afterwards.
# Claude Code is a stand-in that prints its arguments and waits: nothing signs in. The container is
# privileged, because systemd manages its own cgroups (in a cgroup namespace of its own, so it never
# sees this machine's), and publishes nothing to the network.
#
#   scripts/test-server-deb.sh [--deb <new.deb>] [--keep] [image ...]      default: ubuntu:24.04 debian:12 debian:13
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEEP=0
NEW=""
IMAGES=()
while [ $# -gt 0 ]; do
  case "$1" in
    --keep) KEEP=1; shift ;;
    --deb) NEW="${2:?--deb needs a file}"; shift 2 ;;
    -*) echo "test-server-deb.sh: unknown option $1" >&2; exit 2 ;;
    *) IMAGES+=("$1"); shift ;;
  esac
done
[ ${#IMAGES[@]} -gt 0 ] || IMAGES=(ubuntu:24.04 debian:12 debian:13)

PASS=0 FAIL=0
ok() { PASS=$((PASS + 1)); printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf '  \033[31m✗\033[0m %s\n' "$1"; [ -n "${2:-}" ] && printf '%s\n' "$2" | sed 's/^/      /' | tail -40; return 0; }
# expect <what> <text> <pattern...>: every pattern (grep -E) is in the text.
expect() {
  local what="$1" text="$2" missing=""
  shift 2
  for p in "$@"; do printf '%s' "$text" | grep -qE -- "$p" || missing="$missing [$p]"; done
  if [ -z "$missing" ]; then ok "$what"; else bad "$what: missing$missing" "$text"; fi
}
refuse() {
  local what="$1" text="$2" p="$3"
  if printf '%s' "$text" | grep -qE -- "$p"; then bad "$what: found [$p]" "$text"; else ok "$what"; fi
}

ARCH="$(dpkg --print-architecture)"
[ -n "$NEW" ] || NEW="$("$ROOT/scripts/pack-deb.sh" "$ARCH" | tail -n 1)"
OLD="$("$ROOT/scripts/pack-deb.sh" "$ARCH" --version 0.0.1 | tail -n 1)"
NEW_VERSION="$(dpkg-deb -f "$NEW" Version)"

# ── The file itself ────────────────────────────────────────────────────────
echo "The package ($(basename "$NEW"))"
expect "its control fields" "$(dpkg-deb -f "$NEW")" '^Package: hypernova-server$' "^Architecture: $ARCH\$" '^Depends: .*build-essential' '^Depends: .*curl' '^Depends: .*lsof'
listing="$(dpkg-deb -c "$NEW")"
expect "its files: the release in /usr/lib, the setup command, all root's" "$listing" \
  'root/root .* \./usr/lib/hypernova-server/daemon/src/main\.ts$' 'root/root .* \./usr/lib/hypernova-server/scripts/install-linux\.sh$' \
  '^-rwxr-xr-x root/root .* \./usr/bin/hypernova-server-setup$' '\./usr/lib/hypernova-server/LICENSE$' '\./usr/share/doc/hypernova-server/copyright$'
refuse "nothing outside those folders, no node_modules" "$listing" '\./(etc|home|root|var|lib/systemd|usr/lib/systemd)/|node_modules'
refuse "no file owned by the packer" "$listing" "$(id -un)/"

one_image() {
  local IMAGE="$1" TAG NAME
  TAG="hypernova-deb-test-$(printf '%s' "$IMAGE" | tr -c 'a-z0-9' '-')"
  NAME="hypernova-deb-$$-$(printf '%s' "$IMAGE" | tr -c 'a-z0-9' '-')"
  echo
  echo "$IMAGE"
  docker build -q -t "$TAG" - >/dev/null <<DOCKERFILE
FROM $IMAGE
ARG DEBIAN_FRONTEND=noninteractive
ENV PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
RUN apt-get update && apt-get install -y --no-install-recommends systemd systemd-sysv dbus dbus-user-session libpam-systemd sudo && rm -rf /var/lib/apt/lists/*
RUN useradd --create-home --shell /bin/bash ada && echo 'ada ALL=(ALL) NOPASSWD: ALL' > /etc/sudoers.d/ada && chmod 440 /etc/sudoers.d/ada
STOPSIGNAL SIGRTMIN+3
CMD ["/sbin/init"]
DOCKERFILE
  docker run -d --name "$NAME" --hostname deb-test --privileged --cgroupns=private --tmpfs /run --tmpfs /run/lock "$TAG" >/dev/null
  # shellcheck disable=SC2064
  trap "[ $KEEP -eq 1 ] && echo 'Kept: docker exec -it -u ada $NAME bash' || docker rm -f '$NAME' >/dev/null 2>&1 || true" RETURN

  local s="" i
  for i in $(seq 1 90); do
    s="$(docker exec "$NAME" systemctl is-system-running 2>/dev/null || true)"
    case "$s" in running|degraded) break ;; esac
    sleep 1
  done
  case "$s" in running|degraded) ok "systemd runs as the machine's first process ($s)" ;; *) bad "systemd didn't finish starting ($s)"; return 0 ;; esac

  as_ada() { docker exec -u ada -w /home/ada "$NAME" "$@"; }
  apt_do() { docker exec -e DEBIAN_FRONTEND=noninteractive "$NAME" apt-get "$@" 2>&1; }
  health() { docker exec "$NAME" curl -fsS --max-time 2 "http://127.0.0.1:$1/v1/health" 2>/dev/null || true; }
  wait_health() { for _ in $(seq 1 "${1:-60}"); do [ -n "$(health 47321)" ] && [ -n "$(health 47322)" ] && return 0; sleep 1; done; return 1; }
  user_systemctl() { as_ada env XDG_RUNTIME_DIR="/run/user/$(docker exec "$NAME" id -u ada)" systemctl --user "$@"; }
  main_pid() { user_systemctl show hypernova.service --property=MainPID --value 2>/dev/null | tr -d '\r' || true; }
  alive() { docker exec "$NAME" kill -0 "$1" 2>/dev/null; }
  version_of() { docker exec "$NAME" sed -n 's/^ *"version": *"\([^"]*\)".*/\1/p' "$1" 2>/dev/null | head -n 1; }
  local HN=/home/ada/.local/bin/hypernova STATE=/home/ada/.local/share/hypernova

  # /var/tmp: Debian 13 mounts a tmpfs on /tmp when it boots, which docker cp doesn't see.
  docker cp "$OLD" "$NAME:/var/tmp/old.deb" >/dev/null
  docker cp "$NEW" "$NAME:/var/tmp/new.deb" >/dev/null

  # ── Installing the package ─────────────────────────────────────────────
  local before after out rc
  docker exec "$NAME" sh -c 'for c in curl g++ make git python3 lsof; do command -v $c; done; true' | grep -q . && bad "the image should have none of what Depends brings" || ok "before the package: no curl, compiler, git, python3 or lsof"
  before="$(docker exec "$NAME" find /home /root -type f | sort)"
  apt_do update -qq >/dev/null
  out="$(apt_do install -y -q --no-install-recommends /var/tmp/old.deb)" && rc=0 || rc=$?
  [ "$rc" -eq 0 ] && ok "apt installs it, with its Depends" || { bad "apt install exited $rc" "$out"; return 0; }
  expect "its postinst says what's next" "$out" "Hypernova's server is unpacked, not set up yet" 'hypernova-server-setup'
  after="$(docker exec "$NAME" find /home /root -type f | sort)"
  [ "$before" = "$after" ] && ok "no file changed in any home folder" || bad "installing changed a home folder" "$(diff <(printf '%s\n' "$before") <(printf '%s\n' "$after") || true)"
  refuse "no service for anyone" "$(docker exec "$NAME" sh -c 'find /etc/systemd /usr/lib/systemd /lib/systemd -name "*hypernova*" 2>/dev/null; dpkg -L hypernova-server | grep -E "systemd|^/etc|^/home" || true')" 'hypernova|systemd|/etc|/home'
  expect "and everything Depends promised is there" "$(docker exec "$NAME" sh -c 'for c in curl g++ make git python3 lsof ps openssl; do command -v $c; done')" 'curl' 'g\+\+' 'make' 'git' 'python3' 'lsof' 'ps' 'openssl'

  # ── Setup, as a user ───────────────────────────────────────────────────
  out="$(docker exec "$NAME" hypernova-server-setup --yes 2>&1)" && rc=0 || rc=$?
  [ "$rc" -ne 0 ] && ok "as root it refuses" || bad "root wasn't refused" "$out"
  expect "and says who should run it" "$out" 'run it as the user who will run the agents'
  # The stand-in for Claude Code (scripts/test-linux-systemd.sh's), where Anthropic's installer puts it.
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
exec sleep 3000
CLAUDE
  # Not GitHub from a test machine: no looking for releases. The stand-in is no `claude agents`: shared terminals.
  as_ada sh -c "mkdir -p $STATE && printf '{\"releaseCheck\":false,\"autoUpdate\":false,\"claudeAgents\":false}\n' > $STATE/settings.json"
  out="$(as_ada hypernova-server-setup --yes 2>&1)" && rc=0 || rc=$?
  [ "$rc" -eq 0 ] && ok "hypernova-server-setup exits 0 as ada" || bad "hypernova-server-setup exited $rc" "$out"
  expect "it downloads Node.js, builds the terminal module, finds Claude Code and starts the service" "$out" \
    'Downloading Node\.js v24\.' '✓ The daemon installed in ~/\.local/share/hypernova/app/daemon · shared terminals ready \(terminal module built\)' \
    '✓ Claude Code 2\.1\.0 · ~/\.local/bin/claude' '✓ The service: hypernova\.service, running, starts at boot with nobody logged in' 'Pair your phone: hypernova pair'
  refuse "Depends covered it: no package asked of sudo" "$out" 'Installing system packages'
  local unit
  unit="$(as_ada cat .config/systemd/user/hypernova.service 2>&1)"
  expect "the service runs the user's own copy" "$unit" "\"$STATE/app/daemon/src/main\\.ts\"" '^KillMode=process$'
  refuse "not the package's" "$unit" '/usr/lib/hypernova-server'
  expect "it counts as a package's install, so it can update itself" "$(as_ada cat "$STATE/app/daemon/install.json")" '"source":"package"' '"version":"0\.0\.1"'
  wait_health 30 || true
  expect "the phone API and the local API answer" "$(health 47321)$(health 47322)" '"ok":true.*"ok":true'
  expect "status says it runs" "$(as_ada "$HN" status 2>&1 || true)" 'Daemon +running \(pid [0-9]+\), hypernova\.service active, starts at boot'

  # A session of the daemon's, in its holder: what an upgrade or a removal must never end.
  local token made holder="" pid1 pid2
  token="$(as_ada cat "$STATE/admin-token")"
  made="$(docker exec "$NAME" curl -sS --max-time 15 -H "authorization: Bearer $token" -H 'content-type: application/json' -d '{"projectPath":"/home/ada/proj","prompt":"deb-hello"}' http://127.0.0.1:47321/v1/sessions 2>&1 || true)"
  for _ in $(seq 1 20); do
    holder="$(as_ada sh -c "cat $STATE/terms/*.json 2>/dev/null" | grep -o '"pid": *[0-9]*' | head -n 1 | grep -o '[0-9]*$' || true)"
    [ -n "$holder" ] && break
    sleep 0.5
  done
  [ -n "$holder" ] && ok "a session runs in a holder (pid $holder)" || bad "no holder" "$made"
  pid1="$(main_pid)"
  local tokensum
  tokensum="$(docker exec "$NAME" sha256sum "$STATE/admin-token")"

  # ── Upgrading the package ──────────────────────────────────────────────
  out="$(apt_do install -y -q --no-install-recommends /var/tmp/new.deb)" && rc=0 || rc=$?
  [ "$rc" -eq 0 ] && ok "apt upgrades it to $NEW_VERSION" || bad "the upgrade exited $rc" "$out"
  expect "its postinst says running servers aren't touched" "$out" 'is upgraded\. Servers already running'
  [ "$(version_of /usr/lib/hypernova-server/daemon/package.json)" = "$NEW_VERSION" ] && ok "the system's copy is the new one" || bad "the system's copy is $(version_of /usr/lib/hypernova-server/daemon/package.json)"
  [ "$(version_of "$STATE/app/daemon/package.json")" = 0.0.1 ] && ok "the user's copy isn't touched" || bad "the user's copy changed"
  [ "$(main_pid)" = "$pid1" ] && [ -n "$(health 47322)" ] && ok "the same daemon keeps running and answering (pid $pid1)" || bad "the daemon changed: $pid1 is now $(main_pid)"
  [ -n "$holder" ] && alive "$holder" && ok "and so does the holder" || bad "the holder ended with the upgrade"

  out="$(as_ada hypernova-server-setup --yes 2>&1)" && rc=0 || rc=$?
  [ "$rc" -eq 0 ] && ok "setup again exits 0" || bad "setup again exited $rc" "$out"
  [ "$(version_of "$STATE/app/daemon/package.json")" = "$NEW_VERSION" ] && ok "and moves the user's copy to $NEW_VERSION" || bad "the user's copy is $(version_of "$STATE/app/daemon/package.json")"
  wait_health 30 || true
  pid2="$(main_pid)"
  [ "$pid2" != "$pid1" ] && [ -n "$(health 47322)" ] && ok "a new daemon answers (pid $pid2)" || bad "no new daemon: $pid2"
  [ -n "$holder" ] && alive "$holder" && ok "the holder keeps running through that" || bad "the holder ended with setup"
  [ "$(docker exec "$NAME" sha256sum "$STATE/admin-token")" = "$tokensum" ] && ok "the server's secrets are the same" || bad "the admin token changed"

  # ── Never back by accident ─────────────────────────────────────────────
  apt_do install -y -q --allow-downgrades --no-install-recommends /var/tmp/old.deb >/dev/null || bad "apt wouldn't downgrade"
  out="$(as_ada hypernova-server-setup --yes 2>&1)" && rc=0 || rc=$?
  [ "$rc" -eq 0 ] && expect "setup won't go back from a newer server" "$out" "already on $NEW_VERSION, newer than this package \\(0\\.0\\.1\\)" '--force' || bad "the newer server: exit $rc" "$out"
  [ "$(version_of "$STATE/app/daemon/package.json")" = "$NEW_VERSION" ] && [ "$(main_pid)" = "$pid2" ] && ok "and nothing changed" || bad "it went back"
  apt_do install -y -q --no-install-recommends /var/tmp/new.deb >/dev/null || bad "apt wouldn't upgrade again"

  # ── Removing it ────────────────────────────────────────────────────────
  out="$(apt_do remove -y -q hypernova-server)" && rc=0 || rc=$?
  [ "$rc" -eq 0 ] && ok "apt removes it" || bad "remove exited $rc" "$out"
  expect "its postrm says the server stays, and how to take it out" "$out" 'package is gone\. A server you set up from it keeps running' 'hypernova uninstall'
  refuse "the system's files are gone" "$(docker exec "$NAME" sh -c 'ls -d /usr/lib/hypernova-server /usr/bin/hypernova-server-setup 2>/dev/null || true')" 'hypernova'
  [ "$(main_pid)" = "$pid2" ] && [ -n "$(health 47322)" ] && ok "the running server isn't touched" || bad "the daemon went with the package"
  [ -n "$holder" ] && alive "$holder" && ok "nor the session" || bad "the holder ended with the removal"
  expect "the hypernova command still works" "$(as_ada "$HN" status 2>&1 || true)" 'Daemon +running'
  [ "$(docker exec "$NAME" sha256sum "$STATE/admin-token")" = "$tokensum" ] && ok "the state stays" || bad "the state changed"

  apt_do install -y -q --no-install-recommends /var/tmp/new.deb >/dev/null || bad "apt wouldn't install it again"
  out="$(apt_do purge -y -q hypernova-server)" && rc=0 || rc=$?
  [ "$rc" -eq 0 ] && ok "apt purges it" || bad "purge exited $rc" "$out"
  refuse "nothing of it is left to dpkg" "$(docker exec "$NAME" dpkg -l hypernova-server 2>&1 | grep '^[a-z][a-z] ' || true)" '^[a-z]i |^rc '
  [ "$(main_pid)" = "$pid2" ] && [ -n "$(health 47322)" ] && ok "purging leaves the running server alone too" || bad "the daemon went with the purge"
  [ "$(docker exec "$NAME" sha256sum "$STATE/admin-token")" = "$tokensum" ] && ok "and every user's state" || bad "purge took the state"

  # ── And the user's own way out ─────────────────────────────────────────
  out="$(as_ada "$HN" uninstall --yes 2>&1)" && rc=0 || rc=$?
  [ "$rc" -eq 0 ] && ok "hypernova uninstall still works with the package gone" || bad "uninstall exited $rc" "$out"
  expect "paired phones and settings stay" "$(as_ada ls "$STATE" 2>&1)" 'admin-token'
  [ -z "$(health 47322)" ] && ok "nothing answers any more" || bad "the daemon still answers"
}

for image in "${IMAGES[@]}"; do one_image "$image"; done

echo
echo "$PASS passed, $FAIL failed."
[ "$FAIL" -eq 0 ]
