#!/usr/bin/env bash
# Installs one desktop client package the way a person does, opens it, and pairs it with the demo server
# (demo/README.md) from inside its own Java, then removes it. For .github/workflows/client-packages.yml:
#
#   scripts/test-client-package.sh --deb x.deb         --probe package-probe.jar --version 0.23.0 [--shots DIR]
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
#
# Everything but --tarball installs on this machine for real, so it wants CI set: a runner or a throwaway
# container (docs/RELEASING.md, "The desktop client's packages"). Needs node and daemon/node_modules for the demo
# server; on Linux xvfb, xdotool and, for the pictures, imagemagick. Bash 3.2: a Mac runner's own.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$REPO/android/desktop/packaging/client-names.sh"
WINDOW="$(sed -n 's/^const val APP_NAME = "\(.*\)"$/\1/p' "$REPO/android/desktop/src/main/kotlin/com/prudnikweb/hypernova/desktop/AppName.kt")"
DEMO_PORT=47391

kind='' pkg='' probe='' version='' shots='' next='' next_version=''
while [ $# -gt 0 ]; do
  case "$1" in
    --deb|--tarball|--dmg|--msi) kind="${1#--}"; pkg="$2"; shift 2 ;;
    --probe) probe="$2"; shift 2 ;;
    --version) version="$2"; shift 2 ;;
    --shots) shots="$2"; shift 2 ;;
    --msi-next) next="$2"; shift 2 ;;
    --next-version) next_version="$2"; shift 2 ;;
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
xvfb_pid='' demo_pid='' app_pid='' mount=''
finish() {
  code=$?
  set +e
  stop_app >/dev/null 2>&1
  if [ -n "$demo_pid" ]; then
    if [ "$on_windows" = yes ] && [ -r "/proc/$demo_pid/winpid" ]; then taskkill //F //T //PID "$(cat "/proc/$demo_pid/winpid")" >/dev/null 2>&1; else kill "$demo_pid" 2>/dev/null; fi
  fi
  [ -z "$xvfb_pid" ] || kill "$xvfb_pid" 2>/dev/null
  [ -z "$mount" ] || hdiutil detach "$mount" -quiet -force >/dev/null 2>&1
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
  window_up() { DISPLAY=":$xvfb" xdotool search --onlyvisible --name "^$WINDOW\$"; }
  shot() { command -v import >/dev/null || return 0; DISPLAY=":$xvfb" import -window root "$1" || true; }
  # A person's path here is the menu's entry, which runs this.
  open_as_a_person() {
    DISPLAY=":$xvfb" HYPERNOVA_CLIENT_HOME="$tmp/first-home" "$LAUNCHER" >"$tmp/first-window.log" 2>&1 &
    app_pid=$!
  }
  open_on_pairing() {
    DISPLAY=":$xvfb" HYPERNOVA_CLIENT_HOME="$1" "$LAUNCHER" >"$tmp/paired-window.log" 2>&1 &
    app_pid=$!
  }
  stop_app() {
    [ -n "$app_pid" ] || return 0
    kill "$app_pid" 2>/dev/null || true
    until_true 15 sh -c "! kill -0 $app_pid" || { say "      (it was still going 15 s after SIGTERM: SIGKILL)"; kill -9 "$app_pid" 2>/dev/null; }
    app_pid=''
    return 0
  }
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
    $flag = if ($a -eq 'install') { '/i' } else { '/x' }
    $p = Start-Process msiexec.exe -ArgumentList @($flag, "`"$b`"", '/qn', '/norestart') -Wait -PassThru
    exit $p.ExitCode
  }
  'versions' {
    Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*' |
      Where-Object { $_.DisplayName -eq $a } | ForEach-Object { $_.DisplayVersion }
  }
  'upgrade-code' {
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
    $w = Get-Process -Name $a -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -eq $b }
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
HYPERNOVA_DEMO_ADDRESS=127.0.0.1 HYPERNOVA_DEMO_PORT="$DEMO_PORT" HYPERNOVA_DEMO_HOME="$(native "$tmp/demo")" \
  node "$REPO/demo/server.ts" atlas >"$tmp/demo.log" 2>&1 &
demo_pid=$!
until_true 60 test -s "$tmp/demo/atlas/pair-link" || die "the demo server didn't start"
connected() { grep -c 'connected over mutual TLS' "$tmp/demo.log" || true; }

paired="$tmp/paired-home"
mkdir "$paired"
answer="$tmp/probe-answer.txt"
HYPERNOVA_CLIENT_HOME="$(native "$paired")" HYPERNOVA_PROBE_LINK="$(native "$tmp/demo/atlas/pair-link")" HYPERNOVA_PROBE_OUT="$(native "$answer")" \
  JAVA_TOOL_OPTIONS="-javaagent:$probe" "$LAUNCHER" >"$tmp/probe.log" 2>&1 &
probe_pid=$!
until_true 150 test -s "$answer" || die "the probe gave no answer"
wait "$probe_pid" || true
cat "$answer"
grep -q '^ok=true$' "$answer" || die "it didn't pair"
java_home="$(sed -n 's/^java.home=//p' "$answer" | tr '\\' '/')"
case "$java_home" in "$(native "$ROOT")"*) ok "that's the Java in the install, $java_home" ;; *) die "that was another Java: $java_home, not in $ROOT" ;; esac
said="$(sed -n 's/^app.version=//p' "$answer")"
[ "$said" = "$version" ] || die "the app says it's $said, not $version"
ok "the app says it's $said"
ok "paired with the demo server: an EC key, a certificate on it, mutual TLS"

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
