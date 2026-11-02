#!/bin/bash
# The notes of a release (.github/workflows/android-release.yml): what changed since the release
# before it, grouped by the commits' conventional type (Features for feat, Fixes for fix, Other for
# the rest and for messages without a type), one line per commit with its `type(scope):` taken
# off, then how to install what's attached.
#
#   scripts/release-notes.sh <tag> <previous ref or ""> [<commit>, default HEAD] [--no-server]
#
# The previous ref is the release before (scripts/next-version.sh --last). With none, the commits
# since the fork left upstream. --no-server: a release without the server package (only the APK).
set -euo pipefail
tag=$1
from=${2:-}
to=${3:-HEAD}
server=yes
[ "${4:-}" = "--no-server" ] && server=no
if [ -z "$from" ]; then
  from=$(git merge-base "$to" upstream/main 2>/dev/null || git rev-list --max-parents=0 "$to" | tail -1)
fi

features=() fixes=() other=()
while IFS= read -r subject; do
  [ -n "$subject" ] || continue
  if [[ $subject =~ ^([A-Za-z]+)(\([^\)]*\))?!?:\ +(.+)$ ]]; then
    kind=${BASH_REMATCH[1],,}
    text=${BASH_REMATCH[3]}
    text="- ${text^}"
    case $kind in
      feat) features+=("$text") ;;
      fix) fixes+=("$text") ;;
      *) other+=("$text") ;;
    esac
  else
    other+=("- $subject")
  fi
done < <(git log --no-merges --reverse --format=%s "$from..$to")

section() { # <title> <lines...>
  local title=$1; shift
  [ $# -gt 0 ] || return 0
  printf '## %s\n\n' "$title"
  printf '%s\n' "$@"
  echo
}
if [ ${#features[@]} -eq 0 ] && [ ${#fixes[@]} -eq 0 ] && [ ${#other[@]} -eq 0 ]; then
  printf '## Changes\n\n- No app or server changes; rebuilt from %s.\n\n' "$(git rev-parse --short=7 "$to")"
else
  section Features ${features[@]+"${features[@]}"}
  section Fixes ${fixes[@]+"${fixes[@]}"}
  section Other ${other[@]+"${other[@]}"}
fi

echo "## Install"
echo
echo "### Client"
echo
echo "- Android: download \`hypernova-client-$tag-android.apk\` below and open it. It updates the app in place."
if [ "$server" = yes ]; then
  cat <<EOF

### Server

- Linux (Ubuntu or Debian), the latest release in one line:
  \`\`\`
  curl -fsSL https://ljmerza.github.io/Hypernova/get.sh | bash
  hypernova pair
  \`\`\`
  This release itself: \`curl -fsSL https://ljmerza.github.io/Hypernova/get.sh | HYPERNOVA_TAG=$tag bash\`, or
  download \`hypernova-server-$tag-linux.tar.gz\` below and run
  \`\`\`
  tmp=\$(mktemp -d) && tar -xzf hypernova-server-$tag-linux.tar.gz -C "\$tmp" && "\$tmp"/hypernova-*/scripts/install-linux.sh
  \`\`\`
- Linux, as a package: \`hypernova-server-$tag-linux-amd64.deb\` or \`hypernova-server-$tag-linux-arm64.deb\`, then, as the user who will run the agents:
  \`\`\`
  sudo apt install ./hypernova-server-$tag-linux-amd64.deb
  hypernova-server-setup
  \`\`\`
- Mac (tested on CI's Mac runners only so far, and it doesn't update itself yet): the same \`curl\` line, or \`hypernova-server-$tag-macos-arm64.tar.gz\`, \`hypernova-server-$tag-macos-x64.tar.gz\`, \`hypernova-server-$tag-macos-arm64.pkg\` or \`hypernova-server-$tag-macos-x64.pkg\` (unsigned: docs/INSTALL.md, "On a Mac").

The \`.deb\` and the Mac's files come a few minutes after the rest. \`SHA256SUMS\` lists every file's checksum.

The old-named \`hypernova-$tag.apk\` and \`hypernova-$tag.json\` are only there for older servers, which look for exactly those names (and for \`hypernova-server-$tag-linux.tar.gz\`, which keeps its name): you don't need them.
EOF
fi
