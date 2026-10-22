// Switching agents mid-conversation (docs/PROTOCOL.md, "Switching agents"): the owner picks how
// much of the conversation to carry over, reads and edits it, and confirms. The other agent then
// starts a session of its own in the same folder with that text as background (Codex: put into
// its thread; Claude Code: --append-system-prompt), and runs nothing until the next message. The
// two sessions are linked (switch-links.ts); a terminal showing the first one moves to the new one.
import { ensureParsed, getSession, isLiveTerminal, listSessions, pendingApprovals, upsertSession } from './hub.ts'
import type { Ev } from './normalize.ts'
import { addLink, chainBefore, linkOf } from './switch-links.ts'
import { createSession } from './runner.ts'
import { createSharedSession, moveTerm, openCodexTerminal, startedTerm, stopTerm, termOf, waitForTerm } from './terms.ts'
import { codexBinary } from './codex/client.ts'
import { isCodex, isCodexMode, type CodexMode } from './codex/threads.ts'
import { createCodexSession, ensureCodexHistory, leave } from './codex/sessions.ts'
import { projectNameOf } from './util.ts'

export type Agent = 'claude' | 'codex'
export type ContextChoice = 'recent' | 'all' | 'none'
/** How many messages "recent" carries: the owner's and the agent's, with the tools between them. */
export const RECENT = 20
/**
 * The most a switch carries, in UTF-8 bytes. Claude Code takes it as one argument, and Linux caps
 * one at 128 KiB (MAX_ARG_STRLEN).
 */
export const MAX_CONTEXT = 96 * 1024

export class SwitchError extends Error {
  status: number
  code: string
  extra?: Record<string, unknown>
  constructor(status: number, code: string, message: string, extra?: Record<string, unknown>) {
    super(message)
    this.status = status
    this.code = code
    this.extra = extra
  }
}

const agentOf = (id: string): Agent => (isCodex(id) ? 'codex' : 'claude')
const nameOf = (a: Agent) => (a === 'codex' ? 'Codex' : 'Claude')

/** A session's visible conversation as lines: the owner's messages, the agent's replies, and one line per tool. */
function lines(events: Ev[], agent: Agent): { text: string; message: boolean }[] {
  const out: { text: string; message: boolean }[] = []
  for (const e of events) {
    if (e.kind === 'user' && e.text.trim()) out.push({ text: `User: ${e.text.trim()}${e.files?.length ? ` [attached: ${e.files.map((f) => f.name).join(', ')}]` : ''}`, message: true })
    else if (e.kind === 'assistant' && e.text.trim()) out.push({ text: `${nameOf(agent)}: ${e.text.trim()}`, message: true })
    else if (e.kind === 'tool') out.push({ text: `  (${e.name === 'Bash' ? 'ran' : e.name === 'Edit' || e.name === 'Write' ? 'edited' : e.name === 'Read' ? 'read' : 'used ' + e.name}: ${e.summary})`, message: false })
  }
  return out
}

/** The last [n] messages of [ls], with the tool lines among them. */
function lastMessages(ls: { text: string; message: boolean }[], n: number) {
  let seen = 0
  let i = ls.length
  while (i > 0 && seen < n) if (ls[--i]!.message) seen++
  return ls.slice(i)
}

const bytes = (s: string) => Buffer.byteLength(s, 'utf8')

/**
 * What a switch from session [id] would carry over: its last messages, the whole conversation
 * (across earlier switches, oldest first), or nothing. Cut from the oldest end to fit.
 */
export async function previewSwitch(id: string, to: Agent, choice: ContextChoice): Promise<{ text: string; messages: number; bytes: number; maxBytes: number }> {
  const rec = getSession(id)
  if (!rec) throw new SwitchError(404, 'no_session', 'That session no longer exists.')
  if (agentOf(id) === to) throw new SwitchError(409, 'same_agent', `This conversation already runs on ${nameOf(to)}.`)
  if (choice === 'none') return { text: '', messages: 0, bytes: 0, maxBytes: MAX_CONTEXT }
  const ids = choice === 'all' ? [...chainBefore(id).reverse(), id] : [id]
  let all: { text: string; message: boolean }[] = []
  for (const sid of ids) {
    if (isCodex(sid)) await ensureCodexHistory(sid)
    const r = getSession(sid)
    if (r) all = all.concat(lines(ensureParsed(r).events, agentOf(sid)))
  }
  let picked = choice === 'recent' ? lastMessages(all, RECENT) : all
  let text = picked.map((l) => l.text).join('\n')
  // Too long: the oldest go first, a message at a time.
  while (bytes(text) > MAX_CONTEXT && picked.length > 1) {
    const next = picked.findIndex((l, k) => k > 0 && l.message)
    picked = picked.slice(next > 0 ? next : 1)
    text = `(Older messages left out.)\n${picked.map((l) => l.text).join('\n')}`
  }
  if (bytes(text) > MAX_CONTEXT) text = text.slice(0, MAX_CONTEXT / 4)
  return { text, messages: picked.filter((l) => l.message).length, bytes: bytes(text), maxBytes: MAX_CONTEXT }
}

/** The approved text as the other agent gets it: background, plainly not a request. */
export function framed(text: string, from: Agent, cwd: string): string {
  if (!text.trim()) return ''
  return `The user was working with ${nameOf(from)} in ${projectNameOf(cwd)} (${cwd}) and switched to you in Hypernova. Here is that conversation so far, as the user approved it, for reference. It is background, not a new request: wait for the user's next message.\n\n<previous_conversation>\n${text.trim()}\n</previous_conversation>`
}

/**
 * Switch session [id] to the other agent with the approved [text]. The source must be at rest (no
 * turn, queue or question waiting: 409 `busy`), not already switched (409 `switched`), and not a
 * Claude Code open in a terminal Hypernova doesn't hold (409 `terminal_session`). Returns the new session.
 */
export async function commitSwitch(id: string, b: { to?: unknown; text?: unknown; model?: unknown; effort?: unknown; permissionMode?: unknown }) {
  // One switch at a time per conversation: a second tap (or a send again) waits its turn and then
  // finds it switched.
  const before = switching.get(id) ?? Promise.resolve()
  const done = before.catch(() => {}).then(() => switchNow(id, b))
  switching.set(id, done)
  try {
    return await done
  } finally {
    if (switching.get(id) === done) switching.delete(id)
  }
}
const switching = new Map<string, Promise<unknown>>()

async function switchNow(id: string, b: { to?: unknown; text?: unknown; model?: unknown; effort?: unknown; permissionMode?: unknown }) {
  const rec = getSession(id)
  if (!rec) throw new SwitchError(404, 'no_session', 'That session no longer exists.')
  const to: Agent = b.to === 'codex' ? 'codex' : b.to === 'claude' ? 'claude' : (() => { throw new SwitchError(400, 'bad_agent', 'Switch to claude or codex.') })()
  const from = agentOf(id)
  if (from === to) throw new SwitchError(409, 'same_agent', `This conversation already runs on ${nameOf(to)}.`)
  const next = linkOf(id).to
  if (next) throw new SwitchError(409, 'switched', 'This conversation already went on in another session.', { to: next })
  if (rec.state === 'working' || rec.state === 'starting' || rec.state === 'needs_you' || rec.queued > 0 || pendingApprovals().some((a) => a.sessionId === id)) throw new SwitchError(409, 'busy', `${nameOf(from)} is still at work here. Stop it first, then switch.`)
  if (from === 'claude' && isLiveTerminal(id) && !termOf(id)) throw new SwitchError(409, 'terminal_session', 'This session runs in a terminal Hypernova doesn’t hold. End it there, then switch.')
  if (to === 'codex' && !codexBinary().ok) throw new SwitchError(409, 'no_codex', 'Codex isn’t installed on this computer, or it’s too old for Hypernova.')
  const text = typeof b.text === 'string' ? b.text : ''
  if (bytes(text) > MAX_CONTEXT) throw new SwitchError(413, 'context_too_large', `That’s more than ${MAX_CONTEXT / 1024} KB. Shorten it, or pick Recent.`, { maxBytes: MAX_CONTEXT })
  const str = (v: unknown) => (typeof v === 'string' && v.trim() ? v.trim() : null)
  const context = framed(text, from, rec.cwd)

  // The new session: Codex's thread with the context put in, or Claude Code waiting with it.
  let dst: string
  if (to === 'codex') {
    const mode = b.permissionMode == null ? 'read-only' : b.permissionMode
    if (!isCodexMode(mode)) throw new SwitchError(400, 'bad_mode', 'Codex runs read-only or workspace-write.')
    dst = await createCodexSession({ cwd: rec.cwd, context, title: rec.title, model: str(b.model), effort: str(b.effort), mode: mode as CodexMode })
  } else {
    const mode = str(b.permissionMode) ?? 'default'
    if (!['default', 'acceptEdits', 'plan', 'auto', 'bypassPermissions'].includes(mode)) throw new SwitchError(400, 'bad_mode', 'That isn’t a permission mode.')
    const opts = { cwd: rec.cwd, title: rec.title, model: str(b.model), permissionMode: mode, ...(context ? { context } : {}) }
    dst = (await sharedNow()) ? createSharedSession(opts) : createSession(opts)
  }
  addLink(id, dst)

  // A terminal showing the old session follows to the new one; with nobody looking, it just ends.
  const tid = termOf(id)
  const t = tid ? listSessions().find((s) => s.id === id)?.term : null
  if (tid && t && t.mac + t.phone > 0) {
    let into: string | null = null
    try {
      into = to === 'codex' ? await openCodexTerminal(dst) : startedTerm(dst)
      if (into && !(await waitForTerm(into))) into = null
    } catch {
      into = null
    }
    moveTerm(tid, into, into ? undefined : `This conversation went on with ${nameOf(to)} in Hypernova.`)
  } else if (tid) stopTerm(tid, null)
  // The old one rests: a Codex thread is no longer followed, its session ended like a closed terminal's.
  if (from === 'codex') {
    leave(id)
    upsertSession(id, { state: 'ended', activity: null })
  }
  return listSessions().find((s) => s.id === dst)
}

/** Phone sessions run in shared terminals when this computer can (http.ts, shared). */
let sharedNow: () => Promise<boolean> = async () => false
export const useShared = (f: () => Promise<boolean>) => {
  sharedNow = f
}
