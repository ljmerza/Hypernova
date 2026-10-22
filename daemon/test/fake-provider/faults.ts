// What the fake provider answers when it's told to misbehave (docs/specs/spec-providers.md, §5):
// each fault is one real provider's error, as OpenRouter and Anthropic send it (status, headers,
// body), so Claude Code reacts the way it would to the real thing and the daemon reads the same
// lines from its transcript. Nothing here talks to a real provider.

export type FaultKind =
  | 'credits'
  | 'budget'
  | 'in_flight'
  | 'rate'
  | 'rate_short'
  | 'rate_bare'
  | 'key'
  | 'forbidden'
  | 'overloaded'
  | 'slow'
  | 'cut'
  | 'error_event'
  | 'limit_sub'

export const FAULT_KINDS: readonly FaultKind[] = ['credits', 'budget', 'in_flight', 'rate', 'rate_short', 'rate_bare', 'key', 'forbidden', 'overloaded', 'slow', 'cut', 'error_event', 'limit_sub']

/**
 * A fault the fake plays back. `sid`: only requests whose `x-claude-code-session-id` starts with it
 * (none: every request). `times`: how many requests it answers (none: until cleared). `until`:
 * epoch seconds it lasts until (for `rate` and `limit_sub`, also the reset it announces).
 * `retryAfter` (s), `ms` (slow), `bytes` (cut): the fault's own knobs.
 */
export type Fault = {
  id: string
  kind: FaultKind
  sid: string | null
  times: number | null
  until: number | null
  retryAfter: number | null
  ms: number | null
  bytes: number | null
  /** rate: the window its message names, OpenRouter's per-minute (default) or per-day free-model limit. */
  window: 'min' | 'day'
  used: number
  createdAt: number
}

/** A fault's answer when it's a plain error (the rest stream something first, or wait). */
export type ErrorAnswer = { status: number; headers: Record<string, string>; body: string }

const json = (o: unknown) => JSON.stringify(o)
const anthropicError = (type: string, message: string) => json({ type: 'error', error: { type, message } })
const openRouterError = (code: number, message: string, metadata: Record<string, unknown>) => json({ error: { code, message, metadata } })

/** The plain error a fault answers with, or null for one that streams or waits (slow, cut, error_event). */
export function errorAnswer(f: Fault, now = Date.now()): ErrorAnswer | null {
  const ct = { 'content-type': 'application/json' }
  // A reset ahead: the fault's own `until`, else an hour on.
  const resetMs = (f.until ?? Math.floor(now / 1000) + 3600) * 1000
  switch (f.kind) {
    case 'credits':
      return { status: 402, headers: ct, body: openRouterError(402, 'Insufficient credits. Add more using https://openrouter.ai/settings/credits', { limit_source: 'openrouter_credits' }) }
    case 'budget':
      return { status: 403, headers: ct, body: openRouterError(403, 'Key limit exceeded', { limit_source: 'openrouter_key_limit' }) }
    case 'in_flight':
      return {
        status: 402,
        headers: { ...ct, 'retry-after': String(f.retryAfter ?? 20) },
        body: openRouterError(402, 'Request would exceed your in-flight budget. Retry after the requests in flight finish.', { limit_source: 'openrouter_in_flight_budget' }),
      }
    case 'rate':
      // OpenRouter puts its rate-limit headers into the error's metadata too, which is how they
      // reach Claude Code's error line (and so the transcript).
      return {
        status: 429,
        headers: { ...ct, 'retry-after': String(f.retryAfter ?? 3600), 'x-ratelimit-limit': '20', 'x-ratelimit-remaining': '0', 'x-ratelimit-reset': String(resetMs) },
        body: openRouterError(429, `Rate limit exceeded: free-models-per-${f.window === 'day' ? 'day' : 'min'}.`, { headers: { 'X-RateLimit-Limit': '20', 'X-RateLimit-Remaining': '0', 'X-RateLimit-Reset': String(resetMs) } }),
      }
    case 'rate_short':
      return { status: 429, headers: { ...ct, 'retry-after': String(f.retryAfter ?? 3) }, body: anthropicError('rate_limit_error', 'Rate limited. Please try again shortly.') }
    case 'rate_bare':
      return { status: 429, headers: { 'content-type': 'text/plain' }, body: 'Too Many Requests' }
    case 'key':
      return { status: 401, headers: ct, body: anthropicError('authentication_error', 'invalid x-api-key') }
    case 'forbidden':
      return { status: 403, headers: ct, body: anthropicError('permission_error', 'Your API key does not have permission to use the specified resource.') }
    case 'overloaded':
      return { status: 529, headers: ct, body: anthropicError('overloaded_error', 'Overloaded') }
    case 'limit_sub':
      // The subscription's own limit, as Anthropic answers it (the unified headers fill Claude
      // Code's rate_limit_info).
      return {
        status: 429,
        headers: {
          ...ct,
          'anthropic-ratelimit-unified-status': 'rejected',
          'anthropic-ratelimit-unified-reset': String(Math.floor(resetMs / 1000)),
          'anthropic-ratelimit-unified-representative-claim': 'five_hour',
          'anthropic-ratelimit-unified-5h-status': 'rejected',
          'anthropic-ratelimit-unified-5h-reset': String(Math.floor(resetMs / 1000)),
          'anthropic-ratelimit-unified-5h-utilization': '1.0',
        },
        body: anthropicError('rate_limit_error', "This request would exceed your account's rate limit. Please try again later."),
      }
    default:
      return null
  }
}

/** Whether fault [f] answers a request from session [sid] now. */
export function matches(f: Fault, sid: string | null, now = Date.now()): boolean {
  if (f.times != null && f.used >= f.times) return false
  if (f.until != null && now >= f.until * 1000) return false
  if (f.sid && !(sid ?? '').startsWith(f.sid)) return false
  return true
}

/** A fault from what the control API was sent; null when it isn't one. */
export function parseFault(o: Record<string, unknown>, id: string, now = Date.now()): Fault | null {
  const kind = String(o.kind ?? '') as FaultKind
  if (!FAULT_KINDS.includes(kind)) return null
  const n = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) && v >= 0 ? v : null)
  // `for` (seconds from now) is the friendlier way to say `until`.
  const until = n(o.until) ?? (n(o.for) != null ? Math.floor(now / 1000) + n(o.for)! : null)
  return {
    id,
    kind,
    sid: typeof o.sid === 'string' && o.sid ? o.sid : null,
    times: n(o.times),
    until,
    retryAfter: n(o.retryAfter),
    ms: n(o.ms),
    bytes: n(o.bytes),
    window: o.window === 'day' ? 'day' : 'min',
    used: 0,
    createdAt: now,
  }
}
