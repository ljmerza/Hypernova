// The models each provider offers, for the phone's new-session sheet (docs/PROTOCOL.md, "Models").
//
// - Claude (the subscription): Claude Code's own list, the one its /model picker shows for this
//   account's plan (aliases such as `opus` and `fable` first, then earlier versions by their ids),
//   read from an SDK process's `initialize` answer the way the "/" list is (commands.ts): no prompt,
//   hooks off, nothing reaches a model. Until it has answered, a built-in list of the current ones.
// - OpenRouter: its public catalogue, `GET {baseUrl}/v1/models`, at the provider's own address and
//   never through a redirect, without the key (the catalogue needs none). Only models that can call
//   tools are offered: Claude Code can't work without them.
// - Every provider other than the subscription: the models its settings name come first (the
//   `default`, `opus`, `sonnet` and `haiku` slots, docs/PROTOCOL.md, "Providers").
//
// Lists live in memory only: Claude's for an hour, OpenRouter's for six (a failed read is tried
// again after five minutes, the last good list kept meanwhile).
import { HOME, log } from './util.ts'
import { listProviders, providerUrl } from './store.ts'
import { modelInfo } from './session-usage.ts'
import { onClaudeInit, refreshCommands } from './commands.ts'

/**
 * One model to start a session on. `value` is what `POST /v1/sessions` takes as `model` (null: the
 * provider's default, no `--model`); `model` the id it runs when known ("claude-opus-5-5",
 * "openai/gpt-5"); `context` its context window in tokens; `price` dollars per million tokens in
 * and out (OpenRouter's catalogue; null for the subscription and when unknown); `older` an earlier
 * version, listed after the current ones; `slot` which of the provider's settings names it.
 */
export type ModelOption = {
  value: string | null
  name: string
  model: string | null
  description: string | null
  context: number | null
  price: { input: number; output: number } | null
  older: boolean
  slot: 'default' | 'opus' | 'sonnet' | 'haiku' | null
}

/**
 * A provider's models. `source`: `claude` (Claude Code's own list), `builtin` (the list this daemon
 * knows, while Claude Code hasn't answered), `openrouter` (its catalogue), `settings` (only what the
 * provider's settings name). `error`: the last read failed (`unreachable`), so this is older or partial.
 */
export type ModelList = {
  providerId: string
  source: 'claude' | 'builtin' | 'openrouter' | 'settings'
  models: ModelOption[]
  checkedAt: number | null
  error: null | 'unreachable'
}

const CLAUDE_FRESH_MS = 60 * 60_000
const CLAUDE_WAIT_MS = 15_000
const LIST_FRESH_MS = 6 * 3600_000
const LIST_RETRY_MS = 5 * 60_000
const TIMEOUT_MS = 8000
/** A catalogue longer than this is cut (OpenRouter has a few hundred). */
const MAX_LISTED = 800

const option = (o: Partial<ModelOption> & { name: string }): ModelOption => ({
  value: null,
  model: null,
  description: null,
  context: null,
  price: null,
  older: false,
  slot: null,
  ...o,
})

/** The current Claude models, for as long as Claude Code hasn't said which this plan has. */
export const CLAUDE_BUILTIN: ModelOption[] = [
  option({ name: 'Default', model: 'claude-opus-5-5', description: 'Claude Code’s own choice', context: 1_000_000 }),
  option({ value: 'opus', name: 'Opus 5.5', model: 'claude-opus-5-5', description: 'For complex work and everyday tasks', context: 1_000_000 }),
  option({ value: 'fable', name: 'Fable 5.1', model: 'claude-fable-5-1', description: 'For your toughest challenges', context: 1_000_000 }),
  option({ value: 'sonnet', name: 'Sonnet 5.5', model: 'claude-sonnet-5-5', description: 'Most efficient for simpler tasks', context: 1_000_000 }),
  option({ value: 'haiku', name: 'Haiku 5.5', model: 'claude-haiku-5-5', description: 'Fastest for quick answers', context: 1_000_000 }),
]

const obj = (v: unknown) => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null)
const str = (v: unknown) => (typeof v === 'string' && v.trim() ? v.trim() : null)

/**
 * A description without the name Claude Code puts in front of the default's ("Opus 5.5 · Best for
 * everyday, complex tasks" → "Best for everyday, complex tasks"): the phone shows the model apart.
 */
export function plainDescription(d: unknown): string | null {
  const s = str(d)
  if (!s) return null
  const parts = s.split(/\s+·\s+/)
  return (parts[parts.length - 1] ?? s).trim() || null
}

/**
 * Claude Code's models (`initialize`'s `models`, as its /model picker lists them) as options:
 * `default` is the provider's default (no `--model`), an id rather than an alias an earlier version.
 */
export function fromClaude(list: unknown): ModelOption[] {
  if (!Array.isArray(list)) return []
  const out: ModelOption[] = []
  const seen = new Set<string>()
  for (const raw of list) {
    const m = obj(raw)
    const v = str(m?.value)
    if (!m || !v || v.length > 120) continue
    const isDefault = v === 'default'
    const value = isDefault ? null : v
    const key = value ?? '\u0000default'
    if (seen.has(key)) continue
    seen.add(key)
    const model = str(m.resolvedModel) ?? value
    const info = modelInfo(model)
    out.push(
      option({
        value,
        name: isDefault ? 'Default' : (str(m.displayName) ?? info?.name ?? v),
        model,
        description: plainDescription(m.description),
        context: info?.window ?? null,
        older: value != null && /^claude-/i.test(value),
      }),
    )
  }
  return out
}

/** "OpenAI: GPT-5" → "GPT-5": the vendor is already the id's prefix. */
function plainName(name: string | null, id: string): string {
  const n = name?.replace(/^[^:]{1,40}:\s+/, '').trim()
  return n || id.slice(id.indexOf('/') + 1) || id
}

/** OpenRouter quotes dollars per token as strings; per million tokens here. Negative ("varies"): unknown. */
function perMillion(v: unknown): number | null {
  const n = typeof v === 'string' ? Number(v) : typeof v === 'number' ? v : NaN
  if (!Number.isFinite(n) || n < 0) return null
  return Math.round(n * 1_000_000 * 10_000) / 10_000
}

/**
 * OpenRouter's catalogue (`{data: [{id, name, created, context_length, pricing, supported_parameters}]}`)
 * as options, newest first (then by id): only models that can call tools (when the entry says what
 * it supports), not the batch-priced copies (`:batch`, nothing for a session someone waits on), at
 * most [max]. Nothing but those fields is read.
 */
export function parseCatalogue(body: unknown, max = MAX_LISTED): ModelOption[] {
  const data = obj(body)?.data
  if (!Array.isArray(data)) return []
  const out: { o: ModelOption; created: number }[] = []
  const seen = new Set<string>()
  for (const raw of data) {
    const d = obj(raw)
    const id = str(d?.id)
    if (!d || !id || id.length > 120 || /\s/.test(id) || /:batch$/i.test(id) || seen.has(id)) continue
    const params = d.supported_parameters
    if (Array.isArray(params) && !params.includes('tools')) continue
    seen.add(id)
    const pricing = obj(d.pricing)
    const input = perMillion(pricing?.prompt)
    const output = perMillion(pricing?.completion)
    const ctx = typeof d.context_length === 'number' && Number.isFinite(d.context_length) && d.context_length > 0 ? Math.floor(d.context_length) : null
    const created = typeof d.created === 'number' && Number.isFinite(d.created) ? d.created : 0
    out.push({ o: option({ value: id, name: plainName(str(d.name), id), model: id, context: ctx, price: input != null && output != null ? { input, output } : null }), created })
  }
  out.sort((a, b) => b.created - a.created || (a.o.value! < b.o.value! ? -1 : a.o.value! > b.o.value! ? 1 : 0))
  return out.slice(0, max).map((x) => x.o)
}

type ProviderLike = { id: string; kind: string; baseUrl: string | null; models: { default?: string; opus?: string; sonnet?: string; haiku?: string } }

/**
 * The models a provider's settings name, first in its list: its default (always offered; Claude
 * Code's own default when the settings name none), then the opus, sonnet and haiku slots. [known]
 * fills in what its catalogue says of the same ids.
 */
export function settingsModels(p: ProviderLike, known: ModelOption[] = []): ModelOption[] {
  const byId = new Map(known.map((o) => [o.model, o]))
  const out: ModelOption[] = []
  for (const slot of ['default', 'opus', 'sonnet', 'haiku'] as const) {
    const id = p.models[slot]?.trim() || null
    if (!id && slot !== 'default') continue
    const k = id ? byId.get(id) : undefined
    out.push(
      option({
        value: slot === 'default' ? null : slot,
        name: id ? (k?.name ?? plainName(null, id)) : 'Default',
        model: id,
        context: k?.context ?? null,
        price: k?.price ?? null,
        slot,
      }),
    )
  }
  return out
}

/** The provider's own models, then the catalogue's others (one already named by the settings isn't listed twice). */
export function mergeLists(own: ModelOption[], catalogue: ModelOption[]): ModelOption[] {
  const named = new Set(own.map((o) => o.model).filter((m): m is string => !!m))
  return [...own, ...catalogue.filter((o) => !o.model || !named.has(o.model))]
}

/** Where a provider's catalogue is: `/v1/models` under its base address (see providerUrl). */
export const catalogueUrl = (baseUrl: string) => providerUrl(baseUrl, 'models')

// ─── Claude Code's list ───

let claude: { at: number; models: ModelOption[] } | null = null
/** Any SDK process Claude Code answers (the "/" list's too) brings its model list along. */
onClaudeInit((init) => noteClaudeModels(init?.models))

/** Keeps Claude Code's list, when it is one. */
export function noteClaudeModels(list: unknown, now = Date.now()): void {
  const models = fromClaude(list)
  if (models.length) claude = { at: now, models }
}

type Probe = () => Promise<unknown>
let probe: Probe = () => refreshCommands(HOME)
/** Tests ask a stand-in instead of starting Claude Code. */
export function useClaudeProbe(p: Probe): void {
  probe = p
}
let probing: Promise<unknown> | null = null

async function claudeList(providerId: string, now = Date.now()): Promise<ModelList> {
  if (!claude || now - claude.at > CLAUDE_FRESH_MS) {
    const run = probing ?? (probing = probe().catch((e) => log('model list: Claude Code didn’t answer', (e as Error)?.message ?? '')).finally(() => (probing = null)))
    // A list from before stays good while a fresher one is fetched; with none, wait (a while).
    if (!claude) await Promise.race([run, new Promise((r) => setTimeout(r, CLAUDE_WAIT_MS).unref?.())])
  }
  if (claude) return { providerId, source: 'claude', models: claude.models, checkedAt: claude.at, error: null }
  return { providerId, source: 'builtin', models: CLAUDE_BUILTIN, checkedAt: null, error: 'unreachable' }
}

// ─── Catalogues ───

type Fetch = (url: URL, init: RequestInit) => Promise<Response>
let fetcher: Fetch = (url, init) => fetch(url, init)
/** Tests read catalogues from a stand-in instead of the network. */
export function useFetch(f: Fetch): void {
  fetcher = f
}

const catalogues = new Map<string, { at: number; tried: number; models: ModelOption[]; error: null | 'unreachable' }>()
const reading = new Map<string, Promise<void>>()

async function readCatalogue(key: string, url: URL, now: number): Promise<void> {
  const prev = catalogues.get(key)
  try {
    const res = await fetcher(url, { method: 'GET', redirect: 'error', headers: { accept: 'application/json' }, signal: AbortSignal.timeout(TIMEOUT_MS) })
    const models = res.ok ? parseCatalogue(await res.json().catch(() => null)) : []
    if (!res.ok) log('model list: catalogue answered', res.status)
    if (models.length) catalogues.set(key, { at: now, tried: now, models, error: null })
    else catalogues.set(key, { at: prev?.at ?? 0, tried: now, models: prev?.models ?? [], error: 'unreachable' })
  } catch (e) {
    log('model list: catalogue unreachable', (e as Error)?.name ?? '')
    catalogues.set(key, { at: prev?.at ?? 0, tried: now, models: prev?.models ?? [], error: 'unreachable' })
  }
}

async function catalogueList(p: ProviderLike, now = Date.now()): Promise<ModelList> {
  const url = p.baseUrl ? catalogueUrl(p.baseUrl) : null
  if (!url) return { providerId: p.id, source: 'settings', models: settingsModels(p), checkedAt: null, error: 'unreachable' }
  const key = url.href
  const have = catalogues.get(key)
  const due = !have || (have.error ? now - have.tried > LIST_RETRY_MS : now - have.at > LIST_FRESH_MS)
  if (due) {
    const run = reading.get(key) ?? readCatalogue(key, url, now).finally(() => reading.delete(key))
    reading.set(key, run)
    await run
  }
  const c = catalogues.get(key)
  const known = c?.models ?? []
  return { providerId: p.id, source: 'openrouter', models: mergeLists(settingsModels(p, known), known), checkedAt: c?.at || null, error: c?.error ?? null }
}

/** Forget every list read so far (tests; a provider edited to another address is a new key anyway). */
export function forgetModelLists(): void {
  claude = null
  catalogues.clear()
}

/** The names a model goes by in a list: what `--model` takes and the id it runs, lower case. */
const names = (models: ModelOption[]) => [...new Set(models.flatMap((o) => [o.value, o.model]).filter((v): v is string => !!v).map((v) => v.toLowerCase()))]

/**
 * A provider's models as already read (no reading now), for checking an automation's model: Claude
 * Code's own list for the subscription, the catalogue for OpenRouter. Null when there's no list to
 * go by: not read yet, or a custom provider (whose settings name what its gateway takes).
 */
export function knownModels(providerId: string): string[] | null {
  const p = listProviders().find((x) => x.id === providerId)
  if (!p) return null
  if (p.builtIn) return claude ? names(claude.models) : null
  if (p.kind !== 'openrouter' || !p.baseUrl) return null
  const url = catalogueUrl(p.baseUrl)
  const c = url ? catalogues.get(url.href) : undefined
  return c?.models.length ? names(mergeLists(settingsModels(p, c.models), c.models)) : null
}

/** [knownModels], reading the list first when it's due (an automation being saved). */
export async function modelsNow(providerId: string): Promise<string[] | null> {
  await listModels(providerId).catch(() => null)
  return knownModels(providerId)
}

/** Every provider's models ([only]: just that provider's), in the providers' order. */
export async function listModels(only?: string | null): Promise<ModelList[]> {
  const ps = listProviders().filter((p) => !only || p.id === only)
  return Promise.all(
    ps.map((p) => {
      if (p.builtIn) return claudeList(p.id)
      if (p.kind === 'openrouter') return catalogueList(p)
      return Promise.resolve<ModelList>({ providerId: p.id, source: 'settings', models: settingsModels(p), checkedAt: null, error: null })
    }),
  )
}
