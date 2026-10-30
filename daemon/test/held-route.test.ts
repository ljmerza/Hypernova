// Fork: held prompts through the prompt route (http.ts, held.ts) with `claude agents` jobs and a
// stand-in claude (see claude-agents.test.ts). `hold: true` while an approval is open, or while the
// terminal shows a dialog instead of the input box: 200 with `held` and the session's `held`; an
// app that doesn't ask keeps the 409. Answering the approval sends it, through `claude attach`.
// PUT and DELETE /v1/sessions/{id}/held/{heldId} edit it and take it back.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawn, type ChildProcess } from 'node:child_process'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-held-route-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
process.env.FAKE_AGENTS_DIR = join(scratch, 'fake')
for (const d of [process.env.HOME, process.env.ZDOTDIR, process.env.FAKE_AGENTS_DIR]) mkdirSync(d, { recursive: true })
const registry = join(process.env.CLAUDE_CONFIG_DIR, 'sessions')
mkdirSync(registry, { recursive: true })
const fake = join(scratch, 'fake-claude')
writeFileSync(
  fake,
  `#!/bin/sh
case "$1" in
  --version) echo "9.9.9 (Claude Code)" ;;
  attach)
    if [ "$2" = "ask00000" ]; then printf '\\n Do you want to proceed?\\n ❯ 1. Yes\\n   2. No\\n'; exec sleep 30; fi
    printf '\\n────────────────────\\n❯ \\n────────────────────\\n'
    exec sleep 30
    ;;
  *) exec sleep 30 ;;
esac
`,
)
chmodSync(fake, 0o755)
process.env.CLAUDE_BIN = fake

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

const hub = await import('../src/hub.ts')
const { settings, addDevice } = await import('../src/store.ts')
const { startServers, promptOrHold, heldReady } = await import('../src/http.ts')
const { heldOf } = await import('../src/held.ts')
const TOKEN = 'held-test-device-token-0123456789ab'
addDevice({ id: 'phone', name: 'Phone', model: 'Pixel', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)

const procs: ChildProcess[] = []
let close: () => Promise<void>
before(async () => {
  close = startServers()
  for (let i = 0; i < 50; i++) {
    try {
      if ((await call('GET', '/v1/health')).status === 200) return
    } catch {}
    await new Promise((r) => setTimeout(r, 20))
  }
})
after(async () => {
  await close?.()
  for (const p of procs) p.kill('SIGKILL')
  const terms = join(process.env.HYPERNOVA_SUPPORT!, 'terms')
  for (const dir of [terms, registry]) {
    for (const f of existsSync(dir) ? readdirSync(dir) : []) {
      if (!f.endsWith('.json')) continue
      try {
        process.kill(JSON.parse(readFileSync(join(dir, f), 'utf8')).pid, 'SIGKILL')
      } catch {}
    }
  }
  rmSync(scratch, { recursive: true, force: true })
})

function call(method: string, path: string, body?: object): Promise<{ status: number; json: any }> {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : undefined
    const req = request({ host: '127.0.0.1', port: API, method, path, headers: { host: `127.0.0.1:${API}`, authorization: `Bearer ${TOKEN}`, ...(data ? { 'content-type': 'application/json' } : {}) } }, (res) => {
      let s = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (s += c))
      res.on('end', () => {
        let json: any = null
        try {
          json = JSON.parse(s)
        } catch {}
        resolve({ status: res.statusCode ?? 0, json })
      })
    })
    req.on('error', reject)
    req.end(data)
  })
}

/** A `claude agents` job for session [id], registered as Claude Code registers a background one. */
const listed: { id: string; sessionId: string; cwd: string; state: string }[] = []
function job(id: string, jobId: string, status = 'idle'): void {
  const p = spawn('sleep', ['30'], { stdio: 'ignore' })
  procs.push(p)
  writeFileSync(join(registry, `${p.pid}.json`), JSON.stringify({ pid: p.pid, sessionId: id, cwd: scratch, kind: 'bg', jobId, status, updatedAt: Date.now() }))
  // A job `claude agents` lists (only those count, not its pre-started spares).
  if (!listed.some((j) => j.sessionId === id)) listed.push({ id: jobId, sessionId: id, cwd: scratch, state: 'working' })
  hub.applyAgentList([...listed])
  hub.pollRegistry()
}

const ask = (sessionId: string) =>
  hub.requestApproval({ sessionId, kind: 'tool', toolName: 'Bash', summary: 'Bash: ls', input: { command: 'ls' }, reason: null, canAlways: false, questions: null, plan: null }, null)

async function until(f: () => boolean, ms = 20_000): Promise<boolean> {
  const t0 = Date.now()
  while (!f()) {
    if (Date.now() - t0 > ms) return false
    await new Promise((r) => setTimeout(r, 100))
  }
  return true
}

test('an approval open: hold: true is held (200, held, heldId, queued); without it, 409 needs_answer as before', async () => {
  settings.claudeAgents = true
  job('held-ask-1', 'hhhh0001')
  void ask('held-ask-1')
  assert.equal(heldReady('held-ask-1'), 'wait')
  const old = await call('POST', '/v1/sessions/held-ask-1/prompt', { text: 'and then?' })
  assert.equal(old.status, 409)
  assert.equal(old.json?.error?.code ?? old.json?.code, 'needs_answer')
  const r = await call('POST', '/v1/sessions/held-ask-1/prompt', { text: 'and then?', clientId: 'phone-send-1', hold: true })
  assert.equal(r.status, 200)
  assert.equal(r.json.held, true)
  assert.equal(r.json.queued, true, 'an app that only reads queued shows it waiting')
  assert.ok(r.json.heldId)
  assert.deepEqual(r.json.session.held.map((h: any) => [h.id, h.text, h.clientId]), [[r.json.heldId, 'and then?', 'phone-send-1']])
  // The same send again (the phone never saw the answer): the same answer, held once.
  const again = await call('POST', '/v1/sessions/held-ask-1/prompt', { text: 'and then?', clientId: 'phone-send-1', hold: true })
  assert.equal(again.json.heldId, r.json.heldId)
  assert.equal(heldOf('held-ask-1').length, 1)
})

test('behind a held prompt, the next is held too, so they go in order', async () => {
  const r = await promptOrHold('held-ask-1', 'second', 'phone-send-2')
  assert.ok(r.heldId)
  assert.deepEqual(heldOf('held-ask-1').map((e) => e.prompt), ['and then?', 'second'])
})

test('PUT edits a held prompt, DELETE takes it back; one that left is 404 no_held', async () => {
  const second = heldOf('held-ask-1')[1]!
  const ed = await call('PUT', `/v1/sessions/held-ask-1/held/${second.id}`, { text: 'second, edited' })
  assert.equal(ed.status, 200)
  assert.equal(ed.json.session.held[1].text, 'second, edited')
  assert.equal((await call('PUT', `/v1/sessions/held-ask-1/held/${second.id}`, { text: ' ' })).status, 400)
  assert.equal((await call('DELETE', `/v1/sessions/held-ask-1/held/${second.id}`)).status, 200)
  assert.equal(heldOf('held-ask-1').length, 1)
  const gone = await call('DELETE', `/v1/sessions/held-ask-1/held/${second.id}`)
  assert.equal(gone.status, 404)
  assert.equal((await call('DELETE', `/v1/sessions/nope/held/${second.id}`)).status, 404)
})

test('answered: the held prompt goes in through claude attach by itself', async () => {
  const [a] = hub.pendingApprovals().filter((x) => x.sessionId === 'held-ask-1')
  assert.ok(a)
  hub.answerApproval(a.id, { decision: 'deny' }, 'phone')
  // The stand-in's box never empties on Enter, so the send ends "returned": in the terminal's box,
  // named in a notice for the phone to take back. What counts is that it went, unasked.
  assert.ok(await until(() => heldOf('held-ask-1').length === 0), 'left the line')
  const n = hub.eventsFor('held-ask-1').find((e: any) => e.kind === 'notice' && e.unsent)
  assert.equal((n as any)?.unsent?.reason, 'returned')
  assert.deepEqual((n as any)?.unsent?.prompts, [{ text: 'and then?', clientId: 'phone-send-1' }])
  assert.equal(hub.listSessions().find((s) => s.id === 'held-ask-1')?.held, undefined)
})

test('a dialog in the terminal instead of the input box (no_input → waiting) is held too', async () => {
  settings.claudeAgents = true
  job('held-dialog-1', 'ask00000', 'idle')
  const r = await promptOrHold('held-dialog-1', 'when you can', 'phone-send-3')
  assert.ok(r.heldId, 'held, not 409 waiting')
  assert.equal(r.queued, true)
  assert.equal(heldOf('held-dialog-1').length, 1)
})
