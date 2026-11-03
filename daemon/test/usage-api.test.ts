// The usage probe's first way of looking, the Agent SDK's usage call (usage-api.ts), on a stand-in for
// the call, and the /usage screen as its fallback on a stand-in for Claude Code. Nothing here
// reaches the real call: under `node --test` usage-api.ts refuses to start it.
import { test, after, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-usage-api-')))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude-config')
process.env.HOME = join(scratch, 'home')
process.env.FAKE_USAGE_STATE = join(scratch, 'state')
const fixtures = join(dirname(fileURLToPath(import.meta.url)), 'fixtures')
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
const daysAhead = (n: number) => {
  const d = new Date(Date.now() + n * 24 * 3600_000)
  return `${MONTHS[d.getUTCMonth()]} ${d.getUTCDate()},`
}
const screenFile = (name: string) => {
  const f = join(scratch, name)
  writeFileSync(f, readFileSync(join(fixtures, 'usage-screens', name), 'utf8').replace(/Resets (Oct|Nov) \d+,/g, `Resets ${daysAhead(3)}`))
  return f
}
process.env.FAKE_USAGE_SCREEN = screenFile('max-fable.txt')
for (const d of [process.env.HOME, process.env.FAKE_USAGE_STATE, process.env.CLAUDE_CONFIG_DIR]) mkdirSync(d, { recursive: true })
const bin = join(scratch, 'claude')
writeFileSync(bin, `#!/bin/sh\nexec "${process.execPath}" "${join(fixtures, 'fake-claude-usage.mjs')}" "$@"\n`)
chmodSync(bin, 0o755)
process.env.CLAUDE_BIN = bin

const hub = await import('../src/hub.ts')
const api = await import('../src/usage-api.ts')
const probe = await import('../src/usage-probe.ts')

const runFile = join(process.env.FAKE_USAGE_STATE!, 'run.json')
after(() => {
  api.useUsageApi(null)
  rmSync(scratch, { recursive: true, force: true })
})
beforeEach(() => rmSync(runFile, { force: true }))

const METHOD = 'usage_EXPERIMENTAL_MAY_CHANGE_DO_NOT_RELY_ON_THIS_API_YET'
const iso = (ms: number) => new Date(ms).toISOString()
const NOW = Date.now()
const WEEK_END = NOW + 3 * 24 * 3600_000
/** The real answer's shape, read 2026-11-02 (Max, no 5-hour window), with made-up numbers. */
const answer = (rate: Record<string, unknown> = {}) => ({
  session: { total_cost_usd: 0 },
  subscription_type: 'max',
  rate_limits_available: true,
  rate_limits: {
    five_hour: null,
    // Claude Code's own form of the time, microseconds and all.
    seven_day: { utilization: 34, resets_at: iso(WEEK_END).replace(/\.\d+Z$/, '.355474+00:00') },
    seven_day_sonnet: null,
    seven_day_opus: null,
    model_scoped: [{ display_name: 'Fable', utilization: 7, resets_at: iso(WEEK_END) }],
    some_new_internal_key: { anything: true },
    ...rate,
  },
  behaviors: null,
})
const standIn = (raw: unknown, seen?: unknown[]) => api.useUsageApi(async (_cwd, _env, work) => work({ [METHOD]: async (o: unknown) => (seen?.push(o), raw) } as any))
/** A look's own timing would hold the next one back only through timedLookDue; probeUsage always looks. */
const look = () => probe.probeUsage('test')

test('an answer in the real shape reads as the screen would: whole percents, its exact resets, no 5-hour window', () => {
  const r = api.readAnswer(answer(), NOW)
  assert.ok(r.ok)
  assert.equal(r.fiveHour, null)
  assert.equal(r.noFive, true)
  assert.equal(r.sevenDay!.pct, 34)
  assert.equal(Math.floor(r.sevenDay!.resetsAt! / 1000), Math.floor(WEEK_END / 1000))
  assert.deepEqual(r.models, [{ label: 'Fable', pct: 7, resetsAt: WEEK_END }])
})

test('floored as the screen floors; a five-hour window and Sonnet are read too; a name twice is one row', () => {
  const r = api.readAnswer(answer({
    five_hour: { utilization: 12.9, resets_at: iso(NOW + 3600_000) },
    seven_day_sonnet: { utilization: 3.5, resets_at: iso(WEEK_END) },
    model_scoped: [{ display_name: 'Fable', utilization: 41.2, resets_at: null }, { display_name: 'sonnet', utilization: 99, resets_at: null }],
  }), NOW)
  assert.ok(r.ok)
  assert.deepEqual(r.fiveHour, { pct: 12, resetsAt: NOW + 3600_000 })
  assert.equal(r.noFive, false)
  assert.deepEqual(r.models, [{ label: 'Sonnet', pct: 3, resetsAt: WEEK_END }, { label: 'Fable', pct: 41, resetsAt: null }])
})

test('an answer may be a snapshot up to a minute old, with no time on it: it never counts as newer than that', () => {
  const r = api.readAnswer(answer(), NOW)
  assert.ok(r.ok)
  assert.equal(r.at, NOW - api.SNAPSHOT_MS)
})

test('what isn\'t a full reading is a failed look, and says why', () => {
  const wrong: [unknown, RegExp][] = [
    [null, /wrong shape/],
    ['text', /wrong shape/],
    [[], /wrong shape/],
    [{ rate_limits_available: false, rate_limits: null }, /no plan limits/],
    // A 429 with nothing saved (measured on a stand-in for the endpoint).
    [{ rate_limits_available: true, rate_limits: null }, /rate_limits null/],
    [{ rate_limits_available: true }, /rate_limits null/],
    [{ rate_limits: 'soon' }, /wrong shape/],
    [answer({ model_scoped: undefined }), /no model_scoped/],
    [answer({ model_scoped: {} }), /wrong shape/],
    [answer({ model_scoped: [{ utilization: 1 }] }), /wrong shape/],
    [answer({ model_scoped: [{ display_name: 'Fable', utilization: null, resets_at: null }] }), /no number/],
    [answer({ model_scoped: [{ display_name: 'Fable', utilization: '7', resets_at: null }] }), /wrong shape/],
    [answer({ seven_day: null }), /no windows/],
    [answer({ seven_day: 34 }), /wrong shape/],
    [answer({ seven_day: { utilization: -1, resets_at: null } }), /wrong shape/],
    [answer({ seven_day: { utilization: 34, resets_at: 'next week' } }), /wrong shape/],
  ]
  for (const [raw, why] of wrong) {
    const r = api.readAnswer(raw, NOW)
    assert.equal(r.ok, false, JSON.stringify(raw))
    if (!r.ok) assert.match(r.why, why, JSON.stringify(raw))
  }
})

test('a look through the call: the meters and the weeks, the behaviours scan skipped, no terminal started', async () => {
  const seen: unknown[] = []
  standIn(answer(), seen)
  assert.equal(await look(), true)
  assert.deepEqual(seen, [{ skipBehaviors: true }])
  assert.equal(existsSync(runFile), false, 'Claude Code was not started in a terminal')
  const u = hub.getUsage()
  assert.equal(u.source, 'official')
  assert.equal(u.fiveHour, null)
  assert.equal(u.sevenDay!.pct, 34)
  assert.deepEqual(u.models!.map((m) => [m.label, m.pct]), [['Fable', 7]])
  assert.equal(u.modelsStale, false)
  assert.deepEqual(u.probe, { ok: true, since: null })
  // True as of a minute before it was asked, at the latest.
  assert.ok(u.modelsObservedAt! <= Date.now() - api.SNAPSHOT_MS)
  assert.ok(u.observedAt! <= Date.now() - api.SNAPSHOT_MS)
})

test('the wire stays as it was: a look through the call leaves the same fields a look at the screen does', async () => {
  standIn(answer())
  await look()
  const viaApi = Object.keys(hub.getUsage()).sort()
  api.useUsageApi(async () => {
    throw new Error('gone')
  })
  await look()
  assert.deepEqual(Object.keys(hub.getUsage()).sort(), viaApi)
})

test('the process asks for no settings, no transcript and no hooks or MCP servers', () => {
  const o = api.queryOptions('/x', { A: '1' }, '/bin/claude', new AbortController())
  assert.deepEqual(o.settingSources, [])
  assert.equal(o.persistSession, false)
  assert.equal(o.cwd, '/x')
  assert.equal(o.env.A, '1')
  assert.deepEqual(o.extraArgs, { settings: '{"disableAllHooks":true}', 'strict-mcp-config': null })
})

for (const [name, setUp] of [
  ['it throws', () => api.useUsageApi(async () => { throw new Error('boom') })],
  ['the method is gone', () => api.useUsageApi(async (_c, _e, work) => work({}))],
  ['a 429 left it nothing', () => standIn({ rate_limits_available: true, rate_limits: null })],
  ['the shape changed', () => standIn({ rate_limits: { seven_day: 'x' } })],
  ['an answer has no model_scoped', () => standIn(answer({ model_scoped: undefined }))],
] as [string, () => void][]) {
  test(`${name}: the screen is read instead`, async () => {
    setUp()
    assert.equal(await look(), true)
    assert.ok(existsSync(runFile), 'Claude Code was started in the hidden terminal')
    assert.deepEqual(hub.getUsage().models!.map((m) => m.label), ['Sonnet', 'Fable'])
  })
}

test('a method that\'s gone starts no second process: the SDK won\'t grow one before a restart', async () => {
  let started = 0
  api.useUsageApi(async (_c, _e, work) => {
    started++
    return work({})
  })
  assert.equal((await api.readUsageApi('/x', {})).ok, false)
  assert.equal((await api.readUsageApi('/x', {})).ok, false)
  assert.equal(started, 1)
})

test('a call that never answers gives up, and says so', async () => {
  api.useUsageApi(() => new Promise(() => {}))
  assert.deepEqual(await api.readUsageApi('/x', {}, 30), { ok: false, why: 'timed out' })
})

test('without a stand-in under tests, the real call is refused before anything starts', async () => {
  api.useUsageApi(null)
  assert.deepEqual(await api.readUsageApi('/x', {}), { ok: false, why: 'not_in_tests' })
})

test('rate limited on both ways: the known weeks and meters stay, marked stale, not wiped', async () => {
  standIn(answer())
  await look()
  const before = hub.getUsage()
  assert.deepEqual(before.models!.map((m) => m.label), ['Fable'])
  standIn({ rate_limits_available: true, rate_limits: null })
  process.env.FAKE_USAGE_SCREEN = screenFile('breakdown-unavailable.txt')
  try {
    await look()
  } finally {
    process.env.FAKE_USAGE_SCREEN = screenFile('max-fable.txt')
  }
  const u = hub.getUsage()
  assert.deepEqual(u.models!.map((m) => [m.label, m.pct]), before.models!.map((m) => [m.label, m.pct]))
  assert.equal(u.modelsObservedAt, before.modelsObservedAt)
  assert.equal(u.modelsStale, true)
  assert.ok(u.sevenDay)
})

test('nothing works at all: the last good reading stands', async () => {
  standIn(answer())
  await look()
  const before = hub.getUsage()
  api.useUsageApi(async () => {
    throw new Error('boom')
  })
  const held = process.env.CLAUDE_BIN
  process.env.CLAUDE_BIN = join(scratch, 'no-such-claude')
  const path = process.env.PATH
  process.env.PATH = ''
  try {
    assert.equal(await look(), false)
  } finally {
    process.env.CLAUDE_BIN = held
    process.env.PATH = path
  }
  const u = hub.getUsage()
  assert.deepEqual(u.models, before.models)
  assert.deepEqual(u.sevenDay, before.sevenDay)
})

test('a status line from the last minute isn\'t overruled by an answer that may be older', async () => {
  // More than the second offerUsage gives readings to be told apart, past the last test's: the hub takes this one on its time alone.
  const now = Date.now() + 2000
  const reset = hub.getUsage().sevenDay!.resetsAt
  hub.offerUsage(null, { pct: 50, resetsAt: reset }, now)
  standIn(answer({ seven_day: { utilization: 49, resets_at: iso(reset) } }))
  await look()
  assert.equal(hub.getUsage().sevenDay!.pct, 50)
})
