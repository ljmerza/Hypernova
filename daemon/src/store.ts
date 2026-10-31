// Persistent state in <support>: identity, admin token, paired devices, providers, settings.
import { join } from 'node:path'
import { existsSync, readFileSync } from 'node:fs'
import { HOME, SUPPORT, randomToken, readJson, sha256, writeJson, writeText, safeEqual } from './util.ts'

/**
 * `client`: which app paired (none for the Android app), so the Mac can name it. `certFingerprint`:
 * its active device certificate's (lan.ts, tls.ts), once it asked for one; the fingerprint alone,
 * never a key of the phone's. `certExpiresAt`: when that certificate runs out (epoch ms).
 * `installId`: the app install that paired (it sends a stable id of its own), so pairing it again
 * replaces it rather than add a twin.
 */
export type Device = { id: string; name: string; model: string; createdAt: number; lastSeenAt: number; tokenHash: string; appVersion?: string; client?: string; certFingerprint?: string; certExpiresAt?: number; installId?: string }
export type ProviderKind = 'anthropic' | 'openrouter' | 'custom'
/**
 * `paid`: running on it costs money (automations ask before they do); unset counts as yes, and only
 * the owner says no (a model on this computer, say). `removed`: only on the stand-in [getProvider]
 * gives for an id that no longer exists, never kept.
 */
export type ProviderRecord = {
  id: string
  name: string
  kind: ProviderKind
  baseUrl: string | null
  apiKey: string | null
  models: { default?: string; opus?: string; sonnet?: string; haiku?: string }
  builtIn: boolean
  paid?: boolean
  removed?: boolean
}
export type Settings = {
  projectsRoot: string
  terminalApp?: string | null
  /** The LAN listener (lan.ts): the phone reaches the daemon directly on the local network. Unset: on. */
  lanAccess?: boolean
  /**
   * Sessions live in Claude Code's own background daemon (`claude agents`): the phone types into
   * one through `claude attach` in a shared terminal (the same conversation, never a fork), and a
   * new session from the phone starts with `claude --bg`, so it shows in `claude agents`. Unset: off.
   */
  claudeAgents?: boolean
  /** Settings → Browse outside projects (files.ts): sessions' folders in the home folder open to the phone, read-only. Unset: off. */
  browseOutsideProjects?: boolean
  /**
   * Hosts an automation's webhook output may reach on a private address, as written in its URL
   * ("ntfy.home.lan", "192.168.1.20", "ntfy.tail1234.ts.net"): a self-hosted ntfy on the local
   * network or the tailnet. Set by hand in settings.json (the daemon reads it at start). Unset: none.
   */
  webhookPrivateHosts?: string[]
  /** Codex as a second agent (codex/client.ts): Setup turns it on, or the first Codex session from the phone. Unset: off. */
  codex?: boolean
  /**
   * The app's GitHub releases (releases.ts): the daemon looks for a newer one at start and every 6
   * hours and offers its APK to the phones. `POST /local/settings` or settings.json. Unset: on.
   */
  releaseCheck?: boolean
}
/**
 * A session started from Hypernova. `engine` "term" runs in a shared terminal; "sdk" (or none, from
 * 0.2.0) through the Agent SDK. `forkFrom`: an SDK session whose first prompt forks this conversation
 * (it continues one open in a terminal on another provider). `providerAt`, `providerFrom`: when it
 * moved to its provider and from which one, so its transcript's API errors from before then are
 * read as the other provider's (and as old news).
 */
export type ManagedSession = {
  id: string
  cwd: string
  providerId: string
  model: string | null
  permissionMode: string
  createdAt: number
  title?: string
  engine?: 'sdk' | 'term'
  forkFrom?: string
  providerAt?: number
  providerFrom?: string
  /** An automation's run started it (automations.ts): its badge, which survives restarts and the automation's deletion. */
  automation?: { id: string; name: string; runId: string }
  /**
   * An automation's SDK session: its allow rules and its appended system prompt, sent with every
   * query. A shared terminal's run keeps its rules (`--allowedTools`) here too, for the rules in
   * effect (permission-rules.ts) only.
   */
  allowedTools?: string[]
  appendSystemPrompt?: string
  /**
   * What a switch from another agent carried over (switch.ts): the conversation so far, given to
   * Claude Code with --append-system-prompt each time it starts or resumes, never as a prompt.
   */
  context?: string
  /** Claude Code's reasoning effort it was started with (`--effort`) or last set to from the phone (`/effort`); none: Claude Code's default. */
  effort?: string | null
}

/** Claude Code's reasoning efforts, lowest first (`claude --help` of 2.1.314: `--effort <level>`; `/effort <level>`). */
export const CLAUDE_EFFORTS = ['low', 'medium', 'high', 'xhigh', 'max'] as const
export const isClaudeEffort = (v: unknown): v is string => typeof v === 'string' && (CLAUDE_EFFORTS as readonly string[]).includes(v)

const P = {
  identity: join(SUPPORT, 'daemon.json'),
  admin: join(SUPPORT, 'admin-token'),
  devices: join(SUPPORT, 'devices.json'),
  providers: join(SUPPORT, 'providers.json'),
  settings: join(SUPPORT, 'settings.json'),
  managed: join(SUPPORT, 'sessions.json'),
  hookSecret: join(SUPPORT, 'hook-secret'),
}

function loadOrCreateSecret(path: string): string {
  if (existsSync(path)) {
    const v = readFileSync(path, 'utf8').trim()
    if (v) return v
  }
  const v = randomToken(32)
  writeText(path, v + '\n')
  return v
}

export const identity = readJson<{ id: string }>(P.identity, { id: '' })
if (!identity.id) {
  identity.id = randomToken(12)
  writeJson(P.identity, identity)
}
export const adminToken = loadOrCreateSecret(P.admin)
export const hookSecret = loadOrCreateSecret(P.hookSecret)

// Devices
let devices = readJson<Device[]>(P.devices, [])
export const listDevices = () => devices.map(({ tokenHash: _h, ...d }) => d)
export function addDevice(d: Omit<Device, 'tokenHash'>, token: string): Device {
  const rec = { ...d, tokenHash: sha256(token) }
  devices = devices.filter((x) => x.id !== d.id).concat(rec)
  writeJson(P.devices, devices)
  return rec
}
export function deviceForToken(token: string): Device | null {
  const h = sha256(token)
  const d = devices.find((x) => safeEqual(x.tokenHash, h)) ?? null
  if (d && Date.now() - d.lastSeenAt > 60_000) {
    d.lastSeenAt = Date.now()
    writeJson(P.devices, devices)
  }
  return d
}
export const getDevice = (id: string) => devices.find((x) => x.id === id) ?? null
/** The device whose active certificate has this fingerprint (lowercase hex SHA-256 of its DER), if it still exists. */
export const deviceForCert = (fingerprint: string) => devices.find((x) => x.certFingerprint === fingerprint) ?? null
/** A new device certificate replaces the old one: only the newest fingerprint counts from now on. */
export function setDeviceCert(id: string, fingerprint: string, expiresAt: number): boolean {
  const d = devices.find((x) => x.id === id)
  if (!d) return false
  d.certFingerprint = fingerprint
  d.certExpiresAt = expiresAt
  writeJson(P.devices, devices)
  return true
}
/** The device holds a certificate that hasn't run out (one recorded without its expiry counts as none). */
export const hasDeviceCert = (d: Device | null) => !!(d?.certFingerprint && (d.certExpiresAt ?? 0) > Date.now())
export function removeDevice(id: string): boolean {
  const before = devices.length
  devices = devices.filter((x) => x.id !== id)
  writeJson(P.devices, devices)
  return devices.length !== before
}

// Providers
const BUILT_IN: ProviderRecord = { id: 'anthropic', name: 'Claude (your subscription)', kind: 'anthropic', baseUrl: null, apiKey: null, models: {}, builtIn: true }
let providers = readJson<ProviderRecord[]>(P.providers, [])
if (!providers.some((p) => p.id === 'anthropic')) providers.unshift(BUILT_IN)
/** No id, or the built-in one's: the subscription. */
const subscriptionId = (id: string | undefined | null) => id == null || id === '' || id === 'anthropic'
/** What a session on a provider that was deleted is told. */
export const REMOVED_TEXT = 'Its provider was removed. Move this session to another provider.'
/**
 * A provider by its id: the subscription for none. An id that no longer exists (the provider was
 * deleted) is never the subscription: a stand-in, `removed`, with no address and no key, so a
 * session on it neither runs on the owner's Claude login nor files its errors as the account's.
 */
export function getProvider(id: string | undefined | null): ProviderRecord {
  if (subscriptionId(id)) return providers.find((p) => p.id === 'anthropic') ?? BUILT_IN
  return providers.find((p) => p.id === id) ?? { id: id!, name: 'Removed provider', kind: 'custom', baseUrl: null, apiKey: null, models: {}, builtIn: false, removed: true }
}
/**
 * `/v1/<path>` under a provider's base address. Only http(s), and never an address that carries
 * credentials of its own.
 */
export function providerUrl(baseUrl: string, path: 'key' | 'models'): URL | null {
  try {
    const u = new URL(baseUrl)
    if ((u.protocol !== 'https:' && u.protocol !== 'http:') || u.username || u.password) return null
    u.pathname = `${u.pathname.replace(/\/+$/, '')}/v1/${path}`
    u.search = ''
    u.hash = ''
    return u
  } catch {
    return null
  }
}
/** A configured provider by its id, without the built-in stand-in [getProvider] gives for an unknown one. */
export const findProvider = (id: string) => providers.find((p) => p.id === id) ?? null
/** The session runs on the owner's Claude subscription (not another provider, and not one deleted since). */
export const isSubscription = (id: string | undefined | null) => getProvider(id).kind === 'anthropic'
/** The id names a provider that was deleted (or never existed). */
export const providerRemoved = (id: string | undefined | null) => !subscriptionId(id) && !findProvider(id!)
/** An error a route answers as it is: its HTTP status and code (http.ts). */
export class ProviderError extends Error {
  status: number
  code: string
  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}
/** The provider a session starts or resumes on; one deleted is refused (404 `no_provider`), never the subscription instead. */
export function useProvider(id: string | undefined | null): ProviderRecord {
  const p = getProvider(id)
  if (p.removed) throw new ProviderError(404, 'no_provider', 'That provider was removed. Pick another one.')
  return p
}
/**
 * A provider's trouble (its key refused, its credit gone, limited, not answering), since when
 * (epoch ms). `resetsAt`: when a limit lifts, only when the provider said so. `budget`: out of
 * credit under a cap someone set (the key's limit, a budget), not the account's balance.
 */
export type ProviderStatus = { state: 'no_credit' | 'bad_key' | 'limited' | 'down'; text: string; since: number; resetsAt: number | null; budget: boolean }
/**
 * What's left to spend on a provider, as far as its API tells (balance.ts; OpenRouter only):
 * numbers and when they were read, never the key or the provider's own words.
 */
export type ProviderBalance = { remaining: number | null; usedToday: number | null; freeLeft: number | null; freeLimit: number | null; checkedAt: number; error: null | 'bad_key' | 'unreachable' }
// Kept by account.ts and balance.ts; the built-in provider's trouble is the account's own.
let statusOf: (id: string) => ProviderStatus | null = () => null
let balanceOf: (id: string) => ProviderBalance | null = () => null
export function providerStatusSource(f: (id: string) => ProviderStatus | null): void {
  statusOf = f
}
export function providerBalanceSource(f: (id: string) => ProviderBalance | null): void {
  balanceOf = f
}
export const listProviders = () =>
  providers.map(({ apiKey, paid, ...p }) => ({ ...p, hasKey: !!apiKey, paid: !p.builtIn && paid !== false, status: p.builtIn ? null : (statusOf(p.id) ?? null), balance: p.builtIn ? null : (balanceOf(p.id) ?? null) }))

// ── Addresses and keys (docs/PROTOCOL.md, "Providers") ──
export const OPENROUTER_URL = 'https://openrouter.ai/api'
export const NAME_MAX = 40
const KEY_MAX = 1000

/** A host a plain http:// address may name: this computer, a private or Tailscale network, the local network's names. */
export function localHost(host: string): boolean {
  const h = host.toLowerCase().replace(/^\[|\]$/g, '')
  if (h === 'localhost' || h.endsWith('.localhost') || h.endsWith('.local') || h.endsWith('.ts.net') || h === 'host.docker.internal') return true
  if (h === '::1' || /^f[cd][0-9a-f]{2}:/.test(h) || /^fe80:/.test(h)) return true
  const v4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(h)
  if (!v4) return false
  const [a, b] = [Number(v4[1]), Number(v4[2])]
  return a === 127 || a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127) || (a === 169 && b === 254)
}

/**
 * A provider's base address as Claude Code wants it (ANTHROPIC_BASE_URL, to which it adds
 * `/v1/messages`): trimmed, without a trailing `/`, `/v1` or `/v1/messages` (OpenRouter documents
 * `https://openrouter.ai/api/v1`, which would make `/api/v1/v1/messages`), no query or fragment.
 * Only http(s), no credentials in it, and plain http only to a local host (the key would travel
 * unencrypted): 400 `bad_url`, `insecure_url`.
 */
export function cleanBaseUrl(raw: unknown): string {
  const s = typeof raw === 'string' ? raw.trim() : ''
  let u: URL
  try {
    u = new URL(s)
  } catch {
    throw new ProviderError(400, 'bad_url', 'That isn’t a web address, like https://example.com/api.')
  }
  if (u.protocol !== 'https:' && u.protocol !== 'http:') throw new ProviderError(400, 'bad_url', 'The address starts with https://.')
  if (u.username || u.password) throw new ProviderError(400, 'bad_url', 'Put the key in its own field, not in the address.')
  if (u.protocol === 'http:' && !localHost(u.hostname)) throw new ProviderError(400, 'insecure_url', 'The key would travel unencrypted. Use the https:// address.')
  let path = u.pathname.replace(/\/+$/, '')
  path = path.replace(/\/v1\/messages$/i, '').replace(/\/messages$/i, '').replace(/\/+$/, '').replace(/\/v1$/i, '').replace(/\/+$/, '')
  return `${u.protocol}//${u.host}${path}`
}

/** `host:port` of an address, to tell when a key would go somewhere new. */
const hostOf = (url: string | null | undefined) => {
  try {
    return url ? new URL(url).host.toLowerCase() : null
  } catch {
    return null
  }
}

/** A pasted key: trimmed, printable ASCII only (a newline in a header breaks every request), else 400 `bad_key_format`. */
export function cleanKey(raw: unknown): string | null {
  if (raw == null) return null
  const k = String(raw).trim()
  if (!k) return null
  if (k.length > KEY_MAX || !/^[\x21-\x7e]+$/.test(k)) throw new ProviderError(400, 'bad_key_format', 'That key has characters a key can’t have. Copy it again.')
  return k
}

/** A name, at most 40 characters, not another provider's: a second "OpenRouter" becomes "OpenRouter 2". */
function uniqueName(raw: unknown, id: string | null): string {
  const name = (typeof raw === 'string' ? raw : '').trim().replace(/\s+/g, ' ') || 'Provider'
  if (name.length > NAME_MAX) throw new ProviderError(400, 'bad_name', `A name of ${NAME_MAX} characters at most.`)
  const taken = new Set(providers.filter((p) => p.id !== id).map((p) => p.name.toLowerCase()))
  if (!taken.has(name.toLowerCase())) return name
  for (let n = 2; ; n++) {
    const next = `${name.slice(0, NAME_MAX - String(n).length - 1)} ${n}`
    if (!taken.has(next.toLowerCase())) return next
  }
}

/** What a save did beyond the record: the key was dropped because the address moved to another host. */
export type ProviderSaved = { provider: ProviderRecord; keyDropped: boolean; addressChanged: boolean; keyChanged: boolean }

/**
 * Add (no [id]) or change a provider (the routes' `POST` and `PUT`). Checked the same both ways: OpenRouter or Custom, a custom one
 * with an address, the address cleaned (cleanBaseUrl), the key (cleanKey), the name (unique, at
 * most 40). An unknown [id] is 404 `no_provider`. A key kept from before never follows the address
 * to another host: it's dropped, and the answer says so. The built-in one changes its models only.
 */
export function editProvider(input: Partial<ProviderRecord> & { name?: string; kind?: ProviderKind }, id?: string): ProviderSaved {
  const existing = id ? providers.find((p) => p.id === id) : undefined
  if (id && !existing) throw new ProviderError(404, 'no_provider', 'That provider isn’t set up on this Mac.')
  if (existing?.builtIn) {
    // Only the models of the built-in profile can change.
    existing.models = clean(input.models ?? existing.models)
    writeJson(P.providers, providers)
    return { provider: existing, keyDropped: false, addressChanged: false, keyChanged: false }
  }
  const kind = input.kind ?? existing?.kind
  if (kind !== 'openrouter' && kind !== 'custom') throw new ProviderError(400, 'bad_kind', 'Choose OpenRouter or Custom.')
  const rawUrl = typeof input.baseUrl === 'string' && input.baseUrl.trim() ? input.baseUrl : kind === 'openrouter' ? (existing?.kind === 'openrouter' ? existing.baseUrl : null) || OPENROUTER_URL : existing?.baseUrl
  if (!rawUrl) throw new ProviderError(400, 'missing_url', 'A custom provider needs a base URL.')
  const baseUrl = cleanBaseUrl(rawUrl)
  const newKey = cleanKey(input.apiKey)
  const moved = !!existing && hostOf(existing.baseUrl) !== hostOf(baseUrl)
  const keyDropped = !newKey && moved && !!existing?.apiKey
  const rec: ProviderRecord = {
    id: existing?.id ?? randomToken(6),
    name: uniqueName(input.name ?? existing?.name, existing?.id ?? null),
    kind,
    baseUrl,
    apiKey: newKey ?? (keyDropped ? null : (existing?.apiKey ?? null)),
    models: clean(input.models ?? existing?.models ?? {}),
    builtIn: false,
    ...((input.paid ?? existing?.paid) === false ? { paid: false } : {}),
  }
  providers = providers.filter((p) => p.id !== rec.id).concat(rec)
  writeJson(P.providers, providers)
  return { provider: rec, keyDropped, addressChanged: !existing || existing.baseUrl !== baseUrl, keyChanged: !!newKey && newKey !== existing?.apiKey }
}
/** [editProvider], for callers that want the record only. */
export const saveProvider = (input: Partial<ProviderRecord> & { name?: string; kind?: ProviderKind }, id?: string): ProviderRecord => editProvider(input, id).provider
function clean(m: ProviderRecord['models']): ProviderRecord['models'] {
  const out: ProviderRecord['models'] = {}
  for (const k of ['default', 'opus', 'sonnet', 'haiku'] as const) {
    const v = m?.[k]?.trim()
    if (v) out[k] = v
  }
  return out
}
export function deleteProvider(id: string): boolean {
  const p = providers.find((x) => x.id === id)
  if (!p || p.builtIn) return false
  providers = providers.filter((x) => x.id !== id)
  writeJson(P.providers, providers)
  return true
}
/** Environment for a claude process using this provider. */
export function providerEnv(p: ProviderRecord): Record<string, string> {
  if (p.kind === 'anthropic') {
    const env: Record<string, string> = {}
    if (p.models.default) env.ANTHROPIC_MODEL = p.models.default
    return env
  }
  const env: Record<string, string> = { ANTHROPIC_API_KEY: '' }
  if (p.baseUrl) env.ANTHROPIC_BASE_URL = p.baseUrl
  // Without a token of its own, Claude Code signs a request to any base URL with the owner's
  // Claude login (seen with 2.1.300). A keyless gateway gets a placeholder instead, never the login.
  env.ANTHROPIC_AUTH_TOKEN = p.apiKey || 'hypernova-no-key'
  if (p.models.default) env.ANTHROPIC_MODEL = p.models.default
  if (p.models.opus) env.ANTHROPIC_DEFAULT_OPUS_MODEL = p.models.opus
  if (p.models.sonnet) env.ANTHROPIC_DEFAULT_SONNET_MODEL = p.models.sonnet
  if (p.models.haiku) {
    env.ANTHROPIC_DEFAULT_HAIKU_MODEL = p.models.haiku
    env.ANTHROPIC_SMALL_FAST_MODEL = p.models.haiku
  }
  return env
}

// Settings
export const settings = readJson<Settings>(P.settings, { projectsRoot: '' })
if (!settings.projectsRoot) {
  settings.projectsRoot = existsSync(join(HOME, 'Programming')) ? join(HOME, 'Programming') : join(HOME, 'Projects')
  writeJson(P.settings, settings)
}
export function saveSettings(): void {
  writeJson(P.settings, settings)
}

// Sessions this daemon drives (survive restarts; they're plain Claude Code sessions).
let managed = readJson<ManagedSession[]>(P.managed, [])
export const managedSessions = () => managed
/** Driven by the daemon through the Agent SDK (not observed through hooks and the registry). */
export const isSdk = (id: string) => managed.some((m) => m.id === id && m.engine !== 'term')
export const getManaged = (id: string) => managed.find((m) => m.id === id)
export function saveManaged(m: ManagedSession): void {
  managed = managed.filter((x) => x.id !== m.id).concat(m)
  writeJson(P.managed, managed)
}
/**
 * Session [id] runs on [providerId] from its next prompt. Its model was chosen for the provider
 * before, so the new one's default applies.
 */
export function setManagedProvider(id: string, providerId: string): ManagedSession | null {
  const m = getManaged(id)
  if (!m) return null
  const next = { ...m, providerId, model: null, providerAt: Date.now(), providerFrom: m.providerId }
  saveManaged(next)
  return next
}
