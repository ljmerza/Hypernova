// Claude Code's own summary of past days (<claude>/stats-cache.json), read and never written, for
// the days from before Hypernova counted anything (docs/PROTOCOL.md, "Statistics", "Older days").
// Its transcripts go after 30 days by default, but this file goes further back. It has counts per
// day and nothing else that leaves here: its tokens (2.2 times Hypernova's on the days both cover,
// docs/DECISIONS.md), its longest session and its models stay in the file. What it has is copied
// into stats.json (stats.ts, keptOlderDays), so losing the file later loses nothing.
//
// The file isn't a contract: any version or shape other than the one known means nothing new from
// it, said once in the log, and the numbers are Hypernova's own as ever.
import { readFile, stat } from 'node:fs/promises'
import { join } from 'node:path'
import { CLAUDE_DIR, log } from './util.ts'

const FILE = join(CLAUDE_DIR, 'stats-cache.json')
/** The one shape read so far: `version` 5. */
const KNOWN_VERSION = 5
/** It's tens of KB; anything like this size is something else. */
const MAX_BYTES = 16 * 1024 * 1024

/**
 * One day as the file has it. [messages] only says the day had activity: it counts every message
 * of a conversation, tens of times the prompts the owner wrote, so it never goes into `prompts`.
 */
export type CacheDay = { day: string; sessions: number; toolCalls: number; messages: number }

const DAY = /^\d{4}-\d{2}-\d{2}$/
const count = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v) && v >= 0

/** The days in [text], oldest first; a reason as a string when it isn't the shape known. */
export function parseCache(text: string): CacheDay[] | string {
  let o: any
  try {
    o = JSON.parse(text)
  } catch {
    return 'not JSON'
  }
  if (o?.version !== KNOWN_VERSION) return `version ${String(o?.version)}`
  if (!Array.isArray(o.dailyActivity)) return 'no dailyActivity'
  const days = new Map<string, CacheDay>()
  for (const r of o.dailyActivity) {
    if (typeof r?.date !== 'string' || !DAY.test(r.date) || !count(r.sessionCount) || !count(r.toolCallCount) || !count(r.messageCount)) return 'a dailyActivity row changed shape'
    days.set(r.date, { day: r.date, sessions: r.sessionCount, toolCalls: r.toolCallCount, messages: r.messageCount })
  }
  return [...days.values()].sort((a, b) => (a.day < b.day ? -1 : 1))
}

let seen: { mtimeMs: number; size: number; days: CacheDay[] } | null = null
let said: string | null = null

/** Said once per reason: this is asked for on every answer. */
function none(why: string): CacheDay[] {
  seen = null
  if (said !== why) {
    said = why
    log(`stats: no older days from stats-cache.json: ${why}`)
  }
  return []
}

/** The file's days, read again only once it has changed. Nothing there, or nothing known: none. */
export async function cacheDays(): Promise<CacheDay[]> {
  try {
    const st = await stat(FILE)
    if (seen && seen.mtimeMs === st.mtimeMs && seen.size === st.size) return seen.days
    if (st.size > MAX_BYTES) return none('too big')
    const days = parseCache(await readFile(FILE, 'utf8'))
    if (typeof days === 'string') return none(days)
    said = null
    seen = { mtimeMs: st.mtimeMs, size: st.size, days }
    return days
  } catch (e: any) {
    return none(e?.code === 'ENOENT' ? 'no such file' : String(e?.message ?? e))
  }
}
