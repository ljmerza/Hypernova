// Claude Code transcript lines and Agent SDK messages → Hypernova events (docs/PROTOCOL.md).
import { basename, dirname } from 'node:path'
import { tail, trimText } from './util.ts'
import { splitAttachments, type Attachment } from './attachments.ts'
import { autoContinueOf, classifyApiError, classifyNotice, cleanText, type Blocked, type BlockedKind, type Blocker, type LimitNotice } from './blockers.ts'

export type Diff = { path: string; added: number; removed: number; hunks: { oldStart: number; oldLines: number; newStart: number; newLines: number; lines: string[] }[] }
/**
 * A slash command or "!" shell command the owner ran. `local`: one of Claude Code's own
 * (/effort, /model); `prompt`: a prompt or plugin command; `skill`: a skill (a `prompt` or
 * `local` one becomes this once its body is read); `bash`: "!" mode, where `name` is "!" and
 * `args` the command line.
 */
export type Command = { name: string; args: string; kind: 'local' | 'prompt' | 'skill' | 'bash' }
/**
 * Prompts that left a queue without reaching Claude: `returned` to the terminal's input box (the
 * Up arrow, or Esc while no turn runs), or `dropped` (cleared, refused by a hook, or a Stop in an
 * SDK session). `clientId`: the phone send each came from (null when typed on the Mac).
 */
export type Unsent = { reason: 'returned' | 'dropped'; prompts: { text: string; clientId: string | null }[] }
export type Ev =
  /** `clientId`: the phone send a prompt came from, when the daemon knows it. */
  | { id: string; kind: 'user'; ts: number; text: string; files?: Attachment[]; command?: Command; clientId?: string }
  | { id: string; kind: 'assistant'; ts: number; text: string; streaming: boolean }
  | { id: string; kind: 'thinking'; ts: number; chars: number }
  | {
      id: string
      kind: 'tool'
      ts: number
      toolUseId: string
      name: string
      summary: string
      input: Record<string, unknown>
      status: 'running' | 'done' | 'error'
      output: string | null
      exitCode: number | null
      durationMs: number | null
      diff: Diff | null
      path: string | null
    }
  | { id: string; kind: 'approval'; ts: number; approvalId: string; summary: string; resolution: null | 'allow' | 'deny' | 'timeout' | 'terminal' }
  | { id: string; kind: 'result'; ts: number; ok: boolean; durationMs: number; costUsd: number | null; turns: number; error: string | null }
  /**
   * `command`: the id of the command's user event when this is that command's output. `unsent`:
   * queued prompts this notice says didn't reach Claude. `blocker`: an API error that stopped
   * Claude (a usage limit, signed out…), as Session.blocked names it.
   */
  | { id: string; kind: 'notice'; ts: number; text: string; tone: 'info' | 'warn' | 'error'; command?: string; unsent?: Unsent; blocker?: BlockedKind }

export type ToolEv = Extract<Ev, { kind: 'tool' }>

const short = (p: unknown) => {
  if (typeof p !== 'string') return ''
  const b = basename(p)
  const d = basename(dirname(p))
  return d && d !== '.' && d !== '/' ? `${d}/${b}` : b
}

export function toolSummary(name: string, input: Record<string, unknown>): string {
  const i = input ?? {}
  switch (name) {
    case 'Bash':
      return String(i.command ?? '').split('\n')[0] ?? ''
    case 'Read':
    case 'Write':
    case 'Edit':
    case 'MultiEdit':
    case 'NotebookEdit':
      return short(i.file_path ?? i.notebook_path)
    case 'Grep':
      return `“${i.pattern ?? ''}”${i.path ? ' in ' + short(i.path) : ''}`
    case 'Glob':
      return String(i.pattern ?? '')
    case 'WebFetch':
      return String(i.url ?? '')
    case 'WebSearch':
      return String(i.query ?? '')
    case 'Task':
    case 'Agent':
      return String(i.description ?? i.subagent_type ?? 'subagent')
    case 'Workflow':
      return workflowName(i)
    case 'TodoWrite':
      return 'Update the to-do list'
    case 'AskUserQuestion':
      return 'A question for you'
    case 'ExitPlanMode':
      return 'Plan ready for review'
    default:
      return name.startsWith('mcp__') ? name.split('__').slice(1).join(' · ') : name
  }
}

// ── Workflows ────────────────────────────────────────────────────────────
// A Workflow call's input is a script that starts `export const meta = {name, description,
// phases: [{title, detail}]}`. Its name and phases are read from there before the input is
// trimmed for the wire; a named workflow or a script file stands in for a script it didn't send.

/** A string literal's text at [i] in [s] (any quote), with the index after it; null when none starts there. */
function literal(s: string, i: number): { text: string; end: number } | null {
  const q = s[i]
  if (q !== '"' && q !== "'" && q !== '`') return null
  let out = ''
  for (let j = i + 1; j < s.length; j++) {
    const c = s[j]!
    if (c === '\\') {
      out += s[j + 1] ?? ''
      j++
    } else if (c === q) return { text: out, end: j + 1 }
    else out += c
  }
  return null
}

/** The value of `key: '…'` from [from] on in [s], up to [to]; null when it isn't a plain string. */
function keyString(s: string, key: string, from = 0, to = s.length): string | null {
  const re = new RegExp(`\\b${key}\\s*:\\s*`, 'g')
  re.lastIndex = from
  const m = re.exec(s)
  if (!m || m.index >= to) return null
  return literal(s, m.index + m[0].length)?.text ?? null
}

/** What a workflow is called: meta.name in its script, else the named workflow or the script file. */
export function workflowName(i: Record<string, unknown>): string {
  const script = typeof i.script === 'string' ? i.script.slice(0, 8000) : ''
  const name = keyString(script, 'name')?.trim()
  if (name) return name.slice(0, 80)
  if (typeof i.name === 'string' && i.name.trim()) return i.name.trim()
  if (typeof i.scriptPath === 'string' && i.scriptPath) return basename(i.scriptPath).replace(/\.[^.]+$/, '')
  return 'workflow'
}

export type WorkflowPhase = { title: string; detail: string | null }

/**
 * meta.phases in a workflow script, as far as a plain reading finds them: each `{title, detail}`
 * between `phases: [` and its closing bracket (brackets inside strings don't count).
 */
export function workflowPhases(script: unknown): WorkflowPhase[] {
  if (typeof script !== 'string') return []
  const s = script.slice(0, 20000)
  const at = /\bphases\s*:\s*\[/.exec(s)
  if (!at) return []
  let depth = 0
  let end = -1
  for (let j = at.index + at[0].length - 1; j < s.length; j++) {
    const lit = literal(s, j)
    if (lit) {
      j = lit.end - 1
      continue
    }
    if (s[j] === '[') depth++
    else if (s[j] === ']' && --depth === 0) {
      end = j
      break
    }
  }
  if (end < 0) return []
  const out: WorkflowPhase[] = []
  const body = s.slice(at.index + at[0].length, end)
  // One object per phase: its title, and its detail, before or after the title in that object.
  for (const m of body.matchAll(/\btitle\s*:\s*/g)) {
    const lit = literal(body, m.index + m[0].length)
    const title = lit?.text.trim()
    if (!lit || !title) continue
    const start = Math.max(0, body.lastIndexOf('{', m.index))
    const detail = keyString(body, 'detail', start, closing(body, lit.end))
    out.push({ title: title.slice(0, 60), detail: detail?.trim().slice(0, 200) || null })
    if (out.length >= 20) break
  }
  return out
}

/** Where the object [from] is in ends: its next `}` outside a string. */
function closing(s: string, from: number): number {
  for (let j = from; j < s.length; j++) {
    const lit = literal(s, j)
    if (lit) j = lit.end - 1
    else if (s[j] === '}') return j
  }
  return s.length
}

export function toolPath(name: string, input: Record<string, unknown>): string | null {
  const p = input?.file_path ?? input?.notebook_path
  return typeof p === 'string' && ['Read', 'Write', 'Edit', 'MultiEdit', 'NotebookEdit'].includes(name) ? p : null
}

/** Shrink tool input for the wire: long strings are cut, file contents dropped. */
export function trimInput(input: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(input ?? {})) {
    if (typeof v === 'string') out[k] = k === 'content' || k === 'new_string' || k === 'old_string' ? trimText(v, 4000) : trimText(v, 2000)
    else if (v && typeof v === 'object') out[k] = JSON.stringify(v).length <= 8000 ? v : Array.isArray(v) ? `[${v.length} items]` : '{…}'
    else out[k] = v
  }
  return out
}

function safeTrimInput(input: Record<string, unknown>): Record<string, unknown> {
  try {
    return trimInput(input)
  } catch {
    return {}
  }
}

export function newToolEvent(id: string, name: string, input: Record<string, unknown>, ts: number): ToolEv {
  return {
    id,
    kind: 'tool',
    ts,
    toolUseId: id,
    name,
    summary: toolSummary(name, input),
    input: safeTrimInput(input),
    status: 'running',
    output: null,
    exitCode: null,
    durationMs: null,
    diff: null,
    path: toolPath(name, input),
  }
}

function blocksText(content: unknown): string {
  if (typeof content === 'string') return content
  if (Array.isArray(content))
    return content
      .map((b) => (typeof b === 'string' ? b : b?.type === 'text' ? b.text : b?.type === 'image' ? '[image]' : ''))
      .filter(Boolean)
      .join('\n')
  return ''
}

/** A message's text blocks alone, without pictures. */
function textBlocks(content: unknown): string[] {
  if (typeof content === 'string') return [content]
  if (!Array.isArray(content)) return []
  return content.flatMap((b) => (typeof b === 'string' ? [b] : b?.type === 'text' && typeof b.text === 'string' ? [b.text] : []))
}

function diffFrom(result: any, path: string | null): Diff | null {
  const patch = result?.structuredPatch
  if (!Array.isArray(patch) || !patch.length) {
    if (result?.type === 'create' && typeof result.content === 'string') {
      const lines = result.content.split('\n').slice(0, 400).map((l: string) => '+' + l)
      return { path: result.filePath ?? path ?? '', added: lines.length, removed: 0, hunks: [{ oldStart: 0, oldLines: 0, newStart: 1, newLines: lines.length, lines }] }
    }
    return null
  }
  let added = 0
  let removed = 0
  const hunks = patch.slice(0, 40).map((h: any) => {
    const lines: string[] = (h.lines ?? []).slice(0, 400)
    for (const l of lines) {
      if (l.startsWith('+')) added++
      else if (l.startsWith('-')) removed++
    }
    return { oldStart: h.oldStart, oldLines: h.oldLines, newStart: h.newStart, newLines: h.newLines, lines }
  })
  return { path: result.filePath ?? path ?? '', added, removed, hunks }
}

/** Tools that start an agent, and the one that starts a workflow of them. */
export const AGENT_TOOLS = new Set(['Agent', 'Task'])
export const isLauncher = (name: string) => AGENT_TOOLS.has(name) || name === 'Workflow'

/** A launcher's result says its agent or workflow went on in the background. */
const launched = (r: any) => r?.status === 'async_launched' || r?.status === 'remote_launched'

/** Apply a tool_result block (+ optional structured toolUseResult) to its tool event. */
export function applyToolResult(ev: ToolEv, block: any, toolUseResult: any, ts: number): void {
  const isError = !!block?.is_error
  // An agent or workflow launched in the background is still at work: its call stays running until
  // its task notification (closeTool), and Claude Code's launch note ("internal metadata, never
  // quote") isn't output to show.
  if (isLauncher(ev.name) && !isError && launched(toolUseResult)) {
    ev.status = 'running'
    ev.output = null
    if (ev.name === 'Workflow' && typeof toolUseResult.workflowName === 'string' && toolUseResult.workflowName) ev.summary = toolUseResult.workflowName
    return
  }
  let output = blocksText(block?.content)
  // An agent that ran in the foreground: its report, and its own clock.
  if (AGENT_TOOLS.has(ev.name) && toolUseResult?.status === 'completed' && Array.isArray(toolUseResult.content)) {
    output = blocksText(toolUseResult.content)
    if (typeof toolUseResult.totalDurationMs === 'number') ts = ev.ts + toolUseResult.totalDurationMs
  }
  if (toolUseResult && typeof toolUseResult === 'object' && !Array.isArray(toolUseResult)) {
    if (typeof toolUseResult.stdout === 'string') {
      const said = output
      output = toolUseResult.stdout + (toolUseResult.stderr ? (toolUseResult.stdout ? '\n' : '') + toolUseResult.stderr : '')
      // A command in the background has no output yet; its result names its ID and output file
      // ("Command running in background with ID: b8f…. Output is being written to: /…/b8f….output"),
      // which is how "show me" (and Lens) find the address a dev server started there printed.
      const bg = typeof toolUseResult.backgroundTaskId === 'string' ? toolUseResult.backgroundTaskId : null
      if (bg) {
        const note = said.split('\n').find((l) => l.includes(bg)) ?? `Command running in background with ID: ${bg}.`
        if (!output.includes(note)) output = output ? `${output}\n${note}` : note
      }
    }
    const d = diffFrom(toolUseResult, ev.path)
    if (d) ev.diff = d
  }
  // A file's contents stay on the Mac (the line count says enough), except a background command's
  // output file: what Claude read there (a dev server's address) is what the command printed.
  if (ev.name === 'Read') output = output ? (/\/tasks\/[\w-]+\.output$/.test(ev.path ?? '') ? tail(output, 4096) : `${output.split('\n').length} lines`) : output
  let exit: number | null = null
  const m = /Exit code (\d+)/.exec(isError ? output : '')
  if (m) exit = Number(m[1])
  else if (ev.name === 'Bash') exit = isError ? 1 : 0
  ev.status = isError ? 'error' : 'done'
  ev.output = tail(output ?? '')
  ev.exitCode = exit
  ev.durationMs = Math.max(0, ts - ev.ts)
}

// ── Agents and workflows in the main transcript ─────────────────────────
// Claude Code 2.1.29x writes each agent's turns to a file of its own, so the main transcript only
// links them: the launcher's result names the agent (or the workflow run), and a task
// notification says how it ended. The parser collects these as links for agents.ts.
// docs/PROTOCOL.md, "Agents and workflows".

/** How a background task ended, as its notification says. */
export type TaskStatus = 'completed' | 'failed' | 'killed' | 'stopped'
export type TaskEnd = {
  taskId: string
  toolUseId: string | null
  status: TaskStatus
  summary: string | null
  result: string | null
  tokens: number | null
  toolUses: number | null
  durationMs: number | null
  /** A workflow's: how many agents it ran, and how many failed. */
  agentCount: number | null
  agentsError: number | null
}
export type AgentLink =
  /** An Agent call started agent [agentId]; [report] when it ran in the foreground and has finished. */
  | { kind: 'agent'; ts: number; toolUseId: string; agentId: string; label: string | null; type: string | null; model: string | null; background: boolean; report: { result: string | null; tokens: number | null; toolUses: number | null; durationMs: number | null } | null }
  /** A Workflow call started run [runId] (task [taskId]). */
  | { kind: 'workflow'; ts: number; toolUseId: string; runId: string | null; taskId: string | null; name: string | null; summary: string | null; phases: WorkflowPhase[] }
  /** SendMessage woke a finished agent: it works again, in the same file. */
  | { kind: 'resume'; ts: number; agentId: string }
  /** TaskStop stopped a task: a workflow ("w…") or a background agent ("a…"; a shell's is ignored). */
  | { kind: 'stop'; ts: number; taskId: string }
  /** An Agent call came back with an error or was interrupted (Esc): the agent it ran stopped with it. */
  | { kind: 'failed'; ts: number; toolUseId: string }
  | ({ kind: 'end'; ts: number } & TaskEnd)

const TASK_STATUSES = new Set<string>(['completed', 'failed', 'killed', 'stopped'])

/** The text of <name>…</name> in [s] (its first), trimmed; null when it isn't there. */
function tag(s: string, name: string): string | null {
  const a = s.indexOf(`<${name}>`)
  if (a < 0) return null
  const b = s.indexOf(`</${name}>`, a)
  return b < 0 ? null : s.slice(a + name.length + 2, b).trim()
}
const num = (s: string | null) => (s && /^\d+$/.test(s) ? Number(s) : null)

/**
 * A `<task-notification>`: Claude Code's word that a background agent ("a…"), workflow ("w…") or
 * shell command ("b…") ended, with its tool call, status and usage. null for anything else.
 */
export function parseTaskNotification(text: unknown): TaskEnd | null {
  if (typeof text !== 'string') return null
  const s = text.trimStart()
  if (!s.startsWith('<task-notification>')) return null
  const taskId = tag(s, 'task-id')
  const status = tag(s, 'status')
  if (!taskId || !status || !TASK_STATUSES.has(status)) return null
  const usage = tag(s, 'usage') ?? ''
  return {
    taskId,
    toolUseId: tag(s, 'tool-use-id') || null,
    status: status as TaskStatus,
    summary: tag(s, 'summary'),
    result: tag(s, 'result'),
    tokens: num(tag(usage, 'subagent_tokens') ?? tag(usage, 'total_tokens')),
    toolUses: num(tag(usage, 'tool_uses')),
    durationMs: num(tag(usage, 'duration_ms')),
    agentCount: num(tag(usage, 'agent_count')),
    agentsError: num(tag(usage, 'agents_error')),
  }
}

/** A tool call's own text from a launcher's input, when it's a plain string. */
const str = (v: unknown) => (typeof v === 'string' && v.trim() ? v.trim() : null)

/**
 * What a launcher's result ([r], its toolUseResult) links: the agent or workflow run it started,
 * an agent it woke (SendMessage), a workflow it stopped (TaskStop), an Agent call that failed. [ev]
 * is the call, its result already applied; [script] the workflow's untrimmed script when known (its
 * phases).
 */
export function resultLinks(ev: ToolEv | undefined, toolUseId: string, r: any, ts: number, script?: unknown): AgentLink[] {
  // Its result is an error (often only a string): a foreground agent ends with no notification.
  if (ev && AGENT_TOOLS.has(ev.name) && ev.status === 'error') return [{ kind: 'failed', ts, toolUseId }]
  if (!r || typeof r !== 'object' || Array.isArray(r)) return stopLink(ev, ts)
  const name = ev?.name ?? ''
  if (typeof r.agentId === 'string' && (AGENT_TOOLS.has(name) || (!ev && (launched(r) || r.status === 'completed') && typeof r.prompt === 'string'))) {
    const done = r.status === 'completed'
    return [{
      kind: 'agent', ts, toolUseId, agentId: r.agentId,
      label: str(r.description) ?? str(ev?.input.description),
      type: str(r.agentType) ?? str(ev?.input.subagent_type),
      model: str(r.resolvedModel),
      background: r.isAsync === true || launched(r) || ev?.input.run_in_background === true,
      report: done
        ? { result: blocksText(r.content) || null, tokens: typeof r.totalTokens === 'number' ? r.totalTokens : null, toolUses: typeof r.totalToolUseCount === 'number' ? r.totalToolUseCount : null, durationMs: typeof r.totalDurationMs === 'number' ? r.totalDurationMs : null }
        : null,
    }]
  }
  if (name === 'Workflow' && (typeof r.runId === 'string' || typeof r.taskId === 'string')) {
    return [{
      kind: 'workflow', ts, toolUseId,
      runId: typeof r.runId === 'string' ? r.runId : null,
      taskId: typeof r.taskId === 'string' ? r.taskId : null,
      name: str(r.workflowName) ?? (ev ? ev.summary : null),
      summary: str(r.summary),
      phases: workflowPhases(script),
    }]
  }
  if (typeof r.resumedAgentId === 'string') return [{ kind: 'resume', ts, agentId: r.resumedAgentId }]
  if (typeof r.task_id === 'string' && (r.task_type === 'local_workflow' || r.task_type === 'local_agent' || name === 'TaskStop')) return [{ kind: 'stop', ts, taskId: r.task_id }]
  return stopLink(ev, ts)
}

/** TaskStop's own input names the task it stopped, whatever shape its result has (often a string). */
function stopLink(ev: ToolEv | undefined, ts: number): AgentLink[] {
  const id = ev?.name === 'TaskStop' && ev.status !== 'error' ? (ev.input.task_id ?? ev.input.shell_id) : null
  return typeof id === 'string' && id ? [{ kind: 'stop', ts, taskId: id }] : []
}

/**
 * A launcher's call ended (its agent or workflow did): it stops running, with its time, and its
 * summary as output when it failed. A stop isn't a failure. Whether it changed.
 */
export function closeTool(ev: ToolEv, end: Pick<TaskEnd, 'status' | 'summary' | 'durationMs'>, ts: number): boolean {
  if (ev.status !== 'running') return false
  ev.status = end.status === 'failed' ? 'error' : 'done'
  ev.output = end.status === 'completed' ? null : (end.summary ?? (end.status === 'failed' ? 'Failed' : 'Stopped'))
  ev.durationMs = end.durationMs ?? Math.max(0, ts - ev.ts)
  return true
}

// ── Pasted text ──────────────────────────────────────────────────────────
// Claude Code 2.1.299 logs pasted text (a long or multi-line paste, the phone's prompts too:
// the daemon types them as pastes) in full, between tags that carry the same id at both ends,
// with what was typed around it kept:
//   typed\n\n<pasted_content id="d6ee">\n…the paste…\n</pasted_content id="d6ee">\n\nmore typed
// in user entries, queue entries and queued prompts alike. Tags the owner typed or pasted
// himself are escaped there ("<\pasted_content"), so only Claude Code's own read as a paste.

const PASTE_OPEN = /<pasted_content id="([0-9a-f]{4,16})">\n/g
const PASTE_FIRST = /^\s*<pasted_content id="[0-9a-f]{4,16}">\n/
// Where a one-line paste stays on the line it was pasted into: after a space (or a bracket or
// quote opened after one), and before a space or the end (or punctuation and then one).
const INLINE_BEFORE = /(^|\s)[([{"'`]*$/
const INLINE_AFTER = /^[)\]}"'`.,;:!?]*(\s|$)/

type Piece = { text: string; pasted: boolean }

/**
 * A prompt split into what was typed and what was pasted, read the way Claude Code reads it
 * back: an opening tag on a line of its own, the paste up to the first closing tag with the
 * same id, and up to two line breaks either side belonging to the tags. null when it has none.
 */
function pastePieces(s: string): Piece[] | null {
  const out: Piece[] = []
  let done = 0
  const open = new RegExp(PASTE_OPEN)
  for (let m = open.exec(s); m; m = open.exec(s)) {
    const from = m.index + m[0].length
    const close = `\n</pasted_content id="${m[1]}">`
    // An empty paste shares its line break with the opening tag.
    const end = s.indexOf(close, from - 1)
    // An opening tag that never closes isn't a paste, and neither is anything after it.
    if (end < 0) break
    let start = m.index
    for (let k = 0; k < 2 && start > done && s[start - 1] === '\n'; k++) start--
    if (start > done) out.push({ text: s.slice(done, start), pasted: false })
    out.push({ text: s.slice(from, Math.max(from, end)), pasted: true })
    done = end + close.length
    for (let k = 0; k < 2 && s[done] === '\n'; k++) done++
    open.lastIndex = done
  }
  if (!out.length) return null
  if (done < s.length) out.push({ text: s.slice(done), pasted: false })
  return out
}

/**
 * A prompt with each paste in place of its tags, as the terminal shows it: a paste with line
 * breaks on lines of its own, a one-line paste inline where spaces already set it apart from
 * the typed text around it. Text without Claude Code's tags comes back as it was, so reading a
 * prompt twice changes nothing. (The escaped "<\pasted_content" stays escaped for that reason.)
 */
export function unwrapPastes(s: string): string {
  const pieces = s.includes('<pasted_content id="') ? pastePieces(s) : null
  if (!pieces) return s
  let out = ''
  // What comes next starts a line of its own.
  let newLine = false
  for (const [i, p] of pieces.entries()) {
    const next = pieces[i + 1]
    if (!p.pasted) {
      let t = p.text
      if (newLine) t = t.replace(/^\s+/, '')
      if (next?.pasted && next.text.includes('\n')) t = t.replace(/\s+$/, '')
      if (!t) continue
      out += (newLine && out ? '\n' : '') + t
      newLine = false
      continue
    }
    const lines = p.text.includes('\n')
    if (out && (lines || newLine || !INLINE_BEFORE.test(out))) out += '\n'
    out += p.text
    const after = next?.text ?? ''
    newLine = lines || !!next?.pasted || /^\s\s/.test(after) || !INLINE_AFTER.test(after)
  }
  return out
}

// ── What the owner typed, as Claude Code records it ──────────────────────
// A slash command is a record of tags rather than the text typed, its output a user entry of
// its own (or a system entry), and "!" mode has its own tags; a paste is wrapped (above).
// docs/PROTOCOL.md, "Reading transcripts", lists the shapes.

/** A user entry's text, read: a prompt, a command, or a command's output. null = not shown. */
export type UserText =
  | { kind: 'prompt'; text: string }
  | { kind: 'command'; command: Command }
  | { kind: 'output'; text: string; tone: 'info' | 'error' }

// Claude Code's notes to the model, its interrupt marker, and "Caveat:" from before it wrapped
// that in a tag.
const HIDDEN_PREFIXES = ['<system-reminder>', '<local-command-caveat>', '<task-notification>', 'Caveat:', '[Request interrupted']

/** The text inside <tag>…</tag>: from the first opening tag to the last closing one. */
function inside(s: string, tag: string): string | null {
  const open = `<${tag}>`
  const a = s.indexOf(open)
  const b = s.lastIndexOf(`</${tag}>`)
  return a >= 0 && b >= a + open.length ? s.slice(a + open.length, b) : null
}

// Colours and cursor moves (CSI), titles and links (OSC), and the two-byte escapes.
const ANSI = /\x1b\[[0-?]*[ -\/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)?|\x1b[@-Z\\-_]/g
const stripAnsi = (s: string) => s.replace(ANSI, '')

const ENTITIES: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', '#39': "'" }
/** "!" output is HTML-escaped inside its tags; one pass, so "&amp;lt;" stays "&lt;". */
const unescapeHtml = (s: string) => s.replace(/&(amp|lt|gt|quot|#39);/g, (_, e: string) => ENTITIES[e]!)

// The tags a command record is made of, as Claude Code reads them back.
const RECORD_TAGS = ['command-message', 'command-name', 'command-args', 'skill-format', 'command-contents']
const startsRecord = (t: string) => RECORD_TAGS.some((tag) => t.startsWith(`<${tag}>`))

function skipSpace(s: string, i: number): number {
  while (i < s.length && /\s/.test(s[i]!)) i++
  return i
}

/**
 * A command record, read the way Claude Code reads it: tags from RECORD_TAGS, each at most
 * once, in any order, only whitespace between them. A body ends at its first closing tag,
 * except <command-args> when it comes last: the args are the owner's raw text, '<' and all,
 * so they run to the end. null when the text is anything else, such as the owner pasting the
 * tags into a prompt.
 */
function parseRecord(t: string): { name: string; args: string } | null {
  const tag = /<([a-z-]+)>([\s\S]*?)<\/\1>/y
  const lastArgs = /<command-args>([\s\S]*)<\/command-args>\s*$/y
  const tags = new Map<string, string>()
  for (let i = skipSpace(t, 0); i < t.length; ) {
    lastArgs.lastIndex = tag.lastIndex = i
    const a = lastArgs.exec(t)
    const m = a ? null : tag.exec(t)
    const name = a ? 'command-args' : m?.[1]
    const body = a ? a[1] : m?.[2]
    if (name === undefined || body === undefined || !RECORD_TAGS.includes(name) || tags.has(name)) return null
    tags.set(name, body)
    i = skipSpace(t, a ? t.length : tag.lastIndex)
  }
  const name = tags.get('command-name')?.trim()
  const format = tags.get('skill-format')
  if (!name || (format ?? 'true') !== 'true') return null
  return { name, args: tags.get('command-args')?.trim() ?? '' }
}

/**
 * A command record as an event's command. Claude Code's own commands start with the name,
 * skills and prompt commands with the message. A name without a leading "/" is a skill the
 * model loaded itself, not something the owner ran: not shown.
 */
function recordCommand(t: string): UserText | null | undefined {
  const r = parseRecord(t)
  if (!r) return undefined
  if (!r.name.startsWith('/')) return null
  return { kind: 'command', command: { name: r.name, args: r.args, kind: t.startsWith('<command-name>') ? 'local' : 'prompt' } }
}

/** A built-in command's output (<local-command-stdout>, <local-command-stderr>), the end kept when long. */
function localOutput(t: string): UserText | null {
  const out = stripAnsi(inside(t, 'local-command-stdout') ?? '').trim()
  const err = stripAnsi(inside(t, 'local-command-stderr') ?? '').trim()
  const text = [out, err].filter(Boolean).join('\n')
  return text ? { kind: 'output', text: tail(text, 8000), tone: err || out.startsWith('Command failed') ? 'error' : 'info' } : null
}

/** "!" mode's output: stdout, then stderr, then a failing exit code, the end kept when long. */
function bashOutput(t: string): UserText | null {
  const body = (tag: string) => {
    const s = inside(t, tag) ?? ''
    // Output too big for the transcript is saved to a file and not escaped.
    return stripAnsi(s.startsWith('<persisted-output>') ? s.replace(/<\/?persisted-output>/g, '') : unescapeHtml(s)).trim()
  }
  const out = body('bash-stdout')
  const err = body('bash-stderr')
  const code = Number(/<bash-exit-code>\s*(-?\d+)\s*<\/bash-exit-code>/.exec(t)?.[1] ?? 0)
  const text = [out, err, code ? `exit ${code}` : ''].filter(Boolean).join('\n')
  return text ? { kind: 'output', text: tail(text, 4000), tone: code || err.startsWith('Command failed') ? 'error' : 'info' } : null
}

/**
 * What a user entry's content (its text, or its blocks) is. Like Claude Code, a command, a "!"
 * line or an output is read from the last text block alone: pictures pasted with a command
 * are blocks of their own, and are dropped with it. Anything else is a prompt, tags and all
 * ("<div>…"), pictures marked "[image]", pasted text in place of its tags (the phone matches
 * its copy by text).
 */
export function classifyUserText(content: unknown): UserText | null {
  const blocks = textBlocks(content)
  const last = (blocks.at(-1) ?? '').trim()
  // A command record, from the last text block back, as Claude Code finds one to display. Tags
  // that aren't a whole record (the owner pasted them into a prompt) are a prompt, below.
  for (let i = blocks.length - 1; i >= 0; i--) {
    const b = blocks[i]!.trim()
    const record = startsRecord(b) ? recordCommand(b) : undefined
    if (record !== undefined) return record
  }
  if (last.startsWith('<local-command-stdout>') || last.startsWith('<local-command-stderr>')) return localOutput(last)
  if (last.startsWith('<bash-input>') && last.endsWith('</bash-input>')) {
    const line = unwrapPastes(last.slice('<bash-input>'.length, -'</bash-input>'.length)).trim()
    return line ? { kind: 'command', command: { name: '!', args: line, kind: 'bash' } } : null
  }
  if (last.startsWith('<bash-stdout>') || last.startsWith('<bash-stderr>')) return bashOutput(last)
  // Claude Code's own notes are told apart before a paste's tags come off: what the owner
  // pasted may start like one.
  const raw = blocksText(content).trim()
  if (!raw || HIDDEN_PREFIXES.some((p) => raw.startsWith(p))) return null
  const t = unwrapPastes(raw).trim()
  return t ? { kind: 'prompt', text: t } : null
}

// ── Titles ───────────────────────────────────────────────────────────────
// Command args are recorded as typed, so "/telegram:configure <bot token>" would put the
// token in a session's title, which lists and notifications show. Titles mask anything that
// looks like a key; the chat keeps the text as typed (the phone matches its copy by text).
const SECRETS = [
  /\b\d{6,}:[A-Za-z0-9_-]{30,}/g, // Telegram bot tokens
  /\bsk-[A-Za-z0-9_-]{20,}/g, // Anthropic, OpenAI and similar API keys
  /\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})/g, // GitHub tokens
  /\bxox[abeprs]-[A-Za-z0-9-]{10,}/g, // Slack tokens
  /\b(?:AKIA|ASIA)[0-9A-Z]{16}\b/g, // AWS access key ids
]
const LONG_RUN = /[A-Za-z0-9+/=_-]{32,}/g
// Credentials a command line carries (the widget masks the same: WidgetModel.kt, Secrets). Every
// run is bounded, so a long line of key-like text stays linear to scan.
/** A password in a URL: "https://user:pass@host". */
const USERINFO = /:\/\/[^/\s:@'"]{1,200}:[^@\s'"]{1,200}@/g
/**
 * One curl or wget command: not past an unquoted ";", "&", "|" or line break, so "docker run -u
 * 1000:1000" (after a curl, or anywhere else) is left alone. Quoted strings count whole, since a
 * ";", "&" or "|" in them ("…?a=1&b=2", "charset=utf-8; …") ends nothing, and so do escaped
 * characters (a line continued with "\"). Inside double quotes an escaped quote doesn't close them
 * (a JSON body, "{\"text\": \"$MSG\"}"), and a quote that can't be closed is read as a plain
 * character: the scan must never stop short of a "-u" further on. A quoted string is read whole up
 * to 600 characters, as much as the widget masks of a text (WidgetModel.kt, Secrets.WINDOW), so
 * the two agree on all it shows: a shorter bound let a long body with a "&" in it end the scan
 * inside it. Nothing follows any repeat, so none is ever given back: the widget's possessive
 * repeats match the same.
 */
const FETCH = /\b(?:curl|wget)\b(?:"(?:[^"\\]|\\[\s\S]){0,600}"|'[^']{0,600}'|\\[\s\S]|[^;&|\n"'\\]|["']){0,600}/g
/**
 * Within [FETCH], the value of each flag that carries credentials: curl's "-u user:pass" ("-su
 * user:pass", "-uuser:pass", "--user=…"), "-U" (its proxy's) and "--oauth2-bearer", and wget's
 * "--user". The value is one shell word: plain, quoted or escaped characters, an escaped quote
 * inside double quotes included (-u "admin:p\"w"). Before the u, only curl's flags that take no
 * value can share the dash: in "-XPUT" the U is the method's.
 */
const FETCH_FLAG =
  /((?:\s|\\\n)(?:-[sSfLkvigGIjJlnNOpqRaBZM0-9#]{0,6}[uU](?:\s{1,8})?|--(?:user|proxy-user|oauth2-bearer)(?:\s{1,8}|=)))(?:"(?:[^"\\]|\\[\s\S]){0,200}"|'[^']{0,200}'|\\.|[^\s"';&|\\]){1,100}/g
/**
 * A value after an obvious key name ("TOKEN=…", "X-Api-Key: …", "\"api_key\": …", "--password …";
 * the plain word "auth" isn't one, AUTH_TOKEN, AUTHORIZATION, X-Auth-Key and BASIC_AUTH are), or
 * after a bare "Bearer". An Authorization scheme after the name ("Authorization: Bearer …") stays
 * with it, so the token itself goes. Curly quotes are never part of a value: an opening one right
 * after the "=" or ":" stays with the name (PASSWORD=“•••”), and a quoted pattern keeps its
 * closing one. Groups: 1 what stays, 2 the scheme after a name, 3 "Bearer" when it's bare, 4 a
 * quote around the value, 5 the value, which credential() judges. The widget has the same rule
 * (WidgetModel.kt, Secrets).
 */
const NAMED =
  /((?:\b(?:[A-Z0-9_-]{0,40}(?:TOKEN|SECRET|PASSWORD|PASSWD|API[-_]?KEY|AUTHORIZATION)[A-Z0-9_-]{0,40}|[A-Z0-9_-]{0,40}[_-]AUTH(?:[_-][A-Z0-9_-]{0,40})?|AUTH[_-][A-Z0-9_-]{0,40})["']?\s{0,8}[=:]\s{0,8}[\u201c\u2018]?|(?<![\w-])--(?:token|password|api-key|secret|http-password|proxy-password)(?:=|\s{1,8}))(?:(Bearer|Basic|Token|Bot|Digest|OAuth|ApiKey)\s{1,8})?|\b(Bearer)\s{1,8})(["']?)([^\s"'\u201c\u201d\u2018\u2019]{1,400})\4/gi
/** Punctuation that ends a sentence rather than a key ("token: abc12345."): left in view. */
const TRAIL = '.,;:!?)]}>'
const TOKEN_CHARS = /^[A-Za-z0-9._~+/=-]+$/
/** A name that ends in the plural "tokens" ("tokens: ", "\"output_tokens\": "), as [NAMED]'s group 1 keeps it. */
const TOKENS_NAME = /tokens["']?\s{0,8}[=:]\s{0,8}[\u201c\u2018]?$/i
/** A number, in groups or not ("12,345,678", "1.5", "1_000_000"). */
const COUNT = /^[0-9][0-9,._]*$/

/**
 * A value that looks like a credential rather than a word: 8 or more characters with a digit
 * ("hunter2pass"), or 16 or more token characters in both cases (base64, a random password).
 * Right after a scheme that follows a key's name ("Authorization: Basic …") it is one by
 * construction: any 8 or more token characters. After a bare "Bearer", 16 or more token
 * characters with a digit or both cases ("Bearer of bad news" stays).
 */
function credential(v: string, after: 'name' | 'scheme' | 'bare'): boolean {
  const digit = /[0-9]/.test(v)
  const mixed = /[a-z]/.test(v) && /[A-Z]/.test(v)
  const token = TOKEN_CHARS.test(v)
  if (after === 'bare') return v.length >= 16 && token && (digit || mixed)
  if (after === 'scheme' && v.length >= 8 && token) return true
  return (v.length >= 8 && digit) || (v.length >= 16 && token && mixed)
}

/**
 * A long run of letters and digits that switches between lower case, upper case and digits as
 * often as random text does (a key, a hash, base64), unlike words, a path, a model name or a
 * long identifier. Random text that long all but always has a digit.
 */
function looksRandom(run: string): boolean {
  if (/^[0-9a-fA-F]+$/.test(run)) return true
  if (!/[0-9]/.test(run)) return false
  let switches = 0
  let prev = ''
  for (const ch of run) {
    const c = ch >= '0' && ch <= '9' ? 'd' : ch >= 'a' && ch <= 'z' ? 'l' : ch >= 'A' && ch <= 'Z' ? 'u' : ''
    if (!c) continue
    if (prev && c !== prev) switches++
    prev = c
  }
  return switches * 3 >= run.length
}

/**
 * A title with token-like text replaced by "•••": bot tokens, API keys, long hex or base64, a URL's
 * password, curl's and wget's credentials ("-u user:pass" in a curl or wget command), a credential
 * after a key's name ("X-Api-Key: …", "TOKEN=…", "Authorization: Bearer …") and a bare "Bearer
 * <token>". The widget masks the same (WidgetModel.kt, Secrets).
 */
export function maskSecrets(s: string): string {
  let out = s
  for (const re of SECRETS) out = out.replace(re, '•••')
  out = out.replace(USERINFO, '://•••@')
  out = out.replace(FETCH, (cmd) => cmd.replace(FETCH_FLAG, (_m, flag: string) => `${flag}•••`))
  out = out.replace(NAMED, (m, keep: string, scheme: string | undefined, bare: string | undefined, _quote: string, value: string) => {
    let n = value.length
    while (n > 0 && TRAIL.includes(value[n - 1]!)) n--
    const core = value.slice(0, n)
    // A count of tokens isn't one ("tokens: 12,345,678"): titles about usage stay readable.
    if (!scheme && !bare && TOKENS_NAME.test(keep) && COUNT.test(core)) return m
    return credential(core, bare ? 'bare' : scheme ? 'scheme' : 'name') ? `${keep}•••${value.slice(n)}` : m
  })
  return out.replace(LONG_RUN, (run) => (looksRandom(run) ? '•••' : run))
}

/** A command as the owner typed it, which is how the phone matches it to what it sent. */
export function commandText(c: Command): string {
  return c.kind === 'bash' ? `!${c.args}` : `${c.name} ${c.args}`.trim()
}

/**
 * The command in a prompt the phone sent to an Agent SDK session ("/effort high", "!ls"),
 * shaped like one read from a transcript so both kinds of session look the same. `builtin`
 * says whether a name (without its "/") is one of Claude Code's own; null when not known.
 */
export function typedCommand(text: string, builtin: (name: string) => boolean | null): Command | null {
  const t = text.trim()
  if (t.startsWith('!')) {
    const line = t.slice(1).trim()
    return line ? { name: '!', args: line, kind: 'bash' } : null
  }
  // "/name args", but not a path ("/Users/…").
  const m = /^\/([^\s/]+)(?:\s+([\s\S]*))?$/.exec(t)
  if (!m) return null
  return { name: `/${m[1]}`, args: (m[2] ?? '').trim(), kind: builtin(m[1]!) ? 'local' : 'prompt' }
}

/**
 * A prompt as the phone shows it: pasted text in place of its tags, and the files attached from
 * the phone apart from the text.
 */
export function userEvent(id: string, ts: number, prompt: string): Ev {
  // A continuation after a limit (resume.ts) isn't the owner speaking: a slim row says it carried on.
  const auto = autoContinueOf(prompt)
  if (auto) return { id, kind: 'notice', ts, text: auto === 'limit' ? 'Continued by itself after the limit' : 'Continued after the limit', tone: 'info' }
  const { text, files } = splitAttachments(unwrapPastes(prompt))
  return files.length ? { id, kind: 'user', ts, text, files } : { id, kind: 'user', ts, text }
}
/** What a prompt is called (a session's title): its text, or the first attached file's name; keys masked. */
export function promptTitle(prompt: string): string {
  const plain = unwrapPastes(prompt)
  const { text, files } = splitAttachments(plain)
  return maskSecrets(text || files[0]?.name || plain)
}

export type ParseState = {
  events: Ev[]
  byId: Map<string, Ev>
  /**
   * Earlier events a later line changed in place (a command found to be a skill), for the
   * reader to send again; it clears the set once it has.
   */
  changed: Set<string>
  title: string | null
  /** The first thing the session is about: a prompt, a skill given a task, a "!" command. */
  firstPrompt: string | null
  /** The first other command ("/effort high", "/clear"): a title only when there's nothing else. */
  firstCommand: string | null
  lastText: string | null
  cwd: string | null
  /** The model the main agent uses now: its last reply's, or what a /model since set (its output says). */
  model: string | null
  /** When [model] was last said (the reply's or the /model output's time; 0: never). */
  modelAt: number
  lastTs: number
  /**
   * Prompts Claude Code holds in its queue (typed while Claude works), oldest first. Ones Claude
   * has taken wait in [absorbed] until their event is made; sessions show both ([queueNow]).
   */
  queue: QueueEntry[]
  /** The queue changed since the reader last looked; it clears this once it has. */
  queueChanged: boolean
  queueSeq: number
  /**
   * Dequeue entries whose prompt hasn't come yet. Claude Code logs one as the queue's oldest
   * prompt starts a turn (several back to back for task notifications), and that prompt's user
   * entry follows it.
   */
  drained: number
  /**
   * Prompts Claude has taken from the queue mid-turn, by the id their event will have, until the
   * queued_command attachment that carries them is read: the phone send each came from, its
   * text, its queue entry (still shown, so it doesn't vanish before its event comes; null once
   * not), when it was taken, and when the reader saw that ([flushAbsorbed]).
   */
  absorbed: Map<string, Absorbed>
  /**
   * Which phone send a prompt queued at [ts] came from, as the reader knows it (null: typed on the
   * Mac). [content] null: Claude Code logged the prompt without its text.
   */
  claim: ((content: string | null, ts: number) => Claim | null) | null
  /** Which phone send a prompt that started a turn at [ts], rewritten by a hook of Claude Code's, came from. */
  claimRewritten: ((ts: number) => Claim | null) | null
  /**
   * An agent's own transcript (agents.ts reads one for the phone): its entries are all marked
   * isSidechain, and how it hands its report back is its last word.
   */
  sidechain: boolean
  /** Agents and workflows linked to their calls, and task ends, in order, for the reader to drain. */
  links: AgentLink[]
  /** Task notifications read (task id and status), with whether one carried usage: each copy counts once. */
  taskSeen: Map<string, boolean>
  /** Workflow calls' untrimmed scripts, until their results link the run (its phases are in it). */
  scripts: Map<string, string>
  /** When the latest turn started, as logged: a prompt, or a hidden one (a task notification). */
  turnAt: number
  /**
   * The session runs on the built-in provider (the owner's Claude login): an API error says
   * something about his account, not another provider's (the reader sets it).
   */
  builtIn: boolean
  /**
   * When the session moved to the provider it runs on now (0: it never moved), and whether the
   * one before was the built-in one: an API error written before then was that provider's.
   */
  movedAt: number
  builtInBefore: boolean
  /**
   * The line being read is history this session inherited: a fork's copy of its parent's (Claude
   * Code marks it `forkedFrom`), or written before the session moved to its provider. It's shown,
   * but tells the account nothing (it was told then, or it's another provider's) and doesn't stop
   * this session.
   */
  inherited: boolean
  /**
   * The API error that stopped Claude last and that nothing has undone since (a real reply, the
   * limit's reset, a sign-in): the event's id, when, and what Session.blocked says.
   */
  blocker: { id: string; at: number; blocked: Blocked } | null
  /** What the lines tell the account (account.ts), in order, for the reader to drain. */
  account: AccountNote[]
  /**
   * When the request behind the next reply was sent, as near as the transcript tells: the last
   * line that wasn't Claude's (a prompt, tool results). A reply proves the account worked then.
   */
  askedAt: number
}

/** What a transcript tells the account: an API error, a real reply, a sign-in, a limit notice. */
export type AccountNote =
  | { kind: 'blocker'; at: number; blocker: Blocker }
  /** A real reply from [model] to a request sent at [at]. */
  | { kind: 'healthy'; at: number; model: string }
  | { kind: 'login'; at: number }
  | { kind: 'notice'; at: number; notice: LimitNotice }

export function newParseState(sidechain = false): ParseState {
  return {
    events: [], byId: new Map(), changed: new Set(), title: null, firstPrompt: null, firstCommand: null, lastText: null, cwd: null, model: null, modelAt: 0, lastTs: 0,
    queue: [], queueChanged: false, queueSeq: 0, drained: 0, absorbed: new Map(), claim: null, claimRewritten: null,
    sidechain, links: [], taskSeen: new Map(), scripts: new Map(), turnAt: 0,
    builtIn: true, movedAt: 0, builtInBefore: true, inherited: false, blocker: null, account: [], askedAt: 0,
  }
}

/**
 * Note something for the account. Replies come by the thousand in a long history and only the
 * latest counts, so they fold into one; a parser nobody drains (a title read, an agent's
 * transcript) keeps the last 200.
 */
function noteAccount(st: ParseState, n: AccountNote): void {
  if (st.inherited) return
  const last = st.account.at(-1)
  if (n.kind === 'healthy' && last?.kind === 'healthy') {
    if (n.at >= last.at) st.account[st.account.length - 1] = n
    return
  }
  st.account.push(n)
  if (st.account.length > 200) st.account.splice(0, st.account.length - 200)
}

/**
 * An API error Claude Code wrote as if Claude had said it (a "<synthetic>" reply with
 * isApiErrorMessage): a notice, never Claude's words, and the session's blocker when it stops
 * Claude. [event] false: a subagent's, which only tells the account.
 */
function feedApiError(st: ParseState, o: any, ts: number, event: boolean): void {
  const text = blocksText(o.message?.content)
  const builtIn = ts < st.movedAt ? st.builtInBefore : st.builtIn
  const b = classifyApiError({ error: o.error, apiError: o.apiError, status: o.apiErrorStatus, text, rateLimitInfo: o.apiErrorParams?.rate_limit_info, transient: o.apiErrorIsTransient, builtIn, at: ts })
  noteAccount(st, { kind: 'blocker', at: ts, blocker: b })
  if (!event) return
  const id = o.uuid ?? `t:${o.message?.id ?? ts}`
  put(st, { id, kind: 'notice', ts, text: cleanText(text, 1000) || b.text, tone: b.tone, ...(b.kind ? { blocker: b.kind } : {}) })
  if (b.kind && !st.inherited) st.blocker = { id, at: ts, blocked: { kind: b.kind, text: b.text, resetsAt: b.resetsAt, autoResumeAt: null } }
}

/** A limit notice: Claude Code will carry on by itself at a time, won't any more, or does now. */
function feedLimitNotice(st: ParseState, n: LimitNotice, ts: number): void {
  noteAccount(st, { kind: 'notice', at: ts, notice: n })
  const b = st.blocker?.blocked
  if (!b || (b.kind !== 'limit' && b.kind !== 'model' && b.kind !== 'no_credit')) return
  if (n.kind === 'reset') st.blocker = null
  else st.blocker = { ...st.blocker!, blocked: { ...b, autoResumeAt: n.kind === 'armed' ? (n.autoResumeAt ?? b.resetsAt) : null } }
}

/**
 * A task notification read from any of its copies (a queue entry, a hidden user entry, a queued
 * command): a link for agents.ts once per task and status (again if this copy brings usage the
 * first lacked), and the launcher's call stops running.
 */
function noteTaskEnd(st: ParseState, end: TaskEnd | null, ts: number): void {
  if (!end) return
  const key = `${end.taskId}:${end.status}`
  const usage = end.tokens != null || end.durationMs != null
  const seen = st.taskSeen.get(key)
  if (seen === true || (seen === false && !usage)) return
  st.taskSeen.set(key, usage)
  if (st.taskSeen.size > 2000) st.taskSeen.delete(st.taskSeen.keys().next().value!)
  // Shell commands' ends ("b…") are the Bash cards' business.
  if (!/^[aw]/.test(end.taskId)) return
  st.links.push({ kind: 'end', ts, ...end })
  const ev = end.toolUseId ? st.byId.get(end.toolUseId) : undefined
  if (ev?.kind === 'tool' && isLauncher(ev.name) && closeTool(ev, end, ts)) st.changed.add(ev.id)
}

/** The report an agent hands back as its last tool call: the hand-back's message, or a workflow agent's structured output. */
export function handbackText(name: string, input: any): string | null {
  if (name === 'SubagentHandback') return typeof input?.message === 'string' ? input.message : null
  if (name === 'StructuredOutput') return structuredText(input)
  return null
}

/** A structured result as one text: its `summary` when it has one, else its JSON. */
export function structuredText(v: unknown): string | null {
  if (v == null) return null
  if (typeof v === 'string') return v
  if (typeof v === 'object' && !Array.isArray(v) && typeof (v as any).summary === 'string') return (v as any).summary
  try {
    return JSON.stringify(v, null, 2)
  } catch {
    return null
  }
}

/** What a session is called when it has no custom or AI title, keys masked. */
export function fallbackTitle(st: ParseState): string | null {
  const t = st.firstPrompt ?? st.firstCommand
  return t && maskSecrets(t)
}

function put(st: ParseState, ev: Ev): void {
  if (!st.byId.has(ev.id)) st.events.push(ev)
  st.byId.set(ev.id, ev)
}

/**
 * A prompt, command or command output from a user entry (or a system one, for commands, or a
 * queued prompt). `parent` is the entry's parent: for an output, the command it belongs to.
 * `clientId`: the phone send a prompt or command came from, when known.
 */
function feedUserText(st: ParseState, id: string, parent: unknown, ts: number, u: UserText, clientId?: string | null): void {
  // An entry seen before changes nothing: a transcript read again, or a queued prompt that
  // also has a user entry (they share an id).
  if (st.byId.has(id)) return
  // A continuation after a limit is a slim row (userEvent), and never the session's title.
  if (u.kind === 'prompt' && autoContinueOf(u.text)) {
    put(st, userEvent(id, ts, u.text))
    return
  }
  if (u.kind === 'prompt') {
    const ev = userEvent(id, ts, u.text)
    if (clientId && ev.kind === 'user') ev.clientId = clientId
    put(st, ev)
    if (!st.firstPrompt) st.firstPrompt = promptTitle(u.text)
  } else if (u.kind === 'command') {
    const c = u.command
    const text = commandText(c)
    put(st, { id, kind: 'user', ts, text, command: c, ...(clientId ? { clientId } : {}) })
    // "/effort high" or a bare "/review" says nothing about what the session is for.
    if (c.kind === 'bash') {
      if (!st.firstPrompt) st.firstPrompt = `! ${c.args}`
    } else if (c.kind === 'prompt' && c.args) {
      if (!st.firstPrompt) st.firstPrompt = text
    } else if (!st.firstCommand) st.firstCommand = text
  } else {
    const command = typeof parent === 'string' ? { command: parent } : {}
    put(st, { id, kind: 'notice', ts, text: u.text, tone: u.tone, ...command })
    // "/model sonnet" in the terminal: the model changes now, not with the next reply.
    const set = modelSet(u.text)
    if (set && ts >= st.modelAt) {
      st.model = set
      st.modelAt = ts
    }
    // /login finished: the account works again (the check that follows reads the plan).
    if (/^Login successful\b/.test(u.text)) {
      noteAccount(st, { kind: 'login', at: ts })
      if (st.blocker && ['logged_out', 'no_subscription'].includes(st.blocker.blocked.kind)) st.blocker = null
    }
  }
}

/**
 * The model a /model command's output says it set: "Set model to Sonnet 4.5 (claude-sonnet-4-5)"
 * gives the id in brackets, "Set model to sonnet" the name as it's written. Null for anything else
 * ("Kept model as…" changes nothing).
 */
export function modelSet(text: string): string | null {
  const m = /^Set model to (.+)$/m.exec(text.trim())
  if (!m) return null
  const said = m[1]!.trim()
  // The last bracket that reads as an id (no spaces, a dash or a slash): "(claude-opus-5-5[1m])".
  const ids = [...said.matchAll(/\(([^()\s]+)\)/g)].map((x) => x[1]!).filter((x) => /[-/]/.test(x))
  if (ids.length) return ids[ids.length - 1]!
  const name = said.replace(/\s*\([^()]*\)\s*$/, '').trim()
  return name && name.length <= 80 ? name : null
}

// What Claude Code puts first in a skill's body when it runs one.
const SKILL_BODY = 'Base directory for this skill:'

/** The command event [id] ran a skill: it says so from now on, and is sent again. */
function markSkill(st: ParseState, id: string): void {
  const ev = st.byId.get(id)
  if (ev?.kind !== 'user' || !ev.command || ev.command.kind === 'bash' || ev.command.kind === 'skill') return
  ev.command.kind = 'skill'
  st.changed.add(id)
  // A skill given a task names the session, even one recorded in the built-in shape.
  if (ev.command.args && !st.firstPrompt) st.firstPrompt = ev.text
}

// ── Claude Code's queue ──────────────────────────────────────────────────
// A prompt typed while Claude works waits in Claude Code's queue until the step Claude is on
// finishes (it's handed over with the step's tool results), or until the turn ends (it starts
// the next one). The transcript logs each move as a queue-operation entry, written at once,
// while a step's own lines can wait minutes for the step to end: so these entries are what says
// a prompt is queued, and when Claude has it. docs/PROTOCOL.md, "Reading transcripts".

/**
 * A prompt in Claude Code's queue: `content` as typed (@-mentions included, pasted text in place
 * of its tags), `clientId` the phone send it came from. `bash`: a "!" line, which Claude Code
 * queues without its "!" (bash mode keeps it apart), as far as the daemon knows: one the phone
 * sent. `content` is empty for a prompt Claude Code logged without its text (queued while no
 * model turn runs) that no phone send claimed: it keeps its place in the queue, unseen, so the
 * prompts around it are told apart right. `lent`: such a prompt that a phone send being typed
 * then lent its text and clientId to. That is a guess, as a rule right; if the send's own entry
 * is then logged with its text, they move there ([reclaim]).
 */
export type QueueEntry = { id: string; content: string; at: number; clientId: string | null; bash?: boolean; lent?: boolean }
type Absorbed = { clientId: string | null; content: string; entry: QueueEntry | null; ts: number; seenAt: number }
/** A queued prompt as sessions carry it (docs/PROTOCOL.md, Session.queue). */
export type QueuedPrompt = { id: string; text: string; files?: Attachment[]; at: number; clientId: string | null }
/**
 * The phone send a newly queued prompt came from, whether it was a "!" line, and its text as the
 * daemon typed it (for a prompt Claude Code logged without its own).
 */
export type Claim = { clientId: string; bash: boolean; text: string }

/** How a prompt compares with its copies: no whitespace, and no "!" (bash mode keeps it apart). */
export const squash = (s: string) => s.replace(/\s+/g, '').replace(/^!/, '')

/** A queued prompt's text was never logged, and no phone send lent it one. */
const unknown = (e: QueueEntry) => !e.content

/** What a session shows as queued: prompts Claude took whose event isn't made yet, then the queue. */
export function queueNow(st: ParseState): QueueEntry[] {
  const taken = [...st.absorbed.values()].flatMap((a) => (a.entry ? [a.entry] : []))
  return taken.length ? [...taken, ...st.queue] : st.queue
}

/** A prompt Claude took has its event (or never will): it leaves what the session shows. */
function untake(st: ParseState, id: string): Absorbed | undefined {
  const a = st.absorbed.get(id)
  if (!a) return undefined
  st.absorbed.delete(id)
  if (a.entry) st.queueChanged = true
  return a
}

/** A queued prompt as typed, its files apart: a "!" line with its "!", which draws it as one. */
function entryText(e: QueueEntry): { text: string; files: Attachment[] } {
  const { text, files } = splitAttachments(e.content)
  return { text: e.bash && !text.startsWith('!') ? `!${text}` : text, files }
}

/**
 * The queue as a session carries it: the text apart from its files, 300 characters at most. A
 * prompt whose text nobody knows is left out: there is nothing to show for it until it runs.
 */
export function queueView(entries: QueueEntry[]): QueuedPrompt[] {
  return entries.flatMap((e) => {
    if (unknown(e)) return []
    const { text, files } = entryText(e)
    return [{ id: e.id, text: trimText(text, 300) ?? '', ...(files.length ? { files } : {}), at: e.at, clientId: e.clientId }]
  })
}

/** Prompts as a notice names them, without their @-mentions. */
export const unsentPrompts = (entries: QueueEntry[]): Unsent['prompts'] => entries.map((e) => ({ text: entryText(e).text, clientId: e.clientId }))

/** "A queued message wasn’t sent." and the like, for prompts that didn't reach Claude. */
export function unsentText(reason: Unsent['reason'], n: number): string {
  if (reason === 'returned') return n === 1 ? 'A queued message went back to the terminal’s input box.' : `${n} queued messages went back to the terminal’s input box.`
  return n === 1 ? 'A queued message wasn’t sent.' : `${n} queued messages weren’t sent.`
}

/**
 * The owner's own text in a queue entry, pasted text in place of its tags; null for anything
 * else. Task notifications and other sessions' messages are queued as tags; that is told before
 * a paste's tags come off, as a paste the owner made may start with a tag of its own.
 */
function ownText(c: unknown): string | null {
  if (typeof c !== 'string') return null
  const text = unwrapPastes(c)
  if (!text.trim()) return null
  return !c.trimStart().startsWith('<') || (PASTE_FIRST.test(c) && text !== c) ? text : null
}

function takeQueued(st: ParseState, i: number): QueueEntry {
  st.queueChanged = true
  return st.queue.splice(i, 1)[0]!
}

/** The first queued prompt with this text (whitespace aside), or -1. */
function withText(st: ParseState, text: string): number {
  const s = squash(text)
  return st.queue.findIndex((e) => !unknown(e) && squash(e.content) === s)
}

/**
 * The queue entry a prompt Claude Code just handed over came from, taken out of the queue: the
 * first with its text (Claude Code hands out the same prompt typed twice in order), its pictures'
 * "[image]" marks aside (the queue logs the text alone). One that came out of the queue and
 * matches none is the oldest whose text wasn't logged: [drained] (a dequeue was logged for it,
 * whatever its promptSource, which Claude Code 2.1 sets to "typed" or none as often as
 * "queued"), [source] "queued", or an SDK prompt ("sdk"), which is always queued without text.
 * Never a prompt with other text: that would give it that prompt's phone send.
 */
function dequeue(st: ParseState, text: string, source?: unknown, drained = false): QueueEntry | null {
  if (!st.queue.length) return null
  let i = withText(st, text)
  if (i < 0 && text.includes('[image]')) i = withText(st, text.replaceAll('[image]', ''))
  if (i < 0 && (drained || source === 'queued' || source === 'sdk')) i = st.queue.findIndex(unknown)
  return i < 0 ? null : takeQueued(st, i)
}

/** A prompt from the queue has come: one dequeue logged before it is used up. Whether there was one. */
function drain(st: ParseState): boolean {
  if (st.drained <= 0) return false
  st.drained--
  return true
}

/**
 * The phone send lent to a prompt logged without text ([QueueEntry.lent]) whose text is
 * [content], taken back: the send's own entry has now been logged with that text. So the one
 * that borrowed it was another prompt (a task notification queued as the send was typed, say),
 * and is one whose text nobody knows again.
 */
function reclaim(st: ParseState, content: string): Claim | null {
  const s = squash(content)
  const e = st.queue.find((x) => x.lent && squash(x.content) === s)
  if (!e?.clientId) return null
  const claim = { clientId: e.clientId, bash: !!e.bash, text: e.content }
  e.content = ''
  e.clientId = null
  delete e.bash
  delete e.lent
  return claim
}

/**
 * A notice for prompts that left the queue without reaching Claude, naming the ones whose text is
 * known for the phone.
 */
function unsent(st: ParseState, ts: number, reason: Unsent['reason'], entries: QueueEntry[]): void {
  if (!entries.length) return
  put(st, { id: `qn:${ts}:${st.events.length}`, kind: 'notice', ts, text: unsentText(reason, entries.length), tone: reason === 'dropped' ? 'warn' : 'info', unsent: { reason, prompts: unsentPrompts(entries.filter((e) => !unknown(e))) } })
}

/**
 * Prompts Claude took from the queue that the reader saw taken before [before] (its own clock)
 * and whose attachment still isn't written: a step's lines can be held for minutes (after a
 * compaction, say), and the prompt reached Claude all the same, so it shows now, from its queue
 * entry, at the end. Returns the ids of the events made.
 */
export function flushAbsorbed(st: ParseState, before: number): string[] {
  const out: string[] = []
  for (const [id, a] of st.absorbed) {
    if (a.seenAt > before) continue
    untake(st, id)
    const u = classifyUserText(a.content)
    if (u?.kind !== 'prompt' || st.byId.has(id)) continue
    feedUserText(st, id, null, a.ts, u, a.clientId)
    out.push(id)
  }
  return out
}

/** One queue-operation entry: enqueue, remove, popAll, dequeue (its user entry follows). */
function feedQueue(st: ParseState, o: any, ts: number): void {
  const content = ownText(o.content)
  switch (o.operation) {
    case 'enqueue': {
      // A background task's end is queued for Claude at once, before any of its other copies.
      noteTaskEnd(st, parseTaskNotification(o.content), ts)
      if (content) {
        // No send noted with this text: it may be one already lent to a prompt logged without text.
        const claim = st.claim?.(content, ts) ?? reclaim(st, content)
        st.queue.push({ id: `q:${++st.queueSeq}`, content, at: ts, clientId: claim?.clientId ?? null, ...(claim?.bash ? { bash: true } : {}) })
        st.queueChanged = true
        return
      }
      // Content that isn't the owner's (a task notification) is left out.
      if (o.content != null && o.content !== '') return
      // No text: Claude Code logs none for a prompt queued while no model turn runs (behind a "!"
      // line, as a turn ends, as a session starts). It keeps its place in the queue all the same.
      // A phone send being typed into the terminal then is that prompt, as a rule, and lends it
      // its text, so the phone sees its message queued; one typed on the Mac waits unseen.
      const claim = st.claim?.(null, ts) ?? null
      st.queue.push({ id: `q:${++st.queueSeq}`, content: claim?.text ?? '', at: ts, clientId: claim?.clientId ?? null, ...(claim ? { lent: true } : {}), ...(claim?.bash ? { bash: true } : {}) })
      if (claim) st.queueChanged = true
      return
    }
    case 'dequeue':
      // Says nothing more: the prompt's own user entry follows, and names it (feedLine).
      st.drained++
      return
    case 'remove': {
      if (!content) return
      let i = withText(st, content)
      // A prompt that leaves for good (to Claude, or dropped) and matches none is the oldest whose
      // text wasn't logged: the text is its.
      const leaves = o.reason === 'absorbed_mid_turn' || o.reason === 'cleared_on_cancel' || o.reason === 'dropped_by_hook'
      if (i < 0 && leaves) i = st.queue.findIndex(unknown)
      const e = i >= 0 ? takeQueued(st, i) : null
      if (e && unknown(e)) e.content = content
      if (o.reason === 'absorbed_mid_turn') {
        // Claude has it now, between two steps. Its event comes from the queued_command
        // attachment written after the step's last lines (its id, source_uuid, is commandUuid),
        // so it keeps its place after what Claude wrote before it, lines often written just after
        // this one. When they're held up longer, the reader shows it from here (flushAbsorbed).
        const id = typeof o.commandUuid === 'string' ? o.commandUuid : null
        if (id && !st.byId.has(id)) st.absorbed.set(id, { clientId: e?.clientId ?? null, content, entry: e, ts, seenAt: Date.now() })
      } else if (e && (o.reason === 'cleared_on_cancel' || o.reason === 'dropped_by_hook')) {
        unsent(st, ts, 'dropped', [e])
      }
      return
    }
    case 'popAll': {
      if (!st.queue.length) return
      // Back in the input box, for the owner to edit: the prompt with that text, or (several at
      // once, or no text logged) every one the text holds, with those whose text wasn't logged.
      // Not those, though, when the ones with text already make up all of it: they aren't in it
      // (a placeholder that left unseen, or one Claude Code doesn't give back), and the notice
      // would count them.
      const s = content ? squash(content) : null
      const i = content ? withText(st, content) : -1
      const held = (e: QueueEntry) => !unknown(e) && s !== null && s.includes(squash(e.content))
      let back = i >= 0 ? [takeQueued(st, i)] : st.queue.filter((e) => s === null || unknown(e) || held(e))
      const known = back.filter(held)
      if (i < 0 && known.length < back.length && known.map((e) => squash(e.content)).join('') === s) back = known
      if (i < 0 && back.length) {
        st.queue = st.queue.filter((e) => !back.includes(e))
        st.queueChanged = true
      }
      // One whose text wasn't logged, back on its own: the text is its.
      if (content && back.length === 1 && unknown(back[0]!)) back[0]!.content = content
      unsent(st, ts, 'returned', back)
      return
    }
  }
}

/** Feed one transcript JSONL entry. */
export function feedLine(st: ParseState, o: any): void {
  if (!o || typeof o !== 'object') return
  const ts = o.timestamp ? Date.parse(o.timestamp) : st.lastTs
  if (ts) st.lastTs = Math.max(st.lastTs, ts)
  st.inherited = !!o.forkedFrom || (ts > 0 && ts < st.movedAt)
  if (o.cwd) st.cwd = o.cwd
  switch (o.type) {
    case 'custom-title':
      if (o.customTitle) st.title = o.customTitle
      return
    case 'ai-title':
      if (o.aiTitle && !st.title) st.title = o.aiTitle
      return
    case 'system':
      // A subagent's own notes (its compactions too) aren't the session's.
      if (o.isSidechain && !st.sidechain) return
      if (o.subtype === 'compact_boundary') {
        put(st, { id: o.uuid ?? `c:${ts}`, kind: 'notice', ts, text: 'Conversation compacted', tone: 'info' })
        return
      }
      if (o.isMeta || typeof o.content !== 'string') return
      if (o.subtype === 'local_command') {
        // Some built-in commands (/rename, /color, /skills…) record themselves and their output here.
        const u = classifyUserText(o.content)
        if (u && u.kind !== 'prompt') feedUserText(st, o.uuid ?? `u:${ts}`, o.parentUuid, ts, u)
      } else if (o.subtype === 'informational' && (o.level === 'warning' || o.level === 'error')) {
        // "Unknown command: /effot. Did you mean /effort?"
        const text = stripAnsi(o.content).trim()
        // Claude Code giving up on carrying on after a limit warns too ("Automatic continue stopped…").
        const n = text ? classifyNotice(text, ts) : null
        if (n) {
          put(st, { id: o.uuid ?? `i:${ts}`, kind: 'notice', ts, text: n.text, tone: o.level === 'error' ? 'error' : 'warn' })
          feedLimitNotice(st, n, ts)
        } else if (text) put(st, { id: o.uuid ?? `i:${ts}`, kind: 'notice', ts, text, tone: o.level === 'error' ? 'error' : 'warn' })
      } else if (o.subtype === 'informational' && o.level === 'notice') {
        // Of Claude Code's many notice-level lines, only the usage limit's are shown: "Usage limit
        // reached · continuing automatically at 2am", "Automatic continue cancelled", "Usage limit reset".
        const n = classifyNotice(stripAnsi(o.content), ts)
        if (n) {
          put(st, { id: o.uuid ?? `i:${ts}`, kind: 'notice', ts, text: n.text, tone: 'info' })
          feedLimitNotice(st, n, ts)
        }
      }
      return
    case 'user': {
      if (o.isSidechain && !st.sidechain) return
      // A prompt or tool results: the next reply's request goes out after this.
      if (ts) st.askedAt = Math.max(st.askedAt, ts)
      const c = o.message?.content
      if (Array.isArray(c)) {
        const results = c.filter((b: any) => b?.type === 'tool_result')
        for (const b of results) {
          const ev = st.byId.get(b.tool_use_id)
          if (ev && ev.kind === 'tool') applyToolResult(ev, b, o.toolUseResult, ts)
          // The structured result is the entry's one tool result's; with several, a launcher's only.
          if (results.length === 1 || (ev?.kind === 'tool' && (isLauncher(ev.name) || ev.name === 'TaskStop'))) {
            const tool = ev?.kind === 'tool' ? ev : undefined
            for (const l of resultLinks(tool, b.tool_use_id, o.toolUseResult, ts, st.scripts.get(b.tool_use_id))) {
              st.links.push(l)
              // A woken agent ends again with a notification just like its first one: that counts too.
              if (l.kind === 'resume') for (const k of [...st.taskSeen.keys()]) if (k.startsWith(`${l.agentId}:`)) st.taskSeen.delete(k)
            }
            st.scripts.delete(b.tool_use_id)
          }
        }
      }
      if (Array.isArray(c) && c.some((b: any) => b?.type === 'tool_result')) return
      if (o.isMeta) {
        // Never shown, tags or not: the caveat before a command, skill bodies, expanded prompt
        // commands and the model's own skill loads. A skill's body follows the command that
        // ran it, with that command as its parent; the model's own loads come from a tool use.
        if (!o.sourceToolUseID && typeof o.parentUuid === 'string' && textBlocks(c)[0]?.startsWith(SKILL_BODY)) markSkill(st, o.parentUuid)
        else {
          // A prompt Claude Code queued itself (one that carries on once a usage limit resets, a
          // channel's message) is queued like the owner's, and starts its turn as a hidden entry
          // with a promptSource: the dequeue before it was its own. It leaves the queue by its
          // text alone. Other meta entries (the caveat before a command) come from no queue.
          if (o.promptSource) {
            drain(st)
            st.turnAt = Math.max(st.turnAt, ts)
          }
          dequeue(st, unwrapPastes(textBlocks(c).join('\n')))
        }
        return
      }
      // The summary after compaction, which a manual /compact's output repeats.
      if (o.isCompactSummary || o.isVisibleInTranscriptOnly || o.repeatsCompactSummary) return
      const head = typeof c === 'string' ? c : Array.isArray(c) && c[0]?.type === 'text' ? String(c[0].text ?? '') : ''
      if (head.trimStart().startsWith('<task-notification>')) noteTaskEnd(st, parseTaskNotification(head), ts)
      const u = classifyUserText(c)
      if (!u) {
        // A task notification starting a turn: the dequeue before it was its own. Its enqueue
        // had its tags as content as a rule, which the queue leaves out, so it takes no prompt
        // from the queue: one in front whose text wasn't logged is someone else's.
        if (o.promptSource) {
          drain(st)
          st.turnAt = Math.max(st.turnAt, ts)
        }
        return
      }
      if (u.kind !== 'output') st.turnAt = Math.max(st.turnAt, ts)
      const id = o.uuid ?? `u:${ts}`
      // A prompt (or command) from Claude Code's queue, starting a turn of its own: it leaves the
      // queue. Not one that left it when Claude took it mid-turn (it has the same id), nor one
      // shown already. One that follows a dequeue came from the queue whatever it matches, so a
      // prompt whose text wasn't logged leaves with it (a command at session start, say), rather
      // than hide at the front of the queue until the session is idle.
      const taken = untake(st, id)
      const fresh = !taken && u.kind !== 'output' && !st.byId.has(id)
      const q = fresh ? dequeue(st, u.kind === 'prompt' ? u.text : commandText(u.command), o.promptSource, drain(st)) : null
      // Typed while Claude was idle, and changed by a hook of Claude Code's on the way in (a
      // spellcheck plugin): the phone can't tell it by its text any more, so it carries the send's name.
      const rewritten = fresh && !q && u.kind === 'prompt' && o.promptRewritten === true ? st.claimRewritten?.(ts) : null
      feedUserText(st, id, o.parentUuid, ts, u, taken?.clientId ?? q?.clientId ?? rewritten?.clientId)
      return
    }
    case 'attachment': {
      // A prompt the owner typed while Claude worked, handed to Claude between two steps rather
      // than as a turn of its own. Task notifications and other sessions' messages arrive the
      // same way, and aren't the owner's.
      const a = o.attachment
      if ((o.isSidechain && !st.sidechain) || a?.type !== 'queued_command') return
      if (a.commandMode === 'task-notification') {
        // Its usage comes as numbers of their own here.
        const end = parseTaskNotification(typeof a.prompt === 'string' ? a.prompt : blocksText(a.prompt))
        const u = a.usage
        if (end && u && typeof u === 'object') {
          if (typeof u.totalTokens === 'number') end.tokens = u.totalTokens
          if (typeof u.toolUses === 'number') end.toolUses = u.toolUses
          if (typeof u.durationMs === 'number') end.durationMs = u.durationMs
        }
        noteTaskEnd(st, end, ts)
      }
      // Its id is the queued prompt's own uuid (source_uuid), which a user entry for the same
      // prompt carries too, so it shows once whichever comes first.
      const id = typeof a.source_uuid === 'string' ? a.source_uuid : (o.uuid ?? `q:${ts}`)
      // Claude took it from the queue (a remove entry, logged first): whoever's it is, it's here.
      const taken = untake(st, id)
      if (a.isMeta || (a.origin?.kind ?? 'human') !== 'human' || (a.commandMode ?? 'prompt') !== 'prompt') return
      const u = classifyUserText(a.prompt)
      if (u?.kind !== 'prompt' || st.byId.has(id)) return
      // With no remove entry logged, its queue entry leaves now.
      feedUserText(st, id, o.parentUuid, ts, u, taken ? taken.clientId : dequeue(st, u.text)?.clientId)
      return
    }
    case 'queue-operation':
      feedQueue(st, o, ts)
      return
    case 'assistant': {
      // An API error: a notice, not Claude's words. A subagent's (one left in the main
      // transcript) only tells the account.
      if (o.isApiErrorMessage) return feedApiError(st, o, ts, !o.isSidechain || st.sidechain)
      if (o.isSidechain && !st.sidechain) return
      const msg = o.message
      const blocks = Array.isArray(msg?.content) ? msg.content : []
      if (msg?.model && msg.model !== '<synthetic>') {
        st.model = msg.model
        st.modelAt = Math.max(st.modelAt, ts)
        // A real reply: whatever stopped Claude here is over, and the account worked when it was asked.
        if (blocks.length) {
          st.blocker = null
          noteAccount(st, { kind: 'healthy', at: st.askedAt || ts, model: msg.model })
        }
      }
      for (const b of blocks) feedAssistantBlock(st, msg?.id ?? o.uuid, b, ts, false)
      return
    }
  }
}

export function feedAssistantBlock(st: ParseState, msgId: string, b: any, ts: number, streaming: boolean): void {
  if (!b) return
  if (b.type === 'text' && typeof b.text === 'string') {
    const id = `t:${msgId}`
    const prev = st.byId.get(id)
    if (prev && prev.kind === 'assistant') {
      if (!prev.text.includes(b.text)) prev.text = prev.text ? `${prev.text}\n\n${b.text}` : b.text
      prev.streaming = streaming
    } else put(st, { id, kind: 'assistant', ts, text: b.text, streaming })
    if (b.text.trim()) st.lastText = b.text
  } else if (b.type === 'thinking') {
    const id = `th:${msgId}`
    const prev = st.byId.get(id)
    const chars = (b.thinking ?? '').length
    if (prev && prev.kind === 'thinking') prev.chars += chars
    else put(st, { id, kind: 'thinking', ts, chars })
  } else if (b.type === 'tool_use' && b.id) {
    // An agent's report, handed back as its last tool call, reads as what it said last.
    const report = st.sidechain ? handbackText(b.name, b.input) : null
    if (report != null) {
      const id = `t:${b.id}`
      if (!st.byId.has(id)) put(st, { id, kind: 'assistant', ts, text: report, streaming: false })
      if (report.trim()) st.lastText = report
      return
    }
    if (!st.byId.has(b.id)) put(st, newToolEvent(b.id, b.name, b.input ?? {}, ts))
    // A workflow's phases are in its script, which the event's input keeps only the start of.
    if (b.name === 'Workflow' && typeof b.input?.script === 'string') {
      st.scripts.set(b.id, b.input.script.slice(0, 20000))
      if (st.scripts.size > 50) st.scripts.delete(st.scripts.keys().next().value!)
    }
  }
}

export function parseTranscript(text: string, sidechain = false): ParseState {
  const st = newParseState(sidechain)
  for (const line of text.split('\n')) {
    if (!line) continue
    try {
      feedLine(st, JSON.parse(line))
    } catch {}
  }
  return st
}
