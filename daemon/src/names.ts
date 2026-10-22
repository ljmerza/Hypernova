// A private name under hypernova.observer, with a real certificate (names/README.md;
// docs/PROTOCOL.md, "A name on the local network"). The download page is opened in a phone's
// browser over the Wi-Fi, which can't vouch for the daemon's self-signed certificate;
// `https://l-<name>.hypernova.observer:47323/…` resolving to this computer's Wi-Fi address, with a
// Let's Encrypt certificate for that name, opens with no warning. The phone app never uses the
// name: it keeps the pinned IP route (lan.ts).
//
// Only while it's needed, and only once the user has been told: nothing is registered, published
// or certified until Setup's phone step (or `hypernova pair` on Linux) has shown what the name is
// and that Let's Encrypt's terms are accepted for it (the `nameTermsShown` setting), and a code
// that uses the name is on screen (Setup asks /local/status?qr=1; `pair/start?name=1` counts until
// the code expires; the menu bar's pairing code doesn't, as it never uses the name). Then:
//   1. An Ed25519 key, made once (<support>/names/name.json, 0600), registers a random name.
//   2. `l-<name>` is pointed at the Wi-Fi address (the same pick as the download link), with no
//      public record (`public: false`): a home's address stays out of DNS. A service that
//      publishes one anyway (it predates the option) gets the name given up at once.
//   3. A certificate for `l-<name>` from Let's Encrypt by DNS-01, the challenge record published
//      through the name service (acme.ts), kept in <support>/names/certificate.json (0600) and
//      renewed from 30 days before it runs out (renewals need only the challenge record).
//   4. Whether this network's resolver answers for the name (routers with DNS rebind protection
//      hide private addresses): only then do the links use it; checked again every 10 minutes.
// Thirty minutes after the last code was shown, the address record is withdrawn again.
// Anything failing (the service down, a rate limit, no Wi-Fi address) leaves today's behaviour:
// the links use the IP address and the self-signed certificate; /local/status says why.
//
// A test daemon (HYPERNOVA_SUPPORT) registers nothing unless HYPERNOVA_NAMES_URL says where.
import { mkdirSync, readFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { createPrivateKey, X509Certificate } from 'node:crypto'
import { Resolver, lookup } from 'node:dns/promises'
import { rootCertificates } from 'node:tls'
import { SUPPORT, VERSION, log, readJson, writeJson } from './util.ts'
import { settings, saveSettings } from './store.ts'
import { NameServiceClient, NameServiceError, exportPrivateKey, fromBase64, generateServerKey, importServerKey, toBase64, type Registration, type ServerKey } from './names-client.ts'
import { AcmeClient, AcmeError, LETS_ENCRYPT, certHosts, chainTrusted, httpsTransport, newEcKey, renewAt, splitPem } from './acme.ts'
import { advertisedAddresses, lanEnabled, lanInfo, onLanChange, pickLanAddress, setNamedCert, type NamedCert } from './lan.ts'

export const DEFAULT_SERVICE = 'https://api.hypernova.observer'
export const DEFAULT_DOMAIN = 'hypernova.observer'
/**
 * Where certificates come from unless HYPERNOVA_ACME_DIRECTORY or the `acmeDirectory` setting
 * says otherwise: production. (Staging, whose certificates browsers don't trust so the links keep the
 * IP address, served the first real run on the owner's Mac, 2026-10-18.)
 */
export const DEFAULT_DIRECTORY: 'staging' | 'production' = 'production'
/** Let's Encrypt's terms, which creating the ACME account accepts on the user's behalf. */
export const TERMS_URL = 'https://letsencrypt.org/repository/'

const HOUR = 3_600_000
/** While codes are shown, the address is sent again after this even when it hasn't changed. */
const REFRESH_MS = 24 * HOUR
/** How often the name is looked up here again while it's published (a new network, the same address). */
const RECHECK_MS = 10 * 60_000
/** The most a release waits for a round in progress before going ahead (the callers give up at 30 s). */
const RELEASE_WAIT_MS = 5_000
/** A name service's reply is a few hundred bytes; anything past this is refused unread. */
const MAX_REPLY = 64 * 1024

export type NamesConfig = {
  /** The name service; null: this daemon registers nothing. */
  serviceUrl: string | null
  /** The domain its names must be under (the registration reply is checked against it). */
  domain: string
  directoryUrl: string
  /** Certificates the ACME server's own HTTPS may chain to (a test server). */
  acmeCa?: string[]
  /** DNS servers ("host:port") to check the records on instead of the zone's own (tests). */
  dnsServers?: string[]
  /** The roots a certificate must chain to for the links to use the name (default: Node's). */
  roots: readonly string[]
  /** The address to publish: the Wi-Fi pick unless a test says otherwise. */
  lanAddress: () => string | null
  fetch: typeof fetch
  /** Quiet after an address change before it's published: Wi-Fi rejoining shouldn't cost a call per blip. */
  settleMs: number
  /**
   * How long after publishing a new address to look the name up here: asked any earlier, a
   * resolver could cache "no such name" for half an hour.
   */
  checkDelayMs: number
  /** How long the address stays published after the last code was shown. */
  lingerMs: number
}

/** "staging", "production" or a directory URL as written in the environment or the settings. */
export function directoryUrl(v: string | undefined | null): string {
  const s = (v ?? '').trim()
  if (s === 'production' || s === 'staging') return LETS_ENCRYPT[s]
  if (/^https:\/\/\S+$/.test(s)) return s
  return LETS_ENCRYPT[DEFAULT_DIRECTORY]
}
export const directoryLabel = (url: string): 'production' | 'staging' | 'custom' =>
  url === LETS_ENCRYPT.production ? 'production' : url === LETS_ENCRYPT.staging ? 'staging' : 'custom'

/** The domain a service's names are under: `api.<domain>` serves `<domain>`; otherwise hypernova.observer. */
export function domainOf(serviceUrl: string | null, override?: string): string {
  if (override) return override.toLowerCase()
  try {
    const host = new URL(serviceUrl ?? '').hostname
    if (host.startsWith('api.')) return host.slice(4)
  } catch {}
  return DEFAULT_DOMAIN
}

/** Plain words from somewhere else (a service's or a CA's message), safe for the status and the log. */
export function clean(text: unknown, max = 200): string {
  return String(text ?? '')
    .replace(/[\u0000-\u001f\u007f-\u009f\u200b-\u200f\u2028\u2029\u202a-\u202e\u2066-\u2069]/g, ' ')
    .replace(/\s+/g, ' ')
    .slice(0, max)
    .trim()
}

/**
 * The name service's fetch: no redirects (a signed request goes to the service or nowhere),
 * 20 s in all, and a reply read only up to 64 KB.
 */
export async function serviceFetch(input: string | URL | Request, init?: RequestInit): Promise<Response> {
  const timeout = AbortSignal.timeout(20_000)
  const signal = init?.signal ? AbortSignal.any([init.signal, timeout]) : timeout
  const res = await fetch(input, { ...init, redirect: 'error', signal })
  const chunks: Uint8Array[] = []
  let size = 0
  const reader = res.body?.getReader()
  if (reader) {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      size += value.length
      if (size > MAX_REPLY) {
        await reader.cancel().catch(() => {})
        throw new NameServiceError(502, 'too_large', 'The name service’s reply was too large.')
      }
      chunks.push(value)
    }
  }
  const body = res.status === 204 || res.status === 304 ? null : Buffer.concat(chunks)
  return new Response(body, { status: res.status, statusText: res.statusText, headers: res.headers })
}

let extraRoots: { path: string; roots: readonly string[] } | null = null
/** This process's configuration: the environment first, then the settings, then the defaults. */
export function envConfig(env: NodeJS.ProcessEnv = process.env): NamesConfig {
  const named = env.HYPERNOVA_NAMES_URL
  const serviceUrl = named !== undefined ? (named && named !== 'off' ? named : null) : env.HYPERNOVA_SUPPORT ? null : settings.nameServiceUrl || DEFAULT_SERVICE
  const caFile = env.HYPERNOVA_ACME_CA
  const rootsFile = env.HYPERNOVA_NAMES_ROOTS
  if (rootsFile && extraRoots?.path !== rootsFile) extraRoots = { path: rootsFile, roots: [...rootCertificates, ...splitPem(readFileSync(rootsFile, 'utf8'))] }
  const base = serviceUrl ? serviceUrl.replace(/\/+$/, '') : null
  return {
    serviceUrl: base,
    domain: domainOf(base, env.HYPERNOVA_NAMES_DOMAIN),
    directoryUrl: directoryUrl(env.HYPERNOVA_ACME_DIRECTORY ?? settings.acmeDirectory),
    acmeCa: caFile ? splitPem(readFileSync(caFile, 'utf8')) : undefined,
    dnsServers: env.HYPERNOVA_NAMES_DNS ? env.HYPERNOVA_NAMES_DNS.split(',').map((s) => s.trim()).filter(Boolean) : undefined,
    roots: rootsFile ? extraRoots!.roots : rootCertificates,
    lanAddress: () => pickLanAddress(advertisedAddresses()),
    fetch: serviceFetch,
    settleMs: 15_000,
    checkDelayMs: 30_000,
    lingerMs: 30 * 60_000,
  }
}

/**
 * The service's answer to a registration, checked: a plain name, under the expected domain, and
 * the two host names exactly what that makes. Anything else is refused before it's stored or
 * used in a path, a certificate request or a link.
 */
export function checkRegistration(reply: unknown, domain: string): Registration {
  const r = (reply ?? {}) as Partial<Registration>
  const name = typeof r.name === 'string' ? r.name : ''
  if (!/^[a-z0-9]{1,64}$/.test(name) || r.domain !== domain || r.fqdn !== `${name}.${domain}` || r.lanFqdn !== `l-${name}.${domain}`) {
    throw new NameServiceError(502, 'bad_registration', `The name service answered with something other than a name under ${domain}.`)
  }
  return { name, fqdn: r.fqdn, lanFqdn: r.lanFqdn, domain }
}

// ── What went wrong, and when to try again ───────────────────────────────

export type Failure = { kind: 'rate' | 'unreachable' | 'server' | 'clock' | 'invalid' | 'refused'; retryAt: number | null; message: string }

/** An error from the name service, the CA or the network, in the terms the back-off works in (messages cleaned). */
export function classify(e: unknown, where: 'service' | 'acme', now = Date.now()): Failure {
  const who = where === 'service' ? 'the name service' : 'Let’s Encrypt'
  if (e instanceof NameServiceError) {
    const m = clean(e.message)
    if (e.status === 429) return { kind: 'rate', retryAt: e.retryAfter !== null ? now + e.retryAfter * 1000 : null, message: `The name service’s limit was reached (${m})` }
    if (e.code === 'expired') return { kind: 'clock', retryAt: null, message: 'This computer’s clock is off by more than five minutes, so the name service refuses its requests.' }
    if (e.status >= 500) return { kind: 'server', retryAt: null, message: `The name service had a problem (${m})` }
    return { kind: 'refused', retryAt: null, message: `The name service refused: ${m}` }
  }
  if (e instanceof AcmeError) {
    const m = clean(e.message)
    if (e.type === 'rateLimited') return { kind: 'rate', retryAt: e.retryAt, message: `Let’s Encrypt’s limit was reached (${m})` }
    if (e.type === 'connection') return { kind: 'unreachable', retryAt: null, message: `Couldn’t reach Let’s Encrypt.` }
    if (e.type === 'server' || e.type === 'serverInternal' || e.status >= 500) return { kind: 'server', retryAt: e.retryAt, message: `Let’s Encrypt had a problem (${m})` }
    if (['dns', 'unauthorized', 'incorrectResponse', 'invalid', 'timeout', 'caa'].includes(e.type)) return { kind: 'invalid', retryAt: e.retryAt, message: m }
    return { kind: 'refused', retryAt: e.retryAt, message: `Let’s Encrypt refused: ${m}` }
  }
  return { kind: 'unreachable', retryAt: null, message: `Couldn’t reach ${who}.` }
}

/**
 * When to try again after [failures] failures in a row. A rate limit waits as long as it says
 * (at least a minute); no answer doubles from a minute (the service) or five (the CA) up to an
 * hour or six; a failed DNS check doubles from an hour (Let's Encrypt allows five failures per
 * name an hour), a refusal from fifteen minutes, both up to a day; a wrong clock waits an hour.
 */
export function nextAttempt(f: Failure, failures: number, where: 'service' | 'acme', now = Date.now()): number {
  const n = Math.max(1, failures)
  const doubling = (base: number, cap: number) => now + Math.min(cap, base * 2 ** Math.min(20, n - 1))
  switch (f.kind) {
    case 'rate':
      return Math.max(f.retryAt ?? now + HOUR, now + 60_000)
    case 'clock':
      return now + HOUR
    case 'unreachable':
    case 'server':
      return Math.max(f.retryAt ?? 0, where === 'service' ? doubling(60_000, HOUR) : doubling(5 * 60_000, 6 * HOUR))
    case 'invalid':
      return Math.max(f.retryAt ?? 0, doubling(HOUR, 24 * HOUR))
    case 'refused':
      return Math.max(f.retryAt ?? 0, doubling(15 * 60_000, 24 * HOUR))
  }
}

// ── DNS checks ──────────────────────────────────────────────────────────

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

/** A sleep that ends early, with an error, when [signal] is aborted (a release stopping a round). */
export function abortableSleep(signal: AbortSignal): (ms: number) => Promise<void> {
  return (ms) =>
    new Promise<void>((resolve, reject) => {
      if (signal.aborted) return reject(new Error('stopped'))
      const t = setTimeout(() => {
        signal.removeEventListener('abort', stop)
        resolve()
      }, ms)
      const stop = () => {
        clearTimeout(t)
        reject(new Error('stopped'))
      }
      signal.addEventListener('abort', stop, { once: true })
    })
}

/**
 * Waits until [fqdn] has a TXT record [value] on the zone's own name servers (or [servers]), so
 * the CA doesn't look before the record is there (a failed check counts against a limit). Gives
 * up after [limitMs] with an error; when the name servers can't be asked at all (a network that
 * blocks DNS to the outside), waits half a minute and lets the CA look.
 */
export async function waitForTxt(fqdn: string, value: string, zone: string, servers?: string[], limitMs = 120_000, pause: (ms: number) => Promise<void> = sleep): Promise<void> {
  const r = new Resolver({ timeout: 3_000, tries: 1 })
  try {
    if (servers?.length) r.setServers(servers)
    else {
      const sys = new Resolver({ timeout: 5_000, tries: 2 })
      const ns = await sys.resolveNs(zone)
      const ips = (await Promise.all(ns.slice(0, 4).map((n) => sys.resolve4(n).catch(() => [] as string[])))).flat()
      if (!ips.length) throw new Error('no name servers')
      r.setServers(ips)
    }
  } catch {
    await pause(30_000)
    return
  }
  const deadline = Date.now() + limitMs
  let answered = false
  for (;;) {
    try {
      const recs = await r.resolveTxt(fqdn)
      answered = true
      if (recs.some((chunks) => chunks.join('') === value)) return
    } catch (e: any) {
      if (e?.code === 'ENOTFOUND' || e?.code === 'ENODATA') answered = true
    }
    if (Date.now() >= deadline) {
      if (!answered) return // nothing ever answered: let the CA look for itself
      throw new AcmeError('dns', `The challenge record for ${fqdn} didn’t appear in DNS within ${Math.round(limitMs / 1000)} s.`, 0)
    }
    await pause(3_000)
  }
}

/** Whether [host] resolves to [address] here: the system's resolver (as the phone's browser would), or [servers]. */
export async function resolvesTo(host: string, address: string, servers?: string[]): Promise<boolean> {
  try {
    if (servers?.length) {
      const r = new Resolver({ timeout: 3_000, tries: 2 })
      r.setServers(servers)
      const got = address.includes(':') ? await r.resolve6(host) : await r.resolve4(host)
      return got.includes(address)
    }
    const got = await lookup(host, { all: true })
    return got.some((a) => a.address === address)
  } catch {
    return false
  }
}

// ── The keeper ──────────────────────────────────────────────────────────

type SavedName = Registration & { service: string; publicKey: string; privateKey: string; registeredAt: number; pendingRelease?: boolean }
type SavedAcme = { key: string; accounts: Record<string, string> }
type SavedCert = { directory: string; hosts: string[]; cert: string; key: string; issuedAt: number }
type Backoff = { until: number; failures: number }
type State = { published: { address: string; at: number } | null; demandUntil: number; service: Backoff; acme: Backoff; error: { at: number; message: string } | null }
type CertInfo = { notBefore: number; notAfter: number; renewAt: number; trusted: boolean; hosts: string[] }

export type NameStatus = {
  /** The setting (unset: on), a name service to talk to, and not given up. */
  enabled: boolean
  /** The `nameService` setting itself, for a switch. */
  setting: boolean
  /** off, idle (nothing to do now: no code shown, the listener off, no Wi-Fi address), working, waiting (to try again), ready. */
  state: 'off' | 'idle' | 'working' | 'waiting' | 'ready'
  name: string | null
  /** The name the links use: l-<name>.<domain>. */
  host: string | null
  /** The address `host` points at while it's published; null while it isn't. */
  address: string | null
  /** A download or pairing code is (or was in the last 30 minutes) shown, so the address is published. */
  showing: boolean
  /** Whether this computer's resolver answers `host` with `address` (null: not checked yet). */
  resolves: boolean | null
  certificate: { expiresAt: number; renewsAt: number; trusted: boolean; directory: 'production' | 'staging' | 'custom' } | null
  /** The download and pairing links use the name right now. */
  inUse: boolean
  /** Why not, or what's happening, in plain words. */
  reason: string
  lastError: { at: number; message: string } | null
  nextAttemptAt: number | null
  service: string | null
  /** Let's Encrypt's terms, accepted on the user's behalf when the first certificate is asked for. */
  terms: string
  /** Setup (or `hypernova pair`) has shown what the name is and the terms: until then nothing is made. */
  termsShown: boolean
}

const freshState = (): State => ({ published: null, demandUntil: 0, service: { until: 0, failures: 0 }, acme: { until: 0, failures: 0 }, error: null })
/** A round's way out when the name had to be given up (an old service published the public address). */
class GivenUp extends Error {}

export class NameKeeper {
  readonly dir: string
  private cfg: () => NamesConfig
  private onCert: (c: NamedCert | null) => void
  private paths: { name: string; acme: string; cert: string; state: string }
  private st: State
  private saved: SavedName | null
  private key: ServerKey | null = null
  private cert: SavedCert | null
  private info: CertInfo | null = null
  private resolves: boolean | null = null
  private busy: Promise<void> | null = null
  /** The round in progress, which a release stops. */
  private ctrl = new AbortController()
  private working = ''
  private timer: NodeJS.Timeout | null = null
  private settle: NodeJS.Timeout | null = null
  private resolveTimer: NodeJS.Timeout | null = null
  private stopped = false
  /** Why the name was given up and isn't made again until a restart or the setting (B1). */
  private givenUp: string | null = null
  /** A round was asked for while one ran: another follows at once (a code shown, an address change). */
  private again = false
  /** Bumped whenever what's published changes, so a lookup that started before is ignored. */
  private generation = 0
  /** Tests: renewals asked for (forceRenewal) and the last one a finished issuance answered. */
  private renewAsked = 0
  private renewDone = 0
  /** Released (uninstall): nothing more until the daemon starts again or the setting is turned on. */
  suspended = false

  constructor(dir: string, cfg: () => NamesConfig, onCert: (c: NamedCert | null) => void) {
    this.dir = dir
    this.cfg = cfg
    this.onCert = onCert
    this.paths = { name: join(dir, 'name.json'), acme: join(dir, 'acme.json'), cert: join(dir, 'certificate.json'), state: join(dir, 'state.json') }
    this.saved = readJson<SavedName | null>(this.paths.name, null)
    this.cert = readJson<SavedCert | null>(this.paths.cert, null)
    this.st = { ...freshState(), ...readJson<Partial<State>>(this.paths.state, {}) }
    this.parseCert()
  }

  private ensureDir(): void {
    mkdirSync(this.dir, { recursive: true, mode: 0o700 })
  }
  private saveState(): void {
    this.ensureDir()
    writeJson(this.paths.state, this.st)
  }

  /** Reads the stored certificate; drops it when it's unreadable, doesn't match its key, or ran out. */
  private parseCert(): void {
    this.info = null
    if (!this.cert) return
    try {
      const leaf = new X509Certificate(splitPem(this.cert.cert)[0] ?? '')
      if (!leaf.checkPrivateKey(createPrivateKey(this.cert.key))) throw new Error('key mismatch')
      const notBefore = Date.parse(leaf.validFrom)
      const notAfter = Date.parse(leaf.validTo)
      if (!(notAfter > Date.now())) throw new Error('expired')
      this.info = { notBefore, notAfter, renewAt: renewAt(notBefore, notAfter), trusted: chainTrusted(this.cert.cert, this.cfg().roots), hosts: certHosts(this.cert.cert) }
    } catch (e) {
      log('names: the stored certificate is no good, a new one follows', e instanceof Error ? e.message : '')
      this.cert = null
    }
  }

  /** The setting, a name service to talk to, and not released or given up. */
  enabled(cfg = this.cfg()): boolean {
    return settings.nameService !== false && cfg.serviceUrl !== null && !this.suspended && !this.givenUp
  }

  /** A code is shown now, or was within the last 30 minutes. */
  showing(now = Date.now()): boolean {
    return now < this.st.demandUntil + this.cfg().lingerMs
  }

  /** The user has been told what the name is and that Let's Encrypt's terms are accepted for it. */
  termsShown(): boolean {
    return settings.nameTermsShown === true
  }

  /**
   * A code that uses the name is on screen until [until] (now, for a status poll; a pairing code's
   * expiry): the name is made or published if it isn't, and stays published 30 minutes past it.
   * Ignored while the name is off or before the terms were shown, so such a code never counts later.
   */
  want(until = Date.now()): void {
    if (!this.enabled() || !this.termsShown()) return
    const was = this.showing()
    if (until > this.st.demandUntil) {
      const moved = until - this.st.demandUntil
      this.st.demandUntil = until
      // A status poll every 2 s needn't write the file each time.
      if (!was || moved > 60_000) this.saveState()
    }
    if (!was || !this.usable()) this.schedule(0)
  }

  /** What the LAN listener serves for the name (lan.ts): any good certificate for it, trusted or not (staging, for a test). */
  route(): NamedCert | null {
    if (!this.saved || this.saved.pendingRelease || !this.cert || !this.info || this.info.notAfter <= Date.now()) return null
    if (!this.info.hosts.includes(this.saved.lanFqdn)) return null
    return { host: this.saved.lanFqdn, key: this.cert.key, cert: this.cert.cert }
  }

  /** The origin the links use (`https://l-<name>.<domain>:<port>`), or null when the name isn't usable right now. */
  origin(): string | null {
    const port = lanInfo()?.port
    return port && this.usable() ? `https://${this.saved!.lanFqdn}:${port}` : null
  }

  /** The first reason the name can't be used now, or null when it can. */
  private blocker(cfg = this.cfg()): string | null {
    if (this.givenUp) return this.givenUp
    // First, whatever the setting says: a name still in DNS because its release didn't get through.
    if (this.saved?.pendingRelease) return `Giving the name up: the name service couldn’t be reached, so ${this.saved.lanFqdn} stays in DNS until it can; trying again.`
    if (settings.nameService === false) return 'Turned off.'
    if (cfg.serviceUrl === null) return 'This daemon has no name service (a test copy without HYPERNOVA_NAMES_URL).'
    if (this.suspended) return 'Released; the next start registers a new name.'
    if (!this.saved && !this.termsShown()) return 'Not made yet: Setup’s phone step (or hypernova pair) says what the private name is first.'
    if (!lanEnabled()) return 'The local network listener is off, so nothing would use a name.'
    if (!this.showing()) return 'Not published: the name goes into DNS only while a download or pairing code is shown.'
    const address = cfg.lanAddress()
    if (!address) return 'No Wi‑Fi address to publish.'
    if (!this.saved) return this.working || this.st.error?.message || 'Registering a name…'
    if (this.st.published?.address !== address) return this.working || this.st.error?.message || 'Publishing this computer’s address…'
    const route = this.route()
    if (!route) return this.working || this.st.error?.message || 'Getting a certificate…'
    if (!this.info!.trusted) return `The certificate is from ${directoryLabel(this.cert!.directory) === 'staging' ? 'Let’s Encrypt’s staging' : 'a certificate authority'} that browsers don’t trust, so the links keep the address.`
    if (this.resolves === false) return 'This network’s DNS doesn’t answer for the name (routers with DNS rebind protection hide private addresses), so the links keep the address.'
    if (this.resolves === null) return 'Checking that this network finds the name…'
    return null
  }
  private usable(): boolean {
    return this.blocker() === null
  }

  status(): NameStatus {
    const cfg = this.cfg()
    const enabled = this.enabled(cfg)
    const reason = this.blocker(cfg)
    const waitingUntil = Math.max(this.st.service.until, this.st.acme.until)
    const showing = this.showing()
    const address = cfg.lanAddress()
    const idle = enabled && (!lanEnabled() || !showing || !address)
    // Everything in place (a name, its address, a good certificate), whether or not the links can use it.
    const complete = !!this.saved && this.st.published?.address === address && !!this.route()
    return {
      enabled,
      setting: settings.nameService !== false,
      state: !enabled ? 'off' : this.working ? 'working' : idle ? 'idle' : complete ? 'ready' : waitingUntil > Date.now() ? 'waiting' : 'working',
      name: this.saved?.name ?? null,
      host: this.saved?.lanFqdn ?? null,
      address: this.st.published?.address ?? null,
      showing,
      resolves: this.resolves,
      certificate: this.cert && this.info ? { expiresAt: this.info.notAfter, renewsAt: this.info.renewAt, trusted: this.info.trusted, directory: directoryLabel(this.cert.directory) } : null,
      inUse: reason === null,
      reason: reason ?? 'In use.',
      lastError: this.st.error,
      nextAttemptAt: waitingUntil > Date.now() ? waitingUntil : null,
      service: cfg.serviceUrl,
      terms: TERMS_URL,
      termsShown: this.termsShown(),
    }
  }

  // ── Running ──

  /** Starts the keeper: a stored certificate goes to the listener, the first round in [delayMs]. */
  start(delayMs = 3_000): void {
    this.stopped = false
    this.onCert(this.enabled() ? this.route() : null)
    this.schedule(delayMs)
  }

  stop(): void {
    this.stopped = true
    this.ctrl.abort()
    for (const t of [this.timer, this.settle, this.resolveTimer]) if (t) clearTimeout(t)
    this.timer = this.settle = this.resolveTimer = null
  }

  /** Runs a round in [ms] (sooner wins over a later one already set). */
  schedule(ms: number): void {
    if (this.stopped) return
    if (this.timer) clearTimeout(this.timer)
    this.timer = setTimeout(() => {
      this.timer = null
      void this.tick()
    }, Math.max(0, ms))
    this.timer.unref()
  }

  /** The Wi-Fi address changed (lan.ts): a round once it has been quiet for a while. */
  addressChanged(): void {
    if (this.stopped) return
    if (this.settle) clearTimeout(this.settle)
    this.settle = setTimeout(() => {
      this.settle = null
      this.schedule(0)
    }, this.cfg().settleMs)
    this.settle.unref()
  }

  /** One round (one at a time; a call during a round waits for it, and another round follows it). */
  async tick(): Promise<void> {
    if (this.busy) {
      this.again = true
      return this.busy
    }
    this.ctrl = new AbortController()
    const signal = this.ctrl.signal
    this.busy = this.round(signal)
      .catch((e) => {
        if (!(e instanceof GivenUp) && !signal.aborted) log('names: round failed', clean(e instanceof Error ? e.message : e))
      })
      .finally(() => {
        this.busy = null
        this.working = ''
        const again = this.again && !signal.aborted
        this.again = false
        this.schedule(again ? 0 : this.nextWake())
      })
    return this.busy
  }

  private nextWake(): number {
    const now = Date.now()
    let at = now + HOUR
    const withdrawAt = this.st.published ? this.st.demandUntil + this.cfg().lingerMs : 0
    for (const t of [this.st.service.until, this.st.acme.until, this.info?.renewAt ?? 0, withdrawAt]) if (t > now && t < at) at = t
    return at - now + 1_000
  }

  private async round(signal: AbortSignal): Promise<void> {
    const cfg = this.cfg()
    // A certificate that ran out (every renewal failed for a month) isn't served by name any longer.
    if (!this.route()) this.onCert(null)
    // A release that couldn't reach the service is tried again, whatever the setting.
    if (this.saved?.pendingRelease) {
      if (Date.now() >= this.st.service.until) await this.releaseSaved(this.saved, signal)
      return
    }
    if (!this.enabled(cfg)) return
    // Nothing is made before the user has been told (a name made before carries on: renewals).
    if (!this.saved && !this.termsShown()) return
    const showing = this.showing() && lanEnabled()
    const address = showing ? cfg.lanAddress() : null
    if (Date.now() >= this.st.service.until) {
      try {
        // A code shown with no Wi-Fi address for now: nothing to publish, and the record stays.
        if (address) await this.publish(cfg, address, signal)
        else if (!showing && this.st.published && this.saved) await this.withdraw(cfg, signal)
        this.succeeded('service')
      } catch (e) {
        if (e instanceof GivenUp) return
        this.failed('service', e, signal)
      }
    }
    // The first certificate only while a code is shown; renewals whenever they're due.
    if (this.saved && (showing || this.cert) && this.certDue(cfg) && Date.now() >= this.st.acme.until) {
      try {
        await this.issue(cfg, signal)
        this.succeeded('acme')
      } catch (e) {
        this.failed('acme', e, signal)
      }
    }
    if (address && this.route() && this.st.published?.address === address && !this.resolveTimer) this.checkResolves(0)
  }

  private succeeded(where: 'service' | 'acme'): void {
    if (this.st[where].failures || this.st.error) {
      this.st[where] = { until: 0, failures: 0 }
      if (!this.st.service.failures && !this.st.acme.failures) this.st.error = null
      this.saveState()
    }
  }

  private failed(where: 'service' | 'acme', e: unknown, signal?: AbortSignal): void {
    // A release stopped this round: what it was doing no longer matters.
    if (signal?.aborted) return
    const f = classify(e, where)
    const failures = this.st[where].failures + 1
    this.st[where] = { until: nextAttempt(f, failures, where), failures }
    this.st.error = { at: Date.now(), message: f.message }
    this.saveState()
    log(`names: ${where === 'service' ? 'name service' : 'certificate'}: ${f.message}; next try ${new Date(this.st[where].until).toISOString()}`)
  }

  private client(cfg: NamesConfig, signal?: AbortSignal, service = cfg.serviceUrl!): NameServiceClient {
    const f: typeof fetch = signal ? (input, init) => cfg.fetch(input, { ...init, signal: init?.signal ? AbortSignal.any([init.signal, signal]) : signal }) : cfg.fetch
    return new NameServiceClient({ baseUrl: service, fetch: f })
  }

  private async serverKey(): Promise<ServerKey> {
    if (!this.key) this.key = await importServerKey(fromBase64(this.saved!.privateKey), this.saved!.publicKey)
    return this.key
  }

  /** Registers a name when there's none (a name with another service is given up there first). */
  private async identity(cfg: NamesConfig, signal: AbortSignal): Promise<SavedName> {
    if (this.saved && this.saved.service === cfg.serviceUrl) return this.saved
    if (this.saved) {
      // The name service changed: the old name is given up with the old service first (once; it
      // lapses after 90 days unused if that one can't be reached).
      await this.releaseSaved(this.saved, signal, true)
    }
    this.working = 'Registering a name…'
    const key = await generateServerKey()
    const reg = checkRegistration(await this.client(cfg, signal).register(key.publicKey), cfg.domain)
    this.ensureDir()
    this.saved = { service: cfg.serviceUrl!, ...reg, publicKey: key.publicKey, privateKey: toBase64(await exportPrivateKey(key)), registeredAt: Date.now() }
    this.key = key
    writeJson(this.paths.name, this.saved)
    log(`names: registered ${reg.lanFqdn}`)
    return this.saved
  }

  /**
   * An old service publishes the caller's public address whatever `public: false` says: the name
   * is given up at once, before a certificate puts it in the logs next to that address, and not
   * made again until the service is redeployed and the setting turned off and on (or a restart).
   */
  private async refusePublic(signal: AbortSignal): Promise<never> {
    const saved = this.saved!
    log(`names: the name service published this network’s public address for ${saved.fqdn} although asked not to (it predates "public": false); giving the name up`)
    await this.releaseSaved(saved, signal)
    this.givenUp = 'The name service published this network’s public address as well (it predates “public”: false), so the name was given up at once. Redeploy the name service, then turn the private name off and on again.'
    this.st.error = { at: Date.now(), message: this.givenUp }
    this.saveState()
    throw new GivenUp()
  }

  /** Points l-<name> at [address] when it changed or a day has passed; a name the service no longer knows is registered again, once. */
  private async publish(cfg: NamesConfig, address: string, signal: AbortSignal): Promise<void> {
    for (let attempt = 0; ; attempt++) {
      const id = await this.identity(cfg, signal)
      const p = this.st.published
      if (p && p.address === address && Date.now() - p.at < REFRESH_MS) return
      this.working = 'Publishing this computer’s address…'
      let reply
      try {
        reply = await this.client(cfg, signal).updateAddress(await this.serverKey(), id.name, [address], { public: false })
      } catch (e) {
        if (attempt === 0 && e instanceof NameServiceError && e.code === 'unknown_name') {
          this.forget('the name service no longer knows the name')
          continue
        }
        throw e
      }
      if (reply?.public) await this.refusePublic(signal)
      const moved = p?.address !== address
      this.st.published = { address, at: Date.now() }
      this.saveState()
      if (moved) {
        this.generation++
        log(`names: ${id.lanFqdn} → ${address}`)
        // Checked once the record has had time to reach DNS (looking any earlier could leave a
        // "no such name" in the router's cache for half an hour).
        this.resolves = null
        this.checkResolves(cfg.checkDelayMs)
      }
      return
    }
  }

  /** No code shown for a while: the address record goes (the name and its certificate stay, for renewals and the next code). */
  private async withdraw(cfg: NamesConfig, signal: AbortSignal): Promise<void> {
    const id = this.saved!
    this.working = 'Withdrawing the address…'
    let reply
    try {
      reply = await this.client(cfg, signal).updateAddress(await this.serverKey(), id.name, [], { public: false })
    } catch (e) {
      if (!(e instanceof NameServiceError && e.code === 'unknown_name')) throw e
      this.forget('the name service no longer knows the name')
      return
    }
    if (reply?.public) await this.refusePublic(signal)
    this.st.published = null
    this.resolves = null
    this.generation++
    if (this.resolveTimer) clearTimeout(this.resolveTimer)
    this.resolveTimer = null
    this.saveState()
    log(`names: ${id.lanFqdn} withdrawn from DNS (no code shown for ${Math.round(cfg.lingerMs / 60_000)} minutes)`)
  }

  private certDue(cfg: NamesConfig): boolean {
    if (!this.saved) return false
    if (!this.cert || !this.info) return true
    if (this.renewAsked > this.renewDone) return true
    if (this.cert.directory !== cfg.directoryUrl) return true
    if (!this.info.hosts.includes(this.saved.lanFqdn)) return true
    return Date.now() >= this.info.renewAt
  }

  /** A certificate for l-<name> (acme.ts), the challenge records through the name service. */
  private async issue(cfg: NamesConfig, signal: AbortSignal): Promise<void> {
    const id = this.saved!
    this.working = this.cert ? 'Renewing the certificate…' : 'Getting a certificate…'
    this.ensureDir()
    let acme = readJson<SavedAcme | null>(this.paths.acme, null)
    if (!acme?.key) {
      acme = { key: newEcKey().export({ type: 'pkcs8', format: 'pem' }).toString(), accounts: {} }
      writeJson(this.paths.acme, acme)
    }
    const pause = abortableSleep(signal)
    const client = new AcmeClient({
      directoryUrl: cfg.directoryUrl,
      accountKey: createPrivateKey(acme.key),
      accountUrl: acme.accounts[cfg.directoryUrl] ?? null,
      transport: httpsTransport({ ca: cfg.acmeCa, userAgent: `hypernova-daemon/${VERSION}`, signal }),
      sleep: pause,
    })
    const service = this.client(cfg, signal)
    const key = await this.serverKey()
    const hostOf = (fqdn: string) => (fqdn === id.lanFqdn ? 'lan' : 'main')
    const hosts = [id.lanFqdn]
    const asked = this.renewAsked
    try {
      const out = await client.issue(hosts, {
        publish: async (h, v) => void (await service.setAcme(key, id.name, v, hostOf(h))),
        unpublish: async (h, v) => void (await service.clearAcme(key, id.name, v, hostOf(h))),
        visible: (h, v) => waitForTxt(`_acme-challenge.${h}`, v, id.domain, cfg.dnsServers, 120_000, pause),
      })
      this.cert = { directory: cfg.directoryUrl, hosts, cert: out.cert, key: out.key, issuedAt: Date.now() }
      this.renewDone = asked
      writeJson(this.paths.cert, this.cert)
      this.parseCert()
      this.onCert(this.route())
      log(`names: certificate for ${hosts.join(', ')} until ${new Date(this.info?.notAfter ?? 0).toISOString()} (${directoryLabel(cfg.directoryUrl)}${this.info?.trusted ? '' : ', not trusted by browsers'})`)
    } finally {
      // The account (made or found) is kept even when the order failed.
      if (client.accountUrl && client.accountUrl !== acme.accounts[cfg.directoryUrl]) {
        acme.accounts[cfg.directoryUrl] = client.accountUrl
        writeJson(this.paths.acme, acme)
      }
    }
  }

  /**
   * Checks whether this network finds the name, in [delayMs], and again every 10 minutes while
   * it's published (the Mac may join another network with the same address).
   */
  private checkResolves(delayMs: number): void {
    if (this.resolveTimer) clearTimeout(this.resolveTimer)
    this.resolveTimer = setTimeout(async () => {
      this.resolveTimer = null
      const cfg = this.cfg()
      const address = cfg.lanAddress()
      if (!this.saved || !address || this.st.published?.address !== address || !this.showing() || this.stopped) return
      const host = this.saved.lanFqdn
      const generation = this.generation
      const ok = await resolvesTo(host, address, cfg.dnsServers)
      // What was published changed meanwhile (another address, a withdrawal, another name): this
      // answer is about something else; the change scheduled its own check.
      if (generation !== this.generation || this.saved?.lanFqdn !== host || this.st.published?.address !== address) return
      if (ok !== this.resolves) log(`names: ${host} ${ok ? 'resolves here' : 'does not resolve here (yet)'}`)
      this.resolves = ok
      if (!this.resolveTimer) this.checkResolves(RECHECK_MS)
    }, delayMs)
    this.resolveTimer.unref()
  }

  /**
   * Drops the name and its certificate here (the files too); the next round registers anew.
   * [keepName]: keep name.json, marked to be released, so its key can still give it up later.
   */
  private forget(why: string, keepName = false): void {
    if (this.saved) log(`names: ${keepName ? 'dropping the certificate of' : 'forgetting'} ${this.saved.lanFqdn} (${why})`)
    for (const p of [this.paths.cert, this.paths.state, ...(keepName ? [] : [this.paths.name])]) rmSync(p, { force: true })
    if (keepName && this.saved) {
      this.saved = { ...this.saved, pendingRelease: true }
      writeJson(this.paths.name, this.saved)
    } else {
      this.saved = null
      this.key = null
    }
    this.cert = null
    this.info = null
    this.resolves = null
    this.generation++
    if (this.resolveTimer) clearTimeout(this.resolveTimer)
    this.resolveTimer = null
    this.st = { ...freshState(), demandUntil: this.st.demandUntil }
    this.onCert(null)
  }

  /**
   * Gives [saved] up with the service it was registered with: all its records leave DNS. When
   * that fails, the key stays (marked) and the next rounds try again; [once]: don't (a name with
   * a service no longer configured). True when the service confirmed it (or no longer knew it).
   */
  private async releaseSaved(saved: SavedName, signal?: AbortSignal, once = false): Promise<boolean> {
    let released = false
    try {
      const cfg = this.cfg()
      await this.client(cfg, signal, saved.service).release(await this.serverKey(), saved.name)
      released = true
    } catch (e) {
      released = e instanceof NameServiceError && e.code === 'unknown_name'
      if (!released) {
        log(`names: couldn’t release ${saved.lanFqdn} (${classify(e, 'service').message})${once ? '; it lapses after 90 days unused' : '; trying again later'}`)
        if (!once) {
          this.forget('the release is retried', true)
          const failures = this.st.service.failures + 1
          this.st.service = { until: nextAttempt(classify(e, 'service'), failures, 'service'), failures }
          this.saveState()
          return false
        }
      }
    }
    this.forget('released')
    if (released) log(`names: released ${saved.lanFqdn}`)
    return released
  }

  /**
   * Gives the name up (uninstall, or the setting turned off): its records leave DNS at once, and
   * it's forgotten here. A round in progress is stopped, waited for 5 s at most. When the service
   * can't be reached the key is kept and the release tried again by the next start; failing that,
   * the name lapses by itself after 90 days unused. `released`: the service confirmed it.
   */
  async release(): Promise<{ released: boolean; name: string | null; waited?: boolean }> {
    this.ctrl.abort()
    let waited = false
    if (this.busy) waited = !(await Promise.race([this.busy.then(() => true), sleep(RELEASE_WAIT_MS).then(() => false)]))
    const saved = this.saved
    let released = false
    if (!saved) this.forget('released')
    else released = await this.releaseSaved(saved)
    // Codes shown before don't count any more (turned on again, the name waits for a new one).
    this.st.demandUntil = 0
    this.saveState()
    return { released, name: saved?.name ?? null, ...(waited ? { waited } : {}) }
  }

  /** The setting turned on again: whatever stopped the name before is forgotten. */
  resume(): void {
    this.suspended = false
    this.givenUp = null
  }

  /** Tests: a renewal is due now, whatever the dates (one already under way doesn't count for it). */
  forceRenewal(): void {
    this.renewAsked++
  }
}

// ── This daemon's keeper ─────────────────────────────────────────────────

export const NAMES_DIR = join(SUPPORT, 'names')
let keeper: NameKeeper | null = null
let config: () => NamesConfig = envConfig

/** Tests: replaces parts of the configuration (before startNames). */
export function setNamesConfig(over: Partial<NamesConfig>): void {
  config = () => ({ ...envConfig(), ...over })
}

/** Starts keeping the name (main.ts, after the servers). Nothing is registered until a code is shown. */
export function startNames(): NameKeeper {
  if (keeper) return keeper
  keeper = new NameKeeper(NAMES_DIR, () => config(), setNamedCert)
  onLanChange(() => keeper?.addressChanged())
  keeper.start()
  return keeper
}

export function stopNames(): void {
  keeper?.stop()
  keeper = null
}

/** /local/status's `name`; an off state before the keeper starts. */
export function nameStatus(): NameStatus {
  return (
    keeper?.status() ?? {
      enabled: false,
      setting: settings.nameService !== false,
      state: 'off',
      name: null,
      host: null,
      address: null,
      showing: false,
      resolves: null,
      certificate: null,
      inUse: false,
      reason: 'Not running.',
      lastError: null,
      nextAttemptAt: null,
      service: null,
      terms: TERMS_URL,
      termsShown: settings.nameTermsShown === true,
    }
  )
}

/** The origin the download and pairing links use, or null for the IP address (http.ts). */
export const namedOrigin = (): string | null => keeper?.origin() ?? null

/** A download or pairing code is shown (until [until]): the name is made or published for it. */
export function wantName(until = Date.now()): void {
  keeper?.want(until)
}

/** Something the name depends on changed (the listener's setting): a round now. */
export function nameKick(): void {
  keeper?.schedule(0)
}

/**
 * The `nameService` setting: on resumes (the name is made when a code is next shown); off gives
 * the name up, and says whether that got through (`release`: null when there was nothing to give up).
 */
export async function setNameService(on: boolean): Promise<{ status: NameStatus; release: { released: boolean; name: string | null; waited?: boolean } | null }> {
  settings.nameService = on
  saveSettings()
  let release = null
  if (keeper) {
    if (on) {
      keeper.resume()
      keeper.start(0)
    } else release = await keeper.release()
  }
  return { status: nameStatus(), release: release?.name ? release : null }
}

/** Setup (or `hypernova pair`) has shown what the private name is and Let's Encrypt's terms. */
export function setNameTermsShown(): void {
  if (settings.nameTermsShown === true) return
  settings.nameTermsShown = true
  saveSettings()
  keeper?.schedule(0)
}

/** Gives the name up before an uninstall (POST /local/name/release); nothing more until the next start. */
export async function releaseName(): Promise<{ released: boolean; name: string | null; waited?: boolean }> {
  const k = keeper ?? new NameKeeper(NAMES_DIR, () => config(), () => {})
  k.suspended = true
  return k.release()
}
