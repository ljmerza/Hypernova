// What stops Claude from replying, read into one shape (docs/PROTOCOL.md, "Account"). Pure
// functions: the transcript reader (normalize.ts), the hooks, the SDK runner and the account
// (account.ts) all classify through here. Claude Code's texts differ between the terminal and the
// SDK and change between versions, so the kind comes from its fields (error, apiError, the HTTP
// status, rate_limit_info) as far as they go; the text is only shown, and read when it's all there
// is (a StopFailure hook carries no rate_limit_info).
import { trimText } from './util.ts'

export type AccountState = 'ok' | 'limited' | 'no_credit' | 'logged_out' | 'no_subscription' | 'on_hold' | 'unknown'
export type LimitWindow = 'five_hour' | 'seven_day' | 'model' | 'credits'
export type AccountReason = 'expired' | 'revoked' | 'not_logged_in' | 'rejected' | 'ended' | 'trial_ended' | 'downgraded' | 'org_disabled' | 'out_of_credits' | 'spend_cap' | 'api_credit'
/** `label`: Claude Code's own words ("session limit", "weekly limit", "Opus limit"). Times in epoch ms. */
export type Limit = { window: LimitWindow; label: string; resetsAt: number | null; autoResumeAt: number | null; usingCredits: boolean }
export type BlockedKind = 'limit' | 'model' | 'no_credit' | 'logged_out' | 'no_subscription' | 'on_hold' | 'provider'
/** Why a session carries on by itself after a limit, or doesn't (docs/PROTOCOL.md, "Auto-continue"). */
export type ResumeWhy = 'cancelled' | 'setting' | 'far' | 'repeated' | 'exited' | 'slept' | 'dropped' | 'no_reset' | 'back'
/**
 * Whether a blocked session carries on by itself (`armed`, at `at`), won't (`off`; `at`: when the
 * limit lifts), or could now and didn't (`ready`; `at`: when it lifted). `by`: Claude Code in its
 * terminal, or the daemon. resume.ts keeps it; Claude Code's own notices (classifyNotice) win.
 */
export type Resume = { state: 'armed' | 'off' | 'ready'; at: number | null; by: 'claude' | 'hypernova'; canCancel: boolean; canArm: boolean; why: ResumeWhy | null }
/** Why a session can't reply now (Session.blocked). `resume`: only for limits and other providers. */
export type Blocked = { kind: BlockedKind; text: string; resetsAt: number | null; autoResumeAt: number | null; resume?: Resume | null }
/** Another provider's trouble. `down`: it stopped answering (overloaded, a 5xx, no response). */
export type ProviderState = 'no_credit' | 'bad_key' | 'limited' | 'down'

/** One API error, classified. */
export type Blocker = {
  /** What it does to the session; null when it's a notice only (a transient 429, the server's trouble). */
  kind: BlockedKind | null
  tone: 'warn' | 'error'
  /** Claude Code's line, request ids removed, at most 200 characters. */
  text: string
  /** What it says about the account behind the built-in provider; null when nothing. */
  account: { state: AccountState; reason: AccountReason | null; limit: Limit | null } | null
  /** What it says about another provider (its key, its credit); null when nothing. */
  provider: ProviderState | null
  resetsAt: number | null
  /** Worth asking `claude auth status` (signed out, or the plan may have ended or changed). */
  check: boolean
  /** Another provider's `no_credit` from a cap someone set (the key's limit, a budget), not the balance. */
  budget?: boolean
}

/** An API error as Claude Code reports it: a transcript entry, the SDK's message, a StopFailure hook. */
export type ApiError = {
  /** Claude Code's kind: "rate_limit", "authentication_failed", "billing_error"… */
  error?: unknown
  /** The API's own: "usage_limit_reached", "model_requires_usage_credits"… */
  apiError?: unknown
  /** The HTTP status. */
  status?: unknown
  text?: unknown
  /** rate_limit_info: {status, resetsAt (s), rateLimitType, overageStatus, overageResetsAt, overageDisabledReason, isUsingOverage}. */
  rateLimitInfo?: unknown
  /** apiErrorIsTransient: Claude Code will retry, it isn't a quota. */
  transient?: unknown
  /** The session runs on the built-in provider (the owner's Claude login), not another one. */
  builtIn: boolean
  /** When it happened (epoch ms): "resets 2am" is read from then. */
  at: number
}

const str = (v: unknown) => (typeof v === 'string' && v ? v : null)
const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : typeof v === 'string' && /^\d+$/.test(v) ? Number(v) : null)
const obj = (v: unknown) => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null)

/**
 * Claude Code's line as the phone shows it: request ids out (they name one API call, which is
 * noise on a phone and not ours to pass on), whitespace squashed, at most [max] characters.
 * Whitespace is squashed first and every run below is bounded: an error body is the server's
 * text, of any length, and two open-ended runs side by side would take cubic time on it.
 */
export function cleanText(s: unknown, max = 200): string {
  let t = (typeof s === 'string' ? s : '').replace(/\s+/g, ' ')
  if (t.includes('req_')) {
    t = t
      .replace(/,? ?"request_id" ?: ?"req_[\w-]{0,200}"/g, '')
      .replace(/ ?[·(\[]? ?request[ _-]?id ?[:=]? ?req_[\w-]{1,200}(?: ?[)\]])?/gi, '')
      .replace(/\breq_[A-Za-z0-9_-]{6,200}/g, '')
      .replace(/ {2,}/g, ' ')
  }
  t = t.trim()
  // A separator left at the end once what followed it went ("Overloaded · request_id: …").
  while (t.endsWith('·')) t = t.slice(0, -1).trimEnd()
  return trimText(t, max) ?? ''
}

// ── Times as Claude Code writes them ─────────────────────────────────────
// "2am", "5:20pm", "Oct 17, 10am", "Oct 17, 2027, 10:30am", each maybe followed by "(Europe/Moscow)".
// Within a day it's the clock time alone; further off, the month and day too (in the zone named,
// else the Mac's own).
const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec']
const WHEN = /^(?:([A-Za-z]{3})[a-z]*\.? (\d{1,2}),? (?:(\d{4}),? )?)?(\d{1,2})(?::(\d{2}))?\s*([ap])\.?m\.?(?:\s*\(([^)]{1,60})\))?$/i

function zone(tz: string | undefined): string | undefined {
  if (!tz) return undefined
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: tz })
    return tz
  } catch {
    return undefined
  }
}

/** The date and time [t] shows on a wall clock in [tz] (the Mac's own without one). */
function wall(t: number, tz?: string): { y: number; mo: number; d: number; h: number; mi: number; s: number } {
  if (!tz) {
    const d = new Date(t)
    return { y: d.getFullYear(), mo: d.getMonth(), d: d.getDate(), h: d.getHours(), mi: d.getMinutes(), s: d.getSeconds() }
  }
  const p: Record<string, string> = {}
  for (const x of new Intl.DateTimeFormat('en-US', { timeZone: tz, hourCycle: 'h23', year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric' }).formatToParts(t)) p[x.type] = x.value
  return { y: Number(p.year), mo: Number(p.month) - 1, d: Number(p.day), h: Number(p.hour) % 24, mi: Number(p.minute), s: Number(p.second) }
}

/** The moment a wall clock in [tz] shows this date and time (days past the month's end roll over). */
function moment(y: number, mo: number, d: number, h: number, mi: number, tz?: string): number {
  if (!tz) return new Date(y, mo, d, h, mi).getTime()
  const want = Date.UTC(y, mo, d, h, mi)
  const offset = (t: number) => {
    const w = wall(t, tz)
    return Date.UTC(w.y, w.mo, w.d, w.h, w.mi, w.s) - Math.floor(t / 1000) * 1000
  }
  // Twice, so a guess on the other side of a clock change settles.
  const first = want - offset(want)
  return want - offset(first)
}

/** "2am (Europe/Moscow)" as the next such moment after [now] (epoch ms); null when it isn't a time. */
export function parseWhen(s: string, now: number): number | null {
  const m = WHEN.exec(s.trim())
  if (!m) return null
  const h = (Number(m[4]) % 12) + (m[6]!.toLowerCase() === 'p' ? 12 : 0)
  const mi = m[5] ? Number(m[5]) : 0
  if (Number(m[4]) > 12 || mi > 59) return null
  const tz = zone(m[7])
  const today = wall(now, tz)
  if (m[1]) {
    const mo = MONTHS.indexOf(m[1].toLowerCase())
    if (mo < 0) return null
    const day = Number(m[2])
    let t = moment(m[3] ? Number(m[3]) : today.y, mo, day, h, mi, tz)
    // "Jan 3" read on Dec 30 is next year's.
    if (!m[3] && t < now - 2 * 86400_000) t = moment(today.y + 1, mo, day, h, mi, tz)
    return t
  }
  let t = moment(today.y, today.mo, today.d, h, mi, tz)
  if (t <= now - 60_000) t = moment(today.y, today.mo, today.d + 1, h, mi, tz)
  return t
}

// ── API errors ───────────────────────────────────────────────────────────
// The openings Claude Code gives a quota message (its own list), and its words for each window.
const QUOTA_TEXT = /^(?:You['’]ve (?:hit|reached) your |You['’]re out of (?:usage credits|extra usage)|Your org is out of usage|Your seat type doesn['’]t include|Your usage allocation has been disabled|Your group['’]s usage limit is set to \$0|Claude AI usage limit reached)/i
const CREDITS_ONLY = /^(?:Fable|Haiku|Mythos|Opus|Sonnet)(?: [^·\n]{1,40})? requires usage credits\./i
const TYPE_LABEL: Record<string, string> = {
  five_hour: 'session limit',
  seven_day: 'weekly limit',
  seven_day_opus: 'Opus limit',
  seven_day_sonnet: 'Sonnet limit',
  seven_day_overage_included: 'Fable limit',
  overage: 'usage credit limit',
}
// Spend caps (a monthly limit someone set), as rate_limit_info names them.
const SPEND_CAPS = new Set(['org_level_disabled_until', 'org_spend_cap_reached'])

/** What a quota message calls the limit: "session limit" in "You've hit your session limit · resets 2am". */
function quotaLabel(t: string): string | null {
  const m = /^You['’]ve (?:hit|reached) your (.{1,60}?)(?: · |$)/.exec(t)
  return m ? m[1]!.trim() : null
}

/** "resets 2am (Europe/Moscow)" in a quota message, read from [at]. */
function resetsFromText(t: string, at: number): number | null {
  const m = /· (?:[Yy]our [^·]{1,60} )?resets ([^·]{1,60}?)\s*(?:·|$)/.exec(t)
  return m ? parseWhen(m[1]!, at) : null
}

function windowOf(type: string | null, label: string | null): LimitWindow {
  if (type === 'five_hour' || type === 'seven_day') return type
  if (type === 'overage') return 'credits'
  if (type?.startsWith('seven_day_')) return 'model'
  const l = (label ?? '').toLowerCase()
  if (l === 'weekly limit') return 'seven_day'
  if (/^(?:fable|haiku|mythos|opus|sonnet) limit$/.test(l)) return 'model'
  if (l.includes('credit') || l.includes('spend') || l.includes('budget') || l.includes('monthly')) return 'credits'
  return 'five_hour'
}

const notice = (text: string, tone: 'warn' | 'error'): Blocker => ({ kind: null, tone, text, account: null, provider: null, resetsAt: null, check: false })

// ── Other providers ──────────────────────────────────────────────────────
// They have no 5-hour or weekly windows: only credit, rate limits, keys, and being down. Claude
// Code passes their error body through in its line ("API Error: 429 {…}"), so what they said is
// read from there: OpenRouter's `limit_source`, a reset in X-RateLimit-Reset, a retry-after.

/** A reset worth believing: after the error, and within a week of it. */
const sane = (t: number | null, at: number) => (t != null && t > at && t < at + 7 * 86400_000 ? t : null)

/**
 * When another provider's limit lifts, as its error says: rate_limit_info's `resetsAt` (s), an
 * `X-RateLimit-Reset` in the body (OpenRouter puts its headers there, epoch ms or s), a retry-after
 * or "try again in N seconds|minutes" (from [at]), the window its limit names (OpenRouter's
 * "free-models-per-min" lifts within a minute, "free-models-per-day" at midnight UTC), else null:
 * nobody said, so nobody guesses. Claude Code keeps only the error's message, so the window is
 * often all there is.
 */
export function providerReset(raw: string, info: Record<string, unknown> | null, at: number): number | null {
  const s = num(info?.resetsAt)
  if (s) return sane(s * 1000, at - 60_000)
  const x = /X-RateLimit-Reset\\?"?\s{0,3}[:=]\s{0,3}\\?"?(\d{9,13})/i.exec(raw)
  if (x) {
    const v = Number(x[1])
    const t = sane(v >= 1e12 ? v : v * 1000, at - 60_000)
    if (t) return t
  }
  const ra = /retry[-_ ]after\\?"?\s{0,3}[:=]?\s{0,3}\\?"?(\d{1,6})\b/i.exec(raw)
  if (ra) return sane(at + Number(ra[1]) * 1000, at)
  const again = /try again in (\d{1,5}) ?(seconds?|secs?|s|minutes?|mins?|m|hours?|h)\b/i.exec(raw)
  if (again) {
    const u = again[2]!.toLowerCase()
    const k = u.startsWith('h') ? 3600_000 : u.startsWith('m') ? 60_000 : 1000
    return sane(at + Number(again[1]) * k, at)
  }
  if (/\b(?:requests?|tokens?|models?)[- ]per[- ]min(?:ute)?\b/i.test(raw)) return at + 60_000
  if (/\bfree-models-per-day\b/i.test(raw)) {
    const d = new Date(at)
    return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() + 1)
  }
  return resetsFromText(raw, at)
}

/** A cap someone set ran out, not the balance: OpenRouter's key limit, a guardrail's or a workspace's budget. */
const BUDGET_TEXT = /\bkey limit exceeded\b|\b(?:guardrail|workspace|key) budget\b|\bbudget (?:limit )?(?:exceeded|reached)\b|\bspend(?:ing)? limit (?:exceeded|reached)\b/i

const provider = (kind: ProviderState, tone: 'warn' | 'error', text: string, resetsAt: number | null = null, budget = false): Blocker => ({
  kind: 'provider', tone, text, account: null, provider: kind, resetsAt, check: false, ...(budget ? { budget: true } : {}),
})

/**
 * Another provider's error (docs/PROTOCOL.md, "Account", the other providers' table): out of credit
 * (its balance, or a cap someone set), rate limited (until when, if it said), its key refused, or
 * not answering. Null for anything else (a notice only: a model name, an answer too long).
 */
function providerError(raw: string, text: string, err: string | null, status: number | null, info: Record<string, unknown> | null, at: number): Blocker | null {
  // OpenRouter names what ran out. An in-flight budget is a moment's wait; a key's or an admin's
  // budget is a cap (402 or 403, never a bad key); its credits are the account's balance.
  // Claude Code keeps only the error's message ("API Error: 403 Key limit exceeded"), so its words
  // are read too when the body's `limit_source` isn't there.
  const source = /limit_source\\?"?\s{0,3}:\s{0,3}\\?"(openrouter_[a-z_]{1,40})/.exec(raw)?.[1] ?? null
  if (source === 'openrouter_in_flight_budget' || (!source && /\bin-flight budget\b/i.test(raw)))
    return provider('limited', 'warn', text, providerReset(raw, info, at) ?? at + 60_000)
  if (source === 'openrouter_key_limit' || source === 'openrouter_guardrail_budget' || source === 'openrouter_workspace_budget' || (!source && BUDGET_TEXT.test(raw)))
    return provider('no_credit', 'error', text, null, true)
  if (source === 'openrouter_credits') return provider('no_credit', 'error', text)
  if (status === 402 || err === 'billing_error' || /insufficient credits|credit balance|more credits|out of credits/i.test(raw)) return provider('no_credit', 'error', text)
  if (status === 429 || err === 'rate_limit' || /\bAPI Error:? 429\b/.test(raw)) return provider('limited', 'warn', text, providerReset(raw, info, at))
  if (
    status === 401 || status === 403 || err === 'authentication_failed' || err === 'oauth_org_not_allowed' ||
    /\b(?:authentication_error|permission_error)\b|^(?:Invalid API key|Failed to authenticate|Please run \/login)\b|\bAPI Error:? 40[13]\b/i.test(raw)
  )
    return provider('bad_key', 'error', text)
  if (err === 'overloaded' || err === 'server_error' || (status != null && status >= 500) || /\boverloaded\b|\bAPI Error:? 5\d\d\b|Connection error|Request timed out|ECONNREFUSED|ECONNRESET|socket hang up|fetch failed/i.test(raw))
    return provider('down', 'warn', text)
  return null
}

// ── Continuing after a limit ─────────────────────────────────────────────
/**
 * What carries a session on once its limit has passed, typed as its next prompt. Claude Code's own
 * (it sends it hidden at its subscription's reset); the daemon's, at another provider's reset; and
 * the one Continue sends when the owner taps it on a session that waited. Each reads as a slim row
 * in the chat (`auto` on its user event), never as a message from the owner.
 */
export const CONTINUE_TEXT = {
  claude: 'Your claude.ai usage limit has reset. Continue the task you were working on when the limit was reached; do not repeat work that is already complete.',
  provider: 'The rate limit has passed. Continue the task you were working on when it was reached; do not repeat work that is already complete.',
  manual: 'You can carry on now. Continue the task you were working on when you were stopped; do not repeat work that is already complete.',
} as const

/** A prompt that is one of [CONTINUE_TEXT]'s: "limit" when it carried on by itself, "manual" when the owner tapped Continue. */
export function autoContinueOf(text: string): 'limit' | 'manual' | null {
  const t = text.trim()
  if (t === CONTINUE_TEXT.claude || t === CONTINUE_TEXT.provider) return 'limit'
  if (t === CONTINUE_TEXT.manual) return 'manual'
  return null
}

/**
 * One API error, classified (docs/PROTOCOL.md, "Account", has the table): a usage limit, credit
 * run out, signed out, the plan's end, a hold, another provider's key or credit, or none of these
 * (a notice only). A provider other than the built-in one never changes the account: its trouble
 * is its own (Provider.status).
 */
export function classifyApiError(e: ApiError): Blocker {
  const raw = typeof e.text === 'string' ? e.text.trim() : ''
  const err = str(e.error)
  const api = str(e.apiError)
  const status = num(e.status)
  const info = obj(e.rateLimitInfo)
  const legacy = /^Claude AI usage limit reached\|(\d{9,})/.exec(raw)
  const text = cleanText(legacy ? 'Claude AI usage limit reached' : raw) || cleanText(err ? `API Error: ${err}${status ? ` (${status})` : ''}` : 'API Error')

  // Another provider's trouble is its own, and comes in its own words.
  if (!e.builtIn) {
    // The SDK's error has no status of its own: Claude Code's line names it ("API Error: 402 …").
    const p = providerError(raw, text, err, status ?? (Number(/^API Error:? (\d{3})\b/.exec(raw)?.[1]) || null), info, e.at)
    if (p) return p
  }

  // Usage limits and credit.
  const quota = api === 'usage_limit_reached' || info?.status === 'rejected' || QUOTA_TEXT.test(raw)
  if (err === 'rate_limit' || status === 429 || quota || CREDITS_ONLY.test(raw)) {
    if (api === 'model_requires_usage_credits' || api === 'long_context_credits_required' || CREDITS_ONLY.test(raw))
      return { kind: 'model', tone: 'warn', text, account: null, provider: null, resetsAt: null, check: false }
    if (e.transient === true || /not your usage limit|Request rejected \(429\)/i.test(raw)) return notice(text, 'warn')
    // Another provider's limit: its card says so until its reset, when one is given.
    if (!e.builtIn) {
      const s = num(info?.resetsAt)
      return { kind: 'provider', tone: 'warn', text, account: null, provider: 'limited', resetsAt: s ? s * 1000 : resetsFromText(raw, e.at), check: false }
    }
    // A 429 that names no quota: the server limiting requests for a moment, which Claude Code retries.
    if (!quota) return notice(text, 'warn')
    const type = str(info?.rateLimitType)
    const reason = str(info?.overageDisabledReason)
    const outOfCredits = reason === 'out_of_credits' || /^You['’]re out of (?:usage credits|extra usage)|^Your org is out of usage/i.test(raw)
    const label = quotaLabel(raw) ?? (outOfCredits ? 'usage credits' : type ? (TYPE_LABEL[type] ?? null) : null)
    const spendCap = !outOfCredits && ((reason !== null && SPEND_CAPS.has(reason)) || /spend limit|shared budget|usage limit is set to \$0|monthly usage limit/i.test(label ?? raw))
    const window = outOfCredits || spendCap ? 'credits' : windowOf(type, label)
    const seconds = window === 'credits' ? (num(info?.overageResetsAt) ?? num(info?.resetsAt)) : num(info?.resetsAt)
    const resetsAt = legacy ? Number(legacy[1]) * 1000 : seconds ? seconds * 1000 : resetsFromText(raw, e.at)
    const noCredit = outOfCredits || spendCap
    // Past the limit on usage credits Claude carries on (paid), as long as the credits still take
    // it. Credits that ran out stop it after all: the session is blocked, and the run no failure.
    const overage = str(info?.overageStatus)
    const usingCredits = !noCredit && info?.isUsingOverage === true && (overage === null || overage === 'allowed' || overage === 'allowed_warning')
    const limit: Limit = { window, label: label ?? TYPE_LABEL[window] ?? 'usage limit', resetsAt, autoResumeAt: null, usingCredits }
    return {
      kind: noCredit ? 'no_credit' : usingCredits ? null : window === 'model' ? 'model' : 'limit',
      tone: 'warn',
      text,
      account: { state: noCredit ? 'no_credit' : 'limited', reason: outOfCredits ? 'out_of_credits' : spendCap ? 'spend_cap' : null, limit },
      provider: null,
      resetsAt,
      check: false,
    }
  }

  // Signed out, or the login was refused (the plan may have ended: only a check can tell).
  if (err === 'authentication_failed' || /^(?:Not logged in|Login expired|OAuth token (?:has been )?revoked|Invalid API key|Please run \/login|Failed to authenticate)\b/i.test(raw)) {
    if (!e.builtIn) return { kind: 'provider', tone: 'error', text, account: null, provider: 'bad_key', resetsAt: null, check: false }
    const reason: AccountReason = /login expired|session expired|expired and could not be refreshed|token (?:has )?expired/i.test(raw)
      ? 'expired'
      : /revoked/i.test(raw)
        ? 'revoked'
        : /not logged in|authentication required/i.test(raw)
          ? 'not_logged_in'
          : 'rejected'
    return { kind: 'logged_out', tone: 'error', text, account: { state: 'logged_out', reason, limit: null }, provider: null, resetsAt: null, check: true }
  }
  if (err === 'oauth_org_not_allowed') {
    if (!e.builtIn) return { kind: 'provider', tone: 'error', text, account: null, provider: 'bad_key', resetsAt: null, check: false }
    return { kind: 'no_subscription', tone: 'error', text, account: { state: 'no_subscription', reason: 'org_disabled', limit: null }, provider: null, resetsAt: null, check: true }
  }
  if (err === 'account_on_hold' || /^Your account is on hold\b/i.test(raw)) {
    return { kind: 'on_hold', tone: 'error', text, account: { state: 'on_hold', reason: null, limit: null }, provider: null, resetsAt: null, check: false }
  }
  // The plan changed under the session's model: other models still work, and a check reads the plan.
  if (/^Claude Opus is not available with the Claude Pro plan/i.test(raw)) {
    return { kind: 'model', tone: 'warn', text, account: null, provider: null, resetsAt: null, check: e.builtIn }
  }
  // Credit: an API-key account's balance, or another provider's (OpenRouter's 402).
  const credit = /credit balance|insufficient credits|more credits/i.test(raw)
  if (err === 'billing_error' || (!e.builtIn && (status === 402 || credit)) || (e.builtIn && credit && err !== 'server_error')) {
    if (!e.builtIn) return { kind: 'provider', tone: 'error', text, account: null, provider: 'no_credit', resetsAt: null, check: false }
    return { kind: 'no_credit', tone: 'error', text, account: { state: 'no_credit', reason: 'api_credit', limit: null }, provider: null, resetsAt: null, check: false }
  }
  // The server's trouble, the network, a model name, an answer too long: not the account's.
  return notice(text, err === 'overloaded' ? 'warn' : 'error')
}

/** A terminal dialog's title that means the plan is over ("Your Claude Code trial has ended."). */
export const trialEnded = (title: string | null | undefined) => !!title && /^Your Claude Code trial has ended\b/i.test(title.trim())

// ── Limit notices ────────────────────────────────────────────────────────
/**
 * Claude Code's lines about a usage limit (notices, and warnings when it gives up on carrying on):
 * it will carry on by itself (and when), the owner stopped that, it won't (`why`: the setting is
 * off, the reset is over 24 hours out, it hit the limit again and again, it exited), it could have
 * and didn't (`slept`: the Mac slept through the reset; `dropped`: the continuation was blocked),
 * or the limit has reset and it carries on now. null for any other line. `text` drops the tails
 * only a terminal can use ("esc to cancel", "/rate-limit-options to re-arm").
 */
export type LimitNotice =
  | { kind: 'armed'; autoResumeAt: number | null; text: string }
  | { kind: 'cancelled'; text: string }
  | { kind: 'off'; why: 'setting' | 'far' | 'repeated' | 'exited'; text: string }
  | { kind: 'ready'; why: 'slept' | 'dropped'; text: string }
  | { kind: 'reset'; text: string }

/** What a terminal-only tail leaves of Claude Code's line, for the chat. */
export function limitNoticeText(s: string): string {
  return s
    .trim()
    .replace(/ · esc to cancel$/, '')
    .replace(/ · \/rate-limit-options to (?:re-arm|try again)$/, '')
    .replace(/ \(\/rate-limit-options to [^)]{1,60}\)$/, '')
    .replace(/;? \/rate-limit-options can arm it again\.?$/, '')
    .replace(/ · (?:press enter|send a prompt) to continue$/, '')
}

export function classifyNotice(s: string, at: number): LimitNotice | null {
  const t = s.trim()
  const text = limitNoticeText(t)
  if (/^Usage limit (?:reset|available again)\b/.test(t)) return { kind: 'reset', text }
  const armed = /^(?:Usage limit reached(?: again)? · )?[Cc]ontinuing automatically(?: at)? (.{1,60}?)(?: · esc to cancel)?$/.exec(t)
  if (armed) return { kind: 'armed', autoResumeAt: parseWhen(armed[1]!, at), text }
  // The answer to "Wait here, then continue automatically at 2am" in /rate-limit-options.
  const waits = /^Claude Code will continue automatically(?: at)? (.{1,60}?)\.(?:\s|$)/.exec(t)
  if (waits) return { kind: 'armed', autoResumeAt: parseWhen(waits[1]!, at), text: t.replace(/\. Keep this session open[^]*$/, '.') }
  if (/^Automatic continue cancelled · Claude Code exited\b/.test(t)) return { kind: 'off', why: 'exited', text }
  if (/^Automatic continue cancelled\b/.test(t)) return { kind: 'cancelled', text }
  if (/^Automatic continue was turned off\b/.test(t)) return { kind: 'off', why: 'setting', text }
  if (/^Automatic continue stopped after repeated\b/.test(t)) return { kind: 'off', why: 'repeated', text }
  if (/^Automatic continue stopped\b/.test(t)) return { kind: 'off', why: 'far', text }
  if (/^Usage limit has reset\b/.test(t)) return { kind: 'ready', why: 'slept', text }
  if (/^Automatic continue did not run\b/.test(t)) return { kind: 'ready', why: 'dropped', text }
  return null
}
