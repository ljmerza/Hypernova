#!/usr/bin/env bash
# Takes the README's screenshots from the demo server (demo/README.md): starts the three made-up
# servers and an emulator, installs a debug build, pairs it with all three over the real mutual TLS
# route, walks to each screen, and writes docs/images/shot-*.jpg, without the system bars and without
# metadata. Nothing here touches a real Hypernova install.
#
#   scripts/demo-shots.sh                  every shot
#   scripts/demo-shots.sh home chat        just these (home chat changes all-servers statistics automations)
#   DEMO_KEEP=1 scripts/demo-shots.sh      leave the emulator and the servers running afterwards
#   DEMO_REBUILD=1 scripts/demo-shots.sh   build the APK even if there is one
#
# Needs the Android SDK and JDK 21 in ~/Android (docs/DEV-SETUP.md), an AVD (DEMO_AVD, hn-demo: a
# 1080x2400 Pixel 7 on Android 36, google_apis x86_64), ffmpeg, Node and KVM.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/Android/jdk-21}" ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
PKG=com.prudnikweb.hypernova
AVD="${DEMO_AVD:-hn-demo}"
# An even port and the odd one after it must both be free: with its console port taken the emulator
# boots but adb never gets past "offline". Always addressed by this serial, as other emulators may be listed.
EMU_PORT="${DEMO_EMU_PORT:-5582}"
SERIAL="emulator-$EMU_PORT"
# Software ANGLE. It draws the blur and shaders; `swiftshader_indirect` crashes the emulator as the app
# opens, and `host` needs an X display (demo/README.md, "The emulator").
GPU="${DEMO_GPU:-swangle_indirect}"
ZONE=America/New_York
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
OUT="$ROOT/docs/images"
export HYPERNOVA_DEMO_HOME="${HYPERNOVA_DEMO_HOME:-$HOME/.cache/hypernova-demo}"
WORK="$(mktemp -d)"
# What the crop keeps of the 1080x2400 screen: below the status bar, above the gesture pill.
CROP_TOP=130
CROP_HEIGHT=2200
WIDTH=945

log() { printf '%s\n' "$*" >&2; }
die() { log "demo-shots: $*"; exit 1; }
ad() { timeout "${ADB_WAIT:-30}" adb -s "$SERIAL" "$@"; }

STARTED_EMU=
STARTED_SERVERS=
cleanup() {
  if [ -z "${DEMO_KEEP:-}" ]; then
    # By its own serial, through its own console: never the other emulators.
    if [ -n "$STARTED_EMU" ]; then timeout 15 adb -s "$SERIAL" emu kill >/dev/null 2>&1 || kill "$STARTED_EMU" 2>/dev/null || true; fi
    if [ -n "$STARTED_SERVERS" ]; then kill "$STARTED_SERVERS" 2>/dev/null || true; fi
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

# ── The app ──────────────────────────────────────────────────────────────
build_apk() {
  [ -f "$APK" ] && [ -z "${DEMO_REBUILD:-}" ] && return
  log "Building the debug APK…"
  if [ ! -e "$ROOT/android/app/libs/tsnet.aar" ]; then
    # Gitignored, and the build wants it: from the main checkout's sibling worktrees if one has it.
    local aar
    aar="$(find "$ROOT/.." "$ROOT/.claude/worktrees" -maxdepth 5 -path '*/android/app/libs/tsnet.aar' -print -quit 2>/dev/null || true)"
    [ -n "$aar" ] || die "android/app/libs/tsnet.aar is missing (docs/DEV-SETUP.md)"
    mkdir -p "$ROOT/android/app/libs"
    ln -sf "$(realpath "$aar")" "$ROOT/android/app/libs/tsnet.aar"
  fi
  (cd "$ROOT/android" && ./gradlew --no-daemon -q :app:assembleDebug)
}

# ── The demo servers ─────────────────────────────────────────────────────
start_servers() {
  if ss -ltn | grep -q '127.0.0.1:47341 '; then
    log "The demo servers are already running."
    return
  fi
  log "Starting the demo servers…"
  TZ="$ZONE" node "$ROOT/demo/server.ts" >"$WORK/servers.log" 2>&1 &
  STARTED_SERVERS=$!
  local i
  for i in $(seq 1 50); do
    [ "$(grep -c 'pair with' "$WORK/servers.log" || true)" -ge 3 ] && return
    sleep 0.2
  done
  cat "$WORK/servers.log" >&2
  die "the demo servers didn't start"
}

# ── The emulator ─────────────────────────────────────────────────────────
start_emulator() {
  if [ "$(timeout 5 adb -s "$SERIAL" get-state 2>/dev/null)" = device ]; then
    log "$SERIAL is already running."
  else
    log "Starting the emulator ($AVD on $SERIAL, $GPU)…"
    local cmd="$ANDROID_HOME/emulator/emulator -avd $AVD -port $EMU_PORT -no-window -no-audio -no-boot-anim -no-snapshot -gpu $GPU -cores 8 -memory 4096 -timezone $ZONE"
    # A login from before this account joined the kvm group can reach /dev/kvm only through sg.
    if [ -w /dev/kvm ]; then $cmd >"$WORK/emulator.log" 2>&1 &
    else sg kvm -c "$cmd" >"$WORK/emulator.log" 2>&1 &
    fi
    STARTED_EMU=$!
  fi
  local i
  for i in $(seq 1 90); do
    [ "$(timeout 5 adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
    [ "$i" = 90 ] && { tail -5 "$WORK/emulator.log" >&2 || true; die "the emulator didn't boot"; }
    sleep 2
  done
  # With both, it keeps swapping networks, and each swap makes the app reconnect. Its Wi-Fi drops out too.
  ad shell svc wifi disable
  ad shell svc data enable
  # The demo's approvals would otherwise drop down over the shots as notifications.
  ad shell settings put global heads_up_notifications_enabled 0
  ad shell settings put global window_animation_scale 0
  ad shell settings put global transition_animation_scale 0
  animations off
}

# In software a moving screen draws slowly: taps arrive late and uiautomator never sees a
# quiet screen. Off (the app's Reduce Motion) to walk around, on for the capture (`shoot`).
animations() { ad shell settings put global animator_duration_scale "$([ "$1" = on ] && echo 1 || echo 0)"; }

install_app() {
  log "Installing the app…"
  ADB_WAIT=180 ad install -r "$APK" >/dev/null
  ad shell pm clear "$PKG" >/dev/null
  ad shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
}

start_app() {
  ad shell am force-stop "$PKG"
  ad shell am start -n "$PKG/.MainActivity" >/dev/null
}

# ── Finding things on screen ─────────────────────────────────────────────
dump() {
  # Never the last look's file, when this one fails.
  ad shell rm -f /sdcard/ui.xml
  ADB_WAIT=60 ad shell uiautomator dump --compressed /sdcard/ui.xml >/dev/null 2>&1 || return 1
  ad exec-out cat /sdcard/ui.xml
}
where() { dump 2>/dev/null | node "$ROOT/demo/ui-find.mjs" "$1"; }
wait_for() {
  local tries="${2:-12}" i
  for i in $(seq 1 "$tries"); do
    where "$1" >/dev/null && return 0
    sleep 2
  done
  die "never saw /$1/ on screen"
}
tap_text() {
  local p i
  # uiautomator sometimes finds no window just after a screen changes.
  for i in $(seq 1 12); do
    if p="$(where "$1")"; then
      # shellcheck disable=SC2086
      ad shell input tap $p
      return 0
    fi
    sleep 3
  done
  die "nothing to tap for /$1/"
}
tap_at() { ad shell input tap "$1" "$2"; }
back() { ad shell input keyevent 4; }
settle() { sleep "${1:-4}"; }

shoot() {
  local name="$1" raw="$WORK/$1.png"
  animations on
  sleep 10
  ADB_WAIT=30 ad exec-out screencap -p >"$raw"
  [ -s "$raw" ] || die "no screenshot for $name"
  # Metadata-free: no EXIF, no encoder comment.
  ffmpeg -y -loglevel error -i "$raw" -vf "crop=1080:$CROP_HEIGHT:0:$CROP_TOP,scale=$WIDTH:-2:flags=lanczos" \
    -map_metadata -1 -flags +bitexact -fflags +bitexact -q:v 3 "$OUT/shot-$name.jpg"
  animations off
  log "docs/images/shot-$name.jpg"
}

# ── Pairing: the way a phone does it, through the app's own link ──────────
pair() {
  local name="$1" file="$HYPERNOVA_DEMO_HOME/$1/pair-link" link before
  before="$(cat "$file")"
  link="$before"
  log "Pairing with $name…"
  ad shell "am start -a android.intent.action.VIEW -d '$link' $PKG" >/dev/null
  # A link from outside the app asks first ("Add this server?"), and Add waits a moment.
  wait_for 'Add server' 20
  settle 1
  tap_text 'Add server'
  local i
  for i in $(seq 1 30); do
    # The server writes a new code as soon as this one is used.
    [ "$(cat "$file")" != "$before" ] && { settle 4; return 0; }
    sleep 1
  done
  die "pairing with $name didn't finish"
}

# Every shot but the last starts on atlas's Home, from a fresh start of the app.
home() {
  start_app
  wait_for 'Add idempotency keys' 20
  settle 6
}

shot_home() {
  home
  shoot home
}

open_chat() {
  home
  tap_text 'Stream invoice'
  settle 9
}

shot_chat() {
  open_chat
  shoot chat
}

shot_changes() {
  open_chat
  # The strip under the title opens the session's sheet; its last tab is off to the right.
  tap_at 590 340
  wait_for '^Overview$'
  local i
  for i in 1 2 3 4 5; do
    where '^Session$' >/dev/null && break
    ad shell input swipe 950 1080 100 1080 600
    settle 2
  done
  tap_text '^Session$'
  settle 3
  tap_text 'files touched'
  settle 4
  tap_text 'invoiceCsv'
  settle 5
  shoot changes
}

shot_statistics() {
  home
  # A row on Settings' tab.
  tap_text '^SETTINGS$'
  settle 3
  tap_text '^Statistics$'
  settle 5
  tap_text '^All$'
  settle 6
  shoot statistics
}

shot_automations() {
  home
  tap_text 'AUTOMATIONS'
  settle 6
  shoot automations
}

shot_all_servers() {
  home
  tap_text 'atlas'
  settle 3
  tap_text 'All servers'
  settle 6
  # Down to where the three machines' cards mix: a drag that stops before it lets go, so it doesn't fling.
  ad shell "input motionevent DOWN 40 1700; for y in 1600 1450 1300 1140; do input motionevent MOVE 40 \$y; done; sleep 1.5; input motionevent UP 40 1140"
  settle 4
  shoot all-servers
}

main() {
  command -v ffmpeg >/dev/null || die "ffmpeg is missing"
  local want=("$@")
  # All servers stays chosen once picked, so it goes last.
  [ ${#want[@]} -gt 0 ] || want=(home chat changes statistics automations all-servers)
  build_apk
  start_servers
  start_emulator
  install_app
  start_app
  sleep 6
  pair juniper
  pair pico
  pair atlas
  local s
  for s in "${want[@]}"; do "shot_${s//-/_}"; done
  if [ -n "$STARTED_SERVERS" ] && grep -q 'no reply' "$WORK/servers.log"; then
    log "The app asked for things the demo doesn't have (fine unless a shot shows it):"
    grep 'no reply' "$WORK/servers.log" | sort -u >&2
  fi
}

main "$@"
