// The usage probe as a source of the spend pace (usage-probe.ts → hub.ts offerScreenUsage →
// usage-rate.ts), on the stand-in for Claude Code (fixtures/fake-claude-usage.mjs): the 5-hour and
// all-models rows it reads are official readings as of the moment it read them, with no status line
// at all; a look every 2 minutes while something works, every 15 otherwise.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, mkdirSync, mkdtempSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-pace-')))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude-config')
process.env.HOME = join(scratch, 'home')
process.env.FAKE_USAGE_STATE = join(scratch, 'state')
const fixtures = join(dirname(fileURLToPath(import.meta.url)), 'fixtures')
const SCREEN = join(scratch, 'screen.txt')
process.env.FAKE_USAGE_SCREEN = SCREEN
for (const d of [process.env.HOME, process.env.FAKE_USAGE_STATE, process.env.CLAUDE_CONFIG_DIR]) mkdirSync(d, { recursive: true })
const bin = join(scratch, 'claude')
writeFileSync(bin, `#!/bin/sh\nexec "${process.execPath}" "${join(fixtures, 'fake-claude-usage.mjs')}" "$@"\n`)
chmodSync(bin, 0o755)
process.env.CLAUDE_BIN = bin

const hub = await import('../src/hub.ts')
const probe = await import('../src/usage-probe.ts')

after(() => rmSync(scratch, { recursive: true, force: true }))

const MIN = 60_000
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
/** A reset as Claude Code writes it, in UTC, to the minute: "3:07pm (UTC)", or with its date. */
function resetText(t: number, withDate: boolean): string {
  const d = new Date(t)
  const h = d.getUTCHours()
  const m = d.getUTCMinutes()
  const clock = `${h % 12 || 12}${m ? `:${String(m).padStart(2, '0')}` : ''}${h < 12 ? 'am' : 'pm'}`
  return `${withDate ? `${MONTHS[d.getUTCMonth()]} ${d.getUTCDate()}, ` : ''}${clock} (UTC)`
}
/** The Usage tab, wide, as 2.1.302 draws it (usage-screen.ts): a title, a 50-cell meter with "N% used", the reset. */
function drawScreen(rows: [title: string, pct: number, reset: string][], tail: string[] = []): void {
  const lines = ['> /usage', '', '─'.repeat(100), '  Settings:  Status   Config   Usage   Stats  (←/→ or tab to cycle)', '']
  for (const [title, pct, reset] of rows) {
    const cells = (pct * 50) / 100
    const bar = '█'.repeat(Math.floor(cells)) + (cells % 1 >= 0.5 ? '▌' : '')
    lines.push(`  ${title}`, `  ${bar.padEnd(50)} ${pct}% used`, `  Resets ${reset}`, '')
  }
  lines.push(...tail, '  Esc to cancel')
  writeFileSync(SCREEN, lines.join('\n') + '\n')
}
const minute = (t: number) => Math.floor(t / MIN) * MIN

let t0 = 0
let unplug = () => {}
test('the 5-hour and weekly rows a look reads are official readings, and with two earlier looks make a pace and a time to the limit', async () => {
  const sent: any[] = []
  unplug = hub.addClient({ send: (s) => sent.push(JSON.parse(s)), phone: true })
  const now = Date.now()
  const reset5 = minute(now + 3 * 3600_000 + 7 * MIN)
  const reset7 = minute(now + 3 * 24 * 3600_000)
  drawScreen([
    ['Current session', 42, resetText(reset5, false)],
    ['Current week (all models)', 30, resetText(reset7, true)],
    ['Current week (Fable)', 10, resetText(reset7, true)],
  ])
  hub.upsertSession('pace-1', { cwd: scratch, origin: 'terminal', state: 'working' })
  // Two earlier looks, 4 and 2 minutes ago (as the probe's own readings: no status line anywhere).
  hub.offerScreenUsage({ pct: 40, resetsAt: reset5 }, { pct: 30, resetsAt: reset7 }, now - 4 * MIN)
  hub.offerScreenUsage({ pct: 41, resetsAt: reset5 }, { pct: 30, resetsAt: reset7 }, now - 2 * MIN)
  assert.equal(hub.getUsage().rate?.fiveHour, null)
  t0 = Date.now()
  assert.equal(await probe.probeUsage('test'), true)
  const u = hub.getUsage()
  assert.deepEqual(u.fiveHour, { pct: 42, resetsAt: reset5 })
  assert.deepEqual(u.sevenDay, { pct: 30, resetsAt: reset7 })
  assert.equal(u.source, 'official')
  assert.ok(u.observedAt! >= t0 && u.observedAt! <= Date.now(), 'true as of the moment the screen was read')
  assert.deepEqual(u.models?.map((m) => [m.label, m.pct]), [['Fable', 10]])
  // 40, 41, 42 over four minutes (and the few seconds the look took): about 0.5 %/min, the limit in
  // about two hours (58 / 0.5 = 116 minutes), before the reset in 187.
  const r = u.rate!
  assert.ok(r.fiveHour! >= 0.46 && r.fiveHour! <= 0.5, String(r.fiveHour))
  assert.ok(r.fiveHourEtaMs! >= 116 * MIN && r.fiveHourEtaMs! <= 127 * MIN, String(r.fiveHourEtaMs))
  // The week moved 0 in 4 minutes: too short a stretch for its pace (12 minutes at least).
  assert.equal(r.sevenDay, null)
  // Clients hear the readings (the pace comes with every usage message while something works).
  const last = sent.filter((m) => m.t === 'usage').at(-1)?.usage
  assert.equal(last?.fiveHour?.pct, 42)
  assert.ok(last?.rate?.fiveHour > 0)
})

test('looks come every 2 minutes while something works, every 15 otherwise, never with nobody connected', () => {
  // The look above started at t0 (just after it, at the latest).
  const due = (after: number) => probe.timedLookDue(t0 + after)
  assert.equal(hub.anythingWorking(), true)
  assert.equal(due(MIN), null)
  assert.equal(due(2 * MIN + 5000), 'while working')
  hub.upsertSession('pace-1', { state: 'idle' })
  assert.equal(hub.anythingWorking(), false)
  assert.equal(due(2 * MIN + 5000), null)
  assert.equal(due(14 * MIN), null)
  assert.equal(due(15 * MIN + 5000), 'every 15 minutes')
  assert.equal(probe.waitMs(true), 2 * MIN)
  assert.equal(probe.waitMs(false), 15 * MIN)
  // Needs you counts as idle: the meters don't move while it waits on someone.
  hub.upsertSession('pace-1', { state: 'needs_you' })
  assert.equal(due(2 * MIN + 5000), null)
  hub.upsertSession('pace-1', { state: 'working' })
  assert.equal(due(2 * MIN + 5000), 'while working')
  // Nobody connected: nobody to show it to.
  unplug()
  assert.equal(due(2 * MIN + 5000), null)
  assert.equal(due(15 * MIN + 5000), null)
  unplug = hub.addClient({ send: () => {}, phone: true })
  assert.equal(due(2 * MIN + 5000), 'while working')
})

test('idle with only the Mac app’s feed connected, a look an hour; sessions on another provider don’t count as working', async () => {
  const store = await import('../src/store.ts')
  const due = (after: number) => probe.timedLookDue(t0 + after)
  unplug()
  const mac = hub.addClient({ send: () => {} })
  hub.upsertSession('pace-1', { state: 'idle' })
  assert.equal(due(15 * MIN + 5000), null)
  assert.equal(due(60 * MIN + 5000), 'hourly')
  assert.equal(probe.waitMs(false, false), 60 * MIN)
  // Only an OpenRouter session at work: nothing spends the subscription, so no 2-minute looks and no pace.
  const or = store.saveProvider({ name: 'Pace router', kind: 'openrouter', apiKey: 'test-only-not-a-key' })
  hub.upsertSession('pace-or', { cwd: scratch, origin: 'terminal', state: 'working', providerId: or.id })
  assert.equal(hub.anythingWorking(), false)
  assert.equal(hub.getUsage().rate ?? null, null)
  assert.equal(due(2 * MIN + 5000), null)
  hub.upsertSession('pace-or', { state: 'idle' })
  mac()
  unplug = hub.addClient({ send: () => {}, phone: true })
  hub.upsertSession('pace-1', { state: 'working' })
})

test("a look that can't read the screen backs off from 2 minutes while working (from 15 otherwise); a good one resets it", async () => {
  writeFileSync(SCREEN, '  Error: Failed to load usage data\n\n  Esc to cancel\n')
  assert.equal(await probe.probeUsage('test'), false)
  assert.equal(probe.waitMs(true), 4 * MIN)
  assert.equal(probe.waitMs(false), 30 * MIN)
  const now = Date.now()
  drawScreen([
    ['Current session', 43, resetText(minute(now + 3 * 3600_000), false)],
    ['Current week (all models)', 30, resetText(minute(now + 3 * 24 * 3600_000), true)],
  ])
  assert.equal(await probe.probeUsage('test'), true)
  assert.equal(probe.waitMs(true), 2 * MIN)
})

test('three looks in a row that can\'t read /usage: the model weeks are said to be stale, since the first; a good look ends it', async () => {
  writeFileSync(SCREEN, '  Error: Failed to load usage data\n\n  Esc to cancel\n')
  const first = Date.now()
  assert.equal(await probe.probeUsage('test'), false)
  assert.equal(await probe.probeUsage('test'), false)
  assert.deepEqual(hub.getUsage().probe, { ok: true, since: null })
  assert.equal(await probe.probeUsage('test'), false)
  const u = hub.getUsage()
  assert.equal(u.probe?.ok, false)
  assert.ok(u.probe!.since! >= first && u.probe!.since! <= Date.now())
  assert.equal(u.modelsStale, true)
  const now = Date.now()
  drawScreen([
    ['Current session', 44, resetText(minute(now + 3 * 3600_000), false)],
    ['Current week (all models)', 30, resetText(minute(now + 3 * 24 * 3600_000), true)],
    ['Current week (Fable)', 11, resetText(minute(now + 3 * 24 * 3600_000), true)],
  ])
  assert.equal(await probe.probeUsage('test'), true)
  assert.deepEqual([hub.getUsage().probe, hub.getUsage().modelsStale], [{ ok: true, since: null }, false])
})

test('status-line files of sessions not seen for a week are deleted; recent ones stay', async () => {
  const { utimesSync } = await import('node:fs')
  const dir = join(scratch, 'statusline-sweep')
  mkdirSync(dir, { recursive: true })
  for (const f of ['old.json', 'new.json', 'note.txt']) writeFileSync(join(dir, f), '{}')
  const eightDays = (Date.now() - 8 * 24 * 3600_000) / 1000
  utimesSync(join(dir, 'old.json'), eightDays, eightDays)
  utimesSync(join(dir, 'note.txt'), eightDays, eightDays)
  assert.equal(probe.sweepStatusLines(dir), 1)
  const { readdirSync } = await import('node:fs')
  assert.deepEqual(readdirSync(dir).sort(), ['new.json', 'note.txt'])
  assert.equal(probe.sweepStatusLines(join(scratch, 'missing')), 0)
})

test('the last numbers under a "Refreshing…" that never ends are no reading (only the model weeks are kept from them)', async () => {
  const before = hub.getUsage()
  const now = Date.now()
  drawScreen(
    [
      ['Current session', 99, resetText(minute(now + 3 * 3600_000), false)],
      ['Current week (all models)', 99, resetText(minute(now + 3 * 24 * 3600_000), true)],
      ['Current week (Fable)', 12, resetText(minute(now + 3 * 24 * 3600_000), true)],
    ],
    ['  Refreshing…', ''],
  )
  assert.equal(await probe.probeUsage('test'), true)
  const u = hub.getUsage()
  assert.deepEqual([u.fiveHour, u.sevenDay, u.observedAt], [before.fiveHour, before.sevenDay, before.observedAt])
  assert.deepEqual(u.models?.map((m) => [m.label, m.pct]), [['Fable', 12]])
})

test('at a 5-hour limit the looks go back to every 15 minutes, work or not: the meter stands full, with no pace', async () => {
  const { classifyApiError } = await import('../src/blockers.ts')
  const account = await import('../src/account.ts')
  const now = Date.now()
  // The window the looks above read: its limit.
  const resetsAt = hub.getUsage().fiveHour!.resetsAt
  const info = { status: 'rejected', resetsAt: resetsAt / 1000, rateLimitType: 'five_hour', overageStatus: 'rejected', overageDisabledReason: 'org_level_disabled', isUsingOverage: false }
  const b = classifyApiError({ error: 'rate_limit', apiError: 'usage_limit_reached', status: 429, text: "You've hit your session limit · resets 2am (Europe/Moscow)", rateLimitInfo: info, builtIn: true, at: now })
  account.noteBlocker(b, { at: now, sessionId: 'pace-1', providerId: 'anthropic' })
  assert.equal(account.getAccount().state, 'limited')
  assert.equal(hub.anythingWorking(), true)
  // The last look (the "Refreshing…" one) started a moment ago.
  assert.equal(probe.timedLookDue(Date.now() + 2 * MIN + 5000), null)
  assert.equal(probe.timedLookDue(Date.now() + 15 * MIN + 5000), 'every 15 minutes')
  assert.equal(hub.getUsage().fiveHour?.pct, 100)
  assert.equal(hub.getUsage().rate?.fiveHour, null)
})
