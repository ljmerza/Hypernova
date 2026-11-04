#!/usr/bin/env bash
# Fetches the two settings schemas the daemon ships (daemon/src/schemas, docs/DECISIONS.md, "Every setting, from the agents' own
# schemas"). By hand only: the daemon and the clients never make this call. Nothing goes in unless it's a JSON Schema with
# properties. It then says which keys came and went: daemon/test/agent-settings-form.test.ts fails on any key that isn't in
# daemon/src/agent-settings-classes.ts, so a new one gets read and classified before it can ship.
#
#   scripts/refresh-agent-schemas.sh [codex-version]    e.g. 0.164.0; without one, what `codex --version` says
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/daemon/src/schemas"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

VERSION="${1:-$(codex --version 2>/dev/null | awk '{print $2}')}"
[ -n "$VERSION" ] || { echo "Say which Codex version: Codex isn't on the PATH." >&2; exit 2; }
CLAUDE_URL="https://www.schemastore.org/claude-code-settings.json"
CODEX_URL="https://raw.githubusercontent.com/openai/codex/rust-v$VERSION/codex-rs/core/config.schema.json"

curl -fsSL -m 60 -o "$TMP/claude.json" "$CLAUDE_URL"
curl -fsSL -m 60 -o "$TMP/codex.json" "$CODEX_URL"

node --input-type=module - "$TMP" "$OUT" "$VERSION" "$CODEX_URL" <<'NODE'
import { readFileSync, writeFileSync } from 'node:fs'
const [tmp, out, version, codexUrl] = process.argv.slice(2)
const today = new Date().toISOString().slice(0, 10)
const sources = JSON.parse(readFileSync(`${out}/sources.json`, 'utf8'))
const parts = [['claude', 'claude.json', sources.claude.file], ['codex', 'codex.json', sources.codex.file]]
for (const [name, from, to] of parts) {
  const text = readFileSync(`${tmp}/${from}`, 'utf8')
  const next = JSON.parse(text)
  if (!next || typeof next.properties !== 'object') throw new Error(`${name}: that isn't a schema with properties`)
  const was = Object.keys(JSON.parse(readFileSync(`${out}/${to}`, 'utf8')).properties)
  const now = Object.keys(next.properties)
  const came = now.filter((k) => !was.includes(k))
  const went = was.filter((k) => !now.includes(k))
  console.log(`${name}: ${now.length} keys. New: ${came.join(', ') || 'none'}. Gone: ${went.join(', ') || 'none'}.`)
  writeFileSync(`${out}/${to}`, text)
  sources[name].fetched = today
}
sources.codex.url = codexUrl
sources.codex.from = `OpenAI Codex (https://github.com/openai/codex), tag rust-v${version}`
sources.codex.checkedAgainst = `Codex ${version}`
writeFileSync(`${out}/sources.json`, JSON.stringify(sources, null, 2) + '\n')
NODE

echo "Next: read what each new key does, classify it in daemon/src/agent-settings-classes.ts, set sources.json's claude.checkedAgainst"
echo "to the Claude Code you checked, and run: cd daemon && node --test test/agent-settings-form.test.ts test/settings-form-e2e.test.ts"
