// Agents and workflows a session's main agent starts (docs/PROTOCOL.md, "Agents and workflows"):
// the main transcript's links and task notifications, the session's subagents folder (meta files,
// agent transcripts, a workflow's journal and run summary), hooks, and the phone's routes. Every
// fixture is written by hand in the shapes Claude Code 2.1.299 records; none is copied from a
// real transcript.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { appendFileSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, symlinkSync, utimesSync, writeFileSync } from 'node:fs'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-test-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
after(() => rmSync(scratch, { recursive: true, force: true }))

const { parseTranscript, parseTaskNotification, workflowName, workflowPhases } = await import('../src/normalize.ts')
const hub = await import('../src/hub.ts')
const agents = await import('../src/agents.ts')
const { handleHook } = await import('../src/hooks.ts')
const { addDevice } = await import('../src/store.ts')

// ── Fixture helpers ──────────────────────────────────────────────────────
// Half an hour ago, on the second: recent enough that a live session's agents still count as at
// work (a fixed date stopped them once it was 2 hours old).
const T0 = Math.floor(Date.now() / 1000) * 1000 - 30 * 60_000
let tick = 0
const at = () => new Date(T0 + tick++ * 1000).toISOString()
const line = (o: object) => JSON.stringify(o) + '\n'

const PLAIN = 'a0123456789abcdef'
const SURVEYOR = 'a1111111111111111'
const FIXER = 'a2222222222222222'
const RUN = 'wf_0a1b2c3d-4e5'
const WF_TASK = 'w1a2b3c4d'

const assistant = (uuid: string, blocks: object[], extra: object = {}) => ({ type: 'assistant', uuid, timestamp: at(), sessionId: 'S', message: { id: `msg-${uuid}`, role: 'assistant', model: 'claude-test', content: blocks }, ...extra })
const toolUse = (id: string, name: string, input: object) => ({ type: 'tool_use', id, name, input })
const result = (uuid: string, toolUseId: string, text: string, toolUseResult?: object, extra: object = {}) => ({
  type: 'user', uuid, timestamp: at(), sessionId: 'S',
  message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: toolUseId, content: [{ type: 'text', text }] }] },
  ...(toolUseResult ? { toolUseResult } : {}), ...extra,
})
/** A tool call's result that is an error: an interruption (Esc), or a failure, often only a string. */
const failed = (uuid: string, toolUseId: string, text: string) => ({
  type: 'user', uuid, timestamp: at(), sessionId: 'S',
  message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: toolUseId, content: text, is_error: true }] },
  toolUseResult: `Error: ${text}`,
})
const prompt = (uuid: string, text: string, extra: object = {}) => ({ type: 'user', uuid, timestamp: at(), sessionId: 'S', message: { role: 'user', content: text }, ...extra })

/** An Agent call launched in the background, and its launch result. */
const launchAgent = (toolUseId: string, agentId: string, description: string, type = 'Explore') => [
  assistant(`as-${toolUseId}`, [toolUse(toolUseId, 'Agent', { description, prompt: 'Read normalize.ts and say how agents are linked.', subagent_type: type })]),
  result(`ur-${toolUseId}`, toolUseId, `Async agent launched successfully.\nagentId: ${agentId} (internal metadata, never quote)`, {
    isAsync: true, status: 'async_launched', agentId, description, resolvedModel: 'claude-test', prompt: 'Read normalize.ts…', outputFile: '/tmp/x.output', canReadOutputFile: true,
  }),
]

const SCRIPT = `export const meta = {
  name: 'feedback-survey',
  description: 'Survey the parser, then fix it',
  phases: [
    { title: 'Survey', detail: 'Read the code [all of it]' },
    { detail: 'Fix what the survey found', title: 'Implement' },
    { title: 'Verify' },
  ],
}
phase('Survey')`

const launchWorkflow = (toolUseId: string) => [
  assistant(`as-${toolUseId}`, [toolUse(toolUseId, 'Workflow', { script: SCRIPT })]),
  result(`ur-${toolUseId}`, toolUseId, 'Workflow launched in the background.', {
    status: 'async_launched', taskId: WF_TASK, taskType: 'local_workflow', workflowName: 'feedback-survey', runId: RUN, summary: 'Survey the parser, then fix it', transcriptDir: '/tmp/x', scriptPath: '/tmp/x.js',
  }),
]

/** A Workflow call that starts run [RUN] again from where it stopped, as task [taskId]. */
const resumeWorkflow = (toolUseId: string, taskId: string) => [
  assistant(`as-${toolUseId}`, [toolUse(toolUseId, 'Workflow', { script: SCRIPT, resumeFromRunId: RUN })]),
  result(`ur-${toolUseId}`, toolUseId, 'Workflow launched in the background.', { status: 'async_launched', taskId, taskType: 'local_workflow', workflowName: 'feedback-survey', runId: RUN }),
]

/** A task notification's text, as Claude Code writes it. */
const notification = (taskId: string, toolUseId: string, status: string, usage: string) =>
  `<task-notification>\n<task-id>${taskId}</task-id>\n<tool-use-id>${toolUseId}</tool-use-id>\n<output-file>/tmp/${taskId}.output</output-file>\n<status>${status}</status>\n<summary>Task "${taskId}" ${status}</summary>\n<result>The agent's report was delivered as a message.</result>\n<usage>${usage}</usage>\n</task-notification>`
const agentUsage = '<subagent_tokens>171000</subagent_tokens>\n<tool_uses>44</tool_uses>\n<duration_ms>134000</duration_ms>'
const workflowUsage = '<agent_count>2</agent_count>\n<agents_done>2</agents_done>\n<agents_error>0</agents_error>\n<subagent_tokens>300000</subagent_tokens>\n<tool_uses>60</tool_uses>\n<duration_ms>600000</duration_ms>'

const enqueue = (content: string) => ({ type: 'queue-operation', operation: 'enqueue', timestamp: at(), sessionId: 'S', content })

/** An agent's own transcript: its prompt, a Bash call, and (when [done]) its hand-back. */
function agentLines(agentId: string, done: boolean): string {
  const side = { isSidechain: true, agentId }
  const out: object[] = [
    prompt('s1', 'Read normalize.ts and say how agents are linked.', { ...side, parentUuid: null }),
    assistant('s2', [{ type: 'text', text: 'Reading the parser.' }], side),
    { ...assistant('s3', [toolUse('tu-bash', 'Bash', { command: 'npm test' })], side), message: { id: 'msg-s3', role: 'assistant', model: 'claude-test', content: [toolUse('tu-bash', 'Bash', { command: 'npm test' })], usage: { input_tokens: 12, cache_read_input_tokens: 20000, cache_creation_input_tokens: 0, output_tokens: 88 } } },
  ]
  if (done) {
    out.push(result('s4', 'tu-bash', 'ok 82 tests', undefined, side))
    out.push(assistant('s5', [toolUse('tu-hand', 'SubagentHandback', { message: 'Agents link by toolUseId.\nNotifications end them.' })], side))
  }
  return out.map(line).join('')
}

/** A session with its transcript at [dir]/<id>.jsonl, and its subagents folder. */
function makeSession(id: string, lines: object[]) {
  const dir = join(scratch, 'claude', 'projects', '-tmp-agents')
  mkdirSync(dir, { recursive: true })
  const transcriptPath = join(dir, `${id}.jsonl`)
  writeFileSync(transcriptPath, lines.map(line).join(''))
  const sub = join(dir, id, 'subagents')
  mkdirSync(sub, { recursive: true })
  const r = hub.upsertSession(id, { cwd: '/tmp/agents', transcriptPath, origin: 'terminal', state: 'working' })
  hub.ensureParsed(r)
  return { transcriptPath, sub, runDir: join(sub, 'workflows', RUN), snapshots: join(dir, id, 'workflows') }
}

const writeMeta = (dir: string, agentId: string, meta: object) => {
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, `agent-${agentId}.meta.json`), JSON.stringify(meta))
}

/** Messages the hub sends phones, from now on. */
function listen() {
  const got: any[] = []
  const stop = hub.addClient({ send: (s) => got.push(JSON.parse(s)) })
  return { got, stop }
}

// ── The parser: links, ends, workflows ──────────────────────────────────
test('an agent launched in the background keeps its call running, without the launch note, until its notification', () => {
  const st = parseTranscript([...launchAgent('toolu_a1', PLAIN, 'Review the parser')].map((o) => JSON.stringify(o)).join('\n'))
  const call = st.byId.get('toolu_a1')
  assert.equal(call?.kind, 'tool')
  assert.equal(call?.kind === 'tool' && call.status, 'running')
  assert.equal(call?.kind === 'tool' && call.output, null)
  assert.deepEqual(st.links.map((l) => l.kind), ['agent'])
  const l = st.links[0]!
  assert.ok(l.kind === 'agent')
  assert.equal(l.agentId, PLAIN)
  assert.equal(l.label, 'Review the parser')
  assert.equal(l.type, 'Explore')
  assert.equal(l.background, true)
  assert.equal(l.report, null)
})

test('a task notification ends the agent once, from whichever copy comes first, and stops its call', () => {
  const text = notification(PLAIN, 'toolu_a1', 'completed', agentUsage)
  const st = parseTranscript([
    ...launchAgent('toolu_a1', PLAIN, 'Review the parser'),
    enqueue(text),
    prompt('n1', text, { origin: { kind: 'task-notification' }, promptSource: 'system' }),
    { type: 'attachment', uuid: 'q1', timestamp: at(), attachment: { type: 'queued_command', commandMode: 'task-notification', prompt: text, origin: { kind: 'task-notification' }, source_uuid: 'src-1', usage: { totalTokens: 171000, toolUses: 44, durationMs: 134000 } } },
  ].map((o) => JSON.stringify(o)).join('\n'))
  const ends = st.links.filter((l) => l.kind === 'end')
  assert.equal(ends.length, 1)
  const e = ends[0]!
  assert.ok(e.kind === 'end')
  assert.equal(e.taskId, PLAIN)
  assert.equal(e.toolUseId, 'toolu_a1')
  assert.equal(e.status, 'completed')
  assert.equal(e.tokens, 171000)
  assert.equal(e.toolUses, 44)
  assert.equal(e.durationMs, 134000)
  const call = st.byId.get('toolu_a1')
  assert.ok(call?.kind === 'tool')
  assert.equal(call.status, 'done')
  assert.equal(call.durationMs, 134000)
  // The notification itself never shows in the chat.
  assert.ok(!st.events.some((ev) => ev.kind === 'user'))
})

test('a failed agent fails its call, with the summary as its output; a shell command\'s notification links nothing', () => {
  const st = parseTranscript([
    ...launchAgent('toolu_a1', PLAIN, 'Review the parser'),
    enqueue(notification(PLAIN, 'toolu_a1', 'failed', agentUsage)),
    enqueue(notification('b9z8y7x6', 'toolu_bash', 'completed', '')),
  ].map((o) => JSON.stringify(o)).join('\n'))
  const call = st.byId.get('toolu_a1')
  assert.ok(call?.kind === 'tool')
  assert.equal(call.status, 'error')
  assert.equal(call.output, `Task "${PLAIN}" failed`)
  assert.deepEqual(st.links.map((l) => l.kind), ['agent', 'end'])
})

test('an agent woken by SendMessage ends again with its next notification', () => {
  const text = notification(PLAIN, 'toolu_a1', 'completed', agentUsage)
  const st = parseTranscript([
    ...launchAgent('toolu_a1', PLAIN, 'Review the parser'),
    enqueue(text),
    assistant('as-send', [toolUse('toolu_send', 'SendMessage', { to: PLAIN, message: 'and the tests too' })]),
    result('ur-send', 'toolu_send', 'Sent.', { resumedAgentId: PLAIN }),
    enqueue(text),
  ].map((o) => JSON.stringify(o)).join('\n'))
  assert.deepEqual(st.links.map((l) => l.kind), ['agent', 'end', 'resume', 'end'])
})

test('a workflow call is named by its script, linked to its run with the script\'s phases, and stopped by TaskStop', () => {
  const st = parseTranscript([
    ...launchWorkflow('toolu_wf1'),
    assistant('as-stop', [toolUse('toolu_stop', 'TaskStop', { task_id: WF_TASK })]),
    result('ur-stop', 'toolu_stop', 'Stopped.', { message: 'Stopped the workflow', task_id: WF_TASK, task_type: 'local_workflow' }),
    assistant('as-send', [toolUse('toolu_send', 'SendMessage', { to: PLAIN, message: 'one more thing' })]),
    result('ur-send', 'toolu_send', 'Sent.', { resumedAgentId: PLAIN }),
  ].map((o) => JSON.stringify(o)).join('\n'))
  const call = st.byId.get('toolu_wf1')
  assert.ok(call?.kind === 'tool')
  assert.equal(call.summary, 'feedback-survey')
  assert.equal(call.status, 'running')
  const [wf, stop, resume] = st.links
  assert.ok(wf?.kind === 'workflow')
  assert.equal(wf.runId, RUN)
  assert.equal(wf.taskId, WF_TASK)
  assert.equal(wf.name, 'feedback-survey')
  assert.deepEqual(wf.phases, [
    { title: 'Survey', detail: 'Read the code [all of it]' },
    { title: 'Implement', detail: 'Fix what the survey found' },
    { title: 'Verify', detail: null },
  ])
  assert.deepEqual(stop, { kind: 'stop', ts: stop!.ts, taskId: WF_TASK })
  assert.deepEqual(resume, { kind: 'resume', ts: resume!.ts, agentId: PLAIN })
})

test('workflow names fall back to the named workflow, the script file, then "workflow"', () => {
  assert.equal(workflowName({ script: SCRIPT }), 'feedback-survey')
  assert.equal(workflowName({ name: 'spec' }), 'spec')
  assert.equal(workflowName({ scriptPath: '/tmp/s/wf-review.js' }), 'wf-review')
  assert.equal(workflowName({}), 'workflow')
  assert.deepEqual(workflowPhases('export const meta = { name: "x", phases: [] }'), [])
  assert.deepEqual(workflowPhases(undefined), [])
})

test('a workflow\'s notification carries its agent counts', () => {
  const e = parseTaskNotification(notification(WF_TASK, 'toolu_wf1', 'completed', workflowUsage))
  assert.equal(e?.agentCount, 2)
  assert.equal(e?.agentsError, 0)
  assert.equal(e?.tokens, 300000)
  assert.equal(parseTaskNotification('<task-notification><task-id>a1</task-id><status>exploded</status></task-notification>'), null)
  assert.equal(parseTaskNotification('not a notification'), null)
})

test('an agent\'s own transcript reads as a chat: its prompt, its work, and its hand-back as its last words', () => {
  const st = parseTranscript(agentLines(PLAIN, true), true)
  assert.deepEqual(st.events.map((e) => e.kind), ['user', 'assistant', 'tool', 'assistant'])
  const last = st.events.at(-1)
  assert.ok(last?.kind === 'assistant')
  assert.equal(last.text, 'Agents link by toolUseId.\nNotifications end them.')
  const bash = st.byId.get('tu-bash')
  assert.ok(bash?.kind === 'tool' && bash.status === 'done')
  // Read as a main transcript, the same lines are a subagent's and show nothing.
  assert.equal(parseTranscript(agentLines(PLAIN, true)).events.length, 0)
})

test('a workflow agent\'s structured output reads as its last words: the summary when it has one', () => {
  const side = { isSidechain: true, agentId: SURVEYOR }
  const st = parseTranscript([
    prompt('w1', 'Survey the parser.', { ...side, parentUuid: null }),
    assistant('w2', [toolUse('tu-so', 'StructuredOutput', { summary: 'Parser links agents', findings: ['a', 'b'] })], side),
  ].map((o) => JSON.stringify(o)).join('\n'), true)
  const last = st.events.at(-1)
  assert.ok(last?.kind === 'assistant')
  assert.equal(last.text, 'Parser links agents')
})

// ── The registry: transcript, folders, notifications ────────────────────
test('agents and a workflow come from the transcript and the session folder, follow what each does, and end with their notifications', async () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000001'
  const { transcriptPath, sub, runDir, snapshots } = makeSession(id, [prompt('p1', 'Survey and fix the parser'), ...launchAgent('toolu_a1', PLAIN, 'Review the parser'), ...launchWorkflow('toolu_wf1')])
  writeMeta(sub, PLAIN, { agentType: 'Explore', description: 'Review the parser', toolUseId: 'toolu_a1', spawnDepth: 1, requestShape: 'background', requestNonInteractive: false })
  writeFileSync(join(sub, `agent-${PLAIN}.jsonl`), agentLines(PLAIN, false))
  writeMeta(runDir, SURVEYOR, { agentType: 'workflow-subagent', description: 'survey:parser', workflowPhase: 'Survey', spawnDepth: 1, requestShape: 'foreground', requestNonInteractive: true })
  writeMeta(runDir, FIXER, { agentType: 'workflow-subagent', description: 'implement:fix', workflowPhase: 'Implement', spawnDepth: 1, requestShape: 'foreground', requestNonInteractive: true })
  writeFileSync(join(runDir, `agent-${SURVEYOR}.jsonl`), '')
  writeFileSync(join(runDir, `agent-${FIXER}.jsonl`), agentLines(FIXER, false))
  writeFileSync(join(runDir, 'journal.jsonl'), [
    { type: 'launched' },
    { type: 'started', key: 'k1', agentId: SURVEYOR, label: 'survey:parser', phase: 'Survey' },
    { type: 'result', key: 'k1', agentId: SURVEYOR, result: { summary: 'Parser links agents', findings: [] } },
    { type: 'started', key: 'k2', agentId: FIXER, label: 'implement:fix', phase: 'Implement' },
  ].map(line).join(''))

  const { got, stop } = listen()
  agents.pollAgents()
  let list = agents.listAgents(id)
  const plain = list.agents.find((a) => a.id === PLAIN)!
  assert.equal(plain.status, 'running')
  assert.equal(plain.label, 'Review the parser')
  assert.equal(plain.type, 'Explore')
  assert.equal(plain.toolUseId, 'toolu_a1')
  assert.equal(plain.activity, 'Bash: npm test')
  assert.equal(plain.tokens, 20100)
  const surveyor = list.agents.find((a) => a.id === SURVEYOR)!
  assert.equal(surveyor.status, 'done')
  assert.equal(surveyor.result, 'Parser links agents')
  assert.equal(surveyor.workflowId, RUN)
  assert.equal(surveyor.phase, 'Survey')
  assert.equal(list.agents.find((a) => a.id === FIXER)!.status, 'running')
  const wf = list.workflows[0]!
  assert.equal(wf.id, RUN)
  assert.equal(wf.name, 'feedback-survey')
  assert.equal(wf.toolUseId, 'toolu_wf1')
  assert.equal(wf.status, 'running')
  assert.deepEqual(wf.phases.map((p) => p.title), ['Survey', 'Implement', 'Verify'])
  assert.equal(wf.agentCount, 2)
  assert.equal(wf.done, 1)
  assert.equal(hub.getSession(id)!.agentsRunning, 2)
  assert.ok(got.some((m) => m.t === 'session' && m.session.id === id && m.session.agentsRunning === 2))
  assert.ok(got.some((m) => m.t === 'agent' && m.agent.id === SURVEYOR && m.agent.status === 'done'))

  // The plain agent hands back and its notification comes; the workflow's agent finishes, the run
  // summary is written, and the workflow's notification comes.
  appendFileSync(join(sub, `agent-${PLAIN}.jsonl`), agentLines(PLAIN, true).split('\n').slice(3).join('\n'))
  agents.pollAgents()
  appendFileSync(transcriptPath, line(enqueue(notification(PLAIN, 'toolu_a1', 'completed', agentUsage))))
  hub.pollTranscripts()
  list = agents.listAgents(id)
  const ended = list.agents.find((a) => a.id === PLAIN)!
  assert.equal(ended.status, 'done')
  assert.equal(ended.result, 'Agents link by toolUseId.\nNotifications end them.')
  assert.equal(ended.tokens, 171000)
  assert.equal(ended.toolUses, 44)
  assert.equal(ended.activity, null)
  const call = hub.getSession(id)!.parse!.byId.get('toolu_a1')
  assert.ok(call?.kind === 'tool' && call.status === 'done')
  assert.ok(got.some((m) => m.t === 'event' && m.event.id === 'toolu_a1' && m.event.status === 'done'))

  appendFileSync(join(runDir, 'journal.jsonl'), line({ type: 'result', key: 'k2', agentId: FIXER, result: { summary: 'Fixed the links', filesChanged: ['normalize.ts'] } }))
  mkdirSync(snapshots, { recursive: true })
  writeFileSync(join(snapshots, `${RUN}.json`), JSON.stringify({
    runId: RUN, taskId: WF_TASK, workflowName: 'feedback-survey', summary: 'Survey the parser, then fix it', status: 'completed', startTime: T0, durationMs: 600000,
    phases: [{ title: 'Survey', detail: 'Read the code' }, { title: 'Implement', detail: 'Fix it' }, { title: 'Verify', detail: 'Check it' }],
    agentCount: 2, totalTokens: 300000, totalToolCalls: 60, result: {}, logs: [],
    workflowProgress: [
      { type: 'workflow_phase', index: 0, title: 'Survey' },
      { type: 'workflow_agent', index: 0, label: 'survey:parser', phaseIndex: 0, phaseTitle: 'Survey', agentId: SURVEYOR, model: 'claude-test', state: 'done', startedAt: T0, queuedAt: T0, attempt: 1, tokens: 120000, toolCalls: 20, durationMs: 60000, resultPreview: 'Parser links agents' },
      { type: 'workflow_agent', index: 1, label: 'implement:fix', phaseIndex: 1, phaseTitle: 'Implement', agentId: FIXER, model: 'claude-test', state: 'done', startedAt: T0 + 60000, queuedAt: T0 + 60000, attempt: 1, tokens: 180000, toolCalls: 40, durationMs: 300000, resultPreview: 'Fixed the links' },
    ],
  }))
  agents.pollAgents()
  appendFileSync(transcriptPath, line(enqueue(notification(WF_TASK, 'toolu_wf1', 'completed', workflowUsage))))
  hub.pollTranscripts()
  list = agents.listAgents(id)
  const done = list.workflows[0]!
  assert.equal(done.status, 'done')
  assert.equal(done.done, 2)
  assert.equal(done.tokens, 300000)
  assert.equal(done.toolUses, 60)
  assert.equal(done.startedAt, T0)
  assert.equal(done.endedAt, T0 + 600000)
  assert.equal(list.agents.find((a) => a.id === FIXER)!.result, 'Fixed the links')
  assert.equal(list.agents.find((a) => a.id === FIXER)!.tokens, 180000)
  const wfCall = hub.getSession(id)!.parse!.byId.get('toolu_wf1')
  assert.ok(wfCall?.kind === 'tool' && wfCall.status === 'done')
  assert.equal(hub.getSession(id)!.agentsRunning, 0)
  assert.ok(got.some((m) => m.t === 'workflow' && m.workflow.id === RUN && m.workflow.status === 'done'))
  stop()
})

test('a running agent\'s tokens are counted as Claude Code\'s footer counts them: its latest context plus every reply\'s output, each reply once', () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000011'
  const { sub } = makeSession(id, [prompt('p1', 'Review the parser'), ...launchAgent('toolu_a1', PLAIN, 'Review the parser', 'general-purpose')])
  writeMeta(sub, PLAIN, { agentType: 'general-purpose', description: 'Review the parser', toolUseId: 'toolu_a1' })
  const side = { isSidechain: true, agentId: PLAIN }
  const reply = (uuid: string, msgId: string, blocks: object[], usage: object, extra: object = {}) =>
    ({ ...assistant(uuid, blocks, side), message: { id: msgId, role: 'assistant', model: 'claude-test', content: blocks, usage }, ...extra })
  const file = join(sub, `agent-${PLAIN}.jsonl`)
  writeFileSync(file, [
    prompt('t1', 'Review the parser', { ...side, parentUuid: null }),
    // One reply, logged once per content block: its output counts once, at its largest.
    reply('t2', 'msg-1', [{ type: 'text', text: 'Reading the parser.' }], { input_tokens: 10, cache_creation_input_tokens: 5000, cache_read_input_tokens: 0, output_tokens: 40 }),
    reply('t3', 'msg-1', [toolUse('tu-r1', 'Read', { file_path: '/tmp/agents/normalize.ts' })], { input_tokens: 10, cache_creation_input_tokens: 5000, cache_read_input_tokens: 0, output_tokens: 120 }),
    result('t4', 'tu-r1', 'the file', undefined, side),
  ].map(line).join(''))
  agents.pollAgents()
  let a = agents.listAgents(id).agents.find((x) => x.id === PLAIN)!
  assert.equal(a.status, 'running')
  assert.equal(a.tokens, 5010 + 120)
  // The next reply: its context replaces the last one's, its output adds to theirs. An unmetered one isn't counted.
  appendFileSync(file, [
    reply('t5', 'msg-2', [toolUse('tu-b1', 'Bash', { command: 'npm test' })], { input_tokens: 2, cache_creation_input_tokens: 800, cache_read_input_tokens: 5010, output_tokens: 60 }),
    reply('t6', 'msg-3', [{ type: 'text', text: 'Unmetered.' }], { input_tokens: 9_000_000, output_tokens: 9_000_000 }, { isUnmetered: true }),
  ].map(line).join(''))
  agents.pollAgents()
  a = agents.listAgents(id).agents.find((x) => x.id === PLAIN)!
  assert.equal(a.tokens, 2 + 800 + 5010 + 120 + 60)
  assert.equal(a.activity, 'Bash: npm test')
})

test('an agent\'s transcript is served from its own file, only for an agent of that session', () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000002'
  const { sub } = makeSession(id, [...launchAgent('toolu_a1', PLAIN, 'Review the parser')])
  writeMeta(sub, PLAIN, { agentType: 'Explore', description: 'Review the parser', toolUseId: 'toolu_a1' })
  writeFileSync(join(sub, `agent-${PLAIN}.jsonl`), agentLines(PLAIN, true))
  const { events } = agents.agentEvents(id, PLAIN)!
  assert.deepEqual(events.map((e) => e.kind), ['user', 'assistant', 'tool', 'assistant'])
  // The last one, and the task it was given first.
  assert.deepEqual(agents.agentEvents(id, PLAIN, 1)!.events.map((e) => e.kind), ['user', 'assistant'])
  // Read again with nothing new: the same events, not twice.
  assert.equal(agents.agentEvents(id, PLAIN)!.events.length, 4)
  assert.equal(agents.agentEvents(id, '../../../etc/passwd'), null)
  assert.equal(agents.agentEvents(id, 'a9999999999999999'), null)
  assert.equal(agents.agentEvents('no-such-session', PLAIN), null)
  // A link out of the folder isn't followed.
  const outside = join(scratch, 'outside.jsonl')
  writeFileSync(outside, agentLines(PLAIN, true))
  symlinkSync(outside, join(sub, 'agent-a3333333333333333.jsonl'))
  assert.equal(agents.agentFile(id, 'a3333333333333333'), null)
})

// ── Where the files are (the layout Claude Code 2.1.316 writes) ───────────
// Its keys as 2.1.316 writes them (names only; the values here are made up).
const META_316 = { isBuiltIn: true, spawnDepth: 1, requestShape: 'prompt', requestNonInteractive: false, launchedWithDefaults: true }

test('an agent\'s file is found flat, in its workflow\'s folder, or further down its session\'s subagents folder, never outside it', () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000701'
  const nested = 'a4444444444444444'
  const { sub, runDir } = makeSession(id, [...launchAgent('toolu_a1', PLAIN, 'Review the parser'), ...launchAgent('toolu_a2', nested, 'Port the plugin')])
  writeMeta(sub, PLAIN, { ...META_316, agentType: 'general-purpose', description: 'Review the parser', toolUseId: 'toolu_a1' })
  writeFileSync(join(sub, `agent-${PLAIN}.jsonl`), agentLines(PLAIN, false))
  writeMeta(runDir, SURVEYOR, { ...META_316, agentType: 'workflow-subagent', description: 'survey:agents', workflowPhase: 'Survey' })
  writeFileSync(join(runDir, `agent-${SURVEYOR}.jsonl`), agentLines(SURVEYOR, false))
  // One in a folder of its own, two down.
  const deep = join(sub, 'nested', 'deeper')
  writeMeta(deep, nested, { ...META_316, agentType: 'general-purpose', description: 'Port the plugin', toolUseId: 'toolu_a2' })
  writeFileSync(join(deep, `agent-${nested}.jsonl`), agentLines(nested, false))
  assert.equal(agents.agentFile(id, PLAIN), realpathSync(join(sub, `agent-${PLAIN}.jsonl`)))
  assert.equal(agents.agentFile(id, SURVEYOR), realpathSync(join(runDir, `agent-${SURVEYOR}.jsonl`)))
  assert.equal(agents.agentFile(id, nested), realpathSync(join(deep, `agent-${nested}.jsonl`)))
  assert.deepEqual(agents.agentEvents(id, nested)!.events.map((e) => e.kind), ['user', 'assistant', 'tool'])
  // A folder linked in from elsewhere isn't looked through.
  const away = join(scratch, 'away-201')
  mkdirSync(away)
  writeFileSync(join(away, 'agent-a5555555555555555.jsonl'), agentLines('a5555555555555555', false))
  symlinkSync(away, join(sub, 'linked'))
  assert.equal(agents.agentFile(id, 'a5555555555555555'), null)
})

test('a session\'s subagents folder is the one named as its transcript is', () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000702'
  const { transcriptPath } = makeSession(id, [])
  const stem = 'eeeeeeee-0000-4000-8000-0000000007ff'
  const other = join(dirname(transcriptPath), `${stem}.jsonl`)
  writeFileSync(other, '')
  hub.upsertSession(id, { transcriptPath: other })
  const sub = join(dirname(other), stem, 'subagents')
  writeMeta(sub, PLAIN, { ...META_316, agentType: 'general-purpose', description: 'Review the parser', toolUseId: 'toolu_a1' })
  writeFileSync(join(sub, `agent-${PLAIN}.jsonl`), agentLines(PLAIN, false))
  assert.equal(agents.agentFile(id, PLAIN), realpathSync(join(sub, `agent-${PLAIN}.jsonl`)))
})

test('Claude Code\'s own helpers (forks of the main conversation) send subagent hooks with no meta file: no agent for them; an agent with one counts from its first hook', async () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000703'
  const { sub } = makeSession(id, [])
  const fork = 'a6666666666666666'
  const { got, stop } = listen()
  const hook = (agentId: string, tool: string, input: object) =>
    handleHook({ session_id: id, cwd: '/tmp/agents', hook_event_name: 'PreToolUse', agent_id: agentId, agent_type: 'claude', tool_name: tool, tool_input: input, tool_use_id: `tu-${agentId}-${tool}` }, {} as never, {} as never)
  // A `claude agents` job's fork shows its main agent's type, under an id that names no file;
  // memory upkeep's id carries its label.
  await hook(fork, 'Read', { file_path: '/tmp/memory/MEMORY.md' })
  await hook('aextract_memories-0123456789abcdef', 'Read', { file_path: '/tmp/memory/MEMORY.md' })
  assert.deepEqual(agents.listAgents(id).agents, [])
  assert.equal(hub.getSession(id)!.agentsRunning ?? 0, 0)
  assert.ok(!got.some((m) => m.t === 'agent'))
  assert.equal(agents.agentEvents(id, fork), null)
  // An agent the main agent started has its meta file from its start: its first hook counts, labelled.
  const started = 'a7777777777777777'
  writeMeta(sub, started, { ...META_316, agentType: 'claude', description: 'Port the plugin', toolUseId: 'toolu_x' })
  await hook(started, 'Bash', { command: 'npm test' })
  const a = agents.listAgents(id).agents.find((x) => x.id === started)!
  assert.equal(a.label, 'Port the plugin')
  assert.equal(a.activity, 'Bash: npm test')
  assert.equal(a.status, 'running')
  assert.ok(got.some((m) => m.t === 'agent' && m.agent.id === started))
  stop()
})

test('an agent just started, its file not written yet, is starting; then its events come; one started long ago with no file has none', () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000704'
  const now = launchAgent('toolu_a1', PLAIN, 'Review the parser').map((o) => ({ ...o, timestamp: new Date().toISOString() }))
  // FIXER's call is half an hour old.
  const { sub } = makeSession(id, [...launchAgent('toolu_a2', FIXER, 'Fix the parser'), ...now])
  agents.listAgents(id)
  assert.deepEqual(agents.agentEvents(id, PLAIN), { events: [], cursor: '', full: true, starting: true })
  assert.equal(agents.agentEvents(id, FIXER), null)
  writeFileSync(join(sub, `agent-${PLAIN}.jsonl`), agentLines(PLAIN, false))
  const got = agents.agentEvents(id, PLAIN)!
  assert.equal(got.starting, undefined)
  assert.deepEqual(got.events.map((e) => e.kind), ['user', 'assistant', 'tool'])
})

test('an agent whose file went quiet when its session ended is done when its last word was its report, else stopped', () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000003'
  const quiet = 'a4444444444444444'
  const { sub } = makeSession(id, [...launchAgent('toolu_a1', PLAIN, 'Review the parser'), ...launchAgent('toolu_a2', quiet, 'Run the build')])
  writeFileSync(join(sub, `agent-${PLAIN}.jsonl`), agentLines(PLAIN, true))
  writeFileSync(join(sub, `agent-${quiet}.jsonl`), agentLines(quiet, false))
  const old = new Date(Date.now() - 5 * 60_000)
  utimesSync(join(sub, `agent-${PLAIN}.jsonl`), old, old)
  utimesSync(join(sub, `agent-${quiet}.jsonl`), old, old)
  hub.upsertSession(id, { state: 'ended' })
  const list = agents.listAgents(id)
  const done = list.agents.find((a) => a.id === PLAIN)!
  assert.equal(done.status, 'done')
  assert.equal(done.result, 'Agents link by toolUseId.\nNotifications end them.')
  assert.equal(list.agents.find((a) => a.id === quiet)!.status, 'stopped')
  assert.equal(hub.getSession(id)!.agentsRunning, 0)
})

// ── Hooks ─────────────────────────────────────────────────────────────────
test('a subagent\'s hooks say what it does; its approval names it, outlives the main turn\'s Stop, and the session idles while agents work', async () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000004'
  const { sub } = makeSession(id, [...launchAgent('toolu_a1', PLAIN, 'Review the parser')])
  writeMeta(sub, PLAIN, { agentType: 'Explore', description: 'Review the parser', toolUseId: 'toolu_a1' })
  const base = { session_id: id, cwd: '/tmp/agents', agent_id: PLAIN, agent_type: 'Explore' }
  const { got, stop } = listen()
  await handleHook({ ...base, hook_event_name: 'PreToolUse', tool_name: 'Bash', tool_input: { command: './gradlew build' }, tool_use_id: 'tu-sub-1' }, {} as never, {} as never)
  const a = agents.listAgents(id).agents.find((x) => x.id === PLAIN)!
  assert.equal(a.activity, 'Bash: ./gradlew build')
  // A subagent's tool is its own: no tool event in the main chat.
  assert.equal(hub.getSession(id)!.parse!.byId.get('tu-sub-1'), undefined)

  addDevice({ id: 'phone', name: 'Phone', model: 'Test', createdAt: Date.now(), lastSeenAt: Date.now() }, 'test-token-agents-0123456789abcdef')
  const res = { on: () => {}, writableEnded: false }
  const answer = handleHook({ ...base, hook_event_name: 'PermissionRequest', tool_name: 'Bash', tool_input: { command: 'rm -rf build' } }, {} as never, res as never)
  const ask = hub.pendingApprovals().find((p) => p.sessionId === id)!
  assert.equal(ask.agentId, PLAIN)
  assert.equal(ask.agentLabel, 'Review the parser')
  assert.ok(got.some((m) => m.t === 'approval' && m.approval.agentId === PLAIN))

  // The main turn ends while its agent waits: the agent's approval is still open.
  await handleHook({ session_id: id, cwd: '/tmp/agents', hook_event_name: 'Stop', background_tasks: [{ id: PLAIN, type: 'subagent', status: 'running', description: 'Review the parser', agent_type: 'Explore' }] }, {} as never, {} as never)
  assert.ok(hub.pendingApprovals().some((p) => p.id === ask.id))
  hub.answerApproval(ask.id, { decision: 'allow' }, 'phone')
  assert.deepEqual(await answer, { hookSpecificOutput: { hookEventName: 'PermissionRequest', decision: { behavior: 'allow' } } })
  // The main agent is idle while its agent works.
  assert.equal(hub.getSession(id)!.state, 'idle')
  assert.equal(hub.getSession(id)!.agentsRunning, 1)
  stop()
})

test('Claude Code busy only with background agents after the Stop: the session is idle until a turn starts', async () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000005'
  const { transcriptPath } = makeSession(id, [...launchAgent('toolu_a1', PLAIN, 'Review the parser')])
  const reg = join(scratch, 'claude', 'sessions')
  mkdirSync(reg, { recursive: true })
  writeFileSync(join(reg, '1.json'), JSON.stringify({ sessionId: id, pid: process.pid, kind: 'interactive', status: 'busy', cwd: '/tmp/agents' }))
  hub.pollRegistry()
  assert.equal(hub.getSession(id)!.state, 'working')
  await handleHook({ session_id: id, cwd: '/tmp/agents', hook_event_name: 'Stop' }, {} as never, {} as never)
  hub.pollRegistry()
  assert.equal(hub.getSession(id)!.state, 'idle')
  // The agent's notification starts a turn of the main agent's own.
  const text = notification(PLAIN, 'toolu_a1', 'completed', agentUsage)
  appendFileSync(transcriptPath, line(enqueue(text)) + line({ ...prompt('n1', text, { origin: { kind: 'task-notification' }, promptSource: 'system' }), timestamp: new Date(Date.now() + 1000).toISOString() }))
  hub.pollTranscripts()
  hub.pollRegistry()
  assert.equal(hub.getSession(id)!.state, 'working')
  assert.equal(hub.getSession(id)!.agentsRunning, 0)
  rmSync(join(reg, '1.json'))
})

test('the SDK\'s task messages start, follow and end an agent', () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000006'
  makeSession(id, [])
  agents.sdkTask(id, { type: 'system', subtype: 'task_started', task_id: PLAIN, tool_use_id: 'toolu_sdk', description: 'Check the docs', subagent_type: 'Explore', task_type: 'local_agent', is_backgrounded: true })
  agents.sdkTask(id, { type: 'system', subtype: 'task_progress', task_id: PLAIN, tool_use_id: 'toolu_sdk', description: 'Check the docs', usage: { total_tokens: 5000, tool_uses: 3, duration_ms: 4000 }, last_tool_name: 'Read' })
  let a = agents.listAgents(id).agents.find((x) => x.id === PLAIN)!
  assert.equal(a.status, 'running')
  assert.equal(a.activity, 'Read')
  assert.equal(a.tokens, 5000)
  assert.equal(hub.getSession(id)!.agentsRunning, 1)
  agents.sdkTask(id, { type: 'system', subtype: 'task_notification', task_id: PLAIN, tool_use_id: 'toolu_sdk', status: 'completed', output_file: '/tmp/x', summary: 'Checked the docs', usage: { total_tokens: 9000, tool_uses: 5, duration_ms: 8000 } })
  a = agents.listAgents(id).agents.find((x) => x.id === PLAIN)!
  assert.equal(a.status, 'done')
  assert.equal(a.tokens, 9000)
  assert.equal(hub.getSession(id)!.agentsRunning, 0)
})

// ── Following an agent from the phone ────────────────────────────────────
test('a phone following an agent gets only what changed since its cursor, and the whole window from another read\'s cursor', () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000007'
  const { sub } = makeSession(id, [...launchAgent('toolu_a1', PLAIN, 'Review the parser')])
  const file = join(sub, `agent-${PLAIN}.jsonl`)
  writeFileSync(file, agentLines(PLAIN, false))
  const first = agents.agentEvents(id, PLAIN)!
  assert.equal(first.full, true)
  assert.deepEqual(first.events.map((e) => e.kind), ['user', 'assistant', 'tool'])
  // Nothing new: nothing to send.
  assert.deepEqual(agents.agentEvents(id, PLAIN, 200, first.cursor), { events: [], cursor: first.cursor, full: false })
  // Its Bash call ends and it hands back: that call, closed now, and its report; nothing else.
  appendFileSync(file, agentLines(PLAIN, true).split('\n').slice(3).join('\n'))
  const next = agents.agentEvents(id, PLAIN, 200, first.cursor)!
  assert.equal(next.full, false)
  assert.notEqual(next.cursor, first.cursor)
  assert.deepEqual(next.events.map((e) => [e.kind, e.kind === 'tool' ? e.status : null]), [['tool', 'done'], ['assistant', null]])
  assert.deepEqual(agents.agentEvents(id, PLAIN, 200, next.cursor)!.events, [])
  // A cursor from another read (the daemon restarted, the file was rewritten), or none it made: all of it.
  assert.equal(agents.agentEvents(id, PLAIN, 200, 'deadbeef.3')!.full, true)
  assert.equal(agents.agentEvents(id, PLAIN, 200, '../x')!.events.length, 4)
  // Further behind than the window it asks for: the window, its task first.
  assert.deepEqual(agents.agentEvents(id, PLAIN, 1, first.cursor), { events: agents.agentEvents(id, PLAIN, 1)!.events, cursor: next.cursor, full: true })
})

// ── Ends nobody announces ─────────────────────────────────────────────────
/** Lines as logged [ms] ago (the fixtures' clock is a fixed morning, maybe still ahead of this machine's). */
const ago = (lines: object[], ms: number) => lines.map((o) => ({ ...o, timestamp: new Date(Date.now() - ms).toISOString() }))

test('when Claude Code quits, its background agents and workflows end with it, and so do their calls', async () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000008'
  const reported = 'a5555555555555555'
  const lastSecond = 'a7777777777777777'
  const { sub, runDir } = makeSession(id, [prompt('p1', 'Survey and fix the parser'), ...launchAgent('toolu_a1', PLAIN, 'Review the parser'), ...launchAgent('toolu_a2', reported, 'Write it up'), ...launchAgent('toolu_a3', lastSecond, 'Fix the tests'), ...launchWorkflow('toolu_wf1')])
  writeFileSync(join(sub, `agent-${PLAIN}.jsonl`), agentLines(PLAIN, false))
  writeFileSync(join(sub, `agent-${reported}.jsonl`), agentLines(reported, true))
  writeFileSync(join(sub, `agent-${lastSecond}.jsonl`), agentLines(lastSecond, false))
  writeMeta(runDir, SURVEYOR, { agentType: 'workflow-subagent', description: 'survey:parser', workflowPhase: 'Survey' })
  writeFileSync(join(runDir, `agent-${SURVEYOR}.jsonl`), agentLines(SURVEYOR, false))
  writeFileSync(join(runDir, 'journal.jsonl'), line({ type: 'started', key: 'k1', agentId: SURVEYOR, label: 'survey:parser', phase: 'Survey' }))
  agents.pollAgents()
  assert.equal(hub.getSession(id)!.agentsRunning, 4)
  // /clear ends the session but not the process: its agents may still work.
  await handleHook({ session_id: id, cwd: '/tmp/agents', hook_event_name: 'SessionEnd', reason: 'clear' }, {} as never, {} as never)
  assert.equal(hub.getSession(id)!.agentsRunning, 4)
  // One hands back its report after the last scan, just before Claude Code quits.
  appendFileSync(join(sub, `agent-${lastSecond}.jsonl`), agentLines(lastSecond, true).split('\n').slice(3).join('\n'))
  await handleHook({ session_id: id, cwd: '/tmp/agents', hook_event_name: 'SessionEnd', reason: 'prompt_input_exit' }, {} as never, {} as never)
  const list = agents.listAgents(id)
  const status = (a: string) => list.agents.find((x) => x.id === a)!.status
  assert.equal(status(PLAIN), 'stopped')
  // Its report was its last word: it finished, though no notification came to say so.
  assert.equal(status(reported), 'done')
  assert.equal(status(lastSecond), 'done')
  assert.equal(status(SURVEYOR), 'stopped')
  assert.equal(list.workflows[0]!.status, 'stopped')
  assert.ok(list.workflows[0]!.endedAt != null)
  for (const call of ['toolu_a1', 'toolu_a2', 'toolu_a3', 'toolu_wf1']) {
    const ev = hub.getSession(id)!.parse!.byId.get(call)
    assert.ok(ev?.kind === 'tool' && ev.status !== 'running', call)
  }
  assert.equal(hub.getSession(id)!.agentsRunning, 0)
  assert.equal(agents.backgroundRunning(id), false)
})

test('a session whose claude process vanished (no SessionEnd) has its agents stopped; one missing from a single read of the registry doesn\'t', async () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000009'
  const { sub } = makeSession(id, [...launchAgent('toolu_a1', PLAIN, 'Review the parser')])
  writeFileSync(join(sub, `agent-${PLAIN}.jsonl`), agentLines(PLAIN, false))
  // A stand-in for its claude process.
  const claude = spawn(process.execPath, ['-e', 'setTimeout(() => {}, 60_000)'], { stdio: 'ignore' })
  const reg = join(scratch, 'claude', 'sessions')
  mkdirSync(reg, { recursive: true })
  const entry = join(reg, `${claude.pid}.json`)
  const register = () => writeFileSync(entry, JSON.stringify({ sessionId: id, pid: claude.pid, kind: 'interactive', status: 'busy', cwd: '/tmp/agents' }))
  register()
  hub.pollRegistry()
  agents.pollAgents()
  assert.equal(hub.getSession(id)!.agentsRunning, 1)
  // Its registry file caught mid-write: missing from one read while the process goes on.
  rmSync(entry)
  hub.pollRegistry()
  assert.equal(hub.getSession(id)!.state, 'ended')
  assert.equal(hub.getSession(id)!.agentsRunning, 1)
  register()
  hub.pollRegistry()
  assert.equal(hub.getSession(id)!.state, 'working')
  assert.equal(agents.listAgents(id).agents[0]!.status, 'running')
  // The process is gone for good.
  claude.kill()
  await once(claude, 'exit')
  rmSync(entry)
  hub.pollRegistry()
  assert.equal(hub.getSession(id)!.state, 'ended')
  assert.equal(agents.listAgents(id).agents[0]!.status, 'stopped')
  assert.equal(hub.getSession(id)!.agentsRunning, 0)
})

test('a running workflow whose journal went quiet is stopped once its session has ended, not while it goes on', () => {
  const id = 'eeeeeeee-0000-4000-8000-00000000000a'
  const { runDir } = makeSession(id, ago([prompt('p1', 'Survey the parser'), ...launchWorkflow('toolu_wf1')], 10 * 60_000))
  mkdirSync(runDir, { recursive: true })
  writeFileSync(join(runDir, 'journal.jsonl'), [
    { type: 'started', key: 'k1', agentId: SURVEYOR, label: 'survey:parser', phase: 'Survey' },
    { type: 'result', key: 'k1', agentId: SURVEYOR, result: { summary: 'Parser links agents' } },
  ].map(line).join(''))
  const old = new Date(Date.now() - 5 * 60_000)
  utimesSync(join(runDir, 'journal.jsonl'), old, old)
  utimesSync(runDir, old, old)
  agents.pollAgents()
  // Between phases in a live session: it carries on, and the session is still busy with it.
  assert.equal(agents.listAgents(id).workflows[0]!.status, 'running')
  assert.equal(hub.getSession(id)!.agentsRunning, 1)
  hub.upsertSession(id, { state: 'ended' })
  agents.pollAgents()
  const wf = agents.listAgents(id).workflows[0]!
  assert.equal(wf.status, 'stopped')
  assert.equal(wf.done, 1)
  assert.equal(hub.getSession(id)!.agentsRunning, 0)
  const call = hub.getSession(id)!.parse!.byId.get('toolu_wf1')
  assert.ok(call?.kind === 'tool' && call.status !== 'running')
})

test('a workflow step run again is a new agent: the attempt before it stops and no longer counts', () => {
  const id = 'eeeeeeee-0000-4000-8000-00000000000b'
  const { runDir } = makeSession(id, [...launchWorkflow('toolu_wf1')])
  hub.upsertSession(id, { state: 'ended' })
  mkdirSync(runDir, { recursive: true })
  // The first attempt ended in plain text, without its structured output, a while ago.
  const side = { isSidechain: true, agentId: SURVEYOR }
  writeFileSync(join(runDir, `agent-${SURVEYOR}.jsonl`), [prompt('w1', 'Survey the parser.', { ...side, parentUuid: null }), assistant('w2', [{ type: 'text', text: 'The parser links agents.' }], side)].map(line).join(''))
  const old = new Date(Date.now() - 5 * 60_000)
  utimesSync(join(runDir, `agent-${SURVEYOR}.jsonl`), old, old)
  writeFileSync(join(runDir, 'journal.jsonl'), line({ type: 'started', key: 'k1', agentId: SURVEYOR, label: 'survey:parser', phase: 'Survey' }))
  agents.pollAgents()
  // Its last word was text: taken as done, for now.
  assert.equal(agents.listAgents(id).agents.find((a) => a.id === SURVEYOR)!.status, 'done')
  const { got, stop } = listen()
  appendFileSync(join(runDir, 'journal.jsonl'), line({ type: 'started', key: 'k1', agentId: FIXER, label: 'survey:parser', phase: 'Survey' }))
  agents.pollAgents()
  // Phones drop the earlier attempt: its broadcast says it's superseded.
  assert.ok(got.some((m) => m.t === 'agent' && m.agent.id === SURVEYOR && m.agent.status === 'stopped' && m.agent.superseded === true))
  assert.ok(got.some((m) => m.t === 'agent' && m.agent.id === FIXER) && got.every((m) => m.t !== 'agent' || m.agent.id !== FIXER || !('superseded' in m.agent)))
  let list = agents.listAgents(id)
  assert.deepEqual(list.agents.map((a) => [a.id, a.status]), [[FIXER, 'running']])
  assert.equal(list.workflows[0]!.agentCount, 1)
  assert.equal(list.workflows[0]!.done, 0)
  appendFileSync(join(runDir, 'journal.jsonl'), line({ type: 'result', key: 'k1', agentId: FIXER, result: { summary: 'Parser links agents' } }))
  agents.pollAgents()
  list = agents.listAgents(id)
  assert.equal(list.workflows[0]!.agentCount, 1)
  assert.equal(list.workflows[0]!.done, 1)
  stop()
})

test('a prompt typed while a foreground agent works waits behind it; background agents don\'t hold the turn', async () => {
  const id = 'eeeeeeee-0000-4000-8000-00000000000c'
  makeSession(id, [prompt('p1', 'Look at the docs'), assistant('as-fg', [toolUse('toolu_fg', 'Agent', { description: 'Check the docs', prompt: 'Read the docs.', subagent_type: 'Explore' })])])
  assert.equal(hub.toolRunning(id), true)
  // Its call returned at once (the hook says so before the transcript does): it went on in the background.
  await handleHook({ session_id: id, cwd: '/tmp/agents', hook_event_name: 'PostToolUse', tool_name: 'Agent', tool_use_id: 'toolu_fg', tool_response: { isAsync: true, status: 'async_launched', agentId: PLAIN } }, {} as never, {} as never)
  const call = hub.getSession(id)!.parse!.byId.get('toolu_fg')
  assert.ok(call?.kind === 'tool' && call.status === 'running')
  assert.equal(hub.toolRunning(id), false)
  const other = 'eeeeeeee-0000-4000-8000-00000000000d'
  makeSession(other, [prompt('p1', 'Review it'), ...launchAgent('toolu_a1', PLAIN, 'Review the parser')])
  assert.equal(hub.toolRunning(other), false)
})

test('a run started again from where it stopped (resumeFromRunId) runs under its new call, with the agents it starts now', async () => {
  const id = 'eeeeeeee-0000-4000-8000-00000000000e'
  const { transcriptPath, runDir } = makeSession(id, [prompt('p1', 'Survey the parser'), ...launchWorkflow('toolu_wf1')])
  writeMeta(runDir, SURVEYOR, { agentType: 'workflow-subagent', description: 'survey:parser', workflowPhase: 'Survey' })
  writeFileSync(join(runDir, `agent-${SURVEYOR}.jsonl`), agentLines(SURVEYOR, false))
  writeFileSync(join(runDir, 'journal.jsonl'), line({ type: 'started', key: 'k1', agentId: SURVEYOR, label: 'survey:parser', phase: 'Survey' }))
  agents.pollAgents()
  assert.equal(hub.getSession(id)!.agentsRunning, 1)
  // Claude Code quits mid-run: the run, its agent and its call stop.
  await handleHook({ session_id: id, cwd: '/tmp/agents', hook_event_name: 'SessionEnd', reason: 'prompt_input_exit' }, {} as never, {} as never)
  assert.equal(agents.listAgents(id).workflows[0]!.status, 'stopped')
  assert.equal(hub.getSession(id)!.agentsRunning, 0)

  // The session is resumed, and the run with it: the same run id, under a new call and task.
  await handleHook({ session_id: id, cwd: '/tmp/agents', hook_event_name: 'SessionStart', source: 'resume' }, {} as never, {} as never)
  const { got, stop } = listen()
  const TASK2 = 'w9f8e7d6c'
  appendFileSync(transcriptPath, resumeWorkflow('toolu_wf2', TASK2).map(line).join(''))
  hub.pollTranscripts()
  let wf = agents.listAgents(id).workflows[0]!
  assert.equal(wf.status, 'running')
  assert.equal(wf.toolUseId, 'toolu_wf2')
  assert.equal(wf.taskId, TASK2)
  assert.equal(wf.endedAt, null)
  assert.ok(got.some((m) => m.t === 'workflow' && m.workflow.id === RUN && m.workflow.status === 'running' && m.workflow.toolUseId === 'toolu_wf2'))
  // Before its agent's meta file is read the session is busy with the workflow all the same.
  assert.equal(hub.getSession(id)!.agentsRunning, 1)
  // The first call's late notification (killed with Claude Code) doesn't end the run again.
  appendFileSync(transcriptPath, line(enqueue(notification(WF_TASK, 'toolu_wf1', 'killed', workflowUsage))))
  hub.pollTranscripts()
  assert.equal(agents.listAgents(id).workflows[0]!.status, 'running')
  assert.equal(agents.listAgents(id).workflows[0]!.toolUseId, 'toolu_wf2')

  // The step that hadn't finished runs again, as a new agent: the attempt before no longer counts.
  writeMeta(runDir, FIXER, { agentType: 'workflow-subagent', description: 'survey:parser', workflowPhase: 'Survey' })
  writeFileSync(join(runDir, `agent-${FIXER}.jsonl`), agentLines(FIXER, false))
  appendFileSync(join(runDir, 'journal.jsonl'), line({ type: 'started', key: 'k1', agentId: FIXER, label: 'survey:parser', phase: 'Survey' }))
  agents.pollAgents()
  const list = agents.listAgents(id)
  assert.deepEqual(list.agents.map((a) => [a.id, a.status]), [[FIXER, 'running']])
  assert.equal(list.workflows[0]!.agentCount, 1)
  assert.equal(hub.getSession(id)!.agentsRunning, 1)
  assert.ok(got.some((m) => m.t === 'agent' && m.agent.id === SURVEYOR && m.agent.superseded === true))

  // Its notification ends it, and its new call.
  appendFileSync(join(runDir, 'journal.jsonl'), line({ type: 'result', key: 'k1', agentId: FIXER, result: { summary: 'Parser links agents' } }))
  agents.pollAgents()
  appendFileSync(transcriptPath, line(enqueue(notification(TASK2, 'toolu_wf2', 'completed', workflowUsage))))
  hub.pollTranscripts()
  wf = agents.listAgents(id).workflows[0]!
  assert.equal(wf.status, 'done')
  assert.equal(wf.done, 1)
  const call = hub.getSession(id)!.parse!.byId.get('toolu_wf2')
  assert.ok(call?.kind === 'tool' && call.status === 'done')
  assert.equal(hub.getSession(id)!.agentsRunning, 0)
  stop()
})

test('a run started again and read only later (the daemon restarted): the summary its attempt before left doesn\'t end it', () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000010'
  const { snapshots } = makeSession(id, [prompt('p1', 'Survey the parser'), ...launchWorkflow('toolu_wf1'), ...resumeWorkflow('toolu_wf2', 'w9f8e7d6c')])
  mkdirSync(snapshots, { recursive: true })
  const file = join(snapshots, `${RUN}.json`)
  writeFileSync(file, JSON.stringify({ runId: RUN, taskId: WF_TASK, workflowName: 'feedback-survey', status: 'stopped', startTime: T0, durationMs: 1000, workflowProgress: [] }))
  utimesSync(file, new Date(T0 + 1000), new Date(T0 + 1000))
  agents.pollAgents()
  const wf = agents.listAgents(id).workflows[0]!
  assert.equal(wf.status, 'running')
  assert.equal(wf.toolUseId, 'toolu_wf2')
  // The call that started it the first time doesn't run on.
  const first = hub.getSession(id)!.parse!.byId.get('toolu_wf1')
  assert.ok(first?.kind === 'tool' && first.status !== 'running')
  // Its own summary, written as it ends, counts.
  writeFileSync(file, JSON.stringify({ runId: RUN, taskId: 'w9f8e7d6c', workflowName: 'feedback-survey', status: 'completed', startTime: T0, durationMs: 600000, workflowProgress: [] }))
  // After the call that started it again, by the fixtures' clock (it may be ahead of this machine's).
  const later = new Date(Math.max(Date.now(), T0 + tick * 1000) + 60_000)
  utimesSync(file, later, later)
  utimesSync(snapshots, later, later)
  agents.pollAgents()
  assert.equal(agents.listAgents(id).workflows[0]!.status, 'done')
})

test('an Agent call that comes back interrupted or failed stops the agent it ran in the foreground', () => {
  const id = 'eeeeeeee-0000-4000-8000-00000000000f'
  const late = 'a6666666666666666'
  const call = (toolUseId: string, description: string) => assistant(`as-${toolUseId}`, [toolUse(toolUseId, 'Agent', { description, prompt: 'Read the docs.', subagent_type: 'Explore' })])
  const { transcriptPath, sub } = makeSession(id, [prompt('p1', 'Look at the docs'), call('toolu_fg', 'Check the docs')])
  writeMeta(sub, PLAIN, { agentType: 'Explore', description: 'Check the docs', toolUseId: 'toolu_fg' })
  writeFileSync(join(sub, `agent-${PLAIN}.jsonl`), agentLines(PLAIN, false))
  agents.pollAgents()
  assert.equal(hub.getSession(id)!.agentsRunning, 1)
  // Esc: Claude Code ends the call with an error result, and no notification follows.
  const { got, stop } = listen()
  appendFileSync(transcriptPath, line(failed('ur-fg', 'toolu_fg', '[Request interrupted by user for tool use]')))
  hub.pollTranscripts()
  assert.equal(agents.listAgents(id).agents.find((a) => a.id === PLAIN)!.status, 'stopped')
  assert.ok(got.some((m) => m.t === 'agent' && m.agent.id === PLAIN && m.agent.status === 'stopped'))
  assert.equal(hub.getSession(id)!.agentsRunning, 0)
  // Another call fails before its agent's meta file was read: the agent stops as it's read.
  appendFileSync(transcriptPath, [call('toolu_fg2', 'Check the tests'), failed('ur-fg2', 'toolu_fg2', 'API Error: Overloaded')].map(line).join(''))
  hub.pollTranscripts()
  writeMeta(sub, late, { agentType: 'Explore', description: 'Check the tests', toolUseId: 'toolu_fg2' })
  writeFileSync(join(sub, `agent-${late}.jsonl`), agentLines(late, false))
  agents.pollAgents()
  assert.equal(agents.listAgents(id).agents.find((a) => a.id === late)!.status, 'stopped')
  assert.equal(hub.getSession(id)!.agentsRunning, 0)
  stop()
})

// ── Ends nobody announced (the "11 AGENTS" while 4 run) ──────────────────
test('TaskStop on a background agent ends it stopped, from its result or from its own input', () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000101'
  const other = 'a7777777777777777'
  const { transcriptPath } = makeSession(id, [...launchAgent('toolu_a1', PLAIN, 'Review the parser'), ...launchAgent('toolu_a2', other, 'Run the build')])
  assert.equal(hub.getSession(id)!.agentsRunning, 2)
  appendFileSync(transcriptPath, [
    assistant('as-stop1', [toolUse('toolu_stop1', 'TaskStop', { task_id: PLAIN })]),
    result('ur-stop1', 'toolu_stop1', 'Stopped.', { message: 'Successfully stopped task', task_id: PLAIN, task_type: 'local_agent' }),
    // A result that's only words: the call's own input names the task.
    assistant('as-stop2', [toolUse('toolu_stop2', 'TaskStop', { task_id: other })]),
    result('ur-stop2', 'toolu_stop2', 'Stopped.'),
  ].map(line).join(''))
  hub.pollTranscripts()
  const list = agents.listAgents(id).agents
  assert.equal(list.find((a) => a.id === PLAIN)!.status, 'stopped')
  assert.equal(list.find((a) => a.id === other)!.status, 'stopped')
  assert.equal(hub.getSession(id)!.agentsRunning, 0)
  // Its call stops with it.
  const call = hub.getSession(id)!.parse!.byId.get('toolu_a1')
  assert.ok(call?.kind === 'tool' && call.status !== 'running')
})

test('an agent cut off by an API error (a 429, the usage limit) ends once its file has been quiet two minutes; one waiting on a tool doesn’t', () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000102'
  const waiting = 'a8888888888888888'
  const { sub } = makeSession(id, [...launchAgent('toolu_a1', PLAIN, 'Review the parser'), ...launchAgent('toolu_a2', waiting, 'Run the build')])
  writeMeta(sub, PLAIN, { agentType: 'Explore', description: 'Review the parser', toolUseId: 'toolu_a1' })
  writeMeta(sub, waiting, { agentType: 'Explore', description: 'Run the build', toolUseId: 'toolu_a2' })
  const side = { isSidechain: true, agentId: PLAIN }
  writeFileSync(join(sub, `agent-${PLAIN}.jsonl`), agentLines(PLAIN, false) + line(result('s4', 'tu-bash', 'ok', undefined, side)) +
    line({ type: 'assistant', uuid: 's5', timestamp: at(), isSidechain: true, agentId: PLAIN, isApiErrorMessage: true, message: { id: 'msg-s5', role: 'assistant', model: '<synthetic>', content: [{ type: 'text', text: 'API Error: 429 {"type":"error","error":{"type":"rate_limit_error"}}' }] } }))
  writeFileSync(join(sub, `agent-${waiting}.jsonl`), agentLines(waiting, false))
  const old = new Date(Date.now() - 3 * 60_000)
  utimesSync(join(sub, `agent-${PLAIN}.jsonl`), old, old)
  utimesSync(join(sub, `agent-${waiting}.jsonl`), old, old)
  agents.pollAgents()
  const list = agents.listAgents(id).agents
  assert.equal(list.find((a) => a.id === PLAIN)!.status, 'stopped')
  assert.equal(list.find((a) => a.id === waiting)!.status, 'running')
  assert.equal(hub.getSession(id)!.agentsRunning, 1)
})

test('while its session is stopped by a limit, an agent silent for a minute stops with it, even one waiting on a tool', () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000103'
  const { sub } = makeSession(id, [...launchAgent('toolu_a1', PLAIN, 'Review the parser')])
  writeMeta(sub, PLAIN, { agentType: 'Explore', description: 'Review the parser', toolUseId: 'toolu_a1' })
  writeFileSync(join(sub, `agent-${PLAIN}.jsonl`), agentLines(PLAIN, false))
  const old = new Date(Date.now() - 90_000)
  utimesSync(join(sub, `agent-${PLAIN}.jsonl`), old, old)
  agents.pollAgents()
  assert.equal(hub.getSession(id)!.agentsRunning, 1)
  // A model's own week doesn't stop it (it may run on another model).
  hub.upsertSession(id, { blocked: { kind: 'model', text: 'Fable limit', resetsAt: null, autoResumeAt: null } })
  agents.pollAgents()
  assert.equal(hub.getSession(id)!.agentsRunning, 1)
  hub.upsertSession(id, { blocked: { kind: 'limit', text: 'You’ve hit your session limit', resetsAt: Date.now() + 3600_000, autoResumeAt: null } })
  agents.pollAgents()
  assert.equal(agents.listAgents(id).agents.find((a) => a.id === PLAIN)!.status, 'stopped')
  assert.equal(hub.getSession(id)!.agentsRunning, 0)
})

test('the main Stop’s list of background tasks: an agent of the main agent’s it no longer names has ended; one it names, one a workflow or agent runs, and a resumed one it names go on', async () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000104'
  const listed = 'a9999999999999999'
  const reported = 'abbbbbbbbbbbbbbbb'
  const nested = 'acccccccccccccccc'
  const { sub, transcriptPath } = makeSession(id, [
    ...launchAgent('toolu_a1', PLAIN, 'Review the parser'),
    ...launchAgent('toolu_a2', listed, 'Run the build'),
    ...launchAgent('toolu_a3', reported, 'Read the docs'),
  ])
  for (const [a, t] of [[PLAIN, 'toolu_a1'], [listed, 'toolu_a2'], [reported, 'toolu_a3']] as const) writeMeta(sub, a, { agentType: 'Explore', description: a, toolUseId: t })
  writeMeta(sub, nested, { agentType: 'Explore', description: 'nested', toolUseId: 'toolu_n', parentAgentId: listed })
  writeFileSync(join(sub, `agent-${PLAIN}.jsonl`), agentLines(PLAIN, false))
  writeFileSync(join(sub, `agent-${listed}.jsonl`), agentLines(listed, false))
  writeFileSync(join(sub, `agent-${reported}.jsonl`), agentLines(reported, true))
  writeFileSync(join(sub, `agent-${nested}.jsonl`), agentLines(nested, false))
  agents.pollAgents()
  // Started a while ago, as far as the board knows (the launch lines are half an hour old).
  assert.equal(hub.getSession(id)!.agentsRunning, 4)
  await handleHook({ session_id: id, cwd: '/tmp/agents', hook_event_name: 'Stop', background_tasks: [{ id: listed, type: 'local_agent', status: 'running', description: 'Run the build' }] }, {} as never, {} as never)
  const by = (x: string) => agents.listAgents(id).agents.find((a) => a.id === x)!
  assert.equal(by(PLAIN).status, 'stopped')
  assert.equal(by(reported).status, 'done')
  assert.equal(by(reported).result, 'Agents link by toolUseId.\nNotifications end them.')
  assert.equal(by(listed).status, 'running')
  assert.equal(by(nested).status, 'running')
  assert.equal(hub.getSession(id)!.agentsRunning, 2)
  // A notification later still says how it really ended.
  const text = notification(PLAIN, 'toolu_a1', 'completed', agentUsage)
  appendFileSync(transcriptPath, line(prompt('n1', text, { origin: { kind: 'task-notification' }, promptSource: 'system' })))
  hub.pollTranscripts()
  assert.equal(by(PLAIN).status, 'done')
  // SendMessage wakes the stopped one; the next Stop names it, so it goes on.
  appendFileSync(transcriptPath, [assistant('as-send', [toolUse('toolu_send', 'SendMessage', { to: PLAIN, message: 'one more thing' })]), result('ur-send', 'toolu_send', 'Sent.', { resumedAgentId: PLAIN })].map(line).join(''))
  hub.pollTranscripts()
  assert.equal(by(PLAIN).status, 'running')
  await handleHook({ session_id: id, cwd: '/tmp/agents', hook_event_name: 'Stop', background_tasks: [{ id: listed, type: 'local_agent', status: 'running' }, { id: PLAIN, type: 'local_agent', status: 'running' }] }, {} as never, {} as never)
  assert.equal(by(PLAIN).status, 'running')
  // A Stop without the list (an older Claude Code) ends nothing.
  await handleHook({ session_id: id, cwd: '/tmp/agents', hook_event_name: 'Stop' }, {} as never, {} as never)
  assert.equal(by(PLAIN).status, 'running')
})

test('SubagentStop ends a running agent done with its last message; one already stopped keeps how it ended', async () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000105'
  const other = 'addddddddddddddd1'.slice(0, 17)
  const { transcriptPath } = makeSession(id, [...launchAgent('toolu_a1', PLAIN, 'Review the parser'), ...launchAgent('toolu_a2', other, 'Run the build')])
  const { got, stop } = listen()
  await handleHook({ session_id: id, cwd: '/tmp/agents', hook_event_name: 'SubagentStop', agent_id: PLAIN, agent_type: 'Explore', last_assistant_message: 'The parser links agents by toolUseId.' }, {} as never, {} as never)
  const a = agents.listAgents(id).agents.find((x) => x.id === PLAIN)!
  assert.equal(a.status, 'done')
  assert.equal(a.result, 'The parser links agents by toolUseId.')
  assert.ok(got.some((m) => m.t === 'session' && m.session.id === id && m.session.agentsRunning === 1))
  appendFileSync(transcriptPath, [assistant('as-stop', [toolUse('toolu_stop', 'TaskStop', { task_id: other })]), result('ur-stop', 'toolu_stop', 'Stopped.', { task_id: other, task_type: 'local_agent' })].map(line).join(''))
  hub.pollTranscripts()
  await handleHook({ session_id: id, cwd: '/tmp/agents', hook_event_name: 'SubagentStop', agent_id: other }, {} as never, {} as never)
  assert.equal(agents.listAgents(id).agents.find((x) => x.id === other)!.status, 'stopped')
  assert.equal(hub.getSession(id)!.agentsRunning, 0)
  stop()
})

// ── Waiting on its own background work ──────────────────────────────────
// Shapes as Claude Code 2.1.316 records them: a subagent that starts a background command and waits
// for it ends its turn (SubagentStop) and wakes when the command ends. Written by hand, as above.
const WAITER = 'a3333333333333333'
const SHELL = 'b0fa11y1e'
const side = (agentId = WAITER) => ({ isSidechain: true, agentId })
const shellResult = (id: string) => ({ stdout: '', stderr: '', interrupted: false, isImage: false, noOutputExpected: false, backgroundTaskId: id })
/** A Bash call run in the background, and its result naming the shell. */
const bgBash = (toolUseId: string, shell = SHELL) => [
  assistant(`as-${toolUseId}`, [toolUse(toolUseId, 'Bash', { command: 'until grep -q done /tmp/x.log; do sleep 5; done', description: 'Wait for the build', run_in_background: true })], side()),
  result(`ur-${toolUseId}`, toolUseId, `Command running in background with ID: ${shell}. Output is being written to: /tmp/tasks/${shell}.output. You will be notified when it completes.`, shellResult(shell), side()),
]
/** A reply that ends its turn. */
const endTurn = (uuid: string, text: string) => {
  const a = assistant(uuid, [{ type: 'text', text }], side())
  return { ...a, message: { ...a.message, stop_reason: 'end_turn' } }
}
const stopHookLine = () => ({ type: 'attachment', uuid: 'hook-stop', timestamp: at(), ...side(), attachment: { type: 'hook_success', hookName: 'SubagentStop', hookEvent: 'SubagentStop', exitCode: 200 } })
const NOTICE = '[SYSTEM NOTIFICATION - NOT USER INPUT]\nThis is an automated background-task event, NOT a message from the user.\n\n'
/** What wakes it: a command's end as the input of its next turn, after Claude Code's notice. */
const bgEnded = (shell: string, toolUseId: string, status = 'completed', summary = `Background command "Wait for the build" completed (exit code 0)`) => ({
  type: 'user', uuid: `wake-${shell}`, timestamp: at(), sessionId: 'S', ...side(), isMeta: true, origin: { kind: 'task-notification', producer: 'session-task' },
  message: { role: 'user', content: `${NOTICE}<task-notification>\n<task-id>${shell}</task-id>\n<tool-use-id>${toolUseId}</tool-use-id>\n<output-file>/tmp/tasks/${shell}.output</output-file>\n<status>${status}</status>\n<summary>${summary}</summary>\n</task-notification>` },
})
/** The main transcript's word on a waiting agent: "completed", and the words alone say it isn't over. */
const waitingNote = (agentId: string, durationMs: number, resultText = 'This agent has not reported yet: it is waiting on its own background work and will deliver its report through SubagentHandback when that finishes.', toolUseId?: string) =>
  `<task-notification>\n<task-id>${agentId}</task-id>\n${toolUseId ? `<tool-use-id>${toolUseId}</tool-use-id>\n` : ''}<output-file>/tmp/${agentId}.output</output-file>\n<status>completed</status>\n<summary>Agent "Build it" is waiting on its own background work</summary>\n<note>This agent stopped with background work of its own still running. It may resume on its own when that work completes or reports, and the same task-id notifies again if it does; the result below may be interim.</note>\n<result>${resultText}\n</result>\n<usage><subagent_tokens>191703</subagent_tokens><tool_uses>64</tool_uses><duration_ms>${durationMs}</duration_ms></usage>\n</task-notification>`
const finishedNote = (agentId: string, durationMs: number) =>
  `<task-notification>\n<task-id>${agentId}</task-id>\n<output-file>/tmp/${agentId}.output</output-file>\n<status>completed</status>\n<summary>Agent "Build it" finished</summary>\n<note>A task-notification fires each time this agent stops with no live background children of its own. The user can send it another message and resume it, so the same task-id may notify more than once.</note>\n<result>This agent's report was delivered to you as a message.\n</result>\n<usage><subagent_tokens>200000</subagent_tokens><tool_uses>70</tool_uses><duration_ms>${durationMs}</duration_ms></usage>\n</task-notification>`
const handBack = (uuid: string, message: string) => assistant(uuid, [toolUse(`tu-${uuid}`, 'SubagentHandback', { message })], side())

/** A session with one background agent at [lines] so far, seen by the board; [main] after its launch. */
function waiter(id: string, lines: object[], main: object[] = []) {
  const s = makeSession(id, [...launchAgent('toolu_w', WAITER, 'Build it', 'general-purpose'), ...main])
  writeMeta(s.sub, WAITER, { agentType: 'general-purpose', description: 'Build it', toolUseId: 'toolu_w' })
  const file = join(s.sub, `agent-${WAITER}.jsonl`)
  writeFileSync(file, lines.map(line).join(''))
  agents.pollAgents()
  const me = () => agents.listAgents(id).agents.find((a) => a.id === WAITER)!
  const stop = (last: string, tasks?: object[]) => handleHook({ session_id: id, cwd: '/tmp/agents', hook_event_name: 'SubagentStop', agent_id: WAITER, agent_type: 'general-purpose', last_assistant_message: last, ...(tasks ? { background_tasks: tasks } : {}) }, {} as never, {} as never)
  const hookTool = () => handleHook({ session_id: id, cwd: '/tmp/agents', hook_event_name: 'PreToolUse', agent_id: WAITER, agent_type: 'general-purpose', tool_name: 'Bash', tool_use_id: `t-${tick}`, tool_input: { command: 'grep FAILED /tmp/x.log' } }, {} as never, {} as never)
  const running = () => hub.getSession(id)!.agentsRunning
  return { ...s, file, me, stop, hookTool, running, append: (...o: object[]) => appendFileSync(file, o.map(line).join('')) }
}
const shellTask = (id = SHELL) => ({ id, type: 'local_bash', status: 'running', description: 'Wait for the build', command: 'until …' })
const WAITING = 'Waiting for its background command'

test('a background command and then SubagentStop: still running, saying it waits for it', async () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000901'
  const w = waiter(id, [prompt('s1', 'Build it.', { ...side(), parentUuid: null }), ...bgBash('tu-bg'), endTurn('s2', 'The build is running, so I am waiting for it to finish.')])
  const { got, stop } = listen()
  await w.stop('The build is running, so I am waiting for it to finish.', [shellTask()])
  const a = w.me()
  assert.equal(a.status, 'running')
  assert.equal(a.activity, WAITING)
  assert.equal(a.endedAt, null)
  assert.equal(a.result, null)
  assert.equal(w.running(), 1)
  // Nothing tells a phone it ended, and its line goes out.
  assert.ok(!got.some((m) => m.t === 'agent' && m.agent.id === WAITER && m.agent.status !== 'running'))
  const st = hub.getSession(id)!.parse!.byId.get('toolu_w')
  assert.ok(st?.kind === 'tool')
  assert.equal(st.status, 'running')
  stop()
})

test('Claude Code\'s word that it waits ends nothing and keeps its call running; the word is believed with no file to go on', async () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000902'
  const w = waiter(id, [prompt('s1', 'Build it.', side()), ...bgBash('tu-bg'), endTurn('s2', 'Waiting for the build.')])
  await w.stop('Waiting for the build.', [shellTask()])
  appendFileSync(w.transcriptPath, line(prompt('n1', waitingNote(WAITER, 35045882, undefined, 'toolu_w'), { origin: { kind: 'task-notification' }, promptSource: 'system' })))
  hub.pollTranscripts()
  assert.equal(w.me().status, 'running')
  assert.equal(w.me().activity, WAITING)
  assert.equal(hub.getSession(id)!.agentsRunning, 1)
  const call = hub.getSession(id)!.parse!.byId.get('toolu_w')
  assert.ok(call?.kind === 'tool')
  assert.equal(call.status, 'running')

  // Its launch long before the part of its file followed: the word alone holds it, and brings back
  // one a SubagentStop took as done a moment earlier.
  const other = 'eeeeeeee-0000-4000-8000-000000000903'
  const v = waiter(other, [prompt('s1', 'Build it.', side()), endTurn('s2', 'Waiting for the build.')])
  await v.stop('Waiting for the build.')
  assert.equal(v.me().status, 'done')
  appendFileSync(v.transcriptPath, line(enqueue(waitingNote(WAITER, 1000))))
  hub.pollTranscripts()
  assert.equal(v.me().status, 'running')
  assert.equal(v.me().activity, 'Waiting for its background work')
  assert.equal(v.me().result, null)
  assert.equal(v.me().endedAt, null)
})

test('wording it does not know: its own file decides; "finished" with a command in its file still ends it', async () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000904'
  const w = waiter(id, [...bgBash('tu-bg'), endTurn('s2', 'Waiting for the build.')])
  await w.stop('Waiting for the build.', [shellTask()])
  const reworded = waitingNote(WAITER, 2000).replace('is waiting on its own background work', 'is paused').replace(/<note>.*<\/note>/, '')
  appendFileSync(w.transcriptPath, line(enqueue(reworded)))
  hub.pollTranscripts()
  assert.equal(w.me().status, 'running')
  // Claude Code knows better than the file: nothing of its own is left alive.
  appendFileSync(w.transcriptPath, line(enqueue(finishedNote(WAITER, 3000))))
  hub.pollTranscripts()
  assert.equal(w.me().status, 'done')
})

test('the wake-up carries on, a second wait holds, and the real finish ends it done with its report', async () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000905'
  const w = waiter(id, [prompt('s1', 'Build it.', side()), ...bgBash('tu-bg'), endTurn('s2', 'Waiting for the build.')])
  await w.stop('Waiting for the build.', [shellTask()])
  appendFileSync(w.transcriptPath, line(enqueue(waitingNote(WAITER, 1000))))
  hub.pollTranscripts()
  // The command ended: its news is the input of the agent's next turn.
  w.append(bgEnded(SHELL, 'tu-bg'))
  agents.pollAgents()
  assert.equal(w.me().status, 'running')
  assert.notEqual(w.me().activity, WAITING)
  await w.hookTool()
  assert.equal(w.me().status, 'running')
  assert.match(w.me().activity!, /^Bash/)
  // A second wait, its second word in the main transcript: not a copy of the first.
  w.append(...bgBash('tu-bg2', 'be6ot3sif'), endTurn('s3', 'Both suites are running; waiting for them.'))
  await w.stop('Both suites are running; waiting for them.', [shellTask('be6ot3sif')])
  appendFileSync(w.transcriptPath, line(enqueue(waitingNote(WAITER, 2000))))
  hub.pollTranscripts()
  assert.equal(w.me().status, 'running')
  assert.equal(w.me().activity, WAITING)
  w.append(bgEnded('be6ot3sif', 'tu-bg2'))
  agents.pollAgents()
  await w.hookTool()
  // Its report: done at once, and the launcher's call with it.
  w.append(handBack('s4', 'The build is green.'))
  await w.stop('The build is green.', [])
  assert.equal(w.me().status, 'done')
  assert.equal(w.me().result, 'The build is green.')
  assert.equal(w.me().activity, null)
  assert.equal(hub.getSession(id)!.agentsRunning, 0)
  // Its last notification gets through after the waiting ones, and says the same.
  appendFileSync(w.transcriptPath, line(enqueue(finishedNote(WAITER, 3000))))
  hub.pollTranscripts()
  assert.equal(w.me().status, 'done')
  assert.equal(w.me().result, 'The build is green.')
  const call = hub.getSession(id)!.parse!.byId.get('toolu_w')
  assert.ok(call?.kind === 'tool')
  assert.equal(call.status, 'done')
})

test('the finish reported only by its last words, after a wait, is its report', async () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000906'
  const w = waiter(id, [...bgBash('tu-bg'), endTurn('s2', 'Waiting for the build.')])
  await w.stop('Waiting for the build.', [shellTask()])
  w.append(bgEnded(SHELL, 'tu-bg'), endTurn('s3', 'The build passed.'))
  await w.stop('The build passed.', [])
  assert.equal(w.me().status, 'done')
  assert.equal(w.me().result, 'The build passed.')
})

test('no background command: SubagentStop ends it done at once, as before', async () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000907'
  const w = waiter(id, [prompt('s1', 'Read it.', side()), endTurn('s2', 'It reads well.')])
  await w.stop('It reads well.', [])
  assert.equal(w.me().status, 'done')
  assert.equal(w.me().result, 'It reads well.')
  // An older Claude Code's stop, no list, ends it the same.
  const other = 'eeeeeeee-0000-4000-8000-000000000908'
  const v = waiter(other, [endTurn('s2', 'It reads well.')])
  await v.stop('It reads well.')
  assert.equal(v.me().status, 'done')
})

test('a command that ended, was killed or is gone from the hook\'s list holds nothing', async () => {
  // Ended before the turn did: its news came mid-turn, as a queued command.
  const a = waiter('eeeeeeee-0000-4000-8000-000000000909', [
    ...bgBash('tu-bg'),
    { type: 'attachment', uuid: 'q1', timestamp: at(), ...side(), attachment: { type: 'queued_command', commandMode: 'task-notification', prompt: bgEnded(SHELL, 'tu-bg').message.content } },
    endTurn('s2', 'All built.'),
  ])
  await a.stop('All built.', [])
  assert.equal(a.me().status, 'done')
  assert.equal(a.me().result, 'All built.')

  // Failed counts as ended: exit 143 is how a killed one reads.
  const b = waiter('eeeeeeee-0000-4000-8000-000000000910', [
    ...bgBash('tu-bg'),
    { type: 'attachment', uuid: 'q1', timestamp: at(), ...side(), attachment: { type: 'queued_command', commandMode: 'task-notification', prompt: bgEnded(SHELL, 'tu-bg', 'failed', 'Background command "x" failed with exit code 143').message.content } },
    endTurn('s2', 'It failed.'),
  ])
  await b.stop('It failed.')
  assert.equal(b.me().status, 'done')

  // TaskStop, with no notification to follow; also when it answers "not running".
  const c = waiter('eeeeeeee-0000-4000-8000-000000000911', [
    ...bgBash('tu-bg'),
    assistant('s3', [toolUse('tu-stop', 'TaskStop', { task_id: SHELL })], side()),
    result('s4', 'tu-stop', 'Stopped.', { message: `Successfully stopped task: ${SHELL}`, task_id: SHELL, task_type: 'local_bash', command: 'x' }, side()),
    endTurn('s5', 'Stopped it, all done.'),
  ])
  await c.stop('Stopped it, all done.')
  assert.equal(c.me().status, 'done')
  const d = waiter('eeeeeeee-0000-4000-8000-000000000912', [
    ...bgBash('tu-bg'),
    assistant('s3', [toolUse('tu-stop', 'TaskStop', { task_id: SHELL })], side()),
    failed('s4', 'tu-stop', `Task ${SHELL} is not running (status: completed)`),
    endTurn('s5', 'Already over, all done.'),
  ])
  await d.stop('Already over, all done.')
  assert.equal(d.me().status, 'done')

  // A shell whose end never reached its file: the hook's list no longer names it.
  const e = waiter('eeeeeeee-0000-4000-8000-000000000913', [...bgBash('tu-bg'), endTurn('s2', 'Here is my full report.')])
  await e.stop('Here is my full report.', [shellTask('bother')])
  assert.equal(e.me().status, 'done')
  assert.equal(e.me().result, 'Here is my full report.')
})

test('a report handed back ends it, whatever it left running behind it', async () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000914'
  const w = waiter(id, [...bgBash('tu-bg'), handBack('s2', 'Key screens are covered.')])
  await w.stop('', [shellTask()])
  assert.equal(w.me().status, 'done')
  assert.equal(w.me().result, 'Key screens are covered.')
  // Claude Code still calls it waiting, for the shell behind it: it stays ended.
  appendFileSync(w.transcriptPath, line(enqueue(waitingNote(WAITER, 3520062, "This agent's report was delivered to you as a message from \"x\"."))))
  hub.pollTranscripts()
  assert.equal(w.me().status, 'done')
  assert.equal(w.me().result, 'Key screens are covered.')

  // Its hand-back reaches the file just after the hook: the next scan ends it.
  const other = 'eeeeeeee-0000-4000-8000-000000000915'
  const v = waiter(other, [...bgBash('tu-bg'), endTurn('s2', 'Handing it back.')])
  await v.stop('Handing it back.', [shellTask()])
  assert.equal(v.me().status, 'running')
  v.append(handBack('s3', 'Late report.'))
  agents.pollAgents()
  assert.equal(v.me().status, 'done')
  assert.equal(v.me().result, 'Late report.')
})

test('the cap: a wait with no writes ends, done with a report in hand or stopped without, and comes back if it wakes', async () => {
  const old = (file: string, hours: number) => {
    const t = new Date(Date.now() - hours * 3_600_000)
    utimesSync(file, t, t)
  }
  // Under the cap it waits on, however long past the 10 minutes an idle file gets.
  const under = waiter('eeeeeeee-0000-4000-8000-000000000933', [...bgBash('tu-bg'), endTurn('s2', 'Waiting for the build.')])
  await under.stop('Waiting for the build.', [shellTask()])
  old(under.file, 1.9)
  agents.pollAgents()
  assert.equal(under.me().status, 'running')
  assert.equal(under.me().activity, WAITING)
  // Over it. Claude Code said it hasn't reported, and its words were about waiting: no report.
  const id = 'eeeeeeee-0000-4000-8000-000000000916'
  const w = waiter(id, [...bgBash('tu-bg'), endTurn('s2', 'Waiting for the build.')])
  await w.stop('Waiting for the build.', [shellTask()])
  appendFileSync(w.transcriptPath, line(enqueue(waitingNote(WAITER, 1000))))
  hub.pollTranscripts()
  old(w.file, 2.1)
  agents.pollAgents()
  assert.equal(w.me().status, 'stopped')
  assert.equal(w.me().result, null)
  assert.equal(w.me().activity, null)
  assert.equal(hub.getSession(id)!.agentsRunning, 0)
  // One line in the log, once.
  agents.pollAgents()
  const logFile = join(process.env.HYPERNOVA_LOGS!, 'daemon.log')
  const said = readFileSync(logFile, 'utf8').split('\n').filter((l) => l.includes(`waited past its cap`) && l.includes(id))
  assert.equal(said.length, 1)
  // It was only slow: its next hook brings it back.
  await w.hookTool()
  assert.equal(w.me().status, 'running')
  assert.match(w.me().activity!, /^Bash/)

  // Nobody said it hadn't reported (an older Claude Code): its last words stand as its report.
  const other = 'eeeeeeee-0000-4000-8000-000000000917'
  const v = waiter(other, [...bgBash('tu-bg'), endTurn('s2', 'The whole report, at length.')])
  await v.stop('The whole report, at length.', [shellTask()])
  old(v.file, 2.1)
  agents.pollAgents()
  assert.equal(v.me().status, 'done')
  assert.equal(v.me().result, 'The whole report, at length.')
})

test('a long Monitor holds past the cap as long as it runs, a persistent one up to the most; an ended session ends it', async () => {
  const old = (file: string, hours: number) => {
    const t = new Date(Date.now() - hours * 3_600_000)
    utimesSync(file, t, t)
  }
  const monitor = (uuid: string, r: object) => [
    assistant(`as-${uuid}`, [toolUse(uuid, 'Monitor', { command: 'tail -f x', description: 'Watch', timeout_ms: 1 })], side()),
    result(`ur-${uuid}`, uuid, 'Monitor started (task bqd8v7a31).', r, side()),
  ]
  const a = waiter('eeeeeeee-0000-4000-8000-000000000918', [...monitor('tu-m', { taskId: 'bqd8v7a31', timeoutMs: 1800000, persistent: true }), endTurn('s2', 'Watching.')])
  await a.stop('Watching.', [shellTask('bqd8v7a31')])
  assert.equal(a.me().activity, WAITING)
  old(a.file, 11)
  agents.pollAgents()
  assert.equal(a.me().status, 'running')
  const over = waiter('eeeeeeee-0000-4000-8000-000000000934', [...monitor('tu-m', { taskId: 'bqd8v7a31', timeoutMs: 1800000, persistent: true }), endTurn('s2', 'Watching.')])
  await over.stop('Watching.', [shellTask('bqd8v7a31')])
  old(over.file, 12.5)
  agents.pollAgents()
  assert.notEqual(over.me().status, 'running')

  // A Monitor's event has no status of its own: it isn't its end. Its stream ending is.
  const b = waiter('eeeeeeee-0000-4000-8000-000000000919', [...monitor('tu-m', { taskId: 'bqd8v7a31', timeoutMs: 1800000, persistent: false }), endTurn('s2', 'Watching.')])
  await b.stop('Watching.', [shellTask('bqd8v7a31')])
  b.append({ ...bgEnded('bqd8v7a31', 'tu-m'), message: { role: 'user', content: `${NOTICE}<task-notification>\n<task-id>bqd8v7a31</task-id>\n<summary>Monitor event: "Watch"</summary>\n<event>a line</event>\n</task-notification>` } }, endTurn('s3', 'Still watching.'))
  await b.stop('Still watching.', [shellTask('bqd8v7a31')])
  assert.equal(b.me().status, 'running')
  b.append(bgEnded('bqd8v7a31', 'tu-m', 'completed', 'Monitor "Watch" stream ended'), endTurn('s4', 'It ended.'))
  await b.stop('It ended.', [])
  assert.equal(b.me().status, 'done')

  // Its session's process gone: what it waited for went too.
  const c = waiter('eeeeeeee-0000-4000-8000-000000000920', [...bgBash('tu-bg'), endTurn('s2', 'Waiting for the build.')])
  await c.stop('Waiting for the build.', [shellTask()])
  agents.endSessionAgents('eeeeeeee-0000-4000-8000-000000000920')
  assert.notEqual(c.me().status, 'running')
})

test('work of other kinds: a foreground command moved to the background, agents it started; a prompt only quoting a notification ends nothing', async () => {
  const moved = waiter('eeeeeeee-0000-4000-8000-000000000921', [
    assistant('s1', [toolUse('tu-fg', 'Bash', { command: 'npm test' })], side()),
    result('s2', 'tu-fg', `Command was moved to the background (ID: ${SHELL}) after running for 180s.`, { ...shellResult(SHELL), timedOutAfterMs: 180000, subagentCapApplied: true }, side()),
    endTurn('s3', 'Waiting for the tests.'),
  ])
  await moved.stop('Waiting for the tests.', [shellTask()])
  assert.equal(moved.me().status, 'running')
  assert.equal(moved.me().activity, WAITING)

  // Agents it started: the hook's list doesn't name one that itself waits, so that isn't their end.
  const child = 'a4444444444444444'
  const nested = waiter('eeeeeeee-0000-4000-8000-000000000922', [
    assistant('s1', [toolUse('tu-a', 'Agent', { description: 'Sub', prompt: 'x', subagent_type: 'fork' })], side()),
    result('s2', 'tu-a', 'Async agent launched successfully.', { isAsync: true, status: 'async_launched', agentId: child, description: 'Sub', prompt: 'x', outputFile: '/tmp/x' }, side()),
    endTurn('s3', 'Waiting for my agent.'),
  ])
  await nested.stop('Waiting for my agent.', [])
  assert.equal(nested.me().status, 'running')
  assert.equal(nested.me().activity, 'Waiting for the agents it started')
  // Its child's own "waiting" isn't its end; its "finished" is.
  nested.append({ ...bgEnded(child, 'tu-a'), message: { role: 'user', content: `${NOTICE}${waitingNote(child, 1000)}` } }, endTurn('s4', 'Still waiting.'))
  await nested.stop('Still waiting.', [])
  assert.equal(nested.me().status, 'running')
  nested.append({ ...bgEnded(child, 'tu-a'), message: { role: 'user', content: `${NOTICE}${finishedNote(child, 2000)}` } }, endTurn('s5', 'All done.'))
  await nested.stop('All done.', [])
  assert.equal(nested.me().status, 'done')

  // Several ended at once, in one line.
  const two = waiter('eeeeeeee-0000-4000-8000-000000000923', [...bgBash('tu-1', 'bone'), ...bgBash('tu-2', 'btwo'), endTurn('s2', 'Waiting for both.')])
  await two.stop('Waiting for both.', [shellTask('bone'), shellTask('btwo')])
  assert.equal(two.me().activity, 'Waiting for its 2 background commands')
  two.append({ ...bgEnded('bone', 'tu-1'), message: { role: 'user', content: bgEnded('bone', 'tu-1').message.content + '\n\n' + bgEnded('btwo', 'tu-2').message.content } }, endTurn('s3', 'Both passed.'))
  await two.stop('Both passed.', [])
  assert.equal(two.me().status, 'done')

  // The owner's prompt quotes one (it has no origin and doesn't start with it): the shell still runs.
  const quoted = waiter('eeeeeeee-0000-4000-8000-000000000924', [...bgBash('tu-bg'), prompt('s2', `Look at <task-notification><task-id>${SHELL}</task-id><status>completed</status></task-notification> in the docs.`, side()), endTurn('s3', 'Looked; waiting for the build.')])
  await quoted.stop('Looked; waiting for the build.', [shellTask()])
  assert.equal(quoted.me().status, 'running')
})

test('the main turn\'s Stop leaves a waiting agent be, while its shell is listed; once it is not, the agent ends', async () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000925'
  const w = waiter(id, [...bgBash('tu-bg'), endTurn('s2', 'Waiting for the build.')])
  await w.stop('Waiting for the build.', [shellTask()])
  appendFileSync(w.transcriptPath, line(enqueue(waitingNote(WAITER, 1000))))
  hub.pollTranscripts()
  // Claude Code counts a waiting agent as completed: only its shell is named.
  await handleHook({ session_id: id, cwd: '/tmp/agents', hook_event_name: 'Stop', background_tasks: [shellTask()] }, {} as never, {} as never)
  assert.equal(w.me().status, 'running')
  assert.equal(w.me().activity, WAITING)
  assert.equal(hub.getSession(id)!.agentsRunning, 1)
  // An older Claude Code's Stop, no list: nothing changes.
  await handleHook({ session_id: id, cwd: '/tmp/agents', hook_event_name: 'Stop' }, {} as never, {} as never)
  assert.equal(w.me().status, 'running')
  // Its shell gone and no wake-up: it has no more reason to wait, and no report.
  await handleHook({ session_id: id, cwd: '/tmp/agents', hook_event_name: 'Stop', background_tasks: [] }, {} as never, {} as never)
  assert.equal(w.me().status, 'stopped')
  assert.equal(w.me().result, null)
  assert.equal(hub.getSession(id)!.agentsRunning, 0)
})

test('a daemon restart mid-wait: read afresh it is still waiting; finished or reported, it is not', () => {
  // A session first read now, with everything already written, is what a restart reads.
  const fresh = (id: string, agentLines: object[], main: object[]) => {
    const dir = join(scratch, 'claude', 'projects', '-tmp-agents')
    const sub = join(dir, id, 'subagents')
    mkdirSync(sub, { recursive: true })
    writeMeta(sub, WAITER, { agentType: 'general-purpose', description: 'Build it', toolUseId: 'toolu_w' })
    const file = join(sub, `agent-${WAITER}.jsonl`)
    writeFileSync(file, agentLines.map(line).join(''))
    const transcriptPath = join(dir, `${id}.jsonl`)
    writeFileSync(transcriptPath, [...launchAgent('toolu_w', WAITER, 'Build it', 'general-purpose'), ...main].map(line).join(''))
    const r = hub.upsertSession(id, { cwd: '/tmp/agents', transcriptPath, origin: 'terminal', state: 'working' })
    hub.ensureParsed(r)
    agents.pollAgents()
    return { file, me: () => agents.listAgents(id).agents.find((a) => a.id === WAITER)! }
  }
  const mid = fresh('eeeeeeee-0000-4000-8000-000000000926', [prompt('s1', 'Build it.', side()), ...bgBash('tu-bg'), endTurn('s2', 'Waiting for the build.'), stopHookLine()], [enqueue(waitingNote(WAITER, 1000))])
  assert.equal(mid.me().status, 'running')
  assert.equal(mid.me().activity, WAITING)
  assert.equal(hub.getSession('eeeeeeee-0000-4000-8000-000000000926')!.agentsRunning, 1)

  // No word in the main transcript yet: its file is enough.
  const fileOnly = fresh('eeeeeeee-0000-4000-8000-000000000927', [...bgBash('tu-bg'), endTurn('s2', 'Waiting for the build.')], [])
  assert.equal(fileOnly.me().status, 'running')
  assert.equal(fileOnly.me().activity, WAITING)

  // Woken since the last word: at work, not waiting, its older word doesn't bring it back.
  const awake = fresh('eeeeeeee-0000-4000-8000-000000000928', [...bgBash('tu-bg'), endTurn('s2', 'Waiting for the build.'), bgEnded(SHELL, 'tu-bg'), assistant('s3', [toolUse('tu-x', 'Read', { file_path: '/tmp/x.log' })], side())], [enqueue(waitingNote(WAITER, 1000))])
  assert.equal(awake.me().status, 'running')
  assert.notEqual(awake.me().activity, WAITING)

  // Waited, woke, finished: the waits replayed don't hold it.
  const over = fresh('eeeeeeee-0000-4000-8000-000000000929', [...bgBash('tu-bg'), endTurn('s2', 'Waiting.'), bgEnded(SHELL, 'tu-bg'), handBack('s3', 'All green.')], [enqueue(waitingNote(WAITER, 1000)), enqueue(finishedNote(WAITER, 2000))])
  assert.equal(over.me().status, 'done')
  assert.equal(over.me().result, 'All green.')

  // Reported with a shell left behind, the word still "waiting": done, as soon as it's read.
  const left = fresh('eeeeeeee-0000-4000-8000-000000000930', [...bgBash('tu-bg'), handBack('s2', 'Key screens are covered.')], [enqueue(waitingNote(WAITER, 1000, 'This agent\'s report was delivered to you as a message.'))])
  assert.equal(left.me().status, 'done')
  assert.equal(left.me().result, 'Key screens are covered.')

  // Its file long still: over the cap by the time the daemon looks.
  const id = 'eeeeeeee-0000-4000-8000-000000000931'
  const dir = join(scratch, 'claude', 'projects', '-tmp-agents', id, 'subagents')
  mkdirSync(dir, { recursive: true })
  const stale = join(dir, `agent-${WAITER}.jsonl`)
  writeFileSync(stale, [...bgBash('tu-bg'), endTurn('s2', 'Waiting.')].map(line).join(''))
  const t = new Date(Date.now() - 3 * 3_600_000)
  utimesSync(stale, t, t)
  const gone = fresh(id, [...bgBash('tu-bg'), endTurn('s2', 'Waiting.')], [enqueue(waitingNote(WAITER, 1000))])
  assert.equal(gone.me().status, 'running')
  utimesSync(gone.file, t, t)
  agents.pollAgents()
  assert.equal(gone.me().status, 'stopped')
})

test('a hook from an agent with no meta file is left out, and logged once', async () => {
  const id = 'eeeeeeee-0000-4000-8000-000000000932'
  makeSession(id, [])
  const helper = 'a5555555555555555'
  for (let i = 0; i < 3; i++) await handleHook({ session_id: id, cwd: '/tmp/agents', hook_event_name: 'PreToolUse', agent_id: helper, agent_type: 'claude', tool_name: 'Read', tool_use_id: `h${i}`, tool_input: { file_path: '/tmp/x' } }, {} as never, {} as never)
  assert.equal(agents.listAgents(id).agents.length, 0)
  const lines = readFileSync(join(process.env.HYPERNOVA_LOGS!, 'daemon.log'), 'utf8').split('\n').filter((l) => l.includes('no meta file') && l.includes(helper))
  assert.equal(lines.length, 1)
})
