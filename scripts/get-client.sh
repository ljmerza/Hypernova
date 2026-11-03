#!/usr/bin/env bash
# Hypernova's desktop client in one line, on Linux or a Mac:
#
#   curl -fsSL https://ljmerza.github.io/Hypernova/get-client.sh | bash
#
# It finds the latest release's file for this computer, checks it against the release's SHA256SUMS and
# installs it (docs/INSTALL.md, "The desktop client"). Run it again to update.
#
#   Linux with apt, amd64 or arm64    the .deb, with sudo
#   Linux without apt, x86_64         the tarball, under ~/.local/share and ~/.local/bin, no sudo
#   A Mac, Apple silicon or Intel     the .dmg's app, copied to /Applications, or ~/Applications if that isn't writable
#   Windows (Git Bash)                says where the .msi is, as Windows' own installer has to run it
#
# Options go after `bash -s --`: --tarball picks the tarball where the .deb would be used.
#
#   curl -fsSL https://ljmerza.github.io/Hypernova/get-client.sh | bash -s -- --tarball
#
# HYPERNOVA_TAG=v0.3.0 picks a release instead of the latest.
#
# For testing only: HYPERNOVA_CLIENT_DIR=<folder> takes the files and a SHA256SUMS from that folder instead of a
# release, checked the same way, and wants HYPERNOVA_TAG too (scripts/test-client-package.sh --get-client).
# HYPERNOVA_CLIENT_APPLICATIONS=<folder> stands in for /Applications (scripts/test-get-client.sh).
#
# Written for the bash a Mac ships, 3.2 (scripts/test-get-client.sh runs it under that). Everything is in
# functions and the last line calls them, so a connection dropped halfway through `curl | bash` runs nothing.
set -euo pipefail

# The names, a copy of android/desktop/packaging/client-names.sh as there's no checkout to read it from when this is
# piped from a web page. scripts/test-get-client.sh fails if the two differ. Keep each as NAME='value'.
CLIENT_PACKAGE='hypernova-client'
CLIENT_TITLE='Hypernova Client'

REPO=ljmerza/Hypernova
SELF_URL=https://ljmerza.github.io/Hypernova/get-client.sh
RELEASES="https://github.com/$REPO/releases"
GUIDE="https://github.com/$REPO/blob/main/docs/INSTALL.md#the-desktop-client"
# A Mac's app and the Linux tarball are never root's: a person's own apps, under their own home.
NOT_ROOT="run it as yourself, not root: this install is for your own user."
DIR="${HYPERNOVA_CLIENT_DIR:-}"

TMP=''        # The downloads. Gone on any exit.
MOUNT=''      # The Mac's image while it's attached.
STAGE=''      # A new copy on its way in, beside where it goes.
APP=''        # The Mac's app, for putting an older copy back if a run is stopped in the middle of the swap.
TAG=''
ASSETS=''     # The release's download addresses, one a line.

die() { printf 'get-client.sh: %s\n' "$*" >&2; exit 1; }
say() { printf '%s\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || die "$1 is missing: install it and run this again."; }

usage() {
  cat <<EOF
Installs Hypernova's desktop client on Linux or a Mac, and says where it is on Windows.

  curl -fsSL $SELF_URL | bash
  curl -fsSL $SELF_URL | bash -s -- --tarball

  --tarball   on Linux x86_64, the tarball under your home, with no sudo, even where apt is there
  --help      this

HYPERNOVA_TAG=v0.3.0 picks a release instead of the latest. Run it again to update.
EOF
}

finish() {
  local code=$? mounted=no
  set +e
  trap - EXIT
  if [ -n "$MOUNT" ]; then
    # Never empties a folder that may still have the image in it.
    if ! hdiutil detach "$MOUNT" -quiet -force >/dev/null 2>&1; then
      mounted=yes
      printf 'get-client.sh: the image is still attached at %s: hdiutil detach it by hand.\n' "$MOUNT" >&2
    fi
  fi
  if [ -n "$STAGE" ]; then
    # Stopped between moving the older copy aside and putting the new one in: the older one goes back.
    if [ -n "$APP" ] && [ -e "$STAGE/old.app" ] && [ ! -e "$APP" ]; then mv "$STAGE/old.app" "$APP"; fi
    rm -rf "$STAGE"
  fi
  if [ -n "$TMP" ] && [ "$mounted" = no ]; then rm -rf "$TMP"; fi
  exit "$code"
}

# What `curl | bash` reads its script from is standard input, so what runs here is given the terminal, or nothing.
with_terminal() {
  if { : </dev/tty; } 2>/dev/null; then "$@" </dev/tty; else "$@" </dev/null; fi
}

find_release() {
  local api what json
  if [ -n "$DIR" ]; then
    [ -d "$DIR" ] || die "HYPERNOVA_CLIENT_DIR isn't a folder: $DIR"
    [ -n "${HYPERNOVA_TAG:-}" ] || die "HYPERNOVA_CLIENT_DIR wants HYPERNOVA_TAG too."
    DIR="$(cd "$DIR" && pwd)"
    TAG=$HYPERNOVA_TAG
    say "  For testing: the files in $DIR, not a release."
    return 0
  fi
  if [ -n "${HYPERNOVA_TAG:-}" ]; then
    api="https://api.github.com/repos/$REPO/releases/tags/$HYPERNOVA_TAG"
    what="release $HYPERNOVA_TAG"
  else
    api="https://api.github.com/repos/$REPO/releases/latest"
    what="the latest release"
  fi
  json=$(curl -fsL -H 'Accept: application/vnd.github+json' "$api") \
    || die "couldn't read $what from GitHub: no network, no such release, or GitHub's hourly limit on these requests. See $RELEASES."
  TAG=$(printf '%s' "$json" | grep -o '"tag_name": *"[^"]*"' | sed -n '1s/.*"\([^"]*\)"$/\1/p') || true
  check_tag "$TAG"
  ASSETS=$(printf '%s' "$json" | grep -o '"browser_download_url": *"[^"]*"' | sed 's/.*"\(https[^"]*\)"$/\1/') || true
}

check_tag() {
  case "$1" in
    ''|*[!A-Za-z0-9._-]*) die "that isn't a release's name: '$1'." ;;
  esac
}

# Where the release has this file. Fails if it hasn't.
asset() {
  local u
  if [ -n "$DIR" ]; then
    [ -f "$DIR/$1" ] || return 1
    printf '%s' "$DIR/$1"
    return 0
  fi
  while IFS= read -r u; do
    if [ "${u##*/}" = "$1" ]; then
      printf '%s' "$u"
      return 0
    fi
  done <<EOF
$ASSETS
EOF
  return 1
}

not_there_yet() { # <file>
  die "$1 isn't on $TAG. A release's desktop files come some minutes after it's published, so try again in a few minutes. If it's still not there, it wasn't built for that release, and HYPERNOVA_TAG picks an older one: $RELEASES"
}

fetch() { # <where from> <to>
  if [ -n "$DIR" ]; then
    cp "$1" "$2"
  elif [ -t 2 ]; then
    curl -fL --progress-bar "$1" -o "$2"
  else
    curl -fsSL "$1" -o "$2"
  fi
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum <"$1" | awk '{print $1}'
  else
    shasum -a 256 <"$1" | awk '{print $1}'
  fi
}

# Nothing else is ever installed: $TMP/<file> is there after this only if it matches the release's SHA256SUMS.
get_checked() { # <file>
  local file=$1 url sums want got
  url=$(asset "$file") || not_there_yet "$file"
  sums=$(asset SHA256SUMS) \
    || die "$TAG has no SHA256SUMS yet, so $file can't be checked, and it isn't installed unchecked. They come last, some minutes after a release is published: try again in a few minutes. $RELEASES/tag/$TAG"
  fetch "$sums" "$TMP/SHA256SUMS" || die "couldn't download $TAG's SHA256SUMS: try again."
  # A name may be marked as binary (*) or start with ./, by whatever made the file.
  want=$(awk -v f="$file" '{ n = $2; sub(/^\*/, "", n); sub(/^\.\//, "", n) } n == f { print $1; exit }' "$TMP/SHA256SUMS" | tr 'A-F' 'a-f')
  [ -n "$want" ] \
    || die "$TAG's SHA256SUMS doesn't list $file yet, so it can't be checked, and it isn't installed unchecked. Try again in a few minutes."
  say "  Downloading $file"
  fetch "$url" "$TMP/$file" || die "the download of $file failed: try again."
  got=$(sha256_of "$TMP/$file")
  if [ "$got" != "$want" ]; then
    rm -f "$TMP/$file"
    die "$file doesn't match $TAG's SHA256SUMS, so nothing was installed: it may be a bad download, or a file being replaced. Try again in a few minutes, and if it keeps happening please open an issue."
  fi
  say "  Its checksum matches $TAG's SHA256SUMS."
}

check_sum_tool() {
  command -v sha256sum >/dev/null 2>&1 || command -v shasum >/dev/null 2>&1 \
    || die "there's nothing to check the download with: install sha256sum or shasum and run this again."
}

next_step() {
  cat <<EOF

  Next: run \`hypernova pair\` on the server, then paste the link it prints into the app and choose Pair.
  This computer has to be on the server's network or your VPN.
EOF
}

# ── Windows ──
windows() {
  local file url sums
  find_release
  file="$CLIENT_PACKAGE-$TAG-windows-x64.msi"
  url=$(asset "$file") || not_there_yet "$file"
  sums=$(asset SHA256SUMS) || sums=''
  cat <<EOF
  On Windows the client is an installer, and it's Windows that has to run it: it asks you to allow it, and warns
  first as it isn't signed. So nothing was installed from here. Download it and open it:

    $url

  What Windows shows you: $GUIDE
EOF
  [ -z "$sums" ] || say "  Its checksum is in $sums"
  next_step
}

# ── Linux ──
linux() {
  local want_tarball=$1 arch
  if [ -n "${WSL_DISTRO_NAME:-}" ] || grep -qi microsoft /proc/version 2>/dev/null; then
    say "  This is WSL: Linux inside Windows. This installs the Linux client in here, and it opens only if WSL has a display"
    say "  for it, which isn't a given. For the Windows app instead, use the .msi from $RELEASES/latest and stop this now."
  fi
  if [ "$want_tarball" = no ] && command -v apt >/dev/null 2>&1 && command -v dpkg >/dev/null 2>&1; then
    arch=$(dpkg --print-architecture)
    case "$arch" in
      amd64|arm64) install_deb "$arch"; return 0 ;;
      *) die "there's no desktop client for this processor ($arch): only amd64 and arm64, $RELEASES/latest" ;;
    esac
  fi
  case "$(uname -m)" in
    x86_64|amd64) install_tarball ;;
    aarch64|arm64)
      if [ "$want_tarball" = yes ]; then
        die "there's no tarball for Linux on arm64, only a .deb, for a system with apt."
      fi
      die "there's no desktop client for Linux on arm64 without apt: its only package is a .deb, and the tarball is x86_64."
      ;;
    *) die "there's no desktop client for this processor ($(uname -m)): only x86_64 and arm64, $RELEASES/latest" ;;
  esac
}

no_display_note() {
  if [ -z "${DISPLAY:-}" ] && [ -z "${WAYLAND_DISPLAY:-}" ]; then
    say "  This session has no display, and the client needs one: open it from this computer's desktop."
  fi
}

install_deb() {
  local arch=$1 file sudo='' version hint=''
  need curl
  check_sum_tool
  if [ "$(id -u)" -ne 0 ]; then
    command -v sudo >/dev/null 2>&1 || die "sudo is missing, and apt needs it: run this as root, or see --tarball for one under your home."
    sudo=sudo
  fi
  find_release
  file="$CLIENT_PACKAGE-$TAG-linux-$arch.deb"
  # Under /tmp and readable by all, whatever $TMPDIR and this user's umask are: apt reads a local file as its own
  # user, _apt, and says "Download is performed unsandboxed as root" about one it can't. Only this user can write here.
  TMP=$(mktemp -d "/tmp/$CLIENT_PACKAGE.XXXXXX")
  chmod 755 "$TMP"
  get_checked "$file"
  chmod 644 "$TMP/$file"
  say "  Installing with apt${sudo:+, which asks for sudo}"
  # shellcheck disable=SC2086 # sudo, or no word at all
  if ! with_terminal $sudo apt install -y "$TMP/$file"; then
    [ "$arch" != amd64 ] || hint=" On an older system, use the tarball: curl -fsSL $SELF_URL | bash -s -- --tarball"
    die "apt couldn't install $file: what it said is above. It needs Ubuntu 24.04 or Debian 13 or newer, and if a newer version is installed, remove that first.$hint"
  fi
  # shellcheck disable=SC2016 # dpkg-query's own ${Version}
  version=$(dpkg-query -W -f='${Version}' "$CLIENT_PACKAGE" 2>/dev/null) || version=''
  [ -n "$version" ] || die "apt finished, but $CLIENT_PACKAGE isn't installed: see what it said above."
  cat <<EOF

  Installed $CLIENT_TITLE $TAG (package $CLIENT_PACKAGE, version $version).
  Open it: "$CLIENT_PACKAGE" in your desktop's menu, or /opt/$CLIENT_PACKAGE/bin/$CLIENT_PACKAGE from a terminal.
EOF
  no_display_note
  next_step
}

# Whether a running program's own file is inside this folder.
in_use() {
  local e t
  for e in /proc/[0-9]*/exe; do
    t=$(readlink "$e" 2>/dev/null) || continue
    case "$t" in "$1"/*) return 0 ;; esac
  done
  return 1
}

# The app goes in a folder of its own. Not ~/.local/share/$CLIENT_PACKAGE: that's where the client keeps its key and
# pairing (DesktopStorage.kt), and an install must never touch it.
#
# Each install is a new folder in there, and `current` is a link that moves to it in one step, so at any moment
# there's a whole copy to open, old or new.
install_tarball() {
  local file base bin link want target d kept=''
  [ "$(id -u)" -ne 0 ] || die "$NOT_ROOT"
  [ -n "${HOME:-}" ] || die "HOME isn't set."
  need curl
  need tar
  check_sum_tool
  base="$HOME/.local/share/$CLIENT_PACKAGE-app"
  bin="$HOME/.local/bin"
  link="$bin/$CLIENT_PACKAGE"
  want="$base/current/bin/$CLIENT_PACKAGE"
  if [ -e "$link" ] || [ -L "$link" ]; then
    [ "$(readlink "$link" 2>/dev/null || true)" = "$want" ] || die "$link is already there and isn't this script's: move it, and run this again."
  fi
  find_release
  file="$CLIENT_PACKAGE-$TAG-linux-x64.tar.gz"
  TMP=$(mktemp -d)
  get_checked "$file"
  mkdir -p "$base" "$bin"
  STAGE=$(mktemp -d "$base/new.XXXXXX")
  tar -xzf "$TMP/$file" -C "$STAGE" || die "couldn't unpack $file. Nothing has changed."
  [ -x "$STAGE/$CLIENT_PACKAGE/bin/$CLIENT_PACKAGE" ] || die "$file has no $CLIENT_PACKAGE/bin/$CLIENT_PACKAGE. Nothing has changed."
  # What the link holds is relative, so a home that moves keeps it.
  ln -sfn "$(basename "$STAGE")/$CLIENT_PACKAGE" "$base/current.new"
  mv -T "$base/current.new" "$base/current" || { rm -f "$base/current.new"; die "couldn't move the new copy into place. Nothing has changed."; }
  target="$base/$(basename "$STAGE")"
  STAGE=''
  ln -sfn "$want" "$link.new"
  mv -T "$link.new" "$link" || { rm -f "$link.new"; die "couldn't make the link $link: the app is at $want"; }
  # Older copies, and what a run that was stopped left. One still open keeps running, so its folder stays until next time.
  for d in "$base"/new.*; do
    [ -d "$d" ] && [ "$d" != "$target" ] || continue
    if in_use "$(cd "$d" && pwd -P)"; then kept=yes; else rm -rf "$d"; fi
  done
  cat <<EOF

  Installed $CLIENT_TITLE $TAG, the tarball, in $base.
  Open it: $link
EOF
  case ":${PATH:-}:" in
    *":$bin:"*) ;;
    *) say "  ($bin isn't on your PATH here, so use the whole path, or add it to run $CLIENT_PACKAGE by name.)" ;;
  esac
  [ -z "$kept" ] || say "  An older copy is still open: quit it and open this one. Its folder goes the next time you run this."
  say "  If it stops with an error about a library, it needs your system's libGL, libX11, fontconfig, libXext, libXrender, libXtst and libXi."
  no_display_note
  next_step
}

# ── A Mac ──
app_is_running() {
  pgrep -f "$CLIENT_TITLE.app/Contents/MacOS/$CLIENT_TITLE" >/dev/null 2>&1 || pgrep -x "$CLIENT_TITLE" >/dev/null 2>&1
}

refuse_if_running() {
  if app_is_running; then
    die "$CLIENT_TITLE is running, and an open app isn't replaced: quit it and run this again."
  fi
}

install_mac() {
  local arch file apps dest other mounted_app had_old=no fallback=no
  [ "$(id -u)" -ne 0 ] || die "$NOT_ROOT"
  [ -n "${HOME:-}" ] || die "HOME isn't set."
  need curl
  need hdiutil
  need ditto
  check_sum_tool
  # A Terminal under Rosetta says x86_64 on Apple silicon: ask the hardware.
  if [ "$(sysctl -n hw.optional.arm64 2>/dev/null || echo 0)" = 1 ]; then arch=arm64; else arch=x64; fi
  refuse_if_running
  apps="${HYPERNOVA_CLIENT_APPLICATIONS:-/Applications}"
  if [ -d "$apps" ] && [ -w "$apps" ]; then
    dest=$apps
    other="$HOME/Applications"
  else
    dest="$HOME/Applications"
    other=$apps
    fallback=yes
  fi
  find_release
  file="$CLIENT_PACKAGE-$TAG-macos-$arch.dmg"
  TMP=$(mktemp -d)
  get_checked "$file"

  MOUNT="$TMP/volume"
  mkdir "$MOUNT"
  # -nobrowse: not shown in the Finder. -noverify: its checksum has just been checked. `yes`: a licence in the
  # image would otherwise wait for an answer.
  yes | hdiutil attach "$TMP/$file" -nobrowse -noverify -mountpoint "$MOUNT" >"$TMP/hdiutil.log" 2>&1 || true
  mounted_app="$MOUNT/$CLIENT_TITLE.app"
  [ -d "$mounted_app" ] || die "couldn't find $CLIENT_TITLE.app on the image: $(tail -n 1 "$TMP/hdiutil.log")"

  mkdir -p "$dest"
  APP="$dest/$CLIENT_TITLE.app"
  STAGE=$(mktemp -d "$dest/.$CLIENT_PACKAGE-new.XXXXXX")
  # What dragging it does: the signature and its attributes come too. Nothing about them is changed here.
  ditto "$mounted_app" "$STAGE/$CLIENT_TITLE.app" || die "couldn't copy the app to $dest. Nothing has changed."
  [ -x "$STAGE/$CLIENT_TITLE.app/Contents/MacOS/$CLIENT_TITLE" ] || die "the copy has no launcher. Nothing has changed."

  # It may have been opened during the download.
  refuse_if_running
  if [ -e "$APP" ]; then
    mv "$APP" "$STAGE/old.app" || die "couldn't replace $APP: move it to the Trash yourself, and run this again."
    had_old=yes
  fi
  if ! mv "$STAGE/$CLIENT_TITLE.app" "$APP"; then
    [ "$had_old" = no ] || mv "$STAGE/old.app" "$APP" || true
    die "couldn't put the new copy at $APP. Nothing has changed."
  fi

  say ""
  say "  Installed $CLIENT_TITLE $TAG in $dest."
  if [ "$fallback" = yes ]; then say "  (In your own Applications folder, as $apps isn't writable by you.)"; fi
  if [ -e "$other/$CLIENT_TITLE.app" ]; then say "  There's another copy in $other: remove it, so you don't open the old one."; fi
  cat <<EOF
  Open it: from your Applications folder, or: open "$APP"

  What macOS shows you: this file came through curl, which doesn't add the download mark that Gatekeeper's warning
  goes by, so we don't expect "Apple could not verify". This script changed no security setting, and removed no mark.
  The app isn't signed, though, and nobody has tried this on a person's own Mac yet: if macOS does block it, the
  steps are here: $GUIDE
  It may ask to find devices on local networks. Say Allow, or it may not reach a server on another computer.
EOF
  next_step
}

main() {
  local tarball=no
  while [ $# -gt 0 ]; do
    case "$1" in
      --tarball) tarball=yes ;;
      -h|--help) usage; return 0 ;;
      *) printf 'get-client.sh: what is %s? Try --help.\n' "$1" >&2; exit 2 ;;
    esac
    shift
  done
  trap finish EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM
  [ -z "${HYPERNOVA_TAG:-}" ] || check_tag "$HYPERNOVA_TAG"
  case "$(uname -s)" in
    Linux) linux "$tarball" ;;
    Darwin)
      [ "$tarball" = no ] || die "--tarball is for Linux: a Mac gets its .dmg."
      install_mac
      ;;
    MINGW*|MSYS*|CYGWIN*)
      [ "$tarball" = no ] || die "--tarball is for Linux: Windows gets its .msi."
      windows
      ;;
    *) die "there's a desktop client for Linux, a Mac and Windows, and this is $(uname -s). See $RELEASES/latest" ;;
  esac
}

main "$@"
