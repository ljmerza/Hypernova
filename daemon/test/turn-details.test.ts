// What the status bar's opened panel says of a turn (docs/PROTOCOL.md, Session.turn): its clock without
// the stops for the owner's answer, its tokens, the step it's on and how many commands run at once.
import { after, test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, realpathSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-turn-')))
for (const k of Object.keys(process.env)) if (/^(CLAUDE|ANTHROPIC|HYPERNOVA|HN_)/.test(k)) delete process.env[k]
Object.assign(process.env, { HYPERNOVA_SUPPORT: join(scratch, 'support'), HYPERNOVA_LOGS: join(scratch, 'logs'), CLAUDE_CONFIG_DIR: join(scratch, 'claude'), HOME: join(scratch, 'home') })

const { boxLines, spinnerOf } = await import('../src/term/shared.ts')
const { feedLine, newParseState, turnOutput } = await import('../src/normalize.ts')
const hub = await import('../src/hub.ts')

const FIXTURES = join(import.meta.dirname, 'fixtures', 'spinner-screens')
const sleep = (ms: number) => new Promise((ok) => setTimeout(ok, ms))
// A running Bash call has a timer watching its output: ended here, or the process stays up for it.
after(() => {
  for (const r of hub.listSessionRecs()) for (const e of r.parse?.events ?? []) if (e.kind === 'tool') e.status = 'done'
  rmSync(scratch, { recursive: true, force: true })
})

test('spinner lines Claude Code really wrote: its time and tokens, or nothing when it doesn’t read as one', () => {
  const lines = JSON.parse(readFileSync(join(FIXTURES, 'lines-2.1.317.json'), 'utf8')) as Array<{ line: string; seconds?: number; tokens?: number | null; none?: boolean }>
  assert.ok(lines.length > 10)
  for (const l of lines) assert.deepEqual(spinnerOf([l.line]), l.none ? null : { seconds: l.seconds, tokens: l.tokens }, l.line)
})

test('a whole screen: the spinner is found above the box, under what else is there', () => {
  const screen = readFileSync(join(FIXTURES, 'queued-while-a-command-runs-2.1.317.txt'), 'utf8').split('\n')
  assert.deepEqual(spinnerOf(boxLines(screen)!.above), { seconds: 9, tokens: 800 })
})

test('spinner wording it doesn’t know is no row, not a wrong one', () => {
  assert.equal(spinnerOf([]), null)
  assert.equal(spinnerOf(['Moonwalking (8m 16s · 2.9k tokens)']), null)
  assert.equal(spinnerOf(['✢ Moonwalking… (some new words)']), null)
  assert.deepEqual(spinnerOf(['✢ Moonwalking… (8m 16s · 2.9k toks)']), { seconds: 496, tokens: null })
  assert.deepEqual(spinnerOf(['✢ Moonwalking… (↓ 1.2m tokens · thinking)']), { seconds: null, tokens: 1_200_000 })
})

const reply = (id: string, tokens: number, ts: string) => ({ type: 'assistant', uuid: `${id}-${tokens}-${ts}`, timestamp: ts, message: { id, role: 'assistant', model: 'fake-model', content: [{ type: 'text', text: 'Hi' }], usage: { output_tokens: tokens } } })
const prompt = (text: string, ts: string) => ({ type: 'user', uuid: `u-${ts}`, timestamp: ts, message: { role: 'user', content: text } })

test('a turn’s tokens: each reply once, across steps and a prompt taken in mid-turn, from 0 for a turn of its own', () => {
  const st = newParseState()
  feedLine(st, prompt('[Hypernova test] first', '2026-11-03T21:00:00.000Z'))
  assert.equal(turnOutput(st), 0)
  // Logged once per content block, its output growing.
  feedLine(st, reply('m1', 2, '2026-11-03T21:00:01.000Z'))
  feedLine(st, reply('m1', 1500, '2026-11-03T21:00:02.000Z'))
  feedLine(st, reply('m2', 1400, '2026-11-03T21:00:10.000Z'))
  assert.equal(turnOutput(st), 2900)
  // A prompt queued while it worked, taken in mid-turn: the spinner goes on counting (measured on 2.1.317).
  feedLine(st, { type: 'queue-operation', operation: 'enqueue', timestamp: '2026-11-03T21:00:11.000Z', content: 'more' })
  feedLine(st, { type: 'queue-operation', operation: 'remove', timestamp: '2026-11-03T21:00:12.000Z' })
  feedLine(st, { type: 'attachment', uuid: 'att', timestamp: '2026-11-03T21:00:12.000Z', attachment: { type: 'queued_command', prompt: 'more' } })
  feedLine(st, reply('m3', 900, '2026-11-03T21:00:13.000Z'))
  assert.equal(turnOutput(st), 3800)
  // A subagent's reply left in this file isn't the main agent's.
  feedLine(st, { ...reply('side', 5000, '2026-11-03T21:00:14.000Z'), isSidechain: true })
  assert.equal(turnOutput(st), 3800)
  // One left in the queue until the turn ended starts its own: the spinner starts again too.
  feedLine(st, prompt('[Hypernova test] next', '2026-11-03T21:00:20.000Z'))
  assert.equal(turnOutput(st), 0)
  feedLine(st, reply('m4', 300, '2026-11-03T21:00:21.000Z'))
  assert.equal(turnOutput(st), 300)
})

function session(id: string) {
  hub.upsertSession(id, { cwd: join(scratch, 'proj'), origin: 'terminal' })
  hub.ensureParsed(hub.getSession(id)!)
  return id
}
const turnOf = (id: string) => hub.view(hub.getSession(id)!).turn

test('Session.turn is there only in a turn', () => {
  const id = session('turn-only')
  assert.equal(turnOf(id), undefined)
  hub.setState(id, 'working', 'Thinking')
  assert.deepEqual(turnOf(id), { waitedMs: 0, waitingSince: null, tokens: null, step: null })
  hub.setState(id, 'idle')
  assert.equal(turnOf(id), undefined)
  assert.equal('turnWaits' in hub.view(hub.getSession(id)!), false, 'its bookkeeping stays home')
})

test('a stop for the owner’s answer is counted apart, and a new turn starts without it', async () => {
  const id = session('turn-stop')
  hub.setState(id, 'working', 'Thinking')
  const started = hub.getSession(id)!.turnStartedAt
  hub.setState(id, 'needs_you', 'wants to run touch x')
  const since = turnOf(id)!.waitingSince
  assert.ok(since && since >= started!)
  await sleep(60)
  hub.setState(id, 'needs_you', 'wants to run touch y')
  assert.equal(turnOf(id)!.waitingSince, since, 'a second ask while it waits is the same stop')
  hub.setState(id, 'working', 'Thinking')
  const t = turnOf(id)!
  assert.equal(t.waitingSince, null)
  assert.ok(t.waitedMs >= 50, `waited ${t.waitedMs}`)
  assert.equal(hub.getSession(id)!.turnStartedAt, started, 'turnStartedAt stays as it was: the sheet and Home go by it')
  hub.setState(id, 'idle')
  hub.setState(id, 'working', 'Thinking')
  assert.equal(turnOf(id)!.waitedMs, 0)
})

test('the step: the call running now, its whole command masked, and how many commands run at once', () => {
  const id = session('turn-step')
  hub.setState(id, 'working', 'Thinking')
  const key = 'sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789'
  const long = `cd ~/projects/_worktrees/${'x'.repeat(300)} && ANTHROPIC_API_KEY=${key} gh run watch 123\necho second line`
  hub.startTool(id, 'b1', 'Bash', { command: long })
  const one = turnOf(id)!.step!
  assert.equal(one.tool, 'Bash')
  assert.equal(one.running, 1)
  assert.ok(one.command!.includes('x'.repeat(300)), 'whole, not the first line’s first part')
  assert.ok(one.command!.endsWith('echo second line'), 'and its other lines')
  assert.ok(!one.command!.includes(key), 'masked')
  for (let n = 2; n <= 6; n++) hub.startTool(id, `b${n}`, 'Bash', { command: `sleep ${n}` })
  assert.equal(turnOf(id)!.step!.running, 6)
  // Done, by a hook or the transcript.
  const done = hub.getSession(id)!.parse!.byId.get('b6') as any
  done.status = 'done'
  hub.emitEvent(id, 'b6')
  assert.equal(turnOf(id)!.step!.running, 5)
  // A tool with no command: its name, no command, and the commands still count.
  hub.startTool(id, 'e1', 'Edit', { file_path: '/a/b.kt' })
  const edit = turnOf(id)!.step!
  assert.equal(edit.tool, 'Edit')
  assert.equal(edit.command, null)
  assert.equal(edit.running, 5)
})

test('calls of one reply Claude Code runs in turn: only the one it started counts, and its time is its own', () => {
  const id = session('turn-in-turn')
  hub.setState(id, 'working', 'Thinking')
  const st = hub.getSession(id)!.parse!
  // All six are in the transcript at once; PreToolUse says each as it starts (measured on 2.1.317).
  for (let n = 1; n <= 6; n++) feedLine(st, { type: 'assistant', uuid: `r${n}`, timestamp: new Date().toISOString(), message: { id: 'six', role: 'assistant', model: 'fake-model', content: [{ type: 'tool_use', id: `s${n}`, name: 'Bash', input: { command: `step ${n}` } }] } })
  hub.startTool(id, 's1', 'Bash', { command: 'step 1' })
  const s = turnOf(id)!.step!
  assert.equal(s.running, 1)
  assert.equal(s.command, 'step 1')
})

test('a call an older turn left running isn’t this turn’s step', () => {
  const id = session('turn-stale')
  const st = hub.getSession(id)!.parse!
  feedLine(st, { type: 'assistant', uuid: 'old', timestamp: new Date(Date.now() - 3600_000).toISOString(), message: { id: 'old', role: 'assistant', model: 'fake-model', content: [{ type: 'tool_use', id: 'old-call', name: 'Bash', input: { command: 'killed' } }] } })
  hub.setState(id, 'working', 'Thinking')
  assert.equal(turnOf(id)!.step, null)
})

test('tokens: the transcript’s, or the spinner’s reading while it’s ahead, one reading in 5 seconds at most', () => {
  const id = session('turn-screen')
  hub.setState(id, 'working', 'Thinking')
  hub.noteScreenTokens(id, 2900)
  assert.equal(turnOf(id)!.tokens, 2900)
  hub.noteScreenTokens(id, 3100)
  assert.equal(turnOf(id)!.tokens, 2900, 'too soon after the last')
  hub.setState(id, 'idle')
  hub.noteScreenTokens(id, 500)
  assert.equal(turnOf(id), undefined, 'out of a turn it’s nothing')
  hub.setState(id, 'working', 'Thinking')
  assert.equal(turnOf(id)!.tokens, null, 'a new turn starts with none')
  hub.noteScreenTokens(id, 1600, 16)
  assert.equal(turnOf(id)!.tokens, null, 'a reading that says 16s, a moment into this turn, is the last turn’s')
  hub.noteScreenTokens(id, 40, 0)
  assert.equal(turnOf(id)!.tokens, 40)
})

test('Codex’s count: its thread’s total less where the turn began', () => {
  const id = session('turn-codex')
  hub.setState(id, 'working', 'Thinking')
  hub.noteThreadOutput(id, 5000, 200)
  assert.equal(turnOf(id)!.tokens, 200)
  hub.noteThreadOutput(id, 5300, 300)
  assert.equal(turnOf(id)!.tokens, 500)
  hub.setState(id, 'idle')
  hub.setState(id, 'working', 'Thinking')
  assert.equal(turnOf(id)!.tokens, null)
  hub.noteThreadOutput(id, 5400, 100)
  assert.equal(turnOf(id)!.tokens, 100)
  hub.noteThreadOutput(id, Number.NaN, Number.NaN)
  assert.equal(turnOf(id)!.tokens, 100, 'a report without the figure changes nothing')
})
