// Carrying on after a limit (docs/PROTOCOL.md, "Auto-continue"). Each blocked session that can wait
// its limit out has a resume: it carries on by itself at a time (`armed`), won't (`off`), or could
// now and didn't (`ready`, with Continue). Claude Code decides for its own terminal sessions on the
// subscription: its notices say so, and "Don't" or "Continue automatically" drive its terminal the
// way a person would (Esc, /rate-limit-options). The daemon carries on what Claude Code can't:
// SDK sessions at the subscription's reset, and sessions on other providers at a rate limit's
// announced reset. It never carries on by itself after running out of credit or a refused key
// (nothing to wait for, and a continue costs money): when that's over, the session says so and
// offers Continue.
//
// Kept in <support>/resume.json (0600), so a restart keeps what was armed, cancelled or ready.
import { join } from 'node:path'
import { readFileSync } from 'node:fs'
import { CLAUDE_DIR, SUPPORT, log, readJson, writeJson } from './util.ts'
import { broadcast, ensureParsed, getSession, pendingApprovals, resumeWith, view, type Session } from './hub.ts'
import { isSdk, isSubscription } from './store.ts'
import { getAccount, providerStatus } from './account.ts'
import { CONTINUE_TEXT, parseWhen, type Blocked, type BlockedKind, type LimitNotice, type Resume, type ResumeWhy } from './blockers.ts'
import { answerMenu, createSharedSession, pressKeys, sharedTerminalsAvailable, termChildPid, termOf, termScreen, typeInto, type Typed } from './terms.ts'
import { sendPrompt } from './runner.ts'
import { boxLines, boxText, isWorking, menuOf, registryBusy, type Menu } from './term/shared.ts'

const PATH = join(SUPPORT, 'resume.json')
/** Claude Code carries on only within this of the limit (its own rule; SDK sessions follow it too). */
const HORIZON_MS = 24 * 3600_000
/** It re-arms at most this many times in a row (Claude Code's rule). */
const MAX_REARMS = 2
/** More than this late (the Mac slept, the daemon was down), it doesn't fire: it waits for you. */
const LATE_MS = 30 * 60_000
/** A ready card stays this long, then the session is just idle. */
const READY_MS = 12 * 3600_000
/** Claude Code's notice follows its error within a moment: until then its resume isn't shown. */
const NOTICE_WAIT_MS = 5000
/** After it carried on: a block within this counts as hitting the limit again (a re-arm). */
const FIRED_MS = 15 * 60_000

type State = 'armed' | 'off' | 'ready' | 'fired' | 'hidden'
/**
 * One session's resume. `since`: when its block stopped it (the block it's about). `state` adds
 * two the clients never see: `fired` (it carried on; a block soon after is the same limit again)
 * and `hidden` (out of credit or a refused key: never carried on by itself, but followed, so the
 * session says when it's over). `resetsAt`: when the cause lifts, if known. `noticed`: Claude Code
 * has said something about it. `liftedAt`: when the cause ended.
 */
type Entry = {
  sid: string
  providerId: string
  since: number
  kind: BlockedKind
  text: string
  resetsAt: number | null
  state: State
  at: number | null
  by: 'claude' | 'hypernova'
  why: ResumeWhy | null
  rearms: number
  createdAt: number
  noticed: boolean
  noticeAt: number
  firedAt: number | null
  liftedAt: number | null
}

const entries = new Map<string, Entry>()
for (const e of readJson<Entry[]>(PATH, [])) if (e && typeof e.sid === 'string') entries.set(e.sid, e)

let clock = () => Date.now()
let rand = () => Math.random()
/** Tests set the time and the dice. */
export function useClock(now: () => number, random: () => number = Math.random): void {
  clock = now
  rand = random
}

function save(): void {
  writeJson(PATH, [...entries.values()])
}

/** Send the session again (its blocked view changed, though its record didn't). */
function publish(sid: string): void {
  const r = getSession(sid)
  if (r) broadcast({ t: 'session', session: view(r) })
}

/** Claude Code's own setting: on unless it says false (`autoContinueAtUsageLimit`). */
function settingOff(): boolean {
  try {
    return JSON.parse(readFileSync(join(CLAUDE_DIR, 'settings.json'), 'utf8'))?.autoContinueAtUsageLimit === false
  } catch {
    return false
  }
}
/** When it carries on after a reset: a moment after (Claude Code waits 30–90 s; other providers 5–20 s). */
const jitter = (reset: number, provider: boolean) => reset + Math.round((provider ? 5_000 + rand() * 15_000 : 30_000 + rand() * 60_000))

/** Where the daemon can type a continuation for this session: its shared terminal, its SDK run, or (ended) a new terminal. */
function deliverable(s: Pick<Session, 'id' | 'term' | 'state'>): boolean {
  return !!s.term || isSdk(s.id) || s.state === 'ended'
}

/** The reset it waits for, as the account or the provider says now (else what the block said). */
function resetOf(e: Entry): number | null {
  if (!isSubscription(e.providerId)) {
    const st = providerStatus(e.providerId)
    return st?.state === 'limited' ? (st.resetsAt ?? e.resetsAt) : e.resetsAt
  }
  return e.resetsAt
}

/** Arm [e] for the daemon to carry on, at its reset (or say why it can't). */
function armByUs(e: Entry, now: number): void {
  const reset = resetOf(e)
  const provider = !isSubscription(e.providerId)
  e.by = 'hypernova'
  if (reset == null) Object.assign(e, { state: 'off', at: null, why: 'no_reset' })
  else if (reset - now > HORIZON_MS) Object.assign(e, { state: 'off', at: reset, why: 'far' })
  else Object.assign(e, { state: 'armed', at: jitter(Math.max(reset, now), provider), why: null })
}

/** What a new block starts as (docs/PROTOCOL.md, "Auto-continue", the table). */
function initial(s: Session, b: Blocked, since: number, rearms: number, now: number): Entry | null {
  const e: Entry = {
    sid: s.id, providerId: s.providerId, since, kind: b.kind, text: b.text, resetsAt: b.resetsAt,
    state: 'off', at: b.resetsAt, by: 'hypernova', why: null, rearms, createdAt: now, noticed: false, noticeAt: 0, firedAt: null, liftedAt: null,
  }
  if (isSubscription(s.providerId)) {
    if (b.kind !== 'limit' && b.kind !== 'model') return null
    if (!isSdk(s.id)) {
      // A terminal: Claude Code carries on by itself, and its notices say so. Until one comes, off.
      e.by = 'claude'
      // The transcript already said it's armed (its notice came in the same read as the error).
      if (b.autoResumeAt != null) return Object.assign(e, { state: 'armed' as const, at: b.autoResumeAt, noticed: true, noticeAt: since })
      if (b.resetsAt != null && b.resetsAt - now > HORIZON_MS) e.why = 'far'
      else if (settingOff()) e.why = 'setting'
      return e
    }
    // An SDK session: the daemon, for the 5-hour and weekly windows only (not one model's limit).
    if (b.kind === 'model') return Object.assign(e, { why: 'no_reset' as const })
    if (settingOff()) return Object.assign(e, { why: 'setting' as const })
    if (rearms > MAX_REARMS) return Object.assign(e, { why: 'repeated' as const })
    armByUs(e, now)
    return e
  }
  if (b.kind !== 'provider') return null
  const st = providerStatus(s.providerId)
  if (st?.state === 'limited') {
    e.resetsAt = st.resetsAt ?? b.resetsAt
    if (!deliverable(s)) return Object.assign(e, { at: e.resetsAt })
    if (rearms > MAX_REARMS) return Object.assign(e, { at: e.resetsAt, why: 'repeated' as const })
    armByUs(e, now)
    return e
  }
  if (st?.state === 'down') return Object.assign(e, { at: null, why: 'no_reset' as const })
  // Out of credit, a refused key (or a status already gone): never by itself. Followed, so it can
  // say when it's over.
  return Object.assign(e, { state: 'hidden' as const, at: null })
}

// ── What the session hears (hub.ts calls these) ────────────────────────
/** Session [sid]'s block changed to [b] (null: it no longer holds), as of [at] when it stopped it. */
function blocked(sid: string, b: Blocked | null, at: number): void {
  const s0 = getSession(sid)
  if (!s0) return
  const s = view(s0)
  const now = clock()
  const e = entries.get(sid)
  if (b) {
    if (b.kind !== 'limit' && b.kind !== 'model' && b.kind !== 'provider') {
      if (e) drop(sid)
      return
    }
    if (e && e.since === at && e.state !== 'fired') {
      // The same block, told more (a reset it didn't know): a schedule of the daemon's follows it.
      if (b.resetsAt !== e.resetsAt) {
        e.resetsAt = b.resetsAt
        if (e.by === 'hypernova' && (e.state === 'armed' || e.why === 'no_reset')) armByUs(e, now)
        changed(e)
      }
      return
    }
    // Hit again soon after it carried on by itself: one more re-arm.
    const again = e?.state === 'fired' && e.firedAt != null && at >= e.firedAt - 1000 && now - e.firedAt < FIRED_MS
    const next = initial(s, b, at, again ? e!.rearms + 1 : 0, now)
    if (!next) {
      if (e) drop(sid)
      return
    }
    entries.set(sid, next)
    if (next.state === 'armed') log('resume', sid.slice(0, 8), 'armed by', next.by, 'in', Math.round(((next.at ?? now) - now) / 1000), 's')
    changed(next)
    return
  }
  if (!e || e.state === 'fired' || e.state === 'ready') return
  // It moved on (a prompt, a reply): nothing to carry on.
  if (s.state === 'working' || s.state === 'starting') return drop(sid)
  lifted(e, now)
}

/** The cause of [e]'s block is over and the session didn't carry on: armed waits for its time; else it's ready. */
function lifted(e: Entry, now: number): void {
  e.liftedAt = now
  if (e.state === 'armed') return changed(e)
  e.state = 'ready'
  e.at = now
  // A provider that recovered (credit again, a key that works, a limit over): "back". Why it didn't
  // carry on stays when the owner or Claude Code chose so.
  e.why = e.kind === 'provider' ? 'back' : e.why === 'cancelled' || e.why === 'repeated' || e.why === 'exited' ? e.why : null
  log('resume', e.sid.slice(0, 8), 'ready', e.why ?? '')
  changed(e)
}

/** Claude replied in session [sid] to a request sent at [at]: whatever it waited on is over. */
function replied(sid: string, at: number): void {
  const e = entries.get(sid)
  if (e && at > e.since) drop(sid)
}

/** One of Claude Code's limit notices, in session [sid]'s transcript at [at]. */
function noticed(sid: string, n: LimitNotice, at: number): void {
  const now = clock()
  let e = entries.get(sid)
  // From before the block this resume is about: old news.
  if (e && at < e.since - 60_000) return
  if (!e) {
    // "Usage limit has reset · press enter to continue": a ready card even on a session whose
    // block cleared already, unless it's old news (a prompt came after, or long ago).
    if (n.kind !== 'ready' || now - at > READY_MS) return
    const r = getSession(sid)
    if (!r || r.state === 'working' || lastPromptAt(sid) > at) return
    e = {
      sid, providerId: r.providerId, since: at, kind: 'limit', text: n.text, resetsAt: null, state: 'ready', at, by: 'claude', why: n.why,
      rearms: 0, createdAt: now, noticed: true, noticeAt: at, firedAt: null, liftedAt: at,
    }
    entries.set(sid, e)
    return changed(e)
  }
  if (at < e.noticeAt) return
  e.noticed = true
  e.noticeAt = at
  switch (n.kind) {
    case 'reset':
      // "Usage limit reset · continuing automatically": Claude Code carries on now.
      return drop(sid)
    case 'armed':
      Object.assign(e, { state: 'armed', by: 'claude', at: n.autoResumeAt ?? e.resetsAt, why: null })
      break
    case 'cancelled':
      Object.assign(e, { state: e.liftedAt ? 'ready' : 'off', by: 'claude', at: e.liftedAt ?? e.resetsAt, why: 'cancelled' })
      break
    case 'off':
      // Claude Code exited during the wait: the daemon can carry it on in a new terminal.
      if (n.why === 'exited') Object.assign(e, { state: 'off', by: 'hypernova', at: e.resetsAt, why: 'exited' })
      else Object.assign(e, { state: 'off', by: 'claude', at: e.resetsAt, why: n.why })
      break
    case 'ready':
      Object.assign(e, { state: 'ready', by: 'claude', at, why: n.why, liftedAt: e.liftedAt ?? at })
      break
  }
  changed(e)
}

/** When the owner last sent session [sid] a prompt (0: never, as far as its history shows). */
function lastPromptAt(sid: string): number {
  const r = getSession(sid)
  if (!r) return 0
  const evs = ensureParsed(r).events
  for (let i = evs.length - 1; i >= 0; i--) if (evs[i]!.kind === 'user') return evs[i]!.ts
  return 0
}

// ── What the clients see ────────────────────────────────────────────────
/** [e] as Session.blocked.resume: null while it isn't one to show. */
function resumeOf(e: Entry, s: Session, now = clock()): Resume | null {
  if (e.state === 'hidden' || e.state === 'fired') return null
  // Claude Code's notice follows its error within a moment: no "off" flashes before it.
  if (e.by === 'claude' && !e.noticed && now - e.createdAt < NOTICE_WAIT_MS) return null
  const shared = !!s.term
  const canCancel = e.state === 'armed' && (e.by === 'hypernova' || shared)
  let canArm = false
  if (e.state === 'off') {
    if (e.by === 'claude') canArm = shared && e.why !== 'far'
    else if (e.why === 'exited') canArm = true
    else {
      const reset = resetOf(e)
      canArm = deliverable(s) && reset != null && reset - now <= HORIZON_MS && e.why !== 'no_reset'
    }
  }
  return { state: e.state, at: e.at, by: e.by, canCancel, canArm, why: e.why }
}

/**
 * Session [s]'s block as the clients see it: with its resume, and `autoResumeAt` (older clients)
 * set while it's armed. A block whose cause is over stays on while it's armed (it carries on at
 * its time) or ready (Continue), drawn from the resume.
 */
function blockedView(s: Session): Blocked | null {
  const b = s.blocked
  const e = entries.get(s.id)
  const limitish = (k: BlockedKind) => k === 'limit' || k === 'model' || k === 'provider'
  if (!e) return b && limitish(b.kind) ? { ...b, resume: null } : b
  const r = resumeOf(e, s)
  if (b) return limitish(b.kind) ? { ...b, autoResumeAt: r?.state === 'armed' ? r.at : e.by === 'claude' && !e.noticed ? b.autoResumeAt : null, resume: r } : b
  if (!r || (r.state !== 'armed' && r.state !== 'ready')) return null
  return { kind: e.kind, text: e.text, resetsAt: e.resetsAt, autoResumeAt: r.state === 'armed' ? r.at : null, resume: r }
}

// ── Keeping it going ────────────────────────────────────────────────────
let timer: NodeJS.Timeout | null = null
let sweeper: NodeJS.Timeout | null = null

function changed(e: Entry): void {
  save()
  publish(e.sid)
  schedule()
}

function drop(sid: string): void {
  if (!entries.delete(sid)) return
  save()
  publish(sid)
  schedule()
}

/** One timer, for the nearest thing to do; a slower look over all of them while any are kept. */
function schedule(): void {
  if (timer) clearTimeout(timer)
  timer = null
  const now = clock()
  let next = Infinity
  for (const e of entries.values()) {
    if (e.state === 'armed' && e.by === 'hypernova' && e.at != null) next = Math.min(next, e.at)
    if (e.by === 'claude' && !e.noticed && now - e.createdAt < NOTICE_WAIT_MS) next = Math.min(next, e.createdAt + NOTICE_WAIT_MS + 50)
  }
  if (next !== Infinity) {
    timer = setTimeout(() => void tick(), Math.max(0, Math.min(next - now, 2 ** 31 - 1)))
    timer.unref()
  }
  if (entries.size && !sweeper) {
    sweeper = setInterval(() => void tick(), 15_000)
    sweeper.unref()
  } else if (!entries.size && sweeper) {
    clearInterval(sweeper)
    sweeper = null
  }
}

let ticking = false
/** Fire what's due, notice what's over. */
export async function tick(): Promise<void> {
  if (ticking) return
  ticking = true
  try {
    const now = clock()
    for (const e of [...entries.values()]) {
      const r = getSession(e.sid)
      if (!r) {
        // Not seen (yet) after a restart: kept a while, then let go.
        if (now - Math.max(e.createdAt, e.liftedAt ?? 0) > READY_MS) drop(e.sid)
        continue
      }
      const s = view(r)
      // Moved to another provider: its resume goes with the old one.
      if (s.providerId !== e.providerId) {
        drop(e.sid)
        continue
      }
      if (e.state === 'fired') {
        if (e.firedAt != null && now - e.firedAt > FIRED_MS) drop(e.sid)
        continue
      }
      if (e.state === 'ready') {
        if (now - (e.at ?? now) > READY_MS || s.state === 'working' || s.state === 'starting') drop(e.sid)
        continue
      }
      // A prompt of the owner's (a new turn) ends it; so does a session that ended, unless the
      // daemon is to carry it on in a new terminal.
      if ((s.state === 'working' || s.state === 'starting') && now - e.createdAt > NOTICE_WAIT_MS) {
        drop(e.sid)
        continue
      }
      if (s.state === 'ended') {
        if (e.by === 'claude' && e.state === 'armed') {
          Object.assign(e, { state: 'off', by: 'hypernova', why: 'exited', at: e.at })
          changed(e)
        } else if (e.by === 'hypernova' && (e.state === 'armed' || e.why === 'exited')) {
          // Carried on in a new terminal at its time.
        } else {
          drop(e.sid)
          continue
        }
      }
      // A provider limit whose reset moved later, or whose status is now worse.
      if (e.by === 'hypernova' && e.kind === 'provider') {
        const st = providerStatus(e.providerId)
        if (st && (st.state === 'no_credit' || st.state === 'bad_key') && e.state !== 'hidden') {
          Object.assign(e, { state: 'hidden', at: null, why: null })
          changed(e)
        } else if (e.state === 'armed' && st?.state === 'limited' && st.resetsAt != null && st.resetsAt > (e.at ?? 0)) {
          armByUs(e, now)
          changed(e)
        }
      }
      // The cause ended while nothing said so (a block the hub cleared before the entry was made).
      if (!s.blocked && (e.state === 'off' || e.state === 'hidden') && now - e.createdAt > NOTICE_WAIT_MS) lifted(e, now)
      if (e.state === 'armed' && e.at != null && now >= e.at) {
        if (e.by === 'hypernova') await fire(e, s, now)
        else if (now - e.at > LATE_MS && s.state !== 'working') {
          // Claude Code didn't carry on (it slept through it, or its terminal is gone): it waits for you.
          Object.assign(e, { state: 'ready', why: 'slept', at: now, liftedAt: e.liftedAt ?? now })
          changed(e)
        }
      }
    }
    // The 5-second wait for Claude Code's notice is over: send what's shown now.
    for (const e of entries.values()) if (e.by === 'claude' && !e.noticed && now - e.createdAt >= NOTICE_WAIT_MS && now - e.createdAt < NOTICE_WAIT_MS + 20_000) publish(e.sid)
  } finally {
    ticking = false
    schedule()
  }
}

/** Carry session [s] on now, if it can: idle, nothing open, and the limit really over. */
async function fire(e: Entry, s: Session, now: number): Promise<void> {
  if (now - (e.at ?? now) > LATE_MS) {
    Object.assign(e, { state: 'ready', why: 'slept', at: now, liftedAt: e.liftedAt ?? now })
    return changed(e)
  }
  // Still limited, with a later reset: wait for that one.
  if (isSubscription(e.providerId)) {
    const l = getAccount().limit
    if (getAccount().state === 'limited' && l?.resetsAt != null && l.resetsAt > now + 5000 && !l.usingCredits) {
      e.at = jitter(l.resetsAt, false)
      return changed(e)
    }
  } else {
    const st = providerStatus(e.providerId)
    if (st?.state === 'limited' && st.resetsAt != null && st.resetsAt > now + 1000) {
      e.at = jitter(st.resetsAt, true)
      return changed(e)
    }
    if (st && st.state !== 'limited') {
      // Out of credit or a refused key now: never by itself.
      Object.assign(e, { state: st.state === 'down' ? 'off' : 'hidden', at: null, why: st.state === 'down' ? 'no_reset' : null })
      return changed(e)
    }
  }
  // Busy with something of the owner's: a moment later.
  const asking = pendingApprovals().some((a) => a.sessionId === s.id) || !!s.menu || s.state === 'needs_you'
  if (asking) {
    e.at = now + 20_000
    return changed(e)
  }
  const text = isSubscription(e.providerId) ? CONTINUE_TEXT.claude : CONTINUE_TEXT.provider
  const r = await deliverer(s, text)
  if (r === 'retry') {
    e.at = now + 20_000
    return changed(e)
  }
  if (r === 'typing') {
    // Text of the owner's in the terminal's box: not typed over. It waits for you.
    Object.assign(e, { state: 'ready', why: 'dropped', at: now, liftedAt: e.liftedAt ?? now })
    return changed(e)
  }
  if (r === 'no') {
    Object.assign(e, { state: 'ready', why: e.why ?? null, at: now, liftedAt: e.liftedAt ?? now })
    return changed(e)
  }
  log('resume', s.id.slice(0, 8), 'carried on by itself after the limit')
  Object.assign(e, { state: 'fired', firedAt: now })
  changed(e)
}

/**
 * Type [text] as session [s]'s next prompt: into its shared terminal, its SDK run, or (it ended)
 * a new shared terminal on the same conversation. 'retry': not now; 'typing': the owner's text is
 * in the way; 'no': there's no way to (a terminal Hypernova doesn't hold).
 */
async function deliver(s: Session, text: string): Promise<'ok' | 'retry' | 'typing' | 'no'> {
  const tid = s.term?.id ?? termOf(s.id)
  if (tid && s.state !== 'ended') {
    const r = await typeInto(tid, text)
    if (r.ok) return 'ok'
    return r.reason === 'busy_input' ? 'typing' : r.reason === 'no_input' || r.reason === 'not_started' || r.reason === 'unreachable' ? 'retry' : 'no'
  }
  if (isSdk(s.id)) {
    sendPrompt(s.id, text)
    return 'ok'
  }
  if (s.state === 'ended' && (await sharedTerminalsAvailable())) {
    createSharedSession({ cwd: s.cwd, providerId: s.providerId, prompt: text, resume: s.id })
    return 'ok'
  }
  return 'no'
}

type Deliver = (s: Session, text: string) => Promise<'ok' | 'retry' | 'typing' | 'no'>
let deliverer: Deliver = deliver
/** Tests take the continuation themselves instead of typing it anywhere. */
export function useDeliver(f: Deliver): void {
  deliverer = f
}

// ── Asked from a phone or the Mac ───────────────────────────────────────
export class ResumeError extends Error {
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

/**
 * The terminal, as the auto-continue switch drives it: its screen, keys, typing and menu answers.
 * Tests hand in a stand-in.
 */
export type TermIO = {
  screen(): Promise<string | null>
  keys(data: string): void
  type(text: string): Promise<{ ok: boolean; reason?: string; text?: string }>
  pick(menuId: string, choice: number): Promise<boolean>
  sleep(ms: number): Promise<void>
  /** Claude Code's registry says it's working (a turn started that the screen may not show yet). */
  busy?(): boolean
}

function realIO(tid: string): TermIO {
  return {
    screen: async () => (await termScreen(tid))?.text ?? null,
    keys: (d) => void pressKeys(tid, d),
    type: (t) => typeInto(tid, t),
    pick: async (id, choice) => (await answerMenu(tid, id, { choice })).ok,
    sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
    busy: () => registryBusy(termChildPid(tid)),
  }
}

const driving = new Set<string>()
/** Claude Code's menu for a limit, "What do you want to do?". */
const isLimitMenu = (m: Menu | null): m is Menu => !!m && /^What do you want to do\?/.test(m.title ?? '')
const ARM_OPTION = /^Wait here, then continue automatically/
const CANCEL_OPTION = /^Don.t continue automatically$/

/**
 * Turn session [sid]'s carrying on by itself on or off (POST /v1/sessions/{id}/auto-continue).
 * The daemon's own schedule just changes; Claude Code's is changed in its terminal: Esc while it's
 * armed, else its /rate-limit-options menu, and only when Claude is idle, nothing else is open and
 * the input box is empty (or holds Claude Code's "continue").
 */
export async function setAutoContinue(sid: string, on: boolean, io?: TermIO): Promise<void> {
  const r = getSession(sid)
  if (!r) throw new ResumeError(404, 'no_session', 'That session no longer exists.')
  const e = entries.get(sid)
  const s = view(r)
  const rv = e ? resumeOf(e, s) : null
  if (!e || !rv || rv.state === 'ready') throw new ResumeError(409, 'not_limited', 'This session isn’t waiting on a limit.')
  if (on === (rv.state === 'armed')) return
  if (on ? !rv.canArm : !rv.canCancel) throw new ResumeError(409, 'not_available', on ? 'This session can’t carry on by itself from here.' : 'This can only be stopped in the terminal on the Mac.')
  const now = clock()
  if (e.by === 'hypernova') {
    if (on) {
      e.rearms = 0
      if (e.why === 'exited') e.resetsAt ??= now
      armByUs(e, now)
    } else Object.assign(e, { state: 'off', at: resetOf(e), why: 'cancelled' })
    log('resume', sid.slice(0, 8), on ? 'armed' : 'cancelled', 'from a client')
    return changed(e)
  }
  const tid = s.term?.id ?? termOf(sid)
  if (!tid) throw new ResumeError(409, 'no_terminal', 'This session isn’t running in a shared terminal.')
  // Claude Code carried on a moment ago (its notice isn't read yet): an Esc now would stop the
  // turn it just started, so nothing is pressed.
  if (s.state === 'working' || s.state === 'starting') throw new ResumeError(409, 'not_limited', WORKING_AGAIN)
  if (pendingApprovals().some((a) => a.sessionId === sid)) throw new ResumeError(409, 'needs_answer', 'Claude is waiting for your answer above. Answer it first.')
  if (driving.has(sid)) throw new ResumeError(409, 'waiting', 'The terminal is still being changed. Try again in a moment.')
  driving.add(sid)
  try {
    const before = e.noticeAt
    const done = await drive(io ?? realIO(tid), on, () => e.noticeAt > before && (on ? e.state === 'armed' : e.state !== 'armed'))
    if (done.ok && on && e.state !== 'armed') {
      // Seen on its screen (or in its menu) before the transcript says so: armed at the time it names.
      Object.assign(e, { state: 'armed', at: (done.when ? parseWhen(done.when, clock()) : null) ?? e.resetsAt, why: null, noticed: true })
      changed(e)
    }
    if (!on && e.state === 'armed' && done.ok) {
      Object.assign(e, { state: 'off', why: 'cancelled', at: e.resetsAt, noticed: true })
      changed(e)
    }
    log('resume', sid.slice(0, 8), on ? 'arm' : 'cancel', 'in the terminal:', done.ok ? 'done' : done.code)
    if (!done.ok) {
      if (done.code === 'working') throw new ResumeError(409, 'not_limited', WORKING_AGAIN)
      const why: Record<string, [string, Record<string, unknown>?]> = {
        waiting: ['Claude is showing something in the terminal. Open it to answer.'],
        typing: ['There’s unsent text in the terminal. Open it to finish.', done.pending ? { pending: done.pending } : undefined],
        not_available: ['Claude Code doesn’t offer that for this limit.'],
        not_confirmed: ['Didn’t go through. Open the terminal to check.'],
        terminal_busy: ['The terminal didn’t answer. Try again in a moment.'],
      }
      const [msg, extra] = why[done.code] ?? why.not_confirmed!
      throw new ResumeError(409, done.code, msg, extra)
    }
  } finally {
    driving.delete(sid)
  }
}

const WORKING_AGAIN = 'Claude is working again: the limit is over.'

type Driven =
  | { ok: true; when?: string }
  | { ok: false; code: 'waiting' | 'working' | 'typing' | 'not_available' | 'not_confirmed' | 'terminal_busy'; pending?: string }

/** One of Claude Code's lines saying it will carry on by itself (its notice, the menu's answer, or its live status). */
const ARMED_LINE = /^(?:(?:Usage limit reached(?: again)? · )?continuing automatically|Claude Code will continue automatically)\b/i
/** One saying it won't (any more): cancelled, off, stopped, didn't run, or carried on already. */
const ENDED_LINE = /^(?:Automatic continue (?:cancelled|was turned off|stopped|did not run)|Usage limit (?:reset|available again|has reset))\b/i

/**
 * Whether Claude Code will carry on by itself, as its screen says now: the newest of its limit
 * lines above the input box decides. Its armed notice is part of the conversation, so it stays
 * on screen after a cancel: "esc to cancel" somewhere above means nothing on its own. `when`:
 * the time the armed line names ("2am", "shortly"). `armedLines`: how many armed lines show, so
 * a new one can be told from an old one.
 */
export function armedOnScreen(lines: string[]): { armed: boolean; when: string | null; armedLines: number } {
  const box = boxLines(lines)
  let armed = false
  let when: string | null = null
  let armedLines = 0
  for (const raw of box ? lines.slice(0, box.top) : lines) {
    // Claude Code draws its notices after a bullet or "⎿": the words start at the first letter.
    const l = raw.replace(/^[^\p{L}]+/u, '').trim()
    if (ARMED_LINE.test(l)) {
      armed = true
      armedLines++
      when = /automatically(?: at)? (.+?)(?: · |\.(?:\s|$)|$)/i.exec(l)?.[1]?.trim() || null
    } else if (ENDED_LINE.test(l)) {
      armed = false
      when = null
    }
  }
  return { armed, when, armedLines }
}

/**
 * Drive the terminal to arm ([on]) or cancel Claude Code's carrying on, confirmed by [confirmed]
 * (its notice) or by what its screen shows, within 4 s. Nothing is pressed while Claude works.
 */
export async function drive(io: TermIO, on: boolean, confirmed: () => boolean): Promise<Driven> {
  const look = async () => (await io.screen())?.split('\n') ?? null
  let lines = await look()
  if (!lines) return { ok: false, code: 'terminal_busy' }
  const start = armedOnScreen(lines)
  const wait = async (until: (lines: string[]) => boolean): Promise<string[] | null> => {
    for (let i = 0; i < 10; i++) {
      await io.sleep(400)
      const l = await look()
      if (l && until(l)) return l
      if (confirmed()) return l ?? []
    }
    return null
  }
  // Done when the screen's newest limit line says so. Arming needs an armed line that wasn't
  // there before (an old one stays on screen after a cancel).
  const done = (l: string[]) => {
    const now = armedOnScreen(l)
    return on ? now.armed && (!start.armed || now.armedLines > start.armedLines) : !now.armed
  }
  const finish = async (): Promise<Driven> => {
    const l = await wait(done)
    if (l && l.length && done(l)) {
      const when = on ? armedOnScreen(l).when : null
      return when ? { ok: true, when } : { ok: true }
    }
    if (confirmed()) return { ok: true }
    return { ok: false, code: 'not_confirmed' }
  }
  // Claude Code's menu offers "Don't continue automatically" only while it's armed, and "Wait
  // here, then continue automatically" only while it isn't: the other one means it's so already.
  const answer = async (menu: Menu): Promise<Driven> => {
    const find = (want: RegExp) => menu.options.findIndex((o) => want.test(o.label.trim()))
    const i = find(on ? ARM_OPTION : CANCEL_OPTION)
    if (i >= 0) {
      if (!(await io.pick(menu.id, i))) return { ok: false, code: 'not_confirmed' }
      return finish()
    }
    io.keys('\x1b')
    if (find(on ? CANCEL_OPTION : ARM_OPTION) >= 0) return on && start.when ? { ok: true, when: start.when } : { ok: true }
    return { ok: false, code: 'not_available' }
  }

  // Claude Code's own menu for the limit may be open already (it opens it when it isn't armed).
  let menu = menuOf(lines)
  if (menu) return isLimitMenu(menu) ? answer(menu) : { ok: false, code: 'waiting' }
  const box = boxLines(lines)
  if (!box) return { ok: false, code: 'waiting' }
  // Claude carried on a moment ago: an Esc would stop that turn.
  if (isWorking(box) || io.busy?.()) return { ok: false, code: 'working' }
  const inBox = boxText(lines, box).trim()
  if (inBox && inBox !== 'continue') return { ok: false, code: 'typing', pending: inBox.slice(0, 500) }
  if (!on && start.armed) {
    // Esc once cancels the wait while it's armed.
    io.keys('\x1b')
    const r = await finish()
    if (r.ok) return r
  }
  // Through its menu, which also says what's true now. First Claude Code's "continue" prefill
  // goes (Backspace, never Ctrl+C: that would cancel).
  if (inBox) {
    io.keys('\x7f'.repeat(inBox.length))
    await io.sleep(200)
  }
  const typed = await io.type('/rate-limit-options')
  if (!typed.ok) return typed.reason === 'busy_input' ? { ok: false, code: 'typing', pending: typed.text } : { ok: false, code: 'waiting' }
  for (let i = 0; i < 10 && !isLimitMenu(menu); i++) {
    await io.sleep(400)
    lines = await look()
    menu = lines ? menuOf(lines) : null
  }
  if (!isLimitMenu(menu)) return { ok: false, code: 'not_confirmed' }
  return answer(menu)
}

/**
 * Type the owner's Continue after a limit into a shared terminal. Claude Code may have put
 * "continue" in the box itself (its "Usage limit has reset · press enter to continue" after the
 * Mac slept through the reset): that isn't the owner's text, so it goes (Backspace, never Ctrl+C)
 * and the continuation is typed in its place. Any other text stays and is answered as before.
 */
export async function typeContinue(io: Pick<TermIO, 'keys' | 'sleep'> & { type(text: string): Promise<Typed> }, text: string): Promise<Typed> {
  const r = await io.type(text)
  if (r.ok || r.reason !== 'busy_input' || r.text?.trim() !== 'continue') return r
  io.keys('\x7f'.repeat(r.text.length))
  await io.sleep(200)
  return io.type(text)
}

/** Session [sid]'s resume, as the clients see it (tests). */
export function resumeFor(sid: string): Resume | null {
  const r = getSession(sid)
  const e = entries.get(sid)
  return r && e ? resumeOf(e, view(r)) : null
}

/** Forget everything (tests). */
export function resetResume(): void {
  entries.clear()
  save()
  schedule()
}

resumeWith({ blocked, replied, noticed, view: blockedView })
schedule()
