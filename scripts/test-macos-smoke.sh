#!/usr/bin/env bash
# Hypernova's server on a real Mac, end to end. This is the one test that runs on macOS: CI's Mac
# runners run it (.github/workflows/server-packages.yml). Nobody should run it on a Mac they use: it
# installs the real service under its real label, and removes it again.
#
#   scripts/test-macos-smoke.sh --tarball <hypernova-server-…-macos-<arch>.tar.gz>
#   scripts/test-macos-smoke.sh --pkg <hypernova-server-…-macos-<arch>.pkg>       (sudo; run after --tarball)
#
# It installs with a clean environment (no Homebrew, no runner's Node on PATH), as a fresh Mac would,
# with a stand-in for Claude Code that prints its arguments and waits: nothing signs in, and nothing
# is published. Then it checks, against launchd itself:
#   - the install downloads Node.js and loads node-pty's prebuilt binary for this processor;
#   - the plist is valid (plutil), the agent is loaded in gui/<uid>, and launchd's words are what
#     daemon/src/cli/launchd.ts reads (`status`, `doctor`);
#   - the API answers on loopback only; a pairing code is made (LibreSSL's openssl makes the certs);
#   - a session's holder survives `launchctl kickstart -k`, a kill -9 (KeepAlive brings the daemon
#     back) and running the install again, and the new daemon finds the session;
#   - `hypernova auto-update` says a Mac doesn't update itself;
#   - `hypernova uninstall` takes out the agent, the command and the shell block, and keeps the state.
# With no login session at the screen on the runner there's no gui domain: setup then only warns, and
# this says loudly (::warning) that the LaunchAgent went untested instead of pretending.
# Bash 3.2, as a Mac ships.
set -u

MODE="${1:-}"
FILE="${2:-}"
case "$MODE" in
  --tarball|--pkg) [ -f "$FILE" ] || { echo "no file $FILE" >&2; exit 2; } ;;
  *) echo "usage: test-macos-smoke.sh --tarball <file> | --pkg <file>" >&2; exit 2 ;;
esac
[ "$(uname -s)" = Darwin ] || { echo "test-macos-smoke.sh: this runs on a Mac (scripts/test-install-macos.sh is what runs elsewhere)." >&2; exit 2; }

LABEL=com.prudnikweb.hypernova.daemon
UIDN="$(id -u)"
TARGET="gui/$UIDN/$LABEL"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
STATE="$HOME/Library/Application Support/Hypernova"
LOGS="$HOME/Library/Logs/Hypernova"
HN="$HOME/.local/bin/hypernova"
# A fresh Mac: the system's folders and nothing else.
CLEAN=(env -i HOME="$HOME" USER="$(id -un)" LOGNAME="$(id -un)" PATH=/usr/bin:/bin:/usr/sbin:/sbin LANG=en_US.UTF-8)

PASS=0 FAIL=0
ok() { PASS=$((PASS + 1)); printf '  ok   %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf '  FAIL %s\n' "$1"; [ -n "${2:-}" ] && printf '%s\n' "$2" | sed 's/^/       /' | tail -40; return 0; }
expect() {
  local what="$1" text="$2" missing=""
  shift 2
  for p in "$@"; do printf '%s' "$text" | grep -qE -- "$p" || missing="$missing [$p]"; done
  if [ -z "$missing" ]; then ok "$what"; else bad "$what: missing$missing" "$text"; fi
}
refuse() {
  if printf '%s' "$2" | grep -qE -- "$3"; then bad "$1: found [$3]" "$2"; else ok "$1"; fi
}
health() { curl -fsS --max-time 2 "http://127.0.0.1:$1/v1/health" 2>/dev/null || true; }
wait_health() { local i; for i in $(seq 1 "${1:-60}"); do [ -n "$(health 47321)" ] && [ -n "$(health 47322)" ] && return 0; sleep 1; done; return 1; }
agent_pid() { launchctl print "$TARGET" 2>/dev/null | sed -n 's/^[[:space:]]*pid = \([0-9][0-9]*\).*/\1/p' | head -n 1; }
alive() { [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null; }
hn() { "${CLEAN[@]}" "$HN" "$@"; }

dump() {
  echo "--- what the service left, for the failure above ---"
  launchctl print "$TARGET" 2>&1 | head -40
  [ -f "$LOGS/daemon.stderr.log" ] && { echo "--- daemon.stderr.log"; tail -30 "$LOGS/daemon.stderr.log"; }
  [ -f "$LOGS/daemon.log" ] && { echo "--- daemon.log"; tail -40 "$LOGS/daemon.log"; }
}

echo "macOS $(sw_vers -productVersion) on $(uname -m), bash $BASH_VERSION, $(id -un), login shell $(dscl . -read "/Users/$(id -un)" UserShell 2>/dev/null | awk '{print $2}')"
echo "launchd manager: $(launchctl managername 2>&1)"
if launchctl print "gui/$UIDN" >/dev/null 2>&1; then GUI=1; ok "this runner has a login session at the screen (gui/$UIDN)"; else GUI=0; echo "::warning::No gui/$UIDN domain on this runner: the LaunchAgent can't be tested here."; launchctl print "gui/$UIDN" 2>&1 | head -3; fi

# The stand-in for Claude Code, where Anthropic's installer puts it (test-linux-systemd.sh's, signed in).
mkdir -p "$HOME/.local/bin" "$HOME/proj"
cat > "$HOME/.local/bin/claude" <<'CLAUDE'
#!/bin/sh
case "$1" in
  --version) echo "2.1.0 (Claude Code stand-in)"; exit 0 ;;
  auth) echo '{"loggedIn":true,"subscriptionType":"max"}'; exit 0 ;;
esac
sid="" prev=""
for a in "$@"; do [ "$prev" = "--session-id" ] && sid="$a"; prev="$a"; done
[ -n "$sid" ] || exit 0
mkdir -p "$HOME/.claude/sessions"
printf '{"pid":%s,"sessionId":"%s","cwd":"%s","startedAt":%s000,"kind":"interactive","status":"idle"}\n' "$$" "$sid" "$PWD" "$(date +%s)" > "$HOME/.claude/sessions/$$.json"
echo "stand-in claude: $*"
exec sleep 1800
CLAUDE
chmod +x "$HOME/.local/bin/claude"
# Not GitHub from a test: no looking for releases. The stand-in is no `claude agents`: shared terminals.
mkdir -p "$STATE"
chmod 700 "$STATE"
printf '{"releaseCheck":false,"claudeAgents":false}\n' > "$STATE/settings.json"

# ── Install ────────────────────────────────────────────────────────────────
if [ "$MODE" = --tarball ]; then
  WORK="$(mktemp -d)"
  tar -xzf "$FILE" -C "$WORK"
  INSTALL=("$WORK"/hypernova-*/scripts/install-macos.sh)
  [ -x "${INSTALL[0]}" ] && ok "the tarball holds an executable install-macos.sh" || bad "no install-macos.sh in the tarball" "$(tar -tzf "$FILE" | head -20)"
  refuse "no install-linux.sh beside it" "$(tar -tzf "$FILE")" 'install-linux\.sh'
  OUT="$("${CLEAN[@]}" /bin/bash "${INSTALL[0]}" --yes 2>&1 </dev/null)"; RC=$?
else
  sudo installer -pkg "$FILE" -target / 2>&1 | tail -3
  [ "${PIPESTATUS[0]}" -eq 0 ] && ok "installer takes the .pkg" || bad "installer refused the .pkg"
  expect "pkgutil knows it" "$(pkgutil --pkg-info com.prudnikweb.hypernova.server 2>&1)" 'package-id: com\.prudnikweb\.hypernova\.server'
  [ -x /usr/local/bin/hypernova-server-setup ] && [ -f /usr/local/lib/hypernova-server/daemon/src/main.ts ] && ok "the setup command and the release are in /usr/local" || bad "the .pkg's files aren't there"
  [ ! -e "$PLIST" ] && ok "installing the .pkg loaded no service" || bad "the .pkg left a plist"
  [ -z "$(health 47322)" ] && ok "and nothing answers yet" || bad "something answers before setup"
  OUT="$("${CLEAN[@]}" /usr/local/bin/hypernova-server-setup --yes 2>&1 </dev/null)"; RC=$?
fi
[ "$RC" -eq 0 ] && ok "the install exits 0" || bad "the install exited $RC" "$OUT"
printf '%s\n' "$OUT" | sed 's/^/    | /'
expect "it fetched Node.js for this Mac and readied the terminal module without compiling" "$OUT" 'Downloading Node\.js v24\.' '✓ The daemon installed in ~/Library/Application Support/Hypernova/app/daemon · shared terminals ready' '✓ Claude Code 2\.1\.0 · ~/\.local/bin/claude'
NODE_BIN="$(cat "$STATE/node-path" 2>/dev/null)"
expect "Node.js is in Hypernova's own folder, and is this Mac's processor" "$NODE_BIN $(file "$NODE_BIN" 2>/dev/null)" 'Application Support/Hypernova/node/v24\.' "Mach-O 64-bit executable $(uname -m)"
[ -f "$STATE/app/daemon/node_modules/node-pty/prebuilds/darwin-$(uname -m | sed 's/x86_64/x64/')/pty.node" ] && ok "node-pty's prebuilt binary is there" || bad "no prebuilt node-pty" "$(ls "$STATE/app/daemon/node_modules/node-pty/prebuilds" 2>&1)"
[ ! -d "$STATE/app/daemon/node_modules/node-pty/build" ] && ok "nothing was compiled: Apple's Command Line Tools aren't needed for it" || bad "node-pty was built here, so a Mac without Xcode's tools would have failed"
[ -x "$STATE/app/daemon/node_modules/node-pty/prebuilds/darwin-$(uname -m | sed 's/x86_64/x64/')/spawn-helper" ] && ok "spawn-helper is executable (the daemon's postinstall)" || bad "spawn-helper isn't executable"

# ── The agent ──────────────────────────────────────────────────────────────
if [ "$GUI" -eq 1 ]; then
  expect "setup reports the service" "$OUT" "✓ The service: $LABEL, running, starts when you log in"
  plutil -lint "$PLIST" >/dev/null 2>&1 && ok "plutil accepts the plist" || bad "plutil refuses the plist" "$(plutil -lint "$PLIST" 2>&1)"
  for k in RunAtLoad KeepAlive AbandonProcessGroup; do
    [ "$(plutil -extract "$k" raw "$PLIST" 2>/dev/null)" = true ] && ok "the plist has $k" || bad "the plist lacks $k"
  done
  PRINT="$(launchctl print "$TARGET" 2>&1)"
  printf '%s\n' "$PRINT" | head -30 | sed 's/^/    | /'
  expect "launchd has it, running, in the words launchd.ts reads" "$PRINT" 'state = running' 'pid = [0-9]+' 'type = LaunchAgent'
  echo "    print-disabled: $(launchctl print-disabled "gui/$UIDN" 2>&1 | grep "$LABEL" || echo '(not listed)')"
  wait_health 30 || bad "the daemon doesn't answer after setup"
else
  # No agent to run it: the daemon the way setup says to start it.
  expect "setup says why there's no service" "$OUT" 'Nobody is logged in at this Mac'
  "${CLEAN[@]}" "$HN" run >"$LOGS.run.log" 2>&1 &
  RUN_PID=$!
  wait_health 60 || bad "hypernova run doesn't answer"
fi
expect "the phone API answers" "$(health 47321)" '"ok":true'
expect "the local API answers" "$(health 47322)" '"ok":true'
refuse "the API isn't on the network" "$(lsof -nP -iTCP:47321 -iTCP:47322 -sTCP:LISTEN 2>/dev/null | tail -n +2)" '\*:4732[12]'

STATUS="$(hn status 2>&1)"; RC=$?
printf '%s\n' "$STATUS" | sed 's/^/    | /'
[ "$RC" -eq 0 ] && ok "hypernova status exits 0" || bad "status exited $RC"
[ "$GUI" -eq 1 ] && expect "status names the agent, its pid and when it starts" "$STATUS" "Daemon +running \\(pid [0-9]+\\), $LABEL active, starts when you log in"
DOCTOR="$(hn doctor 2>&1)"; RC=$?
printf '%s\n' "$DOCTOR" | sed 's/^/    | /'
[ "$RC" -eq 0 ] && ok "hypernova doctor finds no problem" || bad "doctor exited $RC" "$DOCTOR"
expect "doctor knows this Mac" "$DOCTOR" '✓ macOS on (arm64|x64)' '✓ Shared terminals: node-pty works' '✓ Phone API and local API on loopback only' '✓ Claude Code signed in \(max\)'
[ "$GUI" -eq 1 ] && expect "doctor reads the agent" "$DOCTOR" "✓ $LABEL running \\(pid [0-9]+\\), starts when you log in" 'A Mac starts its LaunchAgent at login'
refuse "no word of systemd or a menu bar" "$STATUS$DOCTOR" 'systemctl|lingering|Lingering|menu bar|apt-get'
PAIR="$(hn pair --no-wait --no-qr 2>&1)"; RC=$?
[ "$RC" -eq 0 ] && ok "hypernova pair makes a code" || bad "pair exited $RC" "$PAIR"
[ -f "$STATE/tls/ca.crt" ] && ok "the system's openssl (LibreSSL) made the local route's certificates" || bad "no local route certificates" "$(ls -la "$STATE/tls" 2>&1)"
UPD="$(hn auto-update 2>&1)"
expect "auto-update says a Mac doesn't update itself, and how to" "$UPD" 'A Mac doesn’t update itself yet' 'get\.sh \| bash'
expect "the log is where it says" "$(hn logs -n 5 2>&1)" '.'

# The shell block, for the login shell it's written for.
if [ "$(dscl . -read "/Users/$(id -un)" UserShell 2>/dev/null | awk '{print $2}')" = /bin/zsh ]; then
  expect "the shell block is in ~/.zshrc, with ~/.local/bin" "$(cat "$HOME/.zshrc" 2>/dev/null)" '# >>> hypernova shared terminals' '\.local/bin:\$PATH' 'function claude'
  expect "a new zsh finds hypernova and claude" "$("${CLEAN[@]}" /bin/zsh -ic 'command -v hypernova; whence -w claude' 2>&1)" '\.local/bin/hypernova' 'claude: function'
else
  echo "    (login shell isn't zsh: no block to check)"
fi

# ── A session outlives the daemon ──────────────────────────────────────────
TOKEN="$(cat "$STATE/admin-token" 2>/dev/null)"
MADE="$(curl -sS --max-time 15 -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' -d "{\"projectPath\":\"$HOME/proj\",\"prompt\":\"mac-hello\"}" http://127.0.0.1:47321/v1/sessions 2>&1)"
HOLDER=""
for _ in $(seq 1 30); do
  HOLDER="$(cat "$STATE"/terms/*.json 2>/dev/null | grep -o '"pid": *[0-9]*' | head -n 1 | grep -o '[0-9]*$')"
  [ -n "$HOLDER" ] && break
  sleep 0.5
done
[ -n "$HOLDER" ] && ok "a session runs in a shared terminal's holder (pid $HOLDER)" || bad "no holder" "$MADE"
[ -n "$HOLDER" ] && echo "    holder's process group $(ps -o pgid= -p "$HOLDER"), daemon's $(ps -o pgid= -p "$(agent_pid)" 2>/dev/null)"
terms() { hn status 2>&1 | sed -n 's/^ *Sessions *//p'; }
found_session() {
  local i found=""
  for i in $(seq 1 30); do found="$(terms)"; printf '%s' "$found" | grep -q '1 in shared terminals' && { ok "$1"; return 0; }; sleep 1; done
  bad "$1" "$found"
}
found_session "the daemon lists it in its shared terminal"

if [ "$GUI" -eq 1 ]; then
  P1="$(agent_pid)"
  launchctl kickstart -k "$TARGET"
  for _ in $(seq 1 40); do P2="$(agent_pid)"; [ -n "$P2" ] && [ "$P2" != "$P1" ] && [ -n "$(health 47322)" ] && break; sleep 1; done
  [ -n "$P2" ] && [ "$P2" != "$P1" ] && [ -n "$(health 47322)" ] && ok "launchctl kickstart -k restarts the daemon ($P1 to $P2)" || bad "kickstart didn't restart it ($P1, now ${P2:-none})"
  alive "$HOLDER" && ok "the holder survives launchd's restart (AbandonProcessGroup, its own process group)" || bad "the holder ended with launchd's restart"
  found_session "the restarted daemon finds the session again"

  P3="$(agent_pid)"
  kill -9 "$P3"
  # ThrottleInterval: launchd waits up to 10 seconds to start it again.
  for _ in $(seq 1 60); do P4="$(agent_pid)"; [ -n "$P4" ] && [ "$P4" != "$P3" ] && [ -n "$(health 47322)" ] && break; sleep 1; done
  [ -n "$P4" ] && [ "$P4" != "$P3" ] && [ -n "$(health 47322)" ] && ok "killed outright, KeepAlive brings the daemon back ($P3 to $P4)" || bad "KeepAlive didn't bring it back ($P3, now ${P4:-none})"
  alive "$HOLDER" && ok "the holder survives the daemon's death" || bad "the holder ended with the daemon"

  # An update by hand: the install again, which boots the agent out and in.
  if [ "$MODE" = --tarball ]; then
    AGAIN="$("${CLEAN[@]}" /bin/bash "${INSTALL[0]}" --yes 2>&1 </dev/null)"; RC=$?
    [ "$RC" -eq 0 ] && ok "the install run again exits 0" || bad "the install again exited $RC" "$AGAIN"
    expect "and doesn't fetch Node.js twice" "$AGAIN" "✓ The service: $LABEL, running"
    refuse "it keeps the Node.js it has" "$AGAIN" 'Downloading Node\.js'
    wait_health 30 || bad "the daemon doesn't answer after the install again"
    alive "$HOLDER" && ok "the holder survives bootout, bootstrap and the daemon's swap" || bad "the holder ended with the update"
    found_session "the new daemon finds the session again"
  fi
else
  kill "$RUN_PID" 2>/dev/null
  wait "$RUN_PID" 2>/dev/null
  alive "$HOLDER" && ok "the holder survives the daemon's end (hypernova run: no launchd here)" || bad "the holder ended with the daemon"
fi

[ "$FAIL" -eq 0 ] || dump

# ── Uninstall ──────────────────────────────────────────────────────────────
UN="$(hn uninstall --yes 2>&1)"; RC=$?
printf '%s\n' "$UN" | sed 's/^/    | /'
[ "$RC" -eq 0 ] && ok "hypernova uninstall exits 0" || bad "uninstall exited $RC" "$UN"
if [ "$GUI" -eq 1 ]; then
  expect "it says so" "$UN" "✓ $LABEL stopped and removed"
  launchctl print "$TARGET" >/dev/null 2>&1 && bad "launchd still has the agent" || ok "launchd no longer has the agent"
fi
for _ in 1 2 3 4 5; do [ -z "$(health 47322)" ] && break; sleep 1; done
[ -z "$(health 47322)" ] && ok "nothing answers any more" || bad "the daemon still answers"
[ ! -e "$PLIST" ] && [ ! -e "$HN" ] && [ ! -e "$STATE/app" ] && ok "the plist, the command and the daemon are gone" || bad "something stayed" "$(ls -la "$PLIST" "$HN" "$STATE/app" 2>&1)"
[ -f "$STATE/admin-token" ] && ok "the state stays" || bad "the state went too"
refuse "the shell block is out" "$(cat "$HOME/.zshrc" 2>/dev/null)" 'hypernova shared terminals'
refuse "and it never mentions a menu bar" "$UN" 'menu bar|Lingering'

# Clean for the next run on this runner: the holder, the state, the .pkg's files.
[ -n "$HOLDER" ] && kill "$HOLDER" 2>/dev/null
pkill -f 'stand-in|sleep 1800' 2>/dev/null
"${CLEAN[@]}" /bin/rm -rf "$STATE" "$LOGS"
if [ "$MODE" = --pkg ]; then
  sudo rm -rf /usr/local/lib/hypernova-server /usr/local/bin/hypernova-server-setup
  sudo pkgutil --forget com.prudnikweb.hypernova.server >/dev/null 2>&1
fi

echo
echo "$PASS passed, $FAIL failed."
[ "$FAIL" -eq 0 ]
