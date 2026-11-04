// One entry of a session's queue, taken out so the owner can remove or change it (docs/PROTOCOL.md,
// "Taking a prompt out of the queue"). The daemon's own lines (an SDK session's, Codex's) just give it up.
// Claude Code's queue has no such thing: the Up arrow in an empty box gives back all of it at once
// (measured on 2.1.316 and 2.1.318), so here all of it comes out, and what stays is typed back in, in
// order. Being edited, a prompt moves to the held line (held.ts), where nothing sends it until the owner has
// finished, and whatever was behind it waits behind it there.
import { attachAgentsTerm, termOf, typeInto, unqueue } from './terms.ts'
import { agentJobOf, ensureParsed, getSession, isCodexRec, readQueueNow } from './hub.ts'
import { holdFirst } from './held.ts'
import { splitAttachments } from './attachments.ts'
import { isSdk, settings } from './store.ts'
import { takeQueued } from './runner.ts'
import { takeCodexQueued } from './codex/sessions.ts'
import type { QueueEntry } from './normalize.ts'

export type Taken =
  | { ok: true; heldId?: string; text?: string }
  /**
   * `gone`: it reached Claude first. `typing`, `waiting`: unsent words or a dialog in the terminal (nothing
   * came out). `unreadable`: Claude Code's queue holds a prompt whose text wasn't logged, which couldn't be
   * put back (nothing came out). `no_terminal`, `busy`: no way to it right now.
   */
  | { ok: false; code: 'gone' | 'typing' | 'waiting' | 'unreadable' | 'no_terminal' | 'busy'; pending?: string }

/** Sessions whose queue is out and on its way back: what's sent meanwhile waits behind it. */
const rebuilding = new Set<string>()
export const rebuildingQueue = (id: string): boolean => rebuilding.has(id)

const sleep = (ms: number) => new Promise((ok) => setTimeout(ok, ms))
/** As it was typed: Claude Code logs a "!" line without its "!". */
const typedAs = (e: QueueEntry) => (e.bash && !e.content.startsWith('!') ? `!${e.content}` : e.content)
const heldFrom = (e: QueueEntry, editing = false) => ({ prompt: typedAs(e), at: e.at, clientId: e.clientId, editing })

/** Out of the line and gone ([mode] `remove`), or into the held line, being edited ([mode] `edit`). */
export async function takeFromQueue(id: string, queueId: string, mode: 'remove' | 'edit'): Promise<Taken> {
  const r = getSession(id)
  if (!r) return { ok: false, code: 'gone' }
  if (isSdk(id) || isCodexRec(r)) {
    const list = (isSdk(id) ? takeQueued : takeCodexQueued)(id, queueId, mode === 'edit')
    if (!list?.length) return { ok: false, code: 'gone' }
    return mode === 'remove' ? { ok: true } : intoHeld(id, list[0]!, list.slice(1), [])
  }
  if (rebuilding.has(id)) return { ok: false, code: 'busy' }
  const st = ensureParsed(r)
  readQueueNow(id)
  if (!st.queue.some((e) => e.id === queueId)) return { ok: false, code: 'gone' }
  // Up would bring it back and nothing could type it again.
  if (st.queue.some((e) => !e.content)) return { ok: false, code: 'unreadable' }
  const job = settings.claudeAgents === true && !termOf(id) ? agentJobOf(id) : null
  const tid = job ? await attachAgentsTerm(id, r.cwd, job) : termOf(id)
  if (!tid) return { ok: false, code: 'no_terminal' }

  rebuilding.add(id)
  st.popCatch = []
  try {
    const out = await unqueue(tid)
    if (!out.ok) {
      if (out.reason === 'busy_input') return { ok: false, code: 'typing', ...(out.text ? { pending: out.text } : {}) }
      if (out.reason === 'no_input') return { ok: false, code: 'waiting' }
      return { ok: false, code: 'busy' }
    }
    // Logged at once. Whatever Claude took first (a step ended) has left the queue by its own way.
    for (let i = 0; i < 40 && st.queue.length; i++) {
      await sleep(75)
      readQueueNow(id)
    }
    const popped = st.popCatch ?? []
    st.popCatch = null
    const at = popped.findIndex((e) => e.id === queueId)
    const target = popped[at] ?? null
    const stays = !target ? popped : mode === 'remove' ? popped.filter((e) => e !== target) : popped.slice(0, at)
    const behind = target && mode === 'edit' ? popped.slice(at + 1) : []
    // Typed back in order; what the box won't take now waits first in the held line, so it isn't lost.
    const left: QueueEntry[] = []
    for (const e of stays) {
      if (left.length || !(await typeInto(tid, typedAs(e), false, e.clientId ?? undefined)).ok) left.push(e)
    }
    if (!target) {
      holdFirst(id, left.map((e) => heldFrom(e)))
      return { ok: false, code: 'gone' }
    }
    if (mode === 'remove') {
      holdFirst(id, left.map((e) => heldFrom(e)))
      return { ok: true }
    }
    return intoHeld(id, target, behind, left)
  } finally {
    st.popCatch = null
    rebuilding.delete(id)
  }
}

/** [target], being edited, with [before] ahead of it and [behind] behind it. Its words for the edit sheet. */
function intoHeld(id: string, target: QueueEntry, behind: QueueEntry[], before: QueueEntry[]): Taken {
  const made = holdFirst(id, [...before.map((e) => heldFrom(e)), heldFrom(target, true), ...behind.map((e) => heldFrom(e))])
  return { ok: true, heldId: made[before.length]!.id, text: splitAttachments(typedAs(target)).text }
}
