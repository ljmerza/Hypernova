// Codex sessions (docs/PROTOCOL.md, "Codex"): started from the phone, an automation or a switch,
// or found in a shared terminal; prompted, stopped and set up over Codex's app-server. A thread
// is never resumed before Codex has written it down (docs/specs/codex-app-server.md, 1.2).
import { randomUUID } from 'node:crypto'
import { ensureParsed, forgetSession, getSession, loadHistory, notice, putEvent, setState, upsertSession } from '../hub.ts'
import { promptTitle, queueView, unsentPrompts, type QueueEntry } from '../normalize.ts'
import { splitAttachments } from '../attachments.ts'
import { log, trimText } from '../util.ts'
import { RpcError, type CodexRpc } from './rpc.ts'
import { CodexError, codexBinary, codexEnabled, connected, onCodexConnected, onCodexNotification, rpc, setCodexEnabled } from './client.ts'
import { getThread, isCodex, listThreads, patchThread, removeThread, saveThread, type CodexMode, type CodexThread } from './threads.ts'
import { abandonTurn, droppedNotice, historyEvents, liveOf, onTurnEnded, runningTurn, sentEvent, turnOver } from './events.ts'
import { onApprovalTimeout } from './approvals.ts'
import { refreshCodexAccount } from './account.ts'
import { useCodexHolders } from '../terms.ts'

/** What a Codex session shows that isn't Codex's to say: who it is, where, on what. */
const base = (t: CodexThread) => ({ agent: 'codex' as const, cwd: t.cwd, title: t.title, origin: t.origin, providerId: 'codex', model: t.model, permissionMode: t.mode, createdAt: t.createdAt, automation: t.automation ?? null, transcriptPath: '', bypassAvailable: false })

/** At start: the threads Hypernova knows, on Home or in History, before Codex is reached. */
export function restoreCodex(): void {
  for (const t of listThreads()) {
    const r = upsertSession(t.id, { ...base(t), state: 'ended', lastActivityAt: t.lastActivityAt })
    r.lastActivityAt = t.lastActivityAt
  }
}

// ── Joining threads ──────────────────────────────────────────────────────
/** Threads this connection follows: started on it, or joined with thread/resume. */
const joined = new Set<string>()
/** Histories read this run (a thread's is read once, when it's opened or joined). */
const read = new Set<string>()
/** Terminals' threads Codex hasn't written down yet, where each runs: taken on once it has. */
const waiting = new Map<string, { cwd: string; origin: 'hypernova' | 'terminal' }>()
/** How long after its last turn a thread is followed again on a new connection (a turn may still run). */
const RECENT_MS = 6 * 3600_000

onCodexConnected(() => {
  joined.clear()
  // Those that may be running (a terminal's, one the phone started just before a restart) are
  // followed again: Codex sends a thread's approvals only to the clients on it.
  const now = Date.now()
  for (const t of listThreads()) if (getSession(t.id)?.term || (t.started && now - t.lastActivityAt < RECENT_MS)) void join(t.id).catch(() => {})
  for (const [id, w] of waiting) void followTerminal(id, w.cwd, w.origin).catch(() => {})
})

/** A sandbox policy as Codex reports it, as a session's mode. */
const modeOf = (p: any): string | null => (p?.type === 'readOnly' ? 'read-only' : p?.type === 'workspaceWrite' ? 'workspace-write' : p?.type === 'dangerFullAccess' ? 'danger-full-access' : null)

/** The thread/resume Codex answers once it has written the thread down; null before. */
async function resume(c: CodexRpc, id: string): Promise<any | null> {
  try {
    return await c.call('thread/resume', { threadId: id, excludeTurns: true })
  } catch (e) {
    if (e instanceof RpcError && /no rollout/i.test(e.message)) return null
    throw e
  }
}

/**
 * Follow thread [id] on this connection (thread/resume, which rejoins a running thread). False
 * when Codex hasn't written it down yet (it's tried again when its status changes), or when Codex
 * isn't reachable now ([connect]: try to reach it first, for what the owner just asked).
 */
export async function join(id: string, connect = false): Promise<boolean> {
  if (joined.has(id)) return true
  const c = connect ? await rpc() : connected()
  if (!c) return false
  const r = await resume(c, id)
  if (!r) return false
  await joined_(id, r, !getThread(id)?.started)
  return true
}

/** After a thread was joined: what Codex says about it now, its history, and the queue. */
async function joined_(id: string, r: any, fresh: boolean): Promise<void> {
  joined.add(id)
  const t = getThread(id)
  const status = r?.thread?.status?.type
  // A terminal's thread goes by its own settings: the session shows them, until the owner picks one.
  const own = t && !(t.origin === 'hypernova' || t.chosen) ? { ...(modeOf(r?.sandbox) ? { mode: modeOf(r.sandbox)! } : {}), ...(typeof r?.model === 'string' ? { model: r.model } : {}) } : {}
  patchThread(id, { started: true, ...own })
  if (own.mode || own.model) upsertSession(id, { ...(own.mode ? { permissionMode: own.mode } : {}), ...(own.model ? { model: own.model } : {}) })
  // A turn this daemon followed that Codex no longer runs (it restarted meanwhile) is over; its
  // end, and a terminal's turns before Hypernova joined, come from the history read again.
  const lost = !!runningTurn(id) && status !== 'active'
  if (fresh || lost) read.delete(id)
  if (lost) abandonTurn(id)
  await ensureCodexHistory(id)
  if (status === 'active') setState(id, 'working', 'Working')
  // Rejoined at rest (the daemon started, a terminal opened it): idle, but not active now; its last activity stays.
  else if (getSession(id)?.state === 'ended' || getSession(id)?.state === 'starting') upsertSession(id, { state: 'idle', activity: null })
  void drain(id)
}

/** Stop following thread [id] (the conversation went on with another agent). */
export function leave(id: string): void {
  if (!joined.delete(id)) return
  void connected()?.call('thread/unsubscribe', { threadId: id }).catch(() => {})
}

/**
 * A holder reported the thread its TUI runs (terms.ts): one Hypernova knows is followed; a new one
 * becomes a session once Codex has written it down, so a `codex` opened and closed leaves nothing.
 */
export async function followTerminal(id: string, cwd: string, origin: 'hypernova' | 'terminal'): Promise<boolean> {
  if (isCodex(id)) return join(id)
  waiting.set(id, { cwd, origin })
  const c = connected()
  if (!c) return false
  const r = await resume(c, id)
  if (!r || isCodex(id)) return !!r
  waiting.delete(id)
  const now = Date.now()
  const t = saveThread({ id, cwd, title: 'New session', model: typeof r.model === 'string' ? r.model : null, effort: null, mode: modeOf(r.sandbox) ?? 'read-only', createdAt: now, lastActivityAt: now, origin, started: true })
  upsertSession(id, { ...base(t), state: 'idle', lastActivityAt: now })
  ensureParsed(getSession(id)!)
  await joined_(id, r, true)
  return true
}

// A thread Hypernova knows but doesn't follow yet (a terminal's, before its first turn) became
// joinable: its status changes reach every connection (docs/specs/codex-app-server.md, 1.3).
onCodexNotification((m) => {
  const id = m.params?.threadId
  if (m.method !== 'thread/status/changed' || typeof id !== 'string') return
  const s = m.params?.status?.type
  // Put away (archived, unloaded): not followed any more, and not loaded again by joining it.
  if (s === 'notLoaded') return void joined.delete(id)
  if (s !== 'active' && s !== 'idle' || joined.has(id)) return
  const w = waiting.get(id)
  if (w) void followTerminal(id, w.cwd, w.origin).catch(() => {})
  else if (isCodex(id)) void join(id).catch(() => {})
})

/** A thread's turns so far, read once into its session's history (when it's opened). Never waits for Codex. */
export async function ensureCodexHistory(id: string): Promise<void> {
  const t = getThread(id)
  if (!t || read.has(id)) return
  if (!t.started) {
    read.add(id)
    return
  }
  // Codex isn't there now: the session shows what it has, and reads it on a later look.
  const c = connected()
  if (!c) return
  try {
    const r = await c.call('thread/read', { threadId: id, includeTurns: true }, 60_000)
    read.add(id)
    loadHistory(id, historyEvents(r?.thread))
    // Joined while a turn runs (a terminal's): that turn is the one Stop stops.
    const turn = Array.isArray(r?.thread?.turns) ? r.thread.turns.at(-1) : null
    const l = liveOf(id)
    if (turn?.status === 'inProgress' && !l.turn && !turnOver(id, turn.id)) {
      l.turn = turn.id
      l.at = typeof turn.startedAt === 'number' ? turn.startedAt * 1000 : Date.now()
    }
    const last = [...ensureParsed(getSession(id)!).events].reverse().find((e) => e.kind === 'assistant')
    if (last?.kind === 'assistant' && last.text) upsertSession(id, { lastText: trimText(last.text, 200) })
  } catch (e) {
    log('codex history read failed', id.slice(0, 8), (e as Error).message)
  }
}

// ── Starting ─────────────────────────────────────────────────────────────
/** What the next turn changes (a model or an effort chosen meanwhile), sent with it once. */
const next = new Map<string, Record<string, unknown>>()
const policyOf = (mode: string) => (mode === 'workspace-write' ? { type: 'workspaceWrite', writableRoots: [], networkAccess: false, excludeTmpdirEnvVar: false, excludeSlashTmp: false } : { type: 'readOnly', networkAccess: false })
const change = (id: string, patch: Record<string, unknown>) => next.set(id, { ...next.get(id), ...patch })
/** The sandbox and asking every turn Hypernova starts carries where Hypernova decides them (a terminal may have changed them). */
function rules(t: CodexThread): Record<string, unknown> {
  if (!(t.origin === 'hypernova' || t.chosen)) return {}
  return { sandboxPolicy: policyOf(t.mode), approvalPolicy: t.approvalPolicy ?? 'on-request', approvalsReviewer: 'user' }
}

export type CodexStart = {
  cwd: string
  prompt?: string
  /** The phone's name for the first send. */
  clientId?: string
  model?: string | null
  effort?: string | null
  mode?: CodexMode
  title?: string
  /** An automation's run: its badge, and no one to ask (approvalPolicy "never"). */
  automation?: { id: string; name: string; runId: string }
  approvalPolicy?: 'on-request' | 'never'
  /** Told to Codex for the whole thread (an automation's instructions). */
  developerInstructions?: string
  /** What another agent's conversation carried over (switch.ts), put in before any turn. */
  context?: string
}

/** Start a Codex session; it runs [prompt] at once, or holds [context] until the first one. Returns its id. */
export async function createCodexSession(o: CodexStart): Promise<string> {
  if (!codexBinary().ok) throw new CodexError(409, 'no_codex', 'Codex isn’t installed on this computer, or it’s too old for Hypernova.')
  if (!o.prompt?.trim() && o.context === undefined) throw new CodexError(400, 'no_prompt', 'Write what you want Codex to do.')
  // The first Codex session from the phone turns Codex on for this computer.
  if (!codexEnabled()) setCodexEnabled(true)
  const c = await rpc()
  const mode = o.mode ?? 'read-only'
  const r = await c.call('thread/start', {
    cwd: o.cwd,
    sandbox: mode,
    approvalPolicy: o.approvalPolicy ?? 'on-request',
    approvalsReviewer: 'user',
    ...(o.model ? { model: o.model } : {}),
    ...(o.developerInstructions ? { developerInstructions: o.developerInstructions } : {}),
  })
  const id: string = r?.thread?.id
  if (typeof id !== 'string') throw new CodexError(502, 'codex_refused', 'Codex didn’t start a thread.')
  joined.add(id)
  read.add(id)
  const now = Date.now()
  const t = saveThread({
    id,
    cwd: o.cwd,
    title: trimText(o.title ?? (o.prompt ? promptTitle(o.prompt) : 'New session'), 80) ?? 'New session',
    model: (typeof r.model === 'string' ? r.model : null) ?? o.model ?? null,
    effort: o.effort ?? (typeof r.reasoningEffort === 'string' ? r.reasoningEffort : null),
    mode,
    createdAt: now,
    lastActivityAt: now,
    origin: 'hypernova',
    started: false,
    ...(o.automation ? { automation: o.automation } : {}),
    ...(o.approvalPolicy ? { approvalPolicy: o.approvalPolicy } : {}),
  })
  upsertSession(id, { ...base(t), state: 'starting', lastActivityAt: now })
  ensureParsed(getSession(id)!)
  if (o.effort) change(id, { effort: o.effort })
  try {
    if (o.context !== undefined) {
      // So Codex writes the thread down (it can be resumed and opened in a terminal), with no turn.
      const text = o.context.trim() || 'This conversation goes on from one the user had with another agent. No history was carried over.'
      await c.call('thread/inject_items', { threadId: id, items: [{ type: 'message', role: 'user', content: [{ type: 'input_text', text }] }] })
      patchThread(id, { started: true })
    }
    if (o.prompt?.trim()) await promptCodex(id, o.prompt, o.clientId)
    else setState(id, 'idle')
  } catch (e) {
    // Codex took the thread but not what was to follow: the session rests, saying why (a prompt's
    // refusal says so itself). One Codex never wrote down can't come back.
    if (!(e instanceof CodexError && e.code === 'codex_refused')) notice(id, `Codex didn’t start this: ${(e as Error).message}`, 'error')
    setState(id, getThread(id)?.started ? 'idle' : 'ended')
    throw e
  }
  return id
}

// ── Prompts ──────────────────────────────────────────────────────────────
/** Prompts waiting behind the turn that runs (Codex would mix one into the running turn). */
const queues = new Map<string, QueueEntry[]>()
/** Sessions whose turn is being asked for right now. */
const sending = new Set<string>()
/** Sessions the owner stopped while their turn was being asked for: stopped once it's known. */
const stopAsked = new Set<string>()

function publishQueue(id: string): void {
  const queue = queueView(queues.get(id) ?? [])
  upsertSession(id, { queue, queued: queue.length })
}

/** The prompt as Codex's input: its text, the phone's files as pictures or file mentions. */
export function inputOf(text: string): unknown[] {
  const { text: rest, files } = splitAttachments(text)
  const items: unknown[] = []
  if (rest.trim() || !files.length) items.push({ type: 'text', text: rest, text_elements: [] })
  for (const f of files) items.push((f.mime ?? '').startsWith('image/') ? { type: 'localImage', path: f.path } : { type: 'mention', name: f.name, path: f.path })
  return items
}

/** Send [text] to Codex session [id]: a turn now, or queued behind the one running (or the ones before it). */
export async function promptCodex(id: string, text: string, clientId?: string): Promise<{ queued: boolean }> {
  if (!getThread(id) || !getSession(id)) throw new CodexError(404, 'no_session', 'That session no longer exists.')
  if (runningTurn(id) || sending.has(id) || queues.get(id)?.length) {
    const q = queues.get(id) ?? []
    q.push({ id: `q:${randomUUID()}`, content: text, at: Date.now(), clientId: clientId ?? null })
    queues.set(id, q)
    publishQueue(id)
    void drain(id)
    return { queued: true }
  }
  await startTurn(id, text, clientId)
  return { queued: false }
}

/**
 * The prompts in line go, one turn at a time, while nothing runs. One Codex refuses is given back
 * (named, so the phone takes its text back), and the next goes.
 */
async function drain(id: string): Promise<void> {
  while (!runningTurn(id) && !sending.has(id)) {
    const q = queues.get(id)
    const nxt = q?.shift()
    if (!nxt) return
    publishQueue(id)
    try {
      await startTurn(id, nxt.content, nxt.clientId ?? undefined)
    } catch (e) {
      log('codex queued send failed', id.slice(0, 8), (e as Error).message)
      putEvent(id, { id: `n:${randomUUID()}`, kind: 'notice', ts: Date.now(), text: 'A queued message wasn’t sent.', tone: 'warn', unsent: { reason: 'dropped', prompts: unsentPrompts([nxt]) } })
    }
  }
}

async function startTurn(id: string, text: string, clientId?: string): Promise<void> {
  sending.add(id)
  try {
    const c = await rpc()
    if (!joined.has(id)) {
      if (!getThread(id)?.started) throw new CodexError(409, 'not_started', 'Codex never wrote this conversation down, so it can’t go on. Start a new one.')
      if (!(await join(id))) throw new CodexError(409, 'not_started', 'Codex can’t continue this conversation yet. Try again in a moment.')
    }
    const t = getThread(id)!
    const cid = clientId ?? `hn-${randomUUID()}`
    upsertSession(id, { stoppedForAway: false })
    let r: any
    try {
      r = await c.call('turn/start', { threadId: id, clientUserMessageId: cid, input: inputOf(text), ...rules(t), ...(next.get(id) ?? {}) })
    } catch (e) {
      notice(id, `Codex didn’t take that message: ${(e as Error).message}`, 'error')
      setState(id, 'idle')
      throw new CodexError(502, 'codex_refused', (e as Error).message)
    }
    next.delete(id)
    // Codex has it: the prompt shows (its own echo is the same event, wherever it lands first).
    sentEvent(id, cid, text)
    patchThread(id, { started: true, lastActivityAt: Date.now() })
    const l = liveOf(id)
    const turn = r?.turn?.id
    if (typeof turn === 'string' && r.turn.status === 'inProgress' && !turnOver(id, turn)) {
      if (!l.turn) {
        l.turn = turn
        l.at ||= Date.now()
      }
      if (getSession(id)?.state !== 'working' && getSession(id)?.state !== 'needs_you') setState(id, 'working', 'Thinking')
    }
    if (stopAsked.delete(id) && l.turn) await c.call('turn/interrupt', { threadId: id, turnId: l.turn }).catch(() => {})
  } finally {
    sending.delete(id)
  }
}

// The turn ended: the next prompt in line goes, and the account's limits are read again.
onTurnEnded((id) => {
  void drain(id)
  void refreshCodexAccount()
})

/** Stop: the running turn ends and prompts waiting behind it are dropped, named, so the phone takes them back. */
export async function interruptCodex(id: string): Promise<void> {
  const q = queues.get(id)
  if (q?.length) {
    const dropped = q.splice(0)
    publishQueue(id)
    droppedNotice(id, unsentPrompts(dropped))
  }
  const turn = runningTurn(id)
  // Its turn is being asked for: it stops as soon as Codex says which it is.
  if (!turn) return void (sending.has(id) && stopAsked.add(id))
  await (await rpc()).call('turn/interrupt', { threadId: id, turnId: turn })
}
// Nobody answered for 10 minutes: the turn stops (approvals.ts said no).
onApprovalTimeout((id) => void interruptCodex(id).catch(() => {}))

// ── Settings ─────────────────────────────────────────────────────────────
/** Model and effort from the next turn (Codex takes them with the turn). */
export function setCodexModel(id: string, model: string, effort?: string | null): 'now' | 'next_turn' {
  patchThread(id, { model, ...(effort !== undefined ? { effort } : {}) })
  change(id, { model, ...(effort ? { effort } : {}) })
  upsertSession(id, { model })
  return runningTurn(id) ? 'next_turn' : 'now'
}
/** Read only or Can edit, from the next turn, and every one Hypernova starts after it. */
export function setCodexMode(id: string, mode: CodexMode): 'now' | 'next_turn' {
  patchThread(id, { mode, chosen: true })
  upsertSession(id, { permissionMode: mode })
  return runningTurn(id) ? 'next_turn' : 'now'
}

// ── The session menu (docs/PROTOCOL.md, "Session menu") ───────────────────
/** Codex doesn't know the method it was asked (one older than the call). */
const unknownMethod = (e: unknown) => e instanceof RpcError && (e.code === -32601 || /method not found|unknown variant/i.test(e.message))

/**
 * Rename: Codex's own thread/name/set, so `codex resume` shows the name too. Kept by Hypernova
 * alone ('hypernova') for a thread Codex never wrote down, or a Codex that can't name threads.
 */
export async function renameCodex(id: string, name: string): Promise<'codex' | 'hypernova'> {
  const t = getThread(id)
  if (!t || !getSession(id)) throw new CodexError(404, 'no_session', 'That session no longer exists.')
  let kept: 'codex' | 'hypernova' = 'hypernova'
  if (t.started) {
    const c = await rpc()
    try {
      await c.call('thread/name/set', { threadId: id, name })
      kept = 'codex'
    } catch (e) {
      if (!unknownMethod(e)) throw new CodexError(502, 'codex_refused', (e as Error).message)
      log('codex has no thread/name/set: the name is Hypernova’s only', id.slice(0, 8))
    }
  }
  patchThread(id, { title: name })
  upsertSession(id, { title: name })
  return kept
}

/** Everything this daemon keeps about thread [id], and its session, gone. */
function forgetCodex(id: string): void {
  joined.delete(id)
  read.delete(id)
  waiting.delete(id)
  queues.delete(id)
  next.delete(id)
  stopAsked.delete(id)
  removeThread(id)
  forgetSession(id)
}

/**
 * Delete: Codex's own thread/delete, for good (`codex delete`: gone from `codex resume` too), then
 * Hypernova forgets it. A turn running is stopped first. A thread Codex never wrote down has
 * nothing to delete there: Hypernova only forgets it.
 */
export async function deleteCodex(id: string): Promise<void> {
  const t = getThread(id)
  if (!t || !getSession(id)) throw new CodexError(404, 'no_session', 'That session no longer exists.')
  if (t.started) {
    const c = await rpc()
    if (runningTurn(id)) await interruptCodex(id).catch(() => {})
    try {
      await c.call('thread/delete', { threadId: id })
    } catch (e) {
      if (unknownMethod(e)) throw new CodexError(409, 'codex_too_old', 'This Codex can’t delete conversations. Run `codex update`.')
      throw new CodexError(502, 'codex_refused', (e as Error).message)
    }
  } else if (joined.has(id)) void connected()?.call('thread/unsubscribe', { threadId: id }).catch(() => {})
  forgetCodex(id)
}

// Deleted elsewhere (`codex delete` in a terminal): Hypernova forgets it too.
onCodexNotification((m) => {
  const id = m.params?.threadId
  if (m.method === 'thread/deleted' && typeof id === 'string' && isCodex(id)) forgetCodex(id)
})

// Terminals' threads: taken on and followed as holders report them (terms.ts).
useCodexHolders({ follow: followTerminal })
