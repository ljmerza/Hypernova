// Carrying on after a limit (resume.ts; docs/PROTOCOL.md, "Auto-continue"): another provider's rate
// limit arms at its announced reset and fires; Don't and Continue automatically; hitting it again
// re-arms twice at most; out of credit never carries on by itself, and says when it's back; the
// subscription's limit on an SDK session; too late to fire; and Claude Code's terminal driven the
// way a person would (Esc, /rate-limit-options), on a stand-in screen.
import { test, after, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { appendFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-resume-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
mkdirSync(process.env.HOME, { recursive: true })
mkdirSync(process.env.CLAUDE_CONFIG_DIR, { recursive: true })

const { classifyApiError, CONTINUE_TEXT, parseWhen } = await import('../src/blockers.ts')
const hub = await import('../src/hub.ts')
const store = await import('../src/store.ts')
const account = await import('../src/account.ts')
const resume = await import('../src/resume.ts')
const { boxLines, boxText } = await import('../src/term/shared.ts')

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
/** A fresh SDK session on [providerId], idle. */
function session(providerId = provider.id): string {
  const id = `rs-${++n}`
  store.saveManaged({ id, cwd: scratch, providerId, model: null, permissionMode: 'default', createdAt: now, engine: 'sdk' })
  hub.upsertSession(id, { cwd: scratch, origin: 'hypernova', providerId, state: 'idle' })
  return id
}
const providerError = (text: string, status: number, at = now) => classifyApiError({ error: status === 429 ? 'rate_limit' : 'unknown', status, text, builtIn: false, at })
const blockedOf = (id: string) => hub.listSessions().find((s) => s.id === id)!.blocked

beforeEach(() => {
  now = Date.now()
  sent.length = 0
  answer = 'ok'
  resume.resetResume()
  account.clearProviderStatus(provider.id)
})

test('a rate limit with its reset carries on by itself a moment after it, and says so', async () => {
  const id = session()
  const reset = now + 10 * 60_000
  hub.blockSession(id, providerError(`API Error: 429 {"error":{"code":429,"message":"Rate limit exceeded: free-models-per-min.","metadata":{"headers":{"X-RateLimit-Limit":"20","X-RateLimit-Remaining":"0","X-RateLimit-Reset":"${reset}"}}}}`, 429), now)
  assert.equal(account.providerStatus(provider.id)?.state, 'limited')
  assert.equal(account.providerStatus(provider.id)?.resetsAt, reset)
  const b = blockedOf(id)!
  assert.equal(b.kind, 'provider')
  assert.deepEqual(b.resume, { state: 'armed', at: reset + 12_500, by: 'hypernova', canCancel: true, canArm: false, why: null })
  assert.equal(b.autoResumeAt, reset + 12_500)
  // The reset passes: the provider is fine again, and the session still waits for its moment.
  now = reset + 1000
  account.tickAccount(now)
  assert.equal(account.providerStatus(provider.id), null)
  assert.equal(blockedOf(id)?.resume?.state, 'armed')
  await resume.tick()
  assert.equal(sent.length, 0)
  now = reset + 12_500
  await resume.tick()
  assert.deepEqual(sent, [{ id, text: CONTINUE_TEXT.provider }])
  assert.equal(blockedOf(id), null)
})

test("Don't, then Continue automatically, on the daemon's own schedule", async () => {
  const id = session()
  const reset = now + 5 * 60_000
  hub.blockSession(id, providerError(`API Error: 429 Too many requests. Please try again in 5 minutes.`, 429), now)
  assert.equal(blockedOf(id)?.resume?.state, 'armed')
  await resume.setAutoContinue(id, false)
  assert.deepEqual(blockedOf(id)?.resume, { state: 'off', at: reset, by: 'hypernova', canCancel: false, canArm: true, why: 'cancelled' })
  assert.equal(blockedOf(id)?.autoResumeAt, null)
  // Off: the reset passes and it waits for the owner.
  now = reset + 60_000
  account.tickAccount(now)
  assert.equal(blockedOf(id)?.resume?.state, 'ready')
  assert.equal(blockedOf(id)?.resume?.why, 'back')
  await resume.tick()
  assert.equal(sent.length, 0)
  await assert.rejects(resume.setAutoContinue(id, true), (e: any) => e.code === 'not_limited')
  // Another one, turned off and back on.
  const id2 = session()
  hub.blockSession(id2, providerError(`API Error: 429 Too many requests. Please try again in 5 minutes.`, 429), now)
  await resume.setAutoContinue(id2, false)
  await resume.setAutoContinue(id2, true)
  assert.equal(blockedOf(id2)?.resume?.state, 'armed')
})

test('hit again right after carrying on: twice more at most, then it waits for you', async () => {
  const id = session()
  for (let round = 0; round < 4; round++) {
    const reset = now + 60_000
    hub.blockSession(id, providerError(`API Error: 429 {"error":{"metadata":{"headers":{"X-RateLimit-Reset":"${reset}"}}}}`, 429), now)
    const r = blockedOf(id)!.resume!
    if (round < 3) {
      assert.equal(r.state, 'armed', `round ${round}`)
      now = r.at! + 10
      account.tickAccount(now)
      await resume.tick()
      assert.equal(sent.length, round + 1)
      now += 5000
    } else {
      assert.equal(r.state, 'off')
      assert.equal(r.why, 'repeated')
      assert.equal(r.canArm, true)
    }
  }
})

test('out of credit never carries on by itself; when the credit is back the session offers Continue', async () => {
  const id = session()
  hub.blockSession(id, providerError('API Error: 402 {"error":{"code":402,"message":"Insufficient credits. Add more using https://openrouter.ai/settings/credits","metadata":{"limit_source":"openrouter_credits"}}}', 402), now)
  assert.equal(account.providerStatus(provider.id)?.state, 'no_credit')
  assert.equal(blockedOf(id)?.resume, null)
  await assert.rejects(resume.setAutoContinue(id, true), (e: any) => e.code === 'not_limited')
  // Topped up (the balance said so, or the key was edited).
  now += 60_000
  account.clearProviderStatus(provider.id)
  const b = blockedOf(id)!
  assert.equal(b.kind, 'provider')
  assert.equal(b.resume?.state, 'ready')
  assert.equal(b.resume?.why, 'back')
  await resume.tick()
  assert.equal(sent.length, 0)
  // The owner's Continue starts a turn: the card goes.
  hub.upsertSession(id, { state: 'working' })
  await resume.tick()
  assert.equal(blockedOf(id), null)
})

test('a rate limit that names no reset waits for the owner (Try again), and so does a provider not answering', () => {
  const id = session()
  hub.blockSession(id, providerError('API Error: 429 Too Many Requests', 429), now)
  assert.deepEqual(blockedOf(id)?.resume, { state: 'off', at: null, by: 'hypernova', canCancel: false, canArm: false, why: 'no_reset' })
  account.clearProviderStatus(provider.id)
  const id2 = session()
  hub.blockSession(id2, providerError('API Error: 529 {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}', 529), now)
  assert.equal(account.providerStatus(provider.id)?.state, 'down')
  assert.equal(blockedOf(id2)?.resume?.why, 'no_reset')
})

test('more than half an hour late (the Mac slept), it does not fire: it waits for you', async () => {
  const id = session()
  hub.blockSession(id, providerError('API Error: 429 Please try again in 2 minutes.', 429), now)
  const at = blockedOf(id)!.resume!.at!
  now = at + 31 * 60_000
  account.tickAccount(now)
  await resume.tick()
  assert.equal(sent.length, 0)
  assert.deepEqual([blockedOf(id)?.resume?.state, blockedOf(id)?.resume?.why], ['ready', 'slept'])
})

test("the owner's unsent text in the terminal is never typed over", async () => {
  const id = session()
  hub.blockSession(id, providerError('API Error: 429 Please try again in 1 minute.', 429), now)
  now = blockedOf(id)!.resume!.at! + 1
  account.tickAccount(now)
  answer = 'typing'
  await resume.tick()
  assert.deepEqual([blockedOf(id)?.resume?.state, blockedOf(id)?.resume?.why], ['ready', 'dropped'])
})

test('the subscription on an SDK session: armed at the reset, unless the setting is off or it is more than a day away', async () => {
  const id = session('anthropic')
  const reset = now + 3600_000
  hub.blockSession(id, classifyApiError({ error: 'rate_limit', apiError: 'usage_limit_reached', status: 429, text: "You've hit your session limit · resets 2am", rateLimitInfo: { status: 'rejected', rateLimitType: 'five_hour', resetsAt: reset / 1000 }, builtIn: true, at: now }), now)
  assert.deepEqual(blockedOf(id)?.resume, { state: 'armed', at: reset + 60_000, by: 'hypernova', canCancel: true, canArm: false, why: null })
  writeFileSync(join(process.env.CLAUDE_CONFIG_DIR!, 'settings.json'), JSON.stringify({ autoContinueAtUsageLimit: false }))
  const id2 = session('anthropic')
  hub.blockSession(id2, classifyApiError({ error: 'rate_limit', apiError: 'usage_limit_reached', status: 429, text: "You've hit your session limit · resets 2am", rateLimitInfo: { status: 'rejected', rateLimitType: 'five_hour', resetsAt: reset / 1000 }, builtIn: true, at: now }), now)
  assert.equal(blockedOf(id2)?.resume?.why, 'setting')
  assert.equal(blockedOf(id2)?.resume?.canArm, true)
  rmSync(join(process.env.CLAUDE_CONFIG_DIR!, 'settings.json'))
  const id3 = session('anthropic')
  const far = now + 3 * 86400_000
  hub.blockSession(id3, classifyApiError({ error: 'rate_limit', apiError: 'usage_limit_reached', status: 429, text: "You've hit your weekly limit · resets Sun 2pm", rateLimitInfo: { status: 'rejected', rateLimitType: 'seven_day', resetsAt: far / 1000 }, builtIn: true, at: now }), now)
  assert.deepEqual([blockedOf(id3)?.resume?.state, blockedOf(id3)?.resume?.why, blockedOf(id3)?.resume?.canArm], ['off', 'far', false])
  // Kept for a restart.
  assert.ok(existsSync(join(process.env.HYPERNOVA_SUPPORT!, 'resume.json')))
  assert.equal(JSON.parse(readFileSync(join(process.env.HYPERNOVA_SUPPORT!, 'resume.json'), 'utf8')).length, 3)
})

test('a reply ends it', () => {
  const id = session()
  hub.blockSession(id, providerError('API Error: 429 Please try again in 1 minute.', 429), now)
  assert.equal(blockedOf(id)?.resume?.state, 'armed')
  hub.sessionHealthy(id, now + 1000, 'fake-model')
  assert.equal(blockedOf(id), null)
  assert.equal(resume.resumeFor(id), null)
})

test('Continue now while armed: the turn starting ends the wait, so the schedule never sends a second continue', async () => {
  const id = session()
  const reset = now + 10 * 60_000
  hub.blockSession(id, providerError(`API Error: 429 {"error":{"code":429,"message":"Rate limit exceeded: free-models-per-min.","metadata":{"headers":{"X-RateLimit-Limit":"20","X-RateLimit-Remaining":"0","X-RateLimit-Reset":"${reset}"}}}}`, 429), now)
  assert.equal(blockedOf(id)?.resume?.state, 'armed')
  // POST /continue {reason: "limit"} sends the prompt as an ordinary turn (nothing is interrupted):
  // the session works, and with it the wait is over.
  hub.upsertSession(id, { state: 'working' })
  now += 6000
  await resume.tick()
  assert.equal(resume.resumeFor(id), null, 'no resume is left to fire')
  now = reset + 60_000
  account.tickAccount(now)
  await resume.tick()
  assert.deepEqual(sent, [], 'the schedule sent nothing of its own')
})

// ── Claude Code's terminal, on a stand-in screen ─────────────────────────
const RULE = '─'.repeat(70)
const idle = (above: string[], box = '') => [...above, '', RULE, `❯ ${box}`, RULE, '  ? for shortcuts']
const limitMenu = (cursor: 0 | 1, second = 'Wait here, then continue automatically at 2am') => [
  'You\'ve hit your session limit · resets 2am',
  RULE,
  ' What do you want to do?',
  '',
  ` ${cursor === 0 ? '❯' : ' '} 1. Stop and wait for limit to reset`,
  ` ${cursor === 1 ? '❯' : ' '} 2. ${second}`,
  '',
  ' Enter to confirm · Esc to cancel',
]

function fakeTerm(screens: string[][]) {
  let i = 0
  const keys: string[] = []
  const typed: string[] = []
  const picks: number[] = []
  const io: import('../src/resume.ts').TermIO = {
    screen: async () => screens[Math.min(i, screens.length - 1)]!.join('\n'),
    keys: (d) => {
      keys.push(d)
      i++
    },
    type: async (t) => {
      typed.push(t)
      i++
      return { ok: true }
    },
    pick: async (_id, choice) => {
      picks.push(choice)
      i++
      return true
    },
    sleep: async () => {},
  }
  return { io, keys, typed, picks }
}

test("Don't: Esc once while “esc to cancel” shows", async () => {
  const t = fakeTerm([
    idle(['Usage limit reached · continuing automatically at 2am · esc to cancel']),
    idle(['Automatic continue cancelled · /rate-limit-options to re-arm']),
  ])
  const r = await resume.drive(t.io, false, () => false)
  assert.equal(r.ok, true)
  assert.deepEqual(t.keys, ['\x1b'])
  assert.deepEqual(t.typed, [])
})

test('Continue automatically: /rate-limit-options, then “Wait here, then continue automatically”', async () => {
  const t = fakeTerm([
    idle(['Automatic continue cancelled · /rate-limit-options to re-arm'], 'continue'),
    idle(['Automatic continue cancelled · /rate-limit-options to re-arm']),
    limitMenu(0),
    idle(['Claude Code will continue automatically at 2am. Keep this session open; it may still pause for permission prompts. Press esc to cancel the wait.']),
  ])
  const r = await resume.drive(t.io, true, () => false)
  assert.equal(r.ok, true)
  // Claude Code's "continue" prefill cleared first, never with Ctrl+C (that would cancel).
  assert.deepEqual(t.keys, ['\x7f'.repeat('continue'.length)])
  assert.deepEqual(t.typed, ['/rate-limit-options'])
  assert.deepEqual(t.picks, [1])
  assert.equal(r.ok && r.when, '2am')
})

test('the menu Claude Code opened itself is answered in place', async () => {
  const t = fakeTerm([limitMenu(0), idle(['Claude Code will continue automatically at 2am.'])])
  const r = await resume.drive(t.io, true, () => false)
  assert.equal(r.ok, true)
  assert.deepEqual(t.typed, [])
  assert.deepEqual(t.picks, [1])
})

test('never over the owner’s text, another dialog, or a menu without the option', async () => {
  let t = fakeTerm([idle(['Usage limit reached · continuing automatically at 2am · esc to cancel'], 'fix the parser please')])
  assert.deepEqual(await resume.drive(t.io, false, () => false), { ok: false, code: 'typing', pending: 'fix the parser please' })
  assert.deepEqual(t.keys, [])
  const other = [RULE, ' Switch model?', '', ' ❯ 1. Opus', '   2. Sonnet', '', ' Enter to confirm · Esc to cancel']
  t = fakeTerm([other])
  assert.deepEqual(await resume.drive(t.io, true, () => false), { ok: false, code: 'waiting' })
  t = fakeTerm([idle([]), limitMenu(0, 'Upgrade your plan')])
  const r = await resume.drive(t.io, true, () => false)
  assert.deepEqual(r, { ok: false, code: 'not_available' })
  assert.deepEqual(t.keys, ['\x1b'])
})

// ── Claude Code's terminal, on screens as it draws them ─────────────────
// test/fixtures/limit-screens: a limit in a shared terminal, drawn as Claude Code 2.1.300 draws
// it, with the wording from its binary. Its notices are part of the conversation, so they stay
// on screen after what follows them: an "esc to cancel" from before a cancel is still there.
const SCREENS = join(import.meta.dirname, 'fixtures', 'limit-screens')
const screen = (name: string) => readFileSync(join(SCREENS, `${name}.txt`), 'utf8').replace(/\n$/, '').split('\n')

test('the screen says armed only when the newest limit line does', () => {
  assert.deepEqual(resume.armedOnScreen(screen('armed')), { armed: true, when: '2am (Europe/Moscow)', armedLines: 1 })
  assert.equal(resume.armedOnScreen(screen('cancelled')).armed, false)
  assert.deepEqual(resume.armedOnScreen(screen('rearmed')), { armed: true, when: '2am (Europe/Moscow)', armedLines: 2 })
  assert.equal(resume.armedOnScreen(screen('fired-working')).armed, false)
  assert.equal(resume.armedOnScreen(screen('stale-prefill')).armed, false)
})

test("Don't on the real screen: Esc once, confirmed by the cancelled line under the old armed one", async () => {
  const t = fakeTerm([screen('armed'), screen('cancelled')])
  assert.deepEqual(await resume.drive(t.io, false, () => false), { ok: true })
  assert.deepEqual(t.keys, ['\x1b'])
})

test('Continue automatically after a Don’t goes through the menu: the old armed line is not taken for armed', async () => {
  // The cancelled screen alone, and no menu ever opens: not reported as armed.
  let t = fakeTerm([screen('cancelled')])
  assert.deepEqual(await resume.drive(t.io, true, () => false), { ok: false, code: 'not_confirmed' })
  assert.deepEqual(t.typed, ['/rate-limit-options'])
  // The menu opens, the option is picked, and its answer is a new armed line.
  t = fakeTerm([screen('cancelled'), screen('menu-arm'), screen('rearmed')])
  assert.deepEqual(await resume.drive(t.io, true, () => false), { ok: true, when: '2am (Europe/Moscow)' })
  assert.deepEqual([t.keys, t.typed, t.picks], [[], ['/rate-limit-options'], [1]])
  // Picked, but nothing new shows (the pick didn't take): not confirmed, though an armed line is up there.
  t = fakeTerm([screen('cancelled'), screen('menu-arm'), screen('cancelled')])
  assert.deepEqual(await resume.drive(t.io, true, () => false), { ok: false, code: 'not_confirmed' })
})

test('the menu decides when the screen and the switch disagree: it offers Don’t only while armed', async () => {
  const t = fakeTerm([screen('armed'), screen('menu-armed'), screen('armed')])
  assert.deepEqual(await resume.drive(t.io, true, () => false), { ok: true, when: '2am (Europe/Moscow)' })
  // Closed again, nothing picked.
  assert.deepEqual([t.keys, t.picks], [['\x1b'], []])
})

test('no key while Claude works: a Don’t that lands just after Claude Code carried on stops nothing', async () => {
  let t = fakeTerm([screen('fired-working')])
  assert.deepEqual(await resume.drive(t.io, false, () => false), { ok: false, code: 'working' })
  assert.deepEqual([t.keys, t.typed], [[], []])
  // The turn started and the screen doesn't show it yet: Claude Code's registry says busy.
  t = fakeTerm([screen('armed')])
  assert.deepEqual(await resume.drive({ ...t.io, busy: () => true }, false, () => false), { ok: false, code: 'working' })
  assert.deepEqual(t.keys, [])
})

test('Continue on the “press enter to continue” card types over Claude Code’s “continue”, never over the owner’s text', async () => {
  const prefilled = screen('stale-prefill')
  const emptied = prefilled.map((l) => (l === '❯ continue' ? '❯ ' : l))
  let current = prefilled
  const typed: string[] = []
  const keys: string[] = []
  // As the holder answers: text in the box stops it, with that text.
  const io = {
    type: async (text: string) => {
      const box = boxLines(current)!
      const inBox = boxText(current, box)
      if (inBox) return { ok: false, reason: 'busy_input' as const, text: inBox }
      typed.push(text)
      return { ok: true }
    },
    keys: (d: string) => {
      keys.push(d)
      current = emptied
    },
    sleep: async () => {},
  }
  assert.deepEqual(await resume.typeContinue(io, CONTINUE_TEXT.manual), { ok: true })
  assert.deepEqual([keys, typed], [['\x7f'.repeat('continue'.length)], [CONTINUE_TEXT.manual]])
  // The owner's own words stay.
  current = prefilled.map((l) => (l === '❯ continue' ? '❯ continue with the lexer' : l))
  keys.length = 0
  typed.length = 0
  assert.deepEqual(await resume.typeContinue(io, CONTINUE_TEXT.manual), { ok: false, reason: 'busy_input', text: 'continue with the lexer' })
  assert.deepEqual([keys, typed], [[], []])
})

// ── Claude Code's notices, from its transcript ───────────────────────────
const info = (resetsAt: number) => ({ status: 'rejected', resetsAt, rateLimitType: 'five_hour', overageStatus: 'rejected', overageDisabledReason: 'org_level_disabled', isUsingOverage: false })
const limitLine = (uuid: string, at: number, resetsAt: number) => ({
  type: 'assistant', uuid, parentUuid: 'p', isSidechain: false, timestamp: new Date(at).toISOString(),
  error: 'rate_limit', apiError: 'usage_limit_reached', apiErrorStatus: 429, isApiErrorMessage: true, apiErrorParams: { rate_limit_info: info(resetsAt) },
  message: { id: `m-${uuid}`, model: '<synthetic>', role: 'assistant', type: 'message', stop_reason: 'stop_sequence', content: [{ type: 'text', text: "You've hit your session limit · resets 2am" }] },
})
const noticeLine = (uuid: string, at: number, content: string) => ({ type: 'system', subtype: 'informational', level: 'notice', isMeta: false, uuid, timestamp: new Date(at).toISOString(), content })
const promptLine = (uuid: string, at: number, text: string) => ({ type: 'user', uuid, parentUuid: null, timestamp: new Date(at).toISOString(), isSidechain: false, message: { role: 'user', content: text } })

let tn = 0
/** A Claude Code terminal session on the subscription, in a shared terminal, its transcript holding [lines]. */
function terminalSession(lines: object[]): { id: string; file: string } {
  const id = `aaaaaaaa-0000-4000-8000-${String(++tn).padStart(12, '0')}`
  const cwd = join(scratch, `term-${tn}`)
  const file = hub.transcriptPathFor(cwd, id)
  mkdirSync(dirname(file), { recursive: true })
  writeFileSync(file, lines.map((l) => JSON.stringify(l) + '\n').join(''))
  const r = hub.upsertSession(id, { cwd, transcriptPath: file, origin: 'terminal', providerId: 'anthropic', state: 'idle', term: { id: `t-${tn}`, mac: 1, phone: 0 } })
  hub.ensureParsed(r)
  return { id, file }
}

test('the limit and Claude Code’s armed notice in one read: armed at the time it names', () => {
  const at = now - 2000
  const reset = Math.ceil((now + 3600_000) / 1000)
  const armed = 'Usage limit reached · continuing automatically at 2am · esc to cancel'
  const { id } = terminalSession([promptLine('p1', at - 5000, 'now the parser tests'), limitLine('l1', at, reset), noticeLine('n1', at + 5, armed)])
  assert.deepEqual(blockedOf(id)?.resume, { state: 'armed', at: parseWhen('2am', at + 5), by: 'claude', canCancel: true, canArm: false, why: null })
  // Cancelled in the same read too: the newest notice wins.
  const two = terminalSession([promptLine('p2', at - 5000, 'go'), limitLine('l2', at, reset), noticeLine('n2', at + 5, armed), noticeLine('n3', at + 900, 'Automatic continue cancelled · /rate-limit-options to re-arm')])
  assert.deepEqual([blockedOf(two.id)?.resume?.state, blockedOf(two.id)?.resume?.why, blockedOf(two.id)?.resume?.canArm], ['off', 'cancelled', true])
})

test('the limit, then its notice in a later read: armed all the same', () => {
  const at = now - 2000
  const reset = Math.ceil((now + 3600_000) / 1000)
  const { id, file } = terminalSession([promptLine('p1', at - 5000, 'go'), limitLine('l1', at, reset)])
  appendFileSync(file, JSON.stringify(noticeLine('n1', at + 5, 'Usage limit reached · continuing automatically at 2am · esc to cancel')) + '\n')
  hub.pollTranscripts()
  assert.equal(blockedOf(id)?.resume?.state, 'armed')
})

test('Don’t while Claude works again presses nothing and says the limit is over', async () => {
  const at = now - 2000
  const { id } = terminalSession([promptLine('p1', at - 5000, 'go'), limitLine('l1', at, Math.ceil((now + 3600_000) / 1000)), noticeLine('n1', at + 5, 'Usage limit reached · continuing automatically at 2am · esc to cancel')])
  assert.equal(blockedOf(id)?.resume?.state, 'armed')
  hub.upsertSession(id, { state: 'working' })
  const t = fakeTerm([screen('armed')])
  await assert.rejects(resume.setAutoContinue(id, false, t.io), (e: any) => e.code === 'not_limited')
  assert.deepEqual(t.keys, [])
})
