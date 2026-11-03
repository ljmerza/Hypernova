#!/usr/bin/env bash
# Makes the menu entry of a jpackage .deb optional, in place. Run by desktop/build.gradle.kts after packageDeb.
#
# jpackage's post-install and pre-remove scripts run under `set -e` and call xdg-desktop-menu. On a system with no
# menu folder (a container, a bare server, a desktop with no menus) it exits 3, "No writable system menu directory
# found", so the package can't be installed or removed. xdg-utils is there either way: jpackage already depends on it.
# With this, such a system gets the app without a menu entry and a line saying so. Anywhere else nothing changes.
#
# It also recommends libsecret-tools, whose secret-tool is how the app reaches a keychain on Linux (docs/DECISIONS.md,
# "The desktop client's key in the keychain"). Only recommends: with no keychain the app keeps its key in a private file.
set -euo pipefail

deb="$1"
work="$(mktemp -d -p "$(dirname "$deb")")"
trap 'rm -rf "$work"' EXIT

dpkg-deb -f "$deb" Depends | grep -qw xdg-utils || { echo "$deb doesn't depend on xdg-utils" >&2; exit 1; }
dpkg-deb -R "$deb" "$work/root"
for script in postinst prerm; do
    file="$work/root/DEBIAN/$script"
    sed -i 's|^xdg-desktop-menu .*$|& \|\| echo "No system menu here: the app has no menu entry." >\&2|' "$file"
    # A later jpackage that words this differently should stop the build, not ship the failure again.
    grep -q '^xdg-desktop-menu .* || echo ' "$file" || { echo "no xdg-desktop-menu line in $script" >&2; exit 1; }
done
sed -i '/^Depends:/a Recommends: libsecret-tools' "$work/root/DEBIAN/control"
grep -q '^Recommends: libsecret-tools$' "$work/root/DEBIAN/control" || { echo "no Depends line to put Recommends after" >&2; exit 1; }
dpkg-deb --root-owner-group -b "$work/root" "$deb" >/dev/null
