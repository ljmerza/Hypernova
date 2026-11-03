// A skill with words after it, with the real Claude Code in a throwaway home against the fake provider:
// what reaches /v1/messages, by every way a prompt gets in. Started as arguments (a shared terminal, a
// `claude agents` job, an automation's run), through the SDK, and typed into the box of a running session,
// where Claude Code folds a long paste and a fold hid the "/name" (term/shared.ts, pasteParts). Nothing
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
const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-sp-')))
// A test run from inside a session has that session's own in its environment.
for (const k of Object.keys(process.env)) if (/^(CLAUDE|ANTHROPIC|HYPERNOVA|HN_)/.test(k)) delete process.env[k]
const claudeDir = join(scratch, 'claude')
const proj = join(scratch, 'proj')
for (const d of ['home', 'zdot', 'tmp', 'claude/skills/hn-plain', 'claude/skills/hn-args', 'proj']) mkdirSync(join(scratch, d), { recursive: true })
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

// What Claude would read, by session: every user message of every request.
const heardBy = new Map<string, string[]>()
const { CANNED, startFake } = await import('./fake-provider/server.ts')
const fake = await startFake({
  dir: join(scratch, 'fake'),
  onMessages: (sid, body) => {
    const texts = (body.messages ?? []).filter((m: any) => m.role === 'user').map((m: any) => (typeof m.content === 'string' ? m.content : m.content.map((b: any) => b.text ?? '').join('\n')))
    heardBy.set(sid, [...(heardBy.get(sid) ?? []), ...texts])
  },
})
const SKILL_BODY = 'SKILLBODY-PLAIN do the throwaway thing.'
writeFileSync(join(claudeDir, 'skills/hn-plain/SKILL.md'), `---\nname: hn-plain\ndescription: A throwaway skill with no arguments placeholder\n---\n\n${SKILL_BODY}\n`)
writeFileSync(join(claudeDir, 'skills/hn-args/SKILL.md'), '---\nname: hn-args\ndescription: A throwaway skill that names its arguments\n---\n\nSKILLBODY-ARGS for [$ARGUMENTS] end.\n')
writeFileSync(join(claudeDir, 'settings.json'), JSON.stringify({ env: { ANTHROPIC_BASE_URL: fake.url, ANTHROPIC_AUTH_TOKEN: 'sk-fake' } }))
writeFileSync(join(claudeDir, '.claude.json'), JSON.stringify({ hasCompletedOnboarding: true, projects: { [proj]: { hasTrustDialogAccepted: true, hasCompletedProjectOnboarding: true } } }))

const hub = await import('../src/hub.ts')
const { addDevice, settings } = await import('../src/store.ts')
const { createSharedSession, scanTerms, termOf } = await import('../src/terms.ts')
const { pollClaudeAgents } = await import('../src/claude-agents.ts')
const { startServers } = await import('../src/http.ts')
const hooks = await import('../src/hooks.ts')
const { pasteParts } = await import('../src/term/shared.ts')
const TOKEN = 'test-device-token-skill-prompt-0123456789'
addDevice({ id: 'phone', name: 'Phone', model: 'P', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)

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
const replies = (id: string): number => {
  const file = hub.transcriptPathFor(hub.getSession(id)!.cwd, id)
  if (!existsSync(file)) return 0
  return readFileSync(file, 'utf8').split('\n').filter((l) => l.includes(CANNED) && JSON.parse(l).type === 'assistant').length
}
/** The skill ran with all of [words], as Claude reads it and as the phone is told. */
async function ranWithWords(what: string, id: string, words: string, title = true, kind = 'skill'): Promise<void> {
  const heard = (heardBy.get(id) ?? []).join('\n')
  assert.ok(heard.includes(SKILL_BODY), `${what}: the skill's body reached the provider:\n${heard.slice(-600)}`)
  assert.ok(heard.includes(`ARGUMENTS: ${words}`), `${what}: every word reached the provider, as its arguments:\n${heard.slice(-600)}`)
  await until(`${what}: the phone's event`, async () => {
    const ev = await call('GET', `/v1/sessions/${id}/events`)
    return (ev.json?.events ?? []).some((e: any) => e.kind === 'user' && e.command?.name === '/hn-plain' && e.command?.kind === kind && e.command?.args === words)
  })
  if (title) assert.ok(hub.getSession(id)!.title?.startsWith('/hn-plain look at the auth'), `${what}: its title is the skill and its task: ${hub.getSession(id)!.title}`)
}

const timers: NodeJS.Timeout[] = []
let closeServers: (() => Promise<void>) | null = null
after(async () => {
  for (const t of timers) clearInterval(t)
  await closeServers?.()
  // Claude Code's background daemon of this home, then what still holds a terminal.
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

const SEVERAL = 'look at the auth changes and ignore the tests. Also check the migrations. [Hypernova test]'
const LINES = 'look at the auth changes\nand ignore the tests.\n\nAlso check the migrations.\nAnd the docs. [Hypernova test]'
const LONG = `look at the auth changes. ${'Also check the migrations and then the rest of it. '.repeat(30)}And the docs. [Hypernova test]`

async function started(what: string, words: string, kind = 'skill'): Promise<string> {
  const r = await call('POST', '/v1/sessions', { projectPath: proj, prompt: `/hn-plain ${words}`, permissionMode: 'default' })
  assert.equal(r.status, 200, `${what}: ${JSON.stringify(r.json)}`)
  const id = r.json.session.id as string
  await until(`${what}: its reply`, () => replies(id) >= 1)
  await ranWithWords(what, id, words, true, kind)
  return id
}
/** Typed into [id]'s box, as a chat's Send does. */
async function typed(what: string, id: string, said: string, job = false): Promise<void> {
  // Its own words, so what an earlier prompt in this session left at the provider can't pass for them.
  const words = `${said} (${what})`
  const before = replies(id)
  // A job's terminal opens as it's written to; any other is typed into only once it's known here, or it'd be started again with the words as arguments.
  if (!job) await until(`${what}: its terminal`, () => {
    scanTerms()
    return termOf(id) != null
  })
  await until(`${what}: the box`, async () => {
    const r = await call('POST', `/v1/sessions/${id}/prompt`, { text: `/hn-plain ${words}` })
    if (r.status !== 200) assert.ok(['starting', 'waiting', 'not_seen', 'terminal_busy'].includes(r.json?.error?.code), `${what}: ${JSON.stringify(r.json)}`)
    return r.status === 200
  })
  await until(`${what}: its reply`, () => replies(id) > before)
  await ranWithWords(what, id, words, false)
}

test('a skill’s name goes in by itself only when Claude Code would fold the paste', () => {
  assert.deepEqual(pasteParts('/effort high'), ['/effort high'])
  assert.deepEqual(pasteParts(`/hn-plain ${SEVERAL}`), [`/hn-plain ${SEVERAL}`])
  assert.deepEqual(pasteParts(`/hn-plain ${LINES}`), ['/hn-plain ', LINES])
  assert.deepEqual(pasteParts(`/hn-plain ${LONG}`), ['/hn-plain ', LONG])
  assert.deepEqual(pasteParts('/plugin:skill-name.x one\nnext line'), ['/plugin:skill-name.x ', 'one\nnext line'])
  assert.deepEqual(pasteParts('/hn-plain\nnext line'), ['/hn-plain\nnext line'], 'two lines aren’t folded')
  assert.deepEqual(pasteParts('/hn-plain'), ['/hn-plain'], 'a name alone')
  // Not commands, as Claude Code reads them: a prompt stays whole.
  assert.deepEqual(pasteParts(`plain words\n${LINES}`), [`plain words\n${LINES}`])
  assert.deepEqual(pasteParts(`/Users/me/shot.png what's this\n${LINES}`), [`/Users/me/shot.png what's this\n${LINES}`])
})

test('real Claude Code: a skill’s words reach the provider, started any way or typed in a chat', { skip: realClaude ? false : 'Claude Code isn’t installed here', timeout: 420_000 }, async () => {
  hooks.installIntegration()
  closeServers = startServers()
  timers.push(setInterval(hub.pollRegistry, 500), setInterval(hub.pollTranscripts, 300), setInterval(pollClaudeAgents, 1000))

  // ── A shared terminal ──
  settings.claudeAgents = false
  await started('shared terminal, sentences', SEVERAL)
  const chat = await started('shared terminal, lines', LINES)
  // ── Its own box: the same words, folded by Claude Code unless the name goes first ──
  await typed('chat, sentences', chat, SEVERAL)
  await typed('chat, lines', chat, LINES)
  await typed('chat, long', chat, LONG)

  // ── An automation's run: its flags in front of the same words ──
  const auto = createSharedSession({ cwd: proj, prompt: `/hn-plain ${LINES}`, title: 'Skill run', permissionMode: 'default', automation: { id: 'a1', name: 'Skill run', runId: 'r1' }, allowedTools: ['Bash(git log:*)', 'Read'], appendSystemPrompt: 'Started by an automation.' })
  await until('the run’s reply', () => replies(auto) >= 1)
  await ranWithWords('automation', auto, LINES, false)

  // ── A `claude agents` job, and its box through `claude attach` ──
  settings.claudeAgents = true
  await started('job, sentences', SEVERAL)
  const job = await started('job, lines', LINES)
  await typed('job’s chat, lines', job, LINES, true)
  settings.claudeAgents = false

  // ── The SDK: no shared terminals here ──
  hooks.uninstallIntegration()
  // (It tells the phone a command: the meta entry that says "skill" is in no stream it reads.)
  await started('SDK, lines', LINES, 'prompt')
})
