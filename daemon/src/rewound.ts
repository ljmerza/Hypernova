// Sessions a rewind replaced (rewind.ts; docs/PROTOCOL.md, "Rewind"): the copy is the session now,
// on every screen, and the original shows nowhere. Its transcript stays on disk, untouched; only
// this list (<support>/rewound.json) hides it, or the next start would find it there again and put
// it back in History. What still names the old id (a stale notification, an automation, another
// phone) is followed to the session that replaced it. No imports from the hub, which asks this
// file about every session it first sees.
//
// By hand: drop its line from the file and restart the daemon, and it's back in History;
// `claude --resume <id>` in its folder brings it back as a running session.
import { join } from 'node:path'
import { SUPPORT, readJson, writeJson } from './util.ts'

/** [to]: the session that replaced it. [at]: when it was stopped. [pid]: its claude then, if it had one. */
export type Replaced = { to: string; at: number; pid: number | null }

const FILE = join(SUPPORT, 'rewound.json')
let replaced = readJson<Record<string, Replaced>>(FILE, {})
if (!replaced || typeof replaced !== 'object' || Array.isArray(replaced)) replaced = {}

/** Session [id] was replaced by a rewind (a session's id comes from outside: own keys only). */
export const replacedOf = (id: string): Replaced | null => (Object.hasOwn(replaced, id) ? replaced[id]! : null)

/** Session [id] was replaced by session [to]; [pid] is the claude that was stopped, on its way out. */
export function setReplaced(id: string, to: string, pid: number | null, at = Date.now()): void {
  replaced[id] = { to, at, pid }
  writeJson(FILE, replaced)
}

/** A claude resumed it, or someone wrote to it: an ordinary session again. */
export function bringBack(id: string): void {
  if (!Object.hasOwn(replaced, id)) return
  delete replaced[id]
  writeJson(FILE, replaced)
}

/** The session [id] is now: itself, or what replaced it, however many rewinds ago. */
export function wentOn(id: string): string {
  const seen = new Set([id])
  let at = id
  for (let a = replacedOf(at); a && !seen.has(a.to); a = replacedOf(at)) {
    seen.add(a.to)
    at = a.to
  }
  return at
}

/** Every session replaced so far and what it is now: the snapshot's, for a phone that was away. */
export const replacedSessions = (): Record<string, string> => Object.fromEntries(Object.keys(replaced).map((id) => [id, wentOn(id)]))

/**
 * A claude Claude Code's registry lists for a replaced session is the one that was stopped, still
 * on its way out: its pid, or started before the stop. Anything else resumed that conversation.
 */
export const stoppedProcess = (a: Replaced, pid: unknown, startedAt: unknown): boolean => pid === a.pid || (typeof startedAt === 'number' && startedAt > 0 && startedAt <= a.at)
