// Writing in an ended session that was a `claude agents` job, with the real Claude Code in a throwaway
// home against the fake provider: `claude --bg`, `claude rm`, `claude --bg --resume`, `claude attach`
// and `claude agents --json` all as they are, nothing reaching a model or the owner's own Claude Code.
// What the daemon reads to know it was a job (`sessionKind`) is undocumented, and this is what would
// catch it going away. Skipped where Claude Code isn't installed (CI).
import { after, test } from 'node:test'
import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'

const realClaude = [process.env.HN_E2E_CLAUDE, join(homedir(), '.local/bin/claude')].find((p) => p && existsSync(p))
const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-rj-')))
// A test run from inside a session has that session's own in its environment, its job's folder above all.
for (const k of Object.keys(process.env)) if (/^(CLAUDE|ANTHROPIC|HYPERNOVA|HN_)/.test(k)) delete process.env[k]
const claudeDir = join(scratch, 'claude')
const proj = join(scratch, 'proj')
const other = join(scratch, 'worktree')
for (const d of ['home', 'zdot', 'tmp', 'claude', 'proj', 'worktree']) mkdirSync(join(scratch, d))
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

const { CANNED, startFake } = await import('./fake-provider/server.ts')
const fake = await startFake({ dir: join(scratch, 'fake') })
writeFileSync(join(claudeDir, 'settings.json'), JSON.stringify({ env: { ANTHROPIC_BASE_URL: fake.url, ANTHROPIC_AUTH_TOKEN: 'sk-fake' } }))
const trusted = { hasTrustDialogAccepted: true, hasCompletedProjectOnboarding: true }
writeFileSync(join(claudeDir, '.claude.json'), JSON.stringify({ hasCompletedOnboarding: true, projects: { [proj]: trusted, [other]: trusted } }))

const hub = await import('../src/hub.ts')
const { addDevice, settings } = await import('../src/store.ts')
const { createAgentsSession, createSharedSession, endTermAndWait, scanTerms, termOf } = await import('../src/terms.ts')
const { pollClaudeAgents } = await import('../src/claude-agents.ts')
const { startServers } = await import('../src/http.ts')
const hooks = await import('../src/hooks.ts')
const TOKEN = 'test-device-token-resume-job-0123456789'
addDevice({ id: 'phone', name: 'Phone', model: 'P', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)

const claude = (...args: string[]) => new Promise<string>((ok, no) => execFile(realClaude!, args, { cwd: proj, timeout: 30_000 }, (e, out, err) => (e ? no(new Error(`claude ${args.join(' ')}: ${err || e.message}`)) : ok(out))))
const listed = async (id: string) => (JSON.parse(await claude('agents', '--json', '--all')) as { kind: string; sessionId: string }[]).find((j) => j.sessionId === id && j.kind === 'background')
const removedFile = () => JSON.parse(readFileSync(join(process.env.HYPERNOVA_SUPPORT!, 'deleted-sessions.json'), 'utf8'))
const sleep = (ms: number) => new Promise((ok) => setTimeout(ok, ms))
async function until(what: string, check: () => boolean | Promise<boolean>, ms = 45_000): Promise<void> {
  const t0 = Date.now()
  while (!(await check())) {
    if (Date.now() - t0 > ms) throw new Error(`waited ${ms} ms for ${what}`)
    await sleep(200)
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
/** The transcript's messages as [role] wrote them, in order, as text. */
function said(path: string, role: 'user' | 'assistant'): string[] {
  if (!existsSync(path)) return []
  const out: string[] = []
  for (const line of readFileSync(path, 'utf8').split('\n')) {
    if (!line) continue
    const o = JSON.parse(line)
    if (o.type !== role || o.isMeta) continue
    const c = o.message?.content
    out.push(typeof c === 'string' ? c : (Array.isArray(c) ? c : []).map((b: any) => b?.text ?? '').join(''))
  }
  return out
}
const pathOf = (id: string) => hub.transcriptPathFor(hub.getSession(id)!.cwd, id)
const providerCalls = async () => {
  const r = await fetch(`${fake.url}/_fake/faults`, { headers: { 'x-fake-token': fake.token } })
  return ((await r.json()) as { log: { path: string }[] }).log.filter((l) => l.path === '/v1/messages').length
}
/** What each holder of session [id] runs. */
function holderArgs(id: string): string[][] {
  const dir = join(process.env.HYPERNOVA_SUPPORT!, 'terms')
  const out: string[][] = []
  for (const f of existsSync(dir) ? readdirSync(dir) : []) {
    if (!f.endsWith('.json')) continue
    try {
      const meta = JSON.parse(readFileSync(join(dir, f), 'utf8'))
      if (meta.sessionId === id) out.push(meta.args)
    } catch {}
  }
  return out
}

const timers: NodeJS.Timeout[] = []
let closeServers: (() => Promise<void>) | null = null
after(async () => {
  for (const t of timers) clearInterval(t)
  await closeServers?.()
  // The jobs, then Claude Code's background daemon of this home, then what still holds a terminal.
  if (realClaude) await claude('daemon', 'stop', '--any').catch(() => {})
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

/** A session started as the phone starts one with settings.claudeAgents, its first reply in, then taken off Home. */
async function removedJob(cwd: string, words: string): Promise<string> {
  const id = await createAgentsSession({ cwd, prompt: words })
  await until('its first reply', () => said(pathOf(id), 'assistant').some((t) => t.includes(CANNED)))
  await until('the list to have it', async () => !!(await listed(id)))
  await until('the daemon to read the list', () => hub.agentJobOf(id) != null)
  const r = await call('DELETE', `/v1/sessions/${id}`)
  assert.equal(r.status, 200, JSON.stringify(r.json))
  assert.equal(hub.getSession(id)?.state, 'ended')
  assert.equal(await listed(id), undefined, '`claude rm` took it off `claude agents`')
  assert.ok(Object.hasOwn(removedFile(), id))
  return id
}

test('real Claude Code: a job taken off the list and written in comes back on claude agents, in its own transcript, with the words; a plain session and a folder gone don’t', { skip: realClaude ? false : 'Claude Code isn’t installed here', timeout: 240_000 }, async () => {
  settings.claudeAgents = true
  hooks.installIntegration()
  closeServers = startServers()
  timers.push(setInterval(hub.pollRegistry, 500), setInterval(hub.pollTranscripts, 300), setInterval(pollClaudeAgents, 1000))

  // ── A job comes back as a job ──
  const id = await removedJob(proj, '[Hypernova test] first words')
  const file = pathOf(id)
  const calls = await providerCalls()
  const t0 = Date.now()
  const r = await call('POST', `/v1/sessions/${id}/prompt`, { text: '[Hypernova test] second words' })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  console.log(`writing brought it back in ${Date.now() - t0} ms`)
  assert.equal(r.json.session.id, id, 'the same id')
  assert.equal(r.json.forked, undefined, 'no copy')
  const job = r.json.session.agentJob
  assert.equal(typeof job, 'string', 'a job again')
  assert.ok(!Object.hasOwn(removedFile(), id), 'its line left the file')
  await until('`claude agents` to list it', async () => !!(await listed(id)))
  assert.equal((await listed(id) as any).id, job)
  assert.deepEqual(holderArgs(id), [['attach', job]], 'typed through claude attach, with no claude of its own in a shared terminal')
  // The same conversation, the words in it, and the provider's answer after them.
  await until('the second words in the transcript', () => said(file, 'user').includes('[Hypernova test] second words'))
  assert.deepEqual(said(file, 'user').filter((t) => t.startsWith('[Hypernova test]')), ['[Hypernova test] first words', '[Hypernova test] second words'])
  await until('its reply', () => said(file, 'assistant').filter((t) => t.includes(CANNED)).length >= 2)
  assert.ok((await providerCalls()) > calls, 'the provider heard it')
  assert.equal(readdirSync(join(claudeDir, 'projects', file.split('/').at(-2)!)).filter((f) => f.endsWith('.jsonl')).length, 1, 'no second transcript')
  await until('the daemon to say it runs', () => hub.getSession(id)?.state !== 'ended' && hub.agentJobOf(id) === job)

  // ── A plain session stays out of `claude agents` ──
  const plain = createSharedSession({ cwd: proj, prompt: '[Hypernova test] plain words' })
  const plainFile = () => hub.transcriptPathFor(proj, plain)
  await until('the plain session’s reply', () => said(plainFile(), 'assistant').some((t) => t.includes(CANNED)))
  await until('its terminal', () => {
    scanTerms()
    return termOf(plain) != null
  })
  assert.ok(await endTermAndWait(termOf(plain)!))
  await until('it to end', () => hub.getSession(plain)?.state === 'ended')
  const p = await call('POST', `/v1/sessions/${plain}/prompt`, { text: '[Hypernova test] plain again' })
  assert.equal(p.status, 200, JSON.stringify(p.json))
  assert.equal(p.json.session.agentJob, undefined)
  await until('its shared terminal', () => holderArgs(plain).some((a) => a.includes('--resume')))
  await until('the plain session’s words', () => said(plainFile(), 'user').some((t) => t.includes('[Hypernova test] plain again')))
  assert.equal(await listed(plain), undefined)

  // ── A job whose folder went says so, and stays where it is ──
  const gone = await removedJob(other, '[Hypernova test] words in a worktree')
  rmSync(other, { recursive: true })
  const g = await call('POST', `/v1/sessions/${gone}/prompt`, { text: '[Hypernova test] too late' })
  assert.equal(g.status, 409)
  assert.equal(g.json.error.code, 'folder_gone')
  assert.equal(hub.getSession(gone)?.state, 'ended')
  assert.ok(Object.hasOwn(removedFile(), gone))
  assert.equal(await listed(gone), undefined)
})
