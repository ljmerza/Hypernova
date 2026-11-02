#!/usr/bin/env bash
# The "Not built" lines of a release's notes (android-release.yml's checksums job): the notes on standard input, the
# same notes on standard output, saying which of the files that come after the release isn't in <folder>. The client's
# line goes at the end of the Client heading, the server's at the very end. Run again it replaces its own lines, so a
# job re-run that brought a file leaves no stale word about it.
#
#   gh release view "$TAG" --json body --jq .body | scripts/release-not-built.sh <tag> <folder of the release's files>
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$HERE/../android/desktop/packaging/client-names.sh"
tag=$1
dir=$2
MARK='**Not built for this release:**'

missing() {
  local f out=''
  for f in "$@"; do [ -e "$dir/$f" ] || out="$out \`$f\`"; done
  printf '%s' "$out"
}
client="$(missing "$CLIENT_PACKAGE-$tag-linux-amd64.deb" "$CLIENT_PACKAGE-$tag-linux-x64.tar.gz" "$CLIENT_PACKAGE-$tag-linux-arm64.deb" \
  "$CLIENT_PACKAGE-$tag-macos-arm64.dmg" "$CLIENT_PACKAGE-$tag-macos-x64.dmg" "$CLIENT_PACKAGE-$tag-windows-x64.msi")"
server="$(missing "hypernova-server-$tag-linux-amd64.deb" "hypernova-server-$tag-linux-arm64.deb" \
  "hypernova-server-$tag-macos-arm64.tar.gz" "hypernova-server-$tag-macos-arm64.pkg" \
  "hypernova-server-$tag-macos-x64.tar.gz" "hypernova-server-$tag-macos-x64.pkg")"

MARK="$MARK" CLIENT="$client" SERVER="$server" awk '
  BEGIN { mark = ENVIRON["MARK"]; client = ENVIRON["CLIENT"]; server = ENVIRON["SERVER"] }
  # A line of an earlier run goes, and the blank line after it.
  index($0, mark) == 1 { dropped = 1; next }
  dropped && $0 == "" { dropped = 0; next }
  { dropped = 0 }
  $0 == "### Server" && client != "" { line[++n] = mark client; line[++n] = ""; client = "" }
  { line[++n] = $0 }
  END {
    while (n > 0 && line[n] == "") n--
    for (i = 1; i <= n; i++) print line[i]
    if (client != "") { print ""; print mark client }
    if (server != "") { print ""; print mark server }
  }
'
