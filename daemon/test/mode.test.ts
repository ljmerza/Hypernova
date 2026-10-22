// The permission-mode route: a session the daemon runs can switch to Bypass, a shared terminal
// only when it was started with bypass allowed, and the snapshot says which before the tap.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-mode-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
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
const LOCAL = await freePort()
process.env.HYPERNOVA_API_PORT = String(API)
process.env.HYPERNOVA_LOCAL_PORT = String(LOCAL)
// The LAN listener (lan.ts) takes a free port: test files run side by side.
process.env.HYPERNOVA_LAN_PORT = '0'

const { startServers } = await import('../src/http.ts')
const { addDevice, getManaged, saveManaged } = await import('../src/store.ts')
const { getSession, listSessions, upsertSession } = await import('../src/hub.ts')
const { bypassAllowed } = await import('../src/terms.ts')
const TOKEN = 'test-device-token-0123456789abcdef'
addDevice({ id: 'phone', name: 'Phone', model: 'P', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)

let close: () => Promise<void>
before(async () => {
  close = startServers()
  await new Promise((r) => setTimeout(r, 200))
})
after(async () => {
  await close?.()
  rmSync(scratch, { recursive: true, force: true })
})

function post(path: string, body: object): Promise<{ status: number; json: any }> {
  return new Promise((resolve, reject) => {
    const req = request({ host: '127.0.0.1', port: API, method: 'POST', path, headers: { host: `127.0.0.1:${API}`, authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' } }, (res) => {
      let data = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (data += c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, json: data ? JSON.parse(data) : null }))
    })
    req.on('error', reject)
    req.end(JSON.stringify(body))
  })
}

test('a session the daemon runs switches to Bypass, and says so in its snapshot', async () => {
  const id = 'sdk-session'
  saveManaged({ id, cwd: scratch, providerId: 'anthropic', model: null, permissionMode: 'default', createdAt: Date.now() })
  upsertSession(id, { cwd: scratch, origin: 'hypernova' })
  assert.equal(listSessions().find((s) => s.id === id)?.bypassAvailable, true)
  const r = await post(`/v1/sessions/${id}/mode`, { permissionMode: 'bypassPermissions' })
  assert.equal(r.status, 200)
  assert.equal(r.json.session.permissionMode, 'bypassPermissions')
  assert.equal(r.json.applies, 'now') // no turn is running
  assert.equal(getManaged(id)?.permissionMode, 'bypassPermissions')
  // And back, so the next turn starts asking again.
  const back = await post(`/v1/sessions/${id}/mode`, { permissionMode: 'default' })
  assert.equal(back.json.session.permissionMode, 'default')
  assert.equal(getManaged(id)?.permissionMode, 'default')
})

test('a shared terminal started without bypass allowed can’t switch to it: 409 mode_unavailable', async () => {
  const id = 'term-session'
  saveManaged({ id, cwd: scratch, providerId: 'anthropic', model: null, permissionMode: 'default', createdAt: Date.now(), engine: 'term' })
  upsertSession(id, { cwd: scratch, origin: 'hypernova', term: { id: 'T1', mac: 0, phone: 0 }, bypassAvailable: false })
  assert.equal(listSessions().find((s) => s.id === id)?.bypassAvailable, false)
  const r = await post(`/v1/sessions/${id}/mode`, { permissionMode: 'bypassPermissions' })
  assert.equal(r.status, 409)
  assert.equal(r.json.error.code, 'mode_unavailable')
  assert.equal(getSession(id)?.permissionMode, 'default')
})

test('a terminal already in bypass reports it available', () => {
  const id = 'term-bypass'
  upsertSession(id, { cwd: scratch, origin: 'terminal', term: { id: 'T2', mac: 0, phone: 0 }, bypassAvailable: false, permissionMode: 'bypassPermissions' })
  assert.equal(listSessions().find((s) => s.id === id)?.bypassAvailable, true)
})

test('bypass is allowed in a terminal only by the flags that allow it', () => {
  assert.equal(bypassAllowed(['--session-id', 'x']), false)
  assert.equal(bypassAllowed(['--permission-mode', 'plan']), false)
  assert.equal(bypassAllowed(['--allow-dangerously-skip-permissions']), true)
  assert.equal(bypassAllowed(['--dangerously-skip-permissions']), true)
  assert.equal(bypassAllowed(['--permission-mode', 'bypassPermissions']), true)
  assert.equal(bypassAllowed(['--permission-mode=bypassPermissions']), true)
  // A holder that never said how it started: try, and let the switch report.
  assert.equal(bypassAllowed(undefined), true)
})
