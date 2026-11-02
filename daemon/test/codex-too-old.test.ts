// A Codex older than Hypernova's floor, through the phone's routes: each says so, by name, and nothing but `--version` is run. Which
// install is picked when there are several. HN_E2E_OLD_CODEX runs the same against a real old Codex (0.39.0, by hand).
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-codex-old-')))
const HOME = join(scratch, 'home')
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.CODEX_HOME = join(scratch, 'codex')
process.env.HOME = HOME
process.env.ZDOTDIR = join(scratch, 'zdot')
process.env.HYPERNOVA_WSL = '0'
process.env.CLAUDE_BIN = join(scratch, 'none')
for (const d of [HOME, process.env.CLAUDE_CONFIG_DIR, process.env.CODEX_HOME]) mkdirSync(d, { recursive: true })

/** A stand-in for Codex at [path] that says [version] and notes anything else it's asked. */
const ASKED = join(scratch, 'asked.log')
function fake(path: string, version: string): string {
  mkdirSync(join(path, '..'), { recursive: true })
  writeFileSync(path, `#!/bin/sh\nif [ "$1" = "--version" ]; then echo "codex-cli ${version}"; exit 0; fi\necho "$*" >> "${ASKED}"\nexit 0\n`)
  chmodSync(path, 0o755)
  return path
}
const OLD = process.env.HN_E2E_OLD_CODEX ?? fake(join(scratch, 'old/codex'), '0.39.0')
process.env.HYPERNOVA_CODEX = OLD

const freePort = () =>
  new Promise<number>((resolve) => {
    const s = createServer()
    s.listen(0, '127.0.0.1', () => {
      const port = (s.address() as { port: number }).port
      s.close(() => resolve(port))
    })
  })
const API = await freePort()
process.env.HYPERNOVA_API_PORT = String(API)
process.env.HYPERNOVA_LOCAL_PORT = String(await freePort())
process.env.HYPERNOVA_LAN_PORT = '0'

const { startServers } = await import('../src/http.ts')
const { addDevice, settings } = await import('../src/store.ts')
const { CODEX_MIN, findCodex } = await import('../src/codex/binary.ts')
const { codexBinary } = await import('../src/codex/client.ts')
settings.lanAccess = false
const TOKEN = 'codex-too-old-test-device-token-0123456789'
addDevice({ id: 'phone', name: 'Pixel', model: 'Pixel 9', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)

let close: () => Promise<void>
before(async () => {
  close = startServers()
  for (let i = 0; i < 100; i++) {
    try {
      if ((await fetch(`http://127.0.0.1:${API}/v1/health`)).ok) return
    } catch {}
    await new Promise((r) => setTimeout(r, 20))
  }
})
after(async () => {
  await close?.()
  rmSync(scratch, { recursive: true, force: true })
})

const call = async (method: string, path: string, body?: unknown) => {
  const r = await fetch(`http://127.0.0.1:${API}${path}`, { method, headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
  return { status: r.status, body: (await r.json()) as any }
}

const codex = { agent: 'codex' }
const routes: [string, string, unknown?][] = [
  ['GET', '/v1/agents/mcp?agent=codex'],
  ['GET', '/v1/agents/mcp/status?agent=codex'],
  ['GET', '/v1/agents/mcp/server?agent=codex&name=tiny'],
  ['GET', '/v1/agents/mcp/tools?agent=codex&name=tiny'],
  ['POST', '/v1/agents/mcp/servers', { ...codex, scope: 'user', name: 'tiny', transport: 'stdio', command: 'node', args: [], env: [], confirmRun: true }],
  ['PUT', '/v1/agents/mcp/servers', { ...codex, scope: 'user', name: 'tiny', transport: 'stdio', command: 'node', args: [], env: [], confirmRun: true, expected: 'x' }],
  ['POST', '/v1/agents/mcp/servers/remove', { ...codex, name: 'tiny', expected: 'x' }],
  ['POST', '/v1/agents/mcp/toggle', { ...codex, name: 'tiny', enabled: false }],
  ['POST', '/v1/agents/mcp/reconnect', { ...codex, name: 'tiny' }],
  ['POST', '/v1/agents/mcp/login', { ...codex, name: 'tiny' }],
  ['POST', '/v1/agents/mcp/logout', { ...codex, name: 'tiny' }],
  ['GET', '/v1/agents/plugins?agent=codex'],
  ['GET', '/v1/agents/plugins/available?agent=codex'],
  ['GET', '/v1/agents/plugins/detail?agent=codex&plugin=hello@testmp'],
  ['POST', '/v1/agents/plugins/install', { ...codex, plugin: 'hello@testmp', confirmRun: true }],
  ['POST', '/v1/agents/plugins/uninstall', { ...codex, plugin: 'hello@testmp' }],
  ['POST', '/v1/agents/plugins/toggle', { ...codex, plugin: 'hello@testmp', enabled: false }],
  ['POST', '/v1/agents/plugins/marketplaces/add', { ...codex, source: 'acme/plugins', confirmRun: true }],
  ['POST', '/v1/agents/plugins/marketplaces/remove', { ...codex, name: 'testmp' }],
  ['POST', '/v1/agents/plugins/marketplaces/update', { ...codex, name: 'testmp', confirmRun: true }],
  ['GET', '/v1/agents/env?agent=codex'],
  ['POST', '/v1/agents/env', { ...codex, name: 'MY_VAR', value: 'x', expected: null }],
  ['POST', '/v1/agents/env/remove', { ...codex, name: 'MY_VAR', expected: null }],
  ['GET', '/v1/agents/skills?agent=codex'],
  ['POST', '/v1/agents/skills', { ...codex, name: 'greet', state: 'off' }],
]

test('every route that runs Codex refuses an old one by name, with the version and the floor, and runs nothing of it', async () => {
  const { version } = codexBinary()
  assert.ok(version, 'the old Codex says its version')
  for (const [method, path, body] of routes) {
    const r = await call(method, path, body)
    const where = `${method} ${path}: ${JSON.stringify(r.body)}`
    assert.equal(r.status, 409, where)
    assert.equal(r.body.error.code, 'codex_too_old', where)
    assert.equal(r.body.error.version, version, where)
    assert.equal(r.body.error.min, CODEX_MIN, where)
    assert.ok(r.body.error.message.includes(version) && r.body.error.message.includes(CODEX_MIN), where)
  }
  assert.equal(existsSync(ASKED) ? readFileSync(ASKED, 'utf8') : '', '', 'an old Codex was asked for more than its version')
})

test('Claude Code’s routes don’t care how old Codex is', async () => {
  const r = await call('GET', '/v1/agents/mcp?agent=claude')
  assert.equal(r.status, 200, JSON.stringify(r.body))
})

test('a Codex that won’t say its version is refused too', async () => {
  const silent = join(scratch, 'silent/codex')
  mkdirSync(join(silent, '..'), { recursive: true })
  writeFileSync(silent, '#!/bin/sh\nexit 1\n')
  chmodSync(silent, 0o755)
  process.env.HYPERNOVA_CODEX = silent
  const r = await call('GET', '/v1/agents/mcp?agent=codex')
  process.env.HYPERNOVA_CODEX = OLD
  assert.equal(r.status, 409)
  assert.equal(r.body.error.code, 'codex_too_old')
  assert.equal(r.body.error.version, null)
})

test('two installs under nvm: the newer Codex, even on the older Node', () => {
  const older = fake(join(HOME, '.nvm/versions/node/v24.0.0/bin/codex'), '0.39.0')
  const newer = fake(join(HOME, '.nvm/versions/node/v20.0.0/bin/codex'), '99.0.0')
  const was = process.env.HYPERNOVA_CODEX
  delete process.env.HYPERNOVA_CODEX
  try {
    assert.equal(findCodex(), newer)
    assert.notEqual(findCodex(), older)
    assert.equal(codexBinary().version, '99.0.0')
    assert.equal(codexBinary().ok, true)
  } finally {
    process.env.HYPERNOVA_CODEX = was
  }
  // A named one is used as it is, old or not.
  process.env.HYPERNOVA_CODEX = older
  assert.equal(findCodex(), older)
  process.env.HYPERNOVA_CODEX = was
})
