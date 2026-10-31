// A plan without a 5-hour window (Max, 2026-10-31: /usage and the status line report only the week):
// the daemon says none, rather than the last 5-hour reading rolled into empty windows for ever.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-usage-no5-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
mkdirSync(process.env.HOME, { recursive: true })
after(() => rmSync(scratch, { recursive: true, force: true }))

const hub = await import('../src/hub.ts')
const { planWindows, readUsageScreen } = await import('../src/usage-screen.ts')
const MIN = 60_000
const t0 = Date.now() - 60 * MIN
const reset5 = t0 + 3 * 3600_000
const week = t0 + 4 * 24 * 3600_000

test('a /usage screen with the week and no "Current session" row clears the 5-hour window', () => {
  hub.offerScreenUsage({ pct: 14, resetsAt: reset5 }, { pct: 62, resetsAt: week }, t0)
  assert.equal(hub.getUsage().fiveHour?.pct, 14)
  // A screen row left out of a call (null alone) says nothing about the window...
  hub.offerScreenUsage(null, { pct: 62, resetsAt: week }, t0 + MIN / 2)
  assert.equal(hub.getUsage().fiveHour?.pct, 14)
  // ...the probe's "the week, and no session row" (usage-probe.ts passes noFive) does.
  hub.offerScreenUsage(null, { pct: 62, resetsAt: week }, t0 + 2 * MIN, true)
  assert.equal(hub.getUsage().fiveHour, null)
  assert.equal(hub.getUsage().sevenDay?.pct, 62)
  assert.equal(hub.getUsage().source, 'official')
})

test('an older 5-hour reading (an idle session repainting its status line) does not bring it back', () => {
  hub.offerUsage({ pct: 14, resetsAt: reset5 }, { pct: 62, resetsAt: week }, t0 + MIN)
  assert.equal(hub.getUsage().fiveHour, null)
  // Nor one from the same moment.
  hub.offerUsage({ pct: 14, resetsAt: reset5 }, null, t0 + 2 * MIN)
  assert.equal(hub.getUsage().fiveHour, null)
})

test('long after the old reset, nothing rolls into a fresh empty 5-hour window', () => {
  // getUsage() rolls a known window past its reset to 0%: with none known, there is nothing to roll.
  assert.equal(hub.getUsage().fiveHour, null)
  assert.equal(hub.getUsage().rate ?? null, null)
})

test('a status line with the week and no five_hour also says none; the week alone (a forced week) does not', () => {
  // A newer reading brings a 5-hour window back: the data decides, not a list of plans.
  hub.offerUsage({ pct: 3, resetsAt: reset5 + 5 * 3600_000 }, null, t0 + 10 * MIN)
  assert.equal(hub.getUsage().fiveHour?.pct, 3)
  // A weekly limit Claude Code hit carries only the week: that says nothing about the 5-hour window.
  hub.forceUsage('seven_day', week, t0 + 11 * MIN)
  assert.equal(hub.getUsage().fiveHour?.pct, 3)
  // A status line listing the week and no five_hour (services.ts passes noFive): gone again.
  hub.offerUsage(null, { pct: 63, resetsAt: week }, t0 + 12 * MIN, false, true)
  assert.equal(hub.getUsage().fiveHour, null)
})

test('a reading that names neither window changes nothing', () => {
  hub.offerUsage({ pct: 4, resetsAt: reset5 + 5 * 3600_000 }, null, t0 + 20 * MIN)
  assert.equal(hub.getUsage().fiveHour?.pct, 4)
  hub.offerScreenUsage(null, null, t0 + 21 * MIN, false)
  assert.equal(hub.getUsage().fiveHour?.pct, 4)
})

test('planWindows: a screen without "Current session" has no 5-hour row', () => {
  // As the owner's Max account showed it from 2026-10-31 05:09 UTC: the week and Fable, no session.
  const screen = [
    '> /usage',
    '',
    '  Settings:  Status   Config   Usage   Stats  (←/→ or tab to cycle)',
    '',
    '  Current week (all models)',
    '  ███████████████████████████████▌                   62% used',
    '  Resets Nov 4, 10am (UTC)',
    '',
    '  Current week (Fable)',
    '                                                     0% used',
    '  Resets Nov 4, 10am (UTC)',
    '',
    '  Esc to cancel',
  ].join('\n')
  const s = readUsageScreen(screen, Date.now())
  assert.equal(s.ok, true)
  const p = planWindows(s.rows)
  assert.equal(p.fiveHour, null)
  assert.equal(p.sevenDay?.pct, 62)
})
