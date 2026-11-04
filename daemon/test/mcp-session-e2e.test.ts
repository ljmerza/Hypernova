// One session's MCP servers, through the phone's routes, with the real Claude Code in a throwaway home against the fake provider and a tiny real
// server that notes each start: a shared terminal, a `claude agents` job and an SDK session, at rest and in a turn. What's measured here is whether
// an action reached that session's own process, not what it answered. Nothing reaches a model. Skipped where Claude Code isn't installed (CI).
import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'

const realClaude = [process.env.HN_E2E_CLAUDE, join(homedir(), '.local/bin/claude')].find((p) => p && existsSync(p))
const skip = realClaude ? false : 'Claude Code isn’t installed here'
const bin = realClaude ? realpathSync(realClaude) : ''
const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-mcps-')))
for (const k of Object.keys(process.env)) if (/^(CLAUDE|ANTHROPIC|HYPERNOVA|HN_)/.test(k)) delete process.env[k]
const claudeDir = join(scratch, 'claude')
const proj = join(scratch, 'proj')
const work = join(scratch, 'server')
const logs = join(scratch, 'logs')
for (const d of ['home', 'zdot', 'tmp', 'claude', 'proj', 'server', 'logs']) mkdirSync(join(scratch, d))
Object.assign(process.env, {
  HYPERNOVA_SUPPORT: join(scratch, 'support'),
  HYPERNOVA_LOGS: logs,
  CLAUDE_CONFIG_DIR: claudeDir,
  HOME: join(scratch, 'home'),
  ZDOTDIR: join(scratch, 'zdot'),
  TMPDIR: join(scratch, 'tmp'),
  DISABLE_AUTOUPDATER: '1',
  CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: '1',
  ...(realClaude ? { CLAUDE_BIN: bin } : {}),
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

const SECRET = 'PLANTEDsessionMcpSecret0123456789abcdefABCDEF'
const MARKS = join(work, 'marks.log')
const SERVER = join(work, 'tiny.mjs')
writeFileSync(SERVER, `
import { appendFileSync, existsSync } from 'node:fs'
import { createInterface } from 'node:readline'
const label = process.argv[2]
const mark = (w) => appendFileSync(${JSON.stringify(MARKS)}, JSON.stringify({ label, pid: process.pid, ppid: process.ppid, w }) + '\\n')
if (existsSync(${JSON.stringify(work)} + '/fail-' + label)) { mark('refused'); process.exit(3) }
mark('start')
const send = (o) => process.stdout.write(JSON.stringify(o) + '\\n')
const rl = createInterface({ input: process.stdin })
rl.on('line', (l) => {
  const m = JSON.parse(l)
  if (m.method === 'initialize') send({ jsonrpc: '2.0', id: m.id, result: { protocolVersion: m.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: label, version: '1.2.3' } } })
  else if (m.method === 'tools/list') send({ jsonrpc: '2.0', id: m.id, result: { tools: [{ name: 'echo', description: 'Says back.', annotations: { readOnlyHint: true }, inputSchema: { type: 'object', properties: {} } }, { name: 'wipe', annotations: { destructiveHint: true }, inputSchema: { type: 'object', properties: {} } }] } })
  else if (m.id !== undefined) send({ jsonrpc: '2.0', id: m.id, result: {} })
})
rl.on('close', () => process.exit(0))
`)
const srv = (label: string) => ({ type: 'stdio', command: process.execPath, args: [SERVER, label, '--token', SECRET], env: { API_TOKEN: SECRET } })
const marks = (): { label: string; pid: number; ppid: number; w: string }[] => (existsSync(MARKS) ? readFileSync(MARKS, 'utf8').trim().split('\n').filter(Boolean).map((l) => JSON.parse(l)) : [])
const alive = (pid: number) => {
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}
/** The live copies of server [label] under process [parent]. */
const under = (label: string, parent: number) => marks().filter((m) => m.label === label && m.w === 'start' && m.ppid === parent && alive(m.pid)).map((m) => m.pid)

const { CANNED, startFake } = await import('./fake-provider/server.ts')
const fake = await startFake({ dir: join(scratch, 'fake') })
writeFileSync(join(claudeDir, 'settings.json'), JSON.stringify({ env: { ANTHROPIC_BASE_URL: fake.url, ANTHROPIC_AUTH_TOKEN: 'sk-fake' } }))
const configFile = join(claudeDir, '.claude.json')
writeFileSync(configFile, JSON.stringify({ hasCompletedOnboarding: true, projects: { [proj]: { hasTrustDialogAccepted: true, hasCompletedProjectOnboarding: true, mcpServers: { tiny: srv('tiny'), broken: srv('broken') } } } }))
writeFileSync(join(work, 'fail-broken'), '')
const offList = (): string[] => JSON.parse(readFileSync(configFile, 'utf8')).projects[proj].disabledMcpServers ?? []

const hub = await import('../src/hub.ts')
const { addDevice, settings } = await import('../src/store.ts')
const { createAgentsSession, createSharedSession, menusQuiet, pressKeys, scanTerms, termOf, watchMenus } = await import('../src/terms.ts')
const { pollClaudeAgents } = await import('../src/claude-agents.ts')
const { startServers } = await import('../src/http.ts')
const { createSession, sendPrompt } = await import('../src/runner.ts')
const TOKEN = 'test-device-token-session-mcp-0123456789'
addDevice({ id: 'phone', name: 'Phone', model: 'P', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)

const sleep = (ms: number) => new Promise((ok) => setTimeout(ok, ms))
async function until(what: string, check: () => boolean | Promise<boolean>, ms = 45_000): Promise<void> {
  const t0 = Date.now()
  while (!(await check())) {
    if (Date.now() - t0 > ms) throw new Error(`waited ${ms} ms for ${what}`)
    await sleep(150)
  }
}
const call = async (method: string, path: string, body?: unknown) => {
  const r = await fetch(`http://127.0.0.1:${API}${path}`, { method, headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
  const text = await r.text()
  assert.ok(!text.includes(SECRET), `a secret reached the phone from ${path}`)
  assert.ok(!text.includes(SERVER), `a server's command reached the phone from ${path}`)
  return { status: r.status, json: JSON.parse(text) as any }
}
const live = async (id: string, query = '') => {
  const r = await call('GET', `/v1/sessions/${id}/mcp/live${query}`)
  assert.equal(r.status, 200, JSON.stringify(r.json))
  return r.json
}
const row = (reply: any, name: string) => reply.servers.find((s: any) => s.name === name)
const act = async (id: string, action: string, name: string) => {
  const r = await call('POST', `/v1/sessions/${id}/mcp/live`, { action, name })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  return r.json
}
const slow = (ms: number) => fetch(`${fake.url}/_fake/faults`, { method: 'POST', headers: { 'x-fake-token': fake.token, 'content-type': 'application/json' }, body: JSON.stringify({ kind: 'slow', times: 1, ms }) })
const transcript = (id: string) => {
  const p = hub.transcriptPathFor(hub.getSession(id)!.cwd, id)
  return existsSync(p) ? readFileSync(p, 'utf8') : ''
}
const replied = (id: string, n = 1) => (transcript(id).match(new RegExp(CANNED, 'g')) ?? []).length >= n
const claudePid = (id: string) => hub.getSession(id)?.pid ?? 0

const timers: NodeJS.Timeout[] = []
let closeServers: (() => Promise<void>) | null = null
before(() => {
  if (skip) return
  closeServers = startServers()
  timers.push(setInterval(hub.pollRegistry, 500), setInterval(hub.pollTranscripts, 300), setInterval(pollClaudeAgents, 1000), setInterval(scanTerms, 500), setInterval(watchMenus, 300))
})
after(async () => {
  for (const t of timers) clearInterval(t)
  await closeServers?.()
  if (realClaude) await new Promise((ok) => execFile(bin, ['daemon', 'stop', '--any'], { cwd: proj, timeout: 20_000 }, () => ok(null)))
  const terms = join(process.env.HYPERNOVA_SUPPORT!, 'terms')
  for (const dir of [terms, join(claudeDir, 'sessions')]) {
    for (const f of existsSync(dir) ? readdirSync(dir) : []) {
      if (!f.endsWith('.json')) continue
      try {
        process.kill(JSON.parse(readFileSync(join(dir, f), 'utf8')).pid, 'SIGKILL')
      } catch {}
    }
  }
  await fake.close()
  rmSync(scratch, { recursive: true, force: true })
})

const clean = () => {
  // Between tests: nothing off in the folder.
  const c = JSON.parse(readFileSync(configFile, 'utf8'))
  c.projects[proj].disabledMcpServers = []
  writeFileSync(configFile, JSON.stringify(c))
}

test('a shared terminal: each action reaches its own process, Settings’ doesn’t, and a turn carries on', { skip, timeout: 240_000 }, async () => {
  clean()
  const id = createSharedSession({ cwd: proj, prompt: '[Hypernova test] a terminal' })
  await until('its first reply', () => replied(id))
  await until('its holder', () => !!termOf(id))
  await until('its process', () => claudePid(id) > 0)
  const pid = claudePid(id)
  await until('its server', () => under('tiny', pid).length === 1)
  const first = under('tiny', pid)[0]!

  // What the tab reads comes from this session, and goes to no phone as a menu.
  let reply: any
  const reading = live(id)
  let sawMenu = false
  const watch = setInterval(() => {
    if (hub.getSession(id)?.menu) sawMenu = true
  }, 50)
  reply = await reading
  clearInterval(watch)
  assert.equal(sawMenu, false, 'the daemon’s own look at /mcp was offered to the phones as a question')
  assert.equal(menusQuiet.size, 0)
  assert.equal(reply.kind, 'terminal')
  assert.equal(reply.live, true)
  assert.equal(row(reply, 'tiny').status, 'connected')
  assert.equal(row(reply, 'tiny').toolCount, 2)
  assert.equal(row(reply, 'tiny').transport, 'stdio')
  assert.deepEqual(row(reply, 'tiny').actions, ['reconnect', 'disable'])
  assert.equal(row(reply, 'broken').status, 'failed')
  await until('the box', async () => !(await (await import('../src/terms.ts')).termScreen(termOf(id)!))?.text.includes('Manage MCP servers'))

  // Its tools, read off its own screen.
  const tools = (await call('GET', `/v1/sessions/${id}/mcp/live/tools?name=tiny`)).json
  assert.deepEqual(tools.tools.map((t: any) => t.name), ['echo', 'wipe'])
  assert.equal(tools.tools[0].readOnly, true)
  assert.equal(tools.tools[1].destructive, true)

  // Settings' reconnect runs a process of its own: this session's connection is the same one after it.
  const settingsReconnect = await call('POST', '/v1/agents/mcp/reconnect', { agent: 'claude', name: 'tiny', folder: reply.folder })
  assert.equal(settingsReconnect.status, 200, JSON.stringify(settingsReconnect.json))
  assert.deepEqual(under('tiny', pid), [first], 'Settings’ reconnect touched the session')

  // The tab's reaches it.
  const again = await act(id, 'reconnect', 'tiny')
  assert.equal(again.outcome, 'done', JSON.stringify(again))
  assert.match(again.said, /Successfully reconnected to tiny/)
  await until('a new process', () => under('tiny', pid).length === 1 && under('tiny', pid)[0] !== first)
  assert.equal(again.server.status, 'connected')

  // Off: its process ends, the folder's list has it, and the state read back says so.
  const off = await act(id, 'disable', 'tiny')
  assert.equal(off.outcome, 'done', JSON.stringify(off))
  assert.equal(off.server.status, 'disabled')
  assert.deepEqual(off.server.actions, ['enable'])
  await until('its process to end', () => under('tiny', pid).length === 0)
  assert.ok(offList().includes('tiny'))
  const on = await act(id, 'enable', 'tiny')
  assert.equal(on.outcome, 'done', JSON.stringify(on))
  await until('it to run again', () => under('tiny', pid).length === 1)
  assert.ok(!offList().includes('tiny'))

  // A failure says so: nothing to read as success.
  const failed = await act(id, 'reconnect', 'broken')
  assert.equal(failed.outcome, 'failed', JSON.stringify(failed))
  assert.equal(failed.server.status, 'failed')

  // Turned off from elsewhere (as Settings' toggle does): this session keeps it, says so, and only the list changes back.
  const settingsToggle = await call('POST', '/v1/agents/mcp/toggle', { agent: 'claude', name: 'tiny', folder: reply.folder, enabled: false })
  assert.equal(settingsToggle.status, 200, JSON.stringify(settingsToggle.json))
  assert.equal(under('tiny', pid).length, 1, 'Settings’ toggle touched the session')
  const offElsewhere = await live(id)
  assert.equal(row(offElsewhere, 'tiny').status, 'connected')
  assert.equal(row(offElsewhere, 'tiny').offForNew, true)
  const tiny = under('tiny', pid)[0]
  const fixed = await act(id, 'enable', 'tiny')
  assert.equal(fixed.outcome, 'new_sessions', JSON.stringify(fixed))
  assert.ok(!offList().includes('tiny'))
  assert.deepEqual(under('tiny', pid), [tiny], 'only the list changed')

  // While Claude works: at once, beside the turn, which still finishes.
  const had = (transcript(id).match(new RegExp(CANNED, 'g')) ?? []).length
  await slow(20_000)
  const sent = await call('POST', `/v1/sessions/${id}/prompt`, { text: '[Hypernova test] a slow one' })
  assert.equal(sent.status, 200, JSON.stringify(sent.json))
  await until('the turn', () => transcript(id).includes('a slow one'))
  const inTurn = await live(id)
  assert.equal(row(inTurn, 'tiny').status, 'connected')
  const before = under('tiny', pid)[0]
  const busy = await act(id, 'reconnect', 'tiny')
  assert.equal(busy.outcome, 'done', JSON.stringify(busy))
  await until('a new process', () => under('tiny', pid).length === 1 && under('tiny', pid)[0] !== before)
  await until('the turn’s reply', () => replied(id, had + 1), 60_000)
  assert.ok(!transcript(id).includes('Request interrupted'), 'closing the dialog stopped the turn')

  // Words in the box: nothing's typed over them.
  pressKeys(termOf(id)!, 'half a thought')
  await sleep(800)
  const typing = await live(id)
  assert.equal(typing.unread?.code, 'typing')
  assert.deepEqual(typing.servers, [])
  const refused = await call('POST', `/v1/sessions/${id}/mcp/live`, { action: 'reconnect', name: 'tiny' })
  assert.equal(refused.status, 409)
  assert.equal(refused.json.error.code, 'typing')
  pressKeys(termOf(id)!, '\x03')
  await sleep(800)

  // Nothing secret in what the daemon wrote down either.
  for (const f of readdirSync(logs)) assert.ok(!readFileSync(join(logs, f), 'utf8').includes(SECRET), `a secret in ${f}`)
})

test('a `claude agents` job: through `claude attach`, on the job’s own process', { skip, timeout: 240_000 }, async () => {
  clean()
  settings.claudeAgents = true
  const id = await createAgentsSession({ cwd: proj, prompt: '[Hypernova test] a job' })
  await until('its first reply', () => replied(id))
  await until('the daemon to know the job', () => hub.agentJobOf(id) != null)
  await until('its process', () => claudePid(id) > 0)
  const pid = claudePid(id)
  await until('its server', () => under('tiny', pid).length === 1)
  const first = under('tiny', pid)[0]!
  const reply = await live(id)
  assert.equal(reply.kind, 'job')
  assert.equal(reply.live, true)
  assert.equal(row(reply, 'tiny').status, 'connected')
  const again = await act(id, 'reconnect', 'tiny')
  assert.equal(again.outcome, 'done', JSON.stringify(again))
  await until('a new process under the job', () => under('tiny', pid).length === 1 && under('tiny', pid)[0] !== first)
  const off = await act(id, 'disable', 'tiny')
  assert.equal(off.outcome, 'done', JSON.stringify(off))
  await until('it to end', () => under('tiny', pid).length === 0)
  const on = await act(id, 'enable', 'tiny')
  assert.equal(on.outcome, 'done', JSON.stringify(on))
  settings.claudeAgents = false
})

test('an SDK session: at rest it says so; in a turn each action reaches that turn’s process', { skip, timeout: 240_000 }, async () => {
  clean()
  const id = createSession({ cwd: proj, title: '[Hypernova test] sdk' } as never)
  const idOf = typeof id === 'string' ? id : (id as { id: string }).id
  sendPrompt(idOf, '[Hypernova test] a turn')
  await until('its reply', () => replied(idOf), 60_000)
  await until('the turn to end', async () => (await live(idOf)).kind === 'rest')

  const rest = await live(idOf)
  assert.equal(rest.live, false)
  assert.deepEqual(row(rest, 'tiny').actions, ['disable'])
  const refused = await call('POST', `/v1/sessions/${idOf}/mcp/live`, { action: 'reconnect', name: 'tiny' })
  assert.equal(refused.status, 409)
  assert.equal(refused.json.error.code, 'not_here')
  const off = await act(idOf, 'disable', 'tiny')
  assert.equal(off.outcome, 'next_turn', JSON.stringify(off))
  assert.ok(offList().includes('tiny'))
  const on = await act(idOf, 'enable', 'tiny')
  assert.equal(on.outcome, 'next_turn')

  await slow(25_000)
  const had = (transcript(idOf).match(new RegExp(CANNED, 'g')) ?? []).length
  sendPrompt(idOf, '[Hypernova test] a slow turn')
  await until('the turn’s process', async () => (await live(idOf)).kind === 'turn', 20_000)
  await until('its server', () => marks().filter((m) => m.label === 'tiny' && m.w === 'start' && alive(m.pid)).length >= 1)
  const inTurn = await live(idOf)
  assert.equal(inTurn.live, true)
  assert.equal(row(inTurn, 'tiny').status, 'connected')
  const pids = marks().filter((m) => m.label === 'tiny' && m.w === 'start' && alive(m.pid)).map((m) => m.pid)
  const again = await act(idOf, 'reconnect', 'tiny')
  assert.equal(again.outcome, 'done', JSON.stringify(again))
  const bad = await act(idOf, 'reconnect', 'broken')
  assert.equal(bad.outcome, 'failed', JSON.stringify(bad))
  assert.ok(marks().some((m) => m.label === 'tiny' && m.w === 'start' && !pids.includes(m.pid)), 'a new process for it')
  const turnedOff = await act(idOf, 'disable', 'tiny')
  assert.equal(turnedOff.outcome, 'done', JSON.stringify(turnedOff))
  assert.equal(turnedOff.server.status, 'disabled')
  await act(idOf, 'enable', 'tiny')
  await until('its reply', () => replied(idOf, had + 1), 60_000)
})

test('what nothing here reaches: a session gone, an unknown one', { skip, timeout: 60_000 }, async () => {
  assert.equal((await call('GET', '/v1/sessions/no-such-session/mcp/live')).status, 404)
  assert.equal((await call('POST', '/v1/sessions/no-such-session/mcp/live', { action: 'reconnect', name: 'tiny' })).status, 404)
  const noAuth = await fetch(`http://127.0.0.1:${API}/v1/sessions/x/mcp/live`)
  assert.equal(noAuth.status, 401)
})
