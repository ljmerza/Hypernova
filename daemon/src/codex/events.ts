// Codex's thread items as Hypernova's events (normalize.ts, Ev): live from the app-server's
// notifications, and a thread's history when it's opened (docs/specs/codex-app-server.md).
import { randomUUID } from 'node:crypto'
import { emitEvent, ensureParsed, getSession, notice, putEvent, resolveSession, setState, upsertSession } from '../hub.ts'
import { maskSecrets, newToolEvent, userEvent, type Diff, type Ev, type ToolEv } from '../normalize.ts'
import { withAttachments } from '../attachments.ts'
import { tail, trimText } from '../util.ts'
import { isCodex, patchThread } from './threads.ts'
import { onCodexNotification } from './client.ts'
import type { RpcMessage } from './rpc.ts'
import { noteCodexTokens, noteCodexTurn, tokensOf as turnTokens } from './stats.ts'

const toolStatus = (s: unknown): ToolEv['status'] => (s === 'inProgress' ? 'running' : s === 'failed' || s === 'declined' ? 'error' : 'done')

/** A unified diff from Codex as the chat's diff (its hunks and +/- counts). */
export function diffOf(path: string, patch: string): Diff | null {
  const hunks: Diff['hunks'] = []
  let added = 0
  let removed = 0
  for (const line of patch.split('\n')) {
    const h = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/.exec(line)
    if (h) {
      hunks.push({ oldStart: Number(h[1]), oldLines: Number(h[2] ?? 1), newStart: Number(h[3]), newLines: Number(h[4] ?? 1), lines: [] })
      continue
    }
    const hunk = hunks.at(-1)
    if (!hunk || !/^[ +\-\\]/.test(line)) continue
    hunk.lines.push(line)
    if (line.startsWith('+')) added++
    else if (line.startsWith('-')) removed++
  }
  return hunks.length ? { path, added, removed, hunks } : null
}

/** Text a tool gave back: an MCP result's text parts, or its error. */
function mcpOutput(item: any): string | null {
  if (item.error?.message) return String(item.error.message)
  const parts = Array.isArray(item.result?.content) ? item.result.content : []
  const text = parts.map((c: any) => (c?.type === 'text' ? c.text : '')).filter(Boolean).join('\n')
  return text || null
}

/**
 * One item as the chat's events. [live]: as it starts or streams (a reply still being written, a
 * command still running); otherwise as it ended. Reasoning shows as its length, as Claude's does.
 */
export function itemEvents(item: any, ts: number, live = false): Ev[] {
  if (!item || typeof item.id !== 'string') return []
  switch (item.type) {
    case 'userMessage': {
      const parts: any[] = Array.isArray(item.content) ? item.content : []
      const text = parts.filter((p) => p?.type === 'text').map((p) => String(p.text ?? '')).join('\n')
      const files = parts.filter((p) => (p?.type === 'mention' || p?.type === 'localImage') && typeof p.path === 'string').map((p) => p.path as string)
      const ev = userEvent(`u:${item.clientId ?? item.id}`, ts, withAttachments(text, files))
      if (ev.kind === 'user' && typeof item.clientId === 'string') ev.clientId = item.clientId
      return [ev]
    }
    case 'agentMessage':
      return [{ id: `t:${item.id}`, kind: 'assistant', ts, text: String(item.text ?? ''), streaming: live }]
    case 'reasoning': {
      const chars = [...(item.summary ?? []), ...(item.content ?? [])].join('').length
      return chars || live ? [{ id: `th:${item.id}`, kind: 'thinking', ts, chars }] : []
    }
    case 'commandExecution': {
      // A single action Codex recognized (a file read, a search) shows as that tool; else the command
      // (a listing too: its command says more than its folder).
      const a = Array.isArray(item.commandActions) && item.commandActions.length === 1 ? item.commandActions[0] : null
      const [name, input]: [string, Record<string, unknown>] =
        a?.type === 'read' && a.path
          ? ['Read', { file_path: a.path }]
          : a?.type === 'search'
            ? ['Grep', { pattern: a.query ?? a.command, ...(a.path ? { path: a.path } : {}) }]
            : ['Bash', { command: maskSecrets(String(a?.command ?? item.command ?? '')) }]
      const ev = newToolEvent(item.id, name, input, ts)
      ev.status = live && item.status === 'inProgress' ? 'running' : toolStatus(item.status)
      if (typeof item.aggregatedOutput === 'string' && item.aggregatedOutput) ev.output = tail(maskSecrets(item.aggregatedOutput))
      ev.exitCode = typeof item.exitCode === 'number' ? item.exitCode : null
      ev.durationMs = typeof item.durationMs === 'number' ? item.durationMs : null
      return [ev]
    }
    case 'fileChange': {
      const changes: any[] = Array.isArray(item.changes) ? item.changes : []
      // One event per file, so the session's changes list each (changes.ts reads their paths).
      return changes.map((c, i) => {
        const ev = newToolEvent(i ? `${item.id}#${i}` : item.id, c?.kind?.type === 'add' ? 'Write' : 'Edit', { file_path: String(c?.path ?? '') }, ts)
        ev.status = toolStatus(item.status)
        ev.diff = typeof c?.diff === 'string' ? diffOf(String(c.path), maskSecrets(c.diff)) : null
        return ev
      })
    }
    case 'mcpToolCall': {
      const args = item.arguments && typeof item.arguments === 'object' && !Array.isArray(item.arguments) ? item.arguments : {}
      const ev = newToolEvent(item.id, `mcp__${item.server}__${item.tool}`, args, ts)
      ev.status = toolStatus(item.status)
      const out = mcpOutput(item)
      ev.output = out ? tail(maskSecrets(out)) : null
      ev.durationMs = typeof item.durationMs === 'number' ? item.durationMs : null
      return [ev]
    }
    case 'webSearch': {
      const ev = newToolEvent(item.id, 'WebSearch', { query: String(item.query ?? '') }, ts)
      ev.status = live ? 'running' : 'done'
      return [ev]
    }
    case 'imageView': {
      const ev = newToolEvent(item.id, 'Read', { file_path: String(item.path ?? '') }, ts)
      ev.status = 'done'
      return [ev]
    }
    case 'plan':
      return live || !item.text ? [] : [{ id: `p:${item.id}`, kind: 'notice', ts, text: String(item.text), tone: 'info' }]
    case 'contextCompaction':
      return live ? [] : [{ id: `c:${item.id}`, kind: 'notice', ts, text: 'Codex compacted the conversation to fit its context.', tone: 'info' }]
    default:
      return []
  }
}

/** How a turn ended, as the chat's last row for it: its result, or "Stopped." when it was stopped. */
export function turnEnd(turn: any, ts: number): Ev | null {
  if (!turn || turn.status === 'inProgress') return null
  if (turn.status === 'interrupted') return { id: `r:${turn.id}`, kind: 'notice', ts, text: 'Stopped.', tone: 'info' }
  const ok = turn.status === 'completed'
  return { id: `r:${turn.id}`, kind: 'result', ts, ok, durationMs: typeof turn.durationMs === 'number' ? turn.durationMs : 0, costUsd: null, turns: 1, error: ok ? null : String(turn.error?.message ?? 'Codex stopped with an error.') }
}

/** A thread's history (thread/read with its turns) as the chat's events, oldest first. */
export function historyEvents(thread: any): Ev[] {
  const out: Ev[] = []
  for (const turn of Array.isArray(thread?.turns) ? thread.turns : []) {
    const start = typeof turn.startedAt === 'number' ? turn.startedAt * 1000 : 0
    const items: any[] = Array.isArray(turn.items) ? turn.items : []
    // Items carry no time of their own: they keep their order within the turn.
    items.forEach((item, i) => out.push(...itemEvents(item, start + i)))
    const end = turnEnd(turn, typeof turn.completedAt === 'number' ? turn.completedAt * 1000 : start + items.length)
    if (end) out.push(end)
  }
  return out
}

// ── Live ─────────────────────────────────────────────────────────────────
/**
 * Each Codex session's turn as it runs: its id, when it started, when each streaming event was last
 * sent (deltas are sent at most every 120 ms, output every 250), and whether a Stop already said
 * what it dropped.
 */
type Live = { turn: string | null; at: number; endedAt: number; sent: Map<string, number>; stopSaid: boolean; done: string[]; count: Count }
/** What a turn did, for Statistics (codex/stats.ts): the owner's messages, tool calls by name, files changed. */
type Count = { prompts: number; tools: Record<string, number>; files: number }
const noCount = (): Count => ({ prompts: 0, tools: {}, files: 0 })
const lives = new Map<string, Live>()
export function liveOf(id: string): Live {
  let l = lives.get(id)
  if (!l) lives.set(id, (l = { turn: null, at: 0, endedAt: 0, sent: new Map(), stopSaid: false, done: [], count: noCount() }))
  return l
}
/** Turn [turn] ended already (its turn/completed came before turn/start's own answer). */
export const turnOver = (id: string, turn: string) => liveOf(id).done.includes(turn)

/**
 * The turn Hypernova thought ran is gone without an end (Codex restarted under it): what it left
 * streaming or running rests, and the session is at rest.
 */
export function abandonTurn(id: string): void {
  const l = liveOf(id)
  if (!l.turn) return
  if (getSession(id)) settle(id, l.at, true)
  l.done = [...l.done, l.turn].slice(-8)
  l.turn = null
  const r = getSession(id)
  if (r && (r.state === 'working' || r.state === 'needs_you')) setState(id, 'idle')
}
/** The turn running in session [id], if one is. */
export const runningTurn = (id: string) => lives.get(id)?.turn ?? null

/** Each session's tokens as Codex last counted them: the whole thread's, and the last turn's prompt against the window. */
type Tokens = { total: { inputTokens?: number; cachedInputTokens?: number; cacheWriteInputTokens?: number; outputTokens?: number } | null; used: number | null; window: number | null; at: number }
const tokens = new Map<string, Tokens>()
export const tokensOf = (id: string): Tokens | null => tokens.get(id) ?? null

const turnEnders: ((id: string, turn: any) => void)[] = []
/** A turn ended in a Codex session (sessions.ts sends the next queued prompt). */
export const onTurnEnded = (f: (id: string, turn: any) => void) => void turnEnders.push(f)
const strangers: ((m: RpcMessage) => void)[] = []
/** A notification about a thread Hypernova doesn't hold (yet): a terminal's new one, say (sessions.ts). */
export const onOtherThread = (f: (m: RpcMessage) => void) => void strangers.push(f)

function put(id: string, evs: Ev[]): void {
  for (const ev of evs) putEvent(id, ev)
}
/** Send a streaming event's change, at most every [ms]. */
function throttled(id: string, evId: string, ms: number): void {
  const l = liveOf(id)
  const now = Date.now()
  if (now - (l.sent.get(evId) ?? 0) < ms) return
  l.sent.set(evId, now)
  emitEvent(id, evId)
}
const eventOf = (id: string, evId: string) => ensureParsed(getSession(id)!).byId.get(evId)

/** Everything still marked as streaming or running in the turn that ended goes to rest. */
function settle(id: string, since: number, stopped: boolean): void {
  for (const ev of ensureParsed(getSession(id)!).events) {
    if (ev.ts < since) continue
    if (ev.kind === 'assistant' && ev.streaming) {
      ev.streaming = false
      emitEvent(id, ev.id)
    } else if (ev.kind === 'tool' && ev.status === 'running') {
      ev.status = stopped ? 'error' : 'done'
      emitEvent(id, ev.id)
    }
  }
}

export function handleNotification(m: RpcMessage): void {
  const p = m.params ?? {}
  const id: unknown = p.threadId ?? p.thread?.id
  if (typeof id !== 'string') return
  if (!isCodex(id) || !getSession(id)) {
    for (const f of strangers) f(m)
    return
  }
  const now = Date.now()
  switch (m.method) {
    case 'turn/started': {
      const l = liveOf(id)
      l.turn = p.turn?.id ?? null
      l.at = now
      l.stopSaid = false
      l.count = noCount()
      patchThread(id, { started: true, lastActivityAt: now })
      setState(id, 'working', 'Thinking')
      return
    }
    case 'item/started':
    case 'item/completed': {
      const item = p.item
      const done = m.method === 'item/completed'
      // The owner's message, for Statistics, wherever it was sent from.
      if (done && item?.type === 'userMessage') liveOf(id).count.prompts++
      // A prompt this daemon sent already shows (sessions.ts); Codex's copy of it changes nothing.
      if (item?.type === 'userMessage' && eventOf(id, `u:${item.clientId ?? item.id}`)) return
      const evs = itemEvents(item, eventOf(id, `t:${item?.id}`)?.ts ?? eventOf(id, String(item?.id))?.ts ?? now, !done)
      put(id, evs)
      if (done) {
        const c = liveOf(id).count
        for (const e of evs) {
          if (e.kind !== 'tool') continue
          const name = e.name.startsWith('mcp__') ? 'MCP' : e.name
          c.tools[name] = (c.tools[name] ?? 0) + 1
          if (name === 'Edit' || name === 'Write') c.files++
        }
      }
      if (item?.type === 'agentMessage' && done && item.text?.trim()) upsertSession(id, { lastText: trimText(item.text, 200), lastActivityAt: now })
      // Items outside a turn (a terminal resuming the thread, a hook's) don't make it work.
      if (!runningTurn(id) || !evs.length) return
      if (item?.type === 'agentMessage') {
        if (!done) setState(id, 'working', 'Writing')
      } else if (!done && evs[0]?.kind === 'tool') setState(id, 'working', `${evs[0].name}: ${evs[0].summary}`)
      else if (done && item?.type !== 'userMessage') setState(id, 'working', 'Thinking')
      return
    }
    case 'item/agentMessage/delta': {
      const evId = `t:${p.itemId}`
      const ev = eventOf(id, evId)
      if (ev?.kind === 'assistant') ev.text += String(p.delta ?? '')
      else putEvent(id, { id: evId, kind: 'assistant', ts: now, text: String(p.delta ?? ''), streaming: true })
      throttled(id, evId, 120)
      return
    }
    case 'item/commandExecution/outputDelta': {
      const ev = eventOf(id, String(p.itemId))
      if (ev?.kind !== 'tool') return
      ev.output = tail((ev.output ?? '') + String(p.delta ?? ''))
      throttled(id, ev.id, 250)
      return
    }
    case 'item/reasoning/summaryTextDelta':
    case 'item/reasoning/textDelta': {
      const evId = `th:${p.itemId}`
      const ev = eventOf(id, evId)
      if (ev?.kind === 'thinking') ev.chars += String(p.delta ?? '').length
      else putEvent(id, { id: evId, kind: 'thinking', ts: now, chars: String(p.delta ?? '').length })
      throttled(id, evId, 250)
      return
    }
    case 'turn/completed': {
      const l = liveOf(id)
      const turn = p.turn
      const stopped = turn?.status === 'interrupted'
      settle(id, l.at, stopped)
      const end = turnEnd({ ...turn, durationMs: turn?.durationMs ?? now - l.at }, now)
      if (end && !(stopped && l.stopSaid)) putEvent(id, end)
      if (turn?.id) l.done = [...l.done, String(turn.id)].slice(-8)
      // Statistics: the turn's time, what it did, and the thread's tokens now.
      const r = getSession(id)!
      noteCodexTurn({ thread: id, title: r.title, cwd: r.cwd, at: l.at || now, ms: typeof turn?.durationMs === 'number' ? turn.durationMs : now - (l.at || now), ...l.count })
      l.count = noCount()
      l.endedAt = now
      l.turn = null
      // A card still open for this turn was answered where Codex heard it (its resolution was lost).
      resolveSession(id)
      patchThread(id, { lastActivityAt: now })
      setState(id, 'idle')
      for (const f of turnEnders) f(id, turn)
      return
    }
    case 'thread/status/changed': {
      const s = p.status?.type
      const r = getSession(id)!
      if (s === 'systemError') setState(id, 'error')
      else if (s === 'idle' && r.state === 'working' && !runningTurn(id)) setState(id, 'idle')
      else if (s === 'notLoaded' && r.state !== 'working') setState(id, 'ended')
      return
    }
    case 'thread/tokenUsage/updated': {
      const t = p.tokenUsage
      const window = Number(t?.modelContextWindow)
      const used = Number(t?.last?.inputTokens)
      tokens.set(id, { total: t?.total ?? null, used: used >= 0 ? used : null, window: window > 0 ? window : null, at: now })
      // Statistics: in a turn, or just after one (whichever Codex sends first).
      const l = liveOf(id)
      noteCodexTokens({ thread: id, cwd: getSession(id)!.cwd, at: l.at || now, total: turnTokens(t?.total), last: turnTokens(t?.last), inTurn: l.turn != null || now - l.endedAt < 10_000 })
      if (window > 0 && used >= 0) upsertSession(id, { contextPct: Math.min(100, Math.round((used / window) * 100)) })
      return
    }
    case 'thread/name/updated': {
      const name = trimText(typeof p.threadName === 'string' ? p.threadName.trim() : '', 80)
      if (!name) return
      patchThread(id, { title: name })
      upsertSession(id, { title: name })
      return
    }
    case 'error':
      if (!p.willRetry && p.error?.message) notice(id, String(p.error.message), 'error')
      else if (p.willRetry && runningTurn(id)) setState(id, 'working', 'Retrying')
      return
  }
}
onCodexNotification(handleNotification)

/** A send Codex hasn't echoed yet: its event, under the id Codex's echo will carry. */
export function sentEvent(id: string, clientId: string, text: string): Ev {
  const ev = userEvent(`u:${clientId}`, Date.now(), text)
  if (ev.kind === 'user') ev.clientId = clientId
  putEvent(id, ev)
  return ev
}

/** A notice that names prompts which won't be sent (a Stop dropped them), so the phone takes them back. */
export function droppedNotice(id: string, prompts: { text: string; clientId: string | null }[]): void {
  const n = prompts.length
  liveOf(id).stopSaid = true
  putEvent(id, { id: `n:${randomUUID()}`, kind: 'notice', ts: Date.now(), text: `Stopped. ${n} queued message${n === 1 ? ' wasn’t' : 's weren’t'} sent.`, tone: 'info', unsent: { reason: 'dropped', prompts } })
}
