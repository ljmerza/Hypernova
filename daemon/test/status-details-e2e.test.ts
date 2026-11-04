// The status bar's numbers against the terminal's, with the real Claude Code in a throwaway home against
// the fake provider: Session.turn's clock and tokens beside what its spinner says, across steps, a prompt
// taken in mid-turn, one that waits for its own turn, a dialog that stands, and commands run at once.
// Nothing reaches a model or the owner's own Claude Code. Skipped where Claude Code isn't installed (CI).
import { after, test } from 'node:test'
import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'

const realClaude = [process.env.HN_E2E_CLAUDE, join(homedir(), '.local/bin/claude')].find((p) => p && existsSync(p))
const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-sd-')))
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
const plan: Reply[] = []
const fake = await startFake({
  dir: join(scratch, 'fake'),
  // The main loop's requests come with tools; a title's or a suggestion's don't.
  script: (_sid, body) => (body.tools?.length && body.stream === true ? (plan.shift() ?? { blocks: [{ text: 'All done.' }], outputTokens: 30 }) : null),
})
writeFileSync(join(claudeDir, 'settings.json'), JSON.stringify({ env: { ANTHROPIC_BASE_URL: fake.url, ANTHROPIC_AUTH_TOKEN: 'sk-fake' }, permissions: { allow: ['Bash(sleep:*)'] } }))
writeFileSync(join(claudeDir, '.claude.json'), JSON.stringify({ hasCompletedOnboarding: true, projects: { [proj]: { hasTrustDialogAccepted: true, hasCompletedProjectOnboarding: true } } }))

const hub = await import('../src/hub.ts')
const { addDevice, settings } = await import('../src/store.ts')
const { scanTerms, termOf, termScreen, watchMenus } = await import('../src/terms.ts')
const { startServers } = await import('../src/http.ts')
const hooks = await import('../src/hooks.ts')
const { boxLines, spinnerOf } = await import('../src/term/shared.ts')
const TOKEN = 'test-device-token-status-details-0123456789'
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

const bash = (command: string) => ({ tool: 'Bash', input: { command } })
const viewOf = (id: string) => hub.view(hub.getSession(id)!)
/** The turn's time as the app works it out (StatusModel.kt, turnClockMs). */
function clockS(id: string): number {
  const s = viewOf(id)
  const now = Date.now()
  const w = s.turn
  return (now - s.turnStartedAt! - (w?.waitedMs ?? 0) - (w?.waitingSince ? now - w.waitingSince : 0)) / 1000
}
async function spinner(id: string) {
  const t = termOf(id)
  const scr = t ? await termScreen(t) : null
  const at = scr && boxLines(scr.text.split('\n'))
  return at ? spinnerOf(at.above) : null
}
/** The app's clock and the terminal's, within what a second's rounding and a look's lag allow. */
async function agree(what: string, id: string): Promise<number> {
  let sp = await spinner(id)
  await until(`${what}: a spinner with a time`, async () => (sp = await spinner(id))?.seconds != null, 10_000)
  const mine = clockS(id)
  assert.ok(Math.abs(mine - sp!.seconds!) <= 2.5, `${what}: the app says ${mine.toFixed(1)}s, the terminal ${sp!.seconds}s`)
  return sp!.seconds!
}
async function start(prompt: string): Promise<string> {
  const r = await call('POST', '/v1/sessions', { projectPath: proj, prompt, permissionMode: 'default' })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  const id = r.json.session.id as string
  await until('its terminal', () => termOf(id) != null)
  return id
}
const idle = (id: string) => until('the turn’s end', () => viewOf(id).state === 'idle' && !viewOf(id).turn, 60_000)

test('real Claude Code: the app’s turn clock and tokens against its spinner', { skip: realClaude ? false : 'Claude Code isn’t installed here', timeout: 240_000 }, async () => {
  hooks.installIntegration()
  settings.claudeAgents = false
  closeServers = startServers()
  timers.push(setInterval(hub.pollRegistry, 500), setInterval(hub.pollTranscripts, 300), setInterval(scanTerms, 500), setInterval(watchMenus, 800))

  // ── Several tool calls in a row: neither clock restarts, tokens add up ──
  plan.push({ blocks: [{ text: 'One.' }, bash('sleep 5')], outputTokens: 1500 }, { blocks: [bash('sleep 5')], outputTokens: 1400 }, { blocks: [bash('sleep 5')], outputTokens: 900 }, { blocks: [{ text: 'Done.' }], outputTokens: 60, waitMs: 2000 })
  const rows = await start('[Hypernova test] several steps')
  await until('the second step', () => viewOf(rows).turn?.step?.command === 'sleep 5' && (viewOf(rows).turn?.tokens ?? 0) >= 2900, 30_000)
  const second = await agree('second step', rows)
  assert.ok(second >= 4, `the terminal's clock didn't restart: ${second}s`)
  assert.ok(Date.now() - viewOf(rows).turn!.step!.startedAt < 7000, 'the step’s time is its own, not the turn’s')
  await until('the third step', () => (viewOf(rows).turn?.tokens ?? 0) >= 3800, 30_000)
  const third = await agree('third step', rows)
  assert.ok(third > second, 'still counting up')
  // It counts up to a new figure over a moment.
  await until('the spinner’s tokens', async () => (await spinner(rows))?.tokens === 3800, 4000)
  assert.equal(viewOf(rows).turn!.tokens, 3800)
  await idle(rows)

  // ── A prompt taken in mid-turn: one turn to both; one left until the turn ends: a new turn to both ──
  plan.push({ blocks: [bash('sleep 6')], outputTokens: 800 }, { blocks: [bash('sleep 4')], outputTokens: 800 }, { blocks: [{ text: 'First.' }], outputTokens: 50, waitMs: 8000 }, { blocks: [bash('sleep 4')], outputTokens: 300 }, { blocks: [{ text: 'Second.' }], outputTokens: 50 })
  const queued = await start('[Hypernova test] queue')
  await until('its command', () => viewOf(queued).turn?.step?.command === 'sleep 6', 20_000)
  const began = viewOf(queued).turnStartedAt
  await until('the box', async () => (await call('POST', `/v1/sessions/${queued}/prompt`, { text: '[Hypernova test] taken in mid-turn' })).status === 200, 5000)
  await until('it taken in', () => viewOf(queued).turn?.step?.command === 'sleep 4', 20_000)
  assert.equal(viewOf(queued).turnStartedAt, began, 'taken in mid-turn: the same turn')
  assert.ok((await agree('after a prompt taken in', queued)) >= 5, 'the terminal’s clock went on too')
  // The last reply is on its way (8 seconds): this one has to wait for its own turn.
  await until('the last reply', () => viewOf(queued).turn?.step == null, 20_000)
  await until('the box', async () => (await call('POST', `/v1/sessions/${queued}/prompt`, { text: '[Hypernova test] a turn of its own' })).status === 200, 5000)
  await until('its own turn', () => viewOf(queued).turnStartedAt !== began && viewOf(queued).turn?.step?.command === 'sleep 4', 30_000)
  assert.ok((await agree('a turn of its own', queued)) < 4, 'the terminal’s clock started again too')
  assert.ok(viewOf(queued).turn!.tokens! <= 400, `its tokens started again: ${viewOf(queued).turn!.tokens}`)
  await idle(queued)

  // ── Two prompts queued while one command runs: the box's dim hint isn't unsent words ──
  plan.push({ blocks: [bash('sleep 10')], outputTokens: 100 }, { blocks: [{ text: 'Both taken.' }], outputTokens: 30 })
  const two = await start('[Hypernova test] two queued')
  await until('its command', () => viewOf(two).turn?.step?.command === 'sleep 10', 20_000)
  await until('the box', async () => (await call('POST', `/v1/sessions/${two}/prompt`, { text: '[Hypernova test] queued one' })).status === 200, 5000)
  await until('the first in the queue', () => viewOf(two).queued === 1, 5000)
  const another = await call('POST', `/v1/sessions/${two}/prompt`, { text: '[Hypernova test] queued two' })
  assert.equal(another.status, 200, `a second prompt queued: ${JSON.stringify(another.json)}`)
  await until('both in the queue', () => viewOf(two).queued === 2, 5000)
  await idle(two)

  // ── A dialog that stands: neither clock counts it ──
  plan.push({ blocks: [bash('sleep 3')], outputTokens: 400 }, { blocks: [bash('touch needs-approval')], outputTokens: 400 }, { blocks: [bash('sleep 4')], outputTokens: 400 }, { blocks: [{ text: 'Done.' }], outputTokens: 30 })
  const asks = await start('[Hypernova test] asks')
  await until('its ask', () => viewOf(asks).pendingApprovalIds.length > 0, 30_000)
  const waiting = viewOf(asks).turn!.waitingSince
  assert.ok(waiting, 'the stop is on the session')
  await sleep(12_000)
  assert.ok(clockS(asks) < 8, `standing, the app’s clock stands too: ${clockS(asks).toFixed(1)}s`)
  assert.equal((await call('POST', `/v1/approvals/${viewOf(asks).pendingApprovalIds[0]}`, { decision: 'allow' })).status, 200)
  await until('the last command', () => viewOf(asks).turn?.step?.command === 'sleep 4', 20_000)
  assert.ok(viewOf(asks).turn!.waitedMs >= 11_000, `its wait is counted apart: ${viewOf(asks).turn!.waitedMs}`)
  const after_ = await agree('after the dialog', asks)
  assert.ok(after_ < 11, `the terminal didn’t count the dialog: ${after_}s`)
  assert.ok(Date.now() - viewOf(asks).turnStartedAt! > 12_000, 'turnStartedAt still counts from the start, for the sheet and Home')
  await idle(asks)

  // ── Commands at once ──
  plan.push({ blocks: [1, 2, 3, 4, 5, 6].map((n) => bash(`sleep ${6 + n}`)), outputTokens: 2900 })
  const six = await start('[Hypernova test] six at once')
  await until('all six', () => viewOf(six).turn?.step?.running === 6, 20_000)
  await until('fewer as they end', () => (viewOf(six).turn?.step?.running ?? 6) < 6, 30_000)
  await idle(six)
})
