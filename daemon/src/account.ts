// The Claude account behind the built-in provider: whether Claude can reply, and why not (a usage
// limit, credit run out, signed out, the plan's end, a hold), as one Account the phone and the
// Mac show; other providers' trouble (Provider.status); signing in again from the phone (a login
// holder); and the `claude auth status` check. docs/PROTOCOL.md, "Account".
//
// An episode is one (state, window, reset): many sessions hitting the same limit make one, sent
// once. It lives in <support>/account.json, so a restart doesn't forget a limit or a sign-out.
import { execFile } from 'node:child_process'
import { join } from 'node:path'
import { SUPPORT, findClaude, log, readJson, sleep, writeJson } from './util.ts'
import { REMOVED_TEXT, getProvider, isSdk, isSubscription, listProviders, providerEnv, providerRemoved, providerStatusSource, type ProviderStatus } from './store.ts'
import { broadcast, forceUsage, listSessionRecs, reviewBlocked, sendUsage, setBlocked } from './hub.ts'
import { childEnv } from './runner.ts'
import { endLoginTerms, ensureTerm, onTermExit, openOnMac, pressKeys, startLoginTerm, stopTerm, termAlive, termScreen } from './terms.ts'
import { cleanText, type AccountReason, type AccountState, type Blocked, type Blocker, type Limit, type LimitNotice, type ProviderState } from './blockers.ts'
import { noteNoCredit } from './balance.ts'
import { MACHINE } from './platform.ts'

/** Signing in from the phone or the Mac: its holder's progress, and the sign-in page's address. */
export type Login = { state: 'starting' | 'waiting_code' | 'finishing' | 'failed'; url: string | null; where: 'phone' | 'mac'; error: string | null }
export type Account = {
  state: AccountState
  providerId: 'anthropic'
  plan: string | null
  limit: Limit | null
  reason: AccountReason | null
  text: string | null
  since: number | null
  sessionId: string | null
  checkedAt: number | null
  login: Login | null
}
export type { ProviderStatus }

type Core = Omit<Account, 'login'>
const FILE = join(SUPPORT, 'account.json')
const OK: Core = { state: 'ok', providerId: 'anthropic', plan: null, limit: null, reason: null, text: null, since: null, sessionId: null, checkedAt: null }
const STATES = new Set<AccountState>(['ok', 'limited', 'no_credit', 'logged_out', 'no_subscription', 'on_hold', 'unknown'])
/** A sign-in or plan error first read this long after it happened (a history opened late) is old news. */
const STALE_MS = 30 * 60_000
/** A limit whose reset Claude Code didn't say holds this long at most. */
const UNKNOWN_RESET_MS = 6 * 3600_000
const CHECK_GAP_MS = 10_000
const RECHECK_MS = 30 * 60_000
const LOGIN_MS = 10 * 60_000
/** Another provider's limit whose reset wasn't given is taken as over after this long. */
const PROVIDER_LIMIT_MS = 3 * 3600_000
/** Another provider that stopped answering is given another chance after this long. */
const PROVIDER_DOWN_MS = 10 * 60_000

/** An episode as it began: what [under] keeps while a sign-out is on top of it. */
type Episode = Pick<Core, 'state' | 'reason' | 'limit' | 'text' | 'since' | 'sessionId'>
/**
 * A provider's trouble; for a limit, also when it's over (its reset, else a few hours on), and for
 * one not answering, when to try it again. Only its ProviderStatus is shown. Kept from before
 * `resetsAt` and `budget` existed, they read as unknown and false.
 */
type Kept = Omit<ProviderStatus, 'resetsAt' | 'budget'> & { resetsAt?: number | null; budget?: boolean; until?: number | null }
type Saved = {
  account?: Partial<Core>
  okAt?: number
  replyAt?: number
  clearedAt?: number
  byCheck?: boolean
  under?: Episode | null
  providers?: Record<string, Kept>
}
const saved = readJson<Saved>(FILE, {})
let acc: Core = { ...OK, ...(saved.account ?? {}), providerId: 'anthropic' }
if (!STATES.has(acc.state)) acc = { ...OK }
const time = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : 0)
/** The latest proof the account worked: a reply to a request sent then, a sign-in. */
let okAt = time(saved.okAt)
/**
 * The latest reply to a request sent then: a request on the account went through, so a 5-hour or
 * weekly limit from before it is over. A sign-in is no such proof.
 */
let replyAt = time(saved.replyAt ?? saved.okAt)
/**
 * When the account last came back to ok (a reply, a sign-in, a reset): a limit or credit blocker
 * from before then no longer holds while it stays ok.
 */
let clearedAt = time(saved.clearedAt)
/** The state came from a check (`claude auth status`), so a later check may undo it too. */
let byCheck = saved.byCheck === true
/**
 * The limit the account was under when it signed out. Signing in again doesn't lift it: back
 * after the sign-in while its reset is still ahead (with its first `since`, so phones don't
 * notify again).
 */
let under: Episode | null = saved.under && typeof saved.under === 'object' && saved.under.limit && STATES.has(saved.under.state) ? saved.under : null
const providers = new Map<string, Kept>(Object.entries(saved.providers ?? {}))
let okSavedAt = 0
/**
 * The sign-in running now (or the one that failed, until another starts). [deadline]: when it's
 * given up, 10 minutes after its latest address. [typing]: a code is on its way in. [typed]: one
 * went in and Claude Code hasn't answered yet. [said]: what it printed after that code, its answer.
 * [restarts]: how many sign-ins before this one ended in a refused code.
 */
type LoginRun = {
  termId: string
  pid: number | null
  where: 'phone' | 'mac'
  startedAt: number
  deadline: number
  view: Login
  cancelled: boolean
  timedOut: boolean
  exited: boolean
  opened: boolean
  badSeen: number
  typing: boolean
  typed: boolean
  said: string | null
  restarts: number
}
let login: LoginRun | null = null

const shown = (k: Kept | undefined): ProviderStatus | null => (k ? { state: k.state, text: k.text, since: k.since, resetsAt: k.resetsAt ?? null, budget: k.budget === true } : null)
/** Other providers' trouble shows on their cards. The built-in one's is the account's. */
providerStatusSource((id) => shown(providers.get(id)))

export function getAccount(): Account {
  return { ...acc, login: login?.view ?? null }
}

/**
 * Signed out, no plan, or on hold: Claude Code's status line stops reporting limits, so the usage
 * meter keeps its last numbers, marked stale, rather than rolling into fresh empty windows.
 */
export const usageStale = () => acc.state === 'logged_out' || acc.state === 'no_subscription' || acc.state === 'on_hold'

function save(): void {
  try {
    writeJson(FILE, { account: acc, okAt, replyAt, clearedAt, byCheck, under, providers: Object.fromEntries(providers) })
    okSavedAt = Date.now()
  } catch (e) {
    log('saving the account failed', e)
  }
}

let lastSent = JSON.stringify(getAccount())
let wasStale = usageStale()
/** Tell clients what changed (once, however many sessions brought it), and keep it. */
function publish(persist = true): void {
  if (persist) save()
  const a = getAccount()
  const s = JSON.stringify(a)
  if (s !== lastSent) {
    lastSent = s
    broadcast({ t: 'account', account: a })
  }
  const stale = usageStale()
  if (stale !== wasStale) {
    wasStale = stale
    sendUsage()
  }
}

/** A limit is over: its reset has passed, or one Claude Code gave no reset for began long ago. */
const ended = (l: Limit, since: number | null, now: number) => (l.resetsAt != null ? l.resetsAt <= now : since != null && now - since > UNKNOWN_RESET_MS)

/** A 5-hour or weekly limit has used that window up: the meter says 100% until it resets. */
function fillUsage(e: Episode, observedAt: number, now = Date.now()): void {
  const l = e.limit
  if (e.state === 'limited' && l?.resetsAt && l.resetsAt > now && (l.window === 'five_hour' || l.window === 'seven_day')) forceUsage(l.window, l.resetsAt, observedAt)
}

/** A new episode. */
function begin(next: Pick<Core, 'state' | 'reason' | 'limit' | 'text' | 'sessionId'>, at: number, fromCheck = false): void {
  // Signed out in the middle of a limit: the limit outlasts the sign-out, so it waits underneath.
  if (next.state !== 'logged_out') under = null
  else if ((acc.state === 'limited' || acc.state === 'no_credit') && acc.limit && !ended(acc.limit, acc.since, Date.now())) {
    under = { state: acc.state, reason: acc.reason, limit: acc.limit, text: acc.text, since: acc.since, sessionId: acc.sessionId }
  }
  acc = { ...acc, ...next, since: at }
  byCheck = fromCheck
  log('account:', next.state, next.reason ?? next.limit?.window ?? '')
  publish()
  reviewBlocked()
}

/**
 * The episode is over: Claude can reply again. [by] says what showed it at [at]: a reply to a
 * request sent then, a sign-in (or a check that finds one), or nothing but the clock (a reset,
 * Claude Code's "Usage limit reset"). A sign-in during a limit brings the limit back.
 */
function clear(at: number, by: 'reply' | 'login' | 'reset'): void {
  if (by !== 'reset') okAt = Math.max(okAt, at)
  if (acc.state === 'ok') return
  const now = Date.now()
  const back = by === 'login' && acc.state === 'logged_out' && under?.limit && !ended(under.limit, under.since, now) ? under : null
  under = null
  byCheck = false
  if (back) {
    log('account: signed in again, still under the', back.limit?.window, 'limit')
    acc = { ...OK, ...back, plan: acc.plan, checkedAt: acc.checkedAt }
    // The meter may have started empty since (a restart while signed out).
    fillUsage(back, back.since ?? now, now)
  } else {
    log('account: ok again')
    // Never later than now: what's learnt now can't speak for blockers yet to come.
    clearedAt = Math.max(clearedAt, Math.min(at, now))
    acc = { ...OK, plan: acc.plan, checkedAt: acc.checkedAt }
  }
  publish()
  reviewBlocked()
}

const nearly = (a: number | null | undefined, b: number | null | undefined) => a == null || b == null || Math.abs(a - b) <= 120_000

/** Whether [next] continues the episode running now: the same state, window and reset. */
function sameEpisode(next: Blocker['account'] & {}): boolean {
  if (acc.state !== next.state) return false
  const a = acc.limit
  const b = next.limit
  if (!a || !b) return !a && !b
  return a.window === b.window && (a.window !== 'model' || a.label === b.label) && nearly(a.resetsAt, b.resetsAt)
}

/**
 * An API error that stopped a session ([at] when it happened, on [providerId]). On the built-in
 * provider it may begin an episode, or be one more session in the one running; on another, it's
 * that provider's status. Old news is left alone: a reset that has passed, a sign-in error from
 * before the account last worked, or one first read long after it happened (a check says).
 */
export function noteBlocker(b: Blocker, o: { at: number; sessionId: string | null; providerId: string }): void {
  const now = Date.now()
  if (!isSubscription(o.providerId)) {
    // A provider deleted since: nothing to show its trouble on (the session says it was removed).
    if (providerRemoved(o.providerId)) return
    if (b.provider && now - o.at < STALE_MS && !(b.resetsAt != null && b.resetsAt <= now)) setProviderStatus(o.providerId, b.provider, b.text, o.at, b.resetsAt, b.budget === true)
    return
  }
  const a = b.account
  if (a && o.at > okAt) {
    const limit = a.limit
    const over = limit ? ended(limit, o.at, now) : now - o.at > STALE_MS
    if (!over) {
      if (limit && acc.state === 'logged_out' && acc.since != null && o.at < acc.since) {
        // A limit from before the sign-out (a history read late): it waits underneath too.
        if (!under) {
          under = { state: a.state, reason: a.reason, limit: { ...limit }, text: b.text || null, since: o.at, sessionId: o.sessionId }
          save()
        }
      } else if (sameEpisode(a)) {
        // One more session in the same episode: only what the first didn't know (its reset), and
        // whether usage credits carry Claude on past it (or, a session stopped by it, no longer do).
        const cur = acc.limit
        if (cur && limit) {
          const resetsAt = cur.resetsAt ?? limit.resetsAt
          const usingCredits = limit.usingCredits || (cur.usingCredits && b.kind === null)
          if (resetsAt !== cur.resetsAt || usingCredits !== cur.usingCredits) {
            acc = { ...acc, limit: { ...cur, resetsAt, usingCredits } }
            publish()
          }
        }
      } else begin({ state: a.state, reason: a.reason, limit: limit ? { ...limit } : null, text: b.text || null, sessionId: o.sessionId }, o.at)
      fillUsage(acc, o.at, now)
    }
  }
  if (b.check) void requestCheck()
}

/** The family a model limit names ("Opus limit") is in this model's name: a reply on it lifts the limit. */
function sameFamily(label: string, model: string): boolean {
  const fam = /^(\w+) limit$/i.exec(label)?.[1]?.toLowerCase()
  return !fam || model.toLowerCase().includes(fam)
}

/**
 * Claude replied, on [providerId], to a request sent at [at]: whatever stopped it before then is
 * over (a model's limit only by a reply from that model). A reply to a request sent before the
 * episode began proves nothing (it was already under way).
 */
export function noteHealthy(providerId: string, at: number, model: string): void {
  if (!isSubscription(providerId)) {
    const s = providers.get(providerId)
    if (s && at > s.since) clearProviderStatus(providerId)
    return
  }
  if (at <= okAt) return
  replyAt = Math.max(replyAt, at)
  const l = acc.limit
  // On usage credits Claude replies past the limit (paid): a reply proves nothing about the limit,
  // which lasts until its reset or Claude Code says it reset.
  const paid = acc.state === 'limited' && l?.usingCredits === true
  const otherModel = l?.window === 'model' && !sameFamily(l.label, model)
  if (acc.state !== 'ok' && acc.since != null && at > acc.since && !paid && !otherModel) return clear(at, 'reply')
  okAt = at
  // Kept now and then, not on every reply.
  if (Date.now() - okSavedAt > 60_000) save()
}

/** "Login successful" (/login in a terminal) at [at]: signed in again. A check reads the plan. */
export function noteLogin(at: number): void {
  if (acc.state === 'logged_out' && acc.since != null && at > acc.since) clear(at, 'login')
  else if (at > okAt) okAt = at
  if (Date.now() - at < LOGIN_MS) void requestCheck(true)
}

/** A limit notice: Claude Code will carry on by itself at a time, won't any more, or does now. */
export function noteLimitNotice(n: LimitNotice, at: number): void {
  const l = acc.limit
  if (!l || (acc.state !== 'limited' && acc.state !== 'no_credit') || acc.since == null || at < acc.since - 60_000) return
  if (n.kind === 'reset') return clear(at, 'reset')
  const autoResumeAt = n.kind === 'armed' ? (n.autoResumeAt ?? l.resetsAt) : null
  if (autoResumeAt === l.autoResumeAt) return
  acc = { ...acc, limit: { ...l, autoResumeAt } }
  publish()
}

/** A terminal shows "Your Claude Code trial has ended.": the plan is over. */
export function noteTrialEnded(at: number, sessionId: string): void {
  if (acc.state === 'no_subscription' && acc.reason === 'trial_ended') return
  begin({ state: 'no_subscription', reason: 'trial_ended', limit: null, text: 'Your Claude Code trial has ended.', sessionId }, at)
}

/** The plan as the SDK reports it (accountInfo), when it knows. */
export function notePlan(subscriptionType: unknown): void {
  const plan = planOf(subscriptionType)
  if (!plan || plan === acc.plan) return
  acc = { ...acc, plan }
  publish()
}

function planOf(v: unknown): string | null {
  const s = typeof v === 'string' ? v.trim().toLowerCase() : ''
  return /^[a-z][a-z0-9_]{0,23}$/.test(s) ? s : null
}

/**
 * Whether a session's blocker still holds: until its reset, as long as the account (or the
 * provider) is still in that state, for a 5-hour or weekly limit not once a later request on the
 * account went through, and for a limit or credit not once the account came back to ok after it
 * (a reset, Claude Code's "Usage limit reset"). [at]: when it stopped the session.
 */
export function stillBlocks(b: Blocked, providerId: string, at: number, now = Date.now()): boolean {
  if (b.kind === 'provider') return providers.has(providerId) || providerRemoved(providerId)
  const mine = isSubscription(providerId)
  if (b.kind === 'limit' && mine && replyAt > at) return false
  if ((b.kind === 'limit' || b.kind === 'no_credit') && mine && acc.state === 'ok' && clearedAt > at) return false
  if (b.resetsAt != null) return b.resetsAt > now
  if (b.kind === 'limit' || b.kind === 'model') return now - at < UNKNOWN_RESET_MS
  if (!mine) return false
  return acc.state === b.kind && at > okAt
}

// ── Other providers ──────────────────────────────────────────────────────
/**
 * [resetsAt]: when a limit is over, if the provider said (else it's taken as over a few hours on,
 * which is never shown). [budget]: out of credit under a cap, not the balance. Out of credit, the
 * balance is read at once and then now and then, so credit coming back is noticed (balance.ts).
 */
function setProviderStatus(id: string, state: ProviderState, text: string, at: number, resetsAt: number | null = null, budget = false): void {
  const cur = providers.get(id)
  const until = state === 'limited' ? (resetsAt ?? at + PROVIDER_LIMIT_MS) : state === 'down' ? at + PROVIDER_DOWN_MS : null
  if (cur?.state === state) {
    // Still limited, later on: it lasts until the later reset (and says so, when it said).
    if (until != null && until > (cur.until ?? 0)) {
      providers.set(id, { ...cur, until, ...(state === 'limited' && resetsAt != null ? { resetsAt } : {}) })
      save()
      if (state === 'limited' && resetsAt != null) {
        broadcast({ t: 'providers', providers: listProviders() })
        reviewBlocked()
      }
    }
    return
  }
  providers.set(id, { state, text, since: at, until, resetsAt: state === 'limited' ? resetsAt : null, ...(budget ? { budget: true } : {}) })
  log('provider', id, state)
  save()
  broadcast({ t: 'providers', providers: listProviders() })
  reviewBlocked()
  if (state === 'no_credit') noteNoCredit(id, budget)
}

/** The provider's balance check says its key was refused ([at]: when it read so). */
export function providerKeyRefused(id: string, text: string, at = Date.now()): void {
  setProviderStatus(id, 'bad_key', text, at)
}

/** Its key or settings changed, or it replied: its trouble is over. */
export function clearProviderStatus(id: string): void {
  if (!providers.delete(id)) return
  save()
  broadcast({ t: 'providers', providers: listProviders() })
  reviewBlocked()
}

export const providerStatus = (id: string): ProviderStatus | null => shown(providers.get(id))

/**
 * Provider [id] was deleted. The sessions that would start on it again say so: an SDK session
 * (each turn starts Claude Code anew) and one that ended (a resume would). A terminal still open
 * keeps the provider it started with until it ends.
 */
export function noteProviderRemoved(id: string, at = Date.now()): void {
  providers.delete(id)
  for (const r of listSessionRecs()) {
    if (r.providerId !== id || r.blocked) continue
    if (isSdk(r.id) ? r.state !== 'working' : r.state === 'ended') setBlocked(r.id, { kind: 'provider', text: REMOVED_TEXT, resetsAt: null, autoResumeAt: null }, at)
  }
}

// ── The check: `claude auth status --json` ───────────────────────────────
/** Of what `claude auth status` says, the four fields read. Email and organization are never kept, logged or sent. */
export type AuthStatus = { loggedIn: boolean; authMethod: string | null; apiProvider: string | null; subscriptionType: string | null }

export function readAuthStatus(out: string): AuthStatus | null {
  try {
    const o = JSON.parse(out)
    if (!o || typeof o !== 'object' || typeof o.loggedIn !== 'boolean') return null
    const s = (v: unknown) => (typeof v === 'string' && v ? v : null)
    return { loggedIn: o.loggedIn, authMethod: s(o.authMethod), apiProvider: s(o.apiProvider), subscriptionType: s(o.subscriptionType) }
  } catch {
    return null
  }
}

function authStatus(): Promise<AuthStatus | null> {
  const bin = findClaude()
  if (!bin) return Promise.resolve(null)
  return new Promise((resolve) => {
    // Exit 1 means "not logged in", with the same JSON. Its output is never logged (it names the owner).
    execFile(bin, ['auth', 'status', '--json'], { timeout: 10_000, encoding: 'utf8', maxBuffer: 1 << 20, env: childEnv(providerEnv(getProvider(null))) }, (e, out) => {
      const r = readAuthStatus(out ?? '')
      if (!r) log('account check failed', e ? `(${(e as NodeJS.ErrnoException).code ?? 'error'})` : '(unreadable)')
      resolve(r)
    })
  })
}

/**
 * What a check says, applied. Not signed in: signed out. Signed in with Claude but no plan,
 * when something already pointed that way (a refused login, a sign-in just now): the plan has
 * ended. Signed in with a plan: a sign-out the check itself saw is over, and so is any after a
 * sign-in Claude Code reported ([afterLogin]: "Login successful"); one an API error reported
 * isn't, as the token is only read locally (the server may still refuse it, and a failed sign-in
 * leaves it there).
 */
export function applyCheck(r: AuthStatus | null, afterLogin: boolean, now = Date.now()): void {
  acc = { ...acc, checkedAt: now }
  if (!r) return publish()
  const firstParty = !r.apiProvider || r.apiProvider === 'firstParty'
  const plan = r.loggedIn && firstParty ? planOf(r.subscriptionType) : null
  if (plan) acc = { ...acc, plan }
  if (!r.loggedIn) {
    if (acc.state === 'logged_out') return publish()
    return begin({ state: 'logged_out', reason: 'not_logged_in', limit: null, text: null, sessionId: null }, now, true)
  }
  const noPlan = firstParty && r.authMethod === 'claude.ai' && !plan
  const suspect = afterLogin || acc.state === 'no_subscription' || (acc.state === 'logged_out' && acc.reason === 'rejected')
  if (noPlan && suspect) {
    if (acc.state === 'no_subscription' && acc.reason === 'ended') return publish()
    return begin({ state: 'no_subscription', reason: 'ended', limit: null, text: null, sessionId: acc.sessionId }, now, true)
  }
  if (!noPlan && (acc.state === 'logged_out' || acc.state === 'no_subscription') && (byCheck || afterLogin || acc.reason === 'not_logged_in')) return clear(now, 'login')
  publish()
}

let lastCheck = 0
let checking = false
let again = false
let afterLoginPending = false
let waiting: (() => void)[] = []

/**
 * Ask `claude auth status` now: one at a time, and at most once in 10 s (a sign-in just finished
 * always gets one of its own). It starts the whole Claude Code binary, so it runs at start, on a
 * sign-in or plan error, after a sign-in, and every 30 minutes only while the account isn't ok.
 */
export function requestCheck(afterLogin = false): Promise<Account> {
  if (afterLogin) afterLoginPending = true
  if (!afterLogin && !checking && Date.now() - lastCheck < CHECK_GAP_MS) return Promise.resolve(getAccount())
  return new Promise((resolve) => {
    waiting.push(() => resolve(getAccount()))
    if (checking) {
      if (afterLogin) again = true
      return
    }
    void runChecks()
  })
}

async function runChecks(): Promise<void> {
  checking = true
  try {
    do {
      again = false
      const after = afterLoginPending
      afterLoginPending = false
      lastCheck = Date.now()
      applyCheck(await authStatus(), after)
    } while (again)
  } catch (e) {
    log('account check failed', e)
  } finally {
    checking = false
    for (const f of waiting.splice(0)) f()
  }
}

// ── Signing in from the phone ────────────────────────────────────────────
// `claude auth login --claudeai` in a holder of its own (not a session). Its screen gives the
// sign-in page's address, which the phone opens; the code that page shows comes back through
// POST /v1/account/login/code and is typed at the screen's prompt as raw keys plus Enter (never
// the input-box path prompts take, never logged). It is a plain line prompt, not the interactive
// /login screen: a code without its "#state" gets "Invalid code" and the prompt waits for another;
// a code the server refuses ends the command ("Login failed: …", exit 1). Then the daemon starts a
// new sign-in at a new address and the phone opens that one. Any other way it ends, a check says
// how it went; only Claude Code's "Login successful." makes it a sign-in.

const fail = (status: number, code: string, message: string) => Object.assign(new Error(message), { status, code })

/** Refused codes in a row that start the sign-in over by itself; after that it fails, and the owner starts it again. */
const MAX_RESTARTS = 3

/** A new sign-in in a holder of its own: the first, or the next after a refused code ([after]). */
function launchLogin(where: 'phone' | 'mac', after: LoginRun | null): LoginRun {
  const { termId, pid } = startLoginTerm()
  const now = Date.now()
  const run: LoginRun = {
    termId,
    pid,
    where,
    startedAt: now,
    deadline: now + LOGIN_MS,
    view: { state: 'starting', url: null, where, error: after ? SIGN_IN_AGAIN : null },
    cancelled: false,
    timedOut: false,
    exited: false,
    opened: false,
    badSeen: 0,
    typing: false,
    typed: false,
    said: null,
    restarts: after ? after.restarts + 1 : 0,
  }
  login = run
  onTermExit(termId, (code, text) => void loginExited(run, code, text))
  log(after ? 'sign-in started again after a refused code, on the' : 'sign-in started on the', where)
  publish(false)
  void watchLogin(run)
  return run
}

/** Start signing in (`where`: the phone opens the page; "mac" also opens the holder in a terminal there). */
export function startLogin(where: 'phone' | 'mac'): Account {
  if (login && login.view.state !== 'failed') return getAccount()
  if (acc.state === 'ok') throw fail(409, 'not_needed', `Claude Code is already signed in on this ${MACHINE}.`)
  launchLogin(where, null)
  return getAccount()
}

// The sign-in page lives on Anthropic's own domains; any other address on that screen isn't it.
const SIGN_IN_HOST = /^https:\/\/(?:[a-z0-9-]+\.)*(?:claude\.ai|claude\.com|anthropic\.com)(?:[/?#]|$)/i

/**
 * The sign-in page's address on the login screen, and whether it ran to the end of its line, as
 * Claude Code prints it ([atLineEnd]; text after it on the same line is something else written
 * over or next to it). One longer than the terminal is wide wraps onto the next lines, each full
 * to the edge, which are joined back.
 */
export function readSignIn(screen: string, cols: number): { url: string; atLineEnd: boolean } | null {
  const lines = screen.split('\n')
  for (let i = 0; i < lines.length; i++) {
    const m = /https:\/\/\S+/.exec(lines[i]!)
    if (!m) continue
    let url = m[0]
    let j = i
    const atLineEnd = m.index + url.length === lines[i]!.length
    if (atLineEnd) {
      while (lines[j]!.length >= cols && j + 1 < lines.length && /^\S+$/.test(lines[j + 1]!)) url += lines[++j]!
    }
    if (SIGN_IN_HOST.test(url)) return { url, atLineEnd }
  }
  return null
}

/** The sign-in page's address on the login screen (see [readSignIn]). */
export const signInUrl = (screen: string, cols: number): string | null => readSignIn(screen, cols)?.url ?? null

/** A sign-in address's `state`: each sign-in has its own, and the page's code ends with it ("…#<state>"). */
export function signInState(url: string | null): string | null {
  const m = url ? /[?&]state=([^&#\s]+)/.exec(url) : null
  if (!m) return null
  try {
    return decodeURIComponent(m[1]!)
  } catch {
    return m[1]!
  }
}

/**
 * [next] is a new sign-in, not [prev] read again: both carry a `state` and neither is the start of
 * the other (one read cut short inside its state is the same sign-in).
 */
function newSignIn(prev: string | null, next: string): boolean {
  const a = signInState(prev)
  const b = signInState(next)
  return !!a && !!b && !a.startsWith(b) && !b.startsWith(a)
}

/**
 * The sign-in address to show, given the one shown ([cur]) and one just read off the screen: the
 * first whole address (it carries its `state`) stays, unless a later read carries it further or
 * is a new sign-in (another `state`). A read cut short never replaces it: once the Mac's window
 * opens the screen narrows, and a wrap that doesn't join back cleanly would cut it. A new sign-in
 * throws the shown page away, so it's taken only from an address that runs to the end of its
 * line ([atLineEnd]), as Claude Code prints it, never from one with other text after it.
 */
export function keptSignInUrl(cur: string | null, read: string | null, atLineEnd = true): string | null {
  if (!read || read === cur) return cur
  if (!cur || !signInState(cur) || read.startsWith(cur)) return read
  if (cur.startsWith(read)) return cur
  return atLineEnd && newSignIn(cur, read) ? read : cur
}

/** What the login prompt says when a pasted code didn't take and it waits for another. */
const BAD_CODE = /Invalid code|not from this sign-in/gi
const BAD_CODE_LINE = /Invalid code|not from this sign-in/i
const badCount = (text: string) => text.match(BAD_CODE)?.length ?? 0
/** What it says as the command gives up on a code the server refused, just before it exits. */
const LOGIN_FAILED = /Login failed/
const LOGIN_OK = /Login successful/

/**
 * What the login command printed after the code went in (null: nothing yet), the lines below its
 * prompt: its answer, which it prints just before it exits ("Login failed: …", "Login
 * successful.", or why the account can't sign in). Lines about a code it couldn't read aren't one.
 * [live]: a screen read while it runs, whose wrapped lines aren't joined back. In a window narrower
 * than the code (the Mac's, once it opens) the code runs onto the lines below the prompt, so a line
 * with no space in it is left out there: a code has none, and an answer is a sentence.
 */
export function afterCode(screen: string, live = false): string | null {
  const at = screen.lastIndexOf('Paste code here')
  if (at < 0) return null
  const lines = screen
    .slice(at)
    .split('\n')
    .slice(1)
    .map((l) => l.trim())
    .filter((l) => l && !BAD_CODE_LINE.test(l) && !(live && !/\s/.test(l)))
  return lines.length ? lines.join('\n') : null
}
/** The code prompt is on screen: a code typed now reaches it. */
const CODE_BOX = /Paste code here/
const CODE_REFUSED = 'That code didn’t work. Copy it again from the sign-in page.'
const SIGN_IN_AGAIN = 'That code didn’t work, so the sign-in started over. Open the sign-in page again and copy the new code.'

async function watchLogin(run: LoginRun): Promise<void> {
  while (login === run && !run.exited) {
    await sleep(400)
    if (login !== run || run.exited) return
    ensureTerm(run.termId)
    if (!termAlive(run.termId, run.pid)) return void loginExited(run, null)
    if (Date.now() > run.deadline && !run.timedOut) {
      run.timedOut = true
      stopTerm(run.termId, run.pid)
    }
    const s = await termScreen(run.termId)
    if (!s || login !== run) continue
    let changed = false
    const read = readSignIn(s.text, s.cols)
    const url = keptSignInUrl(run.view.url, read?.url ?? null, read?.atLineEnd)
    if (url && url !== run.view.url) {
      // Another sign-in in the same command (Claude Code 2.1.300 has none; a refused code ends it):
      // the page the phone opened is spent, and the 10 minutes start over.
      const fresh = newSignIn(run.view.url, url)
      if (fresh) run.deadline = Date.now() + LOGIN_MS
      run.view = { ...run.view, url, state: run.view.state === 'starting' || fresh ? 'waiting_code' : run.view.state, error: fresh ? SIGN_IN_AGAIN : run.view.error }
      changed = true
    }
    if (run.typed) run.said = afterCode(s.text, true) ?? run.said
    // A code it couldn't read ("Invalid code"): the prompt takes another.
    if (run.view.state === 'finishing' && badCount(s.text) > run.badSeen) {
      run.badSeen = badCount(s.text)
      run.typed = false
      run.view = { ...run.view, state: 'waiting_code', error: CODE_REFUSED }
      changed = true
    }
    if (changed) publish(false)
    // On the Mac: its window once the address is read (opening it resizes the screen, which would
    // wrap the address again), or after a few seconds anyway.
    if (run.where === 'mac' && !run.opened && (url || Date.now() - run.startedAt > 4000)) {
      run.opened = true
      openOnMac(run.termId).catch((e) => log('opening the sign-in on the Mac failed', e))
    }
  }
}

/** How long a code waits for the code prompt to show (Claude Code still starting the sign-in). */
const CODE_BOX_WAIT_MS = 8000

/**
 * The code from the sign-in page, typed into the running sign-in. The page's code is
 * "<code>#<state>": one that isn't, or that ends with another sign-in's state (an earlier page's),
 * is turned away here, since Claude Code would end the sign-in over it.
 */
export async function sendLoginCode(code: unknown): Promise<void> {
  const run = login
  if (!run || run.exited || run.view.state === 'failed') throw fail(409, 'no_login', 'No sign-in is running. Start it again.')
  const c = typeof code === 'string' ? code.trim() : ''
  if (!c || c.length > 2048 || /[\s\x00-\x1f\x7f]/.test(c)) throw fail(400, 'bad_code', 'That doesn’t look like a sign-in code. Copy it again.')
  const [auth, state] = c.split('#')
  if (!auth || !state) throw fail(400, 'bad_code', 'That isn’t the whole code. Copy it again from the sign-in page.')
  // The address may be cut short inside its state (a read before it was whole): its start counts.
  const fromPage = (url: string | null) => {
    const want = signInState(url)
    if (want && !state.startsWith(want)) throw fail(400, 'bad_code', 'That code is from another sign-in. Open the sign-in page again and copy the code it shows now.')
  }
  fromPage(run.view.url)
  // One code at a time: two typed at once would run together at the prompt, and Claude Code reads
  // only the first; it is checking that one now.
  if (run.typing || run.typed) throw fail(409, 'not_ready', 'A code is already on its way. Wait a moment.')
  run.typing = true
  try {
    // Typed only once the prompt shows: before that, keys go nowhere.
    let screen = await termScreen(run.termId)
    for (const t0 = Date.now(); !(screen && CODE_BOX.test(screen.text)); screen = await termScreen(run.termId)) {
      if (login !== run || run.exited) throw fail(409, 'no_login', 'No sign-in is running. Start it again.')
      if (Date.now() - t0 > CODE_BOX_WAIT_MS) throw fail(409, 'not_ready', 'The sign-in isn’t ready for a code yet. Try again in a moment.')
      await sleep(250)
    }
    if (login !== run || run.exited) throw fail(409, 'no_login', 'No sign-in is running. Start it again.')
    // Of the sign-in on this very screen, the one the code goes to (the address shown may lag a read behind).
    const read = readSignIn(screen.text, screen.cols)
    fromPage(keptSignInUrl(run.view.url, read?.url ?? null, read?.atLineEnd))
    run.badSeen = badCount(screen.text)
    if (!pressKeys(run.termId, c)) throw fail(409, 'no_login', 'The sign-in isn’t answering. Start it again.')
    await sleep(250)
    pressKeys(run.termId, '\r')
    run.typed = true
    run.said = null
    run.view = { ...run.view, state: 'finishing', error: null }
    publish(false)
  } finally {
    run.typing = false
  }
}

/** Stop signing in. */
export function cancelLogin(): void {
  const run = login
  if (!run) return
  login = null
  run.cancelled = true
  if (!run.exited) stopTerm(run.termId, run.pid)
  log('sign-in cancelled')
  publish(false)
}

/** The login holder ended ([code]: the command's exit code; [text]: its screen as it ended, each null when unknown). */
async function loginExited(run: LoginRun, code: number | null, text: string | null = null): Promise<void> {
  if (run.exited) return
  run.exited = true
  if (run.cancelled || login !== run) return
  // Its answer, below its prompt on the screen it ended with (wrapped lines joined back), else on a
  // read before: to a code the daemon typed, or to one the owner pasted himself in the Mac's window.
  if (text) run.said = afterCode(text) ?? run.said
  const said = run.said
  // Signed in only when Claude Code says so ("Login successful."). Its exit code stands in only when
  // its screen is unknown: a command a signal ended (Ctrl-C in the window, the time running out)
  // exits with 0 too.
  const signedIn = said != null ? LOGIN_OK.test(said) : text == null && code === 0 && !run.timedOut
  // How the command answered a code. "Login failed: …" (exit 1) is the server refusing it (a wrong,
  // spent or expired one), and so is a failed exit with no words to read after a code the daemon
  // typed (the holder died without saying). Anything else it said (an account on hold, an
  // organization that doesn't allow this sign-in) isn't about the code: a new sign-in would end the
  // same way, so its words are the error. In the Mac's window only "Login failed" counts: whatever
  // else is there (a ^C) is the owner's own doing, and he sees it.
  const failed = !signedIn && !run.timedOut && (code != null ? code !== 0 : said != null)
  const answered = failed && (run.typed || (said != null && LOGIN_FAILED.test(said)))
  const refused = answered && (said == null || LOGIN_FAILED.test(said))
  const turnedAway = answered && !refused ? cleanText(said, 300) || null : null
  run.view = { ...run.view, state: 'finishing', error: null }
  publish(false)
  // A refused code changed nothing on disk: no check. Otherwise one, which may end a sign-out the
  // server reported (an expired, revoked or rejected token) only after Claude Code said it signed
  // in: `claude auth status` reads the token on disk, and a failed sign-in leaves the old one there.
  if (!refused) await requestCheck(signedIn)
  if (login !== run) return
  const out = acc.state === 'logged_out' || acc.state === 'no_subscription'
  // Done once the account isn't signed out; after a code Claude Code turned away, only once it
  // works again by itself meanwhile (a /login in a terminal, a reply), never on that code's say-so.
  if (answered ? acc.state === 'ok' : !out) {
    login = null
    log('sign-in finished')
  } else if (refused && run.restarts < MAX_RESTARTS) {
    // A new sign-in at once, at a new address: the old page's codes are spent.
    try {
      launchLogin(run.where, run)
      return
    } catch (e) {
      log('starting the sign-in again failed', e)
      run.view = { ...run.view, state: 'failed', url: null, error: 'The sign-in didn’t finish. Try again.' }
    }
  } else {
    run.view = {
      ...run.view,
      state: 'failed',
      url: null,
      error: run.timedOut
        ? 'The sign-in timed out. Try again.'
        : turnedAway
          ? turnedAway
          : refused
            ? 'That code didn’t work either. Start the sign-in again.'
            : signedIn
              ? 'Signed in, but Claude still can’t use this account.'
              : 'The sign-in didn’t finish. Try again.',
    }
    log('sign-in failed', code ?? '')
  }
  publish(false)
}

// ── Over time ────────────────────────────────────────────────────────────
/**
 * A limit whose reset has passed is over (another provider's too); sessions' blockers are looked
 * at again; a check every 30 min while not ok.
 */
export function tickAccount(now = Date.now()): void {
  const l = acc.limit
  if ((acc.state === 'limited' || acc.state === 'no_credit') && l && ended(l, acc.since, now)) clear(now, 'reset')
  if (under?.limit && ended(under.limit, under.since, now)) {
    under = null
    save()
  }
  for (const [id, s] of providers) {
    // A status kept from before limits expired had no `until`: a few hours from its start.
    const until = s.until ?? (s.state === 'limited' ? s.since + PROVIDER_LIMIT_MS : s.state === 'down' ? s.since + PROVIDER_DOWN_MS : null)
    if (until != null && until <= now) clearProviderStatus(id)
  }
  reviewBlocked()
  if (acc.state !== 'ok' && acc.state !== 'limited' && now - lastCheck > RECHECK_MS) void requestCheck()
}

let started = false
/** At start: the meter as a limit kept from before left it, a check, and the clock. */
export function startAccount(): void {
  if (started) return
  started = true
  // The meter starts empty: a 5-hour or weekly limit kept in account.json fills its window again
  // (or, under a sign-out, leaves it full as the last numbers known).
  fillUsage(acc, acc.since ?? Date.now())
  if (under) fillUsage(under, under.since ?? Date.now())
  // A sign-in left running by the daemon before this one: nobody waits on its code box any more.
  endLoginTerms()
  void requestCheck()
  setInterval(tickAccount, 30_000).unref()
  tickAccount()
}
