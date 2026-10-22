#!/bin/bash
# Builds the Tailscale helper (daemon/tsnet) for this Mac and for Linux servers into daemon/bin:
#   hypernova-tsnet-darwin-arm64, hypernova-tsnet-linux-amd64, hypernova-tsnet-linux-arm64
# Pure Go, so every target cross-compiles from here. Go: Homebrew's, or whatever is on PATH.
#   daemon/tsnet/build.sh            all three
#   daemon/tsnet/build.sh --host     only this machine's
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../bin"
export PATH="/opt/homebrew/bin:/usr/local/go/bin:$PATH"
command -v go >/dev/null || { echo "build.sh: go is not installed (brew install go)" >&2; exit 1; }
mkdir -p "$OUT"
build() {
  local os="$1" arch="$2"
  echo "▸ hypernova-tsnet-$os-$arch"
  (cd "$HERE" && CGO_ENABLED=0 GOOS="$os" GOARCH="$arch" go build -trimpath -ldflags="-s -w" -o "$OUT/hypernova-tsnet-$os-$arch" .)
}
if [ "${1:-}" = "--host" ]; then
  build "$(go env GOOS)" "$(go env GOARCH)"
else
  build darwin arm64
  build linux amd64
  build linux arm64
fi
ls -la "$OUT" | grep hypernova-tsnet
