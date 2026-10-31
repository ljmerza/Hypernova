// Session.turnStartedAt, the session sheet's "This turn" and Home's "for 12 min": set as a session
// goes into a turn (or from the time the patch brings: Claude Code's registry stamp), kept through
// a stop for the owner's answer, cleared when the turn is over.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-turn-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
for (const d of [process.env.HOME, process.env.ZDOTDIR, process.env.CLAUDE_CONFIG_DIR]) mkdirSync(d, { recursive: true })
after(() => rmSync(scratch, { recursive: true, force: true }))

const hub = await import('../src/hub.ts')
const cwd = join(scratch, 'proj')
mkdirSync(cwd, { recursive: true })
const turn = (id: string) => hub.listSessions().find((s) => s.id === id)!.turnStartedAt
const wait = (ms: number) => new Promise((r) => setTimeout(r, ms))

test('a turn starts, holds through needing you, and ends', async () => {
  hub.upsertSession('t1', { cwd })
  assert.equal(turn('t1'), null)
  const t0 = Date.now()
  hub.setState('t1', 'working')
  const started = turn('t1')!
  assert.ok(started >= t0 && started <= Date.now())
  await wait(5)
  hub.setState('t1', 'needs_you')
  hub.setState('t1', 'working', 'Bash: npm test')
  assert.equal(turn('t1'), started)
  hub.setState('t1', 'idle')
  assert.equal(turn('t1'), null)
  // The next prompt is a new turn.
  await wait(5)
  hub.setState('t1', 'starting')
  assert.ok(turn('t1')! > started)
  hub.setState('t1', 'ended')
  assert.equal(turn('t1'), null)
})

test('a session first seen working is in a turn from then; a patch without a state keeps it', () => {
  hub.upsertSession('t2', { cwd, state: 'working' })
  const started = turn('t2')!
  assert.ok(started > 0)
  hub.upsertSession('t2', { activity: 'Reading App.kt', lastActivityAt: Date.now() })
  assert.equal(turn('t2'), started)
  hub.upsertSession('t2', { state: 'error' })
  assert.equal(turn('t2'), null)
})

test("a time the patch brings (the registry's stamp, seen mid-turn after a restart) is the turn's start", () => {
  hub.upsertSession('t3', { cwd, state: 'working', turnStartedAt: 1_000 })
  assert.equal(turn('t3'), 1_000)
  hub.setState('t3', 'needs_you')
  hub.setState('t3', 'working')
  assert.equal(turn('t3'), 1_000)
  hub.setState('t3', 'idle')
  assert.equal(turn('t3'), null)
})
