// A Monitor running keeps its session in Working; a plain background shell doesn't (docs/PROTOCOL.md,
// "Background commands"; docs/DECISIONS.md, "A Monitor is Working, a shell is a mark"). The lines are
// the shapes Claude Code 2.1.317 wrote, in test/fixtures/background-shells; monitors-e2e.test.ts has the
// real program. A Monitor must never leave a session Working for good.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { appendFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-test-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
after(() => rmSync(scratch, { recursive: true, force: true }))

const hub = await import('../src/hub.ts')
const agents = await import('../src/agents.ts')
const { handleHook } = await import('../src/hooks.ts')
const { sessionsWorking } = await import('../src/awake.ts')
const here = dirname(fileURLToPath(import.meta.url))

let n = 0
const newId = () => `eeeeeeee-1111-4000-8000-${String(++n).padStart(12, '0')}`
const line = (o: object) => JSON.stringify(o) + '\n'
const HOUR = 3_600_000
const iso = (ago: number) => new Date(Date.now() - ago).toISOString()

/** A Monitor's call and its result, as main.jsonl's lines 13 and 16 have them, [ago] ms ago. */
function monitor(task: string, ago: number, r: { timeoutMs?: number; persistent?: boolean }): object[] {
  return [
    { type: 'assistant', uuid: `c-${task}`, timestamp: iso(ago), message: { id: `m-${task}`, role: 'assistant', content: [{ type: 'tool_use', id: `tu-${task}`, name: 'Monitor', input: { description: `Watch ${task}`, command: 'tail -f app.log', ...(r.timeoutMs != null ? { timeout_ms: r.timeoutMs } : {}), persistent: r.persistent === true } }] } },
    { type: 'user', uuid: `r-${task}`, timestamp: iso(ago - 50), message: { role: 'user', content: [{ tool_use_id: `tu-${task}`, type: 'tool_result', content: `Monitor started (task ${task})` }] }, toolUseResult: { taskId: task, ...r } },
  ]
}
/** A plain background command's call and its result, as main.jsonl's lines 0 and 4 have them. */
function shell(task: string, ago: number): object[] {
  return [
    { type: 'assistant', uuid: `c-${task}`, timestamp: iso(ago), message: { id: `m-${task}`, role: 'assistant', content: [{ type: 'tool_use', id: `tu-${task}`, name: 'Bash', input: { command: 'npm run dev', description: 'Dev server', run_in_background: true } }] } },
    { type: 'user', uuid: `r-${task}`, timestamp: iso(ago - 50), message: { role: 'user', content: [{ tool_use_id: `tu-${task}`, type: 'tool_result', content: `Command running in background with ID: ${task}.` }] }, toolUseResult: { stdout: '', stderr: '', interrupted: false, backgroundTaskId: task } },
  ]
}
/** Its stream ending: a notification with a status, first as a queue entry. A Monitor's events have none. */
const ended = (task: string) => ({ type: 'queue-operation', operation: 'enqueue', timestamp: iso(0), content: `<task-notification>\n<task-id>${task}</task-id>\n<tool-use-id>tu-${task}</tool-use-id>\n<status>completed</status>\n<summary>Monitor "Watch ${task}" stream ended</summary>\n</task-notification>` })
const event = (task: string) => ({ type: 'queue-operation', operation: 'enqueue', timestamp: iso(0), content: `<task-notification>\n<task-id>${task}</task-id>\n<summary>Monitor event: "Watch ${task}"</summary>\n<event>tick 1</event>\n</task-notification>` })

function session(id: string, lines: object[], state = 'idle') {
  const dir = join(scratch, 'claude', 'projects', '-tmp-monitors')
  mkdirSync(join(dir, id, 'subagents'), { recursive: true })
  const transcriptPath = join(dir, `${id}.jsonl`)
  writeFileSync(transcriptPath, lines.map(line).join(''))
  const r = hub.upsertSession(id, { cwd: '/tmp/monitors', transcriptPath, origin: 'terminal', state: state as never })
  hub.ensureParsed(r)
  agents.pollAgents()
  return {
    transcriptPath,
    add(more: object[]) {
      appendFileSync(transcriptPath, more.map(line).join(''))
      hub.pollTranscripts()
      agents.pollAgents()
    },
  }
}
/** The session as a client hears it: what's undefined isn't on the wire. */
const wire = (id: string) => JSON.parse(JSON.stringify(hub.view(hub.getSession(id)!)))
const row = (id: string, task: string) => agents.listAgents(id).shells.find((s) => s.id === task)!
const hook = (o: object) => handleHook(o as never, {} as never, {} as never)
/** Moves the clock on by [by] ms for [run]. */
function later(by: number, run: () => void): void {
  const real = Date.now
  Date.now = () => real() + by
  try {
    run()
  } finally {
    Date.now = real
  }
}

test('a plain shell alone: counted as a shell, no Monitor on the wire, nothing holds the session', () => {
  const id = newId()
  session(id, shell('bdev00001', 60_000))
  const s = wire(id)
  assert.equal(s.state, 'idle')
  assert.equal(s.shellsRunning, 1)
  assert.equal('monitorsRunning' in s, false, 'an older client and a session with none see no field')
  assert.equal(sessionsWorking(), false, 'a dev server keeps nothing awake')
})

test('a Monitor alone: it counts as a Monitor, among the shells so an older client reads the same', () => {
  const id = newId()
  session(id, monitor('bmon00001', 60_000, { timeoutMs: 15 * 60_000, persistent: false }))
  const s = wire(id)
  // The session rests: only the clients' one rule (Session.busy) makes it Working, so an older client is as it was.
  assert.equal(s.state, 'idle')
  assert.equal(s.shellsRunning, 1)
  assert.equal(s.monitorsRunning, 1)
  assert.equal(row(id, 'bmon00001').kind, 'monitor')
  assert.equal(sessionsWorking(), true, 'Stay awake holds while it watches')
  hub.upsertSession(id, { state: 'ended' })
  agents.pollAgents()
})

test('both: each is counted apart, and the shell outlives the Monitor', () => {
  const id = newId()
  const s = session(id, [...shell('bdev00002', 60_000), ...monitor('bmon00002', 30_000, { timeoutMs: 15 * 60_000 })])
  assert.equal(wire(id).shellsRunning, 2)
  assert.equal(wire(id).monitorsRunning, 1)
  s.add([ended('bmon00002')])
  assert.equal(wire(id).shellsRunning, 1)
  assert.equal('monitorsRunning' in wire(id), false)
  hub.upsertSession(id, { state: 'ended' })
  agents.pollAgents()
})

test('a Monitor ends: its stream, not its events', () => {
  const id = newId()
  const s = session(id, monitor('bmon00003', 60_000, { timeoutMs: 15 * 60_000 }))
  s.add([event('bmon00003')])
  assert.equal(wire(id).monitorsRunning, 1, 'an event ends nothing')
  s.add([ended('bmon00003')])
  assert.equal('monitorsRunning' in wire(id), false)
  assert.equal(row(id, 'bmon00003').status, 'done')
  assert.equal(sessionsWorking(), false)
})

test('a Monitor past its stated time stops counting by itself, with no word from Claude Code', () => {
  const id = newId()
  session(id, monitor('bmon00004', 60_000, { timeoutMs: 15 * 60_000 }))
  assert.equal(wire(id).monitorsRunning, 1)
  later(20 * 60_000, () => {
    agents.pollAgents()
    assert.equal('monitorsRunning' in wire(id), false)
    assert.equal('shellsRunning' in wire(id), false)
    assert.equal(row(id, 'bmon00004').status, 'stopped')
  })
})

test('Claude Code\'s Stop list no longer naming it ends it; naming it keeps it, and it stays a Monitor', async () => {
  const id = newId()
  const s = session(id, monitor('bmon00005', 60_000, { timeoutMs: 15 * 60_000 }))
  const stop = (tasks: object[]) => hook({ session_id: id, cwd: '/tmp/monitors', hook_event_name: 'Stop', background_tasks: tasks })
  // A Stop's list gives a Monitor as a shell (recorded): the call's result said what it is.
  await stop([{ id: 'bmon00005', type: 'shell', status: 'running', description: 'Watch', command: 'tail -f app.log' }])
  assert.equal(wire(id).monitorsRunning, 1)
  await stop([])
  assert.equal('monitorsRunning' in wire(id), false)
  assert.equal(row(id, 'bmon00005').status, 'done')
  s.add([])
})

test('one only a Stop list names reads as a shell, and its call\'s result, read later, makes it a Monitor', async () => {
  const id = newId()
  const s = session(id, [])
  await hook({ session_id: id, cwd: '/tmp/monitors', hook_event_name: 'Stop', background_tasks: [{ id: 'bmon00006', type: 'shell', status: 'running', description: 'Watch', command: 'tail -f app.log' }] })
  assert.equal(wire(id).shellsRunning, 1)
  assert.equal('monitorsRunning' in wire(id), false)
  s.add(monitor('bmon00006', 1000, { timeoutMs: 15 * 60_000 }))
  assert.equal(wire(id).shellsRunning, 1)
  assert.equal(wire(id).monitorsRunning, 1)
  hub.upsertSession(id, { state: 'ended' })
  agents.pollAgents()
})

test('the session ending, however it goes, leaves no Monitor: not known, not counted, not holding anything', async () => {
  for (const reason of ['prompt_input_exit', 'other']) {
    const id = newId()
    session(id, monitor('bmon00007', 60_000, { timeoutMs: 15 * 60_000 }))
    assert.equal(wire(id).monitorsRunning, 1)
    await hook({ session_id: id, cwd: '/tmp/monitors', hook_event_name: 'SessionEnd', reason })
    assert.equal('monitorsRunning' in wire(id), false, reason)
    assert.equal('shellsRunning' in wire(id), false, reason)
    assert.equal(row(id, 'bmon00007').status, 'unknown', reason)
    hub.upsertSession(id, { state: 'ended' })
    agents.pollAgents()
  }
  // Its record marked ended, with nothing said about the command.
  const id = newId()
  session(id, monitor('bmon00008', 60_000, { persistent: true }))
  assert.equal(wire(id).monitorsRunning, 1)
  hub.upsertSession(id, { state: 'ended' })
  agents.pollAgents()
  assert.equal('monitorsRunning' in wire(id), false)
  assert.equal(sessionsWorking(), false)
})

test('a process newer than the Monitor means it\'s not known any more, so a resume brings no old Working back', () => {
  const id = newId()
  session(id, monitor('bmon00009', 60_000, { timeoutMs: 15 * 60_000 }))
  assert.equal(wire(id).monitorsRunning, 1)
  agents.noteProcess(id, Date.now() - 1000)
  agents.pollAgents()
  assert.equal('monitorsRunning' in wire(id), false)
  assert.equal(row(id, 'bmon00009').status, 'unknown')
  hub.upsertSession(id, { state: 'ended' })
  agents.pollAgents()
})

test('a persistent Monitor holds its session for 12 hours at most, then it is only a shell still running', () => {
  const id = newId()
  session(id, monitor('bmon00010', 60_000, { timeoutMs: 3_600_000, persistent: true }))
  assert.equal(wire(id).monitorsRunning, 1)
  later(11 * HOUR, () => {
    agents.pollAgents()
    assert.equal(wire(id).monitorsRunning, 1, 'still within its 12 hours')
  })
  later(12 * HOUR, () => {
    agents.pollAgents()
    const s = wire(id)
    assert.equal('monitorsRunning' in s, false, 'no more Working')
    assert.equal(s.shellsRunning, 1, 'it may well still run, so it stays counted')
    assert.equal(row(id, 'bmon00010').status, 'running')
    assert.equal(row(id, 'bmon00010').kind, 'monitor')
    assert.equal(sessionsWorking(), false, 'nor awake')
  })
  hub.upsertSession(id, { state: 'ended' })
  agents.pollAgents()
})

test('a Monitor whose result states no time goes by the same 12 hours', () => {
  const id = newId()
  session(id, monitor('bmon00011', 60_000, {}))
  assert.equal(wire(id).monitorsRunning, 1)
  later(12 * HOUR, () => {
    agents.pollAgents()
    assert.equal('monitorsRunning' in wire(id), false)
  })
  hub.upsertSession(id, { state: 'ended' })
  agents.pollAgents()
})

test('a daemon restart reads the same answer from the transcript: running, past its time, and past the 12 hours', () => {
  const id = newId()
  const { transcriptPath } = session(id, [
    ...monitor('bmon00012', 60_000, { timeoutMs: 15 * 60_000 }),
    ...monitor('bmon00013', 30 * 60_000, { timeoutMs: 15 * 60_000 }),
    ...monitor('bmon00014', 13 * HOUR, { persistent: true }),
    ...shell('bdev00014', 60_000),
  ])
  const run = (state: string) => {
    const code = `
      const hub = await import(${JSON.stringify(join(here, '../src/hub.ts'))})
      const agents = await import(${JSON.stringify(join(here, '../src/agents.ts'))})
      const r = hub.upsertSession(${JSON.stringify(id)}, { cwd: '/tmp/monitors', transcriptPath: ${JSON.stringify(transcriptPath)}, origin: 'terminal', state: ${JSON.stringify(state)} })
      hub.ensureParsed(r)
      agents.pollAgents()
      console.log(JSON.stringify({ s: JSON.parse(JSON.stringify(hub.view(hub.getSession(${JSON.stringify(id)})))), list: agents.listAgents(${JSON.stringify(id)}).shells }))
      process.exit(0)
    `
    return JSON.parse(execFileSync(process.execPath, ['--input-type=module', '-e', code], { env: { ...process.env, HYPERNOVA_SUPPORT: join(scratch, 'support-restarted'), HYPERNOVA_LOGS: join(scratch, 'logs2') }, encoding: 'utf8' }).trim().split('\n').pop()!)
  }
  const live = run('idle')
  const status = (o: { list: { id: string; status: string }[] }, task: string) => o.list.find((x) => x.id === task)!.status
  assert.equal(status(live, 'bmon00012'), 'running')
  assert.equal(status(live, 'bmon00013'), 'stopped', 'past its time while the daemon was down')
  assert.equal(status(live, 'bmon00014'), 'running')
  // The running one, then the two the shell mark may say: the persistent one past its 12 hours, and the dev server.
  assert.equal(live.s.monitorsRunning, 1)
  assert.equal(live.s.shellsRunning, 3)
  // Its session gone by then: nothing is left holding it.
  const gone = run('ended')
  assert.equal('monitorsRunning' in gone.s, false)
  assert.equal('shellsRunning' in gone.s, false)
  hub.upsertSession(id, { state: 'ended' })
  agents.pollAgents()
})
