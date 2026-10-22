// Reading Claude Code's /usage screen (usage-screen.ts): every row, and the weeks some models have
// of their own. The screens in fixtures/usage-screens are drawn the way Claude Code 2.1.302's Usage
// tab lays itself out (from its source: a bold title, a 50-cell meter and "N% used", a dim "Resets …"
// line, a blank line between rows; narrower than 62 columns the reset joins the title and the
// percent gets a line of its own), 100 columns by 50 as the probe's terminal is. They were not
// photographed from a running Claude Code: replace max-fable.txt with a real capture when there
// is one (docs/PROTOCOL.md, "Per-model weeks", says how).
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { modelWeeks, parseReset, planWindows, readUsageScreen } from '../src/usage-screen.ts'

const dir = join(dirname(fileURLToPath(import.meta.url)), 'fixtures', 'usage-screens')
const screen = (name: string) => readFileSync(join(dir, name), 'utf8')
// Saturday 17 October 2026, 15:00 in Moscow (UTC+3, no DST).
const NOW = Date.UTC(2026, 9, 17, 12, 0)
const msk = (month: number, day: number, h: number, m = 0) => Date.UTC(2026, month, day, h - 3, m)

test('a Max plan with a Fable week: every row, and Sonnet and Fable as model weeks', () => {
  const s = readUsageScreen(screen('max-fable.txt'), NOW)
  assert.equal(s.ok, true)
  assert.equal(s.loading, false)
  assert.equal(s.error, null)
  assert.deepEqual(
    s.rows.map((r) => [r.title, r.pct, r.reset]),
    [
      ['Current session', 23, '7pm (Europe/Moscow)'],
      ['Current week (all models)', 62, 'Oct 21, 10am (Europe/Moscow)'],
      ['Current week (Sonnet only)', 3, 'Oct 21, 10am (Europe/Moscow)'],
      ['Current week (Fable)', 41, 'Oct 20, 8pm (Europe/Moscow)'],
    ],
  )
  assert.equal(s.rows[0]!.resetsAt, msk(9, 17, 19))
  assert.deepEqual(modelWeeks(s.rows), [
    { label: 'Sonnet', pct: 3, resetsAt: msk(9, 21, 10) },
    { label: 'Fable', pct: 41, resetsAt: msk(9, 20, 20) },
  ])
})

test('no per-model rows (a Pro plan): read, and no model weeks', () => {
  const s = readUsageScreen(screen('no-models.txt'), NOW)
  assert.equal(s.ok, true)
  assert.deepEqual(s.rows.map((r) => r.title), ['Current session', 'Current week (all models)'])
  assert.deepEqual(modelWeeks(s.rows), [])
})

test("a model's week keeps the name the plan gives the model (\"Fable 5.1\"); \"all models\" is never one", () => {
  const text = screen('max-fable.txt').replace('Current week (Fable)', 'Current week (Fable 5.1)')
  assert.deepEqual(modelWeeks(readUsageScreen(text, NOW).rows).map((m) => [m.label, m.pct]), [['Sonnet', 3], ['Fable 5.1', 41]])
})

test('Fable near its limit, a note under its row, a reset with minutes', () => {
  const s = readUsageScreen(screen('fable-near-limit.txt'), NOW)
  const fable = modelWeeks(s.rows).find((m) => m.label === 'Fable')
  assert.deepEqual(fable, { label: 'Fable', pct: 97, resetsAt: msk(9, 20, 20, 30) })
  assert.equal(s.rows.length, 4)
})

test('usage credits are a row, but no model week', () => {
  const s = readUsageScreen(screen('extra-usage.txt'), NOW)
  const credits = s.rows.find((r) => r.title === 'Usage credits')
  assert.deepEqual(credits && [credits.pct, credits.reset, credits.resetsAt], [25, 'Nov 1 (Europe/Moscow)', msk(10, 1, 0)])
  assert.deepEqual(modelWeeks(s.rows).map((m) => m.label), ['Sonnet', 'Fable'])
})

test('the narrow layout (reset on the title line, percent alone, an empty meter drawn as nothing) reads the same', () => {
  const wide = modelWeeks(readUsageScreen(screen('max-fable.txt'), NOW).rows)
  const s = readUsageScreen(screen('narrow.txt'), NOW)
  assert.equal(s.ok, true)
  assert.deepEqual(s.rows.map((r) => [r.title, r.pct]), [
    ['Current session', 23],
    ['Current week (all models)', 62],
    ['Current week (Sonnet only)', 0],
    ['Current week (Fable)', 41],
  ])
  assert.deepEqual(modelWeeks(s.rows), wide.map((m) => (m.label === 'Sonnet' ? { ...m, pct: 0 } : m)))
})

test('still loading, refreshing over the last numbers, or an error', () => {
  const loading = readUsageScreen(screen('loading.txt'), NOW)
  assert.deepEqual([loading.ok, loading.loading, loading.rows.length], [false, true, 0])
  const refreshing = readUsageScreen(screen('refreshing.txt'), NOW)
  assert.deepEqual([refreshing.ok, refreshing.loading], [true, true])
  const error = readUsageScreen(screen('error.txt'), NOW)
  assert.deepEqual([error.ok, error.error], [false, 'Failed to load usage data'])
})

test("a screen that isn't /usage has no rows: Claude Code's start, or a sentence ending in “% used”", () => {
  const start = screen('max-fable.txt').split('\n').slice(0, 13).join('\n') + '\n' + '─'.repeat(100) + '\n❯ \n' + '─'.repeat(100)
  assert.deepEqual(readUsageScreen(start, NOW), { rows: [], ok: false, loading: false, error: null, partial: null })
  const chat = '● The context window is now 45% used\n\n  It will compact soon.'
  assert.equal(readUsageScreen(chat, NOW).rows.length, 0)
})

test('reset times: within a day the next one, dates nearest to now, years, zones', () => {
  // 1:30 in Moscow: "2am" is in half an hour; at 3:00 it's tomorrow's.
  assert.equal(parseReset('2am (Europe/Moscow)', msk(9, 17, 1, 30)), msk(9, 17, 2))
  assert.equal(parseReset('2am (Europe/Moscow)', msk(9, 17, 3)), msk(9, 18, 2))
  assert.equal(parseReset('2:45pm (Europe/Moscow)', NOW), msk(9, 18, 14, 45))
  assert.equal(parseReset('12am (UTC)', Date.UTC(2026, 9, 17, 23, 0)), Date.UTC(2026, 9, 18, 0, 0))
  assert.equal(parseReset('12pm (UTC)', Date.UTC(2026, 9, 17, 9, 0)), Date.UTC(2026, 9, 17, 12, 0))
  // A date across New Year: the nearest year, either way.
  assert.equal(parseReset('Jan 2, 10am (UTC)', Date.UTC(2026, 11, 30)), Date.UTC(2027, 0, 2, 10))
  assert.equal(parseReset('Dec 30, 11pm (UTC)', Date.UTC(2027, 0, 1)), Date.UTC(2026, 11, 30, 23))
  assert.equal(parseReset('Jan 2, 2027, 10am (UTC)', NOW), Date.UTC(2027, 0, 2, 10))
  // Daylight saving: New York is UTC-4 in October, UTC-5 in November.
  assert.equal(parseReset('Oct 21, 10am (America/New_York)', NOW), Date.UTC(2026, 9, 21, 14))
  assert.equal(parseReset('Nov 3, 10am (America/New_York)', NOW), Date.UTC(2026, 10, 3, 15))
  // No zone, or one this Mac doesn't know: the Mac's own.
  assert.equal(parseReset('Oct 21, 10am', NOW), new Date(2026, 9, 21, 10).getTime())
  assert.equal(parseReset('Oct 21, 10am (Mars/Olympus_Mons)', NOW), new Date(2026, 9, 21, 10).getTime())
  for (const bad of ['', 'soon', 'in 3 hours', 'Smarch 3, 10am', '25pm']) assert.equal(parseReset(bad, NOW), null, bad)
})

test('"Per-model breakdown unavailable" and "Partial usage data": read, but flagged, with no model weeks', () => {
  const a = readUsageScreen(screen('breakdown-unavailable.txt'))
  assert.equal(a.ok, true)
  assert.equal(a.partial, 'Per-model breakdown unavailable (rate limited)')
  assert.deepEqual(modelWeeks(a.rows), [])
  const b = readUsageScreen(screen('partial.txt'))
  assert.equal(b.ok, true)
  assert.equal(b.partial, 'Partial usage data')
  assert.equal(readUsageScreen(screen('max-fable.txt')).partial, null)
})

test('the 5-hour window and the all-models week, as the pace reads them (planWindows), in every layout', () => {
  const wide = planWindows(readUsageScreen(screen('max-fable.txt'), NOW).rows)
  assert.deepEqual(wide, { fiveHour: { pct: 23, resetsAt: msk(9, 17, 19) }, sevenDay: { pct: 62, resetsAt: msk(9, 21, 10) } })
  assert.deepEqual(planWindows(readUsageScreen(screen('narrow.txt'), NOW).rows), wide)
  assert.deepEqual(planWindows(readUsageScreen(screen('no-models.txt'), NOW).rows), wide)
  // Over "Refreshing…" the rows read the same (the probe decides whether they're fresh).
  assert.deepEqual(planWindows(readUsageScreen(screen('refreshing.txt'), NOW).rows), wide)
  // Not the usage screen: neither.
  assert.deepEqual(planWindows(readUsageScreen(screen('loading.txt'), NOW).rows), { fiveHour: null, sevenDay: null })
})

test('a session reset to the minute in the small hours, the week by its date, a model week beside them', () => {
  // Sunday 18 October, 2:05 in Moscow: "6:20am" is this morning's, four hours on.
  const now = msk(9, 18, 2, 5)
  const s = readUsageScreen(screen('session-minutes.txt'), now)
  assert.equal(s.ok, true)
  assert.deepEqual(planWindows(s.rows), { fiveHour: { pct: 76, resetsAt: msk(9, 18, 6, 20) }, sevenDay: { pct: 47, resetsAt: msk(9, 21, 10) } })
  assert.deepEqual(modelWeeks(s.rows), [{ label: 'Fable', pct: 43, resetsAt: msk(9, 20, 20) }])
})

test("a row without a reset (or one that doesn't read) has none; a weekday form reads", () => {
  const now = msk(9, 18, 2, 5)
  const text = screen('session-minutes.txt')
  const noReset = text.replace('  Resets 6:20am (Europe/Moscow)\n', '')
  assert.deepEqual(planWindows(readUsageScreen(noReset, now).rows).fiveHour, { pct: 76, resetsAt: null })
  const garbled = text.replace('Resets 6:20am (Europe/Moscow)', 'Resets soon (Europe/Moscow)')
  assert.deepEqual(planWindows(readUsageScreen(garbled, now).rows).fiveHour, { pct: 76, resetsAt: null })
  // Sunday now: "Wed 10am" is in three days; the week's row in that form, read in its zone.
  const weekday = text.replace('Resets Oct 21, 10am (Europe/Moscow)', 'Resets Wed 10am (Europe/Moscow)')
  assert.deepEqual(planWindows(readUsageScreen(weekday, now).rows).sevenDay, { pct: 47, resetsAt: msk(9, 21, 10) })
})

test('reset times with a weekday, or "at": the next such day, or the date when there is one', () => {
  // NOW is Saturday 17 October, 15:00 in Moscow.
  assert.equal(parseReset('Thu 10am (Europe/Moscow)', NOW), msk(9, 22, 10))
  assert.equal(parseReset('Thursday at 10:30pm (Europe/Moscow)', NOW), msk(9, 22, 22, 30))
  assert.equal(parseReset('Sun, 9am (Europe/Moscow)', NOW), msk(9, 18, 9))
  // Today, still ahead; today, gone: a week on.
  assert.equal(parseReset('Sat 4:30pm (Europe/Moscow)', NOW), msk(9, 17, 16, 30))
  assert.equal(parseReset('Sat 2pm (Europe/Moscow)', NOW), msk(9, 24, 14))
  // A date decides over the weekday before it.
  assert.equal(parseReset('Thu, Oct 22, 10am (Europe/Moscow)', NOW), msk(9, 22, 10))
  assert.equal(parseReset('Oct 21 at 10am (Europe/Moscow)', NOW), msk(9, 21, 10))
  for (const bad of ['Thu', 'Funday 10am', 'Thu 13pm']) assert.equal(parseReset(bad, NOW), null, bad)
})

test('a 24-hour clock and the day before the month, as other locales write them', () => {
  // 15:00 in Moscow: "19:00" is this evening's; "02:00" tomorrow's.
  assert.equal(parseReset('19:00 (Europe/Moscow)', NOW), msk(9, 17, 19))
  assert.equal(parseReset('02:00 (Europe/Moscow)', NOW), msk(9, 18, 2))
  assert.equal(parseReset('21 Oct (Europe/Moscow)', NOW), msk(9, 21, 0))
  assert.equal(parseReset('21 Oct, 19:00 (Europe/Moscow)', NOW), msk(9, 21, 19))
  assert.equal(parseReset('21 Oct 2026, 19:30 (Europe/Moscow)', NOW), msk(9, 21, 19, 30))
  assert.equal(parseReset('Oct 21, 19:00 (Europe/Moscow)', NOW), msk(9, 21, 19))
  assert.equal(parseReset('Wed 19:00 (Europe/Moscow)', NOW), msk(9, 21, 19))
  // The 12-hour forms still read as they did; nonsense still doesn't.
  assert.equal(parseReset('Oct 21, 10am (Europe/Moscow)', NOW), msk(9, 21, 10))
  for (const bad of ['24:00', '19:60', '19', 'Oct 21, 19', '21 Smarch']) assert.equal(parseReset(bad, NOW), null, bad)
})
