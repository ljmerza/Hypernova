// Carrying on after signing in again (resume.ts; docs/PROTOCOL.md, "Auto-continue", "After signing
// in again"): a session the login stopped continues once when the account is back, with the setting
// on, and nothing else does. Claude Code here is a stand-in for `claude auth status`.
import { test, after, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { appendFileSync, chmodSync, copyFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const scratch = mkdtempSync(join(tmpdir(), 'hn-login-continue-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
for (const d of [process.env.HOME, process.env.ZDOTDIR, process.env.CLAUDE_CONFIG_DIR]) mkdirSync(d, { recursive: true })
const fakeState = join(scratch, 'fake')
mkdirSync(fakeState)
writeFileSync(join(fakeState, 'signed-in'), '')
process.env.FAKE_CLAUDE_STATE = fakeState
const fake = join(scratch, 'claude-bin')
copyFileSync(join(dirname(fileURLToPath(import.meta.url)), 'fixtures', 'fake-claude.sh'), fake)
chmodSync(fake, 0o755)
process.env.CLAUDE_BIN = fake

const { autoContinueOf, classifyApiError, CONTINUE_TEXT } = await import('../src/blockers.ts')
const { userEvent } = await import('../src/normalize.ts')
const hub = await import('../src/hub.ts')
const store = await import('../src/store.ts')
const account = await import('../src/account.ts')
const resume = await import('../src/resume.ts')

after(() => rmSync(scratch, { recursive: true, force: true }))

let now = Date.now()
resume.useClock(() => now, () => 0.5)
const sent: Array<{ id: string; text: string }> = []
let answer: 'ok' | 'retry' | 'typing' | 'no' = 'ok'
resume.useDeliver(async (s, text) => {
  if (answer === 'ok') sent.push({ id: s.id, text })
  return answer
})

const provider = store.saveProvider({ name: 'OpenRouter', kind: 'openrouter', apiKey: 'test-only-not-a-key', models: {} })
let n = 0
/** A fresh SDK session on the subscription (or [providerId]), idle. */
function session(providerId = 'anthropic'): string {
  const id = `lc-${++n}`
  store.saveManaged({ id, cwd: scratch, providerId, model: null, permissionMode: 'default', createdAt: now, engine: 'sdk' })
  hub.upsertSession(id, { cwd: scratch, origin: 'hypernova', providerId, state: 'idle' })
  return id
}
const lostLogin = (at: number) => classifyApiError({ error: 'authentication_failed', status: 401, text: 'Please run /login · API Error: 401 OAuth token has expired.', builtIn: true, at })
/** Claude Code told session [id] the login is gone. */
const stop = (id: string, at = Date.now()) => hub.blockSession(id, lostLogin(at), at)
/** A check after Claude Code's "Login successful". */
const signIn = () => account.applyCheck({ loggedIn: true, authMethod: 'claude.ai', apiProvider: 'firstParty', subscriptionType: 'max' }, true)
const blockedOf = (id: string) => hub.listSessions().find((s) => s.id === id)!.blocked
const moment = () => new Promise((r) => setTimeout(r, 3))
/** Far enough on for everything armed to be due, and look. */
const later = async (ms = 5 * 60_000) => {
  now += ms
  await resume.tick()
  await resume.tick()
}

beforeEach(async () => {
  signIn()
  await moment()
  now = Date.now()
  sent.length = 0
  answer = 'ok'
  delete store.settings.loginContinue
  resume.resetResume()
})

test('stopped by the login, then signed in: it continues once, as a notice row', async () => {
  const id = session()
  stop(id)
  assert.equal(account.getAccount().state, 'logged_out')
  assert.equal(blockedOf(id)?.kind, 'logged_out')
  await later()
  assert.deepEqual(sent, [], 'nothing while signed out')
  signIn()
  assert.equal(blockedOf(id), null, 'no card while it waits its moment')
  assert.deepEqual(sent, [])
  await later()
  assert.deepEqual(sent, [{ id, text: CONTINUE_TEXT.login }])
  await later()
  assert.equal(sent.length, 1, 'once')
  // What it typed reads as a slim row, never as the owner's words.
  assert.deepEqual(userEvent('e1', 1, CONTINUE_TEXT.login), { id: 'e1', kind: 'notice', ts: 1, text: 'Continued by itself after signing in again', tone: 'info' })
  assert.equal(autoContinueOf(CONTINUE_TEXT.loginManual), 'login_manual')
  assert.deepEqual(userEvent('e2', 2, CONTINUE_TEXT.loginManual), { id: 'e2', kind: 'notice', ts: 2, text: 'Continued after signing in again', tone: 'info' })
})

test('an automation the login stopped is not carried on: its run was already recorded as failed', async () => {
  const id = session()
  store.saveManaged({ ...store.getManaged(id)!, automation: { id: 'a1', name: 'Nightly', runId: 'r1' } })
  stop(id)
  await later()
  signIn()
  await later()
  await later()
  assert.deepEqual(sent, [], 'nothing typed')
  assert.equal(blockedOf(id), null, 'and no card offering to')
})

test('what it types asks the session to take up the sub agents that failed', () => {
  assert.match(CONTINUE_TEXT.login, /SendMessage/)
  assert.match(CONTINUE_TEXT.login, /run them again/)
  assert.match(CONTINUE_TEXT.login, /do not repeat work that is already complete/i)
})

test('with the setting off it does not continue, and says to Continue', async () => {
  store.settings.loginContinue = false
  const id = session()
  stop(id)
  signIn()
  await later()
  assert.deepEqual(sent, [])
  const b = blockedOf(id)!
  assert.equal(b.kind, 'signed_in')
  assert.equal(b.resume?.state, 'ready')
  assert.equal(resume.continueText(id), CONTINUE_TEXT.loginManual)
  // Any other session's Continue stays the limit's.
  assert.equal(resume.continueText(session()), CONTINUE_TEXT.manual)
})

test('turned off while one waits its moment, it stays', async () => {
  const id = session()
  stop(id)
  signIn()
  store.settings.loginContinue = false
  await later()
  assert.deepEqual(sent, [])
  assert.equal(blockedOf(id)?.resume?.state, 'ready')
})

test('one the owner stopped, or never stopped at all, is not touched', async () => {
  const idle = session()
  const stopped = session()
  stop(session())
  signIn()
  await later()
  assert.equal(sent.some((s) => s.id === idle || s.id === stopped), false)
  assert.equal(sent.length, 1)
})

test('one a limit stopped keeps its own schedule and its own words', async () => {
  const reset = Date.now() + 3600_000
  const limited = session()
  hub.blockSession(limited, classifyApiError({ error: 'rate_limit', apiError: 'usage_limit_reached', status: 429, text: "You've hit your session limit · resets 2am", rateLimitInfo: { status: 'rejected', rateLimitType: 'five_hour', resetsAt: reset / 1000 }, builtIn: true, at: Date.now() }), Date.now())
  const before = blockedOf(limited)!.resume
  assert.equal(before?.state, 'armed')
  await moment()
  stop(session())
  signIn()
  await later()
  assert.equal(sent.some((s) => s.id === limited), false)
  assert.deepEqual(blockedOf(limited)?.resume, before)
  // The account's limit doesn't outlast this test.
  account.tickAccount(reset + 1000)
  assert.equal(account.getAccount().state, 'ok')
})

test('one that ended is left, and no new terminal is started for it', async () => {
  const id = session()
  stop(id)
  hub.upsertSession(id, { state: 'ended' })
  signIn()
  await later()
  assert.deepEqual(sent, [])
  assert.equal(resume.resumeFor(id), null)
})

test('one waiting on an approval is not typed into', async () => {
  const id = session()
  stop(id)
  hub.upsertSession(id, { state: 'needs_you' })
  signIn()
  await later()
  await later(60_000)
  assert.deepEqual(sent, [])
})

test('an open menu holds it back too, and once it closes it goes on', async () => {
  const id = session()
  stop(id)
  hub.upsertSession(id, { menu: { id: 'm', title: 'Pick', options: [], cursor: 0 } as any })
  signIn()
  await later()
  assert.deepEqual(sent, [])
  hub.upsertSession(id, { menu: null })
  await later(30_000)
  assert.deepEqual(sent, [{ id, text: CONTINUE_TEXT.login }])
})

let tn = 0
/** A terminal session on the subscription whose transcript holds [lines]. */
function terminalSession(lines: object[]): { id: string; file: string } {
  const id = `bbbbbbbb-0000-4000-8000-${String(++tn).padStart(12, '0')}`
  const cwd = join(scratch, `term-${tn}`)
  const file = hub.transcriptPathFor(cwd, id)
  mkdirSync(dirname(file), { recursive: true })
  writeFileSync(file, lines.map((l) => JSON.stringify(l) + '\n').join(''))
  const r = hub.upsertSession(id, { cwd, transcriptPath: file, origin: 'terminal', providerId: 'anthropic', state: 'idle', term: { id: `t-${tn}`, mac: 1, phone: 0 } })
  hub.ensureParsed(r)
  return { id, file }
}
const promptLine = (uuid: string, at: number, text: string) => ({ type: 'user', uuid, parentUuid: null, timestamp: new Date(at).toISOString(), isSidechain: false, message: { role: 'user', content: text } })

test('someone typed in it since it stopped: it is left', async () => {
  const t0 = Date.now()
  const { id } = terminalSession([promptLine('p1', t0 - 60_000, 'go on with the parser'), promptLine('p2', t0 + 1000, 'actually, leave that for now')])
  stop(id, t0)
  signIn()
  await later()
  assert.deepEqual(sent, [])
  assert.equal(resume.resumeFor(id), null)
})

test('/login in its terminal signs in, and that is no typing: it carries on', async () => {
  const t0 = Date.now()
  const { id, file } = terminalSession([promptLine('p1', t0 - 60_000, 'go on with the parser')])
  stop(id, t0)
  assert.equal(account.getAccount().state, 'logged_out')
  // As Claude Code writes it: the command, then what it printed.
  await moment()
  appendFileSync(file, JSON.stringify(promptLine('p2', t0 + 1, '<command-name>/login</command-name>\n<command-message>login</command-message>\n<command-args></command-args>')) + '\n')
  appendFileSync(file, JSON.stringify({ type: 'system', subtype: 'local_command', level: 'info', isMeta: false, uuid: 'p3', parentUuid: 'p2', timestamp: new Date(t0 + 2).toISOString(), content: '<local-command-stdout>Login successful</local-command-stdout>' }) + '\n')
  hub.pollTranscripts()
  assert.equal(account.getAccount().state, 'ok')
  await later()
  assert.deepEqual(sent, [{ id, text: CONTINUE_TEXT.login }])
  // The check "Login successful" asks for is done before the next test signs out.
  await account.requestCheck(true)
})

test('it started working by itself in the meantime: nothing is typed', async () => {
  const id = session()
  stop(id)
  signIn()
  now += 6000
  hub.upsertSession(id, { state: 'working' })
  await later()
  assert.deepEqual(sent, [])
})

test('a block from long before this sign-out began is left', async () => {
  const old = session()
  const t0 = Date.now()
  stop(session(), t0 + 3 * 3600_000)
  hub.setBlocked(old, { kind: 'logged_out', text: 'Please run /login', resetsAt: null, autoResumeAt: null }, t0 + 1000)
  assert.equal(blockedOf(old)?.kind, 'logged_out')
  signIn()
  await later()
  assert.equal(sent.some((s) => s.id === old), false)
  assert.equal(sent.length, 1)
})

test('back still under a limit: nothing until the limit is over, then once', async () => {
  const reset = Date.now() + 3600_000
  const limited = session()
  hub.blockSession(limited, classifyApiError({ error: 'rate_limit', apiError: 'usage_limit_reached', status: 429, text: "You've hit your session limit · resets 2am", rateLimitInfo: { status: 'rejected', rateLimitType: 'five_hour', resetsAt: reset / 1000 }, builtIn: true, at: Date.now() }), Date.now())
  await moment()
  const id = session()
  stop(id)
  assert.equal(account.getAccount().state, 'logged_out')
  signIn()
  assert.equal(account.getAccount().state, 'limited')
  await later(30 * 60_000)
  assert.equal(sent.some((s) => s.id === id), false, 'under the limit')
  assert.equal(blockedOf(id), null)
  // The limit passes.
  now = reset + 1000
  account.tickAccount(reset + 1000)
  assert.equal(account.getAccount().state, 'ok')
  await later()
  await later()
  assert.deepEqual(sent.filter((s) => s.id === id), [{ id, text: CONTINUE_TEXT.login }])
  await later()
  assert.equal(sent.filter((s) => s.id === id).length, 1)
})

test('a sign-in that failed continues nothing', async () => {
  const id = session()
  stop(id)
  // A check with no "Login successful": the token on disk proves nothing about one the server refused.
  account.applyCheck({ loggedIn: true, authMethod: 'claude.ai', apiProvider: 'firstParty', subscriptionType: 'max' }, false)
  assert.equal(account.getAccount().state, 'logged_out')
  account.applyCheck({ loggedIn: false, authMethod: 'none', apiProvider: 'firstParty', subscriptionType: null }, false)
  await later()
  assert.deepEqual(sent, [])
  assert.equal(blockedOf(id)?.kind, 'logged_out')
  // The plan turns out to have ended: still nothing.
  account.applyCheck({ loggedIn: true, authMethod: 'claude.ai', apiProvider: 'firstParty', subscriptionType: null }, true)
  assert.equal(account.getAccount().state, 'no_subscription')
  await later()
  assert.deepEqual(sent, [])
})

test('signed out again before its moment: it waits, and the next sign-in continues it once', async () => {
  const id = session()
  stop(id)
  signIn()
  await moment()
  stop(session())
  assert.equal(account.getAccount().state, 'logged_out')
  await later()
  assert.equal(sent.some((s) => s.id === id), false)
  signIn()
  await moment()
  stop(session())
  signIn()
  await later()
  assert.equal(sent.filter((s) => s.id === id).length, 1)
})

test('a login flipping back and forth: stopped again soon after carrying on, it waits for you', async () => {
  const id = session()
  stop(id)
  signIn()
  await later()
  assert.equal(sent.length, 1)
  for (let round = 0; round < 3; round++) {
    await moment()
    // The continuation hit the login again, a moment after it was sent.
    hub.upsertSession(id, { state: 'idle' })
    stop(id, now + 1000)
    signIn()
    await later(60_000)
    assert.equal(sent.length, 1, `round ${round}`)
    assert.equal(blockedOf(id)?.kind, 'signed_in')
    assert.equal(blockedOf(id)?.resume?.state, 'ready')
  }
})

test('a session stopped by the login much later than its last carrying on continues again', async () => {
  const id = session()
  stop(id)
  signIn()
  await later()
  assert.equal(sent.length, 1)
  await later(16 * 60_000)
  stop(id, now)
  signIn()
  await later()
  assert.equal(sent.length, 2)
})

test('several sessions: each once, spaced', async () => {
  const ids = [session(), session(), session(), session()]
  const t0 = Date.now()
  ids.forEach((id, i) => stop(id, t0 + i))
  signIn()
  const times = ids.map((id) => resume.resumeFor(id)!.at!)
  for (let i = 1; i < times.length; i++) assert.ok(times[i]! - times[i - 1]! >= 2000, `spaced: ${times}`)
  assert.ok(times[0]! > now, 'not at the same instant as the sign-in')
  await later()
  assert.deepEqual(sent.map((s) => s.id).sort(), [...ids].sort())
})

test('it fires one at a time as each comes due', async () => {
  const a = session()
  const b = session()
  const t0 = Date.now()
  stop(a, t0)
  stop(b, t0 + 1)
  signIn()
  now = resume.resumeFor(a)!.at!
  await resume.tick()
  assert.deepEqual(sent.map((s) => s.id), [a])
  now = resume.resumeFor(b)!.at!
  await resume.tick()
  assert.deepEqual(sent.map((s) => s.id), [a, b])
})

test('a session on another provider was not stopped by this', async () => {
  const id = session(provider.id)
  hub.blockSession(id, classifyApiError({ error: 'authentication_failed', status: 401, text: 'Please run /login · API Error: 401', builtIn: false, at: Date.now() }), Date.now())
  assert.equal(blockedOf(id)?.kind, 'provider')
  stop(session())
  signIn()
  await later()
  assert.equal(sent.some((s) => s.id === id), false)
  account.clearProviderStatus(provider.id)
})

test('the owner’s unsent text is never typed over: it waits for Continue', async () => {
  const id = session()
  stop(id)
  signIn()
  answer = 'typing'
  await later()
  const b = blockedOf(id)!
  assert.deepEqual([b.kind, b.resume?.state, b.resume?.why], ['signed_in', 'ready', 'dropped'])
})

test('a terminal the daemon does not hold: it says to Continue', async () => {
  const id = session()
  stop(id)
  signIn()
  answer = 'no'
  await later()
  assert.deepEqual([blockedOf(id)?.kind, blockedOf(id)?.resume?.state], ['signed_in', 'ready'])
})

test('a reply in the session before its moment ends it', async () => {
  const id = session()
  stop(id)
  await moment()
  hub.sessionHealthy(id, Date.now(), 'claude-opus-5-5')
  assert.equal(account.getAccount().state, 'ok', 'a reply proves the login works')
  await later()
  assert.deepEqual(sent, [])
})

test('a reply in one session brings the others on: the account works again', async () => {
  const a = session()
  const b = session()
  const t0 = Date.now()
  stop(a, t0)
  stop(b, t0 + 1)
  await moment()
  hub.sessionHealthy(a, Date.now(), 'claude-opus-5-5')
  await later()
  assert.deepEqual(sent, [{ id: b, text: CONTINUE_TEXT.login }])
})

test('a `claude agents` job waiting on the login stops saying so once signed in', async () => {
  const id = 'cccccccc-0000-4000-8000-000000000001'
  const job = 'cccccccc'
  const dir = join(process.env.CLAUDE_CONFIG_DIR!, 'jobs', job)
  mkdirSync(dir, { recursive: true })
  // As Claude Code 2.1.317 wrote it for a job that hit a 401 (measured with a fake API).
  writeFileSync(join(dir, 'state.json'), JSON.stringify({ state: 'blocked', needs: 'login required — run /login · API Error: 401 OAuth token has expired. Please obtain a new token or refresh your existing token.' }))
  const list = [{ id: job, sessionId: id, cwd: scratch, state: 'blocked', startedAt: Date.now(), pid: null }]
  store.settings.claudeAgents = true
  try {
    stop(session())
    hub.applyAgentList(list)
    assert.equal(hub.getSession(id)?.state, 'needs_you')
    signIn()
    hub.applyAgentList(list)
    assert.equal(hub.getSession(id)?.state, 'idle')
    assert.equal(hub.getSession(id)?.question ?? null, null)
  } finally {
    store.settings.claudeAgents = false
  }
})

test('a `claude agents` job the owner stopped while signed out stays stopped', async () => {
  const id = 'cccccccc-0000-4000-8000-000000000002'
  const job = 'cccccccd'
  hub.upsertSession(id, { cwd: scratch, origin: 'terminal', providerId: 'anthropic', state: 'idle' })
  store.settings.claudeAgents = true
  try {
    stop(id)
    assert.equal(blockedOf(id)?.kind, 'logged_out')
    // As `claude agents --json --all` lists it after `claude stop` (Claude Code 2.1.317): no pid.
    hub.applyAgentList([{ id: job, sessionId: id, cwd: scratch, state: 'stopped', startedAt: Date.now() }])
    signIn()
    await later()
    assert.deepEqual(sent, [])
    assert.equal(resume.resumeFor(id), null)
  } finally {
    store.settings.claudeAgents = false
  }
})
