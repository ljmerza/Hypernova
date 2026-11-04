// A Monitor against a plain background shell, with the real Claude Code in a throwaway home against the
// fake provider: what the session says on the wire while its main agent rests, and that nothing is left
// holding it in Working when a Monitor runs out, its stream ends or its process dies (docs/PROTOCOL.md,
// "Background commands"). Nothing reaches a model or the owner's own Claude Code. Skipped where Claude
// Code isn't installed (CI).
import { after, test } from 'node:test'
import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'

const realClaude = [process.env.HN_E2E_CLAUDE, join(homedir(), '.local/bin/claude')].find((p) => p && existsSync(p))
const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-mon-')))
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

const { startFake } = await import('./fake-provider/server.ts')
type Reply = { blocks: Array<{ text: string } | { tool: string; input: Record<string, unknown> }>; outputTokens?: number; waitMs?: number }
// By session, told by its prompt: an event or a shell's end wakes a session while the next one starts, and takes no one else's reply.
const plans = new Map<string, Reply[]>()
const replyFor = (body: object): Reply => {
  const said = JSON.stringify((body as { messages?: unknown }).messages ?? '')
  for (const [prompt, replies] of plans) if (said.includes(prompt)) return replies.shift() ?? { blocks: [{ text: 'All done.' }], outputTokens: 30 }
  return { blocks: [{ text: 'All done.' }], outputTokens: 30 }
}
const fake = await startFake({
  dir: join(scratch, 'fake'),
  // The main loop's requests come with tools; a title's or a suggestion's don't.
  script: (_sid, body) => (body.tools?.length && body.stream === true ? replyFor(body) : null),
})
writeFileSync(join(claudeDir, 'settings.json'), JSON.stringify({ env: { ANTHROPIC_BASE_URL: fake.url, ANTHROPIC_AUTH_TOKEN: 'sk-fake' }, permissions: { allow: ['Bash(sleep:*)', 'Monitor'] } }))
writeFileSync(join(claudeDir, '.claude.json'), JSON.stringify({ hasCompletedOnboarding: true, projects: { [proj]: { hasTrustDialogAccepted: true, hasCompletedProjectOnboarding: true } } }))

const hub = await import('../src/hub.ts')
const { addDevice, settings } = await import('../src/store.ts')
const { scanTerms, termOf, watchMenus } = await import('../src/terms.ts')
const { startServers } = await import('../src/http.ts')
const hooks = await import('../src/hooks.ts')
const agents = await import('../src/agents.ts')
const TOKEN = 'test-device-token-monitors-e2e-0123456789'
addDevice({ id: 'phone', name: 'Phone', model: 'P', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)

const sleep = (ms: number) => new Promise((ok) => setTimeout(ok, ms))
async function until(what: string, check: () => boolean | Promise<boolean>, ms = 45_000): Promise<void> {
  const t0 = Date.now()
  while (!(await check())) {
    if (Date.now() - t0 > ms) throw new Error(`waited ${ms} ms for ${what}`)
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

const viewOf = (id: string) => JSON.parse(JSON.stringify(hub.view(hub.getSession(id)!)))
async function start(prompt: string, blocks: Reply['blocks']): Promise<string> {
  plans.set(prompt, [{ blocks, outputTokens: 40 }, { blocks: [{ text: 'Left it going.' }], outputTokens: 20 }])
  const r = await call('POST', '/v1/sessions', { projectPath: proj, prompt, permissionMode: 'default' })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  const id = r.json.session.id as string
  await until('its terminal', () => termOf(id) != null)
  return id
}
const monitor = (description: string, command: string, more: object) => ({ tool: 'Monitor', input: { description, command, ...more } })
/** Its main agent has stopped and [ok] holds. */
const resting = (id: string, what: string, ok: (s: any) => boolean) => until(what, () => viewOf(id).state === 'idle' && ok(viewOf(id)), 45_000)
const registryStatus = (id: string) => {
  const dir = join(claudeDir, 'sessions')
  for (const f of readdirSync(dir)) {
    try {
      const o = JSON.parse(readFileSync(join(dir, f), 'utf8'))
      if (o.sessionId === id) return { status: o.status as string, pid: o.pid as number }
    } catch {}
  }
  return null
}

test('real Claude Code: a Monitor against a plain shell', { skip: realClaude ? false : 'Claude Code isn’t installed here', timeout: 280_000 }, async () => {
  hooks.installIntegration()
  settings.claudeAgents = false
  closeServers = startServers()
  timers.push(setInterval(hub.pollRegistry, 500), setInterval(hub.pollTranscripts, 300), setInterval(scanTerms, 500), setInterval(watchMenus, 800), setInterval(agents.pollAgents, 1000))

  // ── A plain shell: counted, and nothing holds the session ──
  const plain = await start('[Hypernova test] a plain shell', [{ tool: 'Bash', input: { command: 'sleep 90', description: 'Long sleep', run_in_background: true } }])
  await resting(plain, 'the shell at rest', (s) => s.shellsRunning === 1)
  assert.equal('monitorsRunning' in viewOf(plain), false)

  // ── A Monitor that runs out, its main agent at rest: told apart, and the registry isn't "busy" ──
  const timed = await start('[Hypernova test] a Monitor running out', [monitor('Watcher', 'sleep 90', { timeout_ms: 30_000, persistent: false })])
  await resting(timed, 'the Monitor at rest', (s) => s.monitorsRunning === 1)
  assert.equal(viewOf(timed).shellsRunning, 1)
  assert.equal(agents.listAgents(timed).shells[0]!.kind, 'monitor')
  // The daemon goes by this for "working": with a Monitor running it isn't, so the state stays idle.
  assert.notEqual(registryStatus(timed)?.status, 'busy')
  await until('it runs out', () => !('monitorsRunning' in viewOf(timed)) && !('shellsRunning' in viewOf(timed)), 60_000)
  await until('its last turn', () => viewOf(timed).state === 'idle', 30_000)

  // ── A Monitor whose stream ends ──
  const short = await start('[Hypernova test] a Monitor ending', [monitor('Short watcher', 'sleep 20; echo done', { timeout_ms: 60_000, persistent: false })])
  await resting(short, 'the Monitor at rest', (s) => s.monitorsRunning === 1)
  await until('its stream ends', () => !('monitorsRunning' in viewOf(short)), 60_000)
  assert.equal(agents.listAgents(short).shells[0]!.status, 'done')

  // ── A persistent one: no time of its own, and it goes when its process dies, with no word ──
  const kept = await start('[Hypernova test] a persistent Monitor', [monitor('Kept watcher', 'sleep 90', { persistent: true })])
  await resting(kept, 'the Monitor at rest', (s) => s.monitorsRunning === 1)
  const proc = registryStatus(kept)!
  process.kill(proc.pid, 'SIGKILL')
  await until('its process gone', () => !('monitorsRunning' in viewOf(kept)) && !('shellsRunning' in viewOf(kept)), 30_000)
  assert.equal(agents.listAgents(kept).shells[0]!.status, 'unknown')
})
