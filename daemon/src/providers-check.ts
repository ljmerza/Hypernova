// Checking a provider's address and key before a session needs them (docs/PROTOCOL.md, "Providers",
// ProviderCheck): the Mac's and the phone's editors say "Key works", "refused this key" or "nothing
// answers there" at once, instead of the first session failing.
//
// A custom provider is asked `GET {baseUrl}/v1/models` (free, sends nothing to a model) with the key
// both ways Anthropic-compatible gateways take it (`x-api-key` and `Authorization: Bearer`).
// OpenRouter is asked `GET {baseUrl}/v1/key`, the balance read balance.ts makes. The key goes only
// to the address being checked, never through a redirect; neither the key nor the answer's body is
// logged or kept.
import { log } from './util.ts'
import { parseKey } from './balance.ts'
import type { ProviderBalance, ProviderKind } from './store.ts'

/**
 * `ok`: the key works (and [models], a custom provider's, when it lists them; [balance], OpenRouter's).
 * `bad_key`: refused (401/403). `no_credit` (402), `limited` (429). `not_found`: something answers
 * there, but not as an Anthropic-compatible API (404). `unreachable`: nothing answers, or not in time.
 */
export type CheckState = 'ok' | 'bad_key' | 'no_credit' | 'limited' | 'not_found' | 'unreachable'
export type ProviderCheck = { state: CheckState; text: string; models: string[] | null; balance: ProviderBalance | null; checkedAt: number }

const TIMEOUT_MS = 8000
const MODELS_MAX = 50

type Fetch = (url: URL, init: RequestInit) => Promise<Response>
let fetcher: Fetch = (url, init) => fetch(url, init)
/** Tests answer from a stand-in instead of the network. */
export function useCheckFetch(f: Fetch): void {
  fetcher = f
}

/** `{base}{path}` for an address cleanBaseUrl already accepted. */
function under(base: string, path: string): URL | null {
  try {
    const u = new URL(base)
    u.pathname = `${u.pathname.replace(/\/+$/, '')}${path}`
    u.search = ''
    u.hash = ''
    return u
  } catch {
    return null
  }
}

/** The model ids an Anthropic- or OpenAI-shaped `/v1/models` answer lists (`{data: [{id}]}`), at most 50. */
export function modelIds(body: unknown): string[] {
  const data = body && typeof body === 'object' ? (body as { data?: unknown }).data : null
  if (!Array.isArray(data)) return []
  const out: string[] = []
  for (const d of data) {
    const id = d && typeof d === 'object' ? (d as { id?: unknown }).id : null
    if (typeof id === 'string' && id.trim() && id.length <= 120 && !/\s/.test(id) && !out.includes(id)) out.push(id)
    if (out.length >= MODELS_MAX) break
  }
  return out
}

const words = (state: CheckState, name: string, n: number | null, left: number | null): string => {
  switch (state) {
    case 'ok':
      return left != null ? `Key works. $${left.toFixed(2)} left.` : n ? `Key works. ${n} model${n === 1 ? '' : 's'}.` : 'Key works.'
    case 'bad_key':
      return `${name} refused this key.`
    case 'no_credit':
      return `${name} is out of credits.`
    case 'limited':
      return `${name} is rate limiting this key. Try again in a minute.`
    case 'not_found':
      return 'Something answers there, but not as an Anthropic-compatible API. Check the address.'
    case 'unreachable':
      return 'Nothing answers at this address.'
  }
}

/** Ask the provider at [baseUrl] (already cleaned) whether [apiKey] works. Never throws. */
export async function checkProvider(o: { kind: ProviderKind; name?: string | null; baseUrl: string; apiKey: string | null }, now = Date.now()): Promise<ProviderCheck> {
  const name = o.name?.trim() || (o.kind === 'openrouter' ? 'OpenRouter' : 'The provider')
  const done = (state: CheckState, extra: { models?: string[] | null; balance?: ProviderBalance | null } = {}): ProviderCheck => ({
    state,
    text: words(state, name, extra.models?.length ?? null, extra.balance?.remaining ?? null),
    models: extra.models ?? null,
    balance: extra.balance ?? null,
    checkedAt: now,
  })
  const openrouter = o.kind === 'openrouter'
  if (openrouter && !o.apiKey) return done('bad_key')
  const url = under(o.baseUrl, openrouter ? '/v1/key' : '/v1/models')
  if (!url) return done('unreachable')
  const headers: Record<string, string> = { accept: 'application/json' }
  if (o.apiKey) {
    headers.authorization = `Bearer ${o.apiKey}`
    if (!openrouter) {
      headers['x-api-key'] = o.apiKey
      headers['anthropic-version'] = '2023-06-01'
    }
  }
  try {
    const res = await fetcher(url, { method: 'GET', redirect: 'error', headers, signal: AbortSignal.timeout(TIMEOUT_MS) })
    if (res.status === 401 || res.status === 403) return done('bad_key')
    if (res.status === 402) return done('no_credit')
    if (res.status === 429) return done('limited')
    if (res.status === 404 || res.status === 405) return done('not_found')
    if (!res.ok) {
      log('provider check: answered', res.status)
      return done('unreachable')
    }
    const body = await res.json().catch(() => null)
    if (openrouter) {
      const balance = parseKey(body, now)
      return balance ? done('ok', { balance }) : done('not_found')
    }
    return done('ok', { models: modelIds(body) })
  } catch (e) {
    log('provider check: unreachable', (e as Error)?.name ?? '')
    return done('unreachable')
  }
}
