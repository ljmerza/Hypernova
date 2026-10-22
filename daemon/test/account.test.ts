// What stops Claude from replying (docs/PROTOCOL.md, "Account"): Claude Code's API-error entries
// and limit notices classified (pinned to the owner's real lines from his transcripts, Claude Code
// 2.1.296 to 2.1.299), read from a transcript, made into account episodes, checked with
// `claude auth status`, and signing in again through a stand-in for Claude Code.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, copyFileSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

// The owner's lines name Moscow time ("resets 2am (Europe/Moscow)"); the notices use the Mac's own.
process.env.TZ = 'Europe/Moscow'
const scratch = mkdtempSync(join(tmpdir(), 'hn-acct-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
for (const d of [process.env.HOME, process.env.ZDOTDIR]) mkdirSync(d, { recursive: true })
// Claude Code here is a stand-in: it never signs anyone in or out. It starts signed in, on Max.
const fakeState = join(scratch, 'fake')
mkdirSync(fakeState)
writeFileSync(join(fakeState, 'signed-in'), '')
process.env.FAKE_CLAUDE_STATE = fakeState
const fake = join(scratch, 'claude-bin')
copyFileSync(join(dirname(fileURLToPath(import.meta.url)), 'fixtures', 'fake-claude.sh'), fake)
chmodSync(fake, 0o755)
process.env.CLAUDE_BIN = fake

const { classifyApiError, classifyNotice, cleanText, parseWhen } = await import('../src/blockers.ts')
const { parseTranscript } = await import('../src/normalize.ts')
const account = await import('../src/account.ts')
const hub = await import('../src/hub.ts')
const hubStore = await import('../src/store.ts')

after(async () => {
  account.cancelLogin()
  await new Promise((r) => setTimeout(r, 300))
  rmSync(scratch, { recursive: true, force: true })
})

const sent: any[] = []
hub.addClient({ send: (s) => sent.push(JSON.parse(s)) })
const until = async (f: () => boolean, ms = 15_000) => {
  const t0 = Date.now()
  while (!f()) {
    if (Date.now() - t0 > ms) throw new Error('timed out waiting')
    await new Promise((r) => setTimeout(r, 50))
  }
}

// ── The owner's lines ────────────────────────────────────────────────────
// Transcript entries as Claude Code wrote them on this Mac (only the error entries, quoted whole).
const info = (resetsAt: number) => ({ status: 'rejected', resetsAt, rateLimitType: 'five_hour', overageStatus: 'rejected', overageDisabledReason: 'org_level_disabled', isUsingOverage: false })
const limitEntry = (uuid: string, ts: string, resetsAt: number, text: string) => ({
  type: 'assistant', uuid, parentUuid: 'p', isSidechain: false, timestamp: ts, cwd: '/tmp/demo', sessionId: 'sess',
  error: 'rate_limit', apiError: 'usage_limit_reached', apiErrorStatus: 429, isApiErrorMessage: true,
  apiErrorParams: { rate_limit_info: info(resetsAt) },
  quotaLimits: { ...info(resetsAt), unifiedRateLimitFallbackAvailable: false, upgradePaths: ['upgrade_plan'], lowPriorityOffer: 'control', lowPriorityRetryAfterSeconds: 20, lowPriorityMaxWaitSeconds: 1200 },
  message: { id: `m-${uuid}`, model: '<synthetic>', role: 'assistant', type: 'message', stop_reason: 'stop_sequence', content: [{ type: 'text', text }] },
})
const REAL = [
  { at: '2026-10-13T21:44:52.946Z', resetsAt: 1791932400, text: "You've hit your session limit · resets 2am (Europe/Moscow)" },
  { at: '2026-10-14T12:32:16.758Z', resetsAt: 1791987600, text: "You've hit your session limit · resets 5:20pm (Europe/Moscow)" },
  { at: '2026-10-14T17:56:50.842Z', resetsAt: 1792005600, text: "You've hit your session limit · resets 10:20pm (Europe/Moscow)" },
  { at: '2026-10-14T22:17:56.540Z', resetsAt: 1792023600, text: "You've hit your session limit · resets 3:20am (Europe/Moscow)" },
  { at: '2026-10-13T10:56:50.701Z', resetsAt: 1791896400, text: "You've hit your session limit · resets 4pm (Europe/Moscow)" },
]
const AUTH_LINE = {
  type: 'assistant', uuid: 'auth-1', parentUuid: 'p', isSidechain: false, timestamp: '2026-10-13T08:45:03.682Z', cwd: '/tmp/demo', sessionId: 'sess',
  error: 'authentication_failed', apiErrorStatus: 403, isApiErrorMessage: true,
  message: { id: '639e6459-4f4c-49fe-ad72-5b0b9aaa59c1', model: '<synthetic>', role: 'assistant', stop_reason: 'stop_sequence', content: [{ type: 'text', text: 'Please run /login · API Error: 403 Request not allowed' }] },
}

test("the owner's session limits: a 5-hour limit, its reset read from rate_limit_info and from the text alike", () => {
  for (const r of REAL) {
    const at = Date.parse(r.at)
    const b = classifyApiError({ error: 'rate_limit', apiError: 'usage_limit_reached', status: 429, text: r.text, rateLimitInfo: info(r.resetsAt), builtIn: true, at })
    assert.equal(b.kind, 'limit')
    assert.equal(b.tone, 'warn')
    assert.equal(b.text, r.text)
    assert.deepEqual(b.account, { state: 'limited', reason: null, limit: { window: 'five_hour', label: 'session limit', resetsAt: r.resetsAt * 1000, autoResumeAt: null, usingCredits: false } })
    assert.equal(b.check, false)
    // A StopFailure hook carries only the error and the text: the same reset, read from "resets 2am (Europe/Moscow)".
    const hook = classifyApiError({ error: 'rate_limit', text: r.text, builtIn: true, at })
    assert.equal(hook.kind, 'limit')
    assert.equal(hook.resetsAt, r.resetsAt * 1000, r.text)
  }
})

test("the owner's sign-in error and his network error", () => {
  const auth = classifyApiError({ error: 'authentication_failed', status: 403, text: 'Please run /login · API Error: 403 Request not allowed', builtIn: true, at: 0 })
  assert.deepEqual(auth, { kind: 'logged_out', tone: 'error', text: 'Please run /login · API Error: 403 Request not allowed', account: { state: 'logged_out', reason: 'rejected', limit: null }, provider: null, resetsAt: null, check: true })
  const net = classifyApiError({ error: 'server_error', text: 'API Error: Can’t reach the API server — check your internet or DNS (ENOTFOUND)', builtIn: true, at: 0 })
  assert.equal(net.kind, null)
  assert.equal(net.tone, 'error')
  assert.equal(net.account, null)
})

test("the owner's limit notices: when Claude Code carries on, that it won't, and that it does now", () => {
  const armed = classifyNotice('Usage limit reached · continuing automatically at 5:20pm · esc to cancel', Date.parse('2026-10-14T12:32:16.763Z'))
  assert.deepEqual(armed, { kind: 'armed', autoResumeAt: 1791987600000, text: 'Usage limit reached · continuing automatically at 5:20pm' })
  const again = classifyNotice('Usage limit reached again · continuing automatically at 10:20pm · esc to cancel', Date.parse('2026-10-14T17:56:50.839Z'))
  assert.equal(again?.kind === 'armed' && again.autoResumeAt, 1792005600000)
  // The terminal-only tail goes: the phone can't type /rate-limit-options (it has its own button).
  assert.deepEqual(classifyNotice('Automatic continue cancelled · /rate-limit-options to re-arm', 0), { kind: 'cancelled', text: 'Automatic continue cancelled' })
  assert.deepEqual(classifyNotice('Usage limit reset · continuing automatically', 0), { kind: 'reset', text: 'Usage limit reset · continuing automatically' })
  // His other notice-level lines aren't the limit's.
  assert.equal(classifyNotice('Compacted while idle, before the prompt cache expired', 0), null)
  assert.equal(classifyNotice('Saved to /Users/x/.claude/settings.json (environment, soft_deny). Run `claude auto-mode config` to see the effective result.', 0), null)
})

// ── Claude Code's other wordings (2.1.300's own strings) ─────────────────
test('weekly, model and credit limits, spend caps, and the 429s that are no quota', () => {
  const at = Date.parse('2026-10-15T09:00:00Z')
  const weekly = classifyApiError({ error: 'rate_limit', apiError: 'usage_limit_reached', text: "You've hit your weekly limit · resets Oct 17, 10am (Europe/Moscow)", rateLimitInfo: { status: 'rejected', rateLimitType: 'seven_day' }, builtIn: true, at })
  assert.equal(weekly.account?.limit?.window, 'seven_day')
  assert.equal(weekly.account?.limit?.label, 'weekly limit')
  assert.equal(weekly.resetsAt, Date.parse('2026-10-17T07:00:00Z'))
  const opus = classifyApiError({ error: 'rate_limit', apiError: 'usage_limit_reached', text: "You've hit your Opus limit · resets Oct 18, 9:30am", rateLimitInfo: { status: 'rejected', rateLimitType: 'seven_day_opus', resetsAt: 1792305000 }, builtIn: true, at })
  assert.equal(opus.kind, 'model')
  assert.deepEqual(opus.account?.limit, { window: 'model', label: 'Opus limit', resetsAt: 1792305000000, autoResumeAt: null, usingCredits: false })
  const credits = classifyApiError({ error: 'rate_limit', apiError: 'usage_limit_reached', text: "You're out of usage credits · resets Nov 1, 3am", rateLimitInfo: { status: 'rejected', rateLimitType: 'overage', overageDisabledReason: 'out_of_credits', overageResetsAt: 1793491200 }, builtIn: true, at })
  assert.equal(credits.kind, 'no_credit')
  assert.deepEqual(credits.account, { state: 'no_credit', reason: 'out_of_credits', limit: { window: 'credits', label: 'usage credits', resetsAt: 1793491200000, autoResumeAt: null, usingCredits: false } })
  const cap = classifyApiError({ error: 'rate_limit', apiError: 'usage_limit_reached', text: "You've hit your org's monthly spend limit · ask your admin to raise it", rateLimitInfo: { status: 'rejected', rateLimitType: 'five_hour', overageDisabledReason: 'org_spend_cap_reached' }, builtIn: true, at })
  assert.equal(cap.account?.state, 'no_credit')
  assert.equal(cap.account?.reason, 'spend_cap')
  // Past the limit on usage credits Claude carries on: the account says so, the session isn't stopped.
  const paid = classifyApiError({ error: 'rate_limit', rateLimitInfo: { status: 'rejected', rateLimitType: 'five_hour', resetsAt: 1791932400, isUsingOverage: true }, builtIn: true, at })
  assert.equal(paid.kind, null)
  assert.equal(paid.account?.limit?.usingCredits, true)
  const transient = classifyApiError({ error: 'rate_limit', status: 429, transient: true, text: 'API Error: Server is temporarily limiting requests (not your usage limit) · Rate limited', builtIn: true, at })
  assert.deepEqual([transient.kind, transient.account, transient.tone], [null, null, 'warn'])
  const creditsModel = classifyApiError({ error: 'rate_limit', apiError: 'model_requires_usage_credits', text: 'Fable 5 requires usage credits. Turn them on at claude.ai/settings/usage', builtIn: true, at })
  assert.deepEqual([creditsModel.kind, creditsModel.account], ['model', null])
  const legacy = classifyApiError({ text: 'Claude AI usage limit reached|1791932400', builtIn: true, at })
  assert.deepEqual([legacy.kind, legacy.resetsAt, legacy.text], ['limit', 1791932400000, 'Claude AI usage limit reached'])
})

test('signed out, the plan, a hold, credit, and other providers', () => {
  const k = (text: string, error = 'authentication_failed', builtIn = true, status?: number) => classifyApiError({ error, text, builtIn, status, at: 0 })
  assert.equal(k('Login expired · Please run /login').account?.reason, 'expired')
  assert.equal(k('Failed to authenticate: OAuth session expired and could not be refreshed').account?.reason, 'expired')
  assert.equal(k('OAuth token revoked · Please run /login').account?.reason, 'revoked')
  assert.equal(k('Not logged in · Please run /login').account?.reason, 'not_logged_in')
  const org = k('Your organization has disabled Claude subscription access for Claude Code · …', 'oauth_org_not_allowed')
  assert.deepEqual([org.kind, org.account?.state, org.account?.reason, org.check], ['no_subscription', 'no_subscription', 'org_disabled', true])
  const hold = k("Your account is on hold and can't use Claude Code. View details or appeal: https://claude.ai/restricted", 'account_on_hold')
  assert.equal(hold.account?.state, 'on_hold')
  assert.match(hold.text, /claude\.ai\/restricted/)
  const pro = k('Claude Opus is not available with the Claude Pro plan. If you have updated your subscription plan recently, run /logout and /login for the plan to take effect.', 'invalid_request')
  assert.deepEqual([pro.kind, pro.account, pro.check], ['model', null, true])
  assert.deepEqual([k('Credit balance is too low', 'billing_error').account?.reason], ['api_credit'])
  // Another provider's trouble is its own: its key, its credit, its limits. The account hears nothing.
  const key = k('Please run /login · API Error: 401 {"error":"invalid key"}', 'authentication_failed', false, 401)
  assert.deepEqual([key.kind, key.provider, key.account], ['provider', 'bad_key', null])
  assert.equal(k('API Error: 402 {"error":{"message":"Insufficient credits"}}', 'unknown', false, 402).provider, 'no_credit')
  assert.equal(k('Credit balance is too low', 'billing_error', false).provider, 'no_credit')
  assert.equal(k('API Error: 429 Too many requests', 'rate_limit', false, 429).provider, 'limited')
})

test('request ids are taken out of what the phone shows', () => {
  assert.equal(cleanText('API Error: 500 {"type":"error","error":{"type":"api_error","message":"Internal"},"request_id":"req_011CVabcdefghij"}'), 'API Error: 500 {"type":"error","error":{"type":"api_error","message":"Internal"}}')
  assert.equal(cleanText('API Error: Overloaded · request_id: req_011CVabcdefghij'), 'API Error: Overloaded')
  assert.equal(cleanText('Failed (request id: req_011CVabcdefghij)'), 'Failed')
  assert.equal(cleanText('Overloaded ·  request_id:\n req_011CVabcdefghij  · try later'), 'Overloaded · try later')
  assert.equal(cleanText("You've hit your session limit ·  ·\n · "), "You've hit your session limit")
  assert.equal(cleanText('x'.repeat(300)).length, 200)
})

test('cleaning a line stays linear, whatever the server put in it', () => {
  // Two open-ended whitespace runs side by side took 12 s on 4000 spaces (cubic).
  for (const s of [
    'x' + ' '.repeat(200_000) + 'x',
    ' '.repeat(200_000) + '·',
    ' ·'.repeat(100_000) + ' ',
    '\t\n '.repeat(60_000) + 'request id req_0123456789',
    'request id: req_'.repeat(20_000),
    ' · request_id: req_0123456789'.repeat(10_000),
    ',"request_id":"req_'.repeat(20_000),
    'req_' + 'a'.repeat(200_000),
  ]) {
    const t0 = performance.now()
    cleanText(s, 1000)
    assert.ok(performance.now() - t0 < 1500, `${JSON.stringify(s.slice(0, 12))}… took ${Math.round(performance.now() - t0)} ms`)
  }
})

test('credits that ran out stop the session even while it ran on them; credits refused carry nothing on', () => {
  const at = Date.parse('2026-10-15T09:00:00Z')
  const base = { status: 'rejected', rateLimitType: 'five_hour', resetsAt: 1791932400 }
  // Running on credits when they ran out: the account is out of credit, and the session stopped (not a failed run).
  const out = classifyApiError({ error: 'rate_limit', apiError: 'usage_limit_reached', text: "You're out of usage credits · resets Nov 1, 3am", rateLimitInfo: { ...base, rateLimitType: 'overage', overageStatus: 'rejected', overageDisabledReason: 'out_of_credits', overageResetsAt: 1793491200, isUsingOverage: true }, builtIn: true, at })
  assert.deepEqual([out.kind, out.account?.state, out.account?.reason, out.account?.limit?.usingCredits], ['no_credit', 'no_credit', 'out_of_credits', false])
  // Credits refused: the limit stops it.
  const refused = classifyApiError({ error: 'rate_limit', apiError: 'usage_limit_reached', text: "You've hit your session limit · resets 2am", rateLimitInfo: { ...base, isUsingOverage: true, overageStatus: 'rejected' }, builtIn: true, at })
  assert.deepEqual([refused.kind, refused.account?.limit?.usingCredits], ['limit', false])
  // Credits that still take it, warned or not: Claude carries on.
  for (const overageStatus of ['allowed', 'allowed_warning']) {
    const paid = classifyApiError({ error: 'rate_limit', rateLimitInfo: { ...base, isUsingOverage: true, overageStatus }, builtIn: true, at })
    assert.deepEqual([paid.kind, paid.account?.limit?.usingCredits], [null, true], overageStatus)
  }
  // Another provider's limit says when it's over, when it can.
  const gw = classifyApiError({ error: 'rate_limit', status: 429, text: "You've hit your limit · resets 2am (Europe/Moscow)", builtIn: false, at: Date.parse('2026-10-13T21:44:52.946Z') })
  assert.deepEqual([gw.provider, gw.resetsAt], ['limited', 1791932400000])
})

test('times as Claude Code writes them', () => {
  const now = Date.parse('2026-10-14T22:30:00Z') // 01:30 in Moscow
  assert.equal(parseWhen('2am (Europe/Moscow)', now), Date.parse('2026-10-14T23:00:00Z'))
  assert.equal(parseWhen('1am', now), Date.parse('2026-10-15T22:00:00Z')) // passed already: tomorrow's
  assert.equal(parseWhen('Oct 17, 10:30am (Europe/Moscow)', now), Date.parse('2026-10-17T07:30:00Z'))
  assert.equal(parseWhen('Jan 3, 9am (UTC)', Date.parse('2026-12-30T12:00:00Z')), Date.parse('2027-01-03T09:00:00Z'))
  assert.equal(parseWhen('Jan 3, 2028, 9am (UTC)', now), Date.parse('2028-01-03T09:00:00Z'))
  assert.equal(parseWhen('10pm (America/New_York)', Date.parse('2026-10-15T12:00:00Z')), Date.parse('2026-10-16T02:00:00Z'))
  assert.equal(parseWhen('soon', now), null)
  assert.equal(parseWhen('13pm', now), null)
})

// ── Reading a transcript ─────────────────────────────────────────────────
const reply = (uuid: string, ts: string, text: string) => ({ type: 'assistant', uuid, parentUuid: 'p', timestamp: ts, isSidechain: false, message: { id: `m-${uuid}`, model: 'claude-opus-4-8', role: 'assistant', content: [{ type: 'text', text }] } })
const prompt = (uuid: string, ts: string, text: string) => ({ type: 'user', uuid, parentUuid: null, timestamp: ts, isSidechain: false, message: { role: 'user', content: text } })
const notice = (uuid: string, ts: string, content: string) => ({ type: 'system', subtype: 'informational', level: 'notice', isMeta: false, uuid, timestamp: ts, content })
const read = (...lines: object[]) => parseTranscript(lines.map((l) => JSON.stringify(l)).join('\n'))

test("a limit in a transcript is a notice, never Claude's words, and its session's blocker until the reset", () => {
  const st = read(
    prompt('u1', '2026-10-14T12:30:00.000Z', 'carry on'),
    reply('r1', '2026-10-14T12:31:00.000Z', 'Done with the parser.'),
    prompt('u2', '2026-10-14T12:32:10.000Z', 'now the tests'),
    limitEntry('lim', REAL[1]!.at, REAL[1]!.resetsAt, REAL[1]!.text),
    notice('n1', '2026-10-14T12:32:16.763Z', 'Usage limit reached · continuing automatically at 5:20pm · esc to cancel'),
  )
  assert.equal(st.lastText, 'Done with the parser.')
  assert.ok(!st.events.some((e) => e.kind === 'assistant' && e.text.includes('hit your')))
  const ev = st.events.find((e) => e.id === 'lim')
  assert.deepEqual(ev && { ...ev, ts: 0 }, { id: 'lim', kind: 'notice', ts: 0, text: REAL[1]!.text, tone: 'warn', blocker: 'limit' })
  const n = st.events.find((e) => e.id === 'n1')
  assert.deepEqual(n && { ...n, ts: 0 }, { id: 'n1', kind: 'notice', ts: 0, text: 'Usage limit reached · continuing automatically at 5:20pm', tone: 'info' })
  assert.deepEqual(st.blocker?.blocked, { kind: 'limit', text: REAL[1]!.text, resetsAt: 1791987600000, autoResumeAt: 1791987600000 })
  // The reply was to a request sent before the limit: the account hears it was fine then.
  assert.deepEqual(st.account.map((x) => [x.kind, x.at]), [['healthy', Date.parse('2026-10-14T12:30:00.000Z')], ['blocker', Date.parse(REAL[1]!.at)], ['notice', Date.parse('2026-10-14T12:32:16.763Z')]])
  // Cancelled, then the reset: the blocker goes.
  for (const l of [notice('n2', '2026-10-14T12:40:00.000Z', 'Automatic continue cancelled · /rate-limit-options to re-arm')]) {
    const st2 = read(limitEntry('lim', REAL[1]!.at, REAL[1]!.resetsAt, REAL[1]!.text), l)
    assert.equal(st2.blocker?.blocked.autoResumeAt, null)
  }
  const reset = read(limitEntry('lim', REAL[1]!.at, REAL[1]!.resetsAt, REAL[1]!.text), notice('n3', '2026-10-14T14:21:17.127Z', 'Usage limit reset · continuing automatically'))
  assert.equal(reset.blocker, null)
  // A real reply after it ends it too.
  const after = read(limitEntry('lim', REAL[1]!.at, REAL[1]!.resetsAt, REAL[1]!.text), prompt('u3', '2026-10-14T14:30:00.000Z', 'go'), reply('r2', '2026-10-14T14:30:10.000Z', 'Back at it.'))
  assert.equal(after.blocker, null)
  assert.equal(after.lastText, 'Back at it.')
})

test("the owner's sign-in error, then /login: blocked, then not", () => {
  const login = { type: 'system', subtype: 'local_command', level: 'info', isMeta: false, uuid: 'l1', parentUuid: 'c1', timestamp: '2026-10-13T08:45:59.489Z', content: '<local-command-stdout>Login successful</local-command-stdout>' }
  const st = read(AUTH_LINE)
  assert.deepEqual(st.blocker?.blocked, { kind: 'logged_out', text: 'Please run /login · API Error: 403 Request not allowed', resetsAt: null, autoResumeAt: null })
  assert.equal(st.events[0]?.kind, 'notice')
  const st2 = read(AUTH_LINE, login)
  assert.equal(st2.blocker, null)
  assert.deepEqual(st2.account.map((x) => x.kind), ['blocker', 'login'])
})

test("a subagent's limit left in the main transcript only tells the account", () => {
  const st = read({ ...limitEntry('sub', REAL[4]!.at, REAL[4]!.resetsAt, REAL[4]!.text), isSidechain: true })
  assert.deepEqual(st.events, [])
  assert.equal(st.blocker, null)
  assert.deepEqual(st.account.map((x) => x.kind), ['blocker'])
})

// ── The account ──────────────────────────────────────────────────────────
const accountMsgs = () => sent.filter((m) => m.t === 'account')

test('many sessions, one limit: one episode, one message, the meter full until the reset ends it', () => {
  const now = Date.now()
  const resetsAt = Math.ceil((now + 3600_000) / 1000) * 1000
  const before = accountMsgs().length
  const b = classifyApiError({ error: 'rate_limit', apiError: 'usage_limit_reached', status: 429, text: "You've hit your session limit · resets 2am (Europe/Moscow)", rateLimitInfo: info(resetsAt / 1000), builtIn: true, at: now - 5000 })
  for (const [i, s] of ['s1', 's2', 's3'].entries()) account.noteBlocker(b, { at: now - 5000 + i * 1000, sessionId: s, providerId: 'anthropic' })
  assert.equal(accountMsgs().length - before, 1)
  const a = account.getAccount()
  assert.equal(a.state, 'limited')
  assert.deepEqual([a.limit?.window, a.limit?.label, a.limit?.resetsAt, a.sessionId, a.since], ['five_hour', 'session limit', resetsAt, 's1', now - 5000])
  assert.deepEqual(hub.getUsage().fiveHour, { pct: 100, resetsAt })
  // A status line still carrying an older, lower number in the same window can't lower it.
  hub.offerUsage({ pct: 23, resetsAt: resetsAt + 30_000 }, null, now)
  assert.equal(hub.getUsage().fiveHour?.pct, 100)
  // A reply to a request sent before the limit proves nothing.
  account.noteHealthy('anthropic', now - 6000, 'claude-opus-4-8')
  assert.equal(account.getAccount().state, 'limited')
  // Claude Code will carry on by itself, then won't.
  account.noteLimitNotice({ kind: 'armed', autoResumeAt: resetsAt, text: '' }, now - 4000)
  assert.equal(account.getAccount().limit?.autoResumeAt, resetsAt)
  account.noteLimitNotice({ kind: 'cancelled', text: '' }, now - 3000)
  assert.equal(account.getAccount().limit?.autoResumeAt, null)
  // Kept for a restart, without anything about the owner but the state.
  const saved = JSON.parse(readFileSync(join(process.env.HYPERNOVA_SUPPORT!, 'account.json'), 'utf8'))
  assert.equal(saved.account.state, 'limited')
  // The reset passes: over.
  account.tickAccount(resetsAt + 1)
  assert.equal(account.getAccount().state, 'ok')
  assert.equal(accountMsgs().at(-1).account.state, 'ok')
})

test('a reset already past, or a sign-in error read long after, is old news', () => {
  const now = Date.now()
  const before = accountMsgs().length
  const past = classifyApiError({ error: 'rate_limit', apiError: 'usage_limit_reached', rateLimitInfo: info(Math.floor((now - 60_000) / 1000)), text: "You've hit your session limit · resets 2am", builtIn: true, at: now - 3600_000 })
  account.noteBlocker(past, { at: now - 3600_000, sessionId: 'old', providerId: 'anthropic' })
  const oldAuth = classifyApiError({ error: 'authentication_failed', text: 'OAuth token revoked · Please run /login', builtIn: true, at: now - 2 * 3600_000 })
  account.noteBlocker({ ...oldAuth, check: false }, { at: now - 2 * 3600_000, sessionId: 'old', providerId: 'anthropic' })
  assert.equal(account.getAccount().state, 'ok')
  assert.equal(accountMsgs().length, before)
})

test('signed out: the meter goes stale, sessions say so, and a sign-in ends it', async () => {
  const now = Date.now()
  hub.upsertSession('so-1', { cwd: scratch, origin: 'terminal', state: 'working' })
  const b = classifyApiError({ error: 'authentication_failed', text: 'OAuth token revoked · Please run /login', builtIn: true, at: now - 1000 })
  hub.blockSession('so-1', { ...b, check: false }, now - 1000)
  const a = account.getAccount()
  assert.deepEqual([a.state, a.reason, a.text, a.sessionId], ['logged_out', 'revoked', 'OAuth token revoked · Please run /login', 'so-1'])
  assert.equal(hub.getUsage().source, 'stale')
  assert.ok(sent.some((m) => m.t === 'usage' && m.usage.source === 'stale'))
  const s = hub.getSession('so-1')!
  assert.deepEqual([s.state, s.blocked?.kind], ['idle', 'logged_out'])
  // A check that finds the owner signed in can't tell the server accepts the token: still signed out.
  account.applyCheck({ loggedIn: true, authMethod: 'claude.ai', apiProvider: 'firstParty', subscriptionType: 'max' }, false, now)
  assert.equal(account.getAccount().state, 'logged_out')
  assert.equal(account.getAccount().plan, 'max')
  // /login in a terminal.
  await new Promise((r) => setTimeout(r, 5))
  account.noteLogin(Date.now())
  assert.equal(account.getAccount().state, 'ok')
  assert.notEqual(hub.getUsage().source, 'stale')
  assert.equal(hub.getSession('so-1')!.blocked, null)
  await account.requestCheck(true)
})

test('what a check says: not signed in, no plan, signed in again', () => {
  const now = Date.now()
  account.applyCheck({ loggedIn: false, authMethod: null, apiProvider: 'firstParty', subscriptionType: null }, false, now)
  assert.deepEqual([account.getAccount().state, account.getAccount().reason], ['logged_out', 'not_logged_in'])
  // Signed in with Claude after a sign-in, but no plan: the plan has ended.
  account.applyCheck({ loggedIn: true, authMethod: 'claude.ai', apiProvider: 'firstParty', subscriptionType: null }, true, now)
  assert.deepEqual([account.getAccount().state, account.getAccount().reason], ['no_subscription', 'ended'])
  // Renewed: a check undoes what a check found.
  account.applyCheck({ loggedIn: true, authMethod: 'claude.ai', apiProvider: 'firstParty', subscriptionType: 'pro' }, false, now)
  assert.deepEqual([account.getAccount().state, account.getAccount().plan], ['ok', 'pro'])
  // Nothing about the owner but these four fields is ever read.
  assert.deepEqual(account.readAuthStatus('{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty","email":"a@b.c","orgName":"X","subscriptionType":"max"}'), { loggedIn: true, authMethod: 'claude.ai', apiProvider: 'firstParty', subscriptionType: 'max' })
  assert.equal(account.readAuthStatus('9.9.9 (Claude Code)'), null)
})

test("another provider's trouble is its own: its card says so until it replies", () => {
  const { saveProvider, listProviders } = hubStore
  const p = saveProvider({ name: 'OpenRouter', kind: 'openrouter', apiKey: 'test-only-not-a-key' })
  const now = Date.now()
  hub.upsertSession('or-1', { cwd: scratch, origin: 'hypernova', providerId: p.id, state: 'working' })
  hub.blockSession('or-1', classifyApiError({ error: 'unknown', status: 402, text: 'API Error: 402 Insufficient credits', builtIn: false, at: now }), now)
  assert.equal(account.getAccount().state, 'ok')
  assert.deepEqual(listProviders().find((x: any) => x.id === p.id)?.status?.state, 'no_credit')
  assert.ok(sent.some((m) => m.t === 'providers' && m.providers.some((x: any) => x.status?.state === 'no_credit')))
  assert.equal(hub.getSession('or-1')!.blocked?.kind, 'provider')
  hub.sessionHealthy('or-1', now + 1000, 'anthropic/claude-sonnet-4')
  assert.equal(listProviders().find((x: any) => x.id === p.id)?.status, null)
  assert.equal(hub.getSession('or-1')!.blocked, null)
})
test('a session blocked by a limit whose reset has passed is blocked no more', async () => {
  await new Promise((r) => setTimeout(r, 5))
  hub.upsertSession('bl-1', { cwd: scratch, origin: 'terminal', state: 'idle' })
  hub.setBlocked('bl-1', { kind: 'limit', text: 'x', resetsAt: Date.now() - 1000, autoResumeAt: null }, Date.now() - 60_000)
  assert.equal(hub.getSession('bl-1')!.blocked, null)
  const resetsAt = Date.now() + 60_000
  hub.setBlocked('bl-1', { kind: 'limit', text: 'x', resetsAt, autoResumeAt: null }, Date.now())
  assert.equal(hub.getSession('bl-1')!.blocked?.resetsAt, resetsAt)
})

test('StopFailure: the session stops idle with why, and Claude Code\'s error never becomes its last words', async () => {
  const { handleHook } = await import('../src/hooks.ts')
  hub.upsertSession('sf-1', { cwd: scratch, origin: 'terminal', state: 'working', lastText: 'Done with the parser.' })
  await handleHook({ session_id: 'sf-1', hook_event_name: 'StopFailure', cwd: scratch, error: 'rate_limit', error_details: '429', last_assistant_message: "You've hit your session limit · resets 2am (Europe/Moscow)" }, {} as never, {} as never)
  const s = hub.getSession('sf-1')!
  assert.deepEqual([s.state, s.blocked?.kind, s.lastText], ['idle', 'limit', 'Done with the parser.'])
  assert.ok((s.blocked?.resetsAt ?? 0) > Date.now())
  // A subagent's own StopFailure isn't the session's.
  hub.upsertSession('sf-2', { cwd: scratch, origin: 'terminal', state: 'working' })
  await handleHook({ session_id: 'sf-2', hook_event_name: 'StopFailure', cwd: scratch, agent_id: 'a0123456789abcdef', error: 'rate_limit', last_assistant_message: "You've hit your session limit · resets 2am" }, {} as never, {} as never)
  assert.equal(hub.getSession('sf-2')!.blocked, null)
  account.tickAccount(Date.now() + 2 * 86400_000)
})

test('StopFailure is installed with the hooks and added to an older install, but never required', async () => {
  const { addOptionalHooks, installIntegration, integrationStatus, uninstallIntegration } = await import('../src/hooks.ts')
  const file = join(process.env.CLAUDE_CONFIG_DIR!, 'settings.json')
  assert.equal(addOptionalHooks(), false) // nothing installed: nothing added
  installIntegration()
  assert.ok(JSON.parse(readFileSync(file, 'utf8')).hooks.StopFailure)
  // An install from before StopFailure: still installed, and the hook is added at start.
  const s = JSON.parse(readFileSync(file, 'utf8'))
  delete s.hooks.StopFailure
  writeFileSync(file, JSON.stringify(s))
  assert.equal(integrationStatus().hooks.installed, true)
  assert.equal(addOptionalHooks(), true)
  assert.equal(JSON.parse(readFileSync(file, 'utf8')).hooks.StopFailure[0].hooks[0].type, 'http')
  assert.equal(addOptionalHooks(), false)
  uninstallIntegration()
  assert.equal(JSON.parse(readFileSync(file, 'utf8')).hooks?.StopFailure, undefined)
})

// ── Signing in again ─────────────────────────────────────────────────────
test('the sign-in page address is read off the login screen, joined back when it wraps', () => {
  const url = 'https://claude.ai/oauth/authorize?code=true&client_id=x&state=' + 'a'.repeat(60)
  // As Claude Code 2.1.300's `auth login` prints it.
  const screen = `Opening browser to sign in…\nIf the browser didn't open, visit: ${url}\nPaste code here if prompted >`
  assert.equal(account.signInUrl(screen, 500), url)
  assert.deepEqual(account.readSignIn(screen, 500), { url, atLineEnd: true })
  assert.equal(account.signInUrl(`${url.slice(0, 40)}\n${url.slice(40, 80)}\n${url.slice(80)}\n\nPaste code here`, 40), url)
  assert.equal(account.signInUrl('See https://example.com/phish for help', 100), null)
  // Something else on the line after it: read, but not as Claude Code printed it.
  assert.deepEqual(account.readSignIn(`visit: ${url} Paste code here if prompted >`, 500), { url, atLineEnd: false })
})

test('the address shown: kept against reads cut short, carried further, replaced by a new sign-in', () => {
  const page = (challenge: string, state: string) => `https://claude.ai/oauth/authorize?code=true&client_id=x&code_challenge=${challenge}&code_challenge_method=S256&state=${state}`
  const a = page('ChallengeA', 'A'.repeat(43))
  assert.equal(account.signInState(a), 'A'.repeat(43))
  assert.equal(account.keptSignInUrl(null, a), a)
  assert.equal(account.keptSignInUrl(a, null), a)
  // Cut short (the Mac's window narrowed the screen): the address shown stays, inside its state too.
  assert.equal(account.keptSignInUrl(a, a.slice(0, 60)), a)
  assert.equal(account.keptSignInUrl(a, a.slice(0, -10)), a)
  // The first read was cut short: a longer one carries it further.
  assert.equal(account.keptSignInUrl(a.slice(0, 60), a), a)
  assert.equal(account.keptSignInUrl(a.slice(0, a.indexOf('&state=')), a), a)
  assert.equal(account.keptSignInUrl(a.slice(0, -10), a), a)
  // A new sign-in (Claude Code started over after a refused code): a state of its own.
  const b = page('ChallengeB', 'B'.repeat(43))
  assert.equal(account.keptSignInUrl(a, b), b)
  // Read cut short inside its state, it's new all the same, and a later read carries it further.
  assert.equal(account.keptSignInUrl(a, b.slice(0, -20)), b.slice(0, -20))
  assert.equal(account.keptSignInUrl(b.slice(0, -20), b), b)
  // Cut short before its state, it can't be told from a bad read: the address shown stays.
  assert.equal(account.keptSignInUrl(a, b.slice(0, b.indexOf('&state='))), a)
  // A new sign-in only from an address that runs to the end of its line; one with text after it
  // can still carry the shown one further.
  assert.equal(account.keptSignInUrl(a, b, false), a)
  assert.equal(account.keptSignInUrl(a.slice(0, 60), a, false), a)
})

test('signing in from the phone, through a stand-in for Claude Code: codes turned away, a refused one, a new sign-in, the right one', async () => {
  rmSync(join(fakeState, 'signed-in'), { force: true })
  await account.requestCheck(true)
  assert.deepEqual([account.getAccount().state, account.getAccount().reason], ['logged_out', 'not_logged_in'])
  account.startLogin('phone')
  assert.equal(account.getAccount().login?.state, 'starting')
  // A second start while one runs returns the running one.
  account.startLogin('phone')
  await until(() => account.getAccount().login?.state === 'waiting_code')
  const first = account.getAccount().login!.url!
  assert.match(first, /^https:\/\/claude\.ai\/oauth\/authorize\?code=true&.*&state=FakeState\d+x\w+$/)
  const firstState = account.signInState(first)!
  // Turned away before Claude Code sees them (it would end the sign-in over them): not a code,
  // half of one, one from another sign-in's page.
  await assert.rejects(account.sendLoginCode('two words'), { code: 'bad_code' })
  await assert.rejects(account.sendLoginCode('NotTheRightShape'), { code: 'bad_code' })
  await assert.rejects(account.sendLoginCode(`#${firstState}`), { code: 'bad_code' })
  await assert.rejects(account.sendLoginCode('AuthCode0123456789#SomeOtherSignIn'), { code: 'bad_code' })
  assert.deepEqual([account.getAccount().login?.state, account.getAccount().login?.url], ['waiting_code', first])
  // The server refuses a code: `claude auth login` says "Login failed" and exits 1. While it
  // checks the code, another one waits its turn; then a new sign-in starts at once, at a new
  // address, which the phone is told to open again.
  await account.sendLoginCode(`Wrong0123456789#${firstState}`)
  assert.equal(account.getAccount().login?.state, 'finishing')
  await assert.rejects(account.sendLoginCode(`AuthCode0123456789#${firstState}`), { code: 'not_ready' })
  await until(() => account.getAccount().login?.state === 'waiting_code' && account.getAccount().login?.url !== first)
  const again = account.getAccount().login!
  assert.match(again.error ?? '', /started over/)
  const secondState = account.signInState(again.url)!
  assert.match(again.url ?? '', /&state=FakeState\d+x\w+$/)
  assert.notEqual(secondState, firstState)
  // The first page's code is spent; the new page's signs in.
  await assert.rejects(account.sendLoginCode(`AuthCode0123456789#${firstState}`), { code: 'bad_code' })
  assert.equal(account.getAccount().login?.state, 'waiting_code')
  await account.sendLoginCode(`AuthCode0123456789#${secondState}`)
  await until(() => account.getAccount().login === null)
  assert.deepEqual([account.getAccount().state, account.getAccount().plan], ['ok', 'max'])
  assert.ok(existsSync(join(fakeState, 'signed-in')))
  // Signed in: nothing to do.
  assert.throws(() => account.startLogin('phone'), { code: 'not_needed' })
  await assert.rejects(account.sendLoginCode('a#b'), { code: 'no_login' })
})

test('a sign-in whose codes keep being refused starts over three times, then stops and says so', async () => {
  rmSync(join(fakeState, 'signed-in'), { force: true })
  await account.requestCheck(true)
  account.startLogin('phone')
  const seen = new Set<string>()
  for (let i = 0; i < 4; i++) {
    await until(() => account.getAccount().login?.state === 'waiting_code' && !seen.has(account.getAccount().login!.url!))
    const url = account.getAccount().login!.url!
    seen.add(url)
    await account.sendLoginCode(`Wrong0123456789#${account.signInState(url)}`)
  }
  await until(() => account.getAccount().login?.state === 'failed')
  assert.deepEqual({ ...account.getAccount().login, error: account.getAccount().login?.error?.includes('didn’t work') }, { state: 'failed', url: null, where: 'phone', error: true })
  assert.equal(account.getAccount().state, 'logged_out')
  // Starting again is a fresh sign-in with its own three tries.
  account.startLogin('phone')
  await until(() => account.getAccount().login?.state === 'waiting_code')
  assert.equal(account.getAccount().login?.error, null)
  account.cancelLogin()
  writeFileSync(join(fakeState, 'signed-in'), '')
  await account.requestCheck(true)
})

test("how the login command answered a code: read below its prompt, and only \"Login failed\" starts over", async () => {
  const top = "Opening browser to sign in…\nIf the browser didn't open, visit: https://claude.ai/oauth/authorize?state=abc\nPaste code here if prompted > Code#abc"
  assert.equal(account.afterCode(top), null)
  assert.equal(account.afterCode(`${top}\nLogin failed: Authentication failed: Invalid authorization code\n\n`), 'Login failed: Authentication failed: Invalid authorization code')
  // A line it couldn't read is no answer; the prompt waits on.
  assert.equal(account.afterCode(`${top}\nInvalid code. Please make sure the full code was copied.`), null)
  assert.equal(account.afterCode('no prompt yet'), null)
  // Read live in a window narrower than the code (the Mac's), the code runs onto the lines below
  // the prompt: never its answer. The screen it ends with has those lines joined back.
  const narrow = 'Paste code here if prompted > AuthCode012345\n6789abcdefghij#FakeSt\nate1x0123'
  assert.equal(account.afterCode(narrow, true), null)
  assert.equal(account.afterCode(`${narrow}\nLogin failed: Authentication failed: Invalid authorization code`, true), 'Login failed: Authentication failed: Invalid authorization code')
  // A right code for an account on hold: Claude Code says why and exits 1, without "Login failed".
  // A new sign-in would end the same way, so it stops there, with Claude Code's words.
  rmSync(join(fakeState, 'signed-in'), { force: true })
  await account.requestCheck(true)
  account.startLogin('phone')
  await until(() => account.getAccount().login?.state === 'waiting_code')
  const runs = readFileSync(join(fakeState, 'logins'), 'utf8').trim()
  await account.sendLoginCode(`Held0123456789#${account.signInState(account.getAccount().login!.url)}`)
  await until(() => account.getAccount().login?.state === 'failed')
  const l = account.getAccount().login!
  assert.equal(l.url, null)
  assert.match(l.error ?? '', /^Your account is on hold and can't sign in to Claude Code\. View details or appeal: https:\/\/claude\.ai\//)
  assert.equal(readFileSync(join(fakeState, 'logins'), 'utf8').trim(), runs)
  assert.equal(account.getAccount().state, 'logged_out')
  account.cancelLogin()
  writeFileSync(join(fakeState, 'signed-in'), '')
  await account.requestCheck(true)
})

test('a sign-in can be cancelled: its terminal ends and nothing changes', async () => {
  rmSync(join(fakeState, 'signed-in'), { force: true })
  await account.requestCheck(true)
  account.startLogin('phone')
  await until(() => account.getAccount().login?.state === 'waiting_code')
  account.cancelLogin()
  assert.equal(account.getAccount().login, null)
  assert.equal(account.getAccount().state, 'logged_out')
  writeFileSync(join(fakeState, 'signed-in'), '')
  await account.requestCheck(true)
})

test('signed out by the server, the old token still on disk: a refused code, a hold or a Ctrl-C is never a sign-in; "Login successful" is', async () => {
  const terms = await import('../src/terms.ts')
  const dir = join(process.env.HYPERNOVA_SUPPORT!, 'terms')
  /** The sign-in's holder, as the owner reaches it from its window on the Mac: to paste a code or press Ctrl-C himself. */
  const loginTerm = (): string => {
    const metas = readdirSync(dir).flatMap((f) => {
      try {
        return f.endsWith('.json') ? [JSON.parse(readFileSync(join(dir, f), 'utf8'))] : []
      } catch {
        return []
      }
    })
    return metas.filter((m) => m.args?.[1] === 'login').sort((a, b) => b.createdAt - a.createdAt)[0].id
  }
  const login = () => account.getAccount().login
  /** The next sign-in Claude Code starts, at an address other than [url]. */
  const next = async (url: string) => {
    await until(() => login()?.state === 'waiting_code' && login()?.url !== url)
    return login()!.url!
  }
  // The owner's case: the server refuses the token ("Please run /login · API Error: 403"), while
  // `claude auth status` still says signed in, as it reads the token on disk, and a failed
  // `claude auth login` leaves that token there.
  writeFileSync(join(fakeState, 'signed-in'), '')
  await new Promise((r) => setTimeout(r, 5))
  const at = Date.now()
  const rejected = classifyApiError({ error: 'authentication_failed', status: 403, text: 'Please run /login · API Error: 403 Request not allowed', builtIn: true, at })
  account.noteBlocker({ ...rejected, check: false }, { at, sessionId: null, providerId: 'anthropic' })
  const signedOut = () => assert.deepEqual([account.getAccount().state, account.getAccount().reason], ['logged_out', 'rejected'])
  signedOut()
  account.startLogin('phone')
  const first = await next('')
  // A wrong code from the phone: a new sign-in, still signed out.
  await account.sendLoginCode(`Wrong0123456789#${account.signInState(first)}`)
  const second = await next(first)
  signedOut()
  assert.match(login()?.error ?? '', /started over/)
  // A wrong code the owner pastes himself in the sign-in's window: started over the same way.
  terms.pressKeys(loginTerm(), `Wrong0123456789#${account.signInState(second)}\r`)
  await next(second)
  signedOut()
  // Ctrl-C at its prompt: the command ends by a signal, which reports exit 0, and says nothing.
  terms.pressKeys(loginTerm(), '\x03')
  await until(() => login()?.state === 'failed')
  assert.equal(login()?.error, 'The sign-in didn’t finish. Try again.')
  signedOut()
  // A right code for an account on hold: Claude Code's words, still signed out.
  account.startLogin('phone')
  await account.sendLoginCode(`Held0123456789#${account.signInState(await next(''))}`)
  await until(() => login()?.state === 'failed')
  assert.match(login()?.error ?? '', /^Your account is on hold/)
  signedOut()
  // The right code, pasted in the window: "Login successful." signs in.
  account.startLogin('phone')
  const last = await next('')
  terms.pressKeys(loginTerm(), `AuthCode0123456789#${account.signInState(last)}\r`)
  await until(() => login() === null)
  assert.deepEqual([account.getAccount().state, account.getAccount().plan], ['ok', 'max'])
})

// ── After the first round's review ───────────────────────────────────────
const tick = () => new Promise((r) => setTimeout(r, 5))
const sessionLimit = (resetsAt: number, at: number) =>
  classifyApiError({ error: 'rate_limit', apiError: 'usage_limit_reached', status: 429, text: "You've hit your session limit · resets 2am", rateLimitInfo: info(Math.ceil(resetsAt / 1000)), builtIn: true, at })
const revoked = (at: number) => ({ ...classifyApiError({ error: 'authentication_failed', text: 'OAuth token revoked · Please run /login', builtIn: true, at }), check: false })

test("a limit Claude Code says has reset frees every session it stopped, not only the account", async () => {
  await new Promise((r) => setTimeout(r, 50))
  const now = Date.now() - 40
  const resetsAt = Math.ceil((now + 3600_000) / 1000) * 1000
  for (const id of ['cl-1', 'cl-2']) {
    hub.upsertSession(id, { cwd: scratch, origin: 'terminal', state: 'working' })
    hub.blockSession(id, sessionLimit(resetsAt, now), now)
  }
  assert.equal(account.getAccount().state, 'limited')
  assert.deepEqual(['cl-1', 'cl-2'].map((id) => hub.getSession(id)!.blocked?.kind), ['limit', 'limit'])
  account.noteLimitNotice({ kind: 'reset', text: 'Usage limit reset · continuing automatically' }, now + 20)
  assert.equal(account.getAccount().state, 'ok')
  assert.deepEqual(['cl-1', 'cl-2'].map((id) => hub.getSession(id)!.blocked), [null, null])
  // A limit that stops a session after that is news again.
  await tick()
  hub.setBlocked('cl-1', { kind: 'limit', text: 'x', resetsAt, autoResumeAt: null }, Date.now())
  assert.equal(hub.getSession('cl-1')!.blocked?.kind, 'limit')
  hub.setBlocked('cl-1', null)
})

test('on usage credits past a limit, replies go on and the episode stays one', async () => {
  // All of it a moment ago, after the last proof the account worked (nothing stamped later than now).
  await new Promise((r) => setTimeout(r, 50))
  const now = Date.now() - 40
  const resetsAt = Math.ceil((now + 3600_000) / 1000) * 1000
  const before = accountMsgs().length
  const rli = { status: 'rejected', rateLimitType: 'five_hour', resetsAt: resetsAt / 1000, isUsingOverage: true, overageStatus: 'allowed' }
  account.noteBlocker({ ...classifyApiError({ error: 'rate_limit', rateLimitInfo: rli, builtIn: true, at: now }), kind: null }, { at: now, sessionId: 'pc-1', providerId: 'anthropic' })
  const since = account.getAccount().since
  assert.deepEqual([account.getAccount().state, account.getAccount().limit?.usingCredits, since], ['limited', true, now])
  // A reply (paid) doesn't end it, so the next event doesn't begin another.
  account.noteHealthy('anthropic', now + 10, 'claude-opus-4-8')
  assert.equal(account.getAccount().state, 'limited')
  account.noteBlocker({ ...classifyApiError({ error: 'rate_limit', rateLimitInfo: rli, builtIn: true, at: now + 20 }), kind: null }, { at: now + 20, sessionId: 'pc-1', providerId: 'anthropic' })
  assert.equal(account.getAccount().since, since)
  assert.equal(accountMsgs().length - before, 1)
  // A session stopped by it after all: the credits no longer carry Claude on. Still the same episode.
  account.noteBlocker(classifyApiError({ error: 'rate_limit', apiError: 'usage_limit_reached', text: "You've hit your session limit · resets 2am", rateLimitInfo: { ...rli, isUsingOverage: false, overageStatus: 'rejected' }, builtIn: true, at: now + 30 }), { at: now + 30, sessionId: 'pc-2', providerId: 'anthropic' })
  assert.deepEqual([account.getAccount().limit?.usingCredits, account.getAccount().since], [false, since])
  account.tickAccount(resetsAt + 1)
  assert.equal(account.getAccount().state, 'ok')
})

test('signing in again during a limit: the limit comes back after the sign-in, as the same episode', async () => {
  await tick()
  const now = Date.now()
  const resetsAt = Math.ceil((now + 2 * 3600_000) / 1000) * 1000
  hub.upsertSession('rs-1', { cwd: scratch, origin: 'terminal', state: 'working' })
  hub.blockSession('rs-1', sessionLimit(resetsAt, now), now)
  const since = account.getAccount().since
  assert.equal(account.getAccount().state, 'limited')
  // Signed out on top of it: the sign-out shows, and the limit waits underneath (kept for a restart).
  await tick()
  hub.upsertSession('rs-2', { cwd: scratch, origin: 'terminal', state: 'working' })
  hub.blockSession('rs-2', revoked(Date.now()), Date.now())
  assert.deepEqual([account.getAccount().state, hub.getUsage().source], ['logged_out', 'stale'])
  assert.equal(hub.getSession('rs-1')!.blocked?.kind, 'limit')
  const saved = JSON.parse(readFileSync(join(process.env.HYPERNOVA_SUPPORT!, 'account.json'), 'utf8'))
  assert.deepEqual([saved.under.state, saved.under.limit.window, saved.under.since], ['limited', 'five_hour', since])
  // /login in a terminal: signed in, and still under the limit, which phones already know (its since).
  await tick()
  const msgs = accountMsgs().length
  account.noteLogin(Date.now())
  await account.requestCheck(true)
  const a = account.getAccount()
  assert.deepEqual([a.state, a.limit?.window, a.limit?.resetsAt, a.since, a.text], ['limited', 'five_hour', resetsAt, since, "You've hit your session limit · resets 2am"])
  // What clients heard since the sign-in: that limit (and the check's time), never "ok" in between.
  const heard = accountMsgs().slice(msgs)
  assert.ok(heard.length >= 1)
  assert.ok(heard.every((m) => m.account.state === 'limited' && m.account.since === since))
  // Full again (its exact reset is checked in a fresh daemon: earlier tests here left later windows).
  assert.deepEqual([hub.getUsage().source, hub.getUsage().fiveHour?.pct], ['official', 100])
  // The session the limit stopped still waits for its reset; the one the sign-out stopped doesn't.
  assert.equal(hub.getSession('rs-1')!.blocked?.kind, 'limit')
  assert.equal(hub.getSession('rs-2')!.blocked, null)
  // A reply to a request sent after the sign-in: a request went through, the limit is over.
  await tick()
  account.noteHealthy('anthropic', Date.now(), 'claude-opus-4-8')
  assert.equal(account.getAccount().state, 'ok')
  assert.equal(hub.getSession('rs-1')!.blocked, null)
})

test('a sign-out that outlasts the limit under it, or a reply before the sign-in, leaves nothing to bring back', async () => {
  await tick()
  let now = Date.now()
  const resetsAt = Math.ceil((now + 3600_000) / 1000) * 1000
  account.noteBlocker(sessionLimit(resetsAt, now), { at: now, sessionId: 'ou-1', providerId: 'anthropic' })
  await tick()
  account.noteBlocker(revoked(Date.now()), { at: Date.now(), sessionId: 'ou-2', providerId: 'anthropic' })
  assert.equal(account.getAccount().state, 'logged_out')
  account.tickAccount(resetsAt + 1)
  await account.requestCheck()
  assert.equal(JSON.parse(readFileSync(join(process.env.HYPERNOVA_SUPPORT!, 'account.json'), 'utf8')).under, null)
  await tick()
  account.noteLogin(Date.now())
  await account.requestCheck(true)
  assert.equal(account.getAccount().state, 'ok')
  // A reply while signed out (the token worked after all) proves the limit over too.
  await tick()
  now = Date.now()
  account.noteBlocker(sessionLimit(resetsAt + 3600_000, now), { at: now, sessionId: 'ou-3', providerId: 'anthropic' })
  await tick()
  account.noteBlocker(revoked(Date.now()), { at: Date.now(), sessionId: 'ou-4', providerId: 'anthropic' })
  await tick()
  account.noteHealthy('anthropic', Date.now(), 'claude-opus-4-8')
  assert.equal(account.getAccount().state, 'ok')
  await tick()
  account.noteLogin(Date.now())
  await account.requestCheck(true)
  assert.equal(account.getAccount().state, 'ok')
})

test("another provider's limit is over at its reset, or a few hours on; its card shows when only when the provider said", async () => {
  const p = hubStore.saveProvider({ name: 'Gateway', kind: 'custom', baseUrl: 'https://gateway.example', apiKey: 'test-only-not-a-key' })
  const now = Date.now()
  hub.upsertSession('pl-1', { cwd: scratch, origin: 'hypernova', providerId: p.id, state: 'working' })
  hub.blockSession('pl-1', classifyApiError({ error: 'rate_limit', status: 429, text: 'API Error: 429 Too many requests', builtIn: false, at: now }), now)
  assert.equal(account.providerStatus(p.id)?.state, 'limited')
  const shown = hubStore.listProviders().find((x: any) => x.id === p.id)!.status!
  assert.deepEqual(Object.keys(shown).sort(), ['budget', 'resetsAt', 'since', 'state', 'text'])
  // The few hours are a guess, for clearing only: never shown as a reset.
  assert.equal(shown.resetsAt, null)
  assert.equal(hub.getSession('pl-1')!.blocked?.kind, 'provider')
  account.tickAccount(now + 2 * 3600_000)
  assert.equal(account.providerStatus(p.id)?.state, 'limited')
  account.tickAccount(now + 3 * 3600_000 + 1000)
  assert.equal(account.providerStatus(p.id), null)
  assert.equal(hub.getSession('pl-1')!.blocked, null)
  // One that says when it resets lasts until then.
  const resetsAt = Math.ceil((now + 600_000) / 1000)
  hub.blockSession('pl-1', classifyApiError({ error: 'rate_limit', status: 429, text: 'API Error: 429', rateLimitInfo: { status: 'rejected', resetsAt }, builtIn: false, at: now }), now)
  assert.equal(account.providerStatus(p.id)?.state, 'limited')
  assert.equal(account.providerStatus(p.id)?.resetsAt, resetsAt * 1000)
  account.tickAccount(resetsAt * 1000 + 1000)
  assert.equal(account.providerStatus(p.id), null)
  hubStore.deleteProvider(p.id)
})

test('a session seen for the first time is announced at once, then only when it changes', () => {
  const n = sent.length
  hub.upsertSession('first-seen', { cwd: scratch, origin: 'terminal' })
  assert.deepEqual(sent.slice(n).filter((m) => m.t === 'session').map((m) => [m.session.id, m.session.state]), [['first-seen', 'idle']])
  const n2 = sent.length
  hub.upsertSession('first-seen', { cwd: scratch, origin: 'terminal' })
  assert.equal(sent.slice(n2).filter((m) => m.t === 'session').length, 0)
})

test("a fork's copy of its parent's history shows the limit but neither tells the account nor stops the fork", () => {
  // As Claude Code writes a fork: the parent's entries, same uuid and time, with forkedFrom.
  const copy = (o: any) => ({ ...o, sessionId: 'fork', forkedFrom: { sessionId: 'sess', messageUuid: o.uuid } })
  const st = read(copy(prompt('u1', '2026-10-14T12:30:00.000Z', 'carry on')), copy(limitEntry('lim', REAL[1]!.at, REAL[1]!.resetsAt, REAL[1]!.text)))
  assert.deepEqual(st.events.map((e) => [e.kind, e.kind === 'notice' ? e.blocker : null]), [['user', null], ['notice', 'limit']])
  assert.equal(st.blocker, null)
  assert.deepEqual(st.account, [])
  // Its own lines after the copy count as ever.
  const own = read(copy(limitEntry('lim', REAL[1]!.at, REAL[1]!.resetsAt, REAL[1]!.text)), prompt('u2', '2026-10-14T12:40:00.000Z', 'go on'), limitEntry('lim2', '2026-10-14T12:40:05.000Z', REAL[1]!.resetsAt, REAL[1]!.text))
  assert.equal(own.blocker?.id, 'lim2')
  assert.deepEqual(own.account.map((n) => n.kind), ['blocker'])
})

test('moved to another provider and read again (a daemon restart): its limit from before is neither the new provider\'s nor its blocker', async () => {
  const p = hubStore.saveProvider({ name: 'Elsewhere', kind: 'openrouter', apiKey: 'test-only-not-a-key' })
  const now = Date.now()
  const id = 'aaaaaaaa-0000-4000-8000-000000000101'
  const cwd = join(scratch, 'moved')
  const file = hub.transcriptPathFor(cwd, id)
  mkdirSync(dirname(file), { recursive: true })
  const at = (ms: number) => new Date(ms).toISOString()
  const resetsAt = Math.ceil((now + 3600_000) / 1000)
  writeFileSync(file, [prompt('m1', at(now - 120_000), 'carry on'), limitEntry('mlim', at(now - 60_000), resetsAt, "You've hit your session limit · resets 2am"), prompt('m2', at(now - 1000), 'go on')].map((l) => JSON.stringify(l)).join('\n') + '\n')
  // Moved half a minute ago, from the built-in provider, as "Continue on Elsewhere" records it.
  hubStore.saveManaged({ id, cwd, providerId: p.id, model: null, permissionMode: 'default', createdAt: now - 300_000, engine: 'term', providerAt: now - 30_000, providerFrom: 'anthropic' })
  const r = hub.upsertSession(id, { cwd, origin: 'hypernova', providerId: p.id, state: 'idle' })
  const st = hub.ensureParsed(r)
  assert.equal(account.providerStatus(p.id), null)
  assert.equal(hub.getSession(id)!.blocked, null)
  // The notice is still there, read as the built-in provider's limit it was.
  assert.equal(st.events.find((e) => e.id === 'mlim')?.kind === 'notice' && (st.events.find((e) => e.id === 'mlim') as any).blocker, 'limit')
  hubStore.deleteProvider(p.id)
})

test("a provider deleted under its sessions: their 401 and 402 are nobody's (least of all the account's), and each says it was removed", async () => {
  const runner = await import('../src/runner.ts')
  const p = hubStore.saveProvider({ name: 'Gone soon', kind: 'openrouter', apiKey: 'test-only-not-a-key' })
  const before = account.getAccount().state
  const now = Date.now()
  // A terminal still running on the old key, an SDK session between turns, one that ended.
  hub.upsertSession('rm-term', { cwd: scratch, origin: 'terminal', providerId: p.id, state: 'working' })
  hubStore.saveManaged({ id: 'rm-sdk', cwd: scratch, providerId: p.id, model: null, permissionMode: 'default', createdAt: now, engine: 'sdk' })
  hub.upsertSession('rm-sdk', { cwd: scratch, origin: 'hypernova', providerId: p.id, state: 'idle' })
  hub.upsertSession('rm-ended', { cwd: scratch, origin: 'hypernova', providerId: p.id, state: 'ended' })
  hubStore.deleteProvider(p.id)
  account.noteProviderRemoved(p.id, now)
  assert.equal(hub.getSession('rm-term')!.blocked, null)
  assert.deepEqual([hub.getSession('rm-sdk')!.blocked?.kind, hub.getSession('rm-sdk')!.blocked?.text], ['provider', hubStore.REMOVED_TEXT])
  assert.equal(hub.getSession('rm-ended')!.blocked?.kind, 'provider')
  // The terminal's next requests are refused: out of credit, then the key. Neither is the account's.
  const builtIn = hubStore.isSubscription(p.id)
  assert.equal(builtIn, false)
  hub.blockSession('rm-term', classifyApiError({ error: 'billing_error', status: 402, text: 'API Error: 402 Insufficient credits.', builtIn, at: now }), now)
  hub.blockSession('rm-term', classifyApiError({ error: 'authentication_failed', status: 401, text: 'API Error: 401 invalid x-api-key', builtIn, at: now }), now)
  assert.equal(account.getAccount().state, before)
  assert.equal(account.providerStatus(p.id), null)
  assert.equal(hub.getSession('rm-term')!.blocked?.kind, 'provider')
  // Still removed a while later (nothing clears it but a move).
  hub.reviewBlocked()
  assert.equal(hub.getSession('rm-sdk')!.blocked?.kind, 'provider')
  // A new session on it is refused, and the SDK session's next prompt goes nowhere.
  assert.throws(() => runner.createSession({ cwd: scratch, providerId: p.id, prompt: 'hello' }), (e: any) => e.status === 404 && e.code === 'no_provider')
  hub.upsertSession('rm-sdk', { blocked: null })
  runner.sendPrompt('rm-sdk', 'carry on')
  await until(() => hub.getSession('rm-sdk')!.blocked?.kind === 'provider')
  const evs = hub.eventsFor('rm-sdk')
  assert.deepEqual(evs.slice(-2).map((e: any) => [e.kind, e.kind === 'notice' ? e.text : e.text]), [['user', 'carry on'], ['notice', `Not sent. ${hubStore.REMOVED_TEXT}`]])
  assert.equal(hub.getSession('rm-sdk')!.state, 'idle')
  assert.equal(account.getAccount().state, before)
})
