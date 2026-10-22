// The usage probe (usage-probe.ts) end to end, on a stand-in for Claude Code
// (fixtures/fake-claude-usage.mjs): a hidden shared terminal in a folder of its own, past the trust
// prompt, "/usage" typed and nothing else, the per-model weeks read into Usage, the terminal gone
// afterwards, and neither the terminal, its registry entry nor its transcript ever listed as a
// session. Also the per-model weeks in Usage: they roll over at their reset like the week, and a
// status line reading doesn't drop them.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-probe-')))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude-config')
process.env.HOME = join(scratch, 'home')
process.env.FAKE_USAGE_STATE = join(scratch, 'state')
const fixtures = join(dirname(fileURLToPath(import.meta.url)), 'fixtures')
process.env.FAKE_USAGE_SCREEN = join(fixtures, 'usage-screens', 'max-fable.txt')
for (const d of [process.env.HOME, process.env.FAKE_USAGE_STATE, process.env.CLAUDE_CONFIG_DIR]) mkdirSync(d, { recursive: true })
// Claude Code, as the probe finds it (util.ts findClaude reads CLAUDE_BIN first).
const bin = join(scratch, 'claude')
writeFileSync(bin, `#!/bin/sh\nexec "${process.execPath}" "${join(fixtures, 'fake-claude-usage.mjs')}" "$@"\n`)
chmodSync(bin, 0o755)
process.env.CLAUDE_BIN = bin

const hub = await import('../src/hub.ts')
const terms = await import('../src/terms.ts')
const probe = await import('../src/usage-probe.ts')
const { TERMS } = await import('../src/term/shared.ts')
const { hiddenDir, deleteHiddenTranscript } = await import('../src/term/hidden.ts')

after(() => rmSync(scratch, { recursive: true, force: true }))

const run = () => JSON.parse(readFileSync(join(process.env.FAKE_USAGE_STATE!, 'run.json'), 'utf8')) as { args: string[]; cwd: string; keys: string; pid: number; env: Record<string, string | null> }
const inProbe = () => hub.listSessions().filter((s) => s.cwd === probe.PROBE_DIR || realpathSync(s.cwd) === realpathSync(probe.PROBE_DIR))

test('the probe opens /usage in a hidden terminal, reads the model weeks and closes it; no session ever shows', async () => {
  const sent: any[] = []
  hub.addClient({ send: (s) => sent.push(JSON.parse(s)) })
  // What the daemon does meanwhile: find holders, poll Claude Code's registry. The stand-in says
  // it's busy while /usage is up: still no session, and nothing counts as working.
  const watch = setInterval(() => {
    terms.scanTerms()
    hub.pollRegistry()
    assert.deepEqual(inProbe(), [])
    assert.equal(hub.anythingWorking(), false)
  }, 100)
  let ok: boolean
  try {
    ok = await probe.probeUsage('test')
  } finally {
    clearInterval(watch)
  }
  assert.equal(ok, true)
  // The stand-in records the closing Esc a moment after the probe returns (slower on a loaded machine).
  let r = run()
  for (let i = 0; i < 40 && !r.keys.endsWith('\x1b'); i++) {
    await new Promise((done) => setTimeout(done, 50))
    r = run()
  }
  // Hooks and MCP servers off, its own session id, its own folder; the only thing typed: /usage.
  assert.deepEqual(r.args.slice(0, 3), ['--settings', '{"disableAllHooks":true}', '--strict-mcp-config'])
  // No auto-update, telemetry or error reports, and English whatever the Mac's locale.
  assert.deepEqual(r.env, { DISABLE_AUTOUPDATER: '1', DISABLE_TELEMETRY: '1', DISABLE_ERROR_REPORTING: '1', LANG: 'en_US.UTF-8', LC_ALL: 'en_US.UTF-8' })
  assert.equal(r.args[3], '--session-id')
  assert.match(r.args[4]!, /^[0-9a-f-]{36}$/)
  assert.equal(r.args.length, 5)
  assert.equal(realpathSync(r.cwd), realpathSync(probe.PROBE_DIR))
  assert.equal(r.keys.replace(/\x1b\[20[01]~/g, ''), '\r/usage\r\x1b')
  // The model weeks, in Usage and to the clients.
  const models = hub.getUsage().models
  assert.deepEqual(models?.map((m) => [m.label, m.pct]), [['Sonnet', 3], ['Fable', 41]])
  assert.ok(models!.every((m) => typeof m.resetsAt === 'number' && m.resetsAt > 0))
  assert.deepEqual(sent.filter((m) => m.t === 'usage').at(-1)?.usage.models, models)
  // The terminal ended and left nothing behind.
  for (let i = 0; i < 40 && readdirSync(TERMS).some((f) => f.endsWith('.json')); i++) await new Promise((res) => setTimeout(res, 100))
  assert.deepEqual(readdirSync(TERMS).filter((f) => f.endsWith('.json') || f.endsWith('.spec')), [])
  assert.throws(() => process.kill(r.pid, 0))
  // Its transcript (the stand-in writes one, as Claude Code does) is deleted, with the project
  // folder the probe made; nothing of the config folder's is left.
  const projects = join(process.env.CLAUDE_CONFIG_DIR!, 'projects')
  assert.deepEqual(readdirSync(projects), [])
  hub.discover()
  assert.deepEqual(inProbe(), [])
})

test("the probe's transcript is deleted exactly: that file, the folder only if it made it, nothing outside", () => {
  const cfg = join(scratch, 'cfg2')
  const cwd = hiddenDir(join(scratch, 'probe cwd'))
  const id = '33333333-3333-4333-8333-333333333333'
  const dir = join(cfg, 'projects', cwd.replace(/[^a-zA-Z0-9]/g, '-'))
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, `${id}.jsonl`), '{}\n')
  writeFileSync(join(dir, 'other.jsonl'), '{}\n')
  const outside = join(cfg, 'projects', `${id}.jsonl`)
  writeFileSync(outside, '{}\n')
  // The folder was there before, and holds another transcript: only the file goes.
  assert.equal(deleteHiddenTranscript(cfg, cwd, id, { [dir]: true }), true)
  assert.deepEqual(readdirSync(dir), ['other.jsonl'])
  assert.equal(existsSync(outside), true)
  // Refused: ids that aren't a session id (a path out of the folder), and a missing file.
  assert.equal(deleteHiddenTranscript(cfg, cwd, '../../projects/' + id, { [dir]: true }), false)
  assert.equal(deleteHiddenTranscript(cfg, cwd, id, { [dir]: true }), false)
  assert.equal(existsSync(outside), true)
  // A folder the probe made stays while it holds something; goes when empty.
  rmSync(join(dir, 'other.jsonl'))
  writeFileSync(join(dir, `${id}.jsonl`), '{}\n')
  writeFileSync(join(dir, 'other.jsonl'), '{}\n')
  assert.equal(deleteHiddenTranscript(cfg, cwd, id, {}), true)
  assert.equal(existsSync(dir), true)
  rmSync(join(dir, 'other.jsonl'))
  writeFileSync(join(dir, `${id}.jsonl`), '{}\n')
  assert.equal(deleteHiddenTranscript(cfg, cwd, id, {}), true)
  assert.equal(existsSync(dir), false)
})

for (const name of ['breakdown-unavailable.txt', 'partial.txt']) {
  test(`a screen saying the breakdown is unavailable (${name}) keeps the last model weeks, stale, and looks again in 2 minutes`, async () => {
    const week = 7 * 24 * 3600_000
    const good = [{ label: 'Fable', pct: 41, resetsAt: Date.now() + week }]
    const before = Date.now()
    hub.offerModelUsage(good)
    assert.equal(hub.getUsage().modelsStale, false)
    process.env.FAKE_USAGE_SCREEN = join(fixtures, 'usage-screens', name)
    try {
      assert.equal(await probe.probeUsage('test'), true)
    } finally {
      process.env.FAKE_USAGE_SCREEN = join(fixtures, 'usage-screens', 'max-fable.txt')
    }
    const u = hub.getUsage()
    assert.deepEqual(u.models, good)
    assert.equal(u.modelsStale, true)
    assert.ok(u.modelsObservedAt! >= before && u.modelsObservedAt! <= Date.now())
    assert.equal(probe.waitMs(), 2 * 60_000)
    // The next good look replaces them and clears the mark.
    assert.equal(await probe.probeUsage('test'), true)
    assert.equal(hub.getUsage().modelsStale, false)
    assert.deepEqual(hub.getUsage().models?.map((m) => m.label), ['Sonnet', 'Fable'])
    assert.equal(probe.waitMs(), 15 * 60_000)
  })
}

test("a registry entry in a hidden folder isn't a session; one anywhere else is", () => {
  const sessions = join(process.env.CLAUDE_CONFIG_DIR!, 'sessions')
  mkdirSync(sessions, { recursive: true })
  const shown = join(scratch, 'project')
  mkdirSync(shown, { recursive: true })
  const hidden = hiddenDir(join(scratch, 'hidden'))
  // A live pid (this test's own) is all the registry poll asks for.
  writeFileSync(join(sessions, `${process.pid}.json`), JSON.stringify({ pid: process.pid, sessionId: '11111111-1111-4111-8111-111111111111', cwd: hidden, startedAt: Date.now(), kind: 'interactive', status: 'idle' }))
  hub.pollRegistry()
  assert.equal(hub.listSessions().some((s) => s.cwd === hidden), false)
  writeFileSync(join(sessions, `${process.pid}.json`), JSON.stringify({ pid: process.pid, sessionId: '22222222-2222-4222-8222-222222222222', cwd: shown, startedAt: Date.now(), kind: 'interactive', status: 'idle' }))
  hub.pollRegistry()
  assert.equal(hub.listSessions().some((s) => s.cwd === shown), true)
  rmSync(join(sessions, `${process.pid}.json`))
})

test('no Claude Code: the probe says so and starts nothing', async () => {
  const r = await probe.runProbe({ file: null })
  assert.deepEqual([r.ok, r.why], [false, 'no_claude'])
  assert.equal(existsSync(join(TERMS, 'x.spec')), false)
})

test('model weeks roll over at their reset, are replaced whole, and survive status line readings', () => {
  const now = Date.now()
  const week = 7 * 24 * 3600_000
  hub.offerModelUsage([
    { label: 'Fable', pct: 88, resetsAt: now - 3600_000 },
    { label: 'Sonnet', pct: 12, resetsAt: now + 3600_000 },
  ])
  const rolled = hub.getUsage().models!
  // Past its reset: an empty week, ending a week after the last one.
  assert.deepEqual(rolled[0], { label: 'Fable', pct: 0, resetsAt: now - 3600_000 + week })
  assert.deepEqual(rolled[1], { label: 'Sonnet', pct: 12, resetsAt: now + 3600_000 })
  hub.offerUsage({ pct: 30, resetsAt: now + 2 * 3600_000 }, null, now + 5_000)
  assert.equal(hub.getUsage().models!.length, 2)
  // The next look found only Fable: Sonnet's row is gone, so is its week.
  hub.offerModelUsage([{ label: 'Fable', pct: 5, resetsAt: now + week }])
  assert.deepEqual(hub.getUsage().models, [{ label: 'Fable', pct: 5, resetsAt: now + week }])
  // A screen that gave no reset keeps the number as it is.
  hub.offerModelUsage([{ label: 'Fable', pct: 7, resetsAt: null }])
  assert.deepEqual(hub.getUsage().models, [{ label: 'Fable', pct: 7, resetsAt: null }])
})
