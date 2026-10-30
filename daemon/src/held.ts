// Fork: held prompts (docs/PROTOCOL.md, "Held prompts"). A prompt the phone sends while Claude waits
// on the owner (an approval, a question or a plan, or a dialog or menu its terminal shows instead
// of the input box) isn't refused: it waits here, per session, oldest first, and is typed in once
// Claude Code's input box is back, however the ask was answered (the phone, a notification, the
// terminal, the 10-minute auto-deny). Kept in <support>/held.json, so a daemon restart keeps them.
// What "ready" means and how a prompt is typed come from the prompt route (http.ts, useHeldOps):
// this file only keeps the line and works through it.
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { SUPPORT, log, readJson, writeJson } from './util.ts'
import { splitAttachments, withAttachments, type Attachment } from './attachments.ts'

/** One held prompt: the prompt as Claude Code gets it (attachments as @-mentions first), and the phone send it came from. */
type Entry = { id: string; prompt: string; at: number; clientId: string | null }
/** As `session.held` shows it: the text without its @-mentions, the files apart, as `session.queue` does. */
export type HeldView = { id: string; text: string; files?: Attachment[]; at: number; clientId: string | null }

/** Whether a session's input box could be back: `ready` to try, `wait` for now, `ended` (or gone): nothing will take it. */
export type HeldReady = 'ready' | 'wait' | 'ended'
/** Prompts that left the line without reaching Claude, as a notice's `unsent` names them. */
export type HeldUnsent = { reason: 'returned' | 'dropped'; text: string; prompts: { text: string; clientId: string | null }[] }
export type HeldOps = {
  ready: (sessionId: string) => HeldReady
  /** Type it in, as the prompt route does; throws the route's error (with its `code`) when it can't. */
  send: (sessionId: string, prompt: string, clientId: string | null) => Promise<void>
  /** The session's held prompts changed: its clients hear of it. */
  changed: (sessionId: string) => void
  /** Prompts that won't be sent: the session's notice says so, and the phone takes their text back. */
  unsent: (sessionId: string, u: HeldUnsent) => void
}

/** A session that ended keeps its held prompts this long (it may be resumed at once), then they go. */
export const HELD_ENDED_MS = 30_000
/** How often the line is looked at besides the moments that may have brought the box back. */
export const HELD_TICK_MS = 3000
/** After a try the box didn't take, the next waits this long (a session changes many times a second while Claude works). */
export const HELD_RETRY_MS = 1500

const FILE = join(SUPPORT, 'held.json')
let held: Record<string, Entry[]> = load()
let ops: HeldOps | null = null
/** The prompt being typed now, by session: it can't be edited or removed meanwhile. */
const sending = new Map<string, string>()
/** When each session was first seen ended while it had held prompts. */
const endedSince = new Map<string, number>()
const kicks = new Map<string, NodeJS.Timeout>()
/** When each session's last try failed. */
const failedAt = new Map<string, number>()
let ticker: NodeJS.Timeout | null = null

function load(): Record<string, Entry[]> {
  const o = readJson<Record<string, unknown> | null>(FILE, null)
  const out: Record<string, Entry[]> = {}
  if (!o || typeof o !== 'object' || Array.isArray(o)) return out
  for (const [sid, list] of Object.entries(o)) {
    if (!Array.isArray(list)) continue
    const ok = list.filter((e): e is Entry => !!e && typeof e.id === 'string' && typeof e.prompt === 'string').map((e) => ({ id: e.id, prompt: e.prompt, at: Number(e.at) || Date.now(), clientId: typeof e.clientId === 'string' ? e.clientId : null }))
    if (ok.length) out[sid] = ok
  }
  return out
}

function save(): void {
  try {
    writeJson(FILE, held)
  } catch (e) {
    log('held prompts not saved', e)
  }
}

/** The prompts held for a session, oldest first. */
export const heldOf = (sessionId: string): readonly Entry[] => held[sessionId] ?? []

/** `session.held`, only when something is held: a session with none looks as it did before. */
export function heldView(sessionId: string): { held?: HeldView[] } {
  const list = held[sessionId]
  if (!list?.length) return {}
  return {
    held: list.map((e) => {
      const { text, files } = splitAttachments(e.prompt)
      return { id: e.id, text, ...(files.length ? { files } : {}), at: e.at, clientId: e.clientId }
    }),
  }
}

/** The way the line is worked through (the prompt route's, http.ts). Starts looking if anything is held from before a restart. */
export function useHeldOps(o: HeldOps): void {
  ops = o
  if (Object.keys(held).length) tickSoon()
}

/** Hold [prompt] for session [sessionId], behind any held before it. */
export function hold(sessionId: string, prompt: string, clientId: string | null): Entry {
  const e: Entry = { id: randomUUID(), prompt, at: Date.now(), clientId }
  held[sessionId] = [...(held[sessionId] ?? []), e]
  save()
  ops?.changed(sessionId)
  tickSoon()
  return e
}

/** New words for a held prompt, its files kept. `gone`: no such prompt (sent, or removed); `sending`: being typed now. */
export function editHeld(sessionId: string, heldId: string, text: string): 'ok' | 'gone' | 'sending' {
  const e = held[sessionId]?.find((x) => x.id === heldId)
  if (!e) return 'gone'
  if (sending.get(sessionId) === heldId) return 'sending'
  e.prompt = withAttachments(text, splitAttachments(e.prompt).files.map((f) => f.path))
  save()
  ops?.changed(sessionId)
  return 'ok'
}

/** Take a held prompt back: it's never sent. The same answers as [editHeld]. */
export function removeHeld(sessionId: string, heldId: string): 'ok' | 'gone' | 'sending' {
  if (!held[sessionId]?.some((x) => x.id === heldId)) return 'gone'
  if (sending.get(sessionId) === heldId) return 'sending'
  take(sessionId, [heldId])
  ops?.changed(sessionId)
  return 'ok'
}

function take(sessionId: string, ids: string[]): void {
  const left = (held[sessionId] ?? []).filter((e) => !ids.includes(e.id))
  if (left.length) held[sessionId] = left
  else {
    delete held[sessionId]
    endedSince.delete(sessionId)
    failedAt.delete(sessionId)
  }
  save()
}

/**
 * Something that may have brought session [sessionId]'s input box back (an ask answered, its state
 * or its menu changed): look at its line in a moment, once the burst of changes has settled.
 */
export function kickHeld(sessionId: string | null | undefined): void {
  if (!sessionId || !held[sessionId] || kicks.has(sessionId)) return
  kicks.set(
    sessionId,
    setTimeout(() => {
      kicks.delete(sessionId)
      void drain(sessionId)
    }, 300),
  )
}

function tickSoon(): void {
  if (ticker) return
  ticker = setInterval(tick, HELD_TICK_MS)
  ticker.unref?.()
}

/** Every few seconds while anything is held: each session's line, and the ones whose session ended for good. */
export function tick(now = Date.now()): void {
  const ids = Object.keys(held)
  if (!ids.length && ticker) {
    clearInterval(ticker)
    ticker = null
  }
  for (const sid of ids) {
    if (ops?.ready(sid) === 'ended') {
      const since = endedSince.get(sid) ?? now
      endedSince.set(sid, since)
      if (now - since >= HELD_ENDED_MS && !sending.has(sid)) drop(sid, 'The session ended')
      continue
    }
    endedSince.delete(sid)
    void drain(sid)
  }
}

/** Every prompt held for [sessionId] goes, unsent: the notice names them so the phone takes their text back. */
function drop(sessionId: string, why: string): void {
  const list = held[sessionId] ?? []
  if (!list.length) return
  take(sessionId, list.map((e) => e.id))
  ops?.changed(sessionId)
  const n = list.length
  log('held prompts dropped:', why, sessionId.slice(0, 8), n)
  ops?.unsent(sessionId, {
    reason: 'dropped',
    text: `${why}, so ${n === 1 ? 'a held message wasn’t' : `${n} held messages weren’t`} sent.`,
    prompts: list.map((e) => ({ text: splitAttachments(e.prompt).text, clientId: e.clientId })),
  })
}

/**
 * Type session [sessionId]'s held prompts in, oldest first, one at a time, while its box takes
 * them (Claude Code queues the ones after the first while Claude works). One the box doesn't take
 * stays first in line for the next look, except: it went into the box but Enter didn't send it
 * (`not_seen`, `not_sent`), so it's in the terminal's box now ("returned"); or the conversation
 * went on in another session, or the session is gone, so none of them will go ("dropped").
 */
export async function drain(sessionId: string, now = Date.now): Promise<void> {
  if (!ops || sending.has(sessionId) || now() - (failedAt.get(sessionId) ?? 0) < HELD_RETRY_MS) return
  for (;;) {
    const e = held[sessionId]?.[0]
    if (!e || ops.ready(sessionId) !== 'ready') return
    sending.set(sessionId, e.id)
    let code: string | null = null
    try {
      await ops.send(sessionId, e.prompt, e.clientId)
    } catch (err: any) {
      code = String(err?.code ?? 'failed')
    } finally {
      sending.delete(sessionId)
    }
    if (code !== null) failedAt.set(sessionId, now())
    if (code === null) {
      take(sessionId, [e.id])
      ops.changed(sessionId)
      continue
    }
    if (code === 'not_seen' || code === 'not_sent') {
      take(sessionId, [e.id])
      ops.changed(sessionId)
      ops.unsent(sessionId, { reason: 'returned', text: 'A held message is in the terminal’s input box but wasn’t sent. Open the terminal to send it.', prompts: [{ text: splitAttachments(e.prompt).text, clientId: e.clientId }] })
      return
    }
    if (code === 'switched') return drop(sessionId, 'This conversation went on in another session')
    if (code === 'no_session') {
      // Nowhere left to say so.
      take(sessionId, (held[sessionId] ?? []).map((x) => x.id))
      return
    }
    // Claude asks again, unsent text in the box, the terminal busy or starting: next time.
    return
  }
}
