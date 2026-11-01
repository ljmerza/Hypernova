// The chat's own calls: a session's usage (tokens, cost, the context window), Claude's to-do list,
// and "Rewind to here" (docs/PROTOCOL.md, "Session usage", "Rewind"). Fixtures are written by hand
// in the shapes Claude Code records; none is copied from a real transcript.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { appendFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'

// Scratch state, never the owner's; a real path (no /var → /private/var link), as the SDK names
// a project's folder after the real one.
const scratch = mkdtempSync(join(realpathSync(tmpdir()), 'hypernova-chat-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
after(() => rmSync(scratch, { recursive: true, force: true }))

const { callCost, contextWindow, modelInfo, sessionUsage } = await import('../src/session-usage.ts')
const { todosOf, todoNow } = await import('../src/todos.ts')
const { rewindTo, RewindError } = await import('../src/rewind.ts')
const { addClient, applyAgentList, discover, emitEvent, ensureParsed, getSession, listSessions, pendingApprovals, pollRegistry, requestApproval, setState, upsertSession, view } = await import('../src/hub.ts')
const { getManaged, saveManaged, settings } = await import('../src/store.ts')
const { replacedOf, replacedSessions, wentOn } = await import('../src/rewound.ts')
const { linkOf, addLink } = await import('../src/switch-links.ts')
const { projectSlug } = await import('../src/util.ts')
type Ev = import('../src/normalize.ts').Ev

const cwd = join(scratch, 'repo')
mkdirSync(cwd, { recursive: true })
const projectDir = join(scratch, 'claude', 'projects', projectSlug(cwd))
mkdirSync(projectDir, { recursive: true })

let tick = 0
const at = () => new Date(Date.parse('2026-10-16T08:00:00Z') + tick++ * 1000).toISOString()
const base = (sid: string, uuid: string, parentUuid: string | null) => ({ uuid, parentUuid, timestamp: at(), sessionId: sid, cwd, isSidechain: false, userType: 'external', version: '2.1.301' })
const prompt = (sid: string, uuid: string, parent: string | null, text: string) => ({ ...base(sid, uuid, parent), type: 'user', message: { role: 'user', content: text } })
const reply = (sid: string, uuid: string, parent: string | null, msgId: string, text: string, usage: object, model = 'claude-opus-5-5') => ({
  ...base(sid, uuid, parent),
  type: 'assistant',
  message: { id: msgId, type: 'message', role: 'assistant', model, content: [{ type: 'text', text }], usage },
})
const lines = (...o: object[]) => o.map((x) => JSON.stringify(x)).join('\n') + '\n'

// ── Prices and windows ───────────────────────────────────────────────────
test('knows the models by name, version and window', () => {
  assert.equal(modelInfo('claude-opus-5-5')?.name, 'Opus 5.5')
  assert.equal(modelInfo('claude-opus-4-5-20251101')?.name, 'Opus 4.5')
  assert.equal(modelInfo('claude-opus-4-20250514')?.name, 'Opus 4')
  assert.equal(modelInfo('claude-sonnet-4-5-20250929')?.name, 'Sonnet 4.5')
  assert.equal(modelInfo('claude-haiku-4-5-20251001')?.name, 'Haiku 4.5')
  assert.equal(modelInfo('openai/gpt-5'), null)
  assert.equal(contextWindow('claude-sonnet-4-5-20250929', 50_000, null), 200_000)
  assert.equal(contextWindow('claude-sonnet-4-5-20250929', 50_000, 'sonnet[1m]'), 1_000_000)
  // A prompt bigger than the usual window can only have been in the big one.
  assert.equal(contextWindow('claude-sonnet-4-5-20250929', 250_000, null), 1_000_000)
  assert.equal(contextWindow('claude-opus-5-5', 10, null), 1_000_000)
})

test('prices a call at list prices, cache writes by how long they last', () => {
  // Opus 5.5: $4 in, $20 out, $5 / $8 cache writes, $0.20 cache reads, per million.
  const c = callCost('claude-opus-5-5', { input_tokens: 1_000_000, output_tokens: 100_000, cache_read_input_tokens: 1_000_000, cache_creation_input_tokens: 300_000, cache_creation: { ephemeral_5m_input_tokens: 200_000, ephemeral_1h_input_tokens: 100_000 } })
  assert.equal(c!.toFixed(4), (4 + 2 + 0.2 + 1 + 0.8).toFixed(4))
  assert.equal(callCost('some-other-model', { input_tokens: 5 }), null)
})

// ── A session's usage ────────────────────────────────────────────────────
test('adds up a session and its agents, each message once, and the context of the last reply', () => {
  const sid = randomUUID()
  const path = join(projectDir, `${sid}.jsonl`)
  const u1 = { input_tokens: 10, output_tokens: 100, cache_read_input_tokens: 20_000, cache_creation_input_tokens: 5_000 }
  writeFileSync(
    path,
    lines(
      prompt(sid, 'p1', null, 'Fix the bug'),
      // One message, logged once per content block with its usage repeated (output grows).
      reply(sid, 'a1', 'p1', 'msg_1', 'Looking.', { ...u1, output_tokens: 50 }),
      reply(sid, 'a2', 'a1', 'msg_1', 'Found it.', u1),
      reply(sid, 'a3', 'a2', 'msg_2', 'Done.', { input_tokens: 5, output_tokens: 10, cache_read_input_tokens: 30_000, cache_creation_input_tokens: 0 }),
    ),
  )
  const agentDir = join(projectDir, sid, 'subagents')
  mkdirSync(agentDir, { recursive: true })
  writeFileSync(join(agentDir, 'agent-a0123456789abcdef.jsonl'), lines({ ...reply(sid, 'g1', null, 'msg_g', 'Agent done.', { input_tokens: 1000, output_tokens: 1000 }, 'claude-haiku-4-5-20251001'), isSidechain: true }))
  const rec = { id: sid, transcriptPath: path, providerId: 'anthropic', model: null }
  const u = sessionUsage(rec)
  assert.deepEqual(u.tokens, { input: 10 + 5 + 1000, output: 100 + 10 + 1000, cacheRead: 50_000, cacheWrite: 5_000 })
  assert.equal(u.agentTokens, 2000)
  assert.equal(u.model, 'claude-opus-5-5')
  assert.equal(u.modelName, 'Opus 5.5')
  assert.deepEqual(u.context, { used: 30_005, window: 1_000_000, pct: 3, exact: false })
  assert.equal(u.costExact, false)
  const want = callCost('claude-opus-5-5', u1)! + callCost('claude-opus-5-5', { input_tokens: 5, output_tokens: 10, cache_read_input_tokens: 30_000 })! + callCost('claude-haiku-4-5-20251001', { input_tokens: 1000, output_tokens: 1000 })!
  assert.equal(u.costUsd, Math.round(want * 10000) / 10000)

  // Only what was added is read next time; a compaction empties the context until the next reply.
  appendFileSync(path, lines({ ...base(sid, 'c1', 'a3'), type: 'system', subtype: 'compact_boundary', content: 'Conversation compacted' }))
  assert.equal(sessionUsage(rec).context, null)
  appendFileSync(path, lines(reply(sid, 'a4', 'c1', 'msg_3', 'Back.', { input_tokens: 3, output_tokens: 3, cache_read_input_tokens: 9_000 })))
  assert.equal(sessionUsage(rec).context?.used, 9_003)

  // Claude Code's status line, when it has one for this session, knows the window and the cost.
  mkdirSync(join(scratch, 'support', 'statusline'), { recursive: true })
  writeFileSync(join(scratch, 'support', 'statusline', `${sid}.json`), JSON.stringify({ session_id: sid, model: { id: 'claude-opus-5-5', display_name: 'Opus 5.5' }, cost: { total_cost_usd: 1.25 }, context_window: { context_window_size: 200_000 } }))
  const s = sessionUsage(rec)
  assert.deepEqual([s.costUsd, s.costExact, s.context?.window, s.context?.exact], [1.25, true, 200_000, true])
})

test('a fork does not count its parent’s spend, but its context is the conversation’s', () => {
  const sid = randomUUID()
  const path = join(projectDir, `${sid}.jsonl`)
  writeFileSync(path, lines({ ...reply(sid, 'a1', null, 'msg_old', 'Old.', { input_tokens: 100, output_tokens: 100, cache_read_input_tokens: 40_000 }), forkedFrom: { sessionId: 'x', messageUuid: 'y' } }))
  const u = sessionUsage({ id: sid, transcriptPath: path, providerId: 'anthropic', model: null })
  assert.equal(u.tokens.input + u.tokens.output + u.tokens.cacheRead, 0)
  assert.equal(u.context?.used, 40_100)
})

// ── The to-do list ───────────────────────────────────────────────────────
const tool = (id: string, name: string, input: Record<string, unknown>, status: 'running' | 'done' | 'error' = 'done', output: string | null = null): Ev =>
  ({ id, kind: 'tool', ts: tick++, toolUseId: id, name, summary: '', input, status, output, exitCode: null, durationMs: null, diff: null, path: null }) as Ev

test('the to-do list TodoWrite last wrote', () => {
  assert.equal(todosOf([]), null)
  const list = todosOf([
    tool('t1', 'TodoWrite', { todos: [{ content: 'Read the code', activeForm: 'Reading the code', status: 'completed' }, { content: 'Fix it', activeForm: 'Fixing it', status: 'pending' }] }),
    tool('t2', 'TodoWrite', { todos: [{ content: 'Read the code', activeForm: 'Reading the code', status: 'completed' }, { content: 'Fix it', activeForm: 'Fixing it', status: 'in_progress' }] }),
    // Failed calls don't count.
    tool('t3', 'TodoWrite', { todos: [] }, 'error'),
  ])
  assert.deepEqual(list?.todos.map((t) => [t.text, t.active, t.status]), [
    ['Read the code', 'Reading the code', 'completed'],
    ['Fix it', 'Fixing it', 'in_progress'],
  ])
  // Written empty: gone.
  assert.equal(todosOf([tool('t1', 'TodoWrite', { todos: [{ content: 'A', status: 'pending' }] }), tool('t2', 'TodoWrite', { todos: [] })]), null)
})

test('the task list TaskCreate and TaskUpdate keep', () => {
  const list = todosOf([
    tool('c1', 'TaskCreate', { subject: 'Write the parser', activeForm: 'Writing the parser' }, 'done', 'Task #1 created successfully: Write the parser'),
    tool('c2', 'TaskCreate', { subject: 'Test it' }, 'done', 'Task #2 created successfully: Test it'),
    tool('c3', 'TaskCreate', { subject: 'Not yet created' }, 'running'),
    tool('u1', 'TaskUpdate', { taskId: '1', status: 'completed' }),
    tool('u2', 'TaskUpdate', { taskId: '2', status: 'in_progress', activeForm: 'Testing it' }),
    tool('c4', 'TaskCreate', { subject: 'Drop me' }, 'done', 'Task #3 created successfully: Drop me'),
    tool('u3', 'TaskUpdate', { taskId: '3', status: 'deleted' }),
  ])
  assert.deepEqual(list?.todos, [
    { id: '1', text: 'Write the parser', active: 'Writing the parser', status: 'completed' },
    { id: '2', text: 'Test it', active: 'Testing it', status: 'in_progress' },
  ])
})

test('the list in short for Home: the item under way, done and total', () => {
  assert.equal(todoNow([]), null)
  const list = [
    { content: 'Read the code', activeForm: 'Reading the code', status: 'completed' },
    { content: 'Run the tests', activeForm: 'Running the tests', status: 'in_progress' },
    { content: 'Fix it', status: 'pending' },
  ]
  assert.deepEqual(todoNow([tool('t1', 'TodoWrite', { todos: list })]), { active: 'Running the tests', done: 1, total: 3 })
  // No form while under way: its text. None under way: no line, the counts stay.
  assert.deepEqual(todoNow([tool('t1', 'TodoWrite', { todos: [{ content: 'Fix it', status: 'in_progress' }] })]), { active: 'Fix it', done: 0, total: 1 })
  assert.deepEqual(todoNow([tool('t1', 'TodoWrite', { todos: [{ content: 'Fix it', status: 'completed' }] })]), { active: null, done: 1, total: 1 })
  // A long one is cut, for one line on a card.
  assert.equal(todoNow([tool('t1', 'TodoWrite', { todos: [{ content: 'x'.repeat(300), status: 'in_progress' }] })])?.active?.length, 120)
})

test("a session carries its list in short, and it moves on with Claude's next call", () => {
  const sid = randomUUID()
  const path = join(projectDir, `${sid}.jsonl`)
  const use = (uuid: string, id: string, todos: object[]) => ({ ...base(sid, uuid, null), type: 'assistant', message: { id: `msg_${uuid}`, type: 'message', role: 'assistant', model: 'claude-opus-5-5', content: [{ type: 'tool_use', id, name: 'TodoWrite', input: { todos } }] } })
  const done = (uuid: string, id: string) => ({ ...base(sid, uuid, null), type: 'user', message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: id, content: 'Todos have been modified successfully.' }] } })
  writeFileSync(path, lines(prompt(sid, randomUUID(), null, 'Go'), use('a1', 'tu1', [{ content: 'Run the tests', activeForm: 'Running the tests', status: 'in_progress' }, { content: 'Ship', status: 'pending' }]), done('u1', 'tu1')))
  upsertSession(sid, { cwd, transcriptPath: path, title: 'Todo', state: 'idle' })
  const rec = getSession(sid)!
  const st = ensureParsed(rec)
  assert.deepEqual(view(rec).todo, { active: 'Running the tests', done: 0, total: 2 })
  // The next call, as an event the runner or a poll brings.
  const ev = tool('tu2', 'TodoWrite', { todos: [{ content: 'Run the tests', status: 'completed' }, { content: 'Ship', activeForm: 'Shipping', status: 'in_progress' }] })
  st.events.push(ev)
  st.byId.set(ev.id, ev)
  emitEvent(sid, ev.id)
  assert.deepEqual(view(rec).todo, { active: 'Shipping', done: 1, total: 2 })
  // A session with no list says so.
  upsertSession(`${sid}-none`, { cwd, title: 'None', state: 'idle' })
  assert.equal(view(getSession(`${sid}-none`)!).todo, null)
})

// ── Rewind to here ───────────────────────────────────────────────────────
test('rewinds to just before a message: the copy replaces the original on every list, its transcript left on disk', async () => {
  const sid = randomUUID()
  const path = join(projectDir, `${sid}.jsonl`)
  const [p1, a1, p2, a2] = [randomUUID(), randomUUID(), randomUUID(), randomUUID()]
  const original = lines(
    prompt(sid, p1, null, 'First task'),
    reply(sid, a1, p1, 'msg_a', 'First answer.', { input_tokens: 1, output_tokens: 1 }),
    prompt(sid, p2, a1, 'Second task'),
    reply(sid, a2, p2, 'msg_b', 'Second answer.', { input_tokens: 1, output_tokens: 1 }),
  )
  writeFileSync(path, original)
  upsertSession(sid, { cwd, transcriptPath: path, title: 'Two tasks', state: 'idle' })
  const rec = getSession(sid)!
  assert.deepEqual(ensureParsed(rec).events.filter((e) => e.kind === 'user').map((e) => e.id), [p1, p2])

  const r = await rewindTo(rec, p2, false)
  assert.equal(r.text, 'Second task')
  assert.notEqual(r.session.id, sid)
  assert.equal(r.session.origin, 'hypernova')
  // One card: the title stays, and nothing says it's a copy.
  assert.equal(r.session.title, 'First task', 'its title, nothing added')
  assert.equal(r.session.state, 'idle')
  assert.equal(r.originalStays, null)
  // The original is on no list, and what names it is followed; its transcript is below.
  assert.equal(getSession(sid), undefined)
  assert.ok(!listSessions().some((s) => s.id === sid))
  assert.equal(wentOn(sid), r.session.id)
  // The new conversation holds the first exchange only.
  const forked = getSession(r.session.id)!
  assert.ok(existsSync(forked.transcriptPath))
  const texts = ensureParsed(forked).events.map((e) => (e.kind === 'user' || e.kind === 'assistant' ? e.text : e.kind))
  assert.deepEqual(texts, ['First task', 'First answer.'])
  assert.equal(getManaged(r.session.id)?.engine, undefined)
  // The original is untouched.
  assert.equal(readFileSync(path, 'utf8'), original)

  // The first message has nothing before it; an unknown one isn't there.
  await assert.rejects(rewindTo(rec, p1, true), (e: unknown) => e instanceof RewindError && e.code === 'first_message')
  await assert.rejects(rewindTo(rec, 'nope', true), (e: unknown) => e instanceof RewindError && e.code === 'no_message')
})

test('finds a prompt the daemon named itself by its words, and shared terminals carry on', async () => {
  const sid = randomUUID()
  const path = join(projectDir, `${sid}.jsonl`)
  const [p1, a1, p2] = [randomUUID(), randomUUID(), randomUUID()]
  writeFileSync(path, lines(prompt(sid, p1, null, 'Same words'), reply(sid, a1, p1, 'msg_c', 'Ok.', { input_tokens: 1, output_tokens: 1 }), prompt(sid, p2, a1, 'Same words')))
  upsertSession(sid, { cwd, transcriptPath: path, title: 'SDK', state: 'idle' })
  const rec = getSession(sid)!
  // As the runner logs them: its own ids, the transcript's words.
  const st = ensureParsed(rec)
  st.events = [
    { id: 'u:one', kind: 'user', ts: 1, text: 'Same words' },
    { id: 't:x', kind: 'assistant', ts: 2, text: 'Ok.', streaming: false },
    { id: 'u:two', kind: 'user', ts: 3, text: 'Same words' },
  ]
  st.byId = new Map(st.events.map((e) => [e.id, e]))
  const r = await rewindTo(rec, 'u:two', true)
  assert.equal(r.text, 'Same words')
  assert.equal(getManaged(r.session.id)?.engine, 'term')
  assert.deepEqual(ensureParsed(getSession(r.session.id)!).events.filter((e) => e.kind === 'user').length, 1)
})

// ── Rewind: the copy replaces the original, whatever ran it ──────────────
/** A conversation of [n] exchanges, on record as Claude Code wrote it; the prompts' ids. */
function conversation(title: string, n = 2, patch: Parameters<typeof upsertSession>[1] = {}) {
  const sid = randomUUID()
  const path = join(projectDir, `${sid}.jsonl`)
  const prompts: string[] = []
  const out: object[] = [{ type: 'custom-title', customTitle: title, sessionId: sid }]
  let parent: string | null = null
  for (let i = 0; i < n; i++) {
    const [p, a] = [randomUUID(), randomUUID()]
    out.push(prompt(sid, p, parent, `Task ${i + 1}`), reply(sid, a, p, `msg_${a}`, `Answer ${i + 1}.`, { input_tokens: 1, output_tokens: 1 }))
    prompts.push(p)
    parent = a
  }
  writeFileSync(path, lines(...out))
  upsertSession(sid, { cwd, transcriptPath: path, title, state: 'idle', ...patch })
  ensureParsed(getSession(sid)!)
  return { sid, path, prompts, rec: getSession(sid)! }
}

/** Stops that only say what they were asked to do. */
function fakeStops(o: { term?: boolean; job?: { ok: boolean; said: string } } = {}) {
  const calls: string[] = []
  return {
    calls,
    stops: {
      endTerm: async (id: string) => (calls.push(`term ${id}`), o.term ?? true),
      stopJob: async (job: string) => (calls.push(`job ${job}`), o.job ?? { ok: true, said: '' }),
      interrupt: async (id: string) => void calls.push(`interrupt ${id}`),
    },
  }
}

const ask = (sessionId: string) => requestApproval({ sessionId, kind: 'tool', toolName: 'Bash', summary: 'Bash: ls', input: { command: 'ls' }, reason: null, canAlways: false, questions: null, plan: null }, null)

/** What every client hears while [f] runs. */
async function hearing(f: () => Promise<void>): Promise<any[]> {
  const heard: any[] = []
  const stop = addClient({ send: (s) => void heard.push(JSON.parse(s)) })
  await f()
  stop()
  return heard
}

test('a rewind closes the original’s shared terminal and answers what it waited on; clients hear what replaced it', async () => {
  const { sid, prompts, rec, path } = conversation('Held', 2, { state: 'working', origin: 'terminal', term: { id: 't-held', mac: 1, phone: 0 } })
  const asked = ask(sid)
  assert.equal(view(rec).pendingApprovalIds.length, 1)
  const original = readFileSync(path, 'utf8')
  const f = fakeStops()
  let r!: Awaited<ReturnType<typeof rewindTo>>
  const heard = await hearing(async () => {
    r = await rewindTo(rec, prompts[1]!, true, f.stops)
  })
  assert.deepEqual(f.calls, ['term t-held'])
  assert.equal(r.originalStays, null)
  assert.equal((await asked).decision, 'terminal')
  assert.ok(!pendingApprovals().some((a) => a.sessionId === sid))
  assert.deepEqual(heard.filter((m) => m.t === 'session.removed'), [{ t: 'session.removed', id: sid, replacedBy: r.session.id }])
  assert.ok(heard.some((m) => m.t === 'approval.resolved' && m.sessionId === sid))
  assert.equal(readFileSync(path, 'utf8'), original, 'its transcript is untouched')
  // What comes in late never brings it back: a dying claude's words, a stopped turn's "idle".
  const late = await hearing(async () => {
    setState(sid, 'idle')
    upsertSession(sid, { state: 'working', contextPct: 3 })
  })
  assert.deepEqual(late, [])
  assert.equal(getSession(sid), undefined)
  // Nor does a restart, which would find its transcript and its record.
  discover()
  assert.equal(getSession(sid), undefined)
  assert.equal(replacedSessions()[sid], r.session.id)
})

test('a rewind stops a claude agents job; its list and its dying process don’t bring it back, a new claude does', async () => {
  const { sid, prompts, rec } = conversation('A job', 2, { state: 'working', origin: 'terminal', term: { id: 't-attach', mac: 0, phone: 1 }, pid: process.pid })
  settings.claudeAgents = true
  const registry = join(scratch, 'claude', 'sessions')
  mkdirSync(registry, { recursive: true })
  const entry = join(registry, `${process.pid}.json`)
  try {
    applyAgentList([{ id: 'job-1', sessionId: sid, cwd, state: 'working' }])
    const f = fakeStops()
    const r = await rewindTo(rec, prompts[1]!, true, f.stops)
    // The holder first: it only watched, and would hold the job open.
    assert.deepEqual(f.calls, ['term t-attach', 'job job-1'])
    assert.equal(r.originalStays, null)
    assert.equal(getSession(sid), undefined)
    // `claude agents --all` lists a stopped job for good.
    applyAgentList([{ id: 'job-1', sessionId: sid, cwd, state: 'done' }])
    assert.equal(getSession(sid), undefined)
    // The claude it stopped, still on its way out.
    writeFileSync(entry, JSON.stringify({ pid: process.pid, sessionId: sid, cwd, kind: 'interactive', status: 'busy', startedAt: Date.now() - 60_000, updatedAt: Date.now() }))
    pollRegistry()
    assert.equal(getSession(sid), undefined)
    // `claude --resume` by hand: another claude, started since. It's an ordinary session again.
    rmSync(entry)
    writeFileSync(join(registry, `${process.ppid}.json`), JSON.stringify({ pid: process.ppid, sessionId: sid, cwd, kind: 'interactive', status: 'busy', startedAt: Date.now() + 1000, updatedAt: Date.now() }))
    pollRegistry()
    assert.equal(getSession(sid)?.state, 'working')
    assert.equal(replacedOf(sid), null)
    assert.equal(wentOn(sid), sid)
  } finally {
    settings.claudeAgents = false
    rmSync(join(registry, `${process.ppid}.json`), { force: true })
    rmSync(entry, { force: true })
    upsertSession(sid, { pid: null, state: 'ended' })
    applyAgentList([])
  }
})

test('a job with no process has nothing that can fail to stop', async () => {
  const { sid, prompts, rec } = conversation('Done job', 2, { state: 'idle', origin: 'terminal' })
  settings.claudeAgents = true
  try {
    applyAgentList([{ id: 'job-2', sessionId: sid, cwd, state: 'done' }])
    const f = fakeStops({ job: { ok: false, said: 'nothing to stop' } })
    const r = await rewindTo(rec, prompts[1]!, true, f.stops)
    assert.deepEqual(f.calls, [], 'not even asked')
    assert.equal(r.originalStays, null)
    assert.equal(getSession(sid), undefined)
  } finally {
    settings.claudeAgents = false
    applyAgentList([])
  }
})

test('a session the daemon runs: its turn is interrupted, and a restart doesn’t bring it back', async () => {
  const { sid, prompts, rec } = conversation('SDK', 2, { state: 'working' })
  saveManaged({ id: sid, cwd, providerId: 'anthropic', model: null, permissionMode: 'default', createdAt: Date.now() })
  const f = fakeStops()
  const r = await rewindTo(rec, prompts[1]!, false, f.stops)
  assert.deepEqual(f.calls, [`interrupt ${sid}`])
  assert.equal(r.originalStays, null)
  // The turn's last word, after the interrupt.
  setState(sid, 'idle')
  discover()
  assert.equal(getSession(sid), undefined)
  assert.ok(getManaged(sid), 'its record stays too')
})

test('a stop that fails: the rewind stands, the original stays where it is, nothing is deleted', async () => {
  for (const [patch, stops, words] of [
    [{ term: { id: 't-stuck', mac: 0, phone: 0 } }, { term: false }, 'terminal didn’t close'],
    [{ pid: process.pid }, { job: { ok: false, said: 'no' } }, 'claude agents didn’t stop'],
    [{ term: { id: 't-boom', mac: 0, phone: 0 } }, 'throws', 'couldn’t be stopped'],
  ] as const) {
    const { sid, prompts, rec, path } = conversation('Stuck', 2, { state: 'working', origin: 'terminal', ...patch })
    settings.claudeAgents = true
    if ('pid' in patch) applyAgentList([{ id: 'job-3', sessionId: sid, cwd, state: 'working' }])
    const original = readFileSync(path, 'utf8')
    const f = fakeStops(stops === 'throws' ? {} : stops)
    if (stops === 'throws') f.stops.endTerm = async () => { throw new Error('boom') }
    try {
      const r = await rewindTo(rec, prompts[1]!, true, f.stops)
      assert.ok(r.originalStays?.includes(words), r.originalStays ?? 'null')
      assert.ok(r.originalStays?.includes('stays on Home'))
      assert.equal(r.text, 'Task 2', 'the message still comes back')
      assert.equal(r.session.title, 'Stuck')
      assert.ok(existsSync(getSession(r.session.id)!.transcriptPath))
      assert.equal(getSession(sid)?.state, 'working')
      assert.equal(replacedOf(sid), null)
      assert.equal(readFileSync(path, 'utf8'), original)
    } finally {
      settings.claudeAgents = false
      applyAgentList([])
      upsertSession(sid, { pid: null })
    }
  }
})

test('a session open in a terminal Hypernova doesn’t hold can’t be stopped from here: it stays, and says why', async () => {
  const { sid, prompts, rec } = conversation('Elsewhere', 2, { state: 'idle', origin: 'terminal', pid: process.pid })
  const f = fakeStops()
  const r = await rewindTo(rec, prompts[1]!, true, f.stops)
  assert.deepEqual(f.calls, [])
  assert.ok(r.originalStays?.includes('terminal Hypernova doesn’t hold'))
  assert.equal(getSession(sid)?.state, 'idle')
  upsertSession(sid, { pid: null })
})

test('rewinding twice: the title never changes, each original goes, and the first id follows to the last', async () => {
  const a = conversation('Old one (rewound)', 3)
  const first = await rewindTo(a.rec, a.prompts[2]!, true, fakeStops().stops)
  assert.equal(first.session.title, 'Old one (rewound)', 'exactly as it was: not made worse, not rewritten')
  const b = getSession(first.session.id)!
  const inB = ensureParsed(b).events.filter((e) => e.kind === 'user').map((e) => e.id)
  assert.equal(inB.length, 2)
  const second = await rewindTo(b, inB[1]!, true, fakeStops().stops)
  assert.equal(second.session.title, 'Old one (rewound)')
  assert.equal(second.text, 'Task 2')
  assert.deepEqual(ensureParsed(getSession(second.session.id)!).events.filter((e) => e.kind === 'user').map((e) => (e.kind === 'user' ? e.text : '')), ['Task 1'])
  assert.equal(getSession(a.sid), undefined)
  assert.equal(getSession(b.id), undefined)
  assert.equal(getSession(second.session.id)?.state, 'idle')
  assert.equal(wentOn(a.sid), second.session.id)
  assert.equal(replacedSessions()[a.sid], second.session.id)
})

test('the new session is the same session to look at: everything the card and the chat show', async () => {
  const automation = { id: 'au_1', name: 'Nightly', runId: 'run_1' }
  const { prompts, rec, sid, path } = conversation('Carried', 2, { createdAt: 1000, effort: 'high', permissionMode: 'acceptEdits', model: 'claude-opus-5-5', providerId: 'anthropic', automation, costUsd: 1.5 })
  saveManaged({ id: sid, cwd, providerId: 'anthropic', model: 'opus', permissionMode: 'acceptEdits', createdAt: 1000, engine: 'term', context: 'From Codex.', automation, allowedTools: ['Read'], appendSystemPrompt: 'Be brief.' })
  addLink('codex-thread', sid)
  const t0 = Date.now()
  const r = await rewindTo(rec, prompts[1]!, true, fakeStops().stops)
  assert.equal(r.session.title, 'Carried')
  assert.equal(r.session.cwd, cwd)
  assert.equal(r.session.projectName, rec.projectName)
  assert.equal(r.session.createdAt, 1000)
  assert.ok(r.session.lastActivityAt >= t0, 'it sits where a session just used sits')
  assert.equal(r.session.effort, 'high')
  assert.equal(r.session.permissionMode, 'acceptEdits')
  assert.equal(r.session.model, 'claude-opus-5-5')
  assert.equal(r.session.providerId, 'anthropic')
  assert.deepEqual(r.session.automation, automation)
  assert.equal(r.session.costUsd, 1.5)
  const m = getManaged(r.session.id)!
  assert.equal(m.context, 'From Codex.')
  assert.equal(m.effort, 'high')
  assert.deepEqual(m.automation, automation)
  assert.deepEqual(m.allowedTools, ['Read'])
  assert.equal(m.appendSystemPrompt, 'Be brief.')
  // The switch of agents it came from is the new one's now.
  assert.equal(linkOf(r.session.id).from, 'codex-thread')
  assert.equal(linkOf('codex-thread').to, r.session.id)
  assert.ok(existsSync(path), 'the old transcript stays on disk')
})
