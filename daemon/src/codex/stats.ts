// Codex's share of Statistics (docs/PROTOCOL.md, "Statistics"): counted by the daemon as each turn
// it follows ends, per day, and kept in <support>/codex-stats.json. Codex's own history lives in its
// rollouts, which Hypernova doesn't read; what Hypernova saw is what it counts.
import { basename, join } from 'node:path'
import { SUPPORT, readJson, writeJson } from '../util.ts'

export type CodexTokens = { input: number; output: number; cacheRead: number; cacheWrite: number }
/** One day of Codex's work, added up. */
export type CodexDay = {
  ms: number
  tokens: CodexTokens
  sessions: number
  prompts: number
  toolCalls: number
  tools: Record<string, number>
  filesEdited: number
  projects: Record<string, { ms: number; tokens: number; sessions: number; path: string }>
  longest: { id: string; title: string; project: string; ms: number; startedAt: number } | null
}
type Saved = {
  days: Record<string, CodexDay>
  /** Each thread's token totals as last seen (Codex reports them for the whole thread, history included). */
  counted: Record<string, CodexTokens>
  /** Each thread's busy time so far, for the longest session; a thread here was counted as a session. */
  threadMs: Record<string, number>
}

const FILE = join(SUPPORT, 'codex-stats.json')
/** A turn longer than this is someone away, not work (Claude's side caps idle stretches at 15 minutes inside a turn). */
const TURN_MAX_MS = 6 * 3600_000
let saved: Saved | null = null
const store = (): Saved => {
  if (saved) return saved
  const s = readJson<Saved | null>(FILE, null)
  saved = { days: s?.days ?? {}, counted: s?.counted ?? {}, threadMs: s?.threadMs ?? {} }
  return saved
}
let timer: NodeJS.Timeout | null = null
function saveSoon(): void {
  if (timer) return
  timer = setTimeout(() => {
    timer = null
    if (saved) writeJson(FILE, saved)
  }, 2000)
  timer.unref?.()
}

const pad = (n: number) => String(n).padStart(2, '0')
const dayKey = (t: number) => {
  const d = new Date(t)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
const emptyDay = (): CodexDay => ({ ms: 0, tokens: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, sessions: 0, prompts: 0, toolCalls: 0, tools: {}, filesEdited: 0, projects: {}, longest: null })
const sum = (t: CodexTokens) => t.input + t.output + t.cacheRead + t.cacheWrite

/** Codex's thread totals ({inputTokens, cachedInputTokens, cacheWriteInputTokens, outputTokens}) in Statistics' shape. */
export function tokensOf(total: any): CodexTokens | null {
  if (!total || typeof total !== 'object') return null
  const n = (v: unknown) => (typeof v === 'number' && v > 0 ? v : 0)
  const cached = n(total.cachedInputTokens)
  return { input: Math.max(0, n(total.inputTokens) - cached), output: n(total.outputTokens), cacheRead: cached, cacheWrite: n(total.cacheWriteInputTokens) }
}

export type CodexTurn = {
  thread: string
  title: string
  cwd: string
  /** When the turn began, and how long it ran. */
  at: number
  ms: number
  prompts: number
  tools: Record<string, number>
  files: number
}
const zero = (): CodexTokens => ({ input: 0, output: 0, cacheRead: 0, cacheWrite: 0 })
const projectOf = (d: CodexDay, cwd: string) => (d.projects[basename(cwd) || cwd] ??= { ms: 0, tokens: 0, sessions: 0, path: cwd })

/**
 * Codex reported a thread's tokens. In a turn (or just after one) what it added since the last
 * report counts, on the day the turn began; the first report of a thread counts only its last
 * request, as a thread joined from a terminal brings its whole history. Outside a turn (a thread
 * joined again), a report only sets where counting goes on from.
 */
export function noteCodexTokens(t: { thread: string; cwd: string; at: number; total: CodexTokens | null; last: CodexTokens | null; inTurn: boolean }): void {
  if (!t.total) return
  const s = store()
  const prev = s.counted[t.thread]
  s.counted[t.thread] = t.total
  if (t.inTurn) {
    const add = prev ? { input: Math.max(0, t.total.input - prev.input), output: Math.max(0, t.total.output - prev.output), cacheRead: Math.max(0, t.total.cacheRead - prev.cacheRead), cacheWrite: Math.max(0, t.total.cacheWrite - prev.cacheWrite) } : (t.last ?? zero())
    const d = (s.days[dayKey(t.at)] ??= emptyDay())
    for (const key of ['input', 'output', 'cacheRead', 'cacheWrite'] as const) d.tokens[key] += add[key]
    projectOf(d, t.cwd).tokens += sum(add)
  }
  saveSoon()
}

/** A turn Hypernova followed ended: its work goes on the day it began. */
export function noteCodexTurn(t: CodexTurn): void {
  const s = store()
  const k = dayKey(t.at)
  const d = (s.days[k] ??= emptyDay())
  const ms = Math.max(0, Math.min(TURN_MAX_MS, t.ms))
  const project = basename(t.cwd) || t.cwd
  const p = projectOf(d, t.cwd)
  // A thread is a session on the day of its first counted turn.
  if (!(t.thread in s.threadMs)) {
    d.sessions++
    p.sessions++
  }
  d.ms += ms
  p.ms += ms
  d.prompts += t.prompts
  for (const [name, n] of Object.entries(t.tools)) {
    d.tools[name] = (d.tools[name] ?? 0) + n
    d.toolCalls += n
  }
  d.filesEdited += t.files
  const total = (s.threadMs[t.thread] ?? 0) + ms
  s.threadMs[t.thread] = total
  if (!d.longest || total > d.longest.ms) d.longest = { id: t.thread, title: t.title, project, ms: total, startedAt: t.at }
  saveSoon()
}

/** Codex's days, for Statistics to add to Claude's. */
export const codexDays = (): Record<string, CodexDay> => store().days

/** For tests: forget what was counted, and read the file again next time. */
export function resetCodexStatsForTest(): void {
  saved = null
  if (timer) clearTimeout(timer)
  timer = null
}
