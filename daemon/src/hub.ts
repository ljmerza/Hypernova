// The daemon's in-memory model: sessions, their events, approvals and usage, plus the
// broadcast to every connected client (docs/PROTOCOL.md).
import { closeSync, existsSync, openSync, readFileSync, readSync, readdirSync, statSync, fstatSync, lstatSync } from 'node:fs'
import { basename, join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { CLAUDE_DIR, log, projectNameOf, projectSlug, trimText, tail } from './util.ts'
import { fallbackTitle, feedLine, flushAbsorbed, isLauncher, newParseState, newToolEvent, queueNow, queueView, squash, type Claim, type Ev, type ParseState, type QueuedPrompt, type ToolEv } from './normalize.ts'
import { recordReading, rateFor, type Rate } from './usage-rate.ts'
import { applyLinks, backgroundRunning, endSessionAgents, launchedInBackground } from './agents.ts'
import { getManaged, isSdk, isSubscription, managedSessions, listDevices } from './store.ts'
import type { TermView } from './terms.ts'
import { registryBusy, type Menu } from './term/shared.ts'
import { isHiddenCwd } from './term/hidden.ts'
import type { PlanWindow } from './usage-screen.ts'
import type { Blocked, Blocker, LimitNotice } from './blockers.ts'
import { cutByRestart, fromBeforeBoot, noteOpen, runsAgain, startAfterBoot } from './reboot.ts'
import { getAccount, noteBlocker, noteHealthy, noteLimitNotice, noteLogin, stillBlocks, usageStale } from './account.ts'
import { folderView } from './files.ts'
import { bypassBlockedByRoot } from './platform.ts'
import { linkOf } from './switch-links.ts'

export type SessionState = 'starting' | 'working' | 'needs_you' | 'idle' | 'error' | 'ended'
export type Session = {
  id: string
  title: string
  cwd: string
  projectName: string
  origin: 'hypernova' | 'terminal'
  state: SessionState
  providerId: string
  model: string | null
  permissionMode: string
  /**
   * The session can switch to Bypass permissions: always for one the daemon runs itself, and for a
   * shared terminal only when Claude Code was started with bypass allowed (else Shift+Tab never
   * reaches it). True too while it is in bypass. The phone dims Bypass when this is false.
   */
  bypassAvailable: boolean
  createdAt: number
  lastActivityAt: number
  lastText: string | null
  activity: string | null
  pendingApprovalIds: string[]
  stoppedForAway: boolean
  contextPct: number | null
  costUsd: number | null
  /** Prompts waiting until Claude can take them (Claude Code's queue, or the SDK runner's), oldest first. */
  queue: QueuedPrompt[]
  /** How many: queue.length. */
  queued: number
  /** The shared terminal this session runs in right now, with who has it open. */
  term: TermView | null
  /** A menu or dialog its terminal shows in place of the input box (one with no approval card). */
  menu: Menu | null
  /** How many agents its main agent started are running now (agents.ts). */
  agentsRunning: number
  /** Why Claude can't reply here now (a usage limit, signed out…): set when an API error stopped it, cleared by a real reply. */
  blocked: Blocked | null
  /**
   * Why it ended when nobody ended it: "mac_restart", it was open when the Mac restarted or shut
   * down, recently (reboot.ts), so the phone keeps it on Home to resume. Null otherwise, and
   * whenever it isn't ended.
   */
  endedBy: 'mac_restart' | null
  /** An automation started it (automations.ts): its name and run, kept with the session's record. */
  automation: { id: string; name: string; runId: string } | null
  /**
   * The phone can browse its folder (files.ts): it's there, inside the projects folder, or in the
   * home folder while "Browse outside projects" is on. Follows the setting live. False: the phone
   * hides its file links (and offers the setting where it would help).
   */
  browsable: boolean
  /** Its folder is in the home folder, outside the projects folder (not a key place): the setting opens it. */
  outsideProjects: boolean
  /** "codex": an OpenAI Codex session (codex/sessions.ts). Absent for Claude Code's, as before Codex. */
  agent?: 'codex'
  /** The session this conversation came from when it switched agents, and the one it went on in (switch.ts). */
  switchedFrom?: string
  switchedTo?: string
}
type Rec = Omit<Session, 'endedBy' | 'browsable' | 'outsideProjects' | 'switchedFrom' | 'switchedTo'> & { transcriptPath: string; parse: ParseState | null; offset: number; carry: Buffer; pid: number | null }
/** Run by Codex, not by Claude Code: no transcript, registry, hooks or terminal screen of Claude's to read. */
export const isCodexRec = (r: { agent?: string }) => r.agent === 'codex'

export type Approval = {
  id: string
  sessionId: string
  createdAt: number
  expiresAt: number
  kind: 'tool' | 'question' | 'plan'
  toolName: string
  summary: string
  input: Record<string, unknown>
  reason: string | null
  canAlways: boolean
  /** The tool can be allowed for the rest of the session at once (web and read tools). */
  canSession: boolean
  questions: unknown[] | null
  plan: string | null
  /** The agent that asks (one the main agent started), and what it's called; null for the main agent. */
  agentId: string | null
  agentLabel: string | null
}
/** What asks: the approval without what the daemon adds itself. */
export type ApprovalAsk = Omit<Approval, 'id' | 'createdAt' | 'expiresAt' | 'canSession' | 'agentId' | 'agentLabel'> & { agentId?: string | null; agentLabel?: string | null }
export type Answer = { decision: 'allow' | 'deny' | 'always' | 'session' | 'timeout' | 'terminal'; message?: string; answers?: Record<string, string> }

/**
 * Tools the owner can allow for a whole session with one answer. A research run fetches dozens
 * of sites, and Claude Code asks once per site; commands and edits stay per-command ("Always")
 * or go with a mode.
 */
export const SESSION_TOOLS = new Set(['WebFetch', 'WebSearch', 'Read', 'Glob', 'Grep', 'LS'])
const sessionAllowed = new Map<string, Set<string>>()
/** The owner allowed this tool for the rest of the session (subagents included). */
export const allowedForSession = (sessionId: string, tool: string) => !!sessionAllowed.get(sessionId)?.has(tool)
export const forgetSessionAllowed = (sessionId: string) => sessionAllowed.delete(sessionId)
/** Claude Code's own rule for it, so it stops asking too (session only, nothing saved). */
export const sessionRule = (tool: string) => [{ type: 'addRules', rules: [{ toolName: tool }], behavior: 'allow', destination: 'session' }]
type Pending = { approval: Approval; toolUseId: string | null; resolve: (a: Answer) => void; timer: NodeJS.Timeout }

export const AWAY_MS = 10 * 60_000
export const AWAY_MESSAGE =
  "The user didn't respond within 10 minutes and is probably away from their phone. Don't retry or work around this. Stop here and wait until they come back."

export type Window = { pct: number; resetsAt: number }
/** A reading offered to the meter; [pace]: the value the spend pace takes, when not [pct] (a floored one's). */
type Reading = Window & { pace?: number }
/**
 * A model's own weekly limit ("Fable", "Sonnet"), which only Claude Code's /usage screen shows
 * (usage-probe.ts). [resetsAt] is null when the screen gave none.
 */
export type ModelWindow = { label: string; pct: number; resetsAt: number | null }
/**
 * `stale`: signed out (or no plan, or on hold), so the last numbers known, not rolled forward.
 * `source` and `observedAt` are the status line's; [models] come from the usage probe (the daemon
 * always sends them, empty when there are none; optional only for callers that build a Usage).
 * [modelsObservedAt]: when they were last read; [modelsStale]: the last look couldn't read them
 * (the screen said the breakdown was unavailable), so these are the last good ones.
 */
export type Usage = { fiveHour: Window | null; sevenDay: Window | null; rate?: Rate | null; models?: ModelWindow[]; modelsObservedAt?: number | null; modelsStale?: boolean; probe?: ProbeHealth; source: 'official' | 'none' | 'stale'; observedAt: number | null }
/**
 * Whether the usage probe can read /usage (usage-probe.ts): `ok` false after 3 looks in a row that
 * couldn't, since the first of them; the per-model weeks are then the last ones read (stale).
 */
export type ProbeHealth = { ok: boolean; since: number | null }

// ── Clients ───────────────────────────────────────────────────────────────
/** `phone`: a paired device's feed (a phone, the glasses), not the Mac app's own. */
type Client = { send: (s: string) => void; phone?: boolean }
const clients = new Set<Client>()
export function addClient(c: Client): () => void {
  clients.add(c)
  return () => clients.delete(c)
}
/**
 * Who hears every message before it's sent: the automations (automations.ts), whose triggers are
 * sessions, approvals, usage, the account and the battery. A tap must be quick and never throw.
 */
const taps: ((msg: object) => void)[] = []
export function tapBroadcast(f: (msg: object) => void): void {
  taps.push(f)
}
export function broadcast(msg: object): void {
  for (const t of taps) {
    try {
      t(msg)
    } catch (e) {
      log('broadcast tap failed', e)
    }
  }
  const s = JSON.stringify(msg)
  for (const c of clients) {
    try {
      c.send(s)
    } catch {}
  }
}
export const clientCount = () => clients.size
/** Feeds of paired devices (phones, glasses) connected now; the Mac app's own feed isn't one. */
export const phoneCount = () => [...clients].filter((c) => c.phone).length

// ── Sessions ──────────────────────────────────────────────────────────────
const sessions = new Map<string, Rec>()

export function transcriptPathFor(cwd: string, id: string): string {
  return join(CLAUDE_DIR, 'projects', projectSlug(cwd), `${id}.jsonl`)
}

export function view(r: Rec): Session {
  const { transcriptPath: _t, parse: _p, offset: _o, carry: _c, pid: _pid, ...rest } = r
  // Only sessions that switched agents carry the links, so nothing changes for the others.
  const link = linkOf(r.id)
  const s: Session = { ...rest, endedBy: rest.state === 'ended' && cutByRestart(r.id) ? 'mac_restart' : null, ...folderView(r.cwd), ...(link.from ? { switchedFrom: link.from } : {}), ...(link.to ? { switchedTo: link.to } : {}) }
  return { ...s, blocked: resume.view(s), bypassAvailable: s.bypassAvailable || s.permissionMode === 'bypassPermissions', pendingApprovalIds: [...pending.values()].filter((p) => p.approval.sessionId === r.id).map((p) => p.approval.id) }
}

/**
 * Carrying on after a limit (resume.ts) hears of each session's blocks, replies and Claude Code's
 * limit notices, and adds its state to the block a session shows (`blocked.resume`).
 */
type ResumeHooks = {
  blocked: (id: string, b: Blocked | null, at: number) => void
  replied: (id: string, at: number) => void
  noticed: (id: string, n: LimitNotice, at: number) => void
  view: (s: Session) => Blocked | null
}
let resume: ResumeHooks = { blocked: () => {}, replied: () => {}, noticed: () => {}, view: (s) => s.blocked }
export function resumeWith(h: ResumeHooks): void {
  resume = h
}

export function getSession(id: string): Rec | undefined {
  return sessions.get(id)
}

export const listSessionRecs = (): Rec[] => [...sessions.values()]

export function listSessions(): Session[] {
  return [...sessions.values()].map(view).sort((a, b) => b.lastActivityAt - a.lastActivityAt)
}

export function upsertSession(id: string, patch: Partial<Rec> & { cwd?: string }): Rec {
  let r = sessions.get(id)
  // A session seen for the first time is news in itself, changed or not.
  let before = ''
  if (r) before = JSON.stringify(view(r))
  else {
    const cwd = patch.cwd ?? process.env.HOME ?? '/'
    const m = getManaged(id)
    r = {
      id,
      title: m?.title ?? 'New session',
      cwd,
      projectName: projectNameOf(cwd),
      origin: m ? 'hypernova' : 'terminal',
      state: 'idle',
      providerId: m?.providerId ?? 'anthropic',
      model: m?.model ?? null,
      permissionMode: m?.permissionMode ?? 'default',
      bypassAvailable: !!m && m.engine !== 'term' && !bypassBlockedByRoot(),
      createdAt: m?.createdAt ?? Date.now(),
      lastActivityAt: Date.now(),
      lastText: null,
      activity: null,
      pendingApprovalIds: [],
      stoppedForAway: false,
      contextPct: null,
      costUsd: null,
      queue: [],
      queued: 0,
      term: null,
      menu: null,
      agentsRunning: 0,
      blocked: null,
      automation: m?.automation ?? null,
      transcriptPath: transcriptPathFor(cwd, id),
      parse: null,
      offset: 0,
      carry: Buffer.alloc(0),
      pid: null,
    }
    sessions.set(id, r)
  }
  Object.assign(r, patch)
  // Running again (a prompt resumed it): no longer one a restart of the Mac cut short.
  if (patch.state && patch.state !== 'ended') runsAgain(id)
  if (patch.cwd) {
    r.projectName = projectNameOf(patch.cwd)
    if (!patch.transcriptPath) r.transcriptPath = transcriptPathFor(patch.cwd, id)
  }
  const after = view(r)
  if (JSON.stringify(after) !== before) broadcast({ t: 'session', session: after })
  return r
}

export function setState(id: string, state: SessionState, activity?: string | null): void {
  const r = sessions.get(id)
  if (!r) return
  const patch: Partial<Rec> = { state, lastActivityAt: Date.now() }
  if (activity !== undefined) patch.activity = activity
  if (state === 'idle' || state === 'ended') patch.activity = null
  upsertSession(id, patch)
}

/** A fresh history for a session, whose queued prompts claim the phone sends they came from. */
function freshParse(r: Rec): ParseState {
  const st = newParseState()
  st.claim = (content, ts) => claimPaste(r.term?.id, content, ts)
  return st
}

/** Load (or reload) a session's history from its transcript. */
export function ensureParsed(r: Rec): ParseState {
  if (r.parse) return r.parse
  r.parse = freshParse(r)
  r.offset = 0
  r.carry = Buffer.alloc(0)
  // Read the whole file in 8 MB steps (big sessions can be tens of MB).
  for (let i = 0; i < 64; i++) {
    const before = r.offset
    readNew(r, true)
    if (r.offset === before) break
  }
  // Claude Code holds no queue while it's idle: what's left was read from before we watched
  // (its way out wasn't logged, or not in a shape we know). Prompts Claude took whose lines
  // aren't written yet show now.
  const st = r.parse
  flushAbsorbed(st, Infinity)
  if (st.queue.length && !registryBusy(r.pid)) st.queue = []
  st.queueChanged = true
  publishQueue(r)
  return st
}

/**
 * The session shows its queue as read so far. After the events read with it: a prompt Claude
 * took shows as its event before it leaves the queue, so it never vanishes in between.
 */
function publishQueue(r: Rec): void {
  const st = r.parse
  if (!st?.queueChanged) return
  st.queueChanged = false
  // An SDK session's queue is the runner's own (its transcript logs no prompt text), a Codex one's
  // the daemon's (codex/sessions.ts).
  if (isSdk(r.id) || isCodexRec(r)) return
  const queue = queueView(queueNow(st))
  upsertSession(r.id, { queue, queued: queue.length })
}

/** Empty a session's queue (Claude Code is idle, so nothing waits in it any more). */
function clearQueue(r: Rec): void {
  if (r.parse) {
    r.parse.queue = []
    for (const a of r.parse.absorbed.values()) a.entry = null
  }
  upsertSession(r.id, { queue: [], queued: 0 })
}

/**
 * Read new transcript bytes; returns ids of events that changed. [quiet]: a history read in bulk,
 * whose agents the phone asks for whole rather than one message each.
 */
function readNew(r: Rec, quiet = false): Set<string> {
  const touched = new Set<string>()
  if (!r.parse || !existsSync(r.transcriptPath)) return touched
  // Whose an API error is: the owner's Claude account, or another provider's; before the session
  // moved to this provider ("Continue on…"), the one it ran on then.
  r.parse.builtIn = isSubscription(r.providerId)
  const m = getManaged(r.id)
  r.parse.movedAt = m?.providerAt ?? 0
  r.parse.builtInBefore = m?.providerFrom ? isSubscription(m.providerFrom) : r.parse.builtIn
  let fd: number | null = null
  try {
    fd = openSync(r.transcriptPath, 'r')
    const size = fstatSync(fd).size
    if (size < r.offset) {
      // Rewritten in place: start over.
      r.parse = freshParse(r)
      r.offset = 0
      r.carry = Buffer.alloc(0)
    }
    if (size === r.offset) return touched
    const buf = Buffer.alloc(Math.min(size - r.offset, 8 * 1024 * 1024))
    const n = readSync(fd, buf, 0, buf.length, r.offset)
    r.offset += n
    // Split on newline bytes and decode whole lines only, so a multi-byte character split
    // across reads isn't garbled.
    const bytes = Buffer.concat([r.carry, buf.subarray(0, n)])
    const lastNl = bytes.lastIndexOf(0x0a)
    r.carry = lastNl >= 0 ? Buffer.from(bytes.subarray(lastNl + 1)) : bytes
    const lines = lastNl >= 0 ? bytes.subarray(0, lastNl).toString('utf8').split('\n') : []
    for (const line of lines) {
      if (!line) continue
      let o: any
      try {
        o = JSON.parse(line)
      } catch {
        continue
      }
      const before = r.parse.events.length
      feedLine(r.parse, o)
      for (const ev of r.parse.events.slice(before)) touched.add(ev.id)
      for (const id of touchedIds(o)) touched.add(id)
      // Earlier events this line changed (a command that turned out to be a skill).
      for (const id of r.parse.changed) touched.add(id)
      r.parse.changed.clear()
    }
  } catch (e) {
    log('transcript read failed', r.transcriptPath, e)
  } finally {
    if (fd != null) closeSync(fd)
  }
  const st = r.parse
  if (st) {
    // Agents and workflows the lines linked, and task ends (agents.ts).
    if (st.links.length) applyLinks(r.id, st.links.splice(0), quiet)
    // A turn the transcript shows starting after the main agent's Stop: it works again.
    if (st.turnAt) mainActive(r.id, st.turnAt)
    const patch: Partial<Rec> = {}
    const title = st.title ?? fallbackTitle(st)
    if (title && r.title !== trimText(title, 80)) patch.title = trimText(title, 80) ?? r.title
    if (st.lastText && r.lastText !== trimText(st.lastText, 200)) patch.lastText = trimText(st.lastText, 200)
    // The model it uses now, from every reply and every /model since (the alias it started with,
    // "opus", gives way to the id). Only news changes it: the status line may know better meanwhile.
    if (st.model && (st.model !== modelSeen.get(r.id) || !r.model)) {
      modelSeen.set(r.id, st.model)
      if (!sameModel(st.model, r.model)) patch.model = st.model
    }
    if (Object.keys(patch).length) upsertSession(r.id, patch)
    applyAccount(r, st)
  }
  return touched
}

// ── Its model ─────────────────────────────────────────────────────────────
/** The model each session's transcript said last, so a read that says nothing new changes nothing. */
const modelSeen = new Map<string, string>()
/** The model each session's status line said last. */
const statusModels = new Map<string, string>()
/** The same model, the 1M window or not ("claude-opus-5-5[1m]" is "claude-opus-5-5"). */
export const sameModel = (a: string | null | undefined, b: string | null | undefined) => !!a && !!b && a.replace(/\[1m\]$/i, '').toLowerCase() === b.replace(/\[1m\]$/i, '').toLowerCase()

/**
 * The model session [id]'s status line names ([at]: when its file was written). Claude Code
 * repaints it right after a /model, before any reply: a change it shows, newer than what the
 * transcript last said, is the session's model now (its "[1m]" kept, which replies never carry).
 */
export function noteStatusModel(id: string, model: unknown, at: number): void {
  if (typeof model !== 'string' || !model.trim()) return
  const r = sessions.get(id)
  if (!r) return
  const prev = statusModels.get(id)
  statusModels.set(id, model)
  if (prev === model || at < (r.parse?.modelAt ?? 0)) return
  if (model !== r.model && (!sameModel(model, r.model) || /\[1m\]$/i.test(model))) upsertSession(id, { model })
}

// ── What stops Claude (docs/PROTOCOL.md, "Account") ──────────────────────
/** The parser's blocker each session last showed (so a reread changes nothing), and when it stopped the session. */
const blockerKeys = new Map<string, string>()
const blockedAt = new Map<string, number>()

/** What a read told the account (API errors, replies, sign-ins, limit notices), then why this session can't reply. */
function applyAccount(r: Rec, st: ParseState): void {
  // Claude Code writes its limit notice a moment after the error, so one read often holds both.
  // The resume they're about is made when the block is set below: the notices are told to it
  // after that, in order, or an "armed" one would find nothing to arm.
  const notices: Array<{ notice: LimitNotice; at: number }> = []
  for (const n of st.account.splice(0)) {
    if (n.kind === 'blocker') noteBlocker(n.blocker, { at: n.at, sessionId: r.id, providerId: r.providerId })
    else if (n.kind === 'healthy') {
      noteHealthy(r.providerId, n.at, n.model)
      resume.replied(r.id, n.at)
      // Stopped by a StopFailure hook, whose error the transcript may not show yet: a reply since undoes it.
      if (r.blocked && n.at > (blockedAt.get(r.id) ?? 0)) setBlocked(r.id, null)
    } else if (n.kind === 'login') noteLogin(n.at)
    else {
      noteLimitNotice(n.notice, n.at)
      notices.push(n)
    }
  }
  const key = st.blocker ? JSON.stringify(st.blocker) : ''
  if ((blockerKeys.get(r.id) ?? '') !== key) {
    blockerKeys.set(r.id, key)
    setBlocked(r.id, st.blocker?.blocked ?? null, st.blocker?.at)
  }
  for (const n of notices) resume.noticed(r.id, n.notice, n.at)
}

/**
 * Why Claude can't reply in session [id] (null: it can), as of [at] when it stopped. One that no
 * longer holds (its reset passed, the account works again) isn't shown. Stopped just now while
 * it showed working: it's idle (the registry may not say so yet), not failed.
 */
export function setBlocked(id: string, b: Blocked | null, at = Date.now()): void {
  const r = sessions.get(id)
  if (!r) return
  const next = b && stillBlocks(b, r.providerId, at) ? b : null
  if (next) blockedAt.set(id, at)
  else blockedAt.delete(id)
  const patch: Partial<Rec> = {}
  if (JSON.stringify(next) !== JSON.stringify(r.blocked)) {
    patch.blocked = next
    resume.blocked(id, next, at)
  }
  if (next && r.state === 'working' && Date.now() - at < 120_000) Object.assign(patch, { state: 'idle', activity: null, lastActivityAt: Date.now() })
  if (Object.keys(patch).length) upsertSession(id, patch)
}

/** Sessions whose blocker no longer holds say so (the account or a provider changed, a reset passed). */
export function reviewBlocked(): void {
  for (const r of sessions.values()) if (r.blocked && !stillBlocks(r.blocked, r.providerId, blockedAt.get(r.id) ?? 0)) setBlocked(r.id, null)
}

/**
 * An API error stopped session [id], reported by a StopFailure hook or the SDK: the account (or
 * its provider) hears of it, and the session says why it stopped.
 */
export function blockSession(id: string, b: Blocker, at = Date.now()): void {
  const r = sessions.get(id)
  if (!r) return
  noteBlocker(b, { at, sessionId: id, providerId: r.providerId })
  if (b.kind) setBlocked(id, { kind: b.kind, text: b.text, resetsAt: b.resetsAt, autoResumeAt: null }, at)
}

/** Claude replied in session [id] (from [model], to a request sent at [at]): nothing stops it. */
export function sessionHealthy(id: string, at: number, model: string): void {
  const r = sessions.get(id)
  if (!r) return
  noteHealthy(r.providerId, at, model)
  resume.replied(id, at)
  if (r.blocked) setBlocked(id, null)
}

function touchedIds(o: any): string[] {
  const out: string[] = []
  const c = o?.message?.content
  if (Array.isArray(c)) for (const b of c) {
    if (b?.type === 'tool_result' && b.tool_use_id) {
      out.push(b.tool_use_id)
      resolveByToolUse(b.tool_use_id) // the terminal answered (allowed or denied) this tool's approval
    }
    if (b?.type === 'tool_use' && b.id) out.push(b.id)
    if (b?.type === 'text' && o.message?.id) out.push(`t:${o.message.id}`)
  }
  return out
}

export function eventsFor(id: string, limit = 400): Ev[] {
  const r = sessions.get(id)
  if (!r) return []
  const st = ensureParsed(r)
  return st.events.slice(-limit)
}

/** Add or replace an event and tell clients. */
export function putEvent(sessionId: string, ev: Ev): void {
  const r = sessions.get(sessionId)
  if (!r) return
  const st = ensureParsed(r)
  if (!st.byId.has(ev.id)) st.events.push(ev)
  else {
    const i = st.events.findIndex((e) => e.id === ev.id)
    if (i >= 0) st.events[i] = ev
  }
  st.byId.set(ev.id, ev)
  broadcast({ t: 'event', sessionId, event: ev })
}

/** One last read of a terminal session's transcript (lines written just before it ended). */
export function finalRead(id: string): void {
  const r = sessions.get(id)
  if (!r?.parse) return
  const touched = readNew(r)
  for (const t of flushAbsorbed(r.parse, Infinity)) touched.add(t)
  for (const t of touched) emitEvent(id, t)
  publishQueue(r)
}

/** Copy a parent session's history into a fork, which starts with the same conversation. */
export function seedFrom(id: string, parentId: string): void {
  const r = sessions.get(id)
  const p = sessions.get(parentId)
  if (!r || !p) return
  const src = ensureParsed(p)
  const st = ensureParsed(r)
  st.events = [...src.events.map((e) => ({ ...e })), ...st.events]
  st.byId = new Map(st.events.map((e) => [e.id, e]))
}

/**
 * A session's history read whole from elsewhere (a Codex thread): it takes the place of what the
 * session held, without a message per event. Events made since it was asked for stay after it.
 */
export function loadHistory(id: string, events: Ev[]): void {
  const r = sessions.get(id)
  if (!r) return
  const st = ensureParsed(r)
  const known = new Set(events.map((e) => e.id))
  st.events = [...events, ...st.events.filter((e) => !known.has(e.id))]
  st.byId = new Map(st.events.map((e) => [e.id, e]))
}

export function emitEvent(sessionId: string, id: string): void {
  const r = sessions.get(sessionId)
  const ev = r?.parse?.byId.get(id)
  if (ev) broadcast({ t: 'event', sessionId, event: ev })
}

export function notice(sessionId: string, text: string, tone: 'info' | 'warn' | 'error' = 'info'): void {
  putEvent(sessionId, { id: `n:${randomUUID()}`, kind: 'notice', ts: Date.now(), text, tone })
}

/**
 * How long a prompt Claude took from the queue waits for its step's lines (as a rule written
 * just after) before it shows from its queue entry.
 */
const ABSORBED_MS = 3000

/** Poll transcripts of live sessions Claude Code runs on its own (terminals) for new lines. */
export function pollTranscripts(): void {
  for (const r of sessions.values()) {
    if (isSdk(r.id) || isCodexRec(r) || !r.parse) continue
    if (r.state !== 'ended') {
      const touched = readNew(r)
      // A prompt Claude took from the queue 3 s ago whose step's lines are still held up.
      for (const id of flushAbsorbed(r.parse, Date.now() - ABSORBED_MS)) touched.add(id)
      for (const id of touched) emitEvent(r.id, id)
      publishQueue(r)
      if (touched.size) upsertSession(r.id, { lastActivityAt: Date.now() })
    }
    // Claude Code never holds a queue while it's idle: entries still here missed their way out
    // (a line in a shape we don't know). They go once it has been idle, and quiet, for 3 s: a
    // turn ends before the queue's next prompt starts the next one. So do prompts whose text
    // wasn't logged, which the session doesn't show.
    const left = r.queue.length || r.parse.queue.length
    if (left && (r.state === 'ended' || (r.state === 'idle' && Date.now() - r.lastActivityAt > 3000 && !registryBusy(r.pid)))) clearQueue(r)
  }
}

// ── Prompts the phone typed while Claude worked ──────────────────────────
// Claude Code logs a queued prompt with its text and no id. The daemon notes each prompt it
// types for a phone send, and a queue entry claims the oldest one typed into its terminal in
// the last minute with the same text (a paste's tags already off, a "!" line's "!" aside). An
// entry logged without text (queued while no model turn runs) borrows the oldest one noted at or
// before its own time, and its text, until the send's own entry shows up (normalize.ts, `lent`).
type Paste = { clientId: string; text: string; raw: string; bash: boolean; at: number; until?: number }
const pastes = new Map<string, Paste[]>()
const PASTE_MS = 60_000

/** The daemon is about to type [text], sent by the phone as [clientId], into terminal [termId]. */
export function notePaste(termId: string, clientId: string, text: string): void {
  const now = Date.now()
  const list = (pastes.get(termId) ?? []).filter((p) => now - p.at < PASTE_MS)
  list.push({ clientId, text: squash(text), raw: text.trim(), bash: text.trimStart().startsWith('!'), at: now })
  pastes.set(termId, list)
}

/**
 * It wasn't typed after all, or Claude Code didn't queue it: no queue entry may claim it. With
 * [afterMs], it was queued: Claude Code logs its entry at once, so it stays claimable only that
 * long, not for the rest of the minute, when a prompt typed on the Mac might take its name.
 */
export function forgetPaste(termId: string, clientId: string, afterMs = 0): void {
  const list = pastes.get(termId)
  if (!list) return
  if (afterMs > 0) {
    const until = Date.now() + afterMs
    for (const p of list) if (p.clientId === clientId) p.until = until
  } else pastes.set(termId, list.filter((p) => p.clientId !== clientId))
}

/**
 * The phone send a prompt just queued (at [ts]) in terminal [termId] came from, taken, whether it
 * was a "!" line (Claude Code queues one without its "!"), and its text; null when none (typed on
 * the Mac). [content] null: Claude Code logged the prompt without its text, so it takes the oldest
 * send noted at or before it (typing is one prompt at a time, in order).
 */
export function claimPaste(termId: string | null | undefined, content: string | null, ts = Date.now()): Claim | null {
  const list = termId ? pastes.get(termId) : undefined
  if (!list) return null
  const now = Date.now()
  const live = (p: Paste) => now - p.at < PASTE_MS && (p.until === undefined || now <= p.until)
  const s = content === null ? null : squash(content)
  // Without text, only a send noted at or before the entry's own time. The daemon notes a send
  // before typing it, and Claude Code stamps its entry as Enter goes in, later, on the same
  // clock. So an entry stamped earlier is never the send's: a prompt typed on the Mac as a turn
  // ended, or an old entry read again.
  const i = list.findIndex((p) => live(p) && (s === null ? p.at <= ts : p.text === s))
  if (i < 0) return null
  const p = list.splice(i, 1)[0]!
  return { clientId: p.clientId, bash: p.bash, text: p.raw }
}

/**
 * One of the session's tools is running (its PreToolUse came, its PostToolUse hasn't): looked
 * for back to the prompt that started the turn.
 */
export function toolRunning(id: string): boolean {
  const evs = sessions.get(id)?.parse?.events ?? []
  for (let i = evs.length - 1, n = 0; i >= 0 && n < 400; i--, n++) {
    const e = evs[i]!
    // An agent's or a workflow's call launched in the background runs as long as it does, while the
    // turn moves on. A foreground agent's call holds the turn like any tool.
    if (e.kind === 'tool' && e.status === 'running' && !(isLauncher(e.name) && launchedInBackground(id, e.id))) return true
    if (e.kind === 'user' || e.kind === 'result') return false
  }
  return false
}

// ── Tool events from hooks or the SDK, with live Bash output ─────────────
export function startTool(sessionId: string, toolUseId: string, name: string, input: Record<string, unknown>): ToolEv {
  const r = sessions.get(sessionId)!
  const st = ensureParsed(r)
  let ev = st.byId.get(toolUseId) as ToolEv | undefined
  if (!ev) {
    ev = newToolEvent(toolUseId, name, input, Date.now())
    putEvent(sessionId, ev)
  }
  if (name === 'Bash') watchBashOutput(sessionId, ev)
  return ev
}

const TASKS_BASE = `/private/tmp/claude-${process.getuid?.() ?? 501}`
function watchBashOutput(sessionId: string, ev: ToolEv): void {
  const t0 = Date.now() - 3000
  let fd: number | null = null
  let pos = 0
  let text = ''
  let lastSent = 0
  let tries = 0
  let claimedPath: string | null = null
  const timer = setInterval(() => {
    try {
      if (ev.status !== 'running' || tries++ > 6000) {
        clearInterval(timer)
        if (fd != null) closeSync(fd)
        if (claimedPath) claimed.delete(claimedPath)
        return
      }
      if (fd == null) {
        for (const slug of safeReaddir(TASKS_BASE)) {
          const dir = join(TASKS_BASE, slug, sessionId, 'tasks')
          for (const f of safeReaddir(dir)) {
            // A shell command's own ("b…"): agents' ("a…", a link to their transcript) and
            // workflows' ("w…") outputs sit in the same folder.
            if (!/^b[a-z0-9]+\.output$/.test(f)) continue
            const p = join(dir, f)
            try {
              const s = lstatSync(p)
              if (!s.isFile()) continue
              if (s.birthtimeMs >= t0 && !claimed.has(p)) {
                claimed.add(p)
                claimedPath = p
                fd = openSync(p, 'r')
                break
              }
            } catch {}
          }
          if (fd != null) break
        }
        if (fd == null) return
      }
      const size = fstatSync(fd).size
      if (size > pos) {
        const buf = Buffer.alloc(Math.min(size - pos, 1024 * 1024))
        const n = readSync(fd, buf, 0, buf.length, pos)
        pos += n
        text = tail(text + buf.subarray(0, n).toString('utf8'))
        if (Date.now() - lastSent > 250 && ev.status === 'running') {
          lastSent = Date.now()
          ev.output = text
          emitEvent(sessionId, ev.id)
        }
      }
    } catch {}
  }, 300)
}
const claimed = new Set<string>()
function safeReaddir(d: string): string[] {
  try {
    return readdirSync(d)
  } catch {
    return []
  }
}

// ── Approvals ─────────────────────────────────────────────────────────────
const pending = new Map<string, Pending>()
export const pendingApprovals = () => [...pending.values()].map((p) => p.approval)
export const hasPairedDevice = () => listDevices().length > 0

export function requestApproval(a: ApprovalAsk, toolUseId: string | null, signal?: AbortSignal): Promise<Answer> {
  // Already allowed for this session: no need to ask (a subagent that started before, say).
  if (a.kind === 'tool' && allowedForSession(a.sessionId, a.toolName)) return Promise.resolve({ decision: 'session' })
  const id = randomUUID()
  const now = Date.now()
  const approval: Approval = { ...a, agentId: a.agentId ?? null, agentLabel: a.agentLabel ?? null, canSession: a.kind === 'tool' && SESSION_TOOLS.has(a.toolName), id, createdAt: now, expiresAt: now + AWAY_MS }
  return new Promise<Answer>((resolve) => {
    const timer = setTimeout(() => finish(id, { decision: 'timeout' }, 'timeout'), AWAY_MS)
    pending.set(id, { approval, toolUseId, resolve, timer })
    putEvent(a.sessionId, { id: `a:${id}`, kind: 'approval', ts: now, approvalId: id, summary: a.summary, resolution: null })
    waitStarted.set(id, { toolUseId, at: now })
    setState(a.sessionId, 'needs_you', a.summary)
    broadcast({ t: 'approval', approval })
    signal?.addEventListener('abort', () => finish(id, { decision: 'terminal' }, 'terminal'), { once: true })
  })
}

export function answerApproval(id: string, ans: Answer, by: 'phone' | 'mac'): boolean {
  const p = pending.get(id)
  if (ans.decision === 'session' && p?.approval.canSession) {
    // This tool for the rest of the session: this one, the same ones already waiting, and later ones.
    const { sessionId, toolName } = p.approval
    let set = sessionAllowed.get(sessionId)
    if (!set) sessionAllowed.set(sessionId, (set = new Set()))
    set.add(toolName)
    const ok = finish(id, ans, by)
    for (const q of [...pending.values()]) if (q.approval.sessionId === sessionId && q.approval.toolName === toolName) finish(q.approval.id, ans, by)
    return ok
  }
  return finish(id, ans.decision === 'session' ? { ...ans, decision: 'allow' } : ans, by)
}

/**
 * A terminal session moved on (new prompt, stop, end): any approval we still hold was answered
 * there. [mainOnly]: the main agent's turn moved on (a prompt, its Stop), which says nothing about
 * agents still at work in the background, whose approvals keep waiting.
 */
export function resolveSession(sessionId: string, mainOnly = false): void {
  for (const p of [...pending.values()]) if (p.approval.sessionId === sessionId && !(mainOnly && p.approval.agentId)) finish(p.approval.id, { decision: 'terminal' }, 'terminal')
}

/** A terminal session's tool finished: if we still hold its approval, the terminal answered it. */
export function resolveByToolUse(toolUseId: string): void {
  for (const p of pending.values()) if (p.toolUseId === toolUseId) finish(p.approval.id, { decision: 'terminal' }, 'terminal')
}

const waitStarted = new Map<string, { toolUseId: string | null; at: number }>()
function finish(id: string, ans: Answer, by: string): boolean {
  const p = pending.get(id)
  if (!p) return false
  pending.delete(id)
  // A tool's clock starts when it's allowed, not while it waits for the answer.
  const w = waitStarted.get(id)
  waitStarted.delete(id)
  const tev = w?.toolUseId ? sessions.get(p.approval.sessionId)?.parse?.byId.get(w.toolUseId) : undefined
  if (tev && tev.kind === 'tool') tev.ts = Date.now()
  clearTimeout(p.timer)
  const sid = p.approval.sessionId
  // "terminal": answered on the Mac (allowed, denied or cancelled; we can't tell which).
  const decision = ans.decision === 'terminal' ? 'unknown' : ans.decision === 'deny' || ans.decision === 'timeout' ? 'deny' : 'allow'
  const resolution = ans.decision === 'timeout' ? 'timeout' : ans.decision === 'terminal' ? 'terminal' : (decision as 'allow' | 'deny')
  const r = sessions.get(sid)
  const ev = r?.parse?.byId.get(`a:${id}`)
  if (ev && ev.kind === 'approval') {
    ev.resolution = resolution
    emitEvent(sid, ev.id)
  }
  broadcast({ t: 'approval.resolved', id, sessionId: sid, decision, by })
  if (ans.decision === 'timeout') {
    upsertSession(sid, { stoppedForAway: true })
    notice(sid, r && isCodexRec(r) ? 'Paused: nobody answered for 10 minutes, so Hypernova said no and stopped Codex until you’re back.' : 'Paused: nobody answered for 10 minutes, so Hypernova said no and asked Claude to wait for you.', 'warn')
  }
  if (r && ![...pending.values()].some((x) => x.approval.sessionId === sid)) {
    // An agent asked while the main agent was idle: it goes back to idle, its agents to work.
    if (r.state === 'needs_you') setState(sid, mainIdle.has(sid) && backgroundRunning(sid) ? 'idle' : 'working', r.activity)
    else upsertSession(sid, {})
  }
  try {
    p.resolve(ans)
  } catch (e) {
    log('approval resolve failed', e)
  }
  return true
}

// ── Usage ─────────────────────────────────────────────────────────────────
const FIVE_HOURS = 5 * 3600_000
const WEEK = 7 * 24 * 3600_000
let usage: Usage = { fiveHour: null, sevenDay: null, models: [], modelsObservedAt: null, modelsStale: false, probe: { ok: true, since: null }, source: 'none', observedAt: null }
export const getUsage = (): Usage => {
  // Signed out, no plan or on hold: the status line stops reporting limits, so the last numbers
  // stay as they were, marked stale, rather than rolling into fresh, empty windows.
  if (usageStale()) return { ...usage, source: 'stale' }
  const now = Date.now()
  const roll = (w: Window | null, period: number): Window | null => {
    if (!w) return null
    if (w.resetsAt && now > w.resetsAt) return { pct: 0, resetsAt: w.resetsAt + Math.ceil((now - w.resetsAt) / period) * period }
    return w
  }
  // A model's week rolls over like the all-models one: empty from its reset until the next look.
  const models = (usage.models ?? []).map((m) => (m.resetsAt == null ? m : { ...m, ...roll({ pct: m.pct, resetsAt: m.resetsAt }, WEEK)! }))
  const fiveHour = roll(usage.fiveHour, FIVE_HOURS)
  const sevenDay = roll(usage.sevenDay, WEEK)
  // The spend rate shows only while a session or its agents work: idle, the meters don't move.
  return { ...usage, fiveHour, sevenDay, rate: rateFor(anythingWorking(), fiveHour, sevenDay, now), models }
}
/**
 * A session on the subscription works, or agents it started run in the background: when Usage has
 * a spend rate, and when the usage probe looks often (usage-probe.ts). One on another provider
 * doesn't spend the subscription's windows, so it counts for neither. The probe's own Claude Code
 * is never one: it runs in a hidden folder, so it is never a session (pollRegistry, term/hidden.ts).
 */
export function anythingWorking(): boolean {
  for (const r of sessions.values()) if ((r.state === 'working' || backgroundRunning(r.id)) && isSubscription(r.providerId)) return true
  return false
}
const limitedIn = (k: 'five' | 'seven'): boolean => {
  const a = getAccount()
  return a.state === 'limited' && a.limit?.window === (k === 'five' ? 'five_hour' : 'seven_day')
}
/** When each window's numbers were true, and whether a limit Claude Code hit set them. */
const usageAt = { five: 0, seven: 0 }
const usageForced = { five: false, seven: false }
/**
 * Merge a reading true as of [observedAt]: a later reset wins; within the same window the newest
 * reading wins, even when it's lower (the owner reset his limits on claude.ai and the meter stayed
 * at 94%: "within a window usage only grows" kept idle sessions' old numbers). Readings within a
 * second of each other keep the higher. A limit Claude Code hit ([forced]) stands until a newer
 * reading or the reset.
 */
export function offerUsage(five: Reading | null, seven: Reading | null, observedAt: number, forced = false): void {
  const pick = (k: 'five' | 'seven', cur: Window | null, next: Reading | null): Window | null => {
    if (!next || !Number.isFinite(next.pct)) return cur
    const take = () => {
      usageAt[k] = Math.max(usageAt[k], observedAt)
      usageForced[k] = forced
      recordReading(k, next.pace ?? next.pct, next.resetsAt, observedAt, forced)
      return { pct: next.pct, resetsAt: next.resetsAt }
    }
    if (!cur) return take()
    if (Math.abs(next.resetsAt - cur.resetsAt) > 120_000) return next.resetsAt > cur.resetsAt ? take() : cur
    if (forced) return next.pct >= cur.pct || observedAt > usageAt[k] ? take() : cur
    // Stopped at this window's limit: the meter stays full whatever a status line still says (they
    // lag), until the account is out of it.
    if (usageForced[k] && limitedIn(k)) return cur
    if (observedAt > usageAt[k] + 1000) return take()
    if (observedAt >= usageAt[k] - 1000 && !usageForced[k] && next.pct > cur.pct) return take()
    return cur
  }
  const nf = pick('five', usage.fiveHour, five)
  const ns = pick('seven', usage.sevenDay, seven)
  if (JSON.stringify([nf, ns]) === JSON.stringify([usage.fiveHour, usage.sevenDay])) return
  usage = { ...usage, fiveHour: nf, sevenDay: ns, source: 'official', observedAt: Math.max(usage.observedAt ?? 0, observedAt) }
  sendUsage()
}

/**
 * The 5-hour window and the all-models week as Claude Code's /usage screen showed them at
 * [observedAt] (the usage probe, usage-probe.ts): official readings like a status line's, merged by
 * the same rules. The screen gives the reset to the minute: one within two minutes of the window
 * already known keeps that one's (a status line's is to the second), and a row with no reset, or
 * one no such window could have (further off than the window is long), takes the known window's
 * while that hasn't ended, else is passed over.
 */
export function offerScreenUsage(five: PlanWindow | null, seven: PlanWindow | null, observedAt: number): void {
  const fit = (w: PlanWindow | null, cur: Window | null, period: number): Reading | null => {
    if (!w || !Number.isFinite(w.pct)) return null
    const r = w.resetsAt
    const at = r != null && r > 0 && r <= observedAt + period + 5 * 60_000 ? (cur && Math.abs(r - cur.resetsAt) <= 120_000 ? cur.resetsAt : r) : cur && cur.resetsAt > observedAt ? cur.resetsAt : null
    if (at == null) return null
    // The screen floors to a whole percent and a status line doesn't: in the same window, a reading
    // less than a point below the one known is that same reading, not a drop (the meter ticked
    // back). For the pace, a floored percent is taken at the middle of the point it stands for.
    const pace = w.pct < 100 ? w.pct + 0.5 : w.pct
    if (cur && at === cur.resetsAt && w.pct < cur.pct && cur.pct - w.pct < 1) return { pct: cur.pct, resetsAt: at, pace: Math.max(cur.pct, pace) }
    return { pct: w.pct, resetsAt: at, pace }
  }
  offerUsage(fit(five, usage.fiveHour, FIVE_HOURS), fit(seven, usage.sevenDay, WEEK), observedAt)
}

/** The usage probe's health changed (usage-probe.ts): clients hear it with the usage. */
export function setProbeHealth(ok: boolean, since: number | null): void {
  if (usage.probe?.ok === ok && usage.probe?.since === since) return
  usage = { ...usage, probe: { ok, since } }
  if (!ok) usage = { ...usage, modelsStale: true }
  sendUsage()
}

/**
 * The per-model weeks the usage probe just read off /usage (usage-probe.ts). They replace the
 * last ones whole: a model whose row is gone has no week of its own any more.
 */
export function offerModelUsage(models: ModelWindow[], now = Date.now()): void {
  const next = models.map((m) => ({ label: m.label, pct: m.pct, resetsAt: m.resetsAt }))
  usage = { ...usage, models: next, modelsObservedAt: now, modelsStale: false }
  sendUsage()
}

/**
 * The usage probe saw /usage say its per-model breakdown is unavailable (rate limited, partial
 * data): the last good rows and their age stay, marked stale.
 */
export function markModelUsageStale(): void {
  if (usage.modelsStale) return
  usage = { ...usage, modelsStale: true }
  sendUsage()
}

/** What clients last heard about usage. */
let usageSent = ''
export function sendUsage(): void {
  const u = getUsage()
  usageSent = JSON.stringify(u)
  broadcast({ t: 'usage', usage: u })
}
/**
 * A window reset with nothing new to read (no session ran since): clients hear the fresh window
 * now, not at the next reading. Called every few seconds.
 */
export function tickUsage(): void {
  if (JSON.stringify(getUsage()) !== usageSent) sendUsage()
}

/**
 * Claude Code was refused for the 5-hour or weekly limit: that window is used up until it resets
 * at [resetsAt]. Status lines from the same window can't lower it (usage only grows within one),
 * however stale the numbers they still carry.
 */
export function forceUsage(window: 'five_hour' | 'seven_day', resetsAt: number, observedAt: number): void {
  const full = { pct: 100, resetsAt }
  offerUsage(window === 'five_hour' ? full : null, window === 'seven_day' ? full : null, observedAt, true)
}

// ── Discovery ─────────────────────────────────────────────────────────────
/**
 * How far back History reaches: conversations touched in the last 30 days, the 300 most recent.
 * (It was a week and 40, which the owner's Mac passes in four or five days.)
 */
const HISTORY_MS = 30 * 24 * 3600_000
const HISTORY_MAX = 300

/**
 * Managed sessions plus recent transcripts, so the phone has history; then the ones a restart of
 * the Mac cut short (reboot.ts), now that each one's last activity is known.
 */
export function discover(): void {
  // Every transcript by its session id. A managed session's is used wherever Claude Code wrote it
  // (the folder is named after the real path, which a symlinked cwd doesn't give) and however old.
  const root = join(CLAUDE_DIR, 'projects')
  const byId = new Map<string, { path: string; mtime: number }>()
  for (const slug of safeReaddir(root)) {
    for (const f of safeReaddir(join(root, slug))) {
      if (!f.endsWith('.jsonl')) continue
      const p = join(root, slug, f)
      try {
        const s = statSync(p)
        const id = basename(f, '.jsonl')
        // The same id in two folders: the one written last.
        if ((byId.get(id)?.mtime ?? -1) < s.mtimeMs) byId.set(id, { path: p, mtime: s.mtimeMs })
      } catch {}
    }
  }
  for (const m of managedSessions()) {
    const t = byId.get(m.id)
    // Shared-terminal sessions come back to life through the registry if their terminal still runs.
    // Last active when its transcript was last written, not when it started, or it sinks in the list.
    upsertSession(m.id, { cwd: m.cwd, origin: 'hypernova', state: m.engine === 'term' ? 'ended' : 'idle', createdAt: m.createdAt, lastActivityAt: Math.max(m.createdAt, t?.mtime ?? 0), ...(t ? { transcriptPath: t.path } : {}) })
  }
  const files = [...byId].filter(([, f]) => Date.now() - f.mtime < HISTORY_MS).sort((a, b) => b[1].mtime - a[1].mtime)
  for (const [id, f] of files.slice(0, HISTORY_MAX)) {
    const head = readHead(f.path)
    // The usage probe's, if its transcript outlived it (term/hidden.ts).
    if (!head.cwd || isHiddenCwd(head.cwd)) continue
    const r = upsertSession(id, { cwd: head.cwd, transcriptPath: f.path })
    if (!isSdk(id)) r.state = 'ended'
    r.lastActivityAt = f.mtime
    if (r.createdAt > f.mtime) r.createdAt = head.ts ?? f.mtime
    if (head.title && r.title === 'New session') r.title = trimText(head.title, 80) ?? r.title
    // Where it left off, for its card, before anyone opens it.
    if (head.lastText && !r.lastText) r.lastText = trimText(head.lastText, 200)
  }
  // Nothing runs yet (the registry and the terminals come next): an ended session's last activity
  // says whether a restart of the Mac cut it short recently.
  startAfterBoot(
    (id) => {
      const r = sessions.get(id)
      return r && r.state === 'ended' && !isSdk(id) ? r.lastActivityAt : undefined
    },
    () => managedSessions().filter((m) => m.engine === 'term').map((m) => m.id),
  )
}

/** A transcript's start is read until its folder and first prompt are known, this far at most. */
const HEAD_MAX = 8 * 1024 * 1024

function readHead(path: string): { cwd: string | null; title: string | null; ts: number | null; lastText: string | null } {
  let cwd: string | null = null
  let title: string | null = null
  let ts: number | null = null
  let lastText: string | null = null
  let fd: number | null = null
  try {
    fd = openSync(path, 'r')
    const size = fstatSync(fd).size
    const st = newParseState()
    // Whole lines, in growing steps. A first prompt with pasted screenshots is one line of hundreds
    // of KB: a fixed 256 KB head ended inside it, found no folder, and left the conversation out of
    // the phone's lists altogether.
    let pos = 0
    let held: Buffer[] = []
    scan: while (pos < Math.min(size, HEAD_MAX)) {
      const chunk = Buffer.alloc(Math.min(pos ? 512 * 1024 : 64 * 1024, size - pos))
      const n = readSync(fd, chunk, 0, chunk.length, pos)
      if (n <= 0) break
      pos += n
      let from = 0
      for (let nl = chunk.indexOf(0x0a); nl >= 0 && nl < n; nl = chunk.indexOf(0x0a, from)) {
        const line = Buffer.concat([...held, chunk.subarray(from, nl)]).toString('utf8')
        held = []
        from = nl + 1
        try {
          const o = JSON.parse(line)
          if (o.cwd && !cwd) cwd = o.cwd
          if (o.timestamp && !ts) ts = Date.parse(o.timestamp)
          feedLine(st, o)
        } catch {}
        // Past the old head with the folder known, a session with no plain prompt (commands only)
        // has nothing more to give from its start.
        if (cwd && (st.firstPrompt || pos >= 256 * 1024)) break scan
      }
      if (from < n) held.push(chunk.subarray(from, n))
    }
    // Titles are re-appended near the end of the file, usually after the first prompt (so the
    // loop above stopped before them). A custom title (/rename) beats the AI's, as it does once
    // the session is open, though Claude Code writes the AI's right after it. Those last lines carry
    // the folder too, for a first line longer than the head.
    const len = Math.min(64 * 1024, size)
    const end = Buffer.alloc(len)
    readSync(fd, end, 0, len, size - len)
    const lines = end.toString('utf8').split('\n').reverse()
    for (const line of cwd ? [] : lines) {
      if (!line.includes('"cwd"')) continue
      try {
        const o = JSON.parse(line)
        if (typeof o.cwd === 'string' && o.cwd) cwd = o.cwd
      } catch {}
      if (cwd) break
    }
    let custom: string | null = null
    let ai: string | null = null
    for (const line of lines) {
      if (!line.includes('"custom-title"') && !line.includes('"ai-title"')) continue
      try {
        const o = JSON.parse(line)
        if (o.type === 'custom-title' && o.customTitle) custom = o.customTitle
        else if (o.type === 'ai-title' && o.aiTitle && !ai) ai = o.aiTitle
      } catch {}
      if (custom) break
    }
    title = custom ?? st.title ?? ai ?? fallbackTitle(st)
    // Claude's last words: the main agent's last reply with text (not Claude Code's own error lines).
    for (const line of lines) {
      if (!line.includes('"assistant"')) continue
      try {
        const o = JSON.parse(line)
        if (o.type !== 'assistant' || o.isSidechain || o.isApiErrorMessage || o.message?.model === '<synthetic>') continue
        const texts = (Array.isArray(o.message?.content) ? o.message.content : []).filter((b: any) => b?.type === 'text' && typeof b.text === 'string' && b.text.trim())
        if (texts.length) lastText = texts[texts.length - 1].text
      } catch {}
      if (lastText) break
    }
  } catch {
  } finally {
    if (fd != null) closeSync(fd)
  }
  return { cwd, title, ts, lastText }
}

// ── Restarts ──────────────────────────────────────────────────────────────
// A session moving to another provider restarts in its shared terminal: the old claude gets SIGHUP,
// runs its SessionEnd hook and leaves the registry while the conversation goes on in a new one. By
// session id, the old process's pid (null when unknown) and until when its end is still expected.
const restarting = new Map<string, { pid: number | null; until: number }>()

/** Session [id]'s claude process [pid] is being replaced: its end, for [ms] from now, isn't the session's. */
export function markRestarting(id: string, pid: number | null, ms: number): void {
  restarting.set(id, { pid, until: Date.now() + ms })
}
/** The restart didn't happen (the old process is still there): it's the session's again. */
export function clearRestarting(id: string): void {
  restarting.delete(id)
}
/** Session [id] is being restarted: the process ending now is the old one. */
export function isRestarting(id: string, now = Date.now()): boolean {
  const r = restarting.get(id)
  if (r && r.until <= now) restarting.delete(id)
  return !!r && r.until > now
}
/**
 * [pid] is the process a restart replaces. When its pid wasn't known, none is: the new claude's
 * registry entry must still count (only the old one's SessionEnd hook, which carries no pid, is
 * passed over), and an old one that never registered has no entry to pass over.
 */
function replacedProcess(id: string, pid: number | null): boolean {
  if (!isRestarting(id)) return false
  const old = restarting.get(id)!.pid
  return old != null && old === pid
}

/** Live interactive sessions from Claude Code's registry (~/.claude/sessions/<pid>.json). */
export function pollRegistry(): void {
  const dir = join(CLAUDE_DIR, 'sessions')
  const live = new Set<string>()
  for (const f of safeReaddir(dir)) {
    if (!f.endsWith('.json')) continue
    let o: any
    try {
      o = JSON.parse(readFileSync(join(dir, f), 'utf8'))
    } catch {
      continue
    }
    if (!o?.sessionId || !o.pid || o.kind !== 'interactive' || isSdk(o.sessionId)) continue
    // The daemon's own Claude Code (the usage probe), in a folder marked hidden (term/hidden.ts).
    if (isHiddenCwd(o.cwd)) continue
    // Left from before the Mac last booted: its pid is some other process's now, and the
    // conversation isn't running (it resumes in place, not in a fork).
    if (fromBeforeBoot(o.startedAt)) continue
    try {
      process.kill(o.pid, 0)
    } catch {
      continue
    }
    // The old claude of a restart, still on its way out: not the session's process any more.
    if (replacedProcess(o.sessionId, o.pid)) continue
    live.add(o.sessionId)
    const r = sessions.get(o.sessionId) ?? upsertSession(o.sessionId, { cwd: o.cwd, origin: 'terminal', lastActivityAt: Number(o.statusUpdatedAt ?? o.updatedAt) || Date.now() })
    // Before the first read: whether Claude Code is busy says whether a queue read from the file still holds.
    r.pid = o.pid
    ensureParsed(r)
    const hasPending = [...pending.values()].some((p) => p.approval.sessionId === r.id)
    // Claude Code stays busy while agents run in the background after the main turn's Stop: the
    // main agent is idle then, and the session says so (its agents say they're at work).
    const busy = o.status === 'busy' && !(mainIdle.has(r.id) && backgroundRunning(r.id))
    const state: SessionState = hasPending ? 'needs_you' : busy ? 'working' : o.status === 'waiting' ? 'needs_you' : 'idle'
    const patch: Partial<Rec> = { pid: o.pid }
    // Claude Code stamps status changes; use that rather than "now" (a daemon restart isn't activity).
    const at = Number(o.statusUpdatedAt ?? o.updatedAt) || r.lastActivityAt
    if (r.state !== state) Object.assign(patch, { state, lastActivityAt: Math.max(r.lastActivityAt, at) })
    if (state === 'idle') patch.activity = null
    if (o.name && r.title === 'New session') patch.title = o.name
    upsertSession(r.id, patch)
  }
  for (const r of sessions.values()) {
    if (r.pid && !live.has(r.id) && r.state !== 'ended' && !isSdk(r.id) && !replacedProcess(r.id, r.pid)) {
      const pid = r.pid
      upsertSession(r.id, { state: 'ended', activity: null, pid: null })
      // Its process is gone (quit without a SessionEnd, or crashed): so is its background work. A
      // process still there was only missing from one read (its file caught mid-write): it comes
      // back on the next poll, and its agents never stopped.
      try {
        process.kill(pid, 0)
      } catch {
        mainEnded(r.id)
        endSessionAgents(r.id)
      }
    }
  }
  // The sessions open now, so that after a restart of the Mac the daemon knows which ones it cut
  // short (reboot.ts). One whose mark ran out goes back to History: its clients hear of it.
  const open = [...sessions.values()].filter((r) => r.state !== 'ended' && !isSdk(r.id) && !isCodexRec(r)).map((r) => r.id)
  for (const id of noteOpen(open)) if (sessions.has(id)) upsertSession(id, {})
}

// ── The main agent's own turn ─────────────────────────────────────────────
// When the main turn stopped (its Stop hook), by session, until a later turn starts: a prompt, a
// main-agent tool call, or a turn the transcript shows starting (a task notification's, say).
const mainIdle = new Map<string, number>()

/** The main agent's turn ended (its Stop hook). */
export function mainStopped(id: string): void {
  mainIdle.set(id, Date.now())
}

/** The main agent works again, as of [at] (Mac time): only a turn after its Stop counts. */
export function mainActive(id: string, at = Date.now()): void {
  const stopped = mainIdle.get(id)
  if (stopped != null && at > stopped) mainIdle.delete(id)
}

/** The session ended: nothing more to remember about its main agent's turn. */
export function mainEnded(id: string): void {
  mainIdle.delete(id)
}

export const isLiveTerminal = (id: string) => {
  const r = sessions.get(id)
  return !!r && r.origin === 'terminal' && r.state !== 'ended' && !!r.pid
}

export function projectsFromSessions(): Map<string, { lastActivityAt: number; count: number }> {
  const out = new Map<string, { lastActivityAt: number; count: number }>()
  for (const r of sessions.values()) {
    const p = out.get(r.cwd) ?? { lastActivityAt: 0, count: 0 }
    p.count++
    p.lastActivityAt = Math.max(p.lastActivityAt, r.lastActivityAt)
    out.set(r.cwd, p)
  }
  return out
}
