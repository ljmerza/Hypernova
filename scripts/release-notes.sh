#!/bin/bash
# The notes of a fork release (.github/workflows/android-release.yml): what changed since the
# release before it, one line per commit, and how to install what's attached.
#
#   scripts/release-notes.sh <tag> <previous ref or ""> [<commit>, default HEAD] [--no-server]
#
# With no previous ref, the commits since the fork left upstream. --no-server: a release from before
# the server package was attached (it has only the APK).
set -euo pipefail
tag=$1
from=${2:-}
to=${3:-HEAD}
server=yes
[ "${4:-}" = "--no-server" ] && server=no
if [ -z "$from" ]; then
  from=$(git merge-base "$to" upstream/main 2>/dev/null || git rev-list --max-parents=0 "$to" | tail -1)
fi

echo "## Changes"
echo
changes=$(git log --no-merges --reverse --format='- %s' "$from..$to")
if [ -n "$changes" ]; then echo "$changes"; else echo "- No app or server changes; rebuilt from ${to::7}."; fi
echo
echo "## Install"
echo
echo "- Phone: download \`hypernova-$tag.apk\` below and open it. It updates the app in place."
if [ "$server" = yes ]; then
  cat <<EOF
- Server (Linux): download \`hypernova-server-$tag-linux.tar.gz\` below, then run
  \`\`\`
  tmp=\$(mktemp -d) && tar -xzf hypernova-server-$tag-linux.tar.gz -C "\$tmp" && "\$tmp"/hypernova-*/scripts/install-linux.sh
  hypernova pair
  \`\`\`
EOF
fi
