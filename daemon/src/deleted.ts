// Sessions the owner deleted (docs/PROTOCOL.md, "Session menu"; DECISIONS, "Delete for every finished
// session"): gone from Home, History and search. Claude Code keeps a transcript of each, `claude rm`
// too, so only this list (<support>/deleted-sessions.json) keeps the next start from finding it there
// and putting it back. No imports from the hub, which asks this file about every session it first sees.
//
// `claude --resume <id>` brings one back: its claude is a running session again. By hand: drop its
// line from the file and restart the daemon, and it's back in History.
import { join } from 'node:path'
import { SUPPORT, readJson, writeJson } from './util.ts'

/** [at]: when it was deleted. [pid]: its claude then, if it had one, on its way out. */
export type Deleted = { at: number; pid: number | null }

const FILE = join(SUPPORT, 'deleted-sessions.json')
let deleted = readJson<Record<string, Deleted>>(FILE, {})
if (!deleted || typeof deleted !== 'object' || Array.isArray(deleted)) deleted = {}

/** Session [id] was deleted (a session's id comes from outside: own keys only). */
export const deletedOf = (id: string): Deleted | null => (Object.hasOwn(deleted, id) ? deleted[id]! : null)

export function setDeleted(id: string, pid: number | null, at = Date.now()): void {
  deleted[id] = { at, pid }
  writeJson(FILE, deleted)
}

/** A claude resumed it: an ordinary session again. */
export function undelete(id: string): void {
  if (!Object.hasOwn(deleted, id)) return
  delete deleted[id]
  writeJson(FILE, deleted)
}
