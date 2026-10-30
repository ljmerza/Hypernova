// The daemon's two HTTP servers, on free ports with scratch state: no CORS on the phone API, and the
// testing checklist's key.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { createServer as createHttpServer, request } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-test-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
mkdirSync(process.env.HOME, { recursive: true })
// The snapshot names Claude Code's version: a stand-in answers, not the owner's claude.
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
const { addDevice, adminToken } = await import('../src/store.ts')
const TOKEN = 'test-device-token-0123456789abcdef'
addDevice({ id: 'device', name: 'Pixel', model: 'Pixel 9', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)

let close: () => Promise<void>
before(async () => {
  close = startServers()
  for (let i = 0; i < 50; i++) {
    try {
      if ((await send(API, 'GET', '/v1/health')).status === 200 && (await send(LOCAL, 'GET', '/v1/health')).status === 200) return
    } catch {}
    await new Promise((r) => setTimeout(r, 20))
  }
})
after(async () => {
  await close?.()
  rmSync(scratch, { recursive: true, force: true })
})

type Reply = { status: number; headers: import('node:http').IncomingHttpHeaders; body: string }
/** One request with exactly these headers (Host and Origin included, which fetch would set itself). */
function send(port: number, method: string, path: string, headers: Record<string, string> = {}, body?: string): Promise<Reply> {
  return new Promise((resolve, reject) => {
    const req = request({ host: '127.0.0.1', port, method, path, headers: { host: `127.0.0.1:${port}`, ...headers } }, (res) => {
      let data = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (data += c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, headers: res.headers, body: data }))
    })
    req.on('error', reject)
    req.end(body)
  })
}

const ORIGIN = { origin: 'https://plugin.example' }

test('no web page may read the phone API: no CORS headers, no preflight answered', async () => {
  const pre = await send(API, 'OPTIONS', '/v1/sessions/abc/prompt', { ...ORIGIN, 'access-control-request-method': 'POST', 'access-control-request-headers': 'authorization,content-type' })
  assert.notEqual(pre.status, 204)
  assert.equal(pre.headers['access-control-allow-origin'], undefined)
  assert.equal(pre.headers['access-control-allow-methods'], undefined)
  const pna = await send(API, 'OPTIONS', '/v1/snapshot', { ...ORIGIN, 'access-control-request-method': 'GET', 'access-control-request-private-network': 'true' })
  assert.equal(pna.headers['access-control-allow-private-network'], undefined)
  const ok = await send(API, 'GET', '/v1/snapshot', { ...ORIGIN, authorization: `Bearer ${TOKEN}` })
  assert.equal(ok.status, 200)
  assert.equal(ok.headers['access-control-allow-origin'], undefined)
  assert.equal(JSON.parse(ok.body).mac.claudeVersion, '9.9.9')
  const no = await send(API, 'GET', '/v1/snapshot', ORIGIN)
  assert.equal(no.status, 401)
  assert.equal(no.headers['access-control-allow-origin'], undefined)
})

test('/v1/ws takes the token in its query (a browser cannot set headers on a WebSocket)', async () => {
  const first = await new Promise<string>((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${API}/v1/ws?token=${TOKEN}`)
    ws.onmessage = (m) => {
      resolve(JSON.parse(String(m.data)).t)
      ws.close()
    }
    ws.onerror = () => reject(new Error('refused'))
  })
  assert.equal(first, 'snapshot')
  const refused = await new Promise<boolean>((resolve) => {
    const ws = new WebSocket(`ws://127.0.0.1:${API}/v1/ws?token=wrong`)
    ws.onopen = () => resolve(false)
    ws.onerror = () => resolve(true)
  })
  assert.equal(refused, true)
})

test("the checklist needs its key; the Mac app's bare link is sent on to the page with it", async () => {
  const shots = join(process.env.HYPERNOVA_SUPPORT!, 'qa', 'shots')
  // The Mac app's menu opens http://127.0.0.1:47322/qa in the browser (Sec-Fetch-Site "none").
  const bare = await send(LOCAL, 'GET', '/qa', { 'sec-fetch-site': 'none', 'sec-fetch-mode': 'navigate' })
  assert.equal(bare.status, 302)
  const key = /^\/qa\?k=(\w+)$/.exec(bare.headers.location ?? '')?.[1]
  assert.ok(key, String(bare.headers.location))
  assert.equal((await send(LOCAL, 'GET', '/qa')).status, 302) // a browser that sends no Sec-Fetch-Site
  assert.equal((await send(LOCAL, 'GET', `/qa?k=${key}`)).status, 200)
  // Another site sending the browser there gets nothing, nor does a rebound domain, even one
  // that claims to come through the tailnet.
  assert.equal((await send(LOCAL, 'GET', '/qa', { 'sec-fetch-site': 'cross-site' })).status, 401)
  assert.equal((await send(LOCAL, 'GET', '/qa', { host: `evil.example:${LOCAL}`, 'sec-fetch-site': 'none' })).status, 403)
  assert.equal((await send(LOCAL, 'GET', '/qa', { host: `evil.example:${LOCAL}`, 'x-forwarded-for': '1.2.3.4' })).status, 403)
  // Without the key nothing is read or changed: a sandboxed page's form post (Origin "null") included.
  assert.equal((await send(LOCAL, 'GET', '/qa/state')).status, 401)
  assert.equal((await send(LOCAL, 'POST', '/qa/shot?target=x', { origin: 'null', 'content-type': 'text/plain' }, 'not a picture')).status, 401)
  assert.equal((await send(LOCAL, 'POST', '/qa/save', { 'content-type': 'application/json' }, '{"item":"a","status":"pass"}')).status, 401)
  assert.deepEqual(readdirSync(shots), [])
  // With it (the page passes it on to its own requests), or the admin token, it works.
  assert.equal((await send(LOCAL, 'POST', `/qa/save?k=${key}`, { 'content-type': 'application/json' }, '{"item":"a","status":"pass"}')).status, 200)
  const state = await send(LOCAL, 'GET', '/qa/state', { authorization: `Bearer ${adminToken}` })
  assert.equal(JSON.parse(state.body).results.items.a.status, 'pass')
  // The phone port never hands the key out; the phone asks for the signed URL.
  assert.equal((await send(API, 'GET', '/qa', { 'sec-fetch-site': 'none' })).status, 401)
  const signed = JSON.parse((await send(API, 'GET', '/v1/qa', { authorization: `Bearer ${TOKEN}` })).body).url as string
  const path = new URL(signed).pathname + new URL(signed).search
  assert.equal((await send(API, 'GET', path, { 'x-forwarded-for': '100.64.0.2' })).status, 200)
  assert.equal((await send(API, 'GET', `/qa/state?k=${key}`, { 'x-forwarded-for': '100.64.0.2' })).status, 200)
})

test("a session's agents, and one agent's own transcript, only by an agent's id and only with the token", async () => {
  const { upsertSession } = await import('../src/hub.ts')
  const id = 'ffffffff-0000-4000-8000-000000000001'
  const agent = 'a0123456789abcdef'
  const dir = join(process.env.CLAUDE_CONFIG_DIR!, 'projects', '-tmp-routes')
  const sub = join(dir, id, 'subagents')
  mkdirSync(sub, { recursive: true })
  writeFileSync(join(dir, `${id}.jsonl`), '')
  writeFileSync(join(sub, `agent-${agent}.meta.json`), JSON.stringify({ agentType: 'Explore', description: 'Read the routes', toolUseId: 'toolu_1' }))
  writeFileSync(join(sub, `agent-${agent}.jsonl`), JSON.stringify({ type: 'user', isSidechain: true, agentId: agent, uuid: 'p1', parentUuid: null, timestamp: new Date().toISOString(), message: { role: 'user', content: 'Read the routes.' } }) + '\n')
  upsertSession(id, { cwd: '/tmp/routes', transcriptPath: join(dir, `${id}.jsonl`), origin: 'terminal', state: 'working' })
  const auth = { authorization: `Bearer ${TOKEN}` }
  const list = await send(API, 'GET', `/v1/sessions/${id}/agents`, auth)
  assert.equal(list.status, 200)
  const body = JSON.parse(list.body)
  assert.deepEqual(body.agents.map((a: any) => [a.id, a.label, a.type, a.status]), [[agent, 'Read the routes', 'Explore', 'running']])
  assert.deepEqual(body.workflows, [])
  const events = await send(API, 'GET', `/v1/sessions/${id}/agents/${agent}/events?limit=50`, auth)
  assert.equal(events.status, 200)
  assert.deepEqual(JSON.parse(events.body).events.map((e: any) => [e.kind, e.text]), [['user', 'Read the routes.']])
  // Anything but an agent's own id never reaches the disk.
  assert.equal((await send(API, 'GET', `/v1/sessions/${id}/agents/..%2F..%2Fsecret/events`, auth)).status, 400)
  assert.equal(JSON.parse((await send(API, 'GET', `/v1/sessions/${id}/agents/a9999999999999999/events`, auth)).body).error.code, 'no_agent')
  assert.equal((await send(API, 'GET', '/v1/sessions/nope/agents', auth)).status, 404)
  assert.equal((await send(API, 'GET', `/v1/sessions/${id}/agents`)).status, 401)
})

test("a session's Blocked tab: the list, Dismiss, and Allow & retry through the prompt route (its errors keep the row)", async () => {
  const { upsertSession } = await import('../src/hub.ts')
  const { noteBlocked } = await import('../src/blocked.ts')
  const { addLink } = await import('../src/switch-links.ts')
  const id = 'ffffffff-0000-4000-8000-0000000000b1'
  upsertSession(id, { cwd: '/tmp/routes', origin: 'terminal', state: 'idle' })
  noteBlocked(id, { tool: 'Bash', input: { command: 'git push origin main' }, reason: 'auto_mode', why: 'Pushes to the default branch', toolUseId: 'toolu_http1' })
  noteBlocked(id, { tool: 'WebFetch', input: { url: 'https://example.com' }, reason: 'away', toolUseId: 'toolu_http2' })
  const auth = { authorization: `Bearer ${TOKEN}` }
  const snap = JSON.parse((await send(API, 'GET', '/v1/snapshot', auth)).body)
  assert.equal(snap.sessions.find((x: any) => x.id === id)?.blockedCalls, 2)
  const list = JSON.parse((await send(API, 'GET', `/v1/sessions/${id}/blocked`, auth)).body).blocked
  assert.deepEqual(list.map((b: any) => [b.tool, b.target, b.reason].join(' ')).sort(), ['Bash git push origin main auto_mode', 'WebFetch https://example.com away'])
  const bash = list.find((b: any) => b.tool === 'Bash')
  // The conversation went on elsewhere: the prompt route's 409, and the row stays.
  addLink(id, 'ffffffff-0000-4000-8000-0000000000b2')
  const r = await send(API, 'POST', `/v1/sessions/${id}/blocked/${bash.id}/retry`, auth)
  assert.equal(r.status, 409)
  assert.equal(JSON.parse(r.body).error.code, 'switched')
  const del = await send(API, 'DELETE', `/v1/sessions/${id}/blocked/${bash.id}`, auth)
  assert.equal(del.status, 200)
  assert.deepEqual(JSON.parse(del.body).blocked.map((b: any) => b.tool), ['WebFetch'])
  assert.equal(JSON.parse((await send(API, 'DELETE', `/v1/sessions/${id}/blocked/${bash.id}`, auth)).body).error.code, 'no_blocked')
  assert.equal(JSON.parse((await send(API, 'POST', `/v1/sessions/${id}/blocked/${bash.id}/retry`, auth)).body).error.code, 'no_blocked')
  assert.equal((await send(API, 'GET', '/v1/sessions/nope/blocked', auth)).status, 404)
  assert.equal((await send(API, 'GET', `/v1/sessions/${id}/blocked`)).status, 401)
})

test('the account is in the snapshot and /local/status; a check runs on demand; signing in only when needed', async () => {
  // /local/status asks Tailscale: not this Mac's.
  process.env.HYPERNOVA_TAILSCALE = join(scratch, 'no-tailscale')
  const auth = { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' }
  const snap = JSON.parse((await send(API, 'GET', '/v1/snapshot', auth)).body)
  assert.deepEqual([snap.account.state, snap.account.providerId, snap.account.login], ['ok', 'anthropic', null])
  // The Mac's battery: none read yet in a test (Snapshot.mac.battery is null on a Mac without one).
  assert.equal(snap.mac.battery, null)
  assert.ok(snap.providers.every((p: any) => p.status === null))
  const status = JSON.parse((await send(LOCAL, 'GET', '/local/status', { authorization: `Bearer ${adminToken}` })).body)
  assert.equal(status.account.state, 'ok')
  const check = await send(API, 'POST', '/v1/account/check', auth, '{}')
  assert.equal(check.status, 200)
  assert.equal(typeof JSON.parse(check.body).account.checkedAt, 'number')
  const err = async (method: string, path: string, body?: string) => {
    const r = await send(API, method, path, auth, body)
    return [r.status, JSON.parse(r.body).error?.code ?? JSON.parse(r.body).ok]
  }
  assert.deepEqual(await err('POST', '/v1/account/login', '{"where":"phone"}'), [409, 'not_needed'])
  assert.deepEqual(await err('POST', '/v1/account/login', '{"where":"tv"}'), [400, 'bad_where'])
  assert.deepEqual(await err('POST', '/v1/account/login/code', '{"code":"a#b"}'), [409, 'no_login'])
  assert.deepEqual(await err('DELETE', '/v1/account/login'), [200, true])
  assert.equal((await send(API, 'POST', '/v1/account/check', {}, '{}')).status, 401)
})

test('a session continues on another provider: the checks, and an SDK session taking it with its next prompt', async () => {
  const { getManaged, saveManaged, saveProvider } = await import('../src/store.ts')
  const { getSession, upsertSession } = await import('../src/hub.ts')
  const p = saveProvider({ name: 'OpenRouter', kind: 'openrouter', apiKey: 'test-only-not-a-key' })
  saveManaged({ id: 'sdk-1', cwd: scratch, providerId: 'anthropic', model: 'opus', permissionMode: 'default', createdAt: Date.now(), engine: 'sdk' })
  upsertSession('sdk-1', { cwd: scratch, origin: 'hypernova', state: 'idle', providerId: 'anthropic', model: 'opus', blocked: { kind: 'limit', text: "You've hit your session limit", resetsAt: Date.now() + 3600_000, autoResumeAt: null } })
  const auth = { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' }
  const to = async (id: string, providerId: string) => {
    const r = await send(API, 'POST', `/v1/sessions/${id}/provider`, auth, JSON.stringify({ providerId }))
    return { status: r.status, body: JSON.parse(r.body) }
  }
  assert.equal((await to('nope', p.id)).body.error.code, 'no_session')
  assert.deepEqual([(await to('sdk-1', 'nope')).status, (await to('sdk-1', 'nope')).body.error.code], [404, 'no_provider'])
  assert.deepEqual([(await to('sdk-1', 'anthropic')).status, (await to('sdk-1', 'anthropic')).body.error.code], [409, 'same_provider'])
  upsertSession('sdk-1', { state: 'working' })
  assert.equal((await to('sdk-1', p.id)).body.error.code, 'busy')
  upsertSession('sdk-1', { state: 'idle' })
  // A move still under way (the old claude closing, or the new one just started): one at a time.
  const { clearRestarting, markRestarting } = await import('../src/hub.ts')
  markRestarting('sdk-1', null, 60_000)
  assert.deepEqual([(await to('sdk-1', p.id)).status, (await to('sdk-1', p.id)).body.error.code], [409, 'busy'])
  clearRestarting('sdk-1')
  const ok = await to('sdk-1', p.id)
  assert.equal(ok.status, 200)
  assert.deepEqual([ok.body.forked, ok.body.session.id, ok.body.session.providerId, ok.body.session.model, ok.body.session.blocked], [false, 'sdk-1', p.id, null, null])
  assert.deepEqual([getManaged('sdk-1')?.providerId, getManaged('sdk-1')?.model], [p.id, null])
  assert.equal(getSession('sdk-1')?.providerId, p.id)
})

test("a session a limit stopped moves on to another provider even with Claude Code's limit dialog open", async () => {
  const { saveManaged, saveProvider } = await import('../src/store.ts')
  const { upsertSession } = await import('../src/hub.ts')
  const p = saveProvider({ name: 'Gateway', kind: 'custom', baseUrl: 'https://gateway.example', apiKey: 'test-only-not-a-key' })
  const blocked = { kind: 'limit' as const, text: "You've hit your session limit · resets 2am", resetsAt: Date.now() + 3600_000, autoResumeAt: null }
  for (const id of ['sdk-dialog', 'sdk-asks']) saveManaged({ id, cwd: scratch, providerId: 'anthropic', model: null, permissionMode: 'default', createdAt: Date.now(), engine: 'sdk' })
  // Waiting on the limit's own menu: free to move.
  upsertSession('sdk-dialog', { cwd: scratch, origin: 'hypernova', state: 'needs_you', providerId: 'anthropic', blocked })
  // Waiting on the owner with nothing stopping it: still busy.
  upsertSession('sdk-asks', { cwd: scratch, origin: 'hypernova', state: 'needs_you', providerId: 'anthropic', blocked: null })
  const auth = { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' }
  const to = async (id: string) => {
    const r = await send(API, 'POST', `/v1/sessions/${id}/provider`, auth, JSON.stringify({ providerId: p.id }))
    return { status: r.status, body: JSON.parse(r.body) }
  }
  const moved = await to('sdk-dialog')
  assert.deepEqual([moved.status, moved.body.session?.providerId, moved.body.session?.blocked], [200, p.id, null])
  const busy = await to('sdk-asks')
  assert.deepEqual([busy.status, busy.body.error?.code], [409, 'busy'])
})

test('an automation never forks a session to move it, nor restarts one over unsent text', async () => {
  const { saveProvider } = await import('../src/store.ts')
  const { upsertSession } = await import('../src/hub.ts')
  const { moveSession, unsentText } = await import('../src/http.ts')
  const p = saveProvider({ name: 'Paid gateway', kind: 'custom', baseUrl: 'https://gateway.example', apiKey: 'test-only-not-a-key' })
  // Open in a plain terminal the owner started: the phone's move forks it; an automation's doesn't.
  upsertSession('plain-open', { cwd: scratch, origin: 'terminal', state: 'idle', pid: process.pid, providerId: 'anthropic' })
  await assert.rejects(moveSession('plain-open', p.id, { noFork: true }), (err: any) => err.status === 409 && err.code === 'no_terminal')
  // The input box, read off the screen: the owner's draft is in the way; Claude Code's own "continue" isn't.
  const screen = (box: string) => ['Some output', '─'.repeat(40), `❯ ${box}`, '─'.repeat(40), '  ? for shortcuts'].join('\n')
  assert.equal(unsentText(screen('half a thought')), 'half a thought')
  assert.equal(unsentText(screen('continue')), '')
  assert.equal(unsentText(screen('')), '')
  assert.equal(unsentText('no box here'), '')
})

test('automations: the routes, their errors, and the snapshot’s and the Mac’s status fields', async () => {
  process.env.HYPERNOVA_TAILSCALE = join(scratch, 'no-tailscale')
  const { settings } = await import('../src/store.ts')
  const project = join(settings.projectsRoot, 'demo')
  mkdirSync(project, { recursive: true })
  const auth = { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' }
  const call = async (method: string, path: string, body?: unknown) => {
    const r = await send(API, method, path, auth, body === undefined ? undefined : JSON.stringify(body))
    return { status: r.status, body: JSON.parse(r.body) }
  }
  // No token, no automations.
  assert.equal((await send(API, 'GET', '/v1/automations')).status, 401)
  const { FEATURES } = await import('../src/automations.ts')
  assert.deepEqual((await call('GET', '/v1/automations')).body, { automations: [], paused: false, features: [...FEATURES] })
  const brief = { name: 'Morning brief', enabled: true, onlyOnce: false, template: 'brief', trigger: { kind: 'time', at: '09:00', days: [1, 2, 3, 4, 5] }, action: { kind: 'start', projectPath: project, prompt: 'Summarise what changed.', providerId: 'anthropic', model: null, permissionMode: 'dontAsk', allowedTools: ['Bash(git log:*)'], then: 'notify' } }
  const made = await call('POST', '/v1/automations', brief)
  assert.equal(made.status, 200)
  const id = made.body.automation.id as string
  assert.equal(typeof made.body.automation.nextAt, 'number')
  assert.equal(made.body.automation.problem, null)
  // Refusals are 400s with their codes.
  assert.deepEqual([(await call('POST', '/v1/automations', { ...brief, name: '' })).status, (await call('POST', '/v1/automations', { ...brief, name: '' })).body.error.code], [400, 'bad_name'])
  // A folder that's there but outside the projects folder, and one that isn't there at all.
  assert.equal((await call('POST', '/v1/automations', { ...brief, action: { ...brief.action, projectPath: '/etc' } })).body.error.code, 'outside_projects')
  assert.equal((await call('POST', '/v1/automations', { ...brief, action: { ...brief.action, projectPath: join(scratch, 'nowhere') } })).body.error.code, 'no_project')
  assert.equal((await call('POST', '/v1/automations', { ...brief, action: { ...brief.action, permissionMode: 'bypassPermissions' } })).body.error.code, 'confirm_bypass')
  assert.equal((await call('POST', '/v1/automations', { ...brief, action: { ...brief.action, allowedTools: ['Bash'] } })).body.error.code, 'rule_too_broad')
  // Replace, switch off, Run now (a note: no claude needed), its runs, pause, the 404s.
  assert.equal((await call('PUT', `/v1/automations/${id}`, { ...brief, name: 'Brief' })).body.automation.name, 'Brief')
  assert.equal((await call('POST', `/v1/automations/${id}/enabled`, { enabled: false })).body.automation.enabled, false)
  assert.equal((await call('POST', `/v1/automations/${id}/enabled`, { enabled: 'yes' })).status, 400)
  const note = await call('POST', '/v1/automations', { name: 'Hello', enabled: true, trigger: { kind: 'time', at: '08:00', days: [6] }, action: { kind: 'notify', text: 'Hello at {time}' } })
  const ran = await call('POST', `/v1/automations/${note.body.automation.id}/run`, {})
  assert.deepEqual([ran.status, ran.body.run.state, ran.body.run.fired.by], [200, 'done', 'manual'])
  const runs = await call('GET', `/v1/automations/${note.body.automation.id}/runs?limit=5`)
  assert.deepEqual([runs.body.runs.length, runs.body.total, runs.body.skipped], [1, 1, 0])
  // Retry an ended run: a new one that names it.
  const retried = await call('POST', `/v1/automations/runs/${ran.body.run.id}/retry`, {})
  assert.deepEqual([retried.status, retried.body.run.retryOf], [200, ran.body.run.id])
  assert.deepEqual((await call('POST', '/v1/automations/runs/ru_0000000000/stop', {})).body.error.code, 'no_run')
  assert.deepEqual((await call('POST', '/v1/automations/paused', { paused: true })).body, { paused: true })
  // The snapshot and the Mac's status.
  const snap = (await call('GET', '/v1/snapshot')).body
  assert.equal(typeof snap.mac.tz, 'string')
  assert.equal(snap.automations.paused, true)
  assert.deepEqual(snap.automations.list.map((a: any) => a.name).sort(), ['Brief', 'Hello'])
  assert.equal(snap.automations.notes[0].name, 'Hello')
  assert.match(snap.automations.notes[0].text, /^Hello at \d{1,2}:\d{2}$/)
  assert.deepEqual([snap.automations.running, snap.automations.queued], [0, 0])
  const status = JSON.parse((await send(LOCAL, 'GET', '/local/status', { authorization: `Bearer ${adminToken}` })).body)
  assert.deepEqual(status.automations, { paused: true, count: 2, next: null, running: [] })
  await call('POST', '/v1/automations/paused', { paused: false })
  assert.equal(JSON.parse((await send(LOCAL, 'GET', '/local/status', { authorization: `Bearer ${adminToken}` })).body).automations.next?.id, note.body.automation.id)
  assert.deepEqual((await call('DELETE', `/v1/automations/${id}`)).body, { ok: true })
  assert.deepEqual([(await call('PUT', `/v1/automations/${id}`, brief)).status, (await call('PUT', `/v1/automations/${id}`, brief)).body.error.code], [404, 'no_automation'])
})

test('workflows: a webhook trigger fired on 127.0.0.1 with its token or by a paired device, the CLI, and the payload’s limits', async () => {
  const { settings } = await import('../src/store.ts')
  const { cliContext } = await import('../src/cli/context.ts')
  const { automation } = await import('../src/cli/commands.ts')
  const project = join(settings.projectsRoot, 'demo-fire')
  mkdirSync(project, { recursive: true })
  const auth = { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' }
  const call = async (method: string, path: string, body?: unknown) => {
    const r = await send(API, method, path, auth, body === undefined ? undefined : JSON.stringify(body))
    return { status: r.status, body: JSON.parse(r.body) }
  }
  // A notify step: it runs without claude.
  const made = await call('POST', '/v1/automations', { name: 'On deploy', enabled: true, trigger: { kind: 'webhook' }, steps: [{ kind: 'notify', text: 'Deployed: {payload}' }] })
  assert.equal(made.status, 200)
  const id = made.body.automation.id as string
  const token = made.body.token as string
  assert.match(token, /^hnf_[0-9a-f]{32}$/)
  assert.equal(made.body.automation.trigger.url, `http://127.0.0.1:${LOCAL}/local/automations/${id}/fire`)
  // On 127.0.0.1 with its token: the one /local route without the admin token.
  const local = await send(LOCAL, 'POST', `/local/automations/${id}/fire`, { authorization: `Bearer ${token}`, 'content-type': 'text/plain' }, 'v1.2 is live')
  assert.equal(local.status, 200)
  assert.deepEqual([JSON.parse(local.body).run.fired.by, JSON.parse(local.body).run.state], ['webhook', 'done'])
  const snap = (await call('GET', '/v1/snapshot')).body
  assert.equal(snap.automations.notes[0].text, 'Deployed: v1.2 is live')
  assert.ok(Array.isArray(snap.automations.features))
  // A wrong token, none, another route's token: 401; a paired device on the phone port: fired.
  assert.equal((await send(LOCAL, 'POST', `/local/automations/${id}/fire`, { authorization: 'Bearer hnf_' + '0'.repeat(32) }, '')).status, 401)
  assert.equal((await send(LOCAL, 'POST', `/local/automations/${id}/fire`, {}, '')).status, 401)
  assert.equal((await send(LOCAL, 'GET', '/local/status', { authorization: `Bearer ${token}` })).status, 401)
  assert.equal((await send(API, 'POST', `/v1/automations/${id}/fire`, { authorization: `Bearer ${TOKEN}` }, 'from the phone')).status, 200)
  assert.equal((await send(API, 'POST', `/v1/automations/${id}/fire`, {}, '')).status, 401)
  // 64 KB at most; text only.
  assert.equal((await send(API, 'POST', `/v1/automations/${id}/fire`, { authorization: `Bearer ${TOKEN}` }, 'x'.repeat(65 * 1024))).status, 413)
  const bin = await new Promise<number>((resolve, reject) => {
    const req = request({ host: '127.0.0.1', port: API, method: 'POST', path: `/v1/automations/${id}/fire`, headers: { authorization: `Bearer ${TOKEN}` } }, (res) => {
      res.resume()
      resolve(res.statusCode ?? 0)
    })
    req.on('error', reject)
    req.end(Buffer.from([0xff, 0xfe, 0x00, 0x81]))
  })
  assert.equal(bin, 415)
  // A new token; the old one stops.
  const fresh = await call('POST', `/v1/automations/${id}/token`, {})
  assert.match(fresh.body.token, /^hnf_/)
  assert.equal((await send(LOCAL, 'POST', `/local/automations/${id}/fire`, { authorization: `Bearer ${token}` }, '')).status, 401)
  // The CLI: the admin token from the support folder, or the automation's own; exit 3 when paused.
  const said: string[] = []
  const cli = cliContext({ localPort: LOCAL, sys: { run: async () => ({ code: 0, out: '', err: '', missing: false }), which: () => null, say: (l = '') => void said.push(l) } })
  assert.equal(await automation(cli, { data: 'from a git hook' }, ['fire', id]), 0)
  assert.match(said.at(-1)!, /^Fired On deploy: done, run ru_[0-9a-f]{10}\.$/)
  assert.equal(await automation(cli, { token: fresh.body.token }, ['fire', id]), 0)
  assert.equal(await automation(cli, { token: token }, ['fire', id]), 1)
  await call('POST', '/v1/automations/paused', { paused: true })
  assert.equal(await automation(cli, {}, ['fire', id]), 3)
  await call('POST', '/v1/automations/paused', { paused: false })
  assert.equal(await automation(cli, {}, ['fire']), 2)
  await call('DELETE', `/v1/automations/${id}`)
})

test('the local fire route: without its token nothing tells which automations exist, no body is read, and a loop is held off', async () => {
  const { settings } = await import('../src/store.ts')
  const { cliContext } = await import('../src/cli/context.ts')
  const { automation } = await import('../src/cli/commands.ts')
  const project = join(settings.projectsRoot, 'demo-fire-2')
  mkdirSync(project, { recursive: true })
  const auth = { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' }
  const call = async (method: string, path: string, body?: unknown) => {
    const r = await send(API, method, path, auth, body === undefined ? undefined : JSON.stringify(body))
    return { status: r.status, body: JSON.parse(r.body) }
  }
  const made = await call('POST', '/v1/automations', { name: 'Loop', enabled: true, trigger: { kind: 'webhook' }, steps: [{ kind: 'notify', text: 'Got: {payload}' }] })
  const id = made.body.automation.id as string
  const token = made.body.token as string
  const timed = await call('POST', '/v1/automations', { name: 'Timed', enabled: true, trigger: { kind: 'time', at: '07:00', days: [7] }, steps: [{ kind: 'notify', text: 'Hi' }] })
  const fire = (aid: string, t: string | null, body = '') => send(LOCAL, 'POST', `/local/automations/${aid}/fire`, t ? { authorization: `Bearer ${t}` } : {}, body)
  // An id that's there, one that isn't, one that isn't a webhook's: the same 401 to a wrong token.
  const answers = await Promise.all([fire(id, 'hnf_' + '1'.repeat(32)), fire('au_0000000000', 'hnf_' + '1'.repeat(32)), fire(timed.body.automation.id, token), fire('au_0000000000', token)])
  for (const r of answers) assert.deepEqual([r.status, JSON.parse(r.body).error.code], [401, 'bad_token'])
  // A wrong token is refused before its body is read: a body over the limit still gets 401, not 413.
  assert.equal((await fire(id, 'hnf_' + '1'.repeat(32), 'x'.repeat(70 * 1024))).status, 401)
  // A device (or the admin token) still hears 404 and 409: it may know.
  assert.equal((await send(API, 'POST', '/v1/automations/au_0000000000/fire', { authorization: `Bearer ${TOKEN}` }, '')).status, 404)
  // `--data` is sent as it is, even when it starts with "@"; only --data-file reads a file.
  const said: string[] = []
  const cli = cliContext({ localPort: LOCAL, sys: { run: async () => ({ code: 0, out: '', err: '', missing: false }), which: () => null, say: (l = '') => void said.push(l) } })
  const secret = join(scratch, 'not-for-the-payload.txt')
  writeFileSync(secret, 'a private line')
  assert.equal(await automation(cli, { data: `@${secret}` }, ['fire', id]), 0)
  const notes = async () => (await call('GET', '/v1/snapshot')).body.automations.notes.map((n: any) => n.text)
  assert.equal((await notes())[0], `Got: @${secret}`)
  assert.equal(await automation(cli, { 'data-file': secret }, ['fire', id]), 0)
  assert.equal((await notes())[0], 'Got: a private line')
  assert.equal(await automation(cli, { data: 'x', 'data-file': secret }, ['fire', id]), 2)
  // An empty HYPERNOVA_FIRE_TOKEN is no token: the admin token is used.
  assert.equal(await automation(cliContext({ localPort: LOCAL, env: { ...process.env, HYPERNOVA_FIRE_TOKEN: '' }, sys: { run: async () => ({ code: 0, out: '', err: '', missing: false }), which: () => null, say: () => {} } }), {}, ['fire', id]), 0)
  // A loop: 10 fires a minute (3 made above), then 429 with nothing recorded; the CLI exits 3.
  const before = (await call('GET', `/v1/automations/${id}/runs`)).body.total
  const codes: number[] = []
  for (let i = 0; i < 12; i++) codes.push((await fire(id, token, `n${i}`)).status)
  assert.deepEqual(codes, [200, 200, 200, 200, 200, 200, 200, 429, 429, 429, 429, 429])
  assert.equal((await call('GET', `/v1/automations/${id}/runs`)).body.total, before + 7)
  assert.equal(JSON.parse((await fire(id, token)).body).error.code, 'too_often')
  assert.equal(await automation(cli, { token }, ['fire', id]), 3)
  await call('DELETE', `/v1/automations/${id}`)
  await call('DELETE', `/v1/automations/${timed.body.automation.id}`)
})

test('workflows: an ask answered over HTTP (who answered), Retry from a step, and errors that name their field', async () => {
  const auth = { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' }
  const call = async (port: number, method: string, path: string, body?: unknown, headers: Record<string, string> = auth) => {
    const r = await send(port, method, path, headers, body === undefined ? undefined : JSON.stringify(body))
    return { status: r.status, body: JSON.parse(r.body) }
  }
  const steps = [{ id: 'st_q', kind: 'ask', question: 'Ship it?', answers: ['Ship', 'Wait'] }, { id: 'st_n', kind: 'notify', text: 'Shipping: {answer}', if: { on: 'answer', text: 'Ship' } }]
  const made = await call(API, 'POST', '/v1/automations', { name: 'Ship', enabled: true, trigger: { kind: 'time', at: '07:00', days: [7] }, steps })
  const id = made.body.automation.id
  const ran = await call(API, 'POST', `/v1/automations/${id}/run`, {})
  assert.deepEqual([ran.body.run.state, ran.body.run.ask.question], ['needs_you', 'Ship it?'])
  const runId = ran.body.run.id
  const wrong = await call(API, 'POST', `/v1/automations/runs/${runId}/answer`, { stepId: 'st_q', answer: 'Maybe' })
  assert.deepEqual([wrong.status, wrong.body.error.code, wrong.body.error.field], [400, 'bad_answer', 'answer'])
  // The Mac app answers with its admin token.
  const ok = await call(LOCAL, 'POST', `/v1/automations/runs/${runId}/answer`, { stepId: 'st_q', answer: 'Wait' }, { authorization: `Bearer ${adminToken}`, 'content-type': 'application/json' })
  assert.deepEqual([ok.status, ok.body.run.state, ok.body.run.steps[0].answer.by, ok.body.run.steps[1].state], [200, 'done', 'mac', 'skipped'])
  const again = await call(API, 'POST', `/v1/automations/runs/${runId}/answer`, { stepId: 'st_q', answer: 'Ship' })
  assert.deepEqual([again.status, again.body.error.code, again.body.error.run.id], [409, 'not_asking', runId])
  // Retry from the ask; a phone answers this time.
  const re = await call(API, 'POST', `/v1/automations/runs/${runId}/retry`, { fromStep: 'st_q' })
  assert.deepEqual([re.status, re.body.run.retryFrom, re.body.run.state], [200, 'st_q', 'needs_you'])
  const shipped = await call(API, 'POST', `/v1/automations/runs/${re.body.run.id}/answer`, { stepId: 'st_q', answer: 'ship' })
  assert.deepEqual([shipped.body.run.steps[0].answer.by, shipped.body.run.steps[1].state, shipped.body.run.result], ['phone', 'done', 'Ship'])
  const bad = await call(API, 'POST', `/v1/automations/runs/${runId}/retry`, { fromStep: 'nope' })
  assert.deepEqual([bad.status, bad.body.error.code, bad.body.error.field], [400, 'bad_step', 'fromStep'])
  // A save's 400 names its field.
  const field = await call(API, 'POST', '/v1/automations', { name: 'X', enabled: true, trigger: { kind: 'time', at: '07:00', days: [7] }, steps: [steps[0], { ...steps[1], if: { on: 'answer', text: 'Later' } }] })
  assert.deepEqual([field.status, field.body.error.code, field.body.error.field], [400, 'bad_guard', 'steps[1].if.text'])
  await call(API, 'DELETE', `/v1/automations/${id}`)
})

test('webhook outputs: an https POST with no redirects followed, 10 s at most, never plain http, never a private address unless allowed', async () => {
  const { postWebhook } = await import('../src/automations/io.ts')
  const { execFileSync } = await import('node:child_process')
  const { createServer: createHttpsServer } = await import('node:https')
  const { readFileSync } = await import('node:fs')
  const dir = join(scratch, 'stub-tls')
  mkdirSync(dir, { recursive: true })
  execFileSync('openssl', ['req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1', '-nodes', '-keyout', join(dir, 'k.pem'), '-out', join(dir, 'c.pem'), '-days', '1', '-subj', '/CN=127.0.0.1', '-addext', 'subjectAltName=IP:127.0.0.1'], { stdio: 'ignore' })
  const cert = readFileSync(join(dir, 'c.pem'))
  const got: { path: string; body: string; type: string }[] = []
  const stub = createHttpsServer({ key: readFileSync(join(dir, 'k.pem')), cert }, (req, res) => {
    let body = ''
    req.on('data', (c) => (body += c))
    req.on('end', () => {
      got.push({ path: req.url ?? '', body, type: String(req.headers['content-type']) })
      if (req.url === '/slow') return // never answers
      if (req.url === '/moved') {
        res.writeHead(302, { location: 'https://127.0.0.1/elsewhere' })
        return res.end()
      }
      if (req.url === '/huge') {
        // An answer that never ends: the POST must not wait for it.
        res.writeHead(200)
        let open = true
        res.on('close', () => (open = false))
        const more = (): void => void (open && res.write(Buffer.alloc(64 * 1024, 120), () => open && setTimeout(more, 5).unref()))
        return more()
      }
      res.writeHead(req.url === '/broken' ? 500 : 200)
      res.end('ok')
    })
  })
  await new Promise<void>((r) => stub.listen(0, '127.0.0.1', r))
  const port = (stub.address() as { port: number }).port
  try {
    const at = (p: string) => ({ url: `https://127.0.0.1:${port}${p}`, headers: { 'content-type': 'application/json' }, body: '{"text":"Brief: done"}' })
    // This computer is a private address: refused before anything is sent, unless its host is allowed
    // (settings.json webhookPrivateHosts, for a self-hosted ntfy on the local network).
    await assert.rejects(postWebhook(at('/hook'), { ca: cert }), (e: any) => e.code === 'private_address')
    await assert.rejects(postWebhook({ ...at('/hook'), url: `https://localhost:${port}/hook` }, { ca: cert }), (e: any) => e.code === 'private_address')
    await assert.rejects(postWebhook({ ...at('/hook'), url: `https://[::ffff:127.0.0.1]:${port}/hook` }, { ca: cert }), (e: any) => e.code === 'private_address')
    // A name is judged by every address it has, and the connection goes to the address judged.
    const fake = (addrs: string[]) => (_h: string, _o: unknown, cb: (e: null, a: { address: string; family: number }[]) => void) => cb(null, addrs.map((address) => ({ address, family: address.includes(':') ? 6 : 4 })))
    await assert.rejects(postWebhook({ ...at('/hook'), url: `https://hooks.example.com:${port}/hook` }, { ca: cert, resolve: fake(['93.184.216.34', '10.0.0.5']) as never }), (e: any) => e.code === 'private_address')
    await assert.rejects(postWebhook({ ...at('/hook'), url: `https://rebind.example.com:${port}/hook` }, { ca: cert, resolve: fake(['127.0.0.1']) as never }), (e: any) => e.code === 'private_address')
    assert.equal(got.length, 0, 'nothing reached the stub')
    const open = { ca: cert, allowHosts: ['127.0.0.1'] }
    assert.deepEqual(await postWebhook(at('/hook'), open), { status: 200 })
    assert.deepEqual(got[0], { path: '/hook', body: '{"text":"Brief: done"}', type: 'application/json' })
    assert.deepEqual(await postWebhook(at('/broken'), open), { status: 500 })
    // A redirect isn't followed: its status is the answer.
    assert.deepEqual(await postWebhook(at('/moved'), open), { status: 302 })
    assert.equal(got.filter((g) => g.path === '/elsewhere').length, 0)
    // A huge answer isn't read: the status comes back at once.
    assert.deepEqual(await postWebhook(at('/huge'), open), { status: 200 })
    await assert.rejects(postWebhook(at('/slow'), { ...open, timeoutMs: 300 }), (e: any) => e.code === 'timeout')
    // Its certificate checked: without the stub's CA it isn't trusted.
    await assert.rejects(postWebhook(at('/hook'), { allowHosts: ['127.0.0.1'] }), (e: any) => e.code !== 'private_address')
    await assert.rejects(postWebhook({ ...at('/hook'), url: `http://127.0.0.1:${port}/hook` }), (e: any) => e.code === 'insecure_url')
  } finally {
    stub.closeAllConnections()
    stub.close()
  }
})

test('providers: addresses cleaned, plain http refused off this network, edits checked like adds, a key never follows a host, and Check without saving', async () => {
  // A stand-in OpenRouter (/api/v1/key) and gateway (/gw/v1/models): "good" works, anything else is refused.
  const fake = createHttpServer((req, res) => {
    const auth = String(req.headers['x-api-key'] ?? req.headers.authorization ?? '').replace(/^Bearer /, '')
    const send = (status: number, body: unknown) => {
      res.writeHead(status, { 'content-type': 'application/json' })
      res.end(JSON.stringify(body))
    }
    if (req.url === '/api/v1/key') return auth === 'good' ? send(200, { data: { limit_remaining: 4.2, usage_daily: 0 } }) : send(401, {})
    if (req.url === '/gw/v1/models') return auth === 'good' ? send(200, { data: [{ id: 'local/coder' }] }) : send(401, {})
    send(404, {})
  })
  await new Promise<void>((r) => fake.listen(0, '127.0.0.1', r))
  const port = (fake.address() as { port: number }).port
  const auth = { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' }
  const call = async (method: string, path: string, body?: unknown) => {
    const r = await send(API, method, path, auth, body === undefined ? undefined : JSON.stringify(body))
    return { status: r.status, body: JSON.parse(r.body) }
  }
  try {
    // OpenRouter's documented address: no /v1/v1, and the key read at once.
    const or = await call('POST', '/v1/providers', { name: 'Router', kind: 'openrouter', baseUrl: `http://127.0.0.1:${port}/api/v1/`, apiKey: ' good\n' })
    assert.equal(or.status, 200)
    assert.deepEqual([or.body.provider.baseUrl, or.body.provider.hasKey, or.body.check.state, or.body.check.text, or.body.keyDropped], [`http://127.0.0.1:${port}/api`, true, 'ok', 'Key works. $4.20 left.', false])
    // Plain http to a public host carries the key unencrypted: refused.
    assert.deepEqual([(await call('POST', '/v1/providers', { name: 'Open', kind: 'custom', baseUrl: 'http://203.0.113.5/v1', apiKey: 'k' })).status, (await call('POST', '/v1/providers', { name: 'Open', kind: 'custom', baseUrl: 'http://203.0.113.5/v1', apiKey: 'k' })).body.error.code], [400, 'insecure_url'])
    assert.equal((await call('POST', '/v1/providers', { name: 'Bad key', kind: 'custom', baseUrl: `http://127.0.0.1:${port}/gw`, apiKey: 'a\nb' })).body.error.code, 'bad_key_format')
    // A custom provider is checked on save: refused key, and the provider says so.
    const gw = await call('POST', '/v1/providers', { name: 'Gateway', kind: 'custom', baseUrl: `http://127.0.0.1:${port}/gw/v1/messages`, apiKey: 'wrong' })
    assert.deepEqual([gw.body.provider.baseUrl, gw.body.check.state, gw.body.provider.status?.state], [`http://127.0.0.1:${port}/gw`, 'bad_key', 'bad_key'])
    // Fixed: works, its models listed; the refusal is over.
    const fixed = await call('PUT', `/v1/providers/${gw.body.provider.id}`, { name: 'Gateway', kind: 'custom', apiKey: 'good' })
    assert.deepEqual([fixed.body.check.state, fixed.body.check.models, fixed.body.provider.status], ['ok', ['local/coder'], null])
    // A rename alone checks nothing.
    assert.equal((await call('PUT', `/v1/providers/${gw.body.provider.id}`, { name: 'Gateway two', kind: 'custom' })).body.check, null)
    // Another host without a new key: the key stays behind, and the answer says so.
    const moved = await call('PUT', `/v1/providers/${gw.body.provider.id}`, { name: 'Gateway two', kind: 'custom', baseUrl: `http://localhost:${port}/gw` })
    assert.deepEqual([moved.body.keyDropped, moved.body.provider.hasKey], [true, false])
    // Unknown ids are 404 for every verb.
    assert.deepEqual([(await call('PUT', '/v1/providers/nope', { name: 'X', kind: 'custom', baseUrl: 'https://x.example' })).status, (await call('DELETE', '/v1/providers/nope')).status], [404, 404])
    // Check without saving: a new key, the saved one at its own host, a wrong address, nothing there.
    const check = async (b: unknown) => (await call('POST', '/v1/providers/check', b)).body
    assert.deepEqual([(await check({ kind: 'custom', baseUrl: `http://127.0.0.1:${port}/gw/v1`, apiKey: 'good' })).check.state], ['ok'])
    assert.equal((await check({ id: or.body.provider.id })).check.state, 'ok')
    assert.equal((await check({ id: or.body.provider.id, baseUrl: `http://localhost:${port}/api` })).check.state, 'bad_key')
    assert.equal((await check({ kind: 'custom', baseUrl: `http://127.0.0.1:${port}/elsewhere`, apiKey: 'good' })).check.state, 'not_found')
    assert.equal((await check({ kind: 'custom', baseUrl: 'http://203.0.113.5', apiKey: 'good' })).error.code, 'insecure_url')
    // Deleted: a new session on it is 404, not a session on the subscription.
    const { settings } = await import('../src/store.ts')
    const project = join(settings.projectsRoot, 'demo-providers')
    mkdirSync(project, { recursive: true })
    assert.deepEqual((await call('DELETE', `/v1/providers/${or.body.provider.id}`)).body, { ok: true })
    const started = await call('POST', '/v1/sessions', { projectPath: project, prompt: '[Hypernova test] hello', providerId: or.body.provider.id })
    assert.deepEqual([started.status, started.body.error?.code], [404, 'no_provider'])
    await call('DELETE', `/v1/providers/${gw.body.provider.id}`)
  } finally {
    fake.close()
  }
})
