// What the sheet opening on Ask is worth, with the real Claude Code in a throwaway home against the fake provider: a
// settings.json holding Bypass must not reach a session started from a client on Ask, and a project's own settings must
// not change the mode a client names (start-modes.ts; docs/DECISIONS.md, "One starting mode, the agent's own"). Nothing
// reaches a model or the owner's own Claude Code. Skipped where Claude Code isn't installed (CI).
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
const marked = join(scratch, 'proj', 'ran-without-asking')
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
const touch = { tool: 'Bash', input: { command: `touch ${marked}` } }

test('real Claude Code: Bypass in settings.json, the form on Ask, and the session asks', { skip: realClaude ? false : 'Claude Code isn’t installed here', timeout: 180_000 }, async () => {
  hooks.installIntegration()
  settings.claudeAgents = false
  // installIntegration added its hooks to this file: the mode goes in beside them.
  const withHooks = JSON.parse(readFileSync(join(claudeDir, 'settings.json'), 'utf8').trim() || '{}')
  const mode = (m: string) => writeFileSync(join(claudeDir, 'settings.json'), JSON.stringify({ ...withHooks, env, skipDangerousModePermissionPrompt: true, permissions: { ...withHooks.permissions, defaultMode: m } }))
  mode('bypassPermissions')
  closeServers = startServers()
  timers.push(setInterval(hub.pollRegistry, 500), setInterval(hub.pollTranscripts, 300), setInterval(scanTerms, 500), setInterval(watchMenus, 800))

  // What a form opens on, as it hears it, and sent as a form sends it.
  const opensOn = (await call('GET', '/v1/snapshot')).json.claude.startMode
  assert.equal(opensOn, 'default')
  plan.push({ blocks: [touch], outputTokens: 20 })
  const asks = await start(proj, '[Hypernova test] the form on Ask', opensOn)
  await until('it stopping to ask', () => viewOf(asks).state === 'needs_you', 60_000)
  assert.equal(existsSync(marked), false, 'it asked before running anything')
  assert.equal(viewOf(asks).permissionMode, 'default')
  // A real ask, on its way to a phone, for that command. Denied: it never runs.
  const ask = (await call('GET', '/v1/snapshot')).json.approvals.find((a: { sessionId: string }) => a.sessionId === asks)
  assert.ok(ask, 'an approval waits')
  assert.equal((await call('POST', `/v1/approvals/${ask.id}`, { decision: 'deny' })).status, 200)
  await until('its turn over', () => viewOf(asks).state === 'idle', 60_000)
  assert.equal(existsSync(marked), false, 'denied, it never ran')

  // The control: no mode named, as a plain terminal starts, and this same file does run it. So the test above can fail.
  plan.length = 0
  plan.push({ blocks: [touch], outputTokens: 20 })
  const bypasses = await start(proj, '[Hypernova test] no mode named')
  await until('the command run with nothing asked', () => existsSync(marked), 60_000)
  assert.notEqual(viewOf(bypasses).state, 'needs_you')
})

test('real Claude Code: a project\'s own defaultMode doesn\'t change the mode a form names, and does a terminal\'s', { skip: realClaude ? false : 'Claude Code isn’t installed here', timeout: 180_000 }, async () => {
  const withHooks = JSON.parse(readFileSync(join(claudeDir, 'settings.json'), 'utf8'))
  writeFileSync(join(claudeDir, 'settings.json'), JSON.stringify({ ...withHooks, permissions: { ...withHooks.permissions, defaultMode: 'acceptEdits' } }))
  assert.equal((await call('GET', '/v1/snapshot')).json.claude.startMode, 'acceptEdits')
  plan.length = 0
  const named = await start(other, '[Hypernova test] the form names its mode', 'acceptEdits')
  await until('its mode', () => viewOf(named).permissionMode != null && viewOf(named).state === 'idle', 60_000)
  assert.equal(viewOf(named).permissionMode, 'acceptEdits')
  const plain = await start(other, '[Hypernova test] no mode named, as a terminal')
  await until('its mode', () => viewOf(plain).permissionMode === 'plan', 60_000)
})
