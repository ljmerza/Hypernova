// The Mac-only features' routes on a Linux server (docs/PROTOCOL.md, "Mac-only features"): asked
// anyway, each answers 501 `unavailable` naming its feature, with the snapshot's sentence, rather
// than failing. A terminal window on the server's screen (POST …/open, signing in "on the Mac") and
// Stay awake. On a Mac these open windows and change sleep, so the file runs on Linux only.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const LINUX = process.platform === 'linux'
const scratch = mkdtempSync(join(tmpdir(), 'hn-maconly-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
// Plain Linux, not WSL (whose sentences differ: wsl.test.ts).
process.env.HYPERNOVA_WSL = '0'
mkdirSync(process.env.HOME, { recursive: true })
const fakeClaude = join(scratch, 'claude-bin')
writeFileSync(fakeClaude, '#!/bin/sh\necho "9.9.9 (Claude Code)"\n')
chmodSync(fakeClaude, 0o755)
process.env.CLAUDE_BIN = fakeClaude

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
const hub = await import('../src/hub.ts')
const { UNAVAILABLE } = await import('../src/platform.ts')
settings.lanAccess = false
const TOKEN = 'maconly-test-device-token-0123456789abcdef'
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

async function call(method: string, path: string, body?: unknown): Promise<{ status: number; json: any }> {
  const r = await fetch(`http://127.0.0.1:${API}${path}`, {
    method,
    headers: { authorization: `Bearer ${TOKEN}`, ...(body === undefined ? {} : { 'content-type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return { status: r.status, json: await r.json() }
}

const skip = !LINUX && 'a Mac has these features'

test('POST …/open on Linux: 501 unavailable {feature: "openWindow"} with its sentence; a session with no shared terminal is 409 no_terminal first', { skip }, async () => {
  // A session in a shared terminal (its holder needn't run: the feature is checked before the terminal is looked up).
  hub.upsertSession('sess-term', { cwd: scratch, origin: 'terminal', title: 'In a terminal', state: 'idle', term: { id: 'f0f0f0f0f0', mac: 0, phone: 0 } })
  const r = await call('POST', '/v1/sessions/sess-term/open', {})
  assert.equal(r.status, 501)
  assert.equal(r.json.error.code, 'unavailable')
  assert.equal(r.json.error.feature, 'openWindow')
  assert.equal(r.json.error.message, UNAVAILABLE.openWindow)
  assert.match(r.json.error.message, /hn attach/)

  hub.upsertSession('sess-sdk', { cwd: scratch, origin: 'hypernova', title: 'No terminal', state: 'idle' })
  const none = await call('POST', '/v1/sessions/sess-sdk/open', {})
  assert.equal(none.status, 409)
  assert.equal(none.json.error.code, 'no_terminal')
  assert.equal((await call('POST', '/v1/sessions/no-such/open', {})).json.error.code, 'no_session')

  // The snapshot says so before anyone asks: the phone hides the button.
  const snap = await call('GET', '/v1/snapshot')
  assert.equal(snap.json.mac.unavailable.openWindow, UNAVAILABLE.openWindow)
})

test('signing in "on the Mac" on Linux: 501 unavailable {feature: "openWindow"}; a bad where is 400', { skip }, async () => {
  const r = await call('POST', '/v1/account/login', { where: 'mac' })
  assert.equal(r.status, 501)
  assert.equal(r.json.error.code, 'unavailable')
  assert.equal(r.json.error.feature, 'openWindow')
  assert.equal((await call('POST', '/v1/account/login', { where: 'tv' })).json.error.code, 'bad_where')
})

test('Stay awake on Linux: GET reads Off with nothing held; POST is 501 unavailable {feature: "awake"}, whatever it asks', { skip }, async () => {
  const g = await call('GET', '/v1/mac/awake')
  assert.equal(g.status, 200)
  assert.equal(g.json.awake.mode, 'off')
  assert.equal(g.json.awake.holding, false)
  assert.equal(g.json.awake.backpackReady, false)
  assert.equal(g.json.awake.laptop, false)
  for (const body of [{ mode: 'backpack' }, { mode: 'working' }, { extend: true }, { minBattery: 20 }]) {
    const p = await call('POST', '/v1/mac/awake', body)
    assert.equal(p.status, 501, JSON.stringify(body))
    assert.equal(p.json.error.code, 'unavailable')
    assert.equal(p.json.error.feature, 'awake')
    assert.equal(p.json.error.message, UNAVAILABLE.awake)
  }
  // Still Off after all that.
  assert.equal((await call('GET', '/v1/mac/awake')).json.awake.mode, 'off')
  const snap = await call('GET', '/v1/snapshot')
  assert.equal(snap.json.mac.battery, null)
  assert.equal(snap.json.mac.awake.mode, 'off')
  assert.deepEqual(Object.keys(snap.json.mac.unavailable).sort(), ['awake', 'battery', 'macVoice', 'openWindow'])
})
