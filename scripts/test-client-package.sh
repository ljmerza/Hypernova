#!/usr/bin/env bash
# Installs one desktop client package the way a person does, opens it, and pairs it with the demo server
# (demo/README.md) from inside its own Java, then removes it. For .github/workflows/client-packages.yml:
#
#   scripts/test-client-package.sh --deb x.deb         --probe package-probe.jar --version 0.23.0 [--shots DIR] [--keychain]
#   scripts/test-client-package.sh --tarball x.tar.gz  --probe …
#   scripts/test-client-package.sh --dmg x.dmg         --probe …      a Mac runner
#   scripts/test-client-package.sh --msi x.msi         --probe … [--msi-next y.msi --next-version 0.24.0]    Windows, in Git's bash
#
# What it shows, on each:
#   1. It installs, at the version it should have, with its licences in it and its own Java.
#   2. A window opens on the path a person takes: under Xvfb on Linux, on the runner's own screen elsewhere.
#   3. With no display, on Linux, it says so and stops (Main.kt).
#   4. EC keys and mutual TLS work in the bundled Java: PackageProbe pairs with the demo server in the installed launcher,
#      and java.home has to be inside the install.
#   5. The installed app, started again on that pairing, reaches the server with its certificate: the server says so.
#   6. A newer .msi replaces the old one rather than piling up (with --msi-next), and uninstalling leaves nothing.
#   7. With --keychain, the device key's and the tokens' whole way (key_journey, below) on this system's real keychain: a
#      throwaway one, never the one of whoever runs this. So it wants CI set too. On Linux that needs dbus, gnome-keyring
#      and secret-tool.
#
# Everything but --tarball installs on this machine for real, so it wants CI set: a runner or a throwaway
# container (docs/RELEASING.md, "The desktop client's packages"). Needs node and daemon/node_modules for the demo
# server; on Linux xvfb, xdotool and, for the pictures, imagemagick. Bash 3.2: a Mac runner's own.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$REPO/android/desktop/packaging/client-names.sh"
WINDOW="$(sed -n 's/^const val APP_NAME = "\(.*\)"$/\1/p' "$REPO/android/desktop/src/main/kotlin/com/prudnikweb/hypernova/desktop/AppName.kt")"
DEMO_PORT=47391

kind='' pkg='' probe='' version='' shots='' next='' next_version='' keychain=''
while [ $# -gt 0 ]; do
  case "$1" in
    --deb|--tarball|--dmg|--msi) kind="${1#--}"; pkg="$2"; shift 2 ;;
    --probe) probe="$2"; shift 2 ;;
    --version) version="$2"; shift 2 ;;
    --shots) shots="$2"; shift 2 ;;
    --msi-next) next="$2"; shift 2 ;;
    --next-version) next_version="$2"; shift 2 ;;
    --keychain) keychain=yes; shift ;;
    *) echo "test-client-package.sh: what is $1?" >&2; exit 2 ;;
  esac
done
say() { printf '%s\n' "$*"; }
die() { printf 'FAIL  %s\n' "$*" >&2; exit 1; }
ok() { printf 'ok    %s\n' "$*"; }
[ -n "$kind" ] && [ -s "$pkg" ] || die "no package: --deb, --tarball, --dmg or --msi, with a file"
[ -s "$probe" ] || die "no probe jar: --probe, from :desktop:probeJar"
[ -n "$version" ] || die "--version: the release's numbers, 0.23.0"
[ "$kind" = tarball ] || [ -n "${CI:-}" ] || die "this installs on this machine for real, so only with CI set: a runner or a throwaway container"
[ -z "$keychain" ] || [ -n "${CI:-}" ] || die "--keychain changes this machine's keychains on a Mac, so only with CI set: a runner or a throwaway container"
# A tarball tried on someone's own computer stays out of their keychain, whatever happens below.
[ -n "${CI:-}" ] || export HYPERNOVA_CLIENT_KEYCHAIN=off
case "$version" in *[!0-9.]*|*.*.*.*) die "--version wants three numbers, not $version" ;; *.*.*) ;; *) die "--version wants three numbers, not $version" ;; esac
[ -d "$REPO/daemon/node_modules/ws" ] || die "the demo server needs daemon/node_modules: npm ci --ignore-scripts in daemon/"

on_windows=no
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) on_windows=yes ;; esac
# A path as a native program wants it.
native() { if [ "$on_windows" = yes ]; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
SUDO=''
[ "$on_windows" = yes ] || [ "$(id -u)" = 0 ] || SUDO=sudo
abs() { (cd "$(dirname "$1")" && printf '%s/%s' "$(pwd)" "$(basename "$1")"); }
pkg="$(abs "$pkg")"
probe="$(native "$(abs "$probe")")"
[ -z "$next" ] || next="$(abs "$next")"
if [ -n "$shots" ]; then mkdir -p "$shots"; shots="$(abs "$shots/.")"; fi

tmp="$(mktemp -d)"
xvfb_pid='' demo_pid='' app_pid='' mount='' test_keychain=''
finish() {
  code=$?
  set +e
  stop_app >/dev/null 2>&1
  if [ -n "$demo_pid" ]; then
    if [ "$on_windows" = yes ] && [ -r "/proc/$demo_pid/winpid" ]; then taskkill //F //T //PID "$(cat "/proc/$demo_pid/winpid")" >/dev/null 2>&1; else kill "$demo_pid" 2>/dev/null; fi
  fi
  [ -z "$xvfb_pid" ] || kill "$xvfb_pid" 2>/dev/null
  [ -z "$mount" ] || hdiutil detach "$mount" -quiet -force >/dev/null 2>&1
  [ -z "$test_keychain" ] || security delete-keychain "$test_keychain" >/dev/null 2>&1
  if [ "$code" -ne 0 ]; then
    for f in "$tmp"/*.log; do [ -e "$f" ] && { echo "--- $f"; tail -n 60 "$f"; }; done
  fi
  rm -rf "$tmp"
  exit "$code"
}
trap finish EXIT

# until <seconds> <command...>: every second, until it answers yes.
until_true() {
  local left=$1; shift
  while [ "$left" -gt 0 ]; do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 1
    left=$((left - 1))
  done
  return 1
}

# What each system does differently. ROOT is the install, LAUNCHER starts it, APPDIR holds its jars and resources.
ROOT='' LAUNCHER='' APPDIR=''

# ── Linux ──
linux_up() {
  xvfb=99
  Xvfb ":$xvfb" -screen 0 1600x1000x24 -nolisten tcp >"$tmp/xvfb.txt" 2>&1 &
  xvfb_pid=$!
  until_true 20 test -e "/tmp/.X11-unix/X$xvfb" || die "Xvfb didn't start"
  # With its count in front when a session needs you, as on a paired demo server (windowTitle in TrayAlerts.kt).
  window_up() { DISPLAY=":$xvfb" xdotool search --onlyvisible --name "^(\([0-9]+\) )?$WINDOW\$"; }
  shot() { command -v import >/dev/null || return 0; DISPLAY=":$xvfb" import -window root "$1" || true; }
  # A person's path here is the menu's entry, which runs this.
  open_as_a_person() {
    DISPLAY=":$xvfb" HYPERNOVA_CLIENT_HOME="$tmp/first-home" "$LAUNCHER" >"$tmp/first-window.log" 2>&1 &
    app_pid=$!
  }
  # The tokens are in the keychain by now, with --keychain: the app itself, not what starts the keychain for it, is what's stopped.
  open_on_pairing() {
    rm -f "$tmp/app.pid"
    under_keychain sh -c 'echo $$ >"$0"; exec env DISPLAY="$1" HYPERNOVA_CLIENT_HOME="$2" "$3"' "$tmp/app.pid" ":$xvfb" "$1" "$LAUNCHER" >"$tmp/paired-window.log" 2>&1 &
    until_true 20 test -s "$tmp/app.pid" || die "the app didn't start"
    app_pid="$(cat "$tmp/app.pid")"
  }
  stop_app() {
    [ -n "$app_pid" ] || return 0
    kill "$app_pid" 2>/dev/null || true
    until_true 15 sh -c "! kill -0 $app_pid" || { say "      (it was still going 15 s after SIGTERM: SIGKILL)"; kill -9 "$app_pid" 2>/dev/null; }
    app_pid=''
    return 0
  }
}
# A session bus and a keyring of their own, in a home of their own: never anyone's real ones.
keychain_linux() {
  for tool in dbus-run-session gnome-keyring-daemon secret-tool; do command -v "$tool" >/dev/null || die "--keychain wants $tool here"; done
  keyring_home="$tmp/keyring-home"
  mkdir -p "$keyring_home" "$tmp/no-bus-home"
  with_keychain() {
    env -u DISPLAY -u XDG_RUNTIME_DIR HOME="$keyring_home" XDG_DATA_HOME="$keyring_home/.local/share" dbus-run-session -- \
      sh -c 'printf %s throwaway | gnome-keyring-daemon --unlock --components=secrets >/dev/null 2>&1
        # It returns before it has the name on the bus, and a question before that would start a second, locked one.
        i=0
        until dbus-send --session --print-reply --dest=org.freedesktop.DBus /org/freedesktop/DBus org.freedesktop.DBus.NameHasOwner string:org.freedesktop.secrets 2>/dev/null | grep -q true; do
          i=$((i + 1))
          [ "$i" -ge 40 ] && break
          sleep 0.25
        done
        exec "$@"' sh "$@"
  }
  # Started by the bus with nobody to give it its password, as after a login that didn't unlock it.
  keychain_gone() {
    env -u DISPLAY -u XDG_RUNTIME_DIR HOME="$keyring_home" XDG_DATA_HOME="$keyring_home/.local/share" dbus-run-session -- "$@"
  }
  keychain_gone_says=file:Down
  keychain_gone_waits=Locked
  no_bus() { env -u DISPLAY -u XDG_RUNTIME_DIR -u DBUS_SESSION_BUS_ADDRESS HOME="$tmp/no-bus-home" "$@"; }
  under_keychain() { with_keychain "$@"; }
  # Locked, as above, until the probe says it's waiting: then the password is given, with the app still running.
  # --replace, as a second daemon wouldn't unlock the first (seen in a container).
  keychain_unlocks() {
    env -u DISPLAY -u XDG_RUNTIME_DIR HOME="$keyring_home" XDG_DATA_HOME="$keyring_home/.local/share" dbus-run-session -- sh -c '
      go="$0"
      (
        while [ ! -e "$go.waiting" ]; do sleep 1; done
        printf %s throwaway | gnome-keyring-daemon --replace --unlock --components=secrets >/dev/null 2>&1
        sleep 1
        : >"$go"
      ) &
      exec "$@"' "$HYPERNOVA_PROBE_GO" "$@"
  }
  # How many items there are, none of them printed. Only a Secret Service can list them.
  items_in_keychain() { with_keychain sh -c "secret-tool search --all service hypernova-client 2>/dev/null | grep -c '^\['"; }
  # A token's far shorter than this. secret-tool cuts what it's given at 8,192 bytes and still exits 0 (seen in a container),
  # which is why nothing leaves a file before it has come back whole.
  long="$(head -c 4096 /dev/zero | tr '\0' A)"
  [ "$(with_keychain sh -c 'printf %s "$1" | secret-tool store --label=long service hypernova-client-long account a && secret-tool lookup service hypernova-client-long account a; secret-tool clear service hypernova-client-long account a' sh "$long")" = "$long" ] || die "4 KB didn't come back from the keyring whole"
  ok "4 KB goes in the keyring and comes back whole"
}
no_display() {
  set +e
  out="$(env -u DISPLAY HYPERNOVA_CLIENT_HOME="$tmp/no-display" "$LAUNCHER" 2>&1)"
  code=$?
  set -e
  [ "$code" = 2 ] || die "with no display: exit $code, not 2: $out"
  case "$out" in *"needs a display"*) ok "with no display it says so and stops" ;; *) die "with no display it said: $out" ;; esac
}
install_deb() {
  # As INSTALL.md says: apt brings what the package depends on.
  $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y "$pkg" >"$tmp/apt.log" 2>&1 || die "apt-get install failed"
  ROOT="/opt/$CLIENT_PACKAGE"
  LAUNCHER="$ROOT/bin/$CLIENT_PACKAGE"
  APPDIR="$ROOT/lib/app"
  got="$(dpkg-query -W -f='${Version}' "$CLIENT_PACKAGE")"
  [ "$got" = "$version" ] || die "the package says $got, not $version"
  ok "apt-get install -y, version $got"
  [ -e "/usr/share/applications/$CLIENT_PACKAGE-$CLIENT_PACKAGE.desktop" ] && ok "a menu entry" || say "      (no menu entry: this system has no menus)"
  case "$(dpkg-deb -f "$pkg" Recommends)" in *libsecret-tools*) ok "it recommends what reaches a keychain" ;; *) die "it doesn't recommend libsecret-tools" ;; esac
  linux_up
}
remove_deb() {
  $SUDO env DEBIAN_FRONTEND=noninteractive apt-get remove -y "$CLIENT_PACKAGE" >>"$tmp/apt.log" 2>&1 || die "apt-get remove failed"
  [ ! -e "$ROOT" ] || die "$ROOT is still there after removing it"
  ok "apt-get remove leaves nothing"
}
install_tarball() {
  mkdir "$tmp/unpacked"
  tar -xzf "$pkg" -C "$tmp/unpacked"
  ROOT="$tmp/unpacked/$CLIENT_PACKAGE"
  LAUNCHER="$ROOT/bin/$CLIENT_PACKAGE"
  APPDIR="$ROOT/lib/app"
  [ -x "$LAUNCHER" ] || die "no $CLIENT_PACKAGE/bin/$CLIENT_PACKAGE in the tarball"
  ok "unpacked, one top folder: $(ls "$tmp/unpacked")"
  linux_up
}
remove_tarball() { :; }
keychain_deb() { keychain_linux; }
keychain_tarball() { keychain_linux; }

# ── Mac ──
install_dmg() {
  mount="$tmp/volume"
  mkdir "$mount"
  # `yes`: a licence in the image would otherwise wait for an answer.
  yes | hdiutil attach "$pkg" -nobrowse -noverify -mountpoint "$mount" >"$tmp/hdiutil.log" 2>&1 || true
  [ -d "$mount/$CLIENT_TITLE.app" ] || die "no $CLIENT_TITLE.app on the image: $(ls "$mount")"
  ROOT="/Applications/$CLIENT_TITLE.app"
  rm -rf "$ROOT"
  # What dragging it does: the signature and its attributes come too.
  ditto "$mount/$CLIENT_TITLE.app" "$ROOT"
  hdiutil detach "$mount" -quiet -force || true
  mount=''
  LAUNCHER="$ROOT/Contents/MacOS/$CLIENT_TITLE"
  APPDIR="$ROOT/Contents/app"
  [ -x "$LAUNCHER" ] || die "no launcher at $LAUNCHER"
  got="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$ROOT/Contents/Info.plist")"
  want="$((${version%%.*} + 1)).${version#*.}"
  [ "$got" = "$want" ] || die "the app's bundle says $got, not $want (docs/RELEASING.md, \"The desktop client's packages\")"
  ok "copied from the image to /Applications, bundle version $got"
  codesign --verify --deep "$ROOT" 2>"$tmp/codesign.log" && ok "its ad-hoc signature holds" || say "      (codesign --verify: $(tail -n 1 "$tmp/codesign.log"))"
  cat >"$tmp/windows.swift" <<'SWIFT'
import CoreGraphics
import Foundation
// A window of this name's owner, on the normal layer, with a real size. Names of owners need no permission.
let owner = CommandLine.arguments[1]
let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] ?? []
let found = list.contains { w in
    guard w[kCGWindowOwnerName as String] as? String == owner, w[kCGWindowLayer as String] as? Int == 0 else { return false }
    guard let bounds = w[kCGWindowBounds as String] as? [String: Double] else { return false }
    return (bounds["Width"] ?? 0) > 100 && (bounds["Height"] ?? 0) > 100
}
exit(found ? 0 : 1)
SWIFT
  xcrun swiftc -o "$tmp/windows" "$tmp/windows.swift" 2>"$tmp/swiftc.log" || die "couldn't build the window check"
  window_up() { "$tmp/windows" "$CLIENT_TITLE"; }
  shot() { screencapture -x "$1" || true; }
  open_as_a_person() { open "$ROOT"; }
  open_on_pairing() {
    HYPERNOVA_CLIENT_HOME="$1" "$LAUNCHER" >"$tmp/paired-window.log" 2>&1 &
    app_pid=$!
  }
  stop_app() {
    pkill -x "$CLIENT_TITLE" 2>/dev/null || true
    until_true 15 sh -c "! pgrep -x '$CLIENT_TITLE'" || { say "      (it was still going 15 s after SIGTERM: SIGKILL)"; pkill -9 -x "$CLIENT_TITLE" 2>/dev/null; }
    app_pid=''
    return 0
  }
}
# A keychain of its own, unlocked, and the default: a runner's login keychain may not be either.
keychain_dmg() {
  test_keychain="$tmp/hypernova-test.keychain-db"
  security create-keychain -p throwaway "$test_keychain"
  security set-keychain-settings "$test_keychain"
  security unlock-keychain -p throwaway "$test_keychain"
  # shellcheck disable=SC2046 # one name per line, each in quotes, none with a space on a runner
  security list-keychains -d user -s "$test_keychain" $(security list-keychains -d user | tr -d '"')
  security default-keychain -d user -s "$test_keychain"
  with_keychain() { "$@"; }
  keychain_gone() { env HYPERNOVA_CLIENT_KEYCHAIN=off "$@"; }
  keychain_gone_says=file:TurnedOff
  keychain_gone_waits=TurnedOff
  no_bus() { env HYPERNOVA_CLIENT_KEYCHAIN=off "$@"; }
}
remove_dmg() {
  rm -rf "$ROOT"
  [ ! -e "$ROOT" ] || die "$ROOT is still there"
  ok "removed from /Applications"
}

# ── Windows ──
# One line, the version. A minor of 255 or more is folded (build.gradle.kts), which isn't worth working out again here.
listed_as() {
  case "$2" in *[!0-9.]*|'') return 1 ;; esac
  case "$1" in
    *.25[5-9].*|*.2[6-9][0-9].*|*.[3-9][0-9][0-9].*|*.[0-9][0-9][0-9][0-9].*) return 0 ;;
    *) [ "$1" = "$2" ] ;;
  esac
}
install_msi() {
  cat >"$tmp/win.ps1" <<'PS1'
param([string]$what, [string]$a = '', [string]$b = '')
$ErrorActionPreference = 'Stop'
switch ($what) {
  'msi' {
    # /qn: no window, so what a person clicks through isn't what's tried. msiexec returns at once unless it's waited for.
    # GetFullPath for its backslashes: given forward slashes, msiexec can't open the package (1619).
    $flag = if ($a -eq 'install') { '/i' } else { '/x' }
    $b = [IO.Path]::GetFullPath($b)
    $p = Start-Process msiexec.exe -ArgumentList @($flag, "`"$b`"", '/qn', '/norestart') -Wait -PassThru
    exit $p.ExitCode
  }
  'versions' {
    Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*' |
      Where-Object { $_.DisplayName -eq $a } | ForEach-Object { $_.DisplayVersion }
  }
  'upgrade-code' {
    $a = [IO.Path]::GetFullPath($a)
    $i = New-Object -ComObject WindowsInstaller.Installer
    $db = $i.GetType().InvokeMember('OpenDatabase', 'InvokeMethod', $null, $i, @($a, 0))
    $view = $db.GetType().InvokeMember('OpenView', 'InvokeMethod', $null, $db, @("SELECT Value FROM Property WHERE Property='UpgradeCode'"))
    $null = $view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, $null)
    $row = $view.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $view, $null)
    $row.GetType().InvokeMember('StringData', 'GetProperty', $null, $row, 1)
  }
  'shortcut' {
    $link = Get-ChildItem -Recurse -Filter '*.lnk' -Path (Join-Path $env:ProgramData 'Microsoft\Windows\Start Menu\Programs') |
      Where-Object { $_.BaseName -eq $a } | Select-Object -First 1
    if (-not $link) { Write-Error "no shortcut named $a in the Start menu" }
    Start-Process $link.FullName
  }
  'window' {
    $w = Get-Process -Name $a -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -match ('^(\([0-9]+\) )?' + [regex]::Escape($b) + '$') }
    if (-not $w) { exit 1 }
  }
  'shot' {
    Add-Type -AssemblyName System.Windows.Forms, System.Drawing
    $s = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $i = New-Object System.Drawing.Bitmap $s.Width, $s.Height
    [System.Drawing.Graphics]::FromImage($i).CopyFromScreen($s.Location, [System.Drawing.Point]::Empty, $s.Size)
    $i.Save($a)
  }
}
PS1
  win() { powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(native "$tmp/win.ps1")" "$@"; }
  # Not /i from here: this bash would turn it into a path.
  win msi install "$(native "$pkg")" || die "msiexec /i failed: exit $?"
  ROOT="$(cygpath -u "$PROGRAMFILES")/$CLIENT_TITLE"
  LAUNCHER="$ROOT/$CLIENT_TITLE.exe"
  APPDIR="$ROOT/app"
  [ -x "$LAUNCHER" ] || die "no launcher at $LAUNCHER"
  got="$(win versions "$CLIENT_TITLE" | tr -d '\r')"
  listed_as "$version" "$got" || die "Windows lists it as '$got', not $version"
  ok "msiexec /i /qn, version $got"
  want="$(sed -n 's/^ *upgradeUuid = "\(.*\)"$/\1/p' "$REPO/android/desktop/build.gradle.kts")"
  code="$(win upgrade-code "$(native "$pkg")" | tr -d '\r{}')"
  [ "$(printf '%s' "$code" | tr 'A-F' 'a-f')" = "$want" ] || die "the .msi's UpgradeCode is $code, not $want"
  ok "its UpgradeCode is the fixed one"
  window_up() { win window "$CLIENT_TITLE" "$WINDOW"; }
  shot() { win shot "$(native "$1")" || true; }
  open_as_a_person() { win shortcut "$CLIENT_TITLE"; }
  open_on_pairing() {
    HYPERNOVA_CLIENT_HOME="$(native "$1")" "$LAUNCHER" >"$tmp/paired-window.log" 2>&1 &
    app_pid=$!
  }
  stop_app() {
    taskkill //F //IM "$CLIENT_TITLE.exe" >/dev/null 2>&1 || true
    until_true 15 sh -c "! tasklist //FI \"IMAGENAME eq $CLIENT_TITLE.exe\" | grep -qi '$CLIENT_TITLE'" || say "      (it was still there 15 s after taskkill)"
    app_pid=''
    return 0
  }
}
# DPAPI has nothing to set up, as the runner's user has a login.
keychain_msi() {
  with_keychain() { "$@"; }
  # Each secret is a sealed file in the folder.
  items_in_keychain() { ls "$paired"/*.dpapi | wc -l | tr -d ' '; }
  keychain_gone() { env HYPERNOVA_CLIENT_KEYCHAIN=off "$@"; }
  keychain_gone_says=file:TurnedOff
  keychain_gone_waits=TurnedOff
  no_bus() { env HYPERNOVA_CLIENT_KEYCHAIN=off "$@"; }
}
remove_msi() {
  if [ -n "$next" ]; then
    win msi install "$(native "$next")" || die "the newer .msi failed: exit $?"
    got="$(win versions "$CLIENT_TITLE" | tr -d '\r')"
    listed_as "$next_version" "$got" || die "after the newer .msi, Windows lists: '$got', not one $next_version"
    ok "a newer .msi replaced it: one entry, $got"
    pkg="$next"
  fi
  win msi uninstall "$(native "$pkg")" || die "msiexec /x failed: exit $?"
  [ ! -e "$LAUNCHER" ] || die "$LAUNCHER is still there after uninstalling"
  [ -z "$(win versions "$CLIENT_TITLE" | tr -d '\r')" ] || die "Windows still lists it after uninstalling"
  ok "msiexec /x leaves nothing"
}

# ── The same on each ──
stop_app() { :; }
under_keychain() { "$@"; }
"install_$kind"

for f in LICENSE NOTICE THIRD-PARTY-NOTICES.md OFL-Manrope.txt OFL-JetBrainsMono.txt LICENSES-skia-bundled.txt; do
  [ -s "$APPDIR/resources/legal/$f" ] || die "no $f in the install's legal folder, $APPDIR/resources/legal"
done
[ -n "$(find "$ROOT" -path '*legal/java.base/LICENSE' | head -n 1)" ] || die "no licence of the Java it carries"
ok "its licences are there, and its Java's"

case "$kind" in deb|tarball) no_display ;; esac

first_shot="${shots:+$shots/$kind-1-first-window.png}"
open_as_a_person || die "it didn't start"
until_true 120 window_up || die "no window named $WINDOW after 2 minutes"
ok "it opens: a window named $WINDOW"
# A moment to draw before the picture.
sleep 5
[ -z "$first_shot" ] || shot "$first_shot"
stop_app

mkdir "$tmp/demo"
# A second server, for the tokens' way: each keeps its own.
HYPERNOVA_DEMO_ADDRESS=127.0.0.1 HYPERNOVA_DEMO_PORT="$DEMO_PORT" HYPERNOVA_DEMO_HOME="$(native "$tmp/demo")" \
  node "$REPO/demo/server.ts" atlas ${keychain:+juniper} >"$tmp/demo.log" 2>&1 &
demo_pid=$!
until_true 60 test -s "$tmp/demo/atlas/pair-link" || die "the demo server didn't start"
[ -z "$keychain" ] || until_true 60 test -s "$tmp/demo/juniper/pair-link" || die "the second demo server didn't start"
connected() { grep -c 'connected over mutual TLS' "$tmp/demo.log" || true; }

paired="$tmp/paired-home"
mkdir "$paired"
# probe_run <name> <link's file, or nothing to start on the folder's pairing> [what to run it under...]
# On $probe_home, $paired unless a step says otherwise. HYPERNOVA_PROBE_* given in front of it go through to the probe.
probe_home="$paired"
probe_run() {
  local name="$1" link="$2" answer="$tmp/probe-$1.txt" pid
  shift 2
  "$@" env HYPERNOVA_CLIENT_HOME="$(native "$probe_home")" HYPERNOVA_PROBE_LINK="$link" HYPERNOVA_PROBE_OUT="$(native "$answer")" \
    JAVA_TOOL_OPTIONS="-javaagent:$probe" "$LAUNCHER" >"$tmp/probe-$name.log" 2>&1 &
  pid=$!
  until_true 150 test -s "$answer" || die "the probe ($name) gave no answer"
  wait "$pid" || true
  grep -q '^ok=true$' "$answer" || { cat "$answer"; die "it didn't pair or connect ($name)"; }
}
said_by() { sed -n "s/^$2=//p" "$tmp/probe-$1.txt"; }
key_is() { # <probe> <where>
  [ "$(said_by "$1" key.place)" = "$2" ] || die "$1: the key is at $(said_by "$1" key.place), not $2"
}

# The first pairing is on the file, as every install before this was.
probe_run first "$(native "$tmp/demo/atlas/pair-link")" env HYPERNOVA_CLIENT_KEYCHAIN=off
answer="$tmp/probe-first.txt"
cat "$answer"
java_home="$(sed -n 's/^java.home=//p' "$answer" | tr '\\' '/')"
case "$java_home" in "$(native "$ROOT")"*) ok "that's the Java in the install, $java_home" ;; *) die "that was another Java: $java_home, not in $ROOT" ;; esac
said="$(sed -n 's/^app.version=//p' "$answer")"
[ "$said" = "$version" ] || die "the app says it's $said, not $version"
ok "the app says it's $said"
ok "paired with the demo server: an EC key, a certificate on it, mutual TLS"

# The device key's and the tokens' whole way, each step reconnecting with mutual TLS on what's in the folder (PackageProbe,
# ok=true), or saying why it can't:
#   an install from before: key and token moved to the keychain with its pairing, the files without them, and found by a restart;
#   a copy of the folder: holds no token, and can't connect;
#   then the keychain locked at the start, or missing: the token's server waits, nothing lost, nothing written over; Try again
#   brings it back once the password's given, in the same run;
#   then a second server paired with it locked: a new key and its token in the file, a new certificate, connected, the first still waiting;
#   then the keychain back: both servers, the file's key over the old one, the file's token moved;
#   then Forget: that server's item goes, and the other's stays.
token_is() { # <probe> <where>
  [ "$(said_by "$1" token.place)" = "$2" ] || die "$1: the token is at $(said_by "$1" token.place), not $2"
}
token_in_file() { sed -n 's/.*\\"token\\":\\"\([^\\"]*\)\\".*/\1/p' "$1/prefs/hypernova.json"; }
no_token_in() { # <token> <where...>
  local token="$1"
  shift
  if grep -rlF -- "$token" "$@" >"$tmp/holds-token.txt" 2>/dev/null; then
    die "a token is readable in: $(cat "$tmp/holds-token.txt")"
  fi
}
key_journey() {
  local key_file="$paired/device-key.json" settings="$paired/prefs/hypernova.json" secret old new first_token second_token
  key_is first file:TurnedOff
  token_is first file:TurnedOff
  [ -s "$key_file" ] || die "no key file after the first pairing"
  ok "paired with the key in its owner-only file"
  # Only to look for, below.
  secret="$(sed 's/.*"private":"\([^"]*\)".*/\1/' "$key_file")"
  [ -n "$secret" ] || die "no private key in the key's file"
  old="$(said_by first key.public)"
  first_token="$(token_in_file "$paired")"
  [ -n "$first_token" ] || die "no token in the settings after the first pairing"
  ok "and its token in the owner-only settings"

  "keychain_$kind"
  probe_run moved '' with_keychain
  key_is moved keychain
  token_is moved keychain
  [ ! -e "$key_file" ] || die "the key's file is still there after the move"
  [ "$(said_by moved key.public)" = "$old" ] || die "the key changed in the move"
  no_token_in "$first_token" "$paired"
  ok "an existing pairing moved its key and token to the keychain, took them from the files, and connected"

  probe_run restarted '' with_keychain
  key_is restarted keychain
  token_is restarted keychain
  [ "$(said_by restarted key.public)" = "$old" ] || die "a restart found another key"
  [ ! -e "$key_file" ] || die "the key's file came back"
  no_token_in "$first_token" "$paired"
  ok "a restart reads the same key and token from the keychain"

  # With DPAPI the same login can undo what's copied along: that's all it is on Windows (DECISIONS).
  if [ "$kind" != msi ]; then
    cp -R "$paired" "$tmp/copied"
    probe_home="$tmp/copied" HYPERNOVA_PROBE_EXPECT=waiting probe_run copied '' with_keychain
    [ "$(said_by copied token.waiting)" = Gone ] || die "a copy of the folder: $(said_by copied token.waiting), not Gone"
    no_token_in "$first_token" "$tmp/copied"
    rm -rf "$tmp/copied"
    ok "a copy of the folder holds no token and can't connect, even beside the same keychain"
  fi

  HYPERNOVA_PROBE_EXPECT=waiting probe_run locked '' keychain_gone
  [ "$(said_by locked token.waiting)" = "$keychain_gone_waits" ] || die "locked: $(said_by locked token.waiting), not $keychain_gone_waits"
  key_is locked nowhere
  grep -q 'tokenInKeychain' "$settings" || die "the server's entry didn't survive a locked start"
  no_token_in "$first_token" "$paired"
  ok "locked at the start with the token inside: it says so, connects to nothing, and loses nothing ($keychain_gone_waits)"

  HYPERNOVA_PROBE_EXPECT=waiting probe_run locked-no-bus '' no_bus
  [ "$(said_by locked-no-bus token.waiting)" = "$keychain_gone_waits" ] || die "no keychain: $(said_by locked-no-bus token.waiting), not $keychain_gone_waits"
  grep -q 'tokenInKeychain' "$settings" || die "the server's entry didn't survive no keychain at all"
  ok "no keychain running at all: the same, and nothing lost"

  if [ "$kind" = deb ] || [ "$kind" = tarball ]; then
    HYPERNOVA_PROBE_GO="$tmp/unlocked" HYPERNOVA_PROBE_EXPECT=waiting probe_run unlocked '' keychain_unlocks
    [ "$(said_by unlocked token.waiting)" = Locked ] || die "before the password: $(said_by unlocked token.waiting), not Locked"
    key_is unlocked keychain
    token_is unlocked keychain
    [ "$(said_by unlocked key.public)" = "$old" ] || die "Try again found another key"
    ok "a real locked keyring, unlocked with the app running: Try again connected, on the same key and token, with no new pairing"
  fi

  HYPERNOVA_PROBE_EXPECT=waiting probe_run second "$(native "$tmp/demo/juniper/pair-link")" keychain_gone
  key_is second "$keychain_gone_says"
  token_is second "$keychain_gone_says"
  [ -s "$key_file" ] || die "no key file with the keychain gone"
  new="$(said_by second key.public)"
  [ "$new" != "$old" ] || die "it had the old key with the keychain gone"
  second_token="$(token_in_file "$paired")"
  [ -n "$second_token" ] || die "the new pairing's token isn't in the file with the keychain gone"
  [ "$second_token" != "$first_token" ] || die "the two servers have the same token"
  grep -q 'tokenInKeychain' "$settings" || die "the first server's entry didn't survive a second pairing"
  no_token_in "$first_token" "$paired"
  ok "a second server paired with the keychain gone: a new key and its token in the file, connected, the first still waiting ($keychain_gone_says)"

  HYPERNOVA_PROBE_EXPECT=waiting probe_run second-no-bus '' no_bus
  key_is second-no-bus "$keychain_gone_says"
  [ "$(said_by second-no-bus key.public)" = "$new" ] || die "with no keychain at all it didn't keep the file's key"
  ok "no keychain running at all: the file's key and token, connected"

  HYPERNOVA_PROBE_SERVERS=2 probe_run back '' with_keychain
  key_is back keychain
  token_is back keychain
  [ "$(said_by back key.public)" = "$new" ] || die "the file's key didn't win"
  [ "$(said_by back servers)" = "atlas,juniper" ] || die "back: the servers are $(said_by back servers), not in their places"
  [ ! -e "$key_file" ] || die "the key's file is still there with the keychain back"
  no_token_in "$first_token" "$paired"
  no_token_in "$second_token" "$paired"
  if type items_in_keychain >/dev/null 2>&1; then
    [ "$(items_in_keychain)" = 3 ] || die "the keyring has $(items_in_keychain) items, not the key and two tokens"
  fi
  ok "the keychain back: both servers linked, each on its own token, the file's key over the old one, no token in a file"

  HYPERNOVA_PROBE_SERVERS=2 HYPERNOVA_PROBE_FORGET=juniper probe_run forgot '' with_keychain
  [ "$(said_by forgot servers)" = atlas ] || die "after Forget: $(said_by forgot servers)"
  if type items_in_keychain >/dev/null 2>&1; then
    [ "$(items_in_keychain)" = 2 ] || die "the keyring has $(items_in_keychain) items after Forget, not the key and one token"
  fi
  probe_run after-forget '' with_keychain
  [ "$(said_by after-forget servers)" = atlas ] || die "after a restart: $(said_by after-forget servers)"
  ok "Forget took that server and its item, and the other connects on its own"

  if grep -rlF -- "$secret" "$paired" "$tmp"/probe-*.log "$tmp"/probe-*.txt ${keyring_home:+"$keyring_home"} >"$tmp/holds-key.txt" 2>/dev/null; then
    die "the first key is readable in: $(cat "$tmp/holds-key.txt")"
  fi
  ok "the private key is in no log, no answer and no file of the folder${keyring_home:+, and the keyring holds it encrypted}"
  no_token_in "$first_token" "$paired" "$tmp"/probe-*.log "$tmp"/probe-*.txt ${keyring_home:+"$keyring_home"}
  no_token_in "$second_token" "$paired" "$tmp"/probe-*.log "$tmp"/probe-*.txt ${keyring_home:+"$keyring_home"}
  ok "neither token is in a log, an answer or a file of the folder${keyring_home:+, and the keyring holds them encrypted}"
}
[ -z "$keychain" ] || key_journey

before="$(connected)"
open_on_pairing "$paired"
until_true 120 window_up || die "no window on the second start"
more_connected() { [ "$(connected)" -gt "$before" ]; }
until_true 60 more_connected || die "the installed app didn't reach the server with its certificate"
ok "the installed app, started again, reached the server over mutual TLS with its window open"
sleep 5
[ -z "$shots" ] || shot "$shots/$kind-2-paired-window.png"
stop_app

"remove_$kind"
say "all passed: $kind"
