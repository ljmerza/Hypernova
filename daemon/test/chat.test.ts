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
const { emitEvent, ensureParsed, getSession, upsertSession, view } = await import('../src/hub.ts')
const { getManaged } = await import('../src/store.ts')
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

test("a working session says when its turn started, through an ask, and not once it's idle", () => {
  const sid = randomUUID()
  upsertSession(sid, { cwd, title: 'Turn', state: 'idle' })
  const rec = getSession(sid)!
  assert.equal(view(rec).workingSince, null)
  const before = Date.now()
  upsertSession(sid, { state: 'working' })
  const since = view(rec).workingSince!
  assert.ok(since >= before && since <= Date.now())
  // More steps and an ask within the turn keep it.
  upsertSession(sid, { state: 'working', activity: 'Bash: npm test' })
  upsertSession(sid, { state: 'needs_you' })
  upsertSession(sid, { state: 'working' })
  assert.equal(view(rec).workingSince, since)
  // Done: gone, and the next turn starts again.
  upsertSession(sid, { state: 'idle' })
  assert.equal(view(rec).workingSince, null)
  // A time the patch brings (Claude Code's registry stamp, a daemon seeing it mid-turn) is kept.
  upsertSession(sid, { state: 'working', workingSince: 1_000 })
  assert.equal(view(rec).workingSince, 1_000)
  upsertSession(sid, { state: 'ended' })
  assert.equal(view(rec).workingSince, null)
})

// ── Rewind to here ───────────────────────────────────────────────────────
test('rewinds to just before a message, in a new session, leaving the original as it was', async () => {
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
  assert.equal(r.session.title, `${rec.title} (rewound)`)
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
