// Statistics (docs/PROTOCOL.md, "Statistics"): how long Claude worked on this Mac, the tokens it
// used, sessions, prompts, tools, files, agents and usage limits reached, read from Claude Code's
// own transcripts, plus what only the daemon sees (prompts from the phone, approvals
// answered, limits the account reports).
//
// Cheap by design: each transcript is read once, then only the bytes added since (its size and
// mtime say whether anything changed), in chunks with a yield between them, so a first read of a
// gigabyte of history never holds the event loop for long. What a file adds up to is kept per file
// and per day (no events, no text), and the answer is put together on request, cached briefly.
//
// Claude Code deletes transcripts after 30 days by default, so finished days are also kept in
// <support>/stats.json, and a day whose files are gone still counts.
import { open, readdir, stat } from 'node:fs/promises'
import { basename, join, sep } from 'node:path'
import { CLAUDE_DIR, SUPPORT, log, readJson, writeJson } from './util.ts'
import { classifyUserText, maskSecrets, promptTitle } from './normalize.ts'
import { settings } from './store.ts'
import { codexDays, type CodexDay } from './codex/stats.ts'

export type StatsRange = 'today' | 'week' | 'month' | 'all'
export const STATS_RANGES: readonly StatsRange[] = ['today', 'week', 'month', 'all']

type Tokens = { input: number; output: number; cacheRead: number; cacheWrite: number }
type SessionRecord = { id: string; title: string; project: string; claudeMs: number; startedAt: number }
type ProjectDay = { ms: number; tokens: number; sessions: number }
/** The usage limits counted: the 5-hour window, the week, and a model's own (an "Opus limit"). */
export type LimitWindow = 'five_hour' | 'seven_day' | 'model'
type LimitCounts = { fiveHour: number; sevenDay: number; model: number }

/** One day, added up: what is kept in stats.json once the day is over. */
type DaySummary = {
  claudeMs: number
  wallMs: number
  agentMs: number
  tokens: Tokens
  sessions: number
  prompts: number
  agents: number
  toolCalls: number
  tools: Record<string, number>
  filesEdited: number
  /** Limits that began that day (older stats.json files have none). */
  limits?: LimitCounts
  projects: Record<string, ProjectDay>
  longest: SessionRecord | null
}

/** The answer to GET /v1/stats (docs/PROTOCOL.md, "Statistics"). */
export type Stats = {
  range: StatsRange
  from: number
  to: number
  computedAt: number
  since: number | null
  countingSince: number
  claudeMs: number
  wallMs: number
  agentMs: number
  tokens: Tokens
  sessions: number
  prompts: number
  promptsFrom: { phone: number; voice: number }
  toolCalls: number
  tools: { name: string; count: number }[]
  filesEdited: number
  agents: number
  approvals: { total: number; phone: number; mac: number }
  limitsHit: LimitCounts
  busiestDay: { day: string; claudeMs: number; tokens: number } | null
  longestSession: SessionRecord | null
  streak: number
  projects: { name: string; path: string | null; claudeMs: number; tokens: number; sessions: number }[]
  /** claudeMs: both agents' busy time that day (the name is from before Codex); codexMs: Codex's share. */
  days: { day: string; claudeMs: number; codexMs: number; wallMs: number; tokens: number; sessions: number; prompts: number }[]
  /** The range's work per agent; codex only where Codex is in use or has counted anything. */
  byAgent: { claude: AgentShare; codex?: AgentShare }
}
export type AgentShare = { ms: number; tokens: number; sessions: number; prompts: number; toolCalls: number }

/**
 * A pause longer than this inside a turn isn't work: a usage limit Claude Code waits out, a
 * dialog nobody answered. Builds, tests and approvals (auto-denied after 10 minutes) fit inside.
 */
export const IDLE_GAP_MS = 15 * 60_000
/** How long an answer is reused, and how often the files are looked at again at most. */
const FRESH_MS = 15_000
/** Read in pieces this big, yielding between them. */
const CHUNK = 1024 * 1024
/** A line this long is almost always a tool's result (a file read, a screenshot): not parsed. */
const BIG_LINE = 64 * 1024
const EDIT_TOOLS = new Set(['Edit', 'Write', 'MultiEdit', 'NotebookEdit'])

// ── Days, in the Mac's own time zone ──────────────────────────────────────
const pad = (n: number) => String(n).padStart(2, '0')
export function dayKey(t: number): string {
  const d = new Date(t)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
function startOfDay(t: number): number {
  const d = new Date(t)
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
}
/** Midnight after [t] (DST-safe: built from the date, not by adding 24 hours). */
function nextDay(t: number): number {
  const d = new Date(t)
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1).getTime()
}
/** Midnight [n] days before [t]'s day. */
function daysBefore(t: number, n: number): number {
  const d = new Date(t)
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() - n).getTime()
}
function dayStart(key: string): number {
  const [y, m, d] = key.split('-').map(Number)
  return new Date(y!, m! - 1, d!).getTime()
}
/** [a, b) cut at midnights: each day's share. */
function eachDay(a: number, b: number, fn: (day: string, ms: number) => void): void {
  let t = a
  while (t < b) {
    const end = Math.min(b, nextDay(t))
    fn(dayKey(t), end - t)
    t = end
  }
}

// ── One transcript ────────────────────────────────────────────────────────
type DayAcc = { tokens: Tokens; prompts: number; tools: Map<string, number>; files: Set<string> }
type FileState = {
  path: string
  id: number
  kind: 'main' | 'agent'
  sessionId: string
  size: number
  mtimeMs: number
  ino: number
  offset: number
  carry: Buffer
  cwd: string | null
  customTitle: string | null
  aiTitle: string | null
  firstPrompt: string | null
  /** Its first line of its own (a fork's copied history doesn't count). */
  startedAt: number | null
  /** It has a prompt or a reply of its own (not only lines copied from the session it forked). */
  worked: boolean
  /** Busy stretches, merged: [start0, end0, start1, end1, …]. */
  intervals: number[]
  /** The stretch still open: from a turn's start to the last line of work seen in it. */
  start: number | null
  end: number | null
  /** Inside lines copied from another file (a forked session repeats its parent's history). */
  inDup: boolean
  days: Map<string, DayAcc>
  /** Claude Code's "You've hit your … limit" replies in it (many may be one limit: see [episodes]). */
  limits: LimitSeen[]
  /** The last few messages' usage, as counted: a message is logged once per content block. */
  recent: Map<string, Tokens>
  recentTools: Set<string>
}

/** A limit seen once: in a transcript ([reset]: its words for which limit and when it resets) or from the account. */
type LimitSeen = { window: LimitWindow; at: number; reset?: string | null; resetsAt?: number | null }

const files = new Map<string, FileState>()
let nextFileId = 1
/** Message ids and prompt uuids → the file that counted them, so a fork's copies count once. */
const msgOwner = new Map<string, number>()
const promptOwner = new Map<string, number>()

function newFile(path: string, kind: 'main' | 'agent', sessionId: string): FileState {
  return {
    path, id: nextFileId++, kind, sessionId, size: 0, mtimeMs: 0, ino: 0, offset: 0, carry: Buffer.alloc(0),
    cwd: null, customTitle: null, aiTitle: null, firstPrompt: null, startedAt: null, worked: false,
    intervals: [], start: null, end: null, inDup: false, days: new Map(), limits: [], recent: new Map(), recentTools: new Set(),
  }
}

/** Start a file over (it was rewritten): what it counted is forgotten, its ids too. */
function resetFile(f: FileState): void {
  for (const map of [msgOwner, promptOwner]) for (const [k, v] of map) if (v === f.id) map.delete(k)
  Object.assign(f, newFile(f.path, f.kind, f.sessionId), { id: f.id })
}

function dropFile(f: FileState): void {
  for (const map of [msgOwner, promptOwner]) for (const [k, v] of map) if (v === f.id) map.delete(k)
  files.delete(f.path)
}

function acc(f: FileState, ts: number): DayAcc {
  const key = dayKey(ts)
  let a = f.days.get(key)
  if (!a) f.days.set(key, (a = { tokens: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, prompts: 0, tools: new Map(), files: new Set() }))
  return a
}

/** Close the open stretch into the file's merged list. */
function closeStretch(f: FileState): void {
  if (f.start != null && f.end != null && f.end > f.start) {
    const iv = f.intervals
    const n = iv.length
    if (n && f.start <= iv[n - 1]!) iv[n - 1] = Math.max(iv[n - 1]!, f.end)
    else iv.push(f.start, f.end)
  }
  f.start = f.end = null
}

/** A turn starts (a prompt, a command, a task notification): the time since the last one isn't work. */
function turnStart(f: FileState, ts: number): void {
  closeStretch(f)
  f.start = f.end = ts
  f.startedAt ??= ts
}

/** Work goes on (a reply, a tool's result): the stretch grows, unless the pause was too long. */
function working(f: FileState, ts: number): void {
  f.startedAt ??= ts
  if (f.end != null && f.start != null && ts - f.end <= IDLE_GAP_MS && ts >= f.start) {
    if (ts > f.end) f.end = ts
    return
  }
  // Out of order by a little: already inside the stretch.
  if (f.end != null && f.start != null && ts >= f.start - IDLE_GAP_MS && ts <= f.end) return
  closeStretch(f)
  f.start = f.end = ts
}

const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) && v > 0 ? v : 0)

function feedAssistant(f: FileState, o: any, ts: number): void {
  const msg = o.message
  const id = typeof msg?.id === 'string' ? msg.id : null
  if (id) {
    const owner = msgOwner.get(id)
    if (owner !== undefined && owner !== f.id) {
      f.inDup = true
      return
    }
    msgOwner.set(id, f.id)
  }
  f.inDup = false
  if (o.isApiErrorMessage === true) {
    const first = Array.isArray(msg?.content) ? msg.content.find((b: any) => b?.type === 'text')?.text : msg?.content
    const l = typeof first === 'string' ? limitOf(first) : null
    if (l) f.limits.push({ ...l, at: ts })
  }
  if (!f.worked && msg?.model !== '<synthetic>') f.worked = true
  working(f, ts)
  const a = acc(f, ts)
  const u = msg?.usage
  if (u && typeof u === 'object') {
    const now: Tokens = { input: num(u.input_tokens), output: num(u.output_tokens), cacheRead: num(u.cache_read_input_tokens), cacheWrite: num(u.cache_creation_input_tokens) }
    // A message is logged once per content block, each line repeating its usage: count it once,
    // and only what a later line adds.
    const was = id ? f.recent.get(id) : undefined
    for (const k of ['input', 'output', 'cacheRead', 'cacheWrite'] as const) a.tokens[k] += Math.max(0, now[k] - (was?.[k] ?? 0))
    if (id) {
      f.recent.set(id, was ? { input: Math.max(was.input, now.input), output: Math.max(was.output, now.output), cacheRead: Math.max(was.cacheRead, now.cacheRead), cacheWrite: Math.max(was.cacheWrite, now.cacheWrite) } : now)
      if (f.recent.size > 16) f.recent.delete(f.recent.keys().next().value!)
    }
  }
  const blocks = Array.isArray(msg?.content) ? msg.content : []
  for (const b of blocks) {
    if (b?.type !== 'tool_use' || typeof b.name !== 'string') continue
    if (typeof b.id === 'string') {
      if (f.recentTools.has(b.id)) continue
      f.recentTools.add(b.id)
      if (f.recentTools.size > 64) f.recentTools.delete(f.recentTools.values().next().value!)
    }
    const name = toolKind(b.name)
    a.tools.set(name, (a.tools.get(name) ?? 0) + 1)
    if (EDIT_TOOLS.has(b.name)) {
      const p = b.input?.file_path ?? b.input?.notebook_path
      if (typeof p === 'string' && p) a.files.add(p)
    }
  }
}

// Claude Code's own words when a usage limit stops it: "You've hit your session limit · resets 4pm
// (Europe/Moscow)", "… your weekly limit …", "… your Opus limit …", and older versions' "Claude AI
// usage limit reached|<reset, epoch seconds>". Credit and spend caps aren't limits reached.
const QUOTA = /^You['’]ve (?:hit|reached) your (.{1,60}?)(?: · |$)/
const LEGACY_QUOTA = /^Claude AI usage limit reached\|(\d{9,})/
const RESETS = /· (?:[Yy]our [^·]{1,60} )?resets ([^·]{1,60}?)\s*(?:·|$)/

/** The limit a Claude Code API error reply says was reached, or null. */
export function limitOf(text: string): { window: LimitWindow; reset: string | null } | null {
  const t = text.trim()
  const legacy = LEGACY_QUOTA.exec(t)
  if (legacy) return { window: 'five_hour', reset: legacy[1]! }
  const m = QUOTA.exec(t)
  if (!m) return null
  const label = m[1]!.trim().toLowerCase()
  if (/credit|spend|budget|monthly|\$/.test(label)) return null
  const window: LimitWindow = label === 'weekly limit' ? 'seven_day' : /^(?:fable|haiku|mythos|opus|sonnet) limit$/.test(label) ? 'model' : 'five_hour'
  // Which limit and when it resets: the same words from many sessions are one limit.
  return { window, reset: `${label} · ${RESETS.exec(t)?.[1]?.trim() ?? ''}` }
}

/** Tools by kind: MCP servers' tools as one, the agent launcher under its current name. */
function toolKind(name: string): string {
  if (name.startsWith('mcp__')) return 'MCP'
  if (name === 'Task') return 'Agent'
  return name
}

/** A prompt the owner wrote (or a skill or prompt command he ran), not Claude Code's own or a command's output. */
function ownPrompt(o: any, content: unknown): string | null {
  if (o.promptSource === 'system' || (o.origin?.kind && o.origin.kind !== 'human')) return null
  const u = classifyUserText(content)
  if (u?.kind === 'prompt') return u.text
  if (u?.kind === 'command' && u.command.kind === 'prompt') return `${u.command.name} ${u.command.args}`.trim()
  return null
}

function countPrompt(f: FileState, uuid: string | null, text: string, ts: number): boolean {
  if (uuid) {
    if (promptOwner.has(uuid)) return false
    promptOwner.set(uuid, f.id)
  }
  if (f.kind === 'main') {
    acc(f, ts).prompts++
    f.firstPrompt ??= text
  }
  return true
}

function feedUser(f: FileState, o: any, ts: number): void {
  const c = o.message?.content
  if (Array.isArray(c) && c.some((b: any) => b?.type === 'tool_result')) {
    if (!f.inDup) working(f, ts)
    return
  }
  // Skill bodies, caveats and Claude Code's notes: written beside a prompt, not a turn of their own.
  if (o.isMeta) return
  if (o.isCompactSummary) {
    if (!f.inDup) working(f, ts)
    return
  }
  const uuid = typeof o.uuid === 'string' ? o.uuid : null
  if (uuid) {
    const owner = promptOwner.get(uuid)
    if (owner !== undefined && owner !== f.id) {
      f.inDup = true
      return
    }
  }
  f.inDup = false
  // An older Claude Code wrote its agents' turns into the session's own file: their work, not a turn.
  if (o.isSidechain && f.kind === 'main') {
    working(f, ts)
    return
  }
  turnStart(f, ts)
  const text = ownPrompt(o, c)
  if (text != null) {
    if (countPrompt(f, uuid, text, ts)) f.worked = true
  } else if (uuid && !promptOwner.has(uuid)) promptOwner.set(uuid, f.id)
}

function feedLine(f: FileState, o: any): void {
  if (!o || typeof o !== 'object') return
  const ts = typeof o.timestamp === 'string' ? Date.parse(o.timestamp) : NaN
  if (!f.cwd && typeof o.cwd === 'string') f.cwd = o.cwd
  switch (o.type) {
    case 'custom-title':
      if (typeof o.customTitle === 'string') f.customTitle = o.customTitle
      return
    case 'ai-title':
      if (typeof o.aiTitle === 'string') f.aiTitle = o.aiTitle
      return
  }
  if (!Number.isFinite(ts)) return
  switch (o.type) {
    case 'assistant':
      return feedAssistant(f, o, ts)
    case 'user':
      return feedUser(f, o, ts)
    case 'attachment': {
      // A prompt typed while Claude worked, handed to it between two steps (mid-turn).
      const a = o.attachment
      if (a?.type !== 'queued_command' || a.isMeta || (a.origin?.kind ?? 'human') !== 'human' || (a.commandMode ?? 'prompt') !== 'prompt') return
      const u = classifyUserText(a.prompt)
      if (u?.kind !== 'prompt') return
      if (countPrompt(f, typeof a.source_uuid === 'string' ? a.source_uuid : null, u.text, ts) && !f.inDup) working(f, ts)
      return
    }
    case 'system':
      // Written as a turn ends.
      if (o.subtype === 'turn_duration' && !f.inDup) working(f, ts)
      return
  }
}

const TS_KEY = Buffer.from('"timestamp":"')
const USER_KEY = Buffer.from('"type":"user"')
const RESULT_KEY = Buffer.from('"type":"tool_result"')

/**
 * One line. A very long one that is a tool's result (a file read, a picture) only says when it
 * came, so its timestamp is read without parsing the rest: inside JSON strings a quote is always
 * escaped, so the bare key can only be the entry's own.
 */
function feedRaw(f: FileState, buf: Buffer, s: number, e: number): void {
  if (e - s > BIG_LINE) {
    const u = buf.indexOf(USER_KEY, s)
    const r = u >= 0 && u < e ? buf.indexOf(RESULT_KEY, s) : -1
    if (r >= 0 && r < e) {
      const i = buf.indexOf(TS_KEY, s)
      if (i >= 0 && i < e) {
        const q = buf.indexOf(34, i + TS_KEY.length) // the closing quote
        const ts = q > 0 && q < e ? Date.parse(buf.toString('latin1', i + TS_KEY.length, q)) : NaN
        if (Number.isFinite(ts) && !f.inDup) working(f, ts)
      }
      return
    }
  }
  let o: unknown
  try {
    o = JSON.parse(buf.toString('utf8', s, e))
  } catch {
    return
  }
  feedLine(f, o)
}

const yieldNow = () => new Promise<void>((r) => setImmediate(r))

/** Read what was added to [f] since the last look. */
async function readNew(f: FileState, size: number): Promise<void> {
  const fh = await open(f.path, 'r')
  try {
    while (f.offset < size) {
      const len = Math.min(CHUNK, size - f.offset)
      const chunk = Buffer.allocUnsafe(len)
      const { bytesRead } = await fh.read(chunk, 0, len, f.offset)
      if (!bytesRead) break
      f.offset += bytesRead
      const buf = f.carry.length ? Buffer.concat([f.carry, chunk.subarray(0, bytesRead)]) : chunk.subarray(0, bytesRead)
      let s = 0
      for (let nl = buf.indexOf(10, s); nl >= 0; nl = buf.indexOf(10, s)) {
        if (nl > s) feedRaw(f, buf, s, nl)
        s = nl + 1
      }
      // A line still being written waits for its end.
      f.carry = s < buf.length ? Buffer.from(buf.subarray(s)) : Buffer.alloc(0)
      await yieldNow()
    }
  } finally {
    await fh.close()
  }
}

// ── Finding the transcripts ───────────────────────────────────────────────
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
type Found = { path: string; kind: 'main' | 'agent'; sessionId: string }

async function list(dir: string): Promise<import('node:fs').Dirent[]> {
  try {
    return await readdir(dir, { withFileTypes: true })
  } catch {
    return []
  }
}

/** Sessions (`<project>/<uuid>.jsonl`) and their agents (`<uuid>/subagents/[workflows/<run>/]agent-*.jsonl`). */
async function findTranscripts(): Promise<Found[]> {
  const root = join(CLAUDE_DIR, 'projects')
  const out: Found[] = []
  for (const p of await list(root)) {
    if (!p.isDirectory()) continue
    const pdir = join(root, p.name)
    for (const e of await list(pdir)) {
      if (e.isFile() && e.name.endsWith('.jsonl') && UUID.test(e.name.slice(0, -6))) out.push({ path: join(pdir, e.name), kind: 'main', sessionId: e.name.slice(0, -6) })
      else if (e.isDirectory() && UUID.test(e.name)) {
        const sub = join(pdir, e.name, 'subagents')
        const agents = (dir: string, names: import('node:fs').Dirent[]) => {
          for (const a of names) if (a.isFile() && a.name.startsWith('agent-') && a.name.endsWith('.jsonl')) out.push({ path: join(dir, a.name), kind: 'agent', sessionId: e.name })
        }
        const entries = await list(sub)
        agents(sub, entries)
        if (entries.some((x) => x.isDirectory() && x.name === 'workflows')) {
          for (const run of await list(join(sub, 'workflows'))) if (run.isDirectory()) agents(join(sub, 'workflows', run.name), await list(join(sub, 'workflows', run.name)))
        }
      }
    }
    await yieldNow()
  }
  return out
}

// ── What the daemon counts itself, and finished days (<support>/stats.json) ─
type Counts = { phone?: number; voice?: number; apPhone?: number; apMac?: number }
type Saved = {
  v: 1
  /** When the daemon started counting what only it sees. */
  countingSince: number
  counts: Record<string, Counts>
  /** Limits the account reported ([recordLimitHit]), kept for good: transcripts don't last. */
  limits: LimitSeen[]
  /** Finished days as they were added up, for when Claude Code has deleted their transcripts. */
  days: Record<string, DaySummary>
  longest: SessionRecord | null
}
const STATS_FILE = join(SUPPORT, 'stats.json')
let saved: Saved | null = null
let saveTimer: NodeJS.Timeout | null = null

function store(): Saved {
  if (saved) return saved
  const s = readJson<Partial<Saved>>(STATS_FILE, {})
  saved = {
    v: 1,
    countingSince: typeof s.countingSince === 'number' ? s.countingSince : Date.now(),
    counts: s.counts && typeof s.counts === 'object' ? s.counts : {},
    limits: Array.isArray(s.limits) ? s.limits.filter((l) => WINDOWS.has(l?.window) && typeof l.at === 'number') : [],
    days: s.days && typeof s.days === 'object' ? s.days : {},
    longest: s.longest ?? null,
  }
  if (typeof s.countingSince !== 'number') saveSoon(0)
  return saved
}

/** Written a moment later, so a burst of answers is one write. */
function saveSoon(ms = 3000): void {
  if (saveTimer) return
  saveTimer = setTimeout(() => {
    saveTimer = null
    try {
      if (saved) writeJson(STATS_FILE, saved)
    } catch (e) {
      log('stats save failed', e)
    }
  }, ms)
  saveTimer.unref?.()
}

function bump(field: keyof Counts, at = Date.now()): void {
  const s = store()
  const c = (s.counts[dayKey(at)] ??= {})
  c[field] = (c[field] ?? 0) + 1
  saveSoon()
}

/**
 * A prompt went to a session from a paired device (or the Mac app, which isn't counted: the
 * transcripts count every prompt). `via: "voice"` from a client that dictated it counts it as
 * spoken too.
 */
export function notePrompt(deviceId: string | null, local: boolean, via?: unknown): void {
  if (local && !deviceId) return
  bump('phone')
  if (via === 'voice') bump('voice')
}

/** An approval answered through Hypernova: on a phone, or the Mac app. */
export function noteApproval(deviceId: string | null, local: boolean): void {
  if (local && !deviceId) return bump('apMac')
  bump('apPhone')
}

const WINDOWS = new Set<unknown>(['five_hour', 'seven_day', 'model'])
/** Within this of each other, two sightings of one window without the same reset are one limit. */
const SAME_LIMIT_MS = 30 * 60_000
/** How long one limit can last: the same words this much later are a new one. */
const LIMIT_SPAN: Record<LimitWindow, number> = { five_hour: 5 * 3600_000, seven_day: 7 * 86400_000, model: 7 * 86400_000 }

/**
 * The hook for the account's limit episodes (account.ts): call it when one begins, with its window
 * as the account names it ("five_hour", "seven_day", "model"; "credits" isn't counted), when it
 * began, and its reset if known. Transcripts show most limits already (Claude Code's "You've hit
 * your session limit"), so the account's are the same limit when they began within 30 minutes of
 * them or share their reset: calling it for a limit already counted, or twice, counts nothing.
 */
export function recordLimitHit(window: string, at = Date.now(), resetsAt: number | null = null): boolean {
  if (!WINDOWS.has(window) || !Number.isFinite(at)) return false
  const s = store()
  const same = (l: LimitSeen) =>
    l.window === window && (resetsAt != null && l.resetsAt != null ? Math.abs(l.resetsAt - resetsAt) < 2 * 60_000 : Math.abs(l.at - at) < SAME_LIMIT_MS)
  if (s.limits.some(same)) return false
  s.limits.push({ window: window as LimitWindow, at, resetsAt })
  s.limits.sort((a, b) => a.at - b.at)
  if (s.limits.length > 2000) s.limits.splice(0, s.limits.length - 2000)
  saveSoon()
  // Shown with the next answer, not after the cache's 15 s.
  stale = true
  return true
}

/**
 * Sightings of limits, made into limits reached. Many sessions hitting one limit, or one session
 * retrying, write the same words ("session limit · 4pm"), so the same words within the limit's
 * span are one. A sighting without them (the account's, or an old reply) joins a limit of its
 * window seen within 30 minutes; one with other words is a limit of its own.
 */
function episodes(seen: LimitSeen[]): { window: LimitWindow; at: number }[] {
  type Ep = { window: LimitWindow; first: number; last: number; resets: Set<string>; at: number[] }
  const eps: Ep[] = []
  for (const l of [...seen].sort((a, b) => a.at - b.at)) {
    const ep = eps.findLast((e) => {
      if (e.window !== l.window) return false
      if (l.reset && e.resets.size) return e.resets.has(l.reset) && l.at - e.first < LIMIT_SPAN[l.window]
      if (l.resetsAt != null && e.at.length) return e.at.some((r) => Math.abs(r - l.resetsAt!) < 2 * 60_000) && l.at - e.first < LIMIT_SPAN[l.window]
      return l.at - e.last <= SAME_LIMIT_MS
    })
    if (ep) {
      ep.last = Math.max(ep.last, l.at)
      if (l.reset) ep.resets.add(l.reset)
      if (l.resetsAt != null) ep.at.push(l.resetsAt)
    } else eps.push({ window: l.window, first: l.at, last: l.at, resets: new Set(l.reset ? [l.reset] : []), at: l.resetsAt != null ? [l.resetsAt] : [] })
  }
  return eps.map((e) => ({ window: e.window, at: e.first }))
}

// ── Adding it up ──────────────────────────────────────────────────────────
type Live = {
  at: number
  days: Map<string, DaySummary>
  /** Files edited per day (a range counts each file once). */
  edited: Map<string, Set<string>>
  /** Each session's busy time per day, for the longest session in a range. */
  sessions: { rec: Omit<SessionRecord, 'claudeMs'>; byDay: Map<string, number> }[]
  paths: Map<string, string>
}
let live: Live | null = null
let refreshing: Promise<Live> | null = null
/** Something the answer counts changed outside the transcripts (a limit from the account). */
let stale = false

const emptyDay = (): DaySummary => ({
  claudeMs: 0, wallMs: 0, agentMs: 0, tokens: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
  sessions: 0, prompts: 0, agents: 0, toolCalls: 0, tools: {}, filesEdited: 0, projects: {}, longest: null,
})

/** A session's project: the folder it started in, a worktree counted as its repository. */
function projectOf(cwd: string | null): { name: string; path: string | null } {
  if (!cwd) return { name: 'Unknown', path: null }
  const wt = cwd.indexOf(`${sep}.claude${sep}worktrees${sep}`)
  const path = wt > 0 ? cwd.slice(0, wt) : cwd
  return { name: basename(path) || path, path }
}

/** Sorted, merged copies of several interval lists (plus each file's open stretch). */
function union(fs: FileState[]): number[] {
  const pairs: [number, number][] = []
  for (const f of fs) {
    for (let i = 0; i < f.intervals.length; i += 2) pairs.push([f.intervals[i]!, f.intervals[i + 1]!])
    if (f.start != null && f.end != null && f.end > f.start) pairs.push([f.start, f.end])
  }
  return mergePairs(pairs)
}
function mergePairs(pairs: [number, number][]): number[] {
  pairs.sort((a, b) => a[0] - b[0])
  const out: number[] = []
  for (const [a, b] of pairs) {
    const n = out.length
    if (n && a <= out[n - 1]!) out[n - 1] = Math.max(out[n - 1]!, b)
    else out.push(a, b)
  }
  return out
}

function titleOf(f: FileState | undefined, fallback: string): string {
  const t = f?.customTitle ?? f?.aiTitle ?? (f?.firstPrompt ? promptTitle(f.firstPrompt) : null)
  const one = (t ?? fallback).replace(/\s+/g, ' ').trim()
  return maskSecrets(one.length > 80 ? one.slice(0, 79) + '…' : one)
}

function compute(at: number): Live {
  const days = new Map<string, DaySummary>()
  const day = (k: string) => {
    let d = days.get(k)
    if (!d) days.set(k, (d = emptyDay()))
    return d
  }
  const edited = new Map<string, Set<string>>()
  const paths = new Map<string, string>()
  const bySession = new Map<string, FileState[]>()
  const wallPairs: [number, number][] = []
  for (const f of files.values()) {
    const list = bySession.get(f.sessionId)
    if (list) list.push(f)
    else bySession.set(f.sessionId, [f])
  }
  const sessions: Live['sessions'] = []
  for (const [id, fs] of bySession) {
    const main = fs.find((f) => f.kind === 'main')
    // Agents of a session whose own file is gone still worked for that project.
    const { name: project, path } = projectOf(main?.cwd ?? fs.find((f) => f.cwd)?.cwd ?? null)
    if (path) paths.set(project, path)
    const iv = union(fs)
    const byDay = new Map<string, number>()
    for (let i = 0; i < iv.length; i += 2) {
      wallPairs.push([iv[i]!, iv[i + 1]!])
      eachDay(iv[i]!, iv[i + 1]!, (k, ms) => byDay.set(k, (byDay.get(k) ?? 0) + ms))
    }
    for (const [k, ms] of byDay) {
      const d = day(k)
      d.claudeMs += ms
      const p = (d.projects[project] ??= { ms: 0, tokens: 0, sessions: 0 })
      p.ms += ms
    }
    for (const f of fs) {
      for (const [k, a] of f.days) {
        const t = a.tokens.input + a.tokens.output + a.tokens.cacheRead + a.tokens.cacheWrite
        if (t) (day(k).projects[project] ??= { ms: 0, tokens: 0, sessions: 0 }).tokens += t
      }
    }
    const startedAt = main?.worked ? main.startedAt : null
    if (startedAt != null) {
      const d = day(dayKey(startedAt))
      d.sessions++
      ;(d.projects[project] ??= { ms: 0, tokens: 0, sessions: 0 }).sessions++
    }
    if (byDay.size) {
      const rec = { id, title: titleOf(main, project), project, startedAt: startedAt ?? iv[0]! }
      sessions.push({ rec, byDay })
      for (const [k, ms] of byDay) {
        const d = day(k)
        if (!d.longest || ms > d.longest.claudeMs) d.longest = { ...rec, claudeMs: ms }
      }
    }
  }
  const wall = mergePairs(wallPairs)
  for (let i = 0; i < wall.length; i += 2) eachDay(wall[i]!, wall[i + 1]!, (k, ms) => (day(k).wallMs += ms))
  for (const f of files.values()) {
    if (f.kind === 'agent') {
      if (f.startedAt != null) day(dayKey(f.startedAt)).agents++
      const iv = union([f])
      for (let i = 0; i < iv.length; i += 2) eachDay(iv[i]!, iv[i + 1]!, (k, ms) => (day(k).agentMs += ms))
    }
    for (const [k, a] of f.days) {
      const d = day(k)
      d.tokens.input += a.tokens.input
      d.tokens.output += a.tokens.output
      d.tokens.cacheRead += a.tokens.cacheRead
      d.tokens.cacheWrite += a.tokens.cacheWrite
      d.prompts += a.prompts
      for (const [name, n] of a.tools) {
        d.tools[name] = (d.tools[name] ?? 0) + n
        d.toolCalls += n
      }
      if (a.files.size) {
        let set = edited.get(k)
        if (!set) edited.set(k, (set = new Set()))
        for (const p of a.files) set.add(p)
      }
    }
  }
  for (const [k, set] of edited) day(k).filesEdited = set.size
  // Limits reached, on the day each began.
  const seen: LimitSeen[] = [...store().limits]
  for (const f of files.values()) seen.push(...f.limits)
  for (const e of episodes(seen)) {
    const d = day(dayKey(e.at))
    const l = (d.limits ??= { fiveHour: 0, sevenDay: 0, model: 0 })
    if (e.window === 'five_hour') l.fiveHour++
    else if (e.window === 'seven_day') l.sevenDay++
    else l.model++
  }
  return { at, days, edited, sessions, paths }
}

/** [a] holds more than [b]: more time, else more tool calls, else more tokens. */
function more(a: DaySummary, b: DaySummary): boolean {
  const tok = (d: DaySummary) => d.tokens.input + d.tokens.output + d.tokens.cacheRead + d.tokens.cacheWrite
  if (a.claudeMs !== b.claudeMs) return a.claudeMs > b.claudeMs
  if (a.toolCalls !== b.toolCalls) return a.toolCalls > b.toolCalls
  return tok(a) > tok(b)
}

/** Keep finished days, and the longest session, for when their transcripts are gone. */
function archive(l: Live, now: number): void {
  const s = store()
  const today = dayKey(now)
  let changed = false
  for (const [k, d] of l.days) {
    if (k >= today || (!d.claudeMs && !d.prompts && !d.toolCalls)) continue
    const old = s.days[k]
    // A day only loses work when its files go; one that gained (a file read late) is kept anew.
    if (!old || more(d, old)) {
      s.days[k] = d
      changed = true
    }
  }
  for (const x of l.sessions) {
    let ms = 0
    for (const v of x.byDay.values()) ms += v
    if (!s.longest || ms > s.longest.claudeMs) {
      s.longest = { ...x.rec, claudeMs: ms }
      changed = true
    }
  }
  if (changed) saveSoon()
}

/** Look at the transcripts again: new files, new bytes in old ones, files gone. */
async function refresh(now: number): Promise<Live> {
  const found = await findTranscripts()
  const seen = new Set<string>()
  // Oldest first, so a session's own lines are counted before a fork's copies of them.
  const withStat: { f: Found; size: number; mtimeMs: number; ino: number; born: number }[] = []
  for (const f of found) {
    seen.add(f.path)
    try {
      const st = await stat(f.path)
      withStat.push({ f, size: st.size, mtimeMs: st.mtimeMs, ino: st.ino, born: st.birthtimeMs || st.mtimeMs })
    } catch {}
  }
  for (const [path, f] of files) if (!seen.has(path)) dropFile(f)
  withStat.sort((a, b) => a.born - b.born)
  for (const { f: found1, size, mtimeMs, ino } of withStat) {
    let f = files.get(found1.path)
    if (!f) files.set(found1.path, (f = newFile(found1.path, found1.kind, found1.sessionId)))
    // Rewritten (shorter, or another file under the same name): read it from the start.
    if (size < f.offset || (f.ino && ino !== f.ino)) resetFile(f)
    f.ino = ino
    if (size === f.offset && mtimeMs === f.mtimeMs) continue
    f.mtimeMs = mtimeMs
    f.size = size
    try {
      await readNew(f, size)
    } catch (e: any) {
      if (e?.code !== 'ENOENT') log('stats: read failed', f.path, e?.message ?? e)
    }
  }
  const l = compute(now)
  archive(l, now)
  return l
}

async function current(now: number, force: boolean): Promise<Live> {
  if (!force && !stale && live && now - live.at < FRESH_MS && now >= live.at) return live
  if (!refreshing) {
    stale = false
    refreshing = refresh(now).then(
      (l) => {
        live = l
        refreshing = null
        return l
      },
      (e) => {
        refreshing = null
        throw e
      },
    )
  }
  return refreshing
}

/** The first moment a range covers. */
function rangeStart(range: StatsRange, now: number, since: number | null): number {
  if (range === 'today') return startOfDay(now)
  if (range === 'week') return daysBefore(now, 6)
  if (range === 'month') return daysBefore(now, 29)
  return since ?? startOfDay(now)
}

/**
 * Keep the numbers up without being asked: a first read a while after the daemon starts (so the
 * phone's first look is quick), then every few hours. That also keeps each finished day in
 * stats.json before Claude Code deletes its transcripts, even if nobody opens the statistics for
 * a month. Each look after the first reads only what was added.
 */
export function startStats(firstMs = 90_000, everyMs = 6 * 3600_000): void {
  const look = () => current(Date.now(), true).catch((e) => log('stats: look failed', e?.message ?? e))
  setTimeout(look, firstMs).unref?.()
  setInterval(look, everyMs).unref?.()
}

export function parseRange(v: string | null): StatsRange | null {
  return (STATS_RANGES as readonly string[]).includes(v ?? '') ? (v as StatsRange) : null
}

/** GET /v1/stats: the range's totals, its records, its projects, and the last 30 days. */
export async function getStats(range: StatsRange, now = Date.now(), opts: { fresh?: boolean } = {}): Promise<Stats> {
  const l = await current(now, opts.fresh === true)
  const s = store()
  const today = dayKey(now)
  // Each day from the transcripts, unless the kept copy has more (its files were deleted since).
  const merged = new Map<string, { d: DaySummary; kept: boolean }>()
  for (const [k, d] of Object.entries(s.days)) if (k <= today) merged.set(k, { d, kept: true })
  for (const [k, d] of l.days) {
    if (k > today) continue
    const old = merged.get(k)
    if (!old || !more(old.d, d)) merged.set(k, { d, kept: false })
  }
  // Codex's days (codex/stats.ts) go into the same days, so every total, the streak and the
  // busiest day cover both agents; its files and its longest session are added apart below.
  const cdays = codexDays()
  for (const [k, c] of Object.entries(cdays)) {
    if (k > today) continue
    const old = merged.get(k)
    merged.set(k, { d: withCodex(old?.d ?? emptyDay(), c), kept: old?.kept ?? true })
    for (const [name, p] of Object.entries(c.projects)) if (!l.paths.has(name)) codexPaths.set(name, p.path)
  }
  const active = [...merged.entries()].filter(([, v]) => v.d.claudeMs > 0 || v.d.prompts > 0 || v.d.toolCalls > 0).map(([k]) => k).sort()
  const since = active.length ? dayStart(active[0]!) : null
  const from = rangeStart(range, now, since)
  const fromKey = dayKey(from)
  const inRange = (k: string) => k >= fromKey && k <= today

  const tokens: Tokens = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }
  const limitsHit: LimitCounts = { fiveHour: 0, sevenDay: 0, model: 0 }
  let claudeMs = 0, wallMs = 0, agentMs = 0, sessions = 0, prompts = 0, agents = 0, toolCalls = 0, keptFiles = 0
  const codex: AgentShare = { ms: 0, tokens: 0, sessions: 0, prompts: 0, toolCalls: 0 }
  let codexFiles = 0
  const tools = new Map<string, number>()
  const projects = new Map<string, ProjectDay>()
  let busiest: Stats['busiestDay'] = null
  let longest: SessionRecord | null = null
  const editedFiles = new Set<string>()
  for (const [k, { d, kept }] of merged) {
    if (!inRange(k)) continue
    claudeMs += d.claudeMs
    wallMs += d.wallMs
    agentMs += d.agentMs
    sessions += d.sessions
    prompts += d.prompts
    agents += d.agents
    toolCalls += d.toolCalls
    for (const key of ['input', 'output', 'cacheRead', 'cacheWrite'] as const) tokens[key] += d.tokens[key]
    for (const key of ['fiveHour', 'sevenDay', 'model'] as const) limitsHit[key] += d.limits?.[key] ?? 0
    for (const [name, n] of Object.entries(d.tools)) tools.set(name, (tools.get(name) ?? 0) + n)
    for (const [name, p] of Object.entries(d.projects)) {
      const q = projects.get(name) ?? { ms: 0, tokens: 0, sessions: 0 }
      projects.set(name, { ms: q.ms + p.ms, tokens: q.tokens + p.tokens, sessions: q.sessions + p.sessions })
    }
    const dayTokens = d.tokens.input + d.tokens.output + d.tokens.cacheRead + d.tokens.cacheWrite
    if (d.claudeMs > 0 && (!busiest || d.claudeMs > busiest.claudeMs)) busiest = { day: k, claudeMs: d.claudeMs, tokens: dayTokens }
    if (kept) {
      keptFiles += d.filesEdited
      if (d.longest && (!longest || d.longest.claudeMs > longest.claudeMs)) longest = d.longest
    } else for (const p of l.edited.get(k) ?? []) editedFiles.add(p)
    const c = cdays[k]
    if (c) {
      codex.ms += c.ms
      codex.tokens += c.tokens.input + c.tokens.output + c.tokens.cacheRead + c.tokens.cacheWrite
      codex.sessions += c.sessions
      codex.prompts += c.prompts
      codex.toolCalls += c.toolCalls
      codexFiles += c.filesEdited
      if (c.longest && (!longest || c.longest.ms > longest.claudeMs)) longest = { id: c.longest.id, title: c.longest.title, project: c.longest.project, claudeMs: c.longest.ms, startedAt: c.longest.startedAt }
    }
  }
  // The longest session: its busy time inside the range (a session can span days).
  for (const x of l.sessions) {
    let ms = 0
    for (const [k, v] of x.byDay) if (inRange(k)) ms += v
    if (ms > 0 && (!longest || ms > longest.claudeMs)) longest = { ...x.rec, claudeMs: ms }
  }
  if (range === 'all' && s.longest && (!longest || s.longest.claudeMs > longest.claudeMs)) longest = s.longest

  // What only the daemon counts.
  const promptsFrom = { phone: 0, voice: 0 }
  const approvals = { total: 0, phone: 0, mac: 0 }
  for (const [k, c] of Object.entries(s.counts)) {
    if (!inRange(k)) continue
    promptsFrom.phone += c.phone ?? 0
    promptsFrom.voice += c.voice ?? 0
    approvals.phone += c.apPhone ?? 0
    approvals.mac += c.apMac ?? 0
  }
  approvals.total = approvals.phone + approvals.mac

  // Days in a row with work, up to today (or yesterday, while today hasn't started yet).
  const worked = (k: string) => (merged.get(k)?.d.claudeMs ?? 0) >= 60_000
  let streak = 0
  for (let i = worked(today) ? 0 : 1; i < 3660 && worked(dayKey(daysBefore(now, i))); i++) streak++

  const ranked = [...tools.entries()].sort((a, b) => b[1] - a[1])
  const topTools = ranked.slice(0, 8).map(([name, count]) => ({ name, count }))
  const rest = ranked.slice(8).reduce((n, [, c]) => n + c, 0)
  if (rest) topTools.push({ name: 'Other', count: rest })

  const days: Stats['days'] = []
  for (let i = 29; i >= 0; i--) {
    const k = dayKey(daysBefore(now, i))
    const d = merged.get(k)?.d
    days.push({ day: k, claudeMs: d?.claudeMs ?? 0, codexMs: cdays[k]?.ms ?? 0, wallMs: d?.wallMs ?? 0, tokens: d ? d.tokens.input + d.tokens.output + d.tokens.cacheRead + d.tokens.cacheWrite : 0, sessions: d?.sessions ?? 0, prompts: d?.prompts ?? 0 })
  }

  return {
    range, from, to: now, computedAt: l.at, since, countingSince: s.countingSince,
    claudeMs, wallMs, agentMs, tokens, sessions, prompts, promptsFrom,
    toolCalls, tools: topTools, filesEdited: editedFiles.size + keptFiles + codexFiles, agents, approvals, limitsHit,
    busiestDay: busiest, longestSession: longest, streak,
    projects: [...projects.entries()]
      .filter(([, p]) => p.ms > 0 || p.tokens > 0)
      .sort((a, b) => b[1].ms - a[1].ms || b[1].tokens - a[1].tokens)
      .slice(0, 8)
      .map(([name, p]) => ({ name, path: l.paths.get(name) ?? codexPaths.get(name) ?? null, claudeMs: p.ms, tokens: p.tokens, sessions: p.sessions })),
    days,
    byAgent: {
      claude: { ms: claudeMs - codex.ms, tokens: tokens.input + tokens.output + tokens.cacheRead + tokens.cacheWrite - codex.tokens, sessions: sessions - codex.sessions, prompts: prompts - codex.prompts, toolCalls: toolCalls - codex.toolCalls },
      ...(settings.codex === true || Object.keys(cdays).length ? { codex } : {}),
    },
  }
}

/** Codex's projects' folders, for those Claude Code never worked in. */
const codexPaths = new Map<string, string>()

/** A day with Codex's work added: its time, tokens, sessions, prompts, tools and projects. */
function withCodex(d: DaySummary, c: CodexDay): DaySummary {
  const tools = { ...d.tools }
  for (const [name, n] of Object.entries(c.tools)) tools[name] = (tools[name] ?? 0) + n
  const projects = { ...d.projects }
  for (const [name, p] of Object.entries(c.projects)) {
    const q = projects[name] ?? { ms: 0, tokens: 0, sessions: 0 }
    projects[name] = { ms: q.ms + p.ms, tokens: q.tokens + p.tokens, sessions: q.sessions + p.sessions }
  }
  return {
    ...d,
    claudeMs: d.claudeMs + c.ms,
    wallMs: d.wallMs + c.ms,
    tokens: { input: d.tokens.input + c.tokens.input, output: d.tokens.output + c.tokens.output, cacheRead: d.tokens.cacheRead + c.tokens.cacheRead, cacheWrite: d.tokens.cacheWrite + c.tokens.cacheWrite },
    sessions: d.sessions + c.sessions,
    prompts: d.prompts + c.prompts,
    toolCalls: d.toolCalls + c.toolCalls,
    tools,
    projects,
  }
}

/** For tests: forget everything read, so the next answer reads the files from the start. */
export function resetStatsForTest(): void {
  files.clear()
  msgOwner.clear()
  promptOwner.clear()
  live = null
  refreshing = null
  stale = false
}

/** For tests: write stats.json now rather than in a moment. */
export function flushStatsForTest(): void {
  if (saveTimer) {
    clearTimeout(saveTimer)
    saveTimer = null
  }
  if (saved) writeJson(STATS_FILE, saved)
}

/** For tests: read stats.json again, as a restarted daemon would. */
export function reloadStatsForTest(): void {
  saved = null
}
