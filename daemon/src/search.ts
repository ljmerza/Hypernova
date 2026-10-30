// Full-text search for History (docs/PROTOCOL.md, "History search"): the words inside the
// conversations the phone lists, read from their Claude Code transcripts. Only what the owner
// typed and Claude's replies count: tool output, Claude Code's notes, meta entries, compaction
// summaries and subagents' lines don't. Local and bounded: no index, an in-memory cache of each
// transcript's text that grows as the file does, a time budget per search.
import { open } from 'node:fs/promises'
import { statSync } from 'node:fs'
import { join, resolve, sep } from 'node:path'
import { CLAUDE_DIR } from './util.ts'
import { classifyUserText, commandText } from './normalize.ts'
import { isCodexRec, listSessionRecs } from './hub.ts'
import { isHiddenCwd } from './term/hidden.ts'

export type SearchMark = { start: number; end: number }
export type SearchHit = {
  id: string
  /** About 120 characters around the first hit, whitespace squeezed, "…" where it was cut. */
  snippet: string
  /** Where the words are in [snippet] (UTF-16 offsets, end exclusive), in order. */
  marks: SearchMark[]
  /** Who said the snippet's message. */
  role: 'user' | 'assistant'
  /** How many times the words occur in the conversation, all of them counted. */
  matches: number
}
export type SearchResult = { results: SearchHit[]; partial: boolean }
/** A transcript to search: its session's id, its file, and when it was last active (newest first). */
export type SearchSource = { id: string; path: string; at: number }

/** Results at most; the newest conversations win. */
export const SEARCH_MAX = 50
/** A search gives up after this long and says `partial`; what it read is kept for the next one. */
export const SEARCH_BUDGET_MS = 2500
/** A transcript is searched in its first 64 MB (tool output makes most of a big one). */
export const FILE_MAX = 64 * 1024 * 1024
/** A line longer than this (pictures pasted into a prompt) isn't read. */
const LINE_MAX = 8 * 1024 * 1024
const CHUNK = 1024 * 1024
/** The cache keeps this much text (characters) at most, the least recently searched dropped first. */
const CACHE_CHARS = 24_000_000
const SNIPPET = 120
const MAX_WORDS = 8
const MAX_WORD = 64

type Msg = { role: 'user' | 'assistant'; text: string }
/** What a transcript says, read up to [offset] (always the start of a line). */
type Doc = { ino: number; size: number; mtime: number; offset: number; msgs: Msg[]; seen: Set<string>; chars: number }
const cache = new Map<string, Doc>()
let cachedChars = 0

/** The query's words: split on spaces, case-insensitively unique, at most 8 of 64 characters each. */
export function queryWords(q: string): string[] {
  const out: string[] = []
  for (const w of q.trim().split(/\s+/)) {
    const word = w.slice(0, MAX_WORD)
    if (word && !out.some((o) => o.toLowerCase() === word.toLowerCase())) out.push(word)
    if (out.length === MAX_WORDS) break
  }
  return out
}

const escapeRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** The text a transcript line adds to the search: a prompt the owner typed or one of Claude's replies. */
function textsOf(o: any, doc: Doc): Msg[] {
  if (!o || typeof o !== 'object' || o.isSidechain) return []
  if (o.type === 'user') {
    if (o.isMeta || o.isCompactSummary || o.isVisibleInTranscriptOnly || o.repeatsCompactSummary) return []
    const c = o.message?.content
    if (Array.isArray(c) && c.some((b: any) => b?.type === 'tool_result')) return []
    return once(doc, typeof o.uuid === 'string' ? `u:${o.uuid}` : null, userText(c))
  }
  if (o.type === 'attachment') {
    // A prompt typed while Claude worked, handed over between two steps (normalize.ts, feedLine).
    const a = o.attachment
    if (a?.type !== 'queued_command' || a.isMeta || (a.origin?.kind ?? 'human') !== 'human' || (a.commandMode ?? 'prompt') !== 'prompt') return []
    const u = classifyUserText(a.prompt)
    const id = typeof a.source_uuid === 'string' ? a.source_uuid : o.uuid
    return u?.kind === 'prompt' ? once(doc, typeof id === 'string' ? `u:${id}` : null, u.text) : []
  }
  if (o.type === 'assistant') {
    // Claude Code's own error lines aren't Claude's words.
    if (o.isApiErrorMessage || o.message?.model === '<synthetic>') return []
    const blocks = Array.isArray(o.message?.content) ? o.message.content : []
    const msgId = o.message?.id ?? o.uuid
    const out: Msg[] = []
    for (const b of blocks) {
      if (b?.type !== 'text' || typeof b.text !== 'string' || !b.text.trim()) continue
      // The same block written twice (a message in parts) counts once.
      const key = `t:${msgId}:${b.text.length}:${b.text.slice(0, 32)}`
      if (doc.seen.has(key)) continue
      doc.seen.add(key)
      out.push({ role: 'assistant', text: b.text })
    }
    return out
  }
  return []
}

function userText(content: unknown): string | null {
  const u = classifyUserText(content)
  if (u?.kind === 'prompt') return u.text
  if (u?.kind === 'command') return commandText(u.command)
  // A command's output isn't the owner's words.
  return null
}

function once(doc: Doc, key: string | null, text: string | null): Msg[] {
  if (!text?.trim()) return []
  if (key) {
    if (doc.seen.has(key)) return []
    doc.seen.add(key)
  }
  return [{ role: 'user', text }]
}

function take(doc: Doc, line: Buffer): void {
  // Tool results and Claude's tool calls alone are most of a transcript's bytes: never parsed.
  if (line.includes('"tool_use_id":')) return
  if (line.includes('"type":"assistant"') && !line.includes('"type":"text"')) return
  let o: unknown
  try {
    o = JSON.parse(line.toString('utf8'))
  } catch {
    return
  }
  for (const m of textsOf(o, doc)) {
    doc.msgs.push(m)
    doc.chars += m.text.length
    cachedChars += m.text.length
  }
}

/** Reads [path] from where its cached text ends, up to [end], whole lines only. False: [stop] said stop first. */
async function fill(doc: Doc, path: string, end: number, stop: () => boolean): Promise<boolean> {
  if (doc.offset >= end) return true
  const fh = await open(path, 'r')
  try {
    const buf = Buffer.allocUnsafe(CHUNK)
    let pos = doc.offset
    let parts: Buffer[] = []
    let partLen = 0
    let skipping = false
    while (pos < end) {
      if (stop()) return false
      const { bytesRead } = await fh.read(buf, 0, Math.min(CHUNK, end - pos), pos)
      if (bytesRead <= 0) break
      let start = 0
      for (;;) {
        const nl = buf.indexOf(10, start)
        if (nl < 0 || nl >= bytesRead) break
        if (!skipping) take(doc, partLen ? Buffer.concat([...parts, buf.subarray(start, nl)]) : buf.subarray(start, nl))
        parts = []
        partLen = 0
        skipping = false
        start = nl + 1
        doc.offset = pos + start
      }
      if (!skipping && start < bytesRead) {
        partLen += bytesRead - start
        if (partLen > LINE_MAX) {
          skipping = true
          parts = []
        } else parts.push(Buffer.from(buf.subarray(start, bytesRead)))
      }
      pos += bytesRead
    }
    // Past the cap there's nothing more to read; a line it cut is left out.
    if (end === FILE_MAX) doc.offset = end
    return true
  } finally {
    await fh.close()
  }
}

/** The cached text for [path], brought up to date with the file. Null: it can't be read. */
async function docFor(path: string, stop: () => boolean): Promise<{ doc: Doc; whole: boolean } | null> {
  let st: { ino: number; size: number; mtimeMs: number }
  try {
    st = statSync(path)
  } catch {
    return null
  }
  let doc = cache.get(path)
  // The same file grown (Claude Code only appends): read on from where it ended. Anything else is read afresh.
  if (doc && (doc.ino !== st.ino || st.size < doc.offset || (st.size === doc.size && st.mtimeMs !== doc.mtime))) {
    drop(path)
    doc = undefined
  }
  if (!doc) doc = { ino: st.ino, size: 0, mtime: 0, offset: 0, msgs: [], seen: new Set(), chars: 0 }
  // Most recently searched last, so the oldest go first.
  cache.delete(path)
  cache.set(path, doc)
  doc.size = st.size
  doc.mtime = st.mtimeMs
  let whole: boolean
  try {
    whole = await fill(doc, path, Math.min(st.size, FILE_MAX), stop)
  } catch {
    drop(path)
    return null
  }
  trim(path)
  return { doc, whole }
}

function drop(path: string): void {
  const d = cache.get(path)
  if (!d) return
  cachedChars -= d.chars
  cache.delete(path)
}

function trim(keep: string): void {
  for (const p of cache.keys()) {
    if (cachedChars <= CACHE_CHARS) return
    if (p !== keep) drop(p)
  }
}

/** Forget every transcript's text (tests). */
export function clearSearchCache(): void {
  cache.clear()
  cachedChars = 0
}

/**
 * [doc] against the words: every word somewhere in the conversation (AND), case-insensitively.
 * The snippet comes from the first message with all of them, else from the first with any.
 */
export function matchDoc(msgs: Msg[], words: string[]): Omit<SearchHit, 'id'> | null {
  if (!words.length) return null
  // Longest first, so "ports" wins over "port" where both fit.
  const sorted = [...words].sort((a, b) => b.length - a.length)
  const re = new RegExp(sorted.map((w) => `(${escapeRe(w)})`).join('|'), 'giu')
  const found = new Set<number>()
  let matches = 0
  let best: { msg: Msg; hits: SearchMark[]; all: boolean } | null = null
  for (const msg of msgs) {
    const hits: SearchMark[] = []
    const here = new Set<number>()
    re.lastIndex = 0
    for (let m = re.exec(msg.text); m; m = re.exec(msg.text)) {
      const g = m.findIndex((v, i) => i > 0 && v !== undefined)
      here.add(g)
      found.add(g)
      hits.push({ start: m.index, end: m.index + m[0].length })
      if (m[0].length === 0) re.lastIndex++
    }
    if (!hits.length) continue
    matches += hits.length
    const all = here.size === sorted.length
    if (!best || (all && !best.all)) best = { msg, hits, all }
  }
  if (!best || found.size < sorted.length) return null
  return { ...snippetOf(best.msg.text, best.hits), role: best.msg.role, matches }
}

const isLow = (s: string, i: number) => i > 0 && i < s.length && /[\uDC00-\uDFFF]/.test(s[i]!)

/** About [SNIPPET] characters of [text] around its first hit, whitespace squeezed, the hits marked. */
export function snippetOf(text: string, hits: SearchMark[]): { snippet: string; marks: SearchMark[] } {
  const first = hits[0]!
  let a = Math.max(0, Math.min(first.start - Math.floor((SNIPPET - (first.end - first.start)) / 3), text.length - SNIPPET))
  // Start at a word when one begins shortly before the hit.
  if (a > 0) {
    const sp = text.slice(a, first.start).search(/\s/)
    if (sp >= 0 && sp < 16) a += sp + 1
  }
  if (isLow(text, a)) a++
  let b = Math.min(text.length, a + SNIPPET)
  if (isLow(text, b)) b--
  // Each of text's characters from a to b: where it lands in the snippet.
  const at = new Array<number>(b - a + 1)
  let out = a > 0 ? '…' : ''
  let space = true
  for (let i = a; i < b; i++) {
    const ch = text[i]!
    at[i - a] = out.length
    if (/\s/.test(ch)) {
      if (!space) out += ' '
      space = true
    } else {
      out += ch
      space = false
    }
  }
  if (out.endsWith(' ')) out = out.slice(0, -1)
  at[b - a] = out.length
  const marks: SearchMark[] = []
  for (const h of hits) {
    const s = Math.max(h.start, a)
    const e = Math.min(h.end, b)
    if (s >= e) continue
    marks.push({ start: at[s - a]!, end: Math.min(at[e - a]!, out.length) })
  }
  if (b < text.length) out += '…'
  return { snippet: out, marks }
}

/**
 * Search [sources] (newest first) for [query]. Stops at [max] results, or when the time is up
 * (`partial`), or as soon as [aborted] says the asker has gone. Searches run one at a time.
 */
export async function searchTranscripts(
  query: string,
  sources: SearchSource[],
  opts: { max?: number; budgetMs?: number; now?: () => number; aborted?: () => boolean } = {},
): Promise<SearchResult> {
  const run = queue.then(() => search(query, sources, opts))
  queue = run.catch(() => {})
  return run
}
let queue: Promise<unknown> = Promise.resolve()

async function search(query: string, sources: SearchSource[], opts: { max?: number; budgetMs?: number; now?: () => number; aborted?: () => boolean }): Promise<SearchResult> {
  const words = queryWords(query)
  const max = opts.max ?? SEARCH_MAX
  const now = opts.now ?? (() => performance.now())
  const budget = opts.budgetMs ?? SEARCH_BUDGET_MS
  const aborted = opts.aborted ?? (() => false)
  const t0 = now()
  const late = () => now() - t0 >= budget
  const results: SearchHit[] = []
  let partial = false
  let breath = now()
  if (!words.length) return { results, partial }
  for (const s of [...sources].sort((x, y) => y.at - x.at)) {
    if (results.length >= max || aborted()) break
    if (late()) {
      partial = true
      break
    }
    const got = await docFor(s.path, () => late() || aborted())
    if (!got) continue
    if (!got.whole) partial = true
    const hit = matchDoc(got.doc.msgs, words)
    if (hit) results.push({ id: s.id, ...hit })
    // Matching cached text takes no reads: let the daemon's other work in now and then.
    if (now() - breath > 25) {
      await new Promise((r) => setImmediate(r))
      breath = now()
    }
    if (!got.whole) break
  }
  return { results, partial }
}

/**
 * Every Claude Code session the phone lists (Home and History alike) whose transcript sits in Claude
 * Code's projects folder: nothing else is ever read. Codex's sessions have no transcript here.
 */
export function historySources(): SearchSource[] {
  const root = resolve(join(CLAUDE_DIR, 'projects')) + sep
  return listSessionRecs()
    .filter((r) => !isCodexRec(r) && r.transcriptPath.endsWith('.jsonl') && resolve(r.transcriptPath).startsWith(root))
    .map((r) => ({ id: r.id, path: r.transcriptPath, at: r.lastActivityAt }))
}

/** `GET /v1/history/search?q=`: the sessions whose conversations have every word of [query]. */
export async function searchHistory(query: string, aborted?: () => boolean): Promise<SearchResult> {
  const found = await searchTranscripts(query, historySources(), { aborted })
  // The usage probe's own sessions (term/hidden.ts) are never listed.
  const recs = new Map(listSessionRecs().map((r) => [r.id, r]))
  return { ...found, results: found.results.filter((h) => !isHiddenCwd(recs.get(h.id)?.cwd)) }
}
