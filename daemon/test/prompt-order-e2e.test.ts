// A message after a command, with the real Claude Code in a throwaway home against the fake provider
// (docs/PROTOCOL.md, "Held prompts", "A command and the words after it"). The rule: a message sent after a
// command never becomes that command's arguments and is never lost; each runs as its own turn, in order.
// What reaches /v1/messages says what Claude really got. Nothing reaches a model or the owner's own
// Claude Code. Skipped where Claude Code isn't installed (CI).
import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'

const realClaude = [process.env.HN_E2E_CLAUDE, join(homedir(), '.local/bin/claude')].find((p) => p && existsSync(p))
const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-order-')))
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

// Every main-loop request, oldest first: the words of its user messages.
const requests: string[] = []
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
      return { blocks: [{ tool: 'Bash', input: { command: 'sleep 6', description: 'wait a bit' } }], outputTokens: 20 }
    }
    return { blocks: [{ text: 'All done.' }], outputTokens: 30 }
  },
})
writeFileSync(join(claudeDir, 'settings.json'), JSON.stringify({ env: { ANTHROPIC_BASE_URL: fake.url, ANTHROPIC_AUTH_TOKEN: 'sk-fake' }, permissions: { allow: ['Bash(sleep:*)'] } }))
writeFileSync(join(claudeDir, '.claude.json'), JSON.stringify({ hasCompletedOnboarding: true, projects: { [proj]: { hasTrustDialogAccepted: true, hasCompletedProjectOnboarding: true } } }))

const hub = await import('../src/hub.ts')
const { addDevice, settings } = await import('../src/store.ts')
const { pollClaudeAgents } = await import('../src/claude-agents.ts')
const { scanTerms, termOf, watchMenus } = await import('../src/terms.ts')
const { startServers } = await import('../src/http.ts')
const hooks = await import('../src/hooks.ts')
const TOKEN = 'test-device-token-order-0123456789'
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
const live = !!realClaude
before(() => {
  if (!live) return
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
  await fake.close()
  rmSync(scratch, { recursive: true, force: true })
})

const hold = (id: string, text: string, clientId: string) => call('POST', `/v1/sessions/${id}/prompt`, { text, clientId, hold: true })
/** A session at rest in its own shared terminal, with one reply behind it. */
async function atRest(first: string): Promise<string> {
  const r = await call('POST', '/v1/sessions', { projectPath: proj, prompt: `[Hypernova test] ${first}`, permissionMode: 'default' })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  const id = r.json.session.id as string
  await until(`${first}: its reply`, () => {
    const f = hub.transcriptPathFor(hub.getSession(id)!.cwd, id)
    return existsSync(f) && readFileSync(f, 'utf8').includes('All done.')
  })
  await until(`${first}: its terminal`, () => {
    scanTerms()
    return termOf(id) != null && hub.getSession(id)?.state === 'idle'
  })
  await sleep(800)
  return id
}
const heardFirstIn = (words: string) => requests.findIndex((r) => r.includes(words))
/** Every transcript of this home that holds [needle], as lines. */
function linesWith(needle: string): string[] {
  const root = join(claudeDir, 'projects')
  const out: string[] = []
  for (const d of existsSync(root) ? readdirSync(root) : []) for (const f of readdirSync(join(root, d))) if (f.endsWith('.jsonl')) out.push(...readFileSync(join(root, d, f), 'utf8').split('\n').filter((l) => l.includes(needle)))
  return out
}
/** The session Claude got [words] in, by its title. */
const sessionTitled = (words: string) => hub.listSessions().find((s: any) => s.title === words)
/** [words] reached Claude as a plain prompt in a conversation that /clear emptied, never as anything's arguments. */
function clearedThenHeard(what: string, words: string, before: string): void {
  const i = heardFirstIn(words)
  assert.ok(i >= 0, `${what}: Claude got the message`)
  assert.ok(!requests[i]!.includes(before), `${what}: in the emptied conversation, without what came before /clear:\n${requests[i]!.slice(-600)}`)
  assert.ok(requests[i]!.includes('<command-name>/clear</command-name>'), `${what}: after /clear ran:\n${requests[i]!.slice(-600)}`)
  assert.ok(!requests[i]!.includes('while you were working'), `${what}: as a turn of its own`)
  assert.equal(linesWith(`<command-args>${words}`).length, 0, `${what}: not /clear's arguments`)
}

const skip = realClaude ? false : 'Claude Code isn’t installed here'

test('one send holding /clear and words after it (the “/” list’s “/clear ” and then typing): /clear, then the words as their own turn', { skip, timeout: 180_000 }, async () => {
  const id = await atRest('joined by a space')
  const words = 'read and stop /tmp/one.md [Hypernova test]'
  const r = await hold(id, `/clear ${words}`, 'c-joined')
  assert.equal(r.status, 200, JSON.stringify(r.json))
  await until('the words reach Claude', () => heardFirstIn(words) >= 0)
  clearedThenHeard('a space', words, 'joined by a space')
  await until('its title', () => !!sessionTitled(words))
  assert.equal(r.json.session.id, sessionTitled(words)!.id, 'the answer names the session it went on in, so a client follows')
  assert.ok(!hub.listSessions().some((s: any) => s.title?.startsWith('/clear read')), 'no session is titled with both')
})

test('the same with a line break (a send that came back to the box, ahead of what was typed since)', { skip, timeout: 180_000 }, async () => {
  const id = await atRest('joined by a line')
  const words = 'read and stop /tmp/two.md [Hypernova test]'
  const r = await hold(id, `/clear\n${words}`, 'c-lines')
  assert.equal(r.status, 200, JSON.stringify(r.json))
  await until('the words reach Claude', () => heardFirstIn(words) >= 0)
  clearedThenHeard('a line break', words, 'joined by a line')
})

test('/clear while Claude works through a tool, then a message: the turn finishes, /clear runs, the message runs, in that order', { skip, timeout: 180_000 }, async () => {
  const id = await atRest('tool step clear')
  toolsAhead = 1
  await hold(id, '[Hypernova test] a step with a tool', 'c-tool')
  await until('the tool runs', () => hub.getSession(id)?.state === 'working' && String(hub.getSession(id)?.activity ?? '').startsWith('Bash'))
  const words = 'read and stop /tmp/three.md [Hypernova test]'
  const a = await hold(id, '/clear', 'c-clear')
  assert.equal(a.status, 200, JSON.stringify(a.json))
  const b = await hold(id, words, 'c-after')
  assert.equal(b.status, 200, JSON.stringify(b.json))
  await until('the words reach Claude', () => heardFirstIn(words) >= 0, 90_000)
  clearedThenHeard('a tool step', words, 'tool step clear')
  await until('the title', () => !!sessionTitled(words))
})

test('/compact while Claude works through a tool, then a message: the message comes after the compaction, not into the turn before it', { skip, timeout: 240_000 }, async () => {
  const id = await atRest('tool step compact')
  toolsAhead = 1
  await hold(id, '[Hypernova test] a step before compacting', 'c-tool2')
  await until('the tool runs', () => hub.getSession(id)?.state === 'working' && String(hub.getSession(id)?.activity ?? '').startsWith('Bash'))
  const words = 'read and stop /tmp/four.md [Hypernova test]'
  await hold(id, '/compact', 'c-compact')
  await hold(id, words, 'c-after2')
  await until('the words reach Claude', () => heardFirstIn(words) >= 0, 120_000)
  const i = heardFirstIn(words)
  assert.ok(requests[i]!.includes('<command-name>/compact</command-name>'), `after the compaction:\n${requests[i]!.slice(-600)}`)
  assert.ok(!requests[i]!.includes('while you were working'), 'as a turn of its own')
  assert.equal(linesWith(`<command-args>${words}`).length, 0, 'not /compact’s words')
})

test('/compact’s own words stay its words: the Commands tab’s “Keep…”', { skip, timeout: 120_000 }, async () => {
  const id = await atRest('compact with words')
  const r = await hold(id, '/compact keep the file names', 'c-keep')
  assert.equal(r.status, 200, JSON.stringify(r.json))
  await until('the record', () => linesWith('<command-args>keep the file names').length > 0, 60_000)
})

test('a message to the old session some time after /clear goes on in the emptied conversation, not back in the old one', { skip, timeout: 180_000 }, async () => {
  const id = await atRest('later after clear')
  const a = await hold(id, '/clear', 'c-clear3')
  assert.equal(a.status, 200, JSON.stringify(a.json))
  // The terminal has moved on: the old session has none now.
  await until('the terminal moves', () => {
    scanTerms()
    return termOf(id) == null
  })
  await sleep(1500)
  const words = 'read and stop /tmp/five.md [Hypernova test]'
  const r = await hold(id, words, 'c-later')
  assert.equal(r.status, 200, JSON.stringify(r.json))
  await until('the words reach Claude', () => heardFirstIn(words) >= 0)
  clearedThenHeard('later', words, 'later after clear')
  assert.notEqual(r.json.session.id, id, 'the answer names the emptied conversation')
})

test('held behind an open question: /clear and a message both wait, then go in order, the message into the emptied conversation', { skip, timeout: 180_000 }, async () => {
  const id = await atRest('held clear')
  void hub.requestApproval({ sessionId: id, kind: 'tool', toolName: 'Bash', summary: 'Bash: ls', input: { command: 'ls' }, reason: null, canAlways: false, questions: null, plan: null }, null)
  const words = 'read and stop /tmp/six.md [Hypernova test]'
  const a = await hold(id, '/clear', 'c-held1')
  const b = await hold(id, words, 'c-held2')
  assert.equal(a.json.held, true, JSON.stringify(a.json))
  assert.equal(b.json.held, true, JSON.stringify(b.json))
  const [ask] = hub.pendingApprovals().filter((x: any) => x.sessionId === id)
  hub.answerApproval(ask!.id, { decision: 'deny' }, 'phone')
  await until('the words reach Claude', () => heardFirstIn(words) >= 0, 60_000)
  clearedThenHeard('held', words, 'held clear')
})

test('the same in a `claude agents` job: /clear while it works through a tool, then a message', { skip, timeout: 240_000 }, async () => {
  settings.claudeAgents = true
  timers.push(setInterval(pollClaudeAgents, 1000))
  try {
    const r = await call('POST', '/v1/sessions', { projectPath: proj, prompt: '[Hypernova test] job clear', permissionMode: 'default' })
    assert.equal(r.status, 200, JSON.stringify(r.json))
    const id = r.json.session.id as string
    await until('its reply', () => {
      const f = hub.transcriptPathFor(hub.getSession(id)!.cwd, id)
      return existsSync(f) && readFileSync(f, 'utf8').includes('All done.')
    })
    await until('at rest', () => hub.getSession(id)?.state === 'idle')
    await sleep(1500)
    toolsAhead = 1
    await hold(id, '[Hypernova test] a step in the job', 'c-job1')
    await until('the tool runs', () => hub.getSession(id)?.state === 'working' && String(hub.getSession(id)?.activity ?? '').startsWith('Bash'), 30_000)
    const words = 'read and stop /tmp/seven.md [Hypernova test]'
    const a = await hold(id, '/clear', 'c-job2')
    assert.equal(a.status, 200, JSON.stringify(a.json))
    const b = await hold(id, words, 'c-job3')
    assert.equal(b.status, 200, JSON.stringify(b.json))
    await until('the words reach Claude', () => heardFirstIn(words) >= 0, 120_000)
    clearedThenHeard('a job', words, 'job clear')
  } finally {
    settings.claudeAgents = false
  }
})
