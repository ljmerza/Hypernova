// Statistics' YTD range (docs/PROTOCOL.md, "Statistics"): from January 1 of the server's own year,
// as Today, Week and Month go by its own calendar. A zone with DST keeps the local-time rule honest:
// a 24-hour sum back from July lands an hour off January's midnight, and a UTC cut lands 5 hours off.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

process.env.TZ = 'America/New_York'
const scratch = mkdtempSync(join(tmpdir(), 'hypernova-test-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
mkdirSync(process.env.HOME, { recursive: true })

const stats = await import('../src/stats.ts')
after(() => rmSync(scratch, { recursive: true, force: true }))

const MIN = 60_000
const dir = join(scratch, 'claude', 'projects', '-home-me-work')
mkdirSync(dir, { recursive: true })
const local = (y: number, mo: number, d: number, h = 0, m = 0) => new Date(y, mo - 1, d, h, m).getTime()
const id = (n: number) => `${String(n).repeat(8)}-1111-4111-8111-111111111111`

/** A session [n] whose one turn runs through [times]: a prompt, then a reply at each of the rest. */
function session(n: number, times: number[]): void {
  const sid = id(n)
  const base = { cwd: '/home/me/work', sessionId: sid }
  const lines = times.map((t, i) => i === 0
    ? { ...base, type: 'user', uuid: `p${n}`, timestamp: new Date(t).toISOString(), message: { role: 'user', content: 'Work' } }
    : { ...base, type: 'assistant', uuid: `r${n}-${i}`, timestamp: new Date(t).toISOString(), message: { id: `m${n}-${i}`, role: 'assistant', model: 'claude-test', content: [{ type: 'text', text: 'ok' }], usage: { input_tokens: 1, output_tokens: 1, cache_read_input_tokens: 0, cache_creation_input_tokens: 0 } } })
  writeFileSync(join(dir, `${sid}.jsonl`), lines.map((l) => JSON.stringify(l) + '\n').join(''))
}

// Across New Year's Eve 2025: 10 minutes on the 31st and 20 on January 1. In UTC it is all January 1.
session(1, [local(2025, 12, 31, 23, 50), local(2026, 1, 1, 0, 0), local(2026, 1, 1, 0, 10), local(2026, 1, 1, 0, 20)])
// March 2026: 5 minutes, this year's and no other's.
session(2, [local(2026, 3, 10, 9, 0), local(2026, 3, 10, 9, 5)])
// Across New Year's Eve 2026: the same again.
session(3, [local(2026, 12, 31, 23, 50), local(2027, 1, 1, 0, 0), local(2027, 1, 1, 0, 10), local(2027, 1, 1, 0, 20)])

const get = (range: 'today' | 'week' | 'month' | 'ytd' | 'all', now: number) => stats.getStats(range, now, { fresh: true })
const mins = (ms: number) => Math.round(ms / MIN)

test('YTD is asked for by name, and starts at the server\'s own January 1', async () => {
  assert.equal(stats.parseRange('ytd'), 'ytd')
  const s = await get('ytd', local(2026, 3, 10, 12))
  assert.equal(s.range, 'ytd')
  assert.equal(s.from, local(2026, 1, 1))
  assert.equal(s.to, local(2026, 3, 10, 12))
  assert.equal(s.days.length, 30, 'the chart stays the last 30 days')
  assert.equal(s.days.at(-1)!.day, '2026-03-10')
})

test('the local-time rule: January\'s midnight, however the clocks have moved since', async () => {
  // July is on daylight time (UTC-4), January wasn't (UTC-5).
  const s = await get('ytd', Date.parse('2026-07-15T16:00:00Z'))
  assert.equal(s.from, Date.parse('2026-01-01T05:00:00Z'))
})

test('a session across midnight on January 1 counts its January part, as Today and Month cut theirs', async () => {
  const onJan1 = local(2026, 1, 1, 10)
  const ytd = await get('ytd', onJan1)
  const today = await get('today', onJan1)
  assert.equal(mins(ytd.claudeMs), 20)
  assert.equal(ytd.claudeMs, today.claudeMs)
  // It started on December 31: a session counts on its first day, so not in January's.
  assert.equal(ytd.sessions, 0)
  assert.equal(today.sessions, 0)
  // Busy time inside the range is all the longest session gets.
  assert.equal(mins(ytd.longestSession!.claudeMs), 20)
  // The month goes back past the cut and holds both parts, and its session.
  const month = await get('month', onJan1)
  assert.equal(mins(month.claudeMs), 30)
  assert.equal(month.sessions, 1)
})

test('the year rolling over: last year\'s work leaves YTD at midnight, All keeps it', async () => {
  const lastMinute = local(2026, 12, 31, 23, 59)
  const before = await get('ytd', lastMinute)
  assert.equal(before.from, local(2026, 1, 1))
  // Last January's 20, March's 5, and tonight's first 10 minutes; next year's 20 aren't here yet.
  assert.equal(mins(before.claudeMs), 20 + 5 + 10)
  assert.equal(before.sessions, 1 + 1, 'March\'s and tonight\'s')

  const after_ = await get('ytd', local(2027, 1, 1, 0, 30))
  assert.equal(after_.from, local(2027, 1, 1))
  assert.equal(mins(after_.claudeMs), 20)
  assert.equal(after_.busiestDay?.day, '2027-01-01')
  assert.equal(after_.sessions, 0)

  const all = await get('all', local(2027, 1, 1, 0, 30))
  assert.equal(mins(all.claudeMs), 10 + 20 + 5 + 10 + 20)
})

// Last: days already kept stay in the zone they were added up in, so nothing after this one reads them.
test('in the southern summer it\'s the other way round: daylight time in January, not in July', async () => {
  process.env.TZ = 'Australia/Sydney'
  const s = await get('ytd', Date.parse('2026-07-15T02:00:00Z'))
  assert.equal(s.from, Date.parse('2025-12-31T13:00:00Z'))
})
