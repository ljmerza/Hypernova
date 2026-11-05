// Changing a session's permission mode, with the real Claude Code in a throwaway home against the fake provider: a shared
// terminal and a `claude agents` job. Nothing reaches a model or the owner's own Claude Code. Skipped where Claude Code isn't
// installed (CI).
import { after, test } from 'node:test'
import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'

const realClaude = [process.env.HN_E2E_CLAUDE, join(homedir(), '.local/bin/claude')].find((p) => p && existsSync(p))
const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-sm-')))
for (const k of Object.keys(process.env)) if (/^(CLAUDE|ANTHROPIC|HYPERNOVA|HN_)/.test(k)) delete process.env[k]
const claudeDir = join(scratch, 'claude')
const proj = join(scratch, 'proj')
for (const d of ['home', 'zdot', 'tmp', 'claude', 'proj', 'other/.claude']) mkdirSync(join(scratch, d), { recursive: true })
Object.assign(process.env, {
  HYPERNOVA_SUPPORT: join(scratch, 'support'),
  HYPERNOVA_LOGS: join(scratch, 'logs'),
  CLAUDE_CONFIG_DIR: claudeDir,
  CODEX_HOME: join(scratch, 'codex'),
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

const { startFake } = await import('./fake-provider/server.ts')
type Reply = { blocks: Array<{ text: string } | { tool: string; input: Record<string, unknown> }>; outputTokens?: number }
const plan: Reply[] = []
const fake = await startFake({
  dir: join(scratch, 'fake'),
  script: (_sid, body) => (body.tools?.length && body.stream === true ? (plan.shift() ?? { blocks: [{ text: 'All done.' }], outputTokens: 10 }) : null),
})
const other = join(scratch, 'other')
const env = { ANTHROPIC_BASE_URL: fake.url, ANTHROPIC_AUTH_TOKEN: 'sk-fake' }
writeFileSync(join(claudeDir, '.claude.json'), JSON.stringify({ hasCompletedOnboarding: true, projects: { [proj]: { hasTrustDialogAccepted: true, hasCompletedProjectOnboarding: true }, [other]: { hasTrustDialogAccepted: true, hasCompletedProjectOnboarding: true } } }))
writeFileSync(join(other, '.claude', 'settings.json'), JSON.stringify({ permissions: { defaultMode: 'plan' } }))

const hub = await import('../src/hub.ts')
const { addDevice, settings } = await import('../src/store.ts')
const { scanTerms, termOf, watchMenus } = await import('../src/terms.ts')
const { startServers } = await import('../src/http.ts')
const hooks = await import('../src/hooks.ts')
const TOKEN = 'test-device-token-start-mode-0123456789'
addDevice({ id: 'phone', name: 'Phone', model: 'P', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)

const sleep = (ms: number) => new Promise((ok) => setTimeout(ok, ms))
async function until(what: string, check: () => boolean | Promise<boolean>, ms = 45_000): Promise<void> {
  const t0 = Date.now()
  while (!(await check())) {
    if (Date.now() - t0 > ms) throw new Error(`waited ${ms} ms for ${what}: ${JSON.stringify(hub.listSessions().map((s) => [s.state, s.permissionMode]))}`)
    await sleep(100)
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
after(async () => {
  for (const t of timers) clearInterval(t)
  // Nothing waits on an answer any more: the ask's hook would hold the server open for its 10 minutes.
  for (const s of hub.listSessions()) hub.resolveSession(s.id)
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
  await fake.close()
  rmSync(scratch, { recursive: true, force: true })
})

const viewOf = (id: string) => hub.view(hub.getSession(id)!)
async function start(cwd: string, prompt: string, permissionMode?: string): Promise<string> {
  const r = await call('POST', '/v1/sessions', { projectPath: cwd, prompt, ...(permissionMode ? { permissionMode } : {}) })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  const id = r.json.session.id as string
  await until('its terminal', () => termOf(id) != null)
  return id
}


// Every change the sheet can ask for, on the two kinds of session a phone can be looking at. The owner, 2026-11-04: tapping any
// mode on a job did nothing and said nothing.
const { createAgentsSession } = await import('../src/terms.ts')
const { pollClaudeAgents } = await import('../src/claude-agents.ts')

async function goThrough(id: string, label: string): Promise<string[]> {
  const out: string[] = []
  // Until its box has drawn, it's refused as "showing something", and a client says so.
  await until('its box', async () => (await call('POST', `/v1/sessions/${id}/mode`, { permissionMode: viewOf(id).permissionMode })).status === 200, 30_000)
  for (const to of ['plan', 'acceptEdits', 'default', 'plan', 'default', 'auto', 'default']) {
    const from = viewOf(id).permissionMode
    const t0 = Date.now()
    const r = await call('POST', `/v1/sessions/${id}/mode`, { permissionMode: to })
    const said = `${label} ${from} -> ${to}: ${r.status} ${r.json?.error?.code ?? r.json?.code ?? ''} in ${Date.now() - t0} ms, now ${viewOf(id).permissionMode}`
    console.log(said)
    out.push(said)
    await sleep(300)
  }
  return out
}

test('real Claude Code: every mode change on a shared terminal and on a job', { skip: realClaude ? false : 'Claude Code isn’t installed here', timeout: 300_000 }, async () => {
  hooks.installIntegration()
  const withHooks = JSON.parse(readFileSync(join(claudeDir, 'settings.json'), 'utf8').trim() || '{}')
  writeFileSync(join(claudeDir, 'settings.json'), JSON.stringify({ ...withHooks, env }))
  closeServers = startServers()
  timers.push(setInterval(hub.pollRegistry, 500), setInterval(hub.pollTranscripts, 300), setInterval(scanTerms, 500), setInterval(watchMenus, 500), setInterval(pollClaudeAgents, 1000))

  settings.claudeAgents = false
  const term = await start(proj, '[Hypernova test] a terminal')
  await until('the terminal at rest', () => viewOf(term).state === 'idle', 60_000)
  const onTerm = await goThrough(term, 'terminal')

  settings.claudeAgents = true
  const job = await createAgentsSession({ cwd: proj, prompt: '[Hypernova test] a job' })
  await until('the daemon to read the list', () => hub.agentJobOf(job) != null, 60_000)
  await until('the job at rest', () => viewOf(job).state === 'idle', 60_000)
  const onJob = await goThrough(job, 'job')

  for (const line of [...onTerm, ...onJob]) assert.match(line, /: 200 /, line)
})
