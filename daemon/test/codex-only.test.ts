// A computer with Codex and no Claude Code (docs/PROTOCOL.md, "Codex", Which agent leads): the
// snapshot says Claude Code isn't here, a session with no agent named goes to Codex, the phone turns
// Codex on and off, and Statistics count Codex's turns, split per agent.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, realpathSync, rmSync } from 'node:fs'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { FakeCodex, fakeCodexBin } from './fake-codex/server.ts'

const scratch = mkdtempSync(join(tmpdir(), 'hn-cxo-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
// No Claude Code anywhere this daemon looks (its usual places are under HOME; the login shell's PATH).
delete process.env.CLAUDE_BIN
process.env.PATH = '/usr/bin:/bin'
mkdirSync(join(scratch, 'home', 'Programming', 'solo'), { recursive: true })
const project = realpathSync(join(scratch, 'home', 'Programming', 'solo'))
process.env.HYPERNOVA_CODEX = fakeCodexBin(join(scratch, 'codex'))
process.env.HYPERNOVA_CODEX_SOCKET = join(scratch, 'cx.sock')
process.env.HYPERNOVA_LAN_PORT = '0'
const freePort = () =>
  new Promise<number>((resolve) => {
    const s = createServer()
    s.listen(0, '127.0.0.1', () => {
      const port = (s.address() as { port: number }).port
      s.close(() => resolve(port))
    })
  })
const LOCAL = await freePort()
process.env.HYPERNOVA_API_PORT = String(await freePort())
process.env.HYPERNOVA_LOCAL_PORT = String(LOCAL)

const codex = new FakeCodex()
await codex.start(process.env.HYPERNOVA_CODEX_SOCKET)
const { startServers } = await import('../src/http.ts')
const { adminToken } = await import('../src/store.ts')
const { listSessions } = await import('../src/hub.ts')

let close: () => Promise<void>
before(async () => {
  close = startServers()
  for (let i = 0; i < 50; i++) {
    try {
      if ((await call('GET', '/v1/health')).status === 200) return
    } catch {}
    await sleep(20)
  }
})
after(async () => {
  await close?.()
  await codex.close()
  rmSync(scratch, { recursive: true, force: true })
})
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
async function call(method: string, path: string, body?: unknown): Promise<{ status: number; body: any }> {
  const r = await fetch(`http://127.0.0.1:${LOCAL}${path}`, { method, headers: { authorization: `Bearer ${adminToken}`, 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
  return { status: r.status, body: await r.json() }
}
async function until(f: () => boolean, ms = 3000): Promise<void> {
  for (let t = 0; t < ms && !f(); t += 20) await sleep(20)
  assert.ok(f(), 'timed out waiting')
}

test('without Claude Code, the snapshot says so and a session with no agent named goes to Codex', async () => {
  const snap = (await call('GET', '/v1/snapshot')).body
  // `sdk`: whether Claude Code's SDK is in the daemon's packages (sdk.ts), here or not.
  const { sdk, ...claude } = snap.claude
  assert.deepEqual(claude, { available: false, version: null })
  assert.equal(typeof sdk, 'boolean')
  assert.equal(snap.codex.available, true)
  const r = await call('POST', '/v1/sessions', { projectPath: project, prompt: 'hello' })
  assert.equal(r.status, 200, JSON.stringify(r.body))
  assert.equal(r.body.session.agent, 'codex')
  await until(() => listSessions().find((s) => s.id === r.body.session.id)?.state === 'idle')
})

test('the phone turns Codex off and on', async () => {
  const off = await call('POST', '/v1/codex', { enabled: false })
  assert.equal(off.status, 200)
  assert.equal(off.body.codex.enabled, false)
  assert.equal((await call('POST', '/v1/codex', {})).body.error.code, 'bad_value')
  const on = await call('POST', '/v1/codex', { enabled: true })
  assert.equal(on.body.codex.enabled, true)
})

test('Statistics count Codex’s turns: in the totals, per day, and split per agent', async () => {
  const id = (await call('POST', '/v1/sessions', { agent: 'codex', projectPath: project, prompt: 'count me' })).body.session.id
  await until(() => listSessions().find((s) => s.id === id)?.state === 'idle')
  await sleep(50)
  const s = (await call('GET', '/v1/stats?range=today')).body
  assert.ok(s.byAgent.codex, JSON.stringify(s.byAgent))
  assert.ok(s.byAgent.codex.ms > 0)
  assert.equal(s.byAgent.codex.sessions, 2, 'both threads, each on the day of its first turn')
  assert.equal(s.byAgent.codex.prompts, 2)
  // 1000 in (200 cached) and 50 out per turn: 800 + 200 + 50.
  assert.equal(s.byAgent.codex.tokens, 2 * 1050)
  assert.equal(s.byAgent.claude.ms, 0)
  assert.equal(s.claudeMs, s.byAgent.codex.ms, 'the totals hold Codex’s work too')
  assert.equal(s.tokens.cacheRead, 400)
  assert.equal(s.sessions, 2)
  assert.equal(s.projects[0].name, 'solo')
  assert.equal(s.projects[0].path, project)
  const today = s.days.at(-1)
  assert.equal(today.codexMs, s.byAgent.codex.ms)
  assert.ok(s.longestSession, 'a Codex session can be the longest')
})
