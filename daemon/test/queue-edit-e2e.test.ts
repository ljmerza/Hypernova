// One waiting message removed or edited, with the real Claude Code in a throwaway home against the fake
// provider (docs/PROTOCOL.md, "Taking a prompt out of the queue"): what reaches /v1/messages says what Claude
// really got. Claude Code's queue only gives back all of it at once, so what stays is typed back in; a
// message being edited sits in the daemon's held line, where nothing sends it. Nothing reaches a model
// or the owner's own Claude Code. Skipped where Claude Code isn't installed (CI).
import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'

const realClaude = [process.env.HN_E2E_CLAUDE, join(homedir(), '.local/bin/claude')].find((p) => p && existsSync(p))
const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-qedit-')))
for (const k of Object.keys(process.env)) if (/^(CLAUDE|ANTHROPIC|HYPERNOVA|HN_)/.test(k)) delete process.env[k]
const claudeDir = join(scratch, 'claude')
const proj = join(scratch, 'proj')
for (const d of ['home', 'zdot', 'tmp', 'claude', 'proj']) mkdirSync(join(scratch, d), { recursive: true })
Object.assign(process.env, {
  HYPERNOVA_SUPPORT: join(scratch, 'support'),
  HYPERNOVA_LOGS: join(scratch, 'logs'),
  CLAUDE_CONFIG_DIR: claudeDir,
  HOME: join(scratch, 'home'),
  ZDOTDIR: join(scratch, 'zdot'),
  TMPDIR: join(scratch, 'tmp'),
  DISABLE_AUTOUPDATER: '1',
  CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: '1',
  ...(realClaude ? { CLAUDE_BIN: realClaude } : {}),
})
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

const requests: string[] = []
let slowMs = 0
let toolsAhead = 0
const { startFake } = await import('./fake-provider/server.ts')
const fake = await startFake({
  dir: join(scratch, 'fake'),
  onMessages: (_sid, body) => {
    if (!body.tools?.length) return
    requests.push((body.messages ?? []).filter((m: any) => m.role === 'user').map((m: any) => (typeof m.content === 'string' ? m.content : m.content.map((b: any) => b.text ?? '').join('\n'))).join('\n'))
  },
  script: (_sid, body) => {
    if (!(body.tools?.length && body.stream === true)) return null
    if (toolsAhead > 0) {
      toolsAhead--
      return { blocks: [{ tool: 'Bash', input: { command: 'sleep 7', description: 'wait a bit' } }], outputTokens: 20 }
    }
    const waitMs = slowMs
    slowMs = 0
    return { blocks: [{ text: 'All done.' }], outputTokens: 30, waitMs }
  },
})
writeFileSync(join(claudeDir, 'settings.json'), JSON.stringify({ env: { ANTHROPIC_BASE_URL: fake.url, ANTHROPIC_AUTH_TOKEN: 'sk-fake' }, permissions: { allow: ['Bash(sleep:*)'] } }))
writeFileSync(join(claudeDir, '.claude.json'), JSON.stringify({ hasCompletedOnboarding: true, projects: { [proj]: { hasTrustDialogAccepted: true, hasCompletedProjectOnboarding: true } } }))

const hub = await import('../src/hub.ts')
const { addDevice } = await import('../src/store.ts')
const { pressKeys, scanTerms, termOf, watchMenus } = await import('../src/terms.ts')
const { startServers } = await import('../src/http.ts')
const hooks = await import('../src/hooks.ts')
const TOKEN = 'test-device-token-qedit-0123456789'
addDevice({ id: 'phone', name: 'Phone', model: 'P', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)

const sleep = (ms: number) => new Promise((ok) => setTimeout(ok, ms))
async function until(what: string, check: () => boolean | Promise<boolean>, ms = 60_000): Promise<void> {
  const t0 = Date.now()
  while (!(await check())) {
    if (Date.now() - t0 > ms) throw new Error(`waited ${ms} ms for ${what}`)
    await sleep(150)
  }
}
function call(method: string, path: string, body?: object): Promise<{ status: number; json: any }> {
  return new Promise((resolve, reject) => {
    const req = request({ host: '127.0.0.1', port: API, method, path, headers: { host: `127.0.0.1:${API}`, authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' } }, (res) => {
      let data = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (data += c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, json: data ? JSON.parse(data) : null }))
    })
    req.on('error', reject)
    req.end(body ? JSON.stringify(body) : undefined)
  })
}

const timers: NodeJS.Timeout[] = []
let closeServers: (() => Promise<void>) | null = null
before(() => {
  if (!realClaude) return
  hooks.installIntegration()
  closeServers = startServers()
  timers.push(setInterval(hub.pollRegistry, 400), setInterval(hub.pollTranscripts, 300), setInterval(scanTerms, 500), setInterval(watchMenus, 800))
})
after(async () => {
  for (const t of timers) clearInterval(t)
  await closeServers?.()
  if (realClaude) await new Promise<void>((ok) => execFile(realClaude, ['daemon', 'stop', '--any'], { cwd: proj, timeout: 30_000 }, () => ok()))
  for (const dir of [join(process.env.HYPERNOVA_SUPPORT!, 'terms'), join(claudeDir, 'sessions')]) {
    for (const f of existsSync(dir) ? readdirSync(dir) : []) {
      if (!f.endsWith('.json')) continue
      try {
        process.kill(JSON.parse(readFileSync(join(dir, f), 'utf8')).pid, 'SIGKILL')
      } catch {}
    }
  }
  fake.server.closeAllConnections?.()
  await fake.close()
  rmSync(scratch, { recursive: true, force: true })
})

const send = (id: string, text: string, clientId: string) => call('POST', `/v1/sessions/${id}/prompt`, { text, clientId, hold: true })
const view = (id: string) => hub.listSessions().find((s: any) => s.id === id) as any
const heard = (words: string) => requests.some((r) => r.includes(words))
const heardBefore = (a: string, b: string) => requests.findIndex((r) => r.includes(a)) < requests.findIndex((r) => r.includes(b))
const events = async (id: string) => ((await call('GET', `/v1/sessions/${id}/events`)).json?.events ?? []) as any[]

/** A session at rest, then a slow turn with [queued] waiting in Claude Code's own queue. */
async function queuedUp(name: string, queued: string[], ms = 25_000): Promise<string> {
  const r = await call('POST', '/v1/sessions', { projectPath: proj, prompt: `[Hypernova test] ${name}`, permissionMode: 'default' })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  const id = r.json.session.id as string
  await until(`${name}: its reply`, () => {
    const f = hub.transcriptPathFor(hub.getSession(id)!.cwd, id)
    return existsSync(f) && readFileSync(f, 'utf8').includes('All done.')
  })
  await until(`${name}: its terminal`, () => {
    scanTerms()
    return termOf(id) != null && view(id)?.state === 'idle'
  })
  await sleep(800)
  slowMs = ms
  await send(id, `[Hypernova test] ${name}: the slow turn`, `${name}-slow`)
  await until(`${name}: working`, () => view(id)?.state === 'working')
  await sleep(1500)
  for (const [i, t] of queued.entries()) assert.equal((await send(id, t, `${name}-${i}`)).status, 200)
  await until(`${name}: all queued`, () => view(id)?.queue?.length === queued.length)
  return id
}
const queueId = (id: string, words: string) => view(id).queue.find((q: any) => q.text === words).id as string

const skip = realClaude ? false : 'Claude Code isn’t installed here'

test('remove one of three: Claude gets the other two, in order, and nothing goes back to the owner’s box', { skip, timeout: 120_000 }, async () => {
  const [a, b, c] = ['rm one [Hypernova test]', 'rm two [Hypernova test]', 'rm three [Hypernova test]']
  const id = await queuedUp('remove', [a, b, c])
  assert.equal(view(id).queueEdit, true, 'a holder of this version can')
  const r = await call('DELETE', `/v1/sessions/${id}/queue/${encodeURIComponent(queueId(id, b))}`)
  assert.equal(r.status, 200, JSON.stringify(r.json))
  await until('the two back in the queue', () => view(id)?.queue?.length === 2, 15_000)
  assert.deepEqual(view(id).queue.map((q: any) => [q.text, q.clientId]), [[a, 'remove-0'], [c, 'remove-2']], 'in order, still named as the sends they were')
  await until('Claude gets them', () => heard(a) && heard(c), 60_000)
  assert.ok(!heard(b), 'never the one removed')
  assert.ok(heardBefore(a, c) || requests.findIndex((x) => x.includes(a)) === requests.findIndex((x) => x.includes(c)), 'in order')
  assert.ok(!(await events(id)).some((e) => e.kind === 'notice' && e.unsent), 'no notice takes anything back')
})

test('edit the second of three: the first goes on, the second and third wait however the turn ends, and the new words go when saved', { skip, timeout: 180_000 }, async () => {
  const [a, b, c] = ['ed one [Hypernova test]', 'ed two [Hypernova test]', 'ed three [Hypernova test]']
  const id = await queuedUp('edit', [a, b, c], 12_000)
  const r = await call('POST', `/v1/sessions/${id}/queue/${encodeURIComponent(queueId(id, b))}/edit`)
  assert.equal(r.status, 200, JSON.stringify(r.json))
  assert.equal(r.json.text, b, 'its words, whole, for the edit sheet')
  assert.ok(r.json.heldId)
  assert.deepEqual(r.json.session.held.map((h: any) => [h.text, h.editing ?? false]), [[b, true], [c, false]], 'it and what’s behind it are in the held line')
  await until('the first back in the queue', () => view(id)?.queue?.length === 1, 15_000)
  // The turn ends, the first runs, and everything goes quiet: nothing sends what's being edited.
  await until('the first reaches Claude', () => heard(a), 60_000)
  await until('at rest', () => view(id)?.state === 'idle' && !view(id)?.queue?.length, 60_000)
  await sleep(6000)
  assert.ok(!heard(b) && !heard(c), 'not while it’s being edited, nor what’s behind it')
  assert.equal(view(id).held.length, 2)
  const edited = 'ed two, changed [Hypernova test]'
  const put = await call('PUT', `/v1/sessions/${id}/held/${r.json.heldId}`, { text: edited })
  assert.equal(put.status, 200, JSON.stringify(put.json))
  assert.equal(put.json.session.held[0].editing, undefined, 'no longer being edited')
  await until('the new words reach Claude, then the next', () => heard(edited) && heard(c), 60_000)
  assert.ok(!heard(b), 'never the old words')
  assert.ok(requests.findIndex((x) => x.includes(edited)) <= requests.findIndex((x) => x.includes(c)), 'in order')
})

test('edit, then change your mind: it goes as it was', { skip, timeout: 120_000 }, async () => {
  const [a] = ['cancel one [Hypernova test]']
  const id = await queuedUp('cancel', [a], 8_000)
  const r = await call('POST', `/v1/sessions/${id}/queue/${encodeURIComponent(queueId(id, a))}/edit`)
  assert.equal(r.status, 200, JSON.stringify(r.json))
  await until('at rest', () => view(id)?.state === 'idle', 40_000)
  await sleep(4000)
  assert.ok(!heard(a), 'held back meanwhile')
  const u = await call('PUT', `/v1/sessions/${id}/held/${r.json.heldId}`, { editing: false })
  assert.equal(u.status, 200, JSON.stringify(u.json))
  await until('it goes', () => heard(a), 30_000)
})

test('being edited during a tool step: the step ends and Claude doesn’t get it, as it gets what’s still queued', { skip, timeout: 120_000 }, async () => {
  const r0 = await call('POST', '/v1/sessions', { projectPath: proj, prompt: '[Hypernova test] step', permissionMode: 'default' })
  const id = r0.json.session.id as string
  await until('its reply', () => {
    const f = hub.transcriptPathFor(hub.getSession(id)!.cwd, id)
    return existsSync(f) && readFileSync(f, 'utf8').includes('All done.')
  })
  await until('its terminal', () => {
    scanTerms()
    return termOf(id) != null && view(id)?.state === 'idle'
  })
  await sleep(800)
  toolsAhead = 1
  await send(id, '[Hypernova test] a step with a tool', 'step-go')
  await until('the tool runs', () => String(view(id)?.activity ?? '').startsWith('Bash'))
  const [a, b] = ['step one [Hypernova test]', 'step two [Hypernova test]']
  await send(id, a, 'step-0')
  await send(id, b, 'step-1')
  await until('both queued', () => view(id)?.queue?.length === 2, 5000)
  const r = await call('POST', `/v1/sessions/${id}/queue/${encodeURIComponent(queueId(id, b))}/edit`)
  assert.equal(r.status, 200, JSON.stringify(r.json))
  await until('the first reaches Claude', () => heard(a), 40_000)
  await until('at rest', () => view(id)?.state === 'idle', 40_000)
  await sleep(3000)
  assert.ok(!heard(b), 'not the one being edited')
  await call('DELETE', `/v1/sessions/${id}/held/${r.json.heldId}`)
})

test('too late: it reached Claude before the choice did. The answer says so, and so does the chat', { skip, timeout: 120_000 }, async () => {
  const a = 'late one [Hypernova test]'
  const id = await queuedUp('late', [a], 6_000)
  const q = queueId(id, a)
  await until('it reaches Claude', () => heard(a), 40_000)
  await until('it left the queue', () => !view(id)?.queue?.length, 20_000)
  const r = await call('DELETE', `/v1/sessions/${id}/queue/${encodeURIComponent(q)}`)
  assert.equal(r.status, 404)
  assert.equal(r.json.error.code, 'no_queued')
  const said = (await events(id)).filter((e) => e.kind === 'notice' && /already gone to Claude/.test(e.text))
  assert.equal(said.length, 1, 'one line in the chat')
})

test('unsent words in the terminal’s box stop it before anything comes out', { skip, timeout: 120_000 }, async () => {
  const a = 'box one [Hypernova test]'
  const id = await queuedUp('box', [a], 14_000)
  pressKeys(termOf(id)!, 'half typed')
  await sleep(600)
  const r = await call('DELETE', `/v1/sessions/${id}/queue/${encodeURIComponent(queueId(id, a))}`)
  assert.equal(r.status, 409)
  assert.equal(r.json.error.code, 'typing')
  assert.equal(r.json.error.pending, 'half typed')
  assert.deepEqual(view(id).queue.map((q: any) => q.text), [a], 'still queued')
  await until('it reaches Claude', () => heard(a), 40_000)
})
