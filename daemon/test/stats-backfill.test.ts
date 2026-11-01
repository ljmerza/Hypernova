// Statistics' older days (docs/PROTOCOL.md, "Statistics", "Older days"): Claude Code's own summary
// (stats-cache.json), only for days before Hypernova counted any. Every file here is a scratch one,
// written by hand in the shape Claude Code's has; none is copied from a real one.
import { test, after, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

process.env.TZ = 'UTC'
const scratch = mkdtempSync(join(tmpdir(), 'hypernova-test-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
mkdirSync(process.env.HOME, { recursive: true })

const stats = await import('../src/stats.ts')
const { parseCache } = await import('../src/stats-cache.ts')
after(() => rmSync(scratch, { recursive: true, force: true }))

const CACHE = join(scratch, 'claude', 'stats-cache.json')
const dir = join(scratch, 'claude', 'projects', '-home-me-work')
mkdirSync(dir, { recursive: true })
const NOW = Date.parse('2026-10-15T18:00:00Z')
const midnight = (d: string) => Date.parse(`${d}T00:00:00Z`)

// Hypernova's first counted day is 24 September: a transcript of 5 minutes, one session.
const sid = '11111111-1111-4111-8111-111111111111'
const base = { cwd: '/home/me/work', sessionId: sid }
writeFileSync(join(dir, `${sid}.jsonl`), [
  { ...base, type: 'user', uuid: 'p', timestamp: '2026-09-24T09:00:00Z', message: { role: 'user', content: 'Work' } },
  { ...base, type: 'assistant', uuid: 'r', timestamp: '2026-09-24T09:05:00Z', message: { id: 'm', role: 'assistant', model: 'claude-test', content: [{ type: 'tool_use', id: 't', name: 'Bash', input: { command: 'ls' } }], usage: { input_tokens: 1, output_tokens: 1, cache_read_input_tokens: 0, cache_creation_input_tokens: 0 } } },
].map((l) => JSON.stringify(l) + '\n').join(''))

const row = (date: string, sessionCount: number, toolCallCount: number, messageCount = 100) => ({ date, sessionCount, toolCallCount, messageCount })
const tok = (date: string, ...each: number[]) => ({ date, tokensByModel: Object.fromEntries(each.map((n, i) => [`model-${i}`, n])) })
const file = {
  version: 5, lastComputedDate: '2026-10-16', dailyModelTokensVersion: 5, firstSessionDate: '2025-12-30T02:48:03.853Z',
  totalSessions: 99_999, totalMessages: 99_999, hourCounts: { 0: 1 }, modelUsage: { 'secret-model': { inputTokens: 1 } },
  longestSession: { sessionId: 'the-longest-session-id', timestamp: 'x', duration: 1, messageCount: 1 },
  dailyActivity: [
    row('2025-12-30', 1000, 10000), // last year: All's, not YTD's.
    row('2026-01-01', 1, 10), // January 1 itself is YTD's.
    row('2026-07-12', 2, 20),
    row('2026-09-20', 3, 30), // Within Month's 30 days of NOW, and Hypernova has nothing that early.
    row('2026-09-23', 4, 40), // The last day before Hypernova's first.
    row('2026-09-24', 500, 5000), // Partly counted: Hypernova saw one session of its 500. Hypernova's stands.
    row('2026-09-25', 600, 6000), // Past Hypernova's first, even though it has nothing that day.
    row('2026-10-14', 700, 7000),
  ],
  dailyModelTokens: [tok('2026-07-12', 100, 50), tok('2026-09-20', 1000), tok('2026-09-23', 2000), tok('2026-09-24', 70000), tok('2026-10-14', 80000)],
}
const put = (o: unknown) => writeFileSync(CACHE, typeof o === 'string' ? o : JSON.stringify(o))
const ask = (range: 'today' | 'week' | 'month' | 'ytd' | 'all', now = NOW) => stats.getStats(range, now, { fresh: true })
beforeEach(() => put(file))

test('All: only the days before Hypernova\'s first, added up day by day, apart from its own totals', async () => {
  const s = await ask('all')
  assert.deepEqual(s.backfill, {
    since: midnight('2025-12-30'),
    hoursSince: midnight('2026-09-24'),
    sessions: 1000 + 1 + 2 + 3 + 4,
    toolCalls: 10000 + 10 + 20 + 30 + 40,
    tokens: 150 + 1000 + 2000,
    tokensSince: midnight('2026-07-12'),
  })
  // The file's own totals aren't used; every top-level number is Hypernova's, so an older app reads what it did.
  assert.equal(s.sessions, 1)
  assert.equal(s.toolCalls, 1)
  assert.equal(s.tokens.input + s.tokens.output, 2)
  assert.equal(s.since, midnight('2026-09-24'))
  assert.equal(s.from, midnight('2026-09-24'))
})

test('a day partly counted is Hypernova\'s whole, and so is every day after it', async () => {
  const { backfill: b } = await ask('all')
  // Nothing from 24 and 25 September or 14 October, though the file has far more for them.
  assert.equal(b!.sessions, 1010)
  assert.equal(b!.tokens, 3150)
})

test('what\'s in hours has no older days to claim: busiest day, longest session, streak, projects', async () => {
  const s = await ask('all')
  assert.equal(s.busiestDay?.day, '2026-09-24')
  assert.equal(s.longestSession?.id, sid)
  assert.equal(s.streak, 0)
  assert.deepEqual(s.projects.map((p) => p.name), ['work'])
  assert.equal(s.prompts, 1, 'messages are never prompts')
  assert.equal(Math.round(s.claudeMs / 60_000), 5)
})

test('YTD: January 1 cuts the older days too', async () => {
  const s = await ask('ytd')
  assert.equal(s.from, midnight('2026-01-01'))
  assert.equal(s.backfill?.sessions, 1 + 2 + 3 + 4)
  assert.equal(s.backfill?.toolCalls, 10 + 20 + 30 + 40)
  assert.equal(s.backfill?.tokensSince, midnight('2026-07-12'))
  // Next year none of them are in it.
  assert.equal((await ask('ytd', Date.parse('2027-01-05T12:00:00Z'))).backfill, undefined)
})

test('Today and Week have nothing to add; Month only the days the file alone knows, with no bar for them', async () => {
  assert.equal((await ask('today')).backfill, undefined)
  assert.equal((await ask('week')).backfill, undefined)
  const m = await ask('month')
  assert.equal(m.backfill?.sessions, 3 + 4)
  assert.equal(m.backfill?.tokens, 3000)
  assert.equal(m.backfill?.tokensSince, midnight('2026-09-20'))
  const day = m.days.find((d) => d.day === '2026-09-20')!
  assert.deepEqual([day.claudeMs, day.sessions, day.tokens, day.prompts], [0, 3, 1000, 0])
  // A day of Hypernova's keeps its own numbers.
  assert.equal(m.days.find((d) => d.day === '2026-10-14')!.sessions, 0)
  assert.equal(m.days.filter((d) => d.claudeMs > 0).length, 1)
})

test('with no day of Hypernova\'s yet, the file\'s are all there is, and it never reads the future', async () => {
  const before = Date.parse('2026-09-23T12:00:00Z')
  const s = await ask('all', before)
  assert.equal(s.since, null)
  assert.equal(s.backfill?.hoursSince, midnight('2026-09-23'))
  assert.equal(s.backfill?.sessions, 1000 + 1 + 2 + 3 + 4)
})

test('without tokens the days still stand; tokens without activity are still that day\'s', async () => {
  const { dailyModelTokens: _, ...noTokens } = file
  put(noTokens)
  const a = await ask('all')
  assert.equal(a.backfill?.sessions, 1010)
  assert.equal(a.backfill?.tokens, 0)
  assert.equal(a.backfill?.tokensSince, null)
  put({ ...file, dailyModelTokensVersion: 99 })
  assert.equal((await ask('all')).backfill?.tokensSince, null)
  put({ ...file, dailyModelTokens: [{ date: '2026-07-12', tokensByModel: { a: 'many' } }] })
  assert.equal((await ask('all')).backfill?.tokens, 0)
  put({ ...file, dailyModelTokens: [tok('2026-02-02', 7)] })
  assert.equal((await ask('all')).backfill?.tokens, 7)
})

test('a missing, unknown or changed file is no older days, said once, and the answer is as ever', async () => {
  const logged = () => { try { return readFileSync(join(scratch, 'logs', 'daemon.log'), 'utf8').split('\n').filter((l) => l.includes('stats-cache.json')).length } catch { return 0 } }
  rmSync(CACHE)
  const was = logged()
  assert.equal((await ask('all')).backfill, undefined)
  assert.equal((await ask('all')).backfill, undefined)
  assert.equal(logged(), was + 1)
  for (const bad of ['{ not json', [], { ...file, version: 6 }, { ...file, version: undefined }, { ...file, dailyActivity: 'x' }, { ...file, dailyActivity: [row('January', 1, 1)] }, { ...file, dailyActivity: [{ date: '2026-01-01', sessions: 1 }] }, { ...file, dailyActivity: [row('2026-01-01', -1, 1)] }]) {
    put(bad)
    const s = await ask('all')
    assert.equal(s.backfill, undefined, JSON.stringify(bad).slice(0, 60))
    assert.equal(s.sessions, 1)
  }
})

test('only counts leave, and the file stays as it was', async () => {
  const at = statSync(CACHE).mtimeMs
  const text = readFileSync(CACHE, 'utf8')
  const said = JSON.stringify(await ask('all'))
  for (const never of ['the-longest-session-id', 'secret-model', 'firstSessionDate', 'hourCounts', 'modelUsage']) assert.ok(!said.includes(never), never)
  assert.equal(readFileSync(CACHE, 'utf8'), text)
  assert.equal(statSync(CACHE).mtimeMs, at)
})

test('the parser, on its own: sorted, and the reason when it says no', () => {
  const days = parseCache(JSON.stringify({ version: 5, dailyActivity: [row('2026-02-02', 1, 1), row('2026-01-01', 2, 2)] }))
  assert.deepEqual((days as { day: string }[]).map((d) => d.day), ['2026-01-01', '2026-02-02'])
  assert.equal(parseCache('nope'), 'not JSON')
  assert.equal(parseCache('{"version":6}'), 'version 6')
})
