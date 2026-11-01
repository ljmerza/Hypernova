// How readings of the 5-hour and weekly windows merge: the newest wins, even when lower (a reset on
// claude.ai), while idle sessions repainting old numbers don't pull the meter back up.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-usage-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
mkdirSync(process.env.HOME, { recursive: true })
after(() => rmSync(scratch, { recursive: true, force: true }))

const hub = await import('../src/hub.ts')
const week = Date.now() + 4 * 24 * 3600_000

test('a newer, lower reading wins; an older, higher one is ignored; same-second readings keep the higher', () => {
  const t = Date.now()
  hub.offerUsage(null, { pct: 94, resetsAt: week }, t - 60_000)
  assert.equal(hub.getUsage().sevenDay?.pct, 94)
  // The owner reset his limits: the active session's fresh reading says 5%.
  hub.offerUsage(null, { pct: 5, resetsAt: week }, t)
  assert.equal(hub.getUsage().sevenDay?.pct, 5)
  // An idle session still repaints 92% from an hour ago: ignored.
  hub.offerUsage(null, { pct: 92, resetsAt: week }, t - 3600_000)
  assert.equal(hub.getUsage().sevenDay?.pct, 5)
  // Two sessions in the same second: the higher.
  hub.offerUsage(null, { pct: 6, resetsAt: week }, t + 200)
  assert.equal(hub.getUsage().sevenDay?.pct, 6)
  hub.offerUsage(null, { pct: 5, resetsAt: week + 30_000 }, t + 400)
  assert.equal(hub.getUsage().sevenDay?.pct, 6)
})

test('a limit Claude Code hit stands against older readings, and a newer one lifts it', () => {
  const t = Date.now() + 10_000
  hub.forceUsage('seven_day', week, t)
  assert.equal(hub.getUsage().sevenDay?.pct, 100)
  hub.offerUsage(null, { pct: 7, resetsAt: week }, t - 5000)
  assert.equal(hub.getUsage().sevenDay?.pct, 100, 'an older reading does not lift the limit')
  hub.offerUsage(null, { pct: 8, resetsAt: week }, t + 5000)
  assert.equal(hub.getUsage().sevenDay?.pct, 8, 'a newer one does')
})

test('a later reset still wins over the old window', () => {
  const t = Date.now() + 20_000
  hub.offerUsage(null, { pct: 3, resetsAt: week + 7 * 24 * 3600_000 }, t - 999_999)
  assert.equal(hub.getUsage().sevenDay?.pct, 3)
  assert.equal(hub.getUsage().sevenDay?.resetsAt, week + 7 * 24 * 3600_000)
})

// ── The usage probe's readings (offerScreenUsage): /usage's 5-hour and all-models rows ──
const MIN = 60_000

test('the pace rises from /usage readings alone, every 2 minutes: a pace by the third look', () => {
  // No status line: the 5-hour window is known from these looks alone, read in the last four minutes.
  const now = Date.now()
  assert.equal(hub.getUsage().fiveHour, null)
  const reset = now + 4 * 3600_000 + 50 * MIN
  hub.upsertSession('pace-work', { cwd: process.env.HOME, origin: 'terminal', state: 'working' })
  // Looks 1 and 2, at -4 and -2 minutes: too little to go on.
  hub.offerScreenUsage({ pct: 60, resetsAt: reset }, null, now - 4 * MIN)
  hub.offerScreenUsage({ pct: 61, resetsAt: reset }, null, now - 2 * MIN)
  assert.equal(hub.getUsage().rate?.fiveHour, null)
  // Look 3, four minutes after the first: 0.5 %/min.
  hub.offerScreenUsage({ pct: 62, resetsAt: reset }, null, now)
  const r = hub.getUsage().rate!
  assert.equal(r.working, true)
  assert.equal(r.fiveHour, 0.5)
  // Nothing working: no rate at all.
  hub.upsertSession('pace-work', { state: 'idle' })
  assert.equal(hub.getUsage().rate, null)
})

// The 5-hour window's readings below, in order, after the pace test's.
let clock = Date.now()
const at = (ms = MIN) => (clock += ms)

test("a /usage reading merges like a status line's: newest wins, its minute-rounded reset keeps the known one's", () => {
  const t0 = at()
  // A new window (its reset after the pace test's), with nearly five hours left.
  const reset = t0 + 4 * 3600_000 + 58 * MIN + 17_000
  hub.offerUsage({ pct: 40, resetsAt: reset }, null, t0)
  // The screen gives the reset to the minute, 17 s off the status line's: the same window, whose reset stays.
  const t1 = at(2 * MIN)
  hub.offerScreenUsage({ pct: 42, resetsAt: reset - 17_000 }, null, t1)
  assert.deepEqual(hub.getUsage().fiveHour, { pct: 42, resetsAt: reset })
  // A status line repainting an older reading doesn't pull it back; a newer one wins, even lower.
  hub.offerUsage({ pct: 41, resetsAt: reset }, null, t1 - MIN)
  assert.equal(hub.getUsage().fiveHour?.pct, 42)
  hub.offerScreenUsage({ pct: 41, resetsAt: reset }, null, at())
  assert.equal(hub.getUsage().fiveHour?.pct, 41)
  // An older screen reading is passed over too.
  hub.offerScreenUsage({ pct: 50, resetsAt: reset }, null, t1 - 5 * MIN)
  assert.equal(hub.getUsage().fiveHour?.pct, 41)
})

test("a /usage row without a reset takes the known window's while it lasts; one that can't be this window's too", () => {
  const reset = hub.getUsage().fiveHour!.resetsAt
  hub.offerScreenUsage({ pct: 44, resetsAt: null }, null, at())
  assert.deepEqual(hub.getUsage().fiveHour, { pct: 44, resetsAt: reset })
  // Read as a day on (a misread time): no 5-hour window resets that far off; the known one stands.
  const t = at()
  hub.offerScreenUsage({ pct: 45, resetsAt: t + 24 * 3600_000 }, null, t)
  assert.deepEqual(hub.getUsage().fiveHour, { pct: 45, resetsAt: reset })
  // Past its reset, a new window by its own (later than the known one's): taken whole.
  const next = at(5 * 3600_000)
  assert.ok(next > reset)
  hub.offerScreenUsage({ pct: 2, resetsAt: next + 5 * 3600_000 - MIN }, null, next)
  assert.deepEqual(hub.getUsage().fiveHour, { pct: 2, resetsAt: next + 5 * 3600_000 - MIN })
})

test('a /usage row without a reset, and no window known that still runs: passed over', () => {
  const before = hub.getUsage().sevenDay
  // As if read after the known week ended: nothing to go by.
  hub.offerScreenUsage(null, { pct: 1, resetsAt: null }, before!.resetsAt + MIN)
  assert.deepEqual(hub.getUsage().sevenDay, before)
})

test('a forced limit stands against an older /usage reading; a newer one lifts it (the account not limited)', () => {
  const reset = hub.getUsage().fiveHour!.resetsAt
  const t = at()
  hub.forceUsage('five_hour', reset, t)
  hub.offerScreenUsage({ pct: 90, resetsAt: reset }, null, t - 5000)
  assert.equal(hub.getUsage().fiveHour?.pct, 100)
  hub.offerScreenUsage({ pct: 97, resetsAt: reset }, null, at())
  assert.equal(hub.getUsage().fiveHour?.pct, 97)
})

test("/usage floors to a whole percent: under a point below the status line's reading is the same reading, not a tick back", () => {
  const reset = hub.getUsage().fiveHour!.resetsAt
  hub.offerUsage({ pct: 41.7, resetsAt: reset }, null, at())
  assert.equal(hub.getUsage().fiveHour?.pct, 41.7)
  hub.offerScreenUsage({ pct: 41, resetsAt: reset }, null, at())
  assert.equal(hub.getUsage().fiveHour?.pct, 41.7)
  // A whole point or more below is a real drop (a reset on claude.ai): the newest wins, as ever.
  hub.offerScreenUsage({ pct: 40, resetsAt: reset }, null, at())
  assert.equal(hub.getUsage().fiveHour?.pct, 40)
})

