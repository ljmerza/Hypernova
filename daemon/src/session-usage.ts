// One session's usage, for the strip at the top of the chat (docs/PROTOCOL.md, "Session usage"):
// its model, how full its context window is, the tokens it has used and what they cost, and the
// plan's 5-hour window.
//
// Read from Claude Code's own transcripts (the session's and its agents'), each once and then
// only the bytes added since, like the statistics. Claude Code's status line, where the tap has
// one for this session (interactive sessions only), knows two things the transcript doesn't: the
// context window it really uses and its own count of the cost. Without it both are worked out
// here, so the phone says "about".
import { closeSync, fstatSync, openSync, readSync, readdirSync, statSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { SUPPORT, readJson } from './util.ts'
import { getUsage, type Window } from './hub.ts'
import { isSubscription } from './store.ts'

export type SessionTokens = { input: number; output: number; cacheRead: number; cacheWrite: number }

export type SessionUsage = {
  /** The model the last reply came from, as the API names it ("claude-opus-5-5"); null before any. */
  model: string | null
  /** Its everyday name ("Opus 5.5"); null when the model isn't one we know. */
  modelName: string | null
  /**
   * The context window: the last reply's whole prompt ([used] tokens) against the window's size.
   * [exact]: the size is Claude Code's own (its status line); else it's the model's usual window.
   * Null before the first reply, and right after a compaction until the next one.
   */
  context: { used: number; window: number; pct: number; exact: boolean } | null
  /** Every token the session's replies used, its agents' included ([agentTokens] of them). */
  tokens: SessionTokens
  agentTokens: number
  /**
   * What those tokens cost at API list prices, in US dollars. [costExact]: Claude Code's own count
   * from its status line; else worked out here from the tokens (null for a model or provider whose
   * prices aren't known: another provider bills its own way).
   */
  costUsd: number | null
  costExact: boolean
  /** The plan's windows, as the snapshot has them (they're the account's, not the session's). */
  fiveHour: Window | null
  sevenDay: Window | null
  usageSource: 'official' | 'none' | 'stale'
  /** When the transcript last changed (epoch ms). */
  updatedAt: number
}

// ── Prices and windows (Claude Code's own model table) ────────────────────
/**
 * US dollars per million tokens: input, output, cache writes for 5 minutes and for an hour, cache
 * reads; then each web search. `long`: above this many prompt tokens a request costs [longPrices].
 */
type Price = { input: number; output: number; write5m: number; write1h: number; read: number; search: number; long?: { above: number; input: number; output: number; write5m: number; write1h: number; read: number } }
const p = (input: number, output: number, write5m: number, write1h: number, read: number): Price => ({ input, output, write5m, write1h, read, search: 0.01 })

/** By model family and version, newest first so "opus-4-5" never reads as "opus-4". */
const MODELS: { match: RegExp; name: string; price: Price; window: number }[] = [
  { match: /opus-5-5/, name: 'Opus 5.5', price: p(4, 20, 5, 8, 0.2), window: 1_000_000 },
  { match: /opus-5(?!-\d(?!\d))/, name: 'Opus 5', price: p(5, 25, 6.25, 10, 0.5), window: 1_000_000 },
  { match: /opus-4-8/, name: 'Opus 4.8', price: p(5, 25, 6.25, 10, 0.5), window: 1_000_000 },
  { match: /opus-4-7/, name: 'Opus 4.7', price: p(5, 25, 6.25, 10, 0.5), window: 1_000_000 },
  { match: /opus-4-6/, name: 'Opus 4.6', price: p(5, 25, 6.25, 10, 0.5), window: 200_000 },
  { match: /opus-4-5/, name: 'Opus 4.5', price: p(5, 25, 6.25, 10, 0.5), window: 200_000 },
  { match: /opus-4-1/, name: 'Opus 4.1', price: p(15, 75, 18.75, 30, 1.5), window: 200_000 },
  { match: /opus-4(?!-\d(?!\d))/, name: 'Opus 4', price: p(15, 75, 18.75, 30, 1.5), window: 200_000 },
  { match: /sonnet-5-5/, name: 'Sonnet 5.5', price: p(2, 10, 2.5, 4, 0.1), window: 1_000_000 },
  { match: /sonnet-5(?!-\d(?!\d))/, name: 'Sonnet 5', price: p(2, 10, 2.5, 4, 0.2), window: 1_000_000 },
  { match: /sonnet-4-6/, name: 'Sonnet 4.6', price: p(3, 15, 3.75, 6, 0.3), window: 200_000 },
  { match: /sonnet-4-5/, name: 'Sonnet 4.5', price: p(3, 15, 3.75, 6, 0.3), window: 200_000 },
  { match: /sonnet-4(?!-\d(?!\d))/, name: 'Sonnet 4', price: p(3, 15, 3.75, 6, 0.3), window: 200_000 },
  { match: /3-7-sonnet/, name: 'Sonnet 3.7', price: p(3, 15, 3.75, 6, 0.3), window: 200_000 },
  { match: /3-5-sonnet/, name: 'Sonnet 3.5', price: p(3, 15, 3.75, 6, 0.3), window: 200_000 },
  {
    match: /haiku-5-5/,
    name: 'Haiku 5.5',
    price: { ...p(0.1, 0.5, 0.125, 0.2, 0.01), long: { above: 100_000, input: 0.5, output: 2.5, write5m: 0.625, write1h: 1, read: 0.05 } },
    window: 1_000_000,
  },
  { match: /haiku-4-5/, name: 'Haiku 4.5', price: p(1, 5, 1.25, 2, 0.1), window: 200_000 },
  { match: /3-5-haiku/, name: 'Haiku 3.5', price: p(0.8, 4, 1, 1.6, 0.08), window: 200_000 },
  { match: /fable-5-1/, name: 'Fable 5.1', price: p(10, 50, 12.5, 20, 0.25), window: 1_000_000 },
  { match: /fable-5(?!-\d(?!\d))/, name: 'Fable 5', price: p(10, 50, 12.5, 20, 1), window: 1_000_000 },
  { match: /mythos-5-1/, name: 'Mythos 5.1', price: p(10, 50, 12.5, 20, 0.25), window: 1_000_000 },
  { match: /mythos-5(?!-\d(?!\d))/, name: 'Mythos 5', price: p(10, 50, 12.5, 20, 1), window: 1_000_000 },
]

/** What we know of a model by its id ("claude-opus-5-5", "claude-sonnet-4-5-20250929", "opus[1m]"…). */
export function modelInfo(model: string | null | undefined): { name: string; price: Price; window: number } | null {
  if (!model) return null
  const m = model.toLowerCase()
  if (!m.includes('claude') && !/^(opus|sonnet|haiku|fable|mythos)/.test(m)) return null
  return MODELS.find((x) => x.match.test(m)) ?? null
}

/**
 * The context window a reply worked in: the model's usual one; 1M when the session asked for it
 * ("[1m]") or when the prompt was already bigger than the usual one could hold.
 */
export function contextWindow(model: string | null, used: number, asked: string | null): number {
  const usual = modelInfo(model)?.window ?? 200_000
  const big = /\[1m\]/i.test(asked ?? '') || /\[1m\]/i.test(model ?? '')
  return big || used > usual ? Math.max(usual, 1_000_000) : usual
}

type Usage = Record<string, any>
const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) && v > 0 ? v : 0)

/** What one API call cost at list prices, or null for a model whose prices we don't know. */
export function callCost(model: string | null, u: Usage): number | null {
  const info = modelInfo(model)
  if (!info) return null
  const input = num(u.input_tokens)
  const read = num(u.cache_read_input_tokens)
  const write = num(u.cache_creation_input_tokens)
  // Claude Code splits cache writes by how long they're kept; older lines don't.
  const cc = u.cache_creation && typeof u.cache_creation === 'object' ? u.cache_creation : null
  const write1h = cc ? Math.min(write, num(cc.ephemeral_1h_input_tokens)) : 0
  const write5m = write - write1h
  const pr = info.price.long && input + read + write > info.price.long.above ? { ...info.price, ...info.price.long } : info.price
  const searches = num(u.server_tool_use?.web_search_requests)
  return (input * pr.input + num(u.output_tokens) * pr.output + write5m * pr.write5m + write1h * pr.write1h + read * pr.read) / 1e6 + searches * pr.search
}

// ── Reading transcripts ───────────────────────────────────────────────────
/** One transcript, added up so far. */
type Tally = {
  path: string
  ino: number
  offset: number
  carry: string
  mtimeMs: number
  tokens: SessionTokens
  cost: number
  /** Calls with no known price: the cost can't be told whole. */
  unpriced: number
  /** The last call of the main conversation: its model and whole prompt; null after a compaction. */
  model: string | null
  context: number | null
  /** Usage already counted per message (a message is logged once per content block). */
  recent: Map<string, { u: SessionTokens; cost: number }>
}

const tallies = new Map<string, Tally>()
const fresh = (path: string): Tally => ({ path, ino: 0, offset: 0, carry: '', mtimeMs: 0, tokens: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, cost: 0, unpriced: 0, model: null, context: null, recent: new Map() })

/** A line bigger than this is parsed only for what we need (a reply that wrote a big file, say). */
const BIG_LINE = 256 * 1024
/** Read at most this much per file per answer: a first look at a long session catches up over a few. */
const STEP = 8 * 1024 * 1024

/** The fields we use from a transcript line, parsed whole when small, picked out when big. */
function lineFields(line: string): { type: string | null; subtype: string | null; id: string | null; model: string | null; usage: Usage | null; sidechain: boolean; inherited: boolean } | null {
  if (!line.includes('"assistant"') && !line.includes('compact_boundary')) return null
  if (line.length <= BIG_LINE) {
    try {
      const o = JSON.parse(line)
      return { type: o.type ?? null, subtype: o.subtype ?? null, id: o.message?.id ?? null, model: o.message?.model ?? null, usage: o.message?.usage ?? null, sidechain: o.isSidechain === true, inherited: !!o.forkedFrom }
    } catch {
      return null
    }
  }
  // Only an assistant line gets this big, with its usage near the end.
  const type = /^\{"[^"]*"/.test(line) && /"type":"assistant"/.test(line.slice(0, 4096)) ? 'assistant' : /"type":"assistant"/.test(line.slice(-8192)) ? 'assistant' : null
  if (!type) return null
  const u = /"usage":(\{(?:[^{}]|\{[^{}]*\})*\})/.exec(line.slice(-16384))
  let usage: Usage | null = null
  try {
    usage = u ? JSON.parse(u[1]!) : null
  } catch {}
  return {
    type,
    subtype: null,
    id: /"id":"(msg_[^"]+)"/.exec(line)?.[1] ?? null,
    model: /"model":"([^"]+)"/.exec(line)?.[1] ?? null,
    usage,
    sidechain: /"isSidechain":true/.test(line.slice(0, 4096)) || /"isSidechain":true/.test(line.slice(-4096)),
    inherited: line.includes('"forkedFrom":'),
  }
}

/** Feed one transcript line into [t]. [agent]: an agent's own transcript (no context of the session's). */
export function feedUsageLine(t: Tally, line: string, agent: boolean): void {
  const f = lineFields(line)
  if (!f) return
  if (f.type === 'system' && f.subtype === 'compact_boundary') {
    // The window was just emptied into a summary: how full it is now shows with the next reply.
    if (!agent && !f.sidechain) t.context = null
    return
  }
  if (f.type !== 'assistant' || !f.usage || f.model === '<synthetic>') return
  const u = f.usage
  const prompt = num(u.input_tokens) + num(u.cache_read_input_tokens) + num(u.cache_creation_input_tokens)
  if (!agent && !f.sidechain) {
    t.model = f.model ?? t.model
    if (prompt > 0) t.context = prompt
  }
  // A fork's copy of its parent's history was the parent's spend, not this session's.
  if (f.inherited) return
  const now: SessionTokens = { input: num(u.input_tokens), output: num(u.output_tokens), cacheRead: num(u.cache_read_input_tokens), cacheWrite: num(u.cache_creation_input_tokens) }
  const cost = callCost(f.model, u)
  const was = f.id ? t.recent.get(f.id) : undefined
  // Only what a later line of the same message adds (its output grows as it streams).
  for (const k of ['input', 'output', 'cacheRead', 'cacheWrite'] as const) t.tokens[k] += Math.max(0, now[k] - (was?.u[k] ?? 0))
  if (cost == null) {
    if (!was) t.unpriced++
  } else t.cost += Math.max(0, cost - (was?.cost ?? 0))
  if (f.id) {
    const keep = was ? { input: Math.max(was.u.input, now.input), output: Math.max(was.u.output, now.output), cacheRead: Math.max(was.u.cacheRead, now.cacheRead), cacheWrite: Math.max(was.u.cacheWrite, now.cacheWrite) } : now
    t.recent.set(f.id, { u: keep, cost: Math.max(cost ?? 0, was?.cost ?? 0) })
    if (t.recent.size > 32) t.recent.delete(t.recent.keys().next().value!)
  }
}

/** Bring [path]'s tally up to date: only the bytes added since the last look (all of it if the file was replaced). */
function refresh(path: string, agent: boolean): Tally | null {
  let st
  try {
    st = statSync(path)
  } catch {
    tallies.delete(path)
    return null
  }
  let t = tallies.get(path)
  if (!t || t.ino !== st.ino || st.size < t.offset) {
    t = fresh(path)
    t.ino = st.ino
    tallies.set(path, t)
  }
  t.mtimeMs = st.mtimeMs
  if (st.size === t.offset) return t
  let fd: number | null = null
  try {
    fd = openSync(path, 'r')
    const size = fstatSync(fd).size
    const end = Math.min(size, t.offset + STEP)
    const buf = Buffer.alloc(end - t.offset)
    const n = readSync(fd, buf, 0, buf.length, t.offset)
    t.offset += n
    const text = t.carry + buf.subarray(0, n).toString('utf8')
    const lines = text.split('\n')
    // The last piece may be a line still being written: kept for the next look.
    t.carry = lines.pop() ?? ''
    // A line far bigger than any we read whole is only ever an assistant one: keep its tail.
    if (t.carry.length > 4 * BIG_LINE) t.carry = t.carry.slice(0, 4096) + t.carry.slice(-BIG_LINE)
    for (const line of lines) if (line) feedUsageLine(t, line, agent)
  } catch {
  } finally {
    if (fd != null) closeSync(fd)
  }
  return t
}

/** Its agents' transcripts: `<session>/subagents/agent-*.jsonl`, and a workflow's one folder down. */
function agentFiles(transcriptPath: string, id: string): string[] {
  const root = join(dirname(transcriptPath), id, 'subagents')
  const out: string[] = []
  const list = (d: string) => {
    try {
      return readdirSync(d, { withFileTypes: true })
    } catch {
      return []
    }
  }
  for (const e of list(root)) {
    if (e.isFile() && e.name.startsWith('agent-') && e.name.endsWith('.jsonl')) out.push(join(root, e.name))
    else if (e.isDirectory() && e.name === 'workflows') {
      for (const run of list(join(root, 'workflows'))) {
        if (!run.isDirectory()) continue
        for (const f of list(join(root, 'workflows', run.name))) if (f.isFile() && f.name.startsWith('agent-') && f.name.endsWith('.jsonl')) out.push(join(root, 'workflows', run.name, f.name))
      }
    }
  }
  return out
}

/** Claude Code's status line for this session, if the tap wrote one lately. */
type StatusLine = { modelId: string | null; modelName: string | null; window: number | null; cost: number | null; at: number }
function statusLine(id: string): StatusLine | null {
  const path = join(SUPPORT, 'statusline', `${id}.json`)
  let at = 0
  try {
    at = statSync(path).mtimeMs
  } catch {
    return null
  }
  // An old one may be from before a /model or a restart: not worth trusting.
  if (Date.now() - at > 6 * 3600_000) return null
  const o = readJson<any>(path, null)
  if (!o || o.session_id !== id) return null
  const n = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : null)
  return {
    modelId: typeof o.model?.id === 'string' ? o.model.id : null,
    modelName: typeof o.model?.display_name === 'string' ? o.model.display_name : null,
    window: n(o.context_window?.context_window_size),
    cost: n(o.cost?.total_cost_usd),
    at,
  }
}

/** The session's usage now. [rec]: its id, transcript, provider and the model it asked for. */
export function sessionUsage(rec: { id: string; transcriptPath: string; providerId: string; model: string | null }): SessionUsage {
  const main = refresh(rec.transcriptPath, false)
  const agents = agentFiles(rec.transcriptPath, rec.id).map((f) => refresh(f, true)).filter((t): t is Tally => t != null)
  const tokens: SessionTokens = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }
  let agentTokens = 0
  let cost = 0
  let unpriced = 0
  for (const t of [main, ...agents]) {
    if (!t) continue
    for (const k of ['input', 'output', 'cacheRead', 'cacheWrite'] as const) tokens[k] += t.tokens[k]
    if (t !== main) agentTokens += t.tokens.input + t.tokens.output + t.tokens.cacheRead + t.tokens.cacheWrite
    cost += t.cost
    unpriced += t.unpriced
  }
  const model = main?.model ?? null
  const line = statusLine(rec.id)
  // The status line's window only for the model it was written for (a /model since changes it).
  const sameModel = !!line?.modelId && !!model && line.modelId.replace(/\[1m\]$/i, '') === model
  const used = main?.context ?? null
  const window = sameModel && line?.window ? line.window : contextWindow(model, used ?? 0, rec.model)
  const context = used != null ? { used, window, pct: Math.min(100, Math.round((used / window) * 100)), exact: sameModel && !!line?.window } : null
  // Another provider bills its own way: no price of ours applies.
  const ours = isSubscription(rec.providerId)
  // Claude Code's own count once it has one at least as recent as the transcript.
  const lineFresh = !!line && line.cost != null && main != null && line.at >= main.mtimeMs - 5000
  const costUsd = !ours ? null : lineFresh ? line!.cost : unpriced > 0 && cost === 0 ? null : cost
  const u = getUsage()
  return {
    model,
    modelName: (sameModel ? line?.modelName : null) ?? modelInfo(model)?.name ?? null,
    context,
    tokens,
    agentTokens,
    costUsd: costUsd == null ? null : Math.round(costUsd * 10000) / 10000,
    costExact: ours && lineFresh,
    // The subscription's windows only for a session that spends them (not one on another provider).
    fiveHour: ours ? u.fiveHour : null,
    sevenDay: ours ? u.sevenDay : null,
    usageSource: ours ? u.source : 'none',
    updatedAt: Math.max(main?.mtimeMs ?? 0, ...agents.map((a) => a.mtimeMs)),
  }
}
