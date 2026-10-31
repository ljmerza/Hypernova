#!/usr/bin/env bash
# The server's own update, step 4 (daemon/src/server-update.ts; docs/LINUX.md, "Updating"). The
# daemon runs a copy of this in a transient systemd unit of its own (systemd-run --user), outside
# its service, so the service's restart doesn't end it:
#
#   server-update.sh <unpacked package> <tag> <result file> <app dir> <local port> <log file> <daemon pid>
#
# 1. Keeps a copy of the installed daemon (<app>/daemon.rollback) to go back to.
# 2. Installs the package as `scripts/install-linux.sh --from <package>` does, without questions and
#    without installing anything new (--yes --no-claude): setup prepares the new daemon beside the
#    old one, swaps it in only once its terminal module works, writes the service again and restarts
#    it. Paired phones, settings and keys stay where they are.
# 3. Checks the new daemon answers (60 s); a service that kept the old process is restarted. If the
#    new one was swapped in but doesn't answer, the copy goes back and the service restarts on it.
# 4. Writes {"tag","code","at"} to the result file: 0 updated, 3 no copy could be kept (nothing
#    changed), 4 went back to the old daemon, anything else the install's own exit (nothing changed).
set -u
SRC=$1 TAG=$2 RESULT=$3 APP=$4 PORT=$5 LOG=$6 OLDPID=${7:-0}
exec >>"$LOG" 2>&1 </dev/null
code=99
finish() {
  printf '{"tag":"%s","code":%s,"at":%s000}\n' "$TAG" "$code" "$(date +%s)" >"$RESULT.tmp" && mv -f "$RESULT.tmp" "$RESULT"
}
trap finish EXIT
say() { printf '%s %s\n' "$(date -Is)" "$*"; }
version_in() { sed -n 's/^ *"version": *"\([^"]*\)".*/\1/p' "$1/package.json" 2>/dev/null | head -n 1; }
answers() {
  local i
  for i in $(seq 60); do
    curl -fsS -m 2 "http://127.0.0.1:$PORT/v1/health" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}
main_pid() { systemctl --user show hypernova.service --property=MainPID --value 2>/dev/null; }

want=${TAG#v}
had=$(version_in "$APP/daemon")
say "updating Hypernova's server from ${had:-?} to $want ($SRC)"

rm -rf "$APP/daemon.rollback"
if ! cp -a "$APP/daemon" "$APP/daemon.rollback"; then
  say "couldn't keep a copy of the daemon to go back to; nothing changed"
  rm -rf "$APP/daemon.rollback"
  code=3
  exit
fi

bash "$SRC/scripts/install-linux.sh" --from "$SRC" --yes --no-claude
rc=$?
now=$(version_in "$APP/daemon")

if [ "$now" != "$want" ]; then
  # Setup stopped before the swap: the running daemon and its files are as they were.
  rm -rf "$APP/daemon.rollback"
  say "the install stopped before the new daemon went in (exit $rc); nothing changed"
  code=$rc
  [ "$code" -eq 0 ] && code=1
  exit
fi

# In. A service still on the old process (setup couldn't restart it) is restarted now.
if [ "$(main_pid)" = "$OLDPID" ]; then
  say "the service still runs the old daemon (pid $OLDPID): restarting it"
  systemctl --user restart hypernova.service || true
fi
if answers && [ "$(main_pid)" != "$OLDPID" ]; then
  rm -rf "$APP/daemon.rollback"
  say "updated to $want (setup exit $rc)"
  code=0
  exit
fi

say "the new daemon doesn't answer; going back to ${had:-the old one}"
rm -rf "$APP/daemon.failed"
if mv "$APP/daemon" "$APP/daemon.failed" && mv "$APP/daemon.rollback" "$APP/daemon"; then
  rm -rf "$APP/daemon.failed"
else
  say "couldn't put the old daemon back: see $APP"
fi
code=4
# The verdict before the restart: the old daemon reads it as it starts again.
finish
trap - EXIT
systemctl --user restart hypernova.service
