// Agents and workflows a session's main agent starts (docs/PROTOCOL.md, "Agents and workflows"):
// the main transcript's links and task notifications, the session's subagents folder (meta files,
// agent transcripts, a workflow's journal and run summary), hooks, and the phone's routes. Every
// fixture is written by hand in the shapes Claude Code 2.1.299 records; none is copied from a
// real transcript.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { appendFileSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, utimesSync, writeFileSync } from 'node:fs'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

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
