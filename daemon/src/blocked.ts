// Calls refused without the owner (docs/PROTOCOL.md, "Blocked calls"): Claude Code's auto mode
// classifier said no, or nobody answered an approval for 10 minutes and Hypernova said no. Each
// session keeps its own list, which the phone shows as its Blocked tab: Allow & retry, or Dismiss.
//
// Sightings come from three places, and the same call is one row: the PermissionDenied hook
// (hooks.ts), the approval that timed out (hub.ts), and the transcript's tool result (hub.ts),
// which stays the fallback (Claude Code doesn't fire the hook for every refusal). A call that later
// ran leaves the list by itself. Rows live in memory and come back from the transcripts after a
// restart; what the owner dismissed or retried is kept in <support>/blocked-settled.json so it
// stays gone. Its own small module, so hub.ts and hooks.ts can both use it.
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { SUPPORT, readJson, trimText, writeJson } from './util.ts'
import { maskSecrets, trimInput, type ToolEv } from './normalize.ts'

export type BlockedReason = 'auto_mode' | 'away'
export type BlockedCall = {
  id: string
  sessionId: string
  tool: string
  /** What it would have touched: the command, the file, the address (keys masked). */
  target: string
  /** Its input as the chat shows it, keys masked. */
  input: Record<string, unknown>
  reason: BlockedReason
  /** Claude Code's reason, when it gave one (the classifier's). */
  why: string | null
  at: number
  toolUseId: string | null
}
type Row = BlockedCall & { key: string; raw: string; toolUseIds: string[] }

/** A session keeps this many, newest first. */
const MAX_ROWS = 20
/** Refusals older than this, read from a transcript, are history, not something to retry. */
const MAX_AGE_MS = 24 * 3600_000
/** How long the owner's Allow & retry answers the same call's next approval by itself. */
const GRANT_MS = 10 * 60_000

const rows = new Map<string, Row[]>()
const grants = new Map<string, { key: string; until: number }[]>()
const retrying = new Set<string>()

const FILE = join(SUPPORT, 'blocked-settled.json')
let settled: Record<string, number> = readJson<Record<string, number>>(FILE, {})
if (!settled || typeof settled !== 'object' || Array.isArray(settled)) settled = {}

let changed: (sessionId: string) => void = () => {}
/** The hub listens, to send the session again with its new count (one listener). */
export function onBlockedChange(f: (sessionId: string) => void): void {
  changed = f
}
function tell(sessionId: string): void {
  try {
    changed(sessionId)
  } catch {}
}

// ── What a refusal looks like ─────────────────────────────────────────────
// Claude Code 2.1.312's words to Claude for a call its auto mode classifier refused, and for one
// it refused when the classifier couldn't judge it (fail closed).
const AUTO = ['Permission for this action was denied by the Claude Code auto mode classifier. Reason: ', 'Permission for this action has been denied. Reason: ']
const AUTO_END = '. If you have other tasks'
// Hypernova's own no after 10 minutes (hub.ts, AWAY_MESSAGE).
const AWAY = "didn't respond within 10 minutes and is probably away"

/** The classifier's reason in Claude Code's words for a refusal, or the words themselves when they're short. */
function whyOf(text: string): string | null {
  const t = text.replace(/^Error: /, '').trim()
  const p = AUTO.find((x) => t.startsWith(x))
  const rest = p ? t.slice(p.length) : t
  const end = rest.indexOf(AUTO_END)
  const why = (end > 0 ? rest.slice(0, end) : rest.split('\n')[0]!).trim()
  return why ? trimText(why, 200) : null
}

/** A tool result's text as a refusal this list keeps, or null. */
export function refusalOf(output: string): { reason: BlockedReason; why: string | null } | null {
  const t = output.replace(/^Error: /, '').trimStart()
  if (AUTO.some((p) => t.startsWith(p))) return { reason: 'auto_mode', why: whyOf(t) }
  if (t.includes(AWAY)) return { reason: 'away', why: null }
  return null
}

// ── A call, as one line ───────────────────────────────────────────────────
/** What the call would have touched, whole: the command, the file, the address. */
function targetOf(tool: string, input: Record<string, unknown>): string {
  const i = input ?? {}
  const s = (v: unknown) => (typeof v === 'string' ? v : '')
  switch (tool) {
    case 'Bash':
      return s(i.command)
    case 'Read':
    case 'Write':
    case 'Edit':
    case 'MultiEdit':
    case 'NotebookEdit':
      return s(i.file_path ?? i.notebook_path)
    case 'WebFetch':
      return s(i.url)
    case 'WebSearch':
      return s(i.query)
    case 'Grep':
    case 'Glob':
      return `${s(i.pattern)}${i.path ? ` in ${s(i.path)}` : ''}`
    case 'Task':
    case 'Agent':
      return s(i.description)
    default: {
      let json = ''
      try {
        json = JSON.stringify(i)
      } catch {}
      return trimText(json === '{}' ? '' : json, 300) ?? ''
    }
  }
}
/**
 * The same call, whichever sighting brought it: the tool and what it touches. The transcript's input
 * is cut for the wire at 2000 characters, the hook's isn't, so only the start counts.
 */
const keyOf = (tool: string, input: Record<string, unknown>) => `${tool}\n${targetOf(tool, input).slice(0, 1500)}`

/** A string, or a string anywhere inside, with keys masked as titles mask them. */
function masked(v: unknown): unknown {
  if (typeof v === 'string') return maskSecrets(v)
  if (Array.isArray(v)) return v.map(masked)
  if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, masked(x)]))
  return v
}
function safeInput(input: Record<string, unknown>): Record<string, unknown> {
  try {
    return masked(trimInput(input)) as Record<string, unknown>
  } catch {
    return {}
  }
}

const view = (r: Row): BlockedCall => {
  const { key: _k, raw: _r, toolUseIds: _t, ...call } = r
  return call
}

// ── Sightings ─────────────────────────────────────────────────────────────
/** Call [tool] in session [sessionId] was refused without the owner (the hook, a timed-out approval, the transcript). */
export function noteBlocked(sessionId: string, s: { tool: string; input: unknown; reason: BlockedReason; why?: string | null; toolUseId?: string | null; at?: number }): void {
  const tool = s.tool
  if (!tool) return
  const input = s.input && typeof s.input === 'object' && !Array.isArray(s.input) ? (s.input as Record<string, unknown>) : {}
  const toolUseId = s.toolUseId || null
  const at = s.at ?? Date.now()
  if (toolUseId && settled[toolUseId]) return
  if (Date.now() - at > MAX_AGE_MS) return
  const why = s.why ? whyOf(s.why) : null
  const list = rows.get(sessionId) ?? []
  const key = keyOf(tool, input)
  // Seen before: by its id (the hook and the transcript), or the same call refused again.
  const same = list.find((r) => (toolUseId && r.toolUseIds.includes(toolUseId)) || r.key === key)
  if (same) {
    const before = JSON.stringify(same)
    if (toolUseId && !same.toolUseIds.includes(toolUseId)) {
      same.toolUseIds.push(toolUseId)
      same.toolUseId = toolUseId
    }
    if (at > same.at) {
      same.at = at
      same.reason = s.reason
    }
    same.why = why ?? same.why
    if (JSON.stringify(same) === before) return
    list.sort((a, b) => b.at - a.at)
    return tell(sessionId)
  }
  const raw = targetOf(tool, input)
  list.unshift({ id: randomUUID(), sessionId, tool, target: maskSecrets(trimText(raw, 2000) ?? ''), input: safeInput(input), reason: s.reason, why, at, toolUseId, key, raw, toolUseIds: toolUseId ? [toolUseId] : [] })
  list.sort((a, b) => b.at - a.at)
  rows.set(sessionId, list.slice(0, MAX_ROWS))
  tell(sessionId)
}

/**
 * A tool call's result, read from the transcript (or the SDK's): a refusal joins the list, and a
 * call that ran takes the same call's row off it (it went through in the end).
 */
export function sawToolResult(sessionId: string, ev: ToolEv): void {
  if (ev.status === 'running') return
  const end = ev.ts + (ev.durationMs ?? 0)
  if (ev.status === 'error') {
    const r = refusalOf(ev.output ?? '')
    if (r) noteBlocked(sessionId, { tool: ev.name, input: ev.input, reason: r.reason, why: r.why, toolUseId: ev.toolUseId, at: end })
    return
  }
  const list = rows.get(sessionId)
  if (!list?.length) return
  const key = keyOf(ev.name, ev.input)
  const left = list.filter((r) => !(r.key === key && r.at <= end))
  if (left.length === list.length) return
  rows.set(sessionId, left)
  tell(sessionId)
}

// ── The owner's answers ───────────────────────────────────────────────────
export const blockedCount = (sessionId: string): number => rows.get(sessionId)?.length ?? 0
/** The session's blocked calls, newest first. */
export const blockedList = (sessionId: string): BlockedCall[] => (rows.get(sessionId) ?? []).map(view)

/** Off the list for good: a transcript read again doesn't bring it back. */
function settle(sessionId: string, row: Row): void {
  const list = rows.get(sessionId) ?? []
  rows.set(
    sessionId,
    list.filter((r) => r !== row),
  )
  const now = Date.now()
  for (const id of row.toolUseIds) settled[id] = now
  // Older than any transcript sighting is let in: they can't come back anyway.
  for (const [id, at] of Object.entries(settled)) if (now - at > 2 * MAX_AGE_MS) delete settled[id]
  writeJson(FILE, settled)
  tell(sessionId)
}

/** Dismiss: the owner has seen it and leaves it. False when it isn't on the list. */
export function dismissBlocked(sessionId: string, id: string): boolean {
  const row = rows.get(sessionId)?.find((r) => r.id === id)
  if (!row) return false
  settle(sessionId, row)
  return true
}

/**
 * What Allow & retry says to Claude, as the owner: that he approves exactly this call and wants it
 * run again. Auto mode's classifier takes the owner's own explicit words naming the action as his
 * consent, so the call is named whole, as it was.
 */
export function retryText(call: { tool: string; raw: string; reason: BlockedReason }): string {
  const what = trimText(call.raw, 4000) || '(no input)'
  const head = call.reason === 'away' ? 'I’m back, and I approve the call you were waiting on.' : 'I approve this call that auto mode blocked.'
  return `${head} Run it again now, exactly as it was:\n\n${call.tool}: ${what}`
}

export class BlockedError extends Error {
  status: number
  code: string
  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

/**
 * Allow & retry: [send] types the owner's approval into the session (the prompt route's own way),
 * and the row leaves the list once it's sent. Its next approval for the same call (one that asks
 * again, as the 10 minutes' one does) is answered yes without asking, once, within 10 minutes.
 * [send]'s errors are the route's: the row stays.
 */
export async function retryBlocked<T>(sessionId: string, id: string, send: (sessionId: string, text: string) => Promise<T>): Promise<T> {
  const row = rows.get(sessionId)?.find((r) => r.id === id)
  if (!row) throw new BlockedError(404, 'no_blocked', 'That call isn’t blocked any more.')
  if (retrying.has(id)) throw new BlockedError(409, 'retrying', 'That call is being retried already.')
  retrying.add(id)
  try {
    const out = await send(sessionId, retryText(row))
    const list = (grants.get(sessionId) ?? []).filter((g) => g.until > Date.now())
    list.push({ key: row.key, until: Date.now() + GRANT_MS })
    grants.set(sessionId, list)
    settle(sessionId, row)
    return out
  } finally {
    retrying.delete(id)
  }
}

/** The owner allowed this call with Allow & retry a moment ago: answer its approval yes (once). */
export function takeRetryGrant(sessionId: string, tool: string, input: unknown): boolean {
  const list = grants.get(sessionId)
  if (!list?.length) return false
  const key = keyOf(tool, input && typeof input === 'object' ? (input as Record<string, unknown>) : {})
  const i = list.findIndex((g) => g.key === key && g.until > Date.now())
  if (i < 0) return false
  list.splice(i, 1)
  return true
}

/** The session is gone for good. */
export function forgetBlocked(sessionId: string): void {
  rows.delete(sessionId)
  grants.delete(sessionId)
}
