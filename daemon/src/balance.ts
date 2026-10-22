// What's left to spend on another provider, where its API says (docs/PROTOCOL.md, "Providers",
// Provider.balance). Only OpenRouter has such an API that its inference key may read:
// GET {baseUrl}/v1/key answers the key's cap or budget, what it spent today and its free-model
// requests. The account's own balance needs a management key (`/credits` answers 403 to an
// inference key), so it is never claimed.
//
// The key goes only to the provider's own address (the one Claude Code already sends it to), never
// through a redirect. Neither the key, the answer's body nor its label is logged or kept: a
// balance is numbers and when they were read, in memory only.
import { log } from './util.ts'
import { findProvider, listProviders, providerBalanceSource, providerUrl, type ProviderBalance, type ProviderRecord } from './store.ts'
import { broadcast, listSessions } from './hub.ts'
import { clearProviderStatus, providerKeyRefused, providerStatus } from './account.ts'

/** At most this often per provider, whatever asks (a turn starting, the no-credit watch). */
const MIN_GAP_MS = 60_000
/** At most this often when someone asks for it (Settings opening, the Check button). */
const ASKED_GAP_MS = 10_000
const TIMEOUT_MS = 8000
/** A turn starting on the provider reads it again once the last read is this old. */
const TURN_GAP_MS = 5 * 60_000
/** Out of credit: read every 2 minutes for half an hour, then every 15 minutes, for a day at most. */
const WATCH_FAST_MS = 2 * 60_000
const WATCH_FAST_FOR_MS = 30 * 60_000
const WATCH_SLOW_MS = 15 * 60_000
const WATCH_FOR_MS = 24 * 3600_000
/** Credit is back once this much is left (a cent: OpenRouter's own rounding leaves crumbs). */
const BACK_USD = 0.01

const balances = new Map<string, ProviderBalance>()
const lastTry = new Map<string, number>()
const inFlight = new Map<string, Promise<ProviderBalance | null>>()
providerBalanceSource((id) => balances.get(id) ?? null)

/** The provider has a balance to read: OpenRouter, with a key and an address. */
export const hasBalance = (p: ProviderRecord | null | undefined): p is ProviderRecord & { apiKey: string; baseUrl: string } =>
  !!p && p.kind === 'openrouter' && !!p.apiKey && !!p.baseUrl

/** Where the key's balance is: `/v1/key` under the provider's base address (see providerUrl). */
export const keyUrl = (baseUrl: string) => providerUrl(baseUrl, 'key')

const usd = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? Math.round(v * 10000) / 10000 : null)
const count = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) && v >= 0 ? Math.floor(v) : null)
const obj = (v: unknown) => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null)

/**
 * OpenRouter's `/v1/key` answer, as numbers: what's left under the key's budget (its effective
 * budget, else its limit; null when it has no cap), what it spent today (UTC), and its free-model
 * requests left today. Nothing else is read. Null when it isn't that answer.
 */
export function parseKey(body: unknown, now = Date.now()): ProviderBalance | null {
  const d = obj(obj(body)?.data)
  if (!d) return null
  const budget = obj(d.effective_budget)
  const free = obj(d.free_model_daily_requests)
  return {
    remaining: usd(budget?.remaining_usd) ?? usd(d.limit_remaining),
    usedToday: usd(d.usage_daily),
    freeLeft: count(free?.remaining),
    freeLimit: count(free?.limit),
    checkedAt: now,
    error: null,
  }
}

type Fetch = (url: URL, init: RequestInit) => Promise<Response>
let fetcher: Fetch = (url, init) => fetch(url, init)
/** Tests read balances from a stand-in instead of the network. */
export function useFetch(f: Fetch): void {
  fetcher = f
}

function publish(): void {
  broadcast({ t: 'providers', providers: listProviders() })
}

/**
 * Read provider [id]'s balance now (OpenRouter only; null for any other), unless it was read less
 * than a minute ago ([asked]: someone asked, then 10 s), when the last reading stands. A refused key
 * says so on the provider; credit back under a no-credit status ends it.
 */
export function checkBalance(id: string, asked = false, now = Date.now()): Promise<ProviderBalance | null> {
  const p = findProvider(id)
  if (!hasBalance(p)) return Promise.resolve(null)
  const running = inFlight.get(id)
  if (running) return running
  if (now - (lastTry.get(id) ?? 0) < (asked ? ASKED_GAP_MS : MIN_GAP_MS)) return Promise.resolve(balances.get(id) ?? null)
  lastTry.set(id, now)
  const run = read(p, now).finally(() => inFlight.delete(id))
  inFlight.set(id, run)
  return run
}

async function read(p: ProviderRecord & { apiKey: string; baseUrl: string }, started: number): Promise<ProviderBalance | null> {
  const url = keyUrl(p.baseUrl)
  const prev = balances.get(p.id) ?? null
  const failed = (error: 'bad_key' | 'unreachable'): ProviderBalance => ({
    ...(prev ?? { remaining: null, usedToday: null, freeLeft: null, freeLimit: null }),
    checkedAt: prev?.checkedAt ?? Date.now(),
    error,
  })
  let next: ProviderBalance
  if (!url) next = failed('unreachable')
  else {
    try {
      // No redirects: a key follows nobody to another address.
      const res = await fetcher(url, {
        method: 'GET',
        redirect: 'error',
        headers: { authorization: `Bearer ${p.apiKey}`, accept: 'application/json' },
        signal: AbortSignal.timeout(TIMEOUT_MS),
      })
      if (res.status === 401) {
        next = failed('bad_key')
        const name = p.name || 'The provider'
        providerKeyRefused(p.id, `${name} refused the key`, Date.now())
      } else if (!res.ok) {
        log('balance check: provider', p.id, 'answered', res.status)
        next = failed('unreachable')
      } else {
        next = parseKey(await res.json().catch(() => null)) ?? failed('unreachable')
      }
    } catch (e) {
      log('balance check: provider', p.id, 'unreachable', (e as Error)?.name ?? '')
      next = failed('unreachable')
    }
  }
  // Deleted meanwhile: nothing to show it on.
  if (!findProvider(p.id)) return null
  const changed = JSON.stringify({ ...prev, checkedAt: 0 }) !== JSON.stringify({ ...next, checkedAt: 0 })
  balances.set(p.id, next)
  // The key works, so whatever refused it before is over. Room under the key's cap after it ran
  // out under that cap (`budget`) means it's back. The account's own credit can't be read with
  // this key (what's left under its cap says nothing about it), so that one holds until a reply,
  // an edit or Try again.
  const st = providerStatus(p.id)
  if (next.error === null && st?.state === 'bad_key' && st.since < started) clearProviderStatus(p.id)
  else if (next.error === null && st?.state === 'no_credit' && st.budget && st.since < started && next.remaining != null && next.remaining > BACK_USD) {
    log('provider', p.id, 'has credit again')
    clearProviderStatus(p.id)
  } else if (changed) publish()
  return next
}

// ── When it's read ───────────────────────────────────────────────────────
const watches = new Map<string, { start: number; timer: NodeJS.Timeout | null }>()

/**
 * Provider [id] just ran out of credit: read its balance at once (so the phone shows "$0.00 left").
 * Out under its key's cap ([budget]), read again every 2 minutes for half an hour and every 15
 * after, until there's room again or a day passes (a raised cap is seen there; the account's own
 * credit isn't, so that one isn't watched).
 */
export function noteNoCredit(id: string, budget = false): void {
  if (!hasBalance(findProvider(id))) return
  // Running out is news, not a poll (once per episode): read now, whenever it was read last.
  lastTry.delete(id)
  void checkBalance(id, true)
  if (!budget || watches.has(id)) return
  const w = { start: Date.now(), timer: null as NodeJS.Timeout | null }
  watches.set(id, w)
  const next = () => {
    const age = Date.now() - w.start
    if (providerStatus(id)?.state !== 'no_credit' || age > WATCH_FOR_MS || !findProvider(id)) {
      watches.delete(id)
      return
    }
    w.timer = setTimeout(
      () => {
        void checkBalance(id).finally(next)
      },
      age < WATCH_FAST_FOR_MS ? WATCH_FAST_MS : WATCH_SLOW_MS,
    )
    w.timer.unref()
  }
  next()
}

/**
 * A provider was saved: a new key is read at once ("Key works · $4.20 left"), and the answer waits
 * for it (8 s at most) so the editor can say so; one deleted is forgotten.
 */
export async function providerSaved(id: string, newKey: boolean): Promise<void> {
  if (!findProvider(id)) {
    balances.delete(id)
    lastTry.delete(id)
    return
  }
  if (newKey) {
    lastTry.delete(id)
    await checkBalance(id, true)
  }
}

/** Sessions working on an OpenRouter provider read its balance again when the last read is 5 minutes old. */
function sweep(now = Date.now()): void {
  const working = new Set<string>()
  for (const s of listSessions()) if (s.state === 'working') working.add(s.providerId)
  for (const id of working) {
    const p = findProvider(id)
    if (!hasBalance(p)) continue
    if (now - (balances.get(id)?.checkedAt ?? 0) >= TURN_GAP_MS) void checkBalance(id, false, now)
  }
}
setInterval(sweep, 60_000).unref()

// A balance lives in memory only: at start, each one is read once (and one out of credit is
// watched again), a few seconds in, so the first phone to connect sees it.
setTimeout(() => {
  for (const p of listProviders()) {
    if (!hasBalance(findProvider(p.id))) continue
    const st = providerStatus(p.id)
    if (st?.state === 'no_credit') noteNoCredit(p.id, st.budget)
    else void checkBalance(p.id)
  }
}, 5000).unref()
