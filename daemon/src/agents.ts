// Agents a session's main agent runs, and workflows of them: who they are, what each does now and
// how it ended, for the phone (docs/PROTOCOL.md, "Agents and workflows").
//
// Claude Code 2.1.29x writes each agent's turns to a file of its own,
// <projects>/<folder>/<session id>/subagents/agent-<id>.jsonl, with a small agent-<id>.meta.json
// written as it starts. A workflow's agents sit one level down, in subagents/workflows/<run id>/,
// next to a journal.jsonl of who started and who finished, and the run's summary lands in
// <session id>/workflows/<run id>.json once it ends. None of this is documented: every field is
// optional here, and what can't be read leaves an agent running or stopped rather than failing.
//
// Sources, cheapest first: links the main transcript's parser finds anyway (an agent's call and
// its task notification), the PreToolUse hooks subagents already send (what each does now, no
// file read), and a scan of live sessions' folders every 2 s that reads a file only when its
// folder or its size changed, and follows only running agents' own files. A whole agent
// transcript (one can be 50 MB) is read only when the phone opens that agent.
import { closeSync, fstatSync, openSync, readFileSync, readSync, readdirSync, realpathSync, statSync, type Dirent } from 'node:fs'
import { randomUUID } from 'node:crypto'
import { basename, dirname, join } from 'node:path'
import { broadcast, emitEvent, ensureParsed, getSession, listSessionRecs, upsertSession } from './hub.ts'
import { closeTool, feedLine, handbackText, maskSecrets, newParseState, parseTaskNotification, structuredText, toolSummary, waitingWord, type AgentLink, type Ev, type ParseState, type TaskEnd, type WorkflowPhase } from './normalize.ts'
import { log, trimText } from './util.ts'

export type AgentStatus = 'running' | 'done' | 'error' | 'stopped'
export type Agent = {
  id: string
  sessionId: string
  /** The Agent call that started it: in the main transcript, or in [parentAgentId]'s own. */
  toolUseId: string | null
  parentAgentId: string | null
  /** The workflow run it belongs to, and its phase there. */
  workflowId: string | null
  phase: string | null
  /** What it's for ("Review attachment feature code", or a workflow's "survey:agents"), and its kind ("Explore"). */
  label: string
  type: string
  model: string | null
  background: boolean
  status: AgentStatus
  /** What it does now ("Bash: ./gradlew build"), while it runs. */
  activity: string | null
  startedAt: number
  endedAt: number | null
  lastActivityAt: number
  tokens: number | null
  toolUses: number | null
  /** Its report, trimmed. */
  result: string | null
  /** A workflow step's earlier attempt (the step ran again as a new agent): it no longer counts. Only on a broadcast. */
  superseded?: true
}
export type Workflow = {
  /** The run id. */
  id: string
  sessionId: string
  toolUseId: string | null
  taskId: string | null
  name: string
  summary: string | null
  status: AgentStatus
  phases: WorkflowPhase[]
  agentCount: number
  done: number
  failed: number
  startedAt: number
  endedAt: number | null
  tokens: number | null
  toolUses: number | null
}

/** Its last word: a tool call waiting, work after a result, text, its report, or an API error that cut it off (a 429, a usage limit). */
type Last = 'tool' | 'working' | 'text' | 'result' | 'error' | null
/** Background work an agent started: a shell or Monitor ('command'), an agent or workflow of its own ('agent'). */
type BgWork = {
  kind: 'command' | 'agent'
  /** When it started. */
  at: number
  /** When a Monitor runs out by itself (Infinity: a persistent one), 0 for anything else. */
  until: number
}
type AgentRec = Agent & {
  /** Its transcript, once found. */
  file: string | null
  /** How far it has been followed, and a line cut off at the end of the last read. */
  offset: number
  carry: Buffer
  /** Followed from its first line, so its start time and tool count are its own. */
  whole: boolean
  /** Tool calls counted from its file, and from hooks (exact since the daemon has been watching). */
  tools: Set<string>
  hookTools: number
  /** When a hook last said what it does: an older line in its file doesn't overwrite that. */
  hookAt: number
  /** Its last word so far: a tool call waiting, work after a result, text, or its report. */
  last: Last
  /** Its last text, the report when it hands none back. */
  text: string | null
  /** [result] came from its own file (a hand-back, a structured output), the best source. */
  fromFile: boolean
  /** Its file's end has been read for its report once. */
  peeked: boolean
  /** When it was last checked for having stopped writing. */
  checkedAt: number
  /** How it ended came from Claude Code (a notification, the journal, a run summary), not a guess. */
  sure: boolean
  /** A workflow ran its step again as a new agent: this attempt doesn't count. */
  retried: boolean
  /**
   * Its tokens from its file, as Claude Code's own footer counts a running agent's: the context its
   * latest reply read ([inTokens]), plus the output of every reply before it ([outTokens]) and of
   * the latest ([msgOut], reply [msgId]: Claude Code logs a reply once per content block).
   */
  inTokens: number
  outTokens: number
  msgId: string | null
  msgOut: number
  /** Background work it started, from its file, not known to have ended, by task id. */
  bg: Map<string, BgWork>
  /** TaskStop calls waiting for their result: call id → the task. */
  stops: Map<string, string>
  /** Its turn has ended (its last reply's stop_reason, or SubagentStop) and nothing has woken it since. */
  turnOver: boolean
  /** When Claude Code last said it waits on its own background work (0: not since it last wrote). */
  saidWaitingAt: number
  /** ... and said it hasn't reported yet, so its last text isn't a report. */
  unreported: boolean
  /** Its turn ended with background work going, so it's still at work, and its activity says so. */
  waiting: boolean
}
type WorkflowRec = Workflow & {
  journalOffset: number
  journalCarry: string
  /** Its folder has been read at least once. */
  scanned: boolean
  /** Totals Claude Code reported at its end (notification or run summary). */
  reported: { tokens: number | null; toolUses: number | null; agentCount: number | null }
  /** Its journal's step keys → the agent that runs each now (a retry starts a new one). */
  keys: Map<string, string>
  /** When a call last started it again from where it stopped (0: never): a run summary older than that is the attempt before. */
  restartedAt: number
}
type Board = {
  id: string
  agents: Map<string, AgentRec>
  workflows: Map<string, WorkflowRec>
  /** A workflow's task id ("w…") → its run id. */
  tasks: Map<string, string>
  /** Folder mtimes as last read, so a scan reads nothing until one changes. */
  seen: Map<string, number>
  metas: Set<string>
  /** Run summaries read, by run id, with their mtime then: one rewritten later (a run started again ended) is read again. */
  snapshots: Map<string, number>
  /** Agents ("a:<id>") and workflows ("w:<id>") waiting to go out. */
  dirty: Set<string>
  sentAt: number
  timer: NodeJS.Timeout | null
  running: number
  /** Its folders have been read once (history the phone asked for). */
  listed: boolean
  /** Agent and Workflow calls that returned at once, their work going on in the background. */
  launched: Set<string>
  /** Agent calls that came back with an error or were interrupted: the agent each ran stopped with it. */
  failedCalls: Set<string>
}

const boards = new Map<string, Board>()
const AGENT_ID = /^a[0-9a-f]{16}$/
const RUN_ID = /^wf_[0-9a-f-]{1,40}$/
const META = /^agent-(a[0-9a-f]{16})\.meta\.json$/
/** An id from a hook or the SDK, before it names a file: letters, digits, "_" and "-". */
const SAFE_ID = /^[\w-]{1,64}$/

/** A phone may ask for an agent by this id (it names a file). */
export const validAgentId = (id: string) => AGENT_ID.test(id)

/** How long after its last activity a session is still scanned. */
const LIVE_MS = 15 * 60_000
/** A running agent's file idle this long in a live session, or [IDLE_ENDED] in an ended one, is checked for having stopped. */
const IDLE_LIVE = 10 * 60_000
const IDLE_ENDED = 2 * 60_000
/** Even waiting on a tool, an agent silent this long has stopped. */
const IDLE_MAX = 2 * 3_600_000
/** An agent whose last line was an API error, silent this long, was cut off (Claude Code retries before it writes one). */
const IDLE_ERROR = 2 * 60_000
/** While its session is stopped by a limit (or signed out, out of credit), an agent silent this long has stopped with it. */
const IDLE_BLOCKED = 60_000
/**
 * A waiting agent whose file has been still this long ends: nothing tells a shell that hangs for
 * good from a long build, and it counts as at work until then. Its next hook brings it back if it
 * was wrong. Past a Monitor that runs longer (plus [IDLE_LIVE]), up to [WAIT_MAX].
 */
const WAIT_CAP = IDLE_MAX
const WAIT_MAX = 12 * 3_600_000
/** Background work that started this recently is believed even when a list of what's going doesn't name it (the list came first). */
const BG_FRESH = 2000
const WAIT_PREFIX = 'Waiting for '
/** Where following an agent's file starts: its last 256 KB (its activity is at the end). */
const TAIL_START = 256 * 1024
const MAX_AGENTS = 60

/** A line for the log when an agent ends or is left out for a reason that could look wrong later: once each. Not for history read in bulk. */
const logged = new Set<string>()
function logOnce(key: string, ...parts: unknown[]): void {
  if (hush || logged.has(key)) return
  if (logged.size >= 5000) logged.clear()
  logged.add(key)
  log(...parts)
}

/** Text from a transcript, for the phone: keys masked, one size. */
const clean = (s: unknown, max = 600): string | null => (typeof s === 'string' && s.trim() ? trimText(maskSecrets(s.trim()), max) : null)
const numOr = (v: unknown, d: number | null): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : d)

function board(id: string): Board {
  let b = boards.get(id)
  if (!b) {
    b = { id, agents: new Map(), workflows: new Map(), tasks: new Map(), seen: new Map(), metas: new Set(), snapshots: new Map(), dirty: new Set(), sentAt: 0, timer: null, running: 0, listed: false, launched: new Set(), failedCalls: new Set() }
    boards.set(id, b)
  }
  return b
}

function newAgent(b: Board, id: string, at: number): AgentRec {
  let a = b.agents.get(id)
  if (!a) {
    a = {
      id, sessionId: b.id, toolUseId: null, parentAgentId: null, workflowId: null, phase: null, label: '', type: '', model: null, background: true,
      status: 'running', activity: null, startedAt: at, endedAt: null, lastActivityAt: at, tokens: null, toolUses: null, result: null,
      file: null, offset: 0, carry: Buffer.alloc(0), whole: false, tools: new Set(), hookTools: 0, hookAt: 0, last: null, text: null, fromFile: false, peeked: false, checkedAt: 0,
      sure: false, retried: false, inTokens: 0, outTokens: 0, msgId: null, msgOut: 0,
      bg: new Map(), stops: new Map(), turnOver: false, saidWaitingAt: 0, unreported: false, waiting: false,
    }
    b.agents.set(id, a)
  }
  return a
}

function newWorkflow(b: Board, id: string, at: number): WorkflowRec {
  let w = b.workflows.get(id)
  if (!w) {
    w = {
      id, sessionId: b.id, toolUseId: null, taskId: null, name: 'workflow', summary: null, status: 'running', phases: [], agentCount: 0, done: 0, failed: 0,
      startedAt: at, endedAt: null, tokens: null, toolUses: null, journalOffset: 0, journalCarry: '', scanned: false, reported: { tokens: null, toolUses: null, agentCount: null },
      keys: new Map(), restartedAt: 0,
    }
    b.workflows.set(id, w)
  }
  return w
}

// ── Out to the phones ─────────────────────────────────────────────────────
// A status change goes out at once; what an agent does now and its tokens at most once a second
// per session (twenty agents can fire several hooks a second each), all that changed together.

/** Applied without telling phones: history read in bulk (the phone asks for it whole). */
let hush = 0

function touch(b: Board, key: string, urgent: boolean): void {
  if (hush) return
  b.dirty.add(key)
  if (urgent) return flush(b)
  if (b.timer) return
  b.timer = setTimeout(() => flush(b), Math.max(0, b.sentAt + 1000 - Date.now()))
  b.timer.unref?.()
}

function flush(b: Board): void {
  if (b.timer) clearTimeout(b.timer)
  b.timer = null
  b.sentAt = Date.now()
  for (const key of b.dirty) {
    const id = key.slice(2)
    if (key.startsWith('a:')) {
      const a = b.agents.get(id)
      if (a) broadcast({ t: 'agent', agent: agentView(a) })
    } else {
      const w = b.workflows.get(id)
      if (w) broadcast({ t: 'workflow', workflow: workflowView(b, w) })
    }
  }
  b.dirty.clear()
}

/** An agent changed: it goes out, and its workflow too when its status did (the counts). */
function changed(b: Board, a: AgentRec, urgent: boolean): void {
  if (urgent && a.workflowId && b.workflows.has(a.workflowId)) b.dirty.add(`w:${a.workflowId}`)
  touch(b, `a:${a.id}`, urgent)
}

/**
 * How many agents run now: the session says so when it changes. At least one while a workflow
 * runs: between its phases, or before its first agent's meta file is read, none of its agents is
 * at work, and the session is still busy with it (so nothing reads it as done).
 */
function publishRunning(b: Board): void {
  let n = 0
  for (const a of b.agents.values()) if (a.status === 'running') n++
  if (!n) for (const w of b.workflows.values()) if (w.status === 'running') n = 1
  if (n === b.running) return
  b.running = n
  if (getSession(b.id)) upsertSession(b.id, { agentsRunning: n })
}

function agentView(a: AgentRec): Agent {
  return {
    id: a.id, sessionId: a.sessionId, toolUseId: a.toolUseId, parentAgentId: a.parentAgentId, workflowId: a.workflowId, phase: a.phase,
    label: a.label || a.type || 'agent', type: a.type || 'agent', model: a.model, background: a.background, status: a.status,
    activity: a.status === 'running' ? a.activity : null, startedAt: a.startedAt, endedAt: a.endedAt, lastActivityAt: a.lastActivityAt,
    tokens: a.tokens, toolUses: a.toolUses ?? (Math.max(a.tools.size, a.hookTools) || null), result: a.result,
    // Phones that know it drop the attempt; older ones ignore the field.
    ...(a.retried ? { superseded: true as const } : {}),
  }
}

function workflowView(b: Board, w: WorkflowRec): Workflow {
  // A step's earlier attempts (a retry runs as a new agent) aren't counted: Claude Code doesn't either.
  const mine = [...b.agents.values()].filter((a) => a.workflowId === w.id && !a.retried)
  // Phases the script listed, then any its agents name that it didn't.
  const phases = [...w.phases]
  for (const a of mine) if (a.phase && !phases.some((p) => p.title === a.phase)) phases.push({ title: a.phase, detail: null })
  const sum = (f: (a: AgentRec) => number | null) => (mine.some((a) => f(a) != null) ? mine.reduce((n, a) => n + (f(a) ?? 0), 0) : null)
  const { journalOffset: _o, journalCarry: _c, scanned: _s, reported, keys: _k, restartedAt: _r, ...v } = w
  return {
    ...v,
    phases,
    agentCount: Math.max(reported.agentCount ?? 0, mine.length),
    done: mine.filter((a) => a.status === 'done').length,
    failed: mine.filter((a) => a.status === 'error').length,
    tokens: reported.tokens ?? sum((a) => a.tokens),
    toolUses: reported.toolUses ?? sum((a) => agentView(a).toolUses),
  }
}

// ── Ends ──────────────────────────────────────────────────────────────────

/**
 * An agent ended. [known]: Claude Code said so (a notification, the journal, the run summary,
 * the SDK), and that always counts; a guess (its file went quiet) only ends one still running.
 */
function end(b: Board, a: AgentRec, status: AgentStatus, at: number, known: boolean): void {
  if (!known && a.status !== 'running') return
  const was = a.status
  a.status = status
  a.sure = known
  if (was === 'running' || a.endedAt == null) a.endedAt = Math.max(at, a.startedAt)
  a.activity = null
  a.waiting = false
  if (status === 'done' && !a.result && a.text) a.result = clean(a.text)
  closeLauncher(b, a.toolUseId, status, a.endedAt - a.startedAt, a.endedAt)
  changed(b, a, was !== status)
}

const taskStatus = (s: AgentStatus) => (s === 'done' ? 'completed' : s === 'error' ? 'failed' : 'stopped')

// ── Waiting on its own background work ────────────────────────────────────
// A subagent that starts a background command and waits for it ends its turn: SubagentStop fires,
// and Claude Code marks it "completed" with the command keeping it alive, then wakes it when the
// command ends. It hasn't finished, so it stays running here, with an activity that says why.

/** Its turn is over, it hasn't reported and its background work goes on. (A foreground agent's call ends with its turn.) */
const waits = (a: AgentRec) => a.status === 'running' && a.background && a.turnOver && a.last !== 'result' && a.last !== 'error' && (a.bg.size > 0 || a.saidWaitingAt > 0)

function waitLine(a: AgentRec): string {
  const all = [...a.bg.values()]
  const commands = all.filter((w) => w.kind === 'command').length
  if (!all.length || (commands && commands < all.length)) return `${WAIT_PREFIX}its background work`
  if (commands) return commands === 1 ? `${WAIT_PREFIX}its background command` : `${WAIT_PREFIX}its ${commands} background commands`
  return `${WAIT_PREFIX}the agents it started`
}

/** Its file read up to now, found first if need be. */
function catchUp(b: Board, a: AgentRec): void {
  const r = getSession(b.id)
  if (!a.file && r) a.file = findFile(subagentsDir(r), a)
  follow(a)
}

/** Whether it waits now, and its activity with it. */
function syncWait(a: AgentRec): void {
  if (waits(a)) {
    a.waiting = true
    a.activity = waitLine(a)
  } else if (a.waiting) {
    a.waiting = false
    if (a.activity?.startsWith(WAIT_PREFIX)) a.activity = null
  }
}

/** Something woke it: whatever was said about its wait is over. */
function woke(a: AgentRec): void {
  a.turnOver = false
  a.saidWaitingAt = 0
  a.unreported = false
}

/**
 * [tasks] is a Stop or SubagentStop hook's background_tasks: what's in flight in the whole session.
 * Shells of [a]'s it doesn't name have ended, whatever its file hasn't said. Agents are left alone:
 * one that waits itself counts as completed there, so it isn't named.
 */
function dropEnded(a: AgentRec, tasks: unknown, now: number): void {
  if (!Array.isArray(tasks) || !a.bg.size) return
  const live = new Set<string>()
  for (const t of tasks) if (t && typeof t === 'object' && typeof t.id === 'string') live.add(t.id)
  let dropped = false
  for (const [id, w] of a.bg) {
    if (w.kind !== 'command' || live.has(id) || now - w.at < BG_FRESH) continue
    a.bg.delete(id)
    dropped = true
  }
  if (dropped && !a.bg.size) a.saidWaitingAt = 0
}

/** How an agent ends when nothing says: done with its report or last words, unless Claude Code said it hadn't reported. */
const quietEnd = (a: AgentRec): AgentStatus => (a.last === 'result' || (a.last === 'text' && !a.unreported) ? 'done' : 'stopped')

/** The longest a waiting agent's file may stay still, given when it was last written. */
function waitCap(a: AgentRec, lastWrite: number): number {
  let cap = WAIT_CAP
  for (const w of a.bg.values()) if (w.until > lastWrite) cap = Math.max(cap, Math.min(w.until - lastWrite + IDLE_LIVE, WAIT_MAX))
  return cap
}

/**
 * A "completed" notification that isn't its end: the agent waits on its own background work. Its
 * file decides first (a report handed back is its end, whatever goes on behind it); the
 * notification's words only say what a file can't, such as work started before the part followed.
 * Whether it goes on. History read in bulk reads no file: its scan settles it.
 */
function goesOn(b: Board, a: AgentRec, e: TaskEnd, ts: number): boolean {
  if (e.waiting === false) {
    a.bg.clear()
    woke(a)
    return false
  }
  if (!hush) catchUp(b, a)
  if (a.last === 'result') return false
  if (e.waiting === null && (hush || !waits(a))) return false
  if (a.status !== 'running') {
    // Stopped is stopped; one taken as done before this word came goes on.
    if (a.status !== 'done') return true
    const { last } = a
    resume(b, a, ts)
    a.last = last
  }
  a.saidWaitingAt = Math.max(a.saidWaitingAt, ts)
  a.unreported = /has not reported yet/.test(e.result ?? '')
  a.turnOver = true
  syncWait(a)
  changed(b, a, false)
  return true
}

/** The call that launched an agent or workflow stops running with it (it was left running at launch). */
function closeLauncher(b: Board, toolUseId: string | null, status: AgentStatus, durationMs: number | null, at: number): void {
  if (!toolUseId || status === 'running') return
  const ev = getSession(b.id)?.parse?.byId.get(toolUseId)
  if (ev?.kind === 'tool' && closeTool(ev, { status: taskStatus(status), summary: null, durationMs }, at) && !hush) emitEvent(b.id, ev.id)
}

/** A status as Claude Code words it (notifications, the journal, run summaries, background tasks); null while it runs. */
function statusOf(s: unknown): AgentStatus | null | undefined {
  switch (s) {
    case 'running':
    case 'pending':
    case 'queued':
    case 'paused':
      return null
    case 'completed':
    case 'done':
    case 'success':
      return 'done'
    case 'failed':
    case 'error':
      return 'error'
    case 'killed':
    case 'stopped':
    case 'cancelled':
    case 'canceled':
    case 'skipped':
      return 'stopped'
    default:
      return undefined
  }
}

function endWorkflow(b: Board, w: WorkflowRec, status: AgentStatus, at: number): void {
  const was = w.status
  w.status = status
  w.endedAt ??= at
  closeLauncher(b, w.toolUseId, status, w.endedAt - w.startedAt, w.endedAt)
  // Its agents ended with it, whatever the journal hasn't said yet (it says so if differently).
  for (const a of b.agents.values()) if (a.workflowId === w.id && a.status === 'running') end(b, a, status === 'done' ? 'done' : 'stopped', at, false)
  touch(b, `w:${w.id}`, was !== status)
}

function taskEnd(b: Board, e: TaskEnd, ts: number): void {
  const status = statusOf(e.status) ?? 'stopped'
  if (e.taskId.startsWith('w')) {
    const runId = b.tasks.get(e.taskId) ?? [...b.workflows.values()].find((w) => e.toolUseId && w.toolUseId === e.toolUseId)?.id
    const w = runId ? b.workflows.get(runId) : undefined
    if (!w) return
    // A call the run was started again from (resumeFromRunId) ending late only ends that call.
    if (e.toolUseId && w.toolUseId && e.toolUseId !== w.toolUseId) {
      closeLauncher(b, e.toolUseId, status, e.durationMs ?? null, ts)
      return
    }
    w.taskId ??= e.taskId
    w.reported.tokens = e.tokens ?? w.reported.tokens
    w.reported.toolUses = e.toolUses ?? w.reported.toolUses
    w.reported.agentCount = e.agentCount ?? w.reported.agentCount
    if (e.durationMs != null) w.endedAt = w.startedAt + e.durationMs
    endWorkflow(b, w, status, ts)
    return
  }
  if (!AGENT_ID.test(e.taskId)) return
  const a = newAgent(b, e.taskId, e.durationMs != null ? ts - e.durationMs : ts)
  a.toolUseId ??= e.toolUseId
  if (status === 'done' && goesOn(b, a, e, ts)) return
  a.tokens = e.tokens ?? a.tokens
  a.toolUses = e.toolUses ?? a.toolUses
  // Its own file says best what it found: a hand-back's <result> only says one was sent. (Not for
  // history read in bulk: the phone's list reads the ends it shows.)
  if (status === 'done' && !hush) peek(b, a)
  if (status === 'done' && !a.fromFile) a.result = clean(a.text) ?? clean(e.result) ?? clean(e.summary) ?? a.result
  if (status !== 'done') a.result = clean(e.summary) ?? a.result
  end(b, a, status, e.durationMs != null ? a.startedAt + e.durationMs : ts, true)
}

/** The end of an agent's file read once for its report, when it wasn't followed to its end. */
function peek(b: Board, a: AgentRec): void {
  if (a.fromFile || a.peeked) return
  a.peeked = true
  const r = getSession(b.id)
  const file = a.file ?? (r ? findFile(subagentsDir(r), a) : null)
  const size = file ? sizeOf(file) : null
  if (!file || size == null) return
  a.file = file
  readLines(a, file, Math.max(0, size - 64 * 1024), 64 * 1024, size > 64 * 1024, false)
}

// ── Links from the main transcript ────────────────────────────────────────

/** What the main transcript's parser linked (hub.ts drains it after each read). [quiet]: history read in bulk. */
export function applyLinks(sessionId: string, links: AgentLink[], quiet = false): void {
  if (!links.length) return
  const b = board(sessionId)
  if (quiet) hush++
  try {
    for (const l of links) {
      switch (l.kind) {
        case 'agent': {
          if (!AGENT_ID.test(l.agentId)) break
          const fresh = !b.agents.has(l.agentId)
          const a = newAgent(b, l.agentId, l.ts)
          a.toolUseId ??= l.toolUseId
          a.label ||= clean(l.label, 120) ?? ''
          a.type ||= l.type ?? ''
          a.model ??= l.model
          a.background = l.background
          if (l.background && !l.report) b.launched.add(l.toolUseId)
          if (l.ts < a.startedAt) a.startedAt = l.ts
          const r = l.report
          if (r) {
            a.tokens = r.tokens ?? a.tokens
            a.toolUses = r.toolUses ?? a.toolUses
            a.result = clean(r.result) ?? a.result
            end(b, a, 'done', r.durationMs != null ? a.startedAt + r.durationMs : l.ts, true)
          } else changed(b, a, fresh)
          break
        }
        case 'workflow': {
          const id = l.runId && RUN_ID.test(l.runId) ? l.runId : null
          if (!id) break
          const fresh = !b.workflows.has(id)
          const w = newWorkflow(b, id, l.ts)
          // Only a newer call starts it again: a transcript read afresh replays the older ones too.
          if (!fresh && w.toolUseId && w.toolUseId !== l.toolUseId && l.ts >= w.restartedAt) restart(b, w, l.toolUseId, l.ts)
          w.toolUseId ??= l.toolUseId
          b.launched.add(l.toolUseId)
          if (l.taskId) {
            w.taskId ??= l.taskId
            b.tasks.set(l.taskId, id)
          }
          if (l.name) w.name = clean(l.name, 80) ?? w.name
          w.summary ??= clean(l.summary, 300)
          if (!w.phases.length && l.phases.length) w.phases = l.phases
          if (l.ts < w.startedAt && l.ts >= w.restartedAt) w.startedAt = l.ts
          touch(b, `w:${id}`, fresh)
          break
        }
        case 'resume': {
          const a = b.agents.get(l.agentId)
          if (a && a.status !== 'running') resume(b, a, l.ts)
          // One waiting on its background work is awake now too.
          else if (a) woke(a)
          break
        }
        case 'stop': {
          const runId = b.tasks.get(l.taskId)
          const w = runId ? b.workflows.get(runId) : undefined
          if (w && w.status === 'running') endWorkflow(b, w, 'stopped', l.ts)
          // TaskStop on a background agent: no notification has to follow.
          const a = AGENT_ID.test(l.taskId) ? b.agents.get(l.taskId) : undefined
          if (a && a.status === 'running') end(b, a, 'stopped', l.ts, true)
          break
        }
        case 'end':
          taskEnd(b, l, l.ts)
          break
        case 'failed': {
          // An Agent call came back with an error or was interrupted (Esc): the agent it ran in
          // the foreground stopped with it, and no notification will say so. One whose meta file
          // hasn't been read yet stops as it's read (readMeta).
          b.failedCalls.add(l.toolUseId)
          for (const a of b.agents.values()) if (a.toolUseId === l.toolUseId && a.status === 'running') end(b, a, 'stopped', l.ts, true)
          break
        }
      }
    }
  } finally {
    if (quiet) hush--
  }
  publishRunning(b)
}

/**
 * A run started again from where it stopped: a Workflow call with resumeFromRunId keeps its run id.
 * It runs under the new call, which gets its card (the old call stops, if it hadn't: Claude Code
 * quit while nobody watched), with the totals Claude Code reports for it next. Its folder is read
 * afresh for the agents it starts now, and its journal goes on from where it was read: a step that
 * runs again there is a retry, so its earlier attempt no longer counts (tailJournal).
 */
function restart(b: Board, w: WorkflowRec, toolUseId: string, at: number): void {
  closeLauncher(b, w.toolUseId, 'stopped', null, at)
  w.toolUseId = toolUseId
  // The old task's late notification or Stop hook must not end the run it now belongs to.
  if (w.taskId) b.tasks.delete(w.taskId)
  w.taskId = null
  w.startedAt = at
  w.status = 'running'
  w.endedAt = null
  w.reported = { tokens: null, toolUses: null, agentCount: null }
  w.restartedAt = Math.max(w.restartedAt, at)
  const r = getSession(b.id)
  if (r) b.seen.delete(join(subagentsDir(r), 'workflows', w.id))
  touch(b, `w:${w.id}`, true)
}

/** A finished agent works again (SendMessage woke it), in the same file. */
function resume(b: Board, a: AgentRec, at: number): void {
  a.status = 'running'
  a.endedAt = null
  a.sure = false
  a.lastActivityAt = Math.max(a.lastActivityAt, at)
  a.checkedAt = 0
  a.last = null
  // What it said before isn't its report any more: a hand-back of its own still is, until the next.
  if (!a.fromFile) a.result = null
  woke(a)
  changed(b, a, true)
}

// ── Hooks ─────────────────────────────────────────────────────────────────

/**
 * A subagent's PreToolUse: what it does now. No file is read for an agent the board knows. One it
 * doesn't know counts only once its meta file is found (read now, for its label): Claude Code's own
 * helpers (memory upkeep, summaries, prompt suggestions: forks of the main conversation) send these
 * hooks too, under an id that names no file, and they aren't agents the main agent started (a
 * `claude agents` job's show its main agent's type, "claude"). Agent calls and workflows write the
 * meta file as they start; a hook that comes first is counted from the next one, or the next scan.
 */
export function hookActivity(sessionId: string, agentId: string, agentType: unknown, tool: string, summary: string): void {
  if (!SAFE_ID.test(agentId)) return
  const b = board(sessionId)
  if (!b.agents.has(agentId) && !metaRead(b, agentId)) {
    logOnce(`hook:${sessionId}/${agentId}`, 'agent hook not counted: no meta file for', agentId, 'in', sessionId, '(a helper of Claude Code, or its file is still to come)')
    return
  }
  const now = Date.now()
  const fresh = !b.agents.has(agentId)
  const a = newAgent(b, agentId, now)
  if (!a.type && typeof agentType === 'string') a.type = agentType
  if (a.status !== 'running') resume(b, a, now)
  woke(a)
  a.waiting = false
  a.activity = clean(summary && summary !== tool ? `${tool}: ${summary}` : tool, 200)
  a.hookAt = now
  a.lastActivityAt = now
  a.hookTools++
  a.last = 'tool'
  changed(b, a, fresh)
  publishRunning(b)
}

/**
 * A subagent's SubagentStop: its turn ended. It has finished (its last message is its report), unless
 * its file has background work still going with no report handed back ([tasks], the hook's
 * background_tasks, says which shells really still run): then it waits, and stays running. One that
 * already ended (a TaskStop, a notification) keeps how it ended; a notification later still says best.
 */
export function hookSubagentStop(sessionId: string, agentId: string, last: unknown, tasks?: unknown): void {
  if (!SAFE_ID.test(agentId)) return
  const b = boards.get(sessionId)
  const a = b?.agents.get(agentId)
  if (!b || !a || a.status !== 'running') return
  // What it wrote up to here first: the command it started, or the report it handed back.
  catchUp(b, a)
  if (typeof last === 'string' && last.trim()) a.text = last
  if (a.last !== 'result') a.last = 'text'
  a.turnOver = true
  const now = Date.now()
  dropEnded(a, tasks, now)
  syncWait(a)
  if (a.waiting) changed(b, a, false)
  else end(b, a, 'done', now, true)
  publishRunning(b)
}

/** A subagent's tool finished: it's at work again, not waiting. */
export function hookToolDone(sessionId: string, agentId: string): void {
  const a = boards.get(sessionId)?.agents.get(agentId)
  if (!a) return
  a.lastActivityAt = Date.now()
  a.hookAt = a.lastActivityAt
  if (a.last === 'tool') a.last = 'working'
}

/**
 * The main agent's Agent or Workflow call returned ([response] is its result): one that went on in
 * the background is known as such before the transcript says so, so a prompt typed right after it
 * isn't taken as queued behind it.
 */
export function hookLaunched(sessionId: string, toolUseId: string, response: any): void {
  if (response?.status === 'async_launched' || response?.status === 'remote_launched') board(sessionId).launched.add(toolUseId)
}

/**
 * Whether call [toolUseId] started an agent or workflow that went on in the background: it stays
 * running as long as they do, while the turn that made it moves on. A foreground agent's call has
 * the turn wait for it.
 */
export const launchedInBackground = (sessionId: string, toolUseId: string) => boards.get(sessionId)?.launched.has(toolUseId) ?? false

/**
 * The main turn's Stop lists the background tasks still going ([{id, type, status, description,
 * agent_type|name}]): agents it names that we hadn't seen run, and ends it states, count. A
 * background agent of the main agent's still running here that it doesn't name has ended (a
 * TaskStop, a 429 or a limit that cut it off, a notification never written): done when its file's
 * last word is its report, else stopped. Not a workflow's agents, nor one an agent started (the
 * list names the main agent's tasks), nor one that started a moment ago, nor one waiting on its own
 * background work (Claude Code counts that one as completed, so the list doesn't name it; the
 * shells it waits for are named, and those no longer named have ended).
 */
export function hookStop(sessionId: string, tasks: unknown): void {
  if (!Array.isArray(tasks)) return
  const b = board(sessionId)
  const now = Date.now()
  const named = new Set<string>()
  for (const t of tasks) if (t && typeof t === 'object' && typeof t.id === 'string') named.add(t.id)
  for (const a of b.agents.values()) {
    if (a.status !== 'running') continue
    catchUp(b, a)
    dropEnded(a, tasks, now)
    syncWait(a)
    if (a.waiting) changed(b, a, false)
    if (a.waiting || named.has(a.id) || !a.background || a.workflowId || a.parentAgentId || now - a.startedAt < 2000) continue
    logOnce(`stop:${sessionId}/${a.id}`, 'agent ended: the main turn\'s Stop no longer lists', a.id, 'in', sessionId)
    end(b, a, quietEnd(a), mtimeOf(a.file) ?? a.lastActivityAt, false)
  }
  for (const t of tasks) {
    if (!t || typeof t !== 'object' || typeof t.id !== 'string') continue
    const status = statusOf(t.status)
    if (status === undefined) continue
    if (t.type === 'subagent' || t.type === 'local_agent') {
      if (!AGENT_ID.test(t.id)) continue
      const known = b.agents.get(t.id)
      if (!known && status) continue
      const a = known ?? newAgent(b, t.id, now)
      a.label ||= clean(t.description, 120) ?? ''
      a.type ||= typeof t.agent_type === 'string' ? t.agent_type : ''
      if (status) end(b, a, status, now, true)
      else if (a.status !== 'running') resume(b, a, now)
      else if (!known) changed(b, a, true)
    } else if (t.type === 'workflow' || t.type === 'local_workflow') {
      const runId = b.tasks.get(t.id)
      const w = runId ? b.workflows.get(runId) : undefined
      if (w && status && w.status === 'running') endWorkflow(b, w, status, now)
    }
  }
  publishRunning(b)
}

// ── Agent SDK sessions ────────────────────────────────────────────────────

/** The SDK's task_started, task_progress, task_updated and task_notification messages (runner.ts). */
export function sdkTask(sessionId: string, m: any): void {
  const id = m?.task_id
  if (typeof id !== 'string' || !SAFE_ID.test(id)) return
  const b = board(sessionId)
  const now = Date.now()
  switch (m.subtype) {
    case 'task_started': {
      // Workflows are linked by their call's result (it names the run); shells are Bash cards.
      if (m.task_type && m.task_type !== 'local_agent') break
      const fresh = !b.agents.has(id)
      const a = newAgent(b, id, now)
      a.toolUseId ??= typeof m.tool_use_id === 'string' ? m.tool_use_id : null
      a.label ||= clean(m.description, 120) ?? ''
      a.type ||= typeof m.subagent_type === 'string' ? m.subagent_type : ''
      a.background = m.is_backgrounded !== false
      if (typeof m.parent_task_id === 'string' && b.agents.has(m.parent_task_id)) a.parentAgentId ??= m.parent_task_id
      if (a.status !== 'running') resume(b, a, now)
      else changed(b, a, fresh)
      break
    }
    case 'task_progress': {
      const a = b.agents.get(id)
      if (!a) break
      a.tokens = numOr(m.usage?.total_tokens, a.tokens)
      a.toolUses = numOr(m.usage?.tool_uses, a.toolUses)
      a.lastActivityAt = now
      const said = clean(m.summary, 200) ?? (typeof m.last_tool_name === 'string' ? m.last_tool_name : null)
      if (said && now - a.hookAt > 1500) a.activity = said
      changed(b, a, false)
      break
    }
    case 'task_updated': {
      const status = statusOf(m.patch?.status)
      if (!status) break
      const at = numOr(m.patch?.end_time, now)!
      const a = b.agents.get(id)
      if (a) {
        // Marked completed with its background work still going: its notification says so next.
        if (status === 'done') {
          catchUp(b, a)
          syncWait(a)
        }
        if (status !== 'done' || !a.waiting) end(b, a, status, at, true)
      }
      const runId = b.tasks.get(id)
      const w = runId ? b.workflows.get(runId) : undefined
      if (w) endWorkflow(b, w, status, at)
      break
    }
    case 'task_notification':
      taskEnd(b, {
        taskId: id, toolUseId: typeof m.tool_use_id === 'string' ? m.tool_use_id : null, status: m.status, summary: typeof m.summary === 'string' ? m.summary : null, result: null,
        tokens: numOr(m.usage?.total_tokens, null), toolUses: numOr(m.usage?.tool_uses, null), durationMs: numOr(m.usage?.duration_ms, null), agentCount: null, agentsError: null, waiting: waitingWord(typeof m.summary === 'string' ? m.summary : null, null),
      }, now)
      break
  }
  publishRunning(b)
}

// ── The session's folders ─────────────────────────────────────────────────

type Rec = NonNullable<ReturnType<typeof getSession>>

/**
 * Where a session's agents write: a folder named as its transcript is (`<session id>.jsonl` →
 * `<session id>/subagents`, as Claude Code makes both), next to it.
 */
function subagentsDir(r: Rec): string {
  const stem = r.transcriptPath.endsWith('.jsonl') ? basename(r.transcriptPath, '.jsonl') : r.id
  return join(dirname(r.transcriptPath), stem || r.id, 'subagents')
}

function ls(dir: string): string[] {
  try {
    return readdirSync(dir)
  } catch {
    return []
  }
}

function mtimeOf(p: string | null): number | null {
  if (!p) return null
  try {
    return statSync(p).mtimeMs
  } catch {
    return null
  }
}

/** Whether a folder changed since it was last read (entries added); it counts as read now. */
function folderChanged(b: Board, dir: string): boolean {
  const m = mtimeOf(dir)
  if (m == null || b.seen.get(dir) === m) return false
  b.seen.set(dir, m)
  return true
}

/** An agent's meta file, read once: who it is ({agentType, description, toolUseId, parentAgentId, workflowPhase, model}). */
function readMeta(b: Board, path: string, id: string, runId: string | null, dir: string): void {
  if (b.metas.has(path)) return
  let o: any
  try {
    o = JSON.parse(readFileSync(path, 'utf8'))
  } catch {
    // Being written: read the folder again next time.
    b.seen.delete(dir)
    return
  }
  b.metas.add(path)
  const born = mtimeOf(path) ?? Date.now()
  const fresh = !b.agents.has(id)
  const a = newAgent(b, id, born)
  if (born < a.startedAt) a.startedAt = born
  a.file = path.replace(/\.meta\.json$/, '.jsonl')
  a.label ||= clean(o?.description, 120) ?? ''
  if (typeof o?.agentType === 'string' && o.agentType) a.type = o.agentType
  if (typeof o?.toolUseId === 'string') a.toolUseId ??= o.toolUseId
  if (typeof o?.parentAgentId === 'string' && SAFE_ID.test(o.parentAgentId)) a.parentAgentId ??= o.parentAgentId
  if (typeof o?.workflowPhase === 'string') a.phase ??= o.workflowPhase
  if (typeof o?.model === 'string' && o.model !== 'inherit') a.model ??= o.model
  if (runId) a.workflowId = runId
  // Its call already came back with an error or was interrupted: it stopped then.
  if (a.toolUseId && b.failedCalls.has(a.toolUseId) && a.status === 'running') return end(b, a, 'stopped', mtimeOf(a.file) ?? born, true)
  changed(b, a, fresh)
}

/** A workflow's journal, followed: started (label, phase), result, failed. It has no times of its own. */
function tailJournal(b: Board, w: WorkflowRec, file: string): void {
  let fd: number | null = null
  try {
    fd = openSync(file, 'r')
    const size = fstatSync(fd).size
    if (size < w.journalOffset) {
      w.journalOffset = 0
      w.journalCarry = ''
    }
    if (size === w.journalOffset) return
    const buf = Buffer.alloc(Math.min(size - w.journalOffset, 4 * 1024 * 1024))
    const n = readSync(fd, buf, 0, buf.length, w.journalOffset)
    w.journalOffset += n
    const text = w.journalCarry + buf.subarray(0, n).toString('utf8')
    const nl = text.lastIndexOf('\n')
    w.journalCarry = nl >= 0 ? text.slice(nl + 1) : text
    const at = mtimeOf(file) ?? Date.now()
    for (const line of nl >= 0 ? text.slice(0, nl).split('\n') : []) {
      let o: any
      try {
        o = JSON.parse(line)
      } catch {
        continue
      }
      const id = typeof o?.agentId === 'string' && AGENT_ID.test(o.agentId) ? o.agentId : null
      if (!id) continue
      const fresh = !b.agents.has(id)
      const a = newAgent(b, id, at)
      a.workflowId = w.id
      if (typeof o.phase === 'string') a.phase ??= o.phase
      if (typeof o.label === 'string' && o.label) a.label = clean(o.label, 120) ?? a.label
      a.type ||= 'workflow-subagent'
      if (o.type === 'started') {
        // The same step started again: a retry, as a new agent. The attempt before it ended with no
        // result (whatever its last words looked like) and no longer counts.
        const key = typeof o.key === 'string' ? o.key : null
        const prev = key ? w.keys.get(key) : undefined
        if (key) w.keys.set(key, id)
        const p = prev && prev !== id ? b.agents.get(prev) : undefined
        if (p && !p.retried) {
          p.retried = true
          const was = p.status
          if (p.status === 'running' || !p.sure) end(b, p, 'stopped', at, true)
          // Out at once even when its status stays (it had stopped already): phones drop it now.
          if (p.status === was) changed(b, p, true)
        }
        changed(b, a, fresh)
      } else if (o.type === 'result' || o.type === 'failed') {
        if (o.type === 'result') a.result = clean(structuredText(o.result)) ?? a.result
        else a.result = clean(structuredText(o.error ?? o.reason)) ?? a.result
        end(b, a, o.type === 'result' ? 'done' : 'error', mtimeOf(a.file) ?? at, true)
      }
    }
  } catch {
    // Not there yet.
  } finally {
    if (fd != null) closeSync(fd)
  }
}

/**
 * A run's summary, written once it ends: the final word on it and each of its agents. Whether it
 * read. [mtime]: the file's, when it was found.
 */
function readSnapshot(b: Board, file: string, runId: string, mtime: number): boolean {
  let o: any
  try {
    o = JSON.parse(readFileSync(file, 'utf8'))
  } catch {
    return false
  }
  b.snapshots.set(runId, mtime)
  // Written before a call started the run again (read only now: the daemon restarted meanwhile):
  // that was the attempt before, and this one goes on.
  if (mtime < (b.workflows.get(runId)?.restartedAt ?? 0)) return true
  const start = numOr(o?.startTime, null)
  const w = newWorkflow(b, runId, start ?? mtimeOf(file) ?? Date.now())
  if (start != null) w.startedAt = start
  if (typeof o.workflowName === 'string' && o.workflowName) w.name = clean(o.workflowName, 80) ?? w.name
  w.summary = clean(o.summary, 300) ?? w.summary
  if (typeof o.taskId === 'string') {
    w.taskId ??= o.taskId
    b.tasks.set(o.taskId, runId)
  }
  if (Array.isArray(o.phases) && o.phases.length) {
    w.phases = o.phases.flatMap((p: any) => (typeof p?.title === 'string' && p.title ? [{ title: p.title.slice(0, 60), detail: typeof p.detail === 'string' && p.detail ? p.detail.slice(0, 200) : null }] : []))
  }
  w.reported = { tokens: numOr(o.totalTokens, w.reported.tokens), toolUses: numOr(o.totalToolCalls, w.reported.toolUses), agentCount: numOr(o.agentCount, w.reported.agentCount) }
  const dur = numOr(o.durationMs, null)
  if (dur != null) w.endedAt = w.startedAt + dur
  for (const p of Array.isArray(o.workflowProgress) ? o.workflowProgress : []) {
    if (p?.type !== 'workflow_agent' || typeof p.agentId !== 'string' || !AGENT_ID.test(p.agentId)) continue
    const a = newAgent(b, p.agentId, numOr(p.startedAt, w.startedAt)!)
    a.workflowId = runId
    if (typeof p.phaseTitle === 'string') a.phase ??= p.phaseTitle
    a.label ||= clean(p.label, 120) ?? ''
    a.type ||= 'workflow-subagent'
    if (typeof p.model === 'string') a.model ??= p.model
    const s = numOr(p.startedAt, null)
    if (s != null) a.startedAt = s
    a.tokens = numOr(p.tokens, a.tokens)
    a.toolUses = numOr(p.toolCalls, a.toolUses)
    a.result ??= clean(p.resultPreview)
    const status = statusOf(p.state)
    const d = numOr(p.durationMs, null)
    if (status) end(b, a, status, d != null ? a.startedAt + d : (w.endedAt ?? Date.now()), true)
  }
  // Written as a run ends; one that still says it runs (a restore) isn't over.
  const status = statusOf(o.status)
  if (status !== null) endWorkflow(b, w, status ?? 'stopped', w.endedAt ?? Date.now())
  else touch(b, `w:${runId}`, false)
  return true
}

// ── Following a running agent's own file ──────────────────────────────────

/**
 * A reply's usage, counted into the agent's tokens the way Claude Code 2.1.31x counts a running
 * agent's for its footer: the context the latest reply read (input, cache writes and cache reads)
 * plus what every reply wrote (output), each reply once. A reply is logged once per content block,
 * so a line of the reply already counted only updates its output. Followed from its last 256 KB,
 * an agent's earlier replies' output isn't in it; Claude Code's own total replaces it at its end.
 */
function countUsage(a: AgentRec, msgId: string | null, u: any): void {
  if (!u || typeof u !== 'object') return
  const n = (v: unknown) => (typeof v === 'number' && v > 0 ? v : 0)
  const input = n(u.input_tokens) + n(u.cache_creation_input_tokens) + n(u.cache_read_input_tokens)
  const output = n(u.output_tokens)
  if (input + output === 0) return
  if (msgId == null || msgId !== a.msgId) {
    a.outTokens += a.msgOut
    a.msgId = msgId
    a.msgOut = output
  } else a.msgOut = Math.max(a.msgOut, output)
  if (input > 0) a.inTokens = input
  a.tokens = a.inTokens + a.outTokens + a.msgOut
}

/** The background work a tool's result ([r], its toolUseResult) started, as Claude Code 2.1.316 logs it. */
function bgStarted(r: any, at: number): [string, BgWork] | null {
  if (!r || typeof r !== 'object') return null
  let id: unknown
  let work: BgWork
  if (r.backgroundTaskId) {
    // run_in_background, or a foreground command moved there by its timeout: only the result says.
    id = r.backgroundTaskId
    work = { kind: 'command', at, until: 0 }
  } else if (typeof r.timeoutMs === 'number' || typeof r.persistent === 'boolean') {
    // Monitor.
    id = r.taskId
    work = { kind: 'command', at, until: r.persistent === true ? Infinity : at + (typeof r.timeoutMs === 'number' ? r.timeoutMs : 0) }
  } else if (r.status === 'async_launched' || r.status === 'remote_launched') {
    id = r.agentId ?? r.taskId
    work = { kind: 'agent', at, until: 0 }
  } else return null
  return typeof id === 'string' && SAFE_ID.test(id) ? [id, work] : null
}

/**
 * A line's task notifications, when Claude Code put them there: an entry it marks as one (with a
 * notice before them, several at once when they came together) or a queued command. Not a prompt
 * that only quotes one.
 */
function notifications(o: any): TaskEnd[] {
  let text: unknown
  if (o?.type === 'attachment') {
    if (o.attachment?.type === 'queued_command' && o.attachment.commandMode === 'task-notification') text = o.attachment.prompt
  } else if (o?.type === 'user') {
    const c = o.message?.content
    const s = typeof c === 'string' ? c : Array.isArray(c) ? c.map((x: any) => (x?.type === 'text' && typeof x.text === 'string' ? x.text : '')).join('') : ''
    if (o.origin?.kind === 'task-notification' || s.trimStart().startsWith('<task-notification>')) text = s
  }
  if (Array.isArray(text)) text = text.map((x: any) => (typeof x?.text === 'string' ? x.text : '')).join('')
  if (typeof text !== 'string') return []
  const out: TaskEnd[] = []
  for (const m of text.matchAll(/<task-notification>[\s\S]*?<\/task-notification>/g)) {
    const e = parseTaskNotification(m[0])
    if (e) out.push(e)
  }
  return out
}

/** One line of an agent's transcript, read lightly: what it does, its tokens, its report. */
function lightLine(a: AgentRec, o: any): void {
  const ts = typeof o?.timestamp === 'string' ? Date.parse(o.timestamp) : NaN
  if (Number.isFinite(ts)) {
    a.lastActivityAt = Math.max(a.lastActivityAt, ts)
    if (a.whole && ts < a.startedAt) a.startedAt = ts
  }
  // Written after Claude Code said it waits (and not an older line, read late): it has woken.
  const later = !(ts <= a.saidWaitingAt)
  if (o?.type === 'assistant' && later) {
    a.saidWaitingAt = 0
    a.unreported = false
  }
  // Claude Code's API error (a 429, the usage limit, an overloaded API): the agent was cut off
  // there, unless a line after it says otherwise.
  if (o?.type === 'assistant' && o.isApiErrorMessage) {
    a.last = 'error'
    a.activity = null
    return
  }
  if (o?.type === 'assistant') {
    const m = o.message
    a.turnOver = m?.stop_reason === 'end_turn'
    // An unmetered reply (Claude Code marks it so) isn't counted, as in its footer.
    if (o.isUnmetered !== true) countUsage(a, typeof m?.id === 'string' ? m.id : null, m?.usage)
    if (typeof m?.model === 'string' && m.model !== '<synthetic>') a.model ??= m.model
    for (const blk of Array.isArray(m?.content) ? m.content : []) {
      if (blk?.type === 'tool_use' && typeof blk.id === 'string') {
        if (a.tools.has(blk.id)) continue
        a.tools.add(blk.id)
        const report = handbackText(blk.name, blk.input)
        const stops = blk.name === 'TaskStop' || blk.name === 'KillShell' ? (blk.input?.task_id ?? blk.input?.shell_id) : null
        if (typeof stops === 'string') a.stops.set(blk.id, stops)
        if (report != null) {
          a.result = clean(report)
          a.fromFile = a.result != null
          a.last = 'result'
          a.activity = null
        } else {
          a.last = 'tool'
          if (!(ts < a.hookAt)) a.activity = clean(`${blk.name}: ${toolSummary(blk.name, blk.input ?? {})}`, 200)
        }
      } else if (blk?.type === 'text' && typeof blk.text === 'string' && blk.text.trim()) {
        a.text = blk.text
        if (a.last !== 'result') a.last = 'text'
      }
    }
  } else if (o?.type === 'user' && Array.isArray(o.message?.content) && o.message.content.some((x: any) => x?.type === 'tool_result')) {
    if (a.last === 'tool') a.last = 'working'
    a.turnOver = false
    const started = bgStarted(o.toolUseResult, Number.isFinite(ts) ? ts : Date.now())
    if (started) a.bg.set(started[0], started[1])
    // TaskStop came back: stopped now, or "not running" (already over). No notification has to follow.
    for (const x of o.message.content) {
      const task = x?.type === 'tool_result' ? a.stops.get(x.tool_use_id) : undefined
      if (task == null) continue
      a.bg.delete(task)
      a.stops.delete(x.tool_use_id)
    }
  } else if (o?.type === 'attachment' && o.attachment?.type === 'structured_output') {
    const r = clean(structuredText(o.attachment.data))
    if (r) {
      a.result = r
      a.fromFile = true
    }
    a.last = 'result'
  } else if (o?.type === 'attachment' && o.attachment?.hookEvent === 'SubagentStop') {
    a.turnOver = true
  } else {
    const ends = notifications(o)
    if (ends.length) {
      // Background work's news reaches it as its next turn's input: it's awake. A notification's
      // own "waiting" (an agent it started) ends nothing, and a Monitor's event has no status.
      for (const e of ends) if (e.waiting !== true) a.bg.delete(e.taskId)
      a.turnOver = false
      if (later) {
        a.saidWaitingAt = 0
        a.unreported = false
      }
      if (a.last === 'text') a.last = 'working'
    }
  }
}

/** Read [a]'s file from [from] for up to [max] bytes; lines go through [lightLine]. Returns the new offset. */
function readLines(a: AgentRec, file: string, from: number, max: number, skipFirst: boolean, keepCarry: boolean): number {
  let fd: number | null = null
  try {
    fd = openSync(file, 'r')
    const size = fstatSync(fd).size
    if (size <= from) return size
    const buf = Buffer.alloc(Math.min(size - from, max))
    const n = readSync(fd, buf, 0, buf.length, from)
    const bytes = keepCarry ? Buffer.concat([a.carry, buf.subarray(0, n)]) : buf.subarray(0, n)
    const nl = bytes.lastIndexOf(0x0a)
    if (keepCarry) a.carry = nl >= 0 ? Buffer.from(bytes.subarray(nl + 1)) : Buffer.from(bytes)
    let text = nl >= 0 ? bytes.subarray(0, nl).toString('utf8') : ''
    // Started mid-file: the first line is a piece of one.
    if (skipFirst) text = text.slice(text.indexOf('\n') + 1)
    for (const line of text.split('\n')) {
      if (!line) continue
      try {
        lightLine(a, JSON.parse(line))
      } catch {}
    }
    return from + n
  } catch {
    return from
  } finally {
    if (fd != null) closeSync(fd)
  }
}

/** Follow a running agent's file from where it was left (its last 256 KB the first time). */
function follow(a: AgentRec): void {
  if (!a.file) return
  const size = sizeOf(a.file)
  if (size == null) return
  if (size < a.offset) {
    a.offset = 0
    a.carry = Buffer.alloc(0)
    // Read afresh: its replies count again from the start.
    a.inTokens = 0
    a.outTokens = 0
    a.msgId = null
    a.msgOut = 0
    a.bg.clear()
    a.stops.clear()
    a.turnOver = false
  }
  if (size === a.offset) return
  let skip = false
  if (a.offset === 0) {
    a.whole = size <= TAIL_START
    if (!a.whole) {
      a.offset = size - TAIL_START
      skip = true
    }
  }
  a.offset = readLines(a, a.file, a.offset, 2 * 1024 * 1024, skip, true)
}

function sizeOf(p: string): number | null {
  try {
    return statSync(p).size
  } catch {
    return null
  }
}

/**
 * A running agent whose file has gone quiet (10 minutes in a live session, 2 once it has ended, 2
 * after an API error, 1 while its session is stopped by a limit): done when its last word was its
 * report or text with no tool call after, else stopped. One waiting on a tool (a long build, an
 * approval) in a live session is still at work, unless an API error or its session's limit cut it
 * off. One waiting on its own background work still is too, up to its cap ([waitCap]), a limit on
 * the session or not (it needs no model while it waits). An agent never followed has its last 64 KB
 * read for this, once. [gone]: its claude process is gone, so it has stopped however recently it wrote.
 */
function settle(b: Board, a: AgentRec, live: boolean, gone = false, blocked = false): void {
  const now = Date.now()
  if (!gone && !blocked && a.last !== 'error' && now - a.checkedAt < 60_000) return
  const mtime = mtimeOf(a.file)
  const lastWrite = Math.max(mtime ?? a.lastActivityAt, a.hookAt)
  const quiet = now - lastWrite
  const cutOff = blocked || a.last === 'error'
  if (!gone && quiet < (blocked ? IDLE_BLOCKED : a.last === 'error' ? IDLE_ERROR : live ? IDLE_LIVE : IDLE_ENDED)) return
  a.checkedAt = now
  if (a.last == null && a.file && mtime != null) {
    const size = sizeOf(a.file) ?? 0
    readLines(a, a.file, Math.max(0, size - 64 * 1024), 64 * 1024, size > 64 * 1024, false)
  }
  if (!gone && !cutOff && live && quiet < IDLE_MAX && (a.last === 'tool' || a.last === 'working')) return
  if (!gone && live && a.waiting && quiet < waitCap(a, lastWrite)) return
  const status = quietEnd(a)
  const why = gone ? 'its claude process is gone' : a.waiting ? (live ? 'waited past its cap on background work' : 'its session ended while it waited') : a.last === 'error' ? 'an API error cut it off' : blocked ? 'its session is blocked' : 'its file went quiet'
  logOnce(`settle:${b.id}/${a.id}:${why}`, `agent ended ${status}: ${why}:`, a.id, 'in', b.id, `(last write ${Math.round(quiet / 60_000)} min ago)`)
  end(b, a, status, mtime ?? a.lastActivityAt, false)
}

/**
 * The session can't reach the model now (a usage limit, signed out, out of credit, its provider
 * failing): its agents can't either. A model's own week isn't (an agent may run on another model).
 */
const cutOffBy = (r: Rec) => !!r.blocked && r.blocked.kind !== 'model'

/**
 * A running workflow with none of its agents at work and a journal that hasn't grown for a while
 * (2 minutes once its session has ended, 2 hours in a live one) has stopped: its notification and
 * run summary never came (Claude Code quit or crashed mid-run). Its call stops with it.
 */
function settleWorkflow(b: Board, w: WorkflowRec, dir: string, live: boolean): void {
  let last = Math.max(w.startedAt, mtimeOf(join(dir, 'journal.jsonl')) ?? 0, mtimeOf(dir) ?? 0)
  for (const a of b.agents.values()) {
    if (a.workflowId !== w.id) continue
    if (a.status === 'running') return
    last = Math.max(last, a.endedAt ?? a.lastActivityAt)
  }
  if (Date.now() - last < (live ? IDLE_MAX : IDLE_ENDED)) return
  endWorkflow(b, w, 'stopped', last)
}

/** Whether the session is still going: its agents end with it. */
const isLive = (r: Rec) => r.state !== 'ended'

/** Read what changed in a session's folders, follow its running agents, and settle quiet ones. */
function scan(b: Board, r: Rec, follows: boolean): void {
  const root = subagentsDir(r)
  if (folderChanged(b, root)) for (const f of ls(root)) {
    const m = META.exec(f)
    if (m) readMeta(b, join(root, f), m[1]!, null, root)
  }
  const runs = join(root, 'workflows')
  if (folderChanged(b, runs)) for (const run of ls(runs)) if (RUN_ID.test(run)) newWorkflow(b, run, mtimeOf(join(runs, run)) ?? Date.now())
  for (const w of b.workflows.values()) {
    if (w.scanned && w.status !== 'running') continue
    const dir = join(runs, w.id)
    if (folderChanged(b, dir)) for (const f of ls(dir)) {
      const m = META.exec(f)
      if (m) readMeta(b, join(dir, f), m[1]!, w.id, dir)
    }
    tailJournal(b, w, join(dir, 'journal.jsonl'))
    w.scanned = true
  }
  const snaps = join(dirname(root), 'workflows')
  if (folderChanged(b, snaps)) for (const f of ls(snaps)) {
    const m = /^(wf_[0-9a-f-]{1,40})\.json$/.exec(f)
    const mtime = m ? mtimeOf(join(snaps, f)) : null
    // Read once, and again if it's written anew (a run started again has ended); one still being
    // written is read again on the next scan.
    if (m && mtime != null && b.snapshots.get(m[1]!) !== mtime && !readSnapshot(b, join(snaps, f), m[1]!, mtime)) b.seen.delete(snaps)
  }
  const live = isLive(r)
  const blocked = cutOffBy(r)
  for (const a of b.agents.values()) {
    if (a.status !== 'running') continue
    if (!a.file) a.file = findFile(root, a)
    const before = `${a.activity}|${a.tokens}|${a.tools.size}`
    if (follows && live) follow(a)
    // It handed back its report after all: nothing it left running keeps it.
    if (a.last === 'result' && (a.waiting || a.saidWaitingAt > 0)) {
      end(b, a, 'done', mtimeOf(a.file) ?? a.lastActivityAt, false)
      continue
    }
    syncWait(a)
    settle(b, a, live, false, blocked)
    if (a.status === 'running' && `${a.activity}|${a.tokens}|${a.tools.size}` !== before) changed(b, a, false)
  }
  for (const w of b.workflows.values()) if (w.status === 'running') settleWorkflow(b, w, join(runs, w.id), live)
  publishRunning(b)
}

/**
 * The session's claude process is gone (its SessionEnd, or it vanished from Claude Code's
 * registry): agents and workflows it ran in the background went with it. What the folders say
 * last is read first (a run summary written as it quit), then whatever still runs ends, an agent
 * done if its last word was its report, else stopped, and its call with it.
 */
export function endSessionAgents(sessionId: string): void {
  const r = getSession(sessionId)
  const b = boards.get(sessionId)
  if (!r || !b) return
  try {
    scan(b, r, false)
    const root = subagentsDir(r)
    for (const a of b.agents.values()) {
      if (a.status !== 'running') continue
      if (!a.file) a.file = findFile(root, a)
      // What it wrote since the last scan first: a report handed back in those last seconds ends it done.
      follow(a)
      settle(b, a, false, true)
    }
    for (const w of b.workflows.values()) if (w.status === 'running') endWorkflow(b, w, 'stopped', Date.now())
  } catch (e) {
    log('agents end failed', sessionId, e)
  }
  publishRunning(b)
}

/** An agent's transcript in a session's subagents folder: flat, in its workflow's folder, or further down. */
function findFile(root: string, a: { id: string; workflowId: string | null }): string | null {
  if (!AGENT_ID.test(a.id)) return null
  const name = `agent-${a.id}.jsonl`
  if (a.workflowId && RUN_ID.test(a.workflowId)) return join(root, 'workflows', a.workflowId, name)
  return locate(root, name)
}

/**
 * File [name] in a session's subagents folder [root]: flat, in a workflow's folder, else anywhere
 * further down (Claude Code can file an agent's transcript in a folder of its own there,
 * `subagents/<folders…>/agent-<id>.jsonl`). Never outside [root]; null when it isn't there.
 */
function locate(root: string, name: string): string | null {
  const flat = join(root, name)
  if (mtimeOf(flat) != null) return flat
  for (const run of ls(join(root, 'workflows'))) {
    if (!RUN_ID.test(run)) continue
    const p = join(root, 'workflows', run, name)
    if (mtimeOf(p) != null) return p
  }
  return search(root, name)
}

/** How many folders down, and how many folders in all, [search] looks below a subagents folder. */
const TREE_DEPTH = 6
const TREE_DIRS = 200

/** File [name] anywhere below [root], nearest first. Links aren't followed. */
function search(root: string, name: string): string | null {
  let level = [root]
  let dirs = 0
  for (let depth = 0; depth < TREE_DEPTH && level.length; depth++) {
    const next: string[] = []
    for (const dir of level) {
      let entries: Dirent[]
      try {
        entries = readdirSync(dir, { withFileTypes: true })
      } catch {
        continue
      }
      for (const e of entries) {
        if (e.isFile() && e.name === name) return join(dir, e.name)
        if (e.isDirectory() && ++dirs <= TREE_DIRS) next.push(join(dir, e.name))
      }
    }
    level = next
  }
  return null
}

/**
 * Agent [id]'s meta file, read when it is in its session's subagents folder (anywhere [locate]
 * looks): whether the board has the agent now.
 */
function metaRead(b: Board, id: string): boolean {
  if (!AGENT_ID.test(id)) return false
  const r = getSession(b.id)
  if (!r) return false
  const root = subagentsDir(r)
  const p = locate(root, `agent-${id}.meta.json`)
  if (!p) return false
  const dir = dirname(p)
  const runId = dirname(dir) === join(root, 'workflows') && RUN_ID.test(basename(dir)) ? basename(dir) : null
  readMeta(b, p, id, runId, dir)
  return b.agents.has(id)
}

/**
 * Every 2 s: sessions that are going, ended in the last 15 minutes, or still have agents running.
 * Old history is read only when the phone asks ([listAgents]).
 */
export function pollAgents(): void {
  const now = Date.now()
  for (const r of listSessionRecs()) {
    const going = r.state === 'working' || r.state === 'needs_you' || r.state === 'starting'
    // A workflow between phases has no agent running, and still has to be seen to its end.
    if (!going && now - r.lastActivityAt > LIVE_MS && !backgroundRunning(r.id)) continue
    try {
      scan(board(r.id), r, true)
    } catch (e) {
      log('agents scan failed', r.id, e)
    }
  }
  for (const [k, c] of transcripts) if (now - c.at > CACHE_MS) transcripts.delete(k)
}

// ── What the phone asks for ───────────────────────────────────────────────

/**
 * A session's agents and workflows: the 60 most recent agents (and any running), older ones only
 * counted in their workflow. A session not scanned lately (history) has its folders read once now,
 * its transcript's links included; no agent transcript is read for it.
 */
export function listAgents(sessionId: string): { agents: Agent[]; workflows: Workflow[] } {
  const r = getSession(sessionId)
  if (!r) return { agents: [], workflows: [] }
  const b = board(sessionId)
  // History is read without a message per agent (the phone asks for it whole); a live session's
  // news goes to every phone, as its scan would send it.
  const quiet = !isLive(r)
  if (quiet) hush++
  try {
    ensureParsed(r)
    if (!b.listed) {
      b.listed = true
      scan(b, r, false)
    }
  } finally {
    if (quiet) hush--
  }
  // A workflow step's earlier attempts are left out: the attempt that counts is the last one.
  const all = [...b.agents.values()].filter((a) => !a.retried).sort((x, y) => y.startedAt - x.startedAt)
  const shown = all.filter((a, i) => i < MAX_AGENTS || a.status === 'running')
  // Reports of plain agents that ended before anyone followed them (a hand-back's own words).
  for (const a of shown) if (a.status === 'done' && !a.workflowId && !a.fromFile) peek(b, a)
  const agents = shown.map(agentView)
  const workflows = [...b.workflows.values()].sort((x, y) => y.startedAt - x.startedAt).map((w) => workflowView(b, w))
  return { agents, workflows }
}

/** How many of a session's agents run now. */
export const agentsRunning = (sessionId: string) => boards.get(sessionId)?.running ?? 0

/** Agents or a workflow still at work in the background of a session. */
export function backgroundRunning(sessionId: string): boolean {
  const b = boards.get(sessionId)
  if (!b) return false
  if (b.running > 0) return true
  for (const w of b.workflows.values()) if (w.status === 'running') return true
  return false
}

/**
 * What an approval from agent [agentId] calls it: its label, from its meta file when it hasn't
 * been read yet (it's written as the agent starts, so an approval comes after it).
 */
export function agentLabel(sessionId: string, agentId: string): string | null {
  if (!SAFE_ID.test(agentId)) return null
  const b = board(sessionId)
  let a = b.agents.get(agentId)
  if (!a?.label && metaRead(b, agentId)) a = b.agents.get(agentId)
  return a?.label || a?.type || null
}

// One agent's own transcript, parsed for the phone's agent screen and kept a while: an open
// screen asks again every 1.5 s while the agent runs, and only new lines are read then. Each event
// is stamped with a number as it's added or changed, so a phone that has the transcript up to a
// cursor gets only what changed since (usually nothing, or a line or two) rather than all of it.
type Cached = {
  st: ParseState
  file: string
  offset: number
  carry: Buffer
  at: number
  /** This parse's name in cursors: a cursor from another parse (the file was rewritten, the daemon restarted) gets the whole window. */
  gen: string
  /** The last stamp given, and each event's. */
  seq: number
  stamps: Map<string, number>
}
const transcripts = new Map<string, Cached>()
const CACHE_SIZE = 8
const CACHE_MS = 10 * 60_000

/** The file of agent [agentId] of session [sessionId], only inside that session's subagents folder; null when there's none. */
export function agentFile(sessionId: string, agentId: string): string | null {
  if (!AGENT_ID.test(agentId)) return null
  const r = getSession(sessionId)
  if (!r) return null
  const root = subagentsDir(r)
  const p = findFile(root, { id: agentId, workflowId: boards.get(sessionId)?.agents.get(agentId)?.workflowId ?? null })
  if (!p) return null
  try {
    // Links can't lead out of the folder.
    const real = realpathSync(p)
    return real.startsWith(realpathSync(root) + '/') && statSync(real).isFile() ? real : null
  } catch {
    return null
  }
}

const newCached = (file: string): Cached => ({ st: newParseState(true), file, offset: 0, carry: Buffer.alloc(0), at: 0, gen: randomUUID().slice(0, 8), seq: 0, stamps: new Map() })

export type AgentEvents = {
  /** [full]: its last events, its task first; else only those added or changed since the cursor asked with, in order. */
  events: Ev[]
  /** Where this answer leaves the phone: ask with it next time. */
  cursor: string
  full: boolean
  /** It has only just started and Claude Code hasn't written its file yet: no events, ask again in a moment. */
  starting?: true
}

/** How long after it started an agent with no file yet is still starting, rather than missing. */
const START_MS = 2 * 60_000

/**
 * Agent [agentId]'s own events (its prompt first, its report last): the last [limit], with its
 * task always first, or, given the [after] cursor of an earlier answer, only those added or changed
 * since then. With no file: [AgentEvents.starting] for one the board has as just started, else null.
 */
export function agentEvents(sessionId: string, agentId: string, limit = 200, after: string | null = null): AgentEvents | null {
  const file = agentFile(sessionId, agentId)
  if (!file) {
    const a = boards.get(sessionId)?.agents.get(agentId)
    return a && a.status === 'running' && Date.now() - a.startedAt < START_MS ? { events: [], cursor: '', full: true, starting: true } : null
  }
  const key = `${sessionId}/${agentId}`
  let c = transcripts.get(key)
  if (!c || c.file !== file) c = newCached(file)
  transcripts.delete(key)
  transcripts.set(key, c)
  c.at = Date.now()
  readTranscript(c)
  for (const k of transcripts.keys()) if (transcripts.size > CACHE_SIZE && k !== key) transcripts.delete(k)
  const n = Math.max(1, limit)
  const cursor = `${c.gen}.${c.seq}`
  const evs = c.st.events
  const m = after ? /^([0-9a-f]{8})\.(\d{1,15})$/.exec(after) : null
  const since = m && m[1] === c.gen ? Number(m[2]) : NaN
  if (since <= c.seq) {
    const changed: Ev[] = []
    for (const e of evs) if ((c.stamps.get(e.id) ?? 0) > since) changed.push(e)
    // Further behind than a window: start again from the window.
    if (changed.length <= n) return { events: changed, cursor, full: false }
  }
  const window = evs.slice(-n)
  // What it was asked stays first, however long it has worked since.
  const task = evs.find((e) => e.kind === 'user')
  if (task && !window.includes(task)) window.unshift(task)
  return { events: window, cursor, full: true }
}

/** The events a transcript line can change in place: a tool's call its result closes, and the message its text or thinking adds to. */
function touchedBy(o: any): string[] {
  const c = o?.message?.content
  if (!Array.isArray(c)) return []
  const msg = o.message?.id ?? o.uuid
  const out: string[] = []
  for (const b of c) {
    if (b?.type === 'tool_result' && typeof b.tool_use_id === 'string') out.push(b.tool_use_id)
    else if (b?.type === 'text') out.push(`t:${msg}`)
    else if (b?.type === 'thinking') out.push(`th:${msg}`)
  }
  return out
}

function readTranscript(c: Cached): void {
  let fd: number | null = null
  try {
    fd = openSync(c.file, 'r')
    const size = fstatSync(fd).size
    // Rewritten: read afresh, under a new name (cursors from before get the whole window).
    if (size < c.offset) Object.assign(c, newCached(c.file), { at: c.at })
    // In 8 MB steps; an agent's file can be tens of MB.
    for (let i = 0; i < 64 && c.offset < size; i++) {
      const buf = Buffer.alloc(Math.min(size - c.offset, 8 * 1024 * 1024))
      const n = readSync(fd, buf, 0, buf.length, c.offset)
      if (n <= 0) break
      c.offset += n
      const bytes = Buffer.concat([c.carry, buf.subarray(0, n)])
      const nl = bytes.lastIndexOf(0x0a)
      c.carry = nl >= 0 ? Buffer.from(bytes.subarray(nl + 1)) : Buffer.from(bytes)
      for (const line of nl >= 0 ? bytes.subarray(0, nl).toString('utf8').split('\n') : []) {
        if (!line) continue
        let o: any
        try {
          o = JSON.parse(line)
        } catch {
          continue
        }
        const before = c.st.events.length
        feedLine(c.st, o)
        const stamp = (id: string) => c.st.byId.has(id) && c.stamps.set(id, ++c.seq)
        for (let j = before; j < c.st.events.length; j++) stamp(c.st.events[j]!.id)
        for (const id of touchedBy(o)) stamp(id)
        for (const id of c.st.changed) stamp(id)
        c.st.changed.clear()
      }
    }
    // Links in an agent's own file (agents it started) are the main board's business.
    c.st.links.length = 0
  } catch (e) {
    log('agent transcript read failed', c.file, e)
  } finally {
    if (fd != null) closeSync(fd)
  }
}
