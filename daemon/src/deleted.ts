// Sessions the owner took off Home (docs/PROTOCOL.md, "Session menu"; DECISIONS, "Delete takes a session
// off Home, History brings it back"): the menu's Remove from list. The conversation stays, in History and
// search, and nothing about it is lost. Only this list (<support>/deleted-sessions.json, named for what
// it meant before) keeps a late word from its stopped claude, or a restart's look at its transcript,
// from putting it back on Home as if it ran. No imports from the hub, which asks this file about every
// session it sees.
//
// Resuming it takes its line out: a prompt typed into it, `…/resume`, or `claude --resume <id>`, its
// claude a running session again. Opening it to read in History starts nothing and changes nothing.
import { join } from 'node:path'
import { SUPPORT, readJson, writeJson } from './util.ts'

/** [at]: when it was taken off Home. [pid]: its claude then, if it had one, on its way out. */
export type Deleted = { at: number; pid: number | null }

const FILE = join(SUPPORT, 'deleted-sessions.json')
let deleted = readJson<Record<string, Deleted>>(FILE, {})
if (!deleted || typeof deleted !== 'object' || Array.isArray(deleted)) deleted = {}

/** Session [id] was taken off Home (a session's id comes from outside: own keys only). */
export const deletedOf = (id: string): Deleted | null => (Object.hasOwn(deleted, id) ? deleted[id]! : null)

export function setDeleted(id: string, pid: number | null, at = Date.now()): void {
  deleted[id] = { at, pid }
  writeJson(FILE, deleted)
}

/** Resumed: an ordinary session again. */
export function undelete(id: string): void {
  if (!Object.hasOwn(deleted, id)) return
  delete deleted[id]
  writeJson(FILE, deleted)
}
