// Statistics (docs/PROTOCOL.md, "Statistics"): Claude's working time, tokens, sessions, prompts,
// tools, files and agents from transcripts, read incrementally; what only the daemon counts; days
// kept after Claude Code deletes their files; and the phone's route. Every fixture is written by
// hand in the shapes Claude Code 2.1.299 records; none is copied from a real transcript.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { appendFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

// Days are the Mac's own: a fixed zone makes them the same on any machine.
process.env.TZ = 'UTC'
const scratch = mkdtempSync(join(tmpdir(), 'hypernova-test-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
mkdirSync(process.env.HOME, { recursive: true })
const fakeClaude = join(scratch, 'claude-bin')
writeFileSync(fakeClaude, '#!/bin/sh\necho "9.9.9 (Claude Code)"\n', { mode: 0o755 })
process.env.CLAUDE_BIN = fakeClaude

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
// The LAN listener (lan.ts) takes a free port: test files run side by side.
process.env.HYPERNOVA_LAN_PORT = '0'

const stats = await import('../src/stats.ts')
const { addDevice } = await import('../src/store.ts')
const { startServers } = await import('../src/http.ts')
after(() => rmSync(scratch, { recursive: true, force: true }))

// ── Fixture helpers ──────────────────────────────────────────────────────
const PROJECTS = join(scratch, 'claude', 'projects')
const MIN = 60_000
const at = (iso: string) => new Date(iso).toISOString()
const line = (o: object) => JSON.stringify(o) + '\n'
const S1 = '11111111-1111-4111-8111-111111111111'
const S2 = '22222222-2222-4222-8222-222222222222'
const FORK = '33333333-3333-4333-8333-333333333333'
const S4 = '44444444-4444-4444-8444-444444444444'
const AGENT = 'a0123456789abcdef'
const WF_AGENT = 'a1111111111111111'

const prompt = (uuid: string, ts: string, text: string, extra: object = {}) => ({ type: 'user', uuid, timestamp: at(ts), cwd: '/Users/me/Programming/hypernova', sessionId: S1, message: { role: 'user', content: text }, ...extra })
const usage = (input: number, output: number, cacheRead = 0, cacheWrite = 0) => ({ input_tokens: input, output_tokens: output, cache_read_input_tokens: cacheRead, cache_creation_input_tokens: cacheWrite })
const reply = (uuid: string, ts: string, msgId: string, blocks: object[], u = usage(10, 100, 1000, 50)) => ({
  type: 'assistant', uuid, timestamp: at(ts), cwd: '/Users/me/Programming/hypernova', sessionId: S1,
  message: { id: msgId, role: 'assistant', model: 'claude-test', content: blocks, usage: u },
})
const text = (t: string) => ({ type: 'text', text: t })
const tool = (id: string, name: string, input: object) => ({ type: 'tool_use', id, name, input })
const result = (uuid: string, ts: string, toolUseId: string, out = 'ok') => ({
  type: 'user', uuid, timestamp: at(ts), sessionId: S1,
  message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: toolUseId, content: out }] }, toolUseResult: { stdout: out },
})

function write(dir: string, name: string, entries: object[]): string {
  mkdirSync(dir, { recursive: true })
  const p = join(dir, name)
  writeFileSync(p, entries.map(line).join(''))
  return p
}

// Session 1, 14 October, in hypernova: three turns.
//  09:00 → 09:05 (5 min), 10:00 → 10:20 (20 min, one message logged as two lines), and 12:00 →
//  12:01, a two-hour pause (a usage limit), 14:00 → 14:05: 6 min, the pause not counted.
const pdir = join(PROJECTS, '-Users-me-Programming-hypernova')
const s1Entries = [
  { type: 'permission-mode', permissionMode: 'default', sessionId: S1 },
  prompt('p1', '2026-10-14T09:00:00Z', 'Fix the parser'),
  reply('r1', '2026-10-14T09:00:30Z', 'msg_1', [tool('t1', 'Bash', { command: 'npm test' })]),
  result('u1', '2026-10-14T09:02:00Z', 't1'),
  reply('r2', '2026-10-14T09:05:00Z', 'msg_2', [text('Fixed.')]),
  { type: 'system', subtype: 'turn_duration', durationMs: 300000, timestamp: at('2026-10-14T09:05:00.100Z'), uuid: 'd1', sessionId: S1 },
  { type: 'ai-title', aiTitle: 'Fix the parser and the export', sessionId: S1 },
  prompt('p2', '2026-10-14T10:00:00Z', 'Now the export'),
  reply('r3', '2026-10-14T10:10:00Z', 'msg_3', [{ type: 'thinking', thinking: '' }]),
  reply('r4', '2026-10-14T10:10:01Z', 'msg_3', [tool('t2', 'Edit', { file_path: '/Users/me/Programming/hypernova/src/a.ts', old_string: 'a', new_string: 'b' })]),
  reply('r5', '2026-10-14T10:10:02Z', 'msg_3', [tool('t3', 'Write', { file_path: '/Users/me/Programming/hypernova/src/b.ts', content: 'x' })]),
  result('u2', '2026-10-14T10:11:00Z', 't2'),
  result('u3', '2026-10-14T10:11:01Z', 't3'),
  reply('r6', '2026-10-14T10:20:00Z', 'msg_4', [text('Done.'), tool('t4', 'mcp__github__create_issue', {})]),
  prompt('p3', '2026-10-14T12:00:00Z', '/hyper-cosmos make a page'.replace('/hyper-cosmos make a page', '<command-message>hyper-cosmos</command-message>\n<command-name>/hyper-cosmos</command-name>\n<command-args>make a page</command-args>')),
  reply('r7', '2026-10-14T12:01:00Z', 'msg_5', [text('Usage limit reached.')]),
  reply('r8', '2026-10-14T14:00:00Z', 'msg_6', [tool('t5', 'Edit', { file_path: '/Users/me/Programming/hypernova/src/a.ts', old_string: 'b', new_string: 'c' })]),
  result('u4', '2026-10-14T14:00:30Z', 't5'),
  reply('r9', '2026-10-14T14:05:00Z', 'msg_7', [text('Page made.')]),
  // Not prompts: a built-in command, its output, an interrupt, a task notification.
  prompt('c1', '2026-10-14T14:06:00Z', '<command-name>/effort</command-name>\n<command-message>effort</command-message>\n<command-args>high</command-args>'),
  prompt('c2', '2026-10-14T14:06:01Z', '<local-command-stdout>Effort set to high</local-command-stdout>'),
  prompt('c3', '2026-10-14T14:06:02Z', '[Request interrupted by user]'),
  prompt('c4', '2026-10-14T14:06:03Z', '<task-notification>\n<task-id>x</task-id>\n<status>completed</status>\n</task-notification>', { promptSource: 'system' }),
]

// Session 2, the same morning in another project, overlapping session 1: 09:02 → 09:08.
const odir = join(PROJECTS, '-Users-me-Programming-0stems')
const s2Entries = [
  { ...prompt('q1', '2026-10-14T09:02:00Z', 'Tune the grain'), cwd: '/Users/me/Programming/0stems', sessionId: S2 },
  { ...reply('qr1', '2026-10-14T09:04:00Z', 'msg_q1', [tool('qt1', 'Read', { file_path: '/x' })], usage(5, 50)), cwd: '/Users/me/Programming/0stems' },
  result('qu1', '2026-10-14T09:05:00Z', 'qt1'),
  { ...reply('qr2', '2026-10-14T09:08:00Z', 'msg_q2', [text('Tuned.')], usage(5, 50)), cwd: '/Users/me/Programming/0stems' },
]

// An agent of session 1, 10:05 → 10:30: inside the session's own turn until 10:20, then 10 more
// minutes. A workflow's agent, 15 October.
const agentEntries = [
  { ...prompt('ap1', '2026-10-14T10:05:00Z', 'Review the parser', { isSidechain: true, agentId: AGENT }) },
  { ...reply('ar1', '2026-10-14T10:15:00Z', 'msg_a1', [tool('at1', 'Grep', { pattern: 'x' })], usage(1, 10)), isSidechain: true },
  result('au1', '2026-10-14T10:16:00Z', 'at1'),
  { ...reply('ar2', '2026-10-14T10:30:00Z', 'msg_a2', [text('Looks right.')], usage(1, 10)), isSidechain: true },
]
const wfAgentEntries = [
  { ...prompt('wp1', '2026-10-15T08:00:00Z', 'survey', { isSidechain: true }) },
  { ...reply('wr1', '2026-10-15T08:03:00Z', 'msg_w1', [text('Surveyed.')], usage(1, 10)), isSidechain: true },
]

// A fork of session 1: its first turn copied (same uuids and message ids), then a turn of its own
// on 15 October, 11:00 → 11:04, with a prompt typed while Claude worked (handed over mid-turn).
const forkEntries = [
  ...s1Entries.slice(1, 6).map((e: any) => ({ ...e, sessionId: FORK })),
  prompt('f1', '2026-10-15T11:00:00Z', 'Continue from the phone', { sessionId: FORK }),
  reply('fr1', '2026-10-15T11:01:00Z', 'msg_f1', [tool('ft1', 'Bash', { command: 'ls' })]),
  { type: 'attachment', uuid: 'fa1', timestamp: at('2026-10-15T11:02:00Z'), sessionId: FORK, attachment: { type: 'queued_command', prompt: 'and run the tests', source_uuid: 'fq1', origin: { kind: 'human' }, commandMode: 'prompt' } },
  result('fu1', '2026-10-15T11:02:30Z', 'ft1'),
  reply('fr2', '2026-10-15T11:04:00Z', 'msg_f2', [text('All green.')]),
]

// A huge tool result (a file read): its time counts, its text is never parsed.
const huge = 'x'.repeat(200 * 1024)

const NOW = Date.parse('2026-10-15T18:00:00Z')

before(async () => {
  write(pdir, `${S1}.jsonl`, s1Entries)
  write(odir, `${S2}.jsonl`, s2Entries)
  write(join(pdir, S1, 'subagents'), `agent-${AGENT}.jsonl`, agentEntries)
  writeFileSync(join(pdir, S1, 'subagents', `agent-${AGENT}.meta.json`), '{"agentType":"Explore"}')
  write(join(pdir, S1, 'subagents', 'workflows', 'wf_abc-123'), `agent-${WF_AGENT}.jsonl`, wfAgentEntries)
  writeFileSync(join(pdir, S1, 'subagents', 'workflows', 'wf_abc-123', 'journal.jsonl'), line({ type: 'started' }))
  // The fork is made later than the session it copies, as it would be.
  await new Promise((r) => setTimeout(r, 30))
  write(pdir, `${FORK}.jsonl`, forkEntries)
})

const h = (ms: number) => Math.round(ms / MIN)

test('Claude hours: prompt to turn end, long pauses left out, overlapping sessions summed; wall hours counted once', async () => {
  const s = await stats.getStats('all', NOW, { fresh: true })
  const d14 = s.days.find((d) => d.day === '2026-10-14')!
  // Session 1: 5 + (its turn 10:00–10:20 joined with its agent's 10:05–10:30 = 30) + 1 + 5 = 41.
  // Session 2: 6. The fork's copy of session 1's first turn adds nothing.
  assert.equal(h(d14.claudeMs), 41 + 6)
  // 09:00–09:08 counted once: 8, then 30, 1, 5.
  assert.equal(h(d14.wallMs), 8 + 30 + 1 + 5)
  const d15 = s.days.find((d) => d.day === '2026-10-15')!
  // The fork's own turn (4) and the workflow agent (3).
  assert.equal(h(d15.claudeMs), 4 + 3)
  // Each agent's own time: 25 and 3.
  assert.equal(h(s.agentMs), 25 + 3)
  assert.equal(s.agents, 2)
})

test('tokens count each message once, also when a fork copies it', async () => {
  const s = await stats.getStats('all', NOW)
  // Session 1: 7 messages; session 2: 2; agents: 3; the fork: 2 of its own (its copies of msg_1
  // and msg_2 aren't counted again).
  const big = 7 + 2 // messages with usage(10, 100, 1000, 50)
  assert.equal(s.tokens.output, big * 100 + 2 * 50 + 3 * 10)
  assert.equal(s.tokens.input, big * 10 + 2 * 5 + 3 * 1)
  assert.equal(s.tokens.cacheRead, big * 1000)
  assert.equal(s.tokens.cacheWrite, big * 50)
})

test('prompts, sessions, tools and files', async () => {
  const s = await stats.getStats('all', NOW)
  // Session 1: three (a skill command counts; /effort, its output, an interrupt and a task
  // notification don't); session 2: one; the fork: its own and the one handed over mid-turn.
  assert.equal(s.prompts, 3 + 1 + 2)
  assert.equal(s.sessions, 3)
  const tools = Object.fromEntries(s.tools.map((t) => [t.name, t.count]))
  assert.deepEqual(tools, { Bash: 2, Edit: 2, Write: 1, MCP: 1, Read: 1, Grep: 1 })
  assert.equal(s.toolCalls, 8)
  // a.ts twice and b.ts: two files.
  assert.equal(s.filesEdited, 2)
})

test('records: busiest day, longest session, streak, top projects', async () => {
  const s = await stats.getStats('all', NOW)
  assert.equal(s.busiestDay?.day, '2026-10-14')
  assert.equal(s.longestSession?.id, S1)
  assert.equal(s.longestSession?.title, 'Fix the parser and the export')
  assert.equal(s.longestSession?.project, 'hypernova')
  // 41 minutes on the 14th, and its workflow agent's 3 on the 15th.
  assert.equal(h(s.longestSession!.claudeMs), 44)
  assert.equal(s.streak, 2)
  assert.deepEqual(s.projects.map((p) => p.name), ['hypernova', '0stems'])
  assert.equal(s.projects[0]!.path, '/Users/me/Programming/hypernova')
  assert.equal(s.projects[0]!.sessions, 2)
  assert.equal(s.since, Date.parse('2026-10-14T00:00:00Z'))
})

test('ranges: today is 15 October, the week and month hold both days, 30 days for the galaxy', async () => {
  const today = await stats.getStats('today', NOW)
  assert.equal(today.from, Date.parse('2026-10-15T00:00:00Z'))
  assert.equal(h(today.claudeMs), 7)
  assert.equal(today.sessions, 1)
  assert.equal(today.longestSession?.id, FORK)
  const week = await stats.getStats('week', NOW)
  assert.equal(week.from, Date.parse('2026-10-09T00:00:00Z'))
  assert.equal(h(week.claudeMs), 47 + 7)
  assert.equal(week.days.length, 30)
  assert.equal(week.days.at(-1)!.day, '2026-10-15')
  assert.equal(week.days[0]!.day, '2026-09-16')
  assert.equal(stats.parseRange('year'), null)
})

test('a busy line too long to parse still counts its time', async () => {
  const p = join(odir, `${S4}.jsonl`)
  writeFileSync(p, [
    { ...prompt('h1', '2026-10-15T15:00:00Z', 'Read the big file'), sessionId: S4, cwd: '/Users/me/Programming/0stems' },
    reply('hr1', '2026-10-15T15:01:00Z', 'msg_h1', [tool('ht1', 'Read', { file_path: '/big' })], usage(1, 1)),
    { type: 'user', uuid: 'hu1', timestamp: at('2026-10-15T15:09:00Z'), sessionId: S4, message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'ht1', content: huge }] }, toolUseResult: { file: { content: huge } } },
  ].map(line).join(''))
  const s = await stats.getStats('today', NOW, { fresh: true })
  assert.equal(h(s.claudeMs), 7 + 9)
})

test('new lines are read on their own; a line still being written waits for its end', async () => {
  const p = join(odir, `${S4}.jsonl`)
  const next = line(reply('hr2', '2026-10-15T15:12:00Z', 'msg_h2', [text('Read it.')], usage(1, 1)))
  appendFileSync(p, next.slice(0, 40))
  let s = await stats.getStats('today', NOW, { fresh: true })
  assert.equal(h(s.claudeMs), 7 + 9)
  appendFileSync(p, next.slice(40))
  s = await stats.getStats('today', NOW, { fresh: true })
  assert.equal(h(s.claudeMs), 7 + 12)
  // A file rewritten shorter is read again from its start.
  writeFileSync(p, [{ ...prompt('h1', '2026-10-15T15:00:00Z', 'Read the big file'), sessionId: S4 }, reply('hr1', '2026-10-15T15:02:00Z', 'msg_h1', [text('Short.')], usage(1, 1))].map(line).join(''))
  s = await stats.getStats('today', NOW, { fresh: true })
  assert.equal(h(s.claudeMs), 7 + 2)
})

test('answers are reused for a moment rather than reading the files on every ask', async () => {
  const a = await stats.getStats('week', NOW, { fresh: true })
  const b = await stats.getStats('week', NOW + 1000)
  assert.equal(a.computedAt, b.computedAt)
  const c = await stats.getStats('week', NOW + 20_000)
  assert.ok(c.computedAt > a.computedAt)
})

test('what only the daemon counts: prompts from the phone and the glasses, spoken ones, approvals, limits', async () => {
  addDevice({ id: 'phone1', name: 'Phone', model: 'NP3', createdAt: 0, lastSeenAt: 0 }, 'phone-token-0123456789abcdef0123')
  addDevice({ id: 'lens1', name: 'Glasses', model: 'G2', createdAt: 0, lastSeenAt: 0, client: 'lens' }, 'lens-token-0123456789abcdef01234')
  stats.notePrompt('phone1', false)
  stats.notePrompt('phone1', false, 'voice')
  stats.notePrompt('lens1', false, 'voice')
  // The Mac app's own prompts are in the transcripts already.
  stats.notePrompt(null, true)
  stats.noteApproval('phone1', false)
  stats.noteApproval('lens1', false)
  stats.noteApproval(null, true)
  // Ten minutes ago, but never before midnight: run just after it, that would be yesterday.
  assert.equal(stats.recordLimitHit('five_hour', Math.max(Date.now() - 10 * MIN, new Date().setUTCHours(0, 0, 0, 0))), true)
  // The same limit, reported again.
  assert.equal(stats.recordLimitHit('five_hour', Date.now()), false)
  assert.equal(stats.recordLimitHit('seven_day', Date.now()), true)
  // Credit running out isn't a limit reached.
  assert.equal(stats.recordLimitHit('credits', Date.now()), false)
  const s = await stats.getStats('today')
  assert.deepEqual(s.promptsFrom, { phone: 2, glasses: 1, voice: 2 })
  assert.deepEqual(s.approvals, { total: 3, phone: 1, glasses: 1, mac: 1 })
  assert.deepEqual(s.limitsHit, { fiveHour: 1, sevenDay: 1, model: 0 })
  // Kept across a restart.
  stats.flushStatsForTest()
  stats.reloadStatsForTest()
  const again = await stats.getStats('today')
  assert.deepEqual(again.promptsFrom, { phone: 2, glasses: 1, voice: 2 })
  assert.ok(again.countingSince <= Date.now())
})

test('a finished day still counts once Claude Code has deleted its transcripts', async () => {
  const before = await stats.getStats('all', NOW, { fresh: true })
  const d14 = before.days.find((d) => d.day === '2026-10-14')!
  stats.flushStatsForTest()
  const saved = JSON.parse(readFileSync(join(scratch, 'support', 'stats.json'), 'utf8'))
  assert.ok(saved.days['2026-10-14'], 'the 14th is kept')
  rmSync(join(odir, `${S2}.jsonl`))
  rmSync(join(pdir, `${S1}.jsonl`))
  stats.resetStatsForTest()
  const s = await stats.getStats('all', NOW, { fresh: true })
  const kept = s.days.find((d) => d.day === '2026-10-14')!
  assert.equal(kept.claudeMs, d14.claudeMs)
  assert.equal(kept.tokens, d14.tokens)
  assert.equal(s.longestSession?.id, S1)
  assert.equal(s.filesEdited, before.filesEdited)
})

// ── The phone's route ────────────────────────────────────────────────────
const TOKEN = 'stats-device-token-0123456789abcdef'
function get(path: string, token = TOKEN): Promise<{ status: number; body: any }> {
  return new Promise((resolve, reject) => {
    const req = request({ host: '127.0.0.1', port: API, method: 'GET', path, headers: { authorization: `Bearer ${token}` } }, (res) => {
      let data = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (data += c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, body: JSON.parse(data || '{}') }))
    })
    req.on('error', reject)
    req.end()
  })
}

test('GET /v1/stats answers a paired phone, refuses others and unknown ranges', async () => {
  addDevice({ id: 'stats-phone', name: 'Phone', model: 'NP3', createdAt: 0, lastSeenAt: 0 }, TOKEN)
  const close = startServers()
  try {
    for (let i = 0; i < 50; i++) {
      try {
        if ((await get('/v1/health')).status === 200) break
      } catch {}
      await new Promise((r) => setTimeout(r, 20))
    }
    const ok = await get('/v1/stats?range=month')
    assert.equal(ok.status, 200)
    assert.equal(ok.body.range, 'month')
    assert.equal(ok.body.days.length, 30)
    for (const k of ['claudeMs', 'wallMs', 'agentMs', 'tokens', 'sessions', 'prompts', 'promptsFrom', 'toolCalls', 'tools', 'filesEdited', 'agents', 'projects', 'approvals', 'limitsHit', 'busiestDay', 'longestSession', 'streak', 'since', 'countingSince']) assert.ok(k in ok.body, k)
    const plain = await get('/v1/stats')
    assert.equal(plain.body.range, 'week')
    const bad = await get('/v1/stats?range=year')
    assert.equal(bad.status, 400)
    assert.equal(bad.body.error.code, 'bad_range')
    assert.equal((await get('/v1/stats', 'not-a-token')).status, 401)
  } finally {
    await close()
  }
})

// ── Limits reached ───────────────────────────────────────────────────────
test('which limit a Claude Code reply says was reached', () => {
  assert.deepEqual(stats.limitOf("You've hit your session limit · resets 4pm (Europe/Moscow)"), { window: 'five_hour', reset: 'session limit · 4pm (Europe/Moscow)' })
  assert.equal(stats.limitOf('You’ve reached your weekly limit · resets Oct 20, 3pm')?.window, 'seven_day')
  assert.equal(stats.limitOf("You've hit your Opus limit · resets Oct 20, 3pm (Europe/Moscow)")?.window, 'model')
  assert.equal(stats.limitOf('Claude AI usage limit reached|1792000000')?.window, 'five_hour')
  // Credit and spend caps, a 429 that names no quota, other errors: not limits reached.
  assert.equal(stats.limitOf("You've hit your monthly spend limit"), null)
  assert.equal(stats.limitOf("You're out of usage credits"), null)
  assert.equal(stats.limitOf('API Error: 429 {"type":"error","error":{"type":"rate_limit_error"}}'), null)
  assert.equal(stats.limitOf('Please run /login · API Error: 403 Request not allowed'), null)
})

test('limits reached: from the transcripts, one per limit however many sessions hit it, the account\'s merged in', async () => {
  const S5 = '55555555-5555-4555-8555-555555555555'
  const S6 = '66666666-6666-4666-8666-666666666666'
  const before = await stats.getStats('all', NOW, { fresh: true })
  const err = (uuid: string, ts: string, t: string, sid: string) => ({
    type: 'assistant', uuid, timestamp: at(ts), sessionId: sid, cwd: '/Users/me/Programming/0stems', error: 'rate_limit', isApiErrorMessage: true,
    message: { id: `err-${uuid}`, role: 'assistant', model: '<synthetic>', content: [text(t)], usage: usage(0, 0) },
  })
  const A = "You've hit your session limit · resets 3pm (Europe/Moscow)"
  write(odir, `${S5}.jsonl`, [
    { ...prompt('l1', '2026-10-15T09:00:00Z', 'Polish the grain'), sessionId: S5, cwd: '/Users/me/Programming/0stems' },
    err('le1', '2026-10-15T09:00:05Z', A, S5),
    // Tried again half an hour later: the same limit.
    { ...prompt('l2', '2026-10-15T09:30:00Z', 'Try again'), sessionId: S5, cwd: '/Users/me/Programming/0stems' },
    err('le2', '2026-10-15T09:30:02Z', A, S5),
    // After the reset, the next window's limit: another one.
    { ...prompt('l3', '2026-10-15T12:10:00Z', 'Go on'), sessionId: S5, cwd: '/Users/me/Programming/0stems' },
    err('le3', '2026-10-15T12:10:03Z', "You've hit your session limit · resets 5pm (Europe/Moscow)", S5),
    err('le4', '2026-10-15T12:20:00Z', "You've hit your weekly limit · resets Oct 20, 3pm (Europe/Moscow)", S5),
    err('le5', '2026-10-15T12:21:00Z', "You've hit your Opus limit · resets Oct 20, 3pm (Europe/Moscow)", S5),
    err('le6', '2026-10-15T12:22:00Z', "You're out of usage credits · Add more to keep going", S5),
    err('le7', '2026-10-15T12:23:00Z', 'API Error: 429 {"type":"error","error":{"type":"rate_limit_error"}}', S5),
  ])
  // Another session hit by the same 5-hour limit an hour in: still one.
  write(odir, `${S6}.jsonl`, [
    { ...prompt('m1', '2026-10-15T10:00:00Z', 'Tune the stars'), sessionId: S6, cwd: '/Users/me/Programming/0stems' },
    err('me1', '2026-10-15T10:00:04Z', A, S6),
  ])
  let s = await stats.getStats('all', NOW, { fresh: true })
  const d = (k: 'fiveHour' | 'sevenDay' | 'model') => s.limitsHit[k] - before.limitsHit[k]
  assert.equal(d('fiveHour'), 2)
  assert.equal(d('sevenDay'), 1)
  assert.equal(d('model'), 1)
  // The account reports the first of them (after the merge, account.ts calls this as a limit
  // begins): it's the same limit, so nothing more counts.
  assert.equal(stats.recordLimitHit('five_hour', Date.parse('2026-10-15T09:01:00Z'), Date.parse('2026-10-15T12:00:00Z')), true)
  s = await stats.getStats('all', NOW)
  assert.equal(d('fiveHour'), 2)
  // One the transcripts don't show (a session on a provider whose files are gone) counts.
  assert.equal(stats.recordLimitHit('five_hour', Date.parse('2026-10-15T16:30:00Z'), Date.parse('2026-10-15T20:00:00Z')), true)
  s = await stats.getStats('all', NOW)
  assert.equal(d('fiveHour'), 3)
})
