// ACME (RFC 8555), as much of it as a Let's Encrypt certificate for the daemon's own name needs
// (names.ts; docs/PROTOCOL.md, "A name on the local network"): an account (EC P-256 key, no
// contact), an order, DNS-01 challenges published through the name service, the CSR (built
// here, DER by hand: Node has no CSR API, and the daemon shouldn't need openssl for this), and
// the certificate chain. Nothing else: no revocation, no key change, no external account
// binding, no wildcards.
//
// Keys and tokens never reach the log or an error message: errors carry the server's problem
// type and detail, which name what went wrong, not the secrets involved.
import { createHash, createPublicKey, generateKeyPairSync, sign, X509Certificate, type KeyObject } from 'node:crypto'
import { request as httpsRequest } from 'node:https'
import { rootCertificates } from 'node:tls'

/** Let's Encrypt's two directories. Staging issues certificates no browser trusts, with far looser limits. */
export const LETS_ENCRYPT = {
  production: 'https://acme-v02.api.letsencrypt.org/directory',
  staging: 'https://acme-staging-v02.api.letsencrypt.org/directory',
} as const

// ── Transport ───────────────────────────────────────────────────────────

export type HttpReply = { status: number; headers: Record<string, string>; body: Buffer }
export type Transport = (url: string, init: { method: 'GET' | 'HEAD' | 'POST'; headers?: Record<string, string>; body?: string }) => Promise<HttpReply>

/**
 * HTTPS only (RFC 8555 §6.1), the system's roots unless [ca] says otherwise (a test ACME server's
 * own certificate), 30 s per request from start to end (not only while idle), replies capped at
 * 1 MB (a certificate chain is a few KB); [signal] stops it (a release stopping a round).
 */
export function httpsTransport(opts: { ca?: string[]; timeoutMs?: number; userAgent?: string; signal?: AbortSignal } = {}): Transport {
  return (url, init) =>
    new Promise((resolve, reject) => {
      const u = new URL(url)
      if (u.protocol !== 'https:') return reject(new AcmeError('connection', 'The certificate authority must be reached over HTTPS.', 0))
      if (opts.signal?.aborted) return reject(new AcmeError('connection', 'Stopped.', 0))
      const headers: Record<string, string> = { 'user-agent': opts.userAgent ?? 'hypernova-daemon acme', ...(init.headers ?? {}) }
      if (init.body !== undefined) headers['content-length'] = String(Buffer.byteLength(init.body))
      const limit = opts.timeoutMs ?? 30_000
      const req = httpsRequest(u, { method: init.method, headers, ca: opts.ca, timeout: limit, signal: opts.signal }, (res) => {
        const chunks: Buffer[] = []
        let size = 0
        res.on('data', (c: Buffer) => {
          size += c.length
          if (size > 1 << 20) req.destroy(new Error('reply too large'))
          else chunks.push(c)
        })
        res.on('end', () => {
          const h: Record<string, string> = {}
          for (const [k, v] of Object.entries(res.headers)) if (v !== undefined) h[k.toLowerCase()] = Array.isArray(v) ? v.join(', ') : v
          resolve({ status: res.statusCode ?? 0, headers: h, body: Buffer.concat(chunks) })
        })
        res.on('error', reject)
      })
      const deadline = setTimeout(() => req.destroy(new Error('timed out')), limit)
      req.on('close', () => clearTimeout(deadline))
      req.on('timeout', () => req.destroy(new Error('timed out')))
      req.on('error', (e) => reject(new AcmeError('connection', `Couldn't reach the certificate authority (${e.message}).`, 0)))
      req.end(init.body)
    })
}

// ── Errors ──────────────────────────────────────────────────────────────

/**
 * A problem document's type without its `urn:ietf:params:acme:error:` prefix (`rateLimited`,
 * `badNonce`, `dns`, `unauthorized`, …), or ours: `connection` (no reply), `server` (a reply that
 * isn't a problem document), `invalid` (an authorization or order that failed), `timeout`.
 */
export class AcmeError extends Error {
  type: string
  status: number
  /** When the server says to try again (epoch ms), from Retry-After; null when it didn't say. */
  retryAt: number | null
  constructor(type: string, message: string, status: number, retryAt: number | null = null) {
    super(message)
    this.type = type
    this.status = status
    this.retryAt = retryAt
  }
}

/** Retry-After as an epoch time: seconds, or an HTTP date; null when absent or unreadable. */
export function retryAt(header: string | undefined, now = Date.now()): number | null {
  if (!header) return null
  const s = header.trim()
  if (/^\d+$/.test(s)) return now + Number(s) * 1000
  const t = Date.parse(s)
  return Number.isFinite(t) ? t : null
}

function problemOf(reply: HttpReply, now: number): AcmeError {
  let doc: { type?: string; detail?: string; subproblems?: { detail?: string; identifier?: { value?: string } }[] } = {}
  try {
    doc = JSON.parse(reply.body.toString('utf8'))
  } catch {}
  const type = typeof doc.type === 'string' ? doc.type.replace(/^urn:ietf:params:acme:error:/, '') : 'server'
  const sub = (doc.subproblems ?? []).map((p) => `${p.identifier?.value ?? ''}: ${p.detail ?? ''}`).join('; ')
  const detail = (typeof doc.detail === 'string' ? doc.detail : `HTTP ${reply.status}`) + (sub ? ` (${sub})` : '')
  return new AcmeError(type, detail.slice(0, 500), reply.status, retryAt(reply.headers['retry-after'], now))
}

// ── Encodings ───────────────────────────────────────────────────────────

export const b64url = (b: Buffer | string): string => Buffer.from(b).toString('base64url')

export type EcJwk = { crv: 'P-256'; kty: 'EC'; x: string; y: string }

/** The public half of an EC P-256 key as a JWK, with only the members RFC 7638 hashes. */
export function jwkOf(key: KeyObject): EcJwk {
  const j = createPublicKey(key).export({ format: 'jwk' }) as { crv?: string; kty?: string; x?: string; y?: string }
  if (j.kty !== 'EC' || j.crv !== 'P-256' || !j.x || !j.y) throw new Error('The ACME account key must be EC P-256.')
  return { crv: 'P-256', kty: 'EC', x: j.x, y: j.y }
}

/** RFC 7638: SHA-256 over the required members in lexicographic order, no whitespace. */
export function thumbprint(jwk: EcJwk): string {
  return b64url(createHash('sha256').update(`{"crv":"${jwk.crv}","kty":"${jwk.kty}","x":"${jwk.x}","y":"${jwk.y}"}`).digest())
}

/** What goes in the `_acme-challenge` TXT record for a DNS-01 challenge (RFC 8555 §8.4). */
export const dnsValue = (token: string, thumb: string): string => b64url(createHash('sha256').update(`${token}.${thumb}`).digest())

/** A fresh EC P-256 private key (accounts and certificates alike). */
export const newEcKey = (): KeyObject => generateKeyPairSync('ec', { namedCurve: 'P-256' }).privateKey

// DER, as little as a PKCS#10 request needs.
function derLength(n: number): Buffer {
  if (n < 0x80) return Buffer.from([n])
  const bytes: number[] = []
  for (let v = n; v > 0; v = Math.floor(v / 256)) bytes.unshift(v & 0xff)
  return Buffer.from([0x80 | bytes.length, ...bytes])
}
const der = (tag: number, ...parts: Buffer[]): Buffer => {
  const body = Buffer.concat(parts)
  return Buffer.concat([Buffer.from([tag]), derLength(body.length), body])
}
const seq = (...parts: Buffer[]) => der(0x30, ...parts)
const set = (...parts: Buffer[]) => der(0x31, ...parts)
function oid(dotted: string): Buffer {
  const [a, b, ...rest] = dotted.split('.').map(Number) as [number, number, ...number[]]
  const out = [40 * a + b]
  for (const n of rest) {
    const chunk = [n & 0x7f]
    for (let v = Math.floor(n / 128); v > 0; v = Math.floor(v / 128)) chunk.unshift((v & 0x7f) | 0x80)
    out.push(...chunk)
  }
  return der(0x06, Buffer.from(out))
}

/** A DNS name a certificate may carry: lowercase letters, digits, hyphens and dots, no wildcard. */
const HOSTNAME = /^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]$/

/**
 * A PKCS#10 certificate request (DER) for [hosts], signed with [key] (EC P-256, ECDSA with
 * SHA-256): the first host as the subject's CN when it fits (64 characters), every host in the
 * subjectAltName extension, which is what the CA actually reads.
 */
export function makeCsr(key: KeyObject, hosts: string[]): Buffer {
  if (!hosts.length || hosts.some((h) => !HOSTNAME.test(h))) throw new Error('A certificate request needs plain DNS names.')
  const spki = createPublicKey(key).export({ type: 'spki', format: 'der' })
  const subject = hosts[0]!.length <= 64 ? seq(set(seq(oid('2.5.4.3'), der(0x0c, Buffer.from(hosts[0]!, 'utf8'))))) : seq()
  const altNames = seq(...hosts.map((h) => der(0x82, Buffer.from(h, 'ascii'))))
  const extensions = seq(seq(oid('2.5.29.17'), der(0x04, altNames)))
  const attributes = der(0xa0, seq(oid('1.2.840.113549.1.9.14'), set(extensions)))
  const info = seq(Buffer.from([0x02, 0x01, 0x00]), subject, spki, attributes)
  const signature = sign('sha256', info, key)
  return seq(info, seq(oid('1.2.840.10045.4.3.2')), der(0x03, Buffer.from([0]), signature))
}

// ── Certificates ────────────────────────────────────────────────────────

/** The certificates in a PEM bundle, in order. */
export function splitPem(pem: string): string[] {
  return pem.match(/-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----/g)?.map((p) => p + '\n') ?? []
}

const rootCache = new WeakMap<readonly string[], X509Certificate[]>()
function parsedRoots(roots: readonly string[]): X509Certificate[] {
  let parsed = rootCache.get(roots)
  if (!parsed) {
    parsed = []
    for (const r of roots) {
      try {
        parsed.push(new X509Certificate(r))
      } catch {}
    }
    rootCache.set(roots, parsed)
  }
  return parsed
}

/**
 * Whether a browser would accept this chain: the leaf leads, through the chain's own
 * intermediates, to one of [roots] (Node's copy of Mozilla's, by default), every link signed by
 * the next and in date. Staging's and a test CA's chains end at roots nobody trusts, so they
 * answer false; that is the difference this is for.
 */
export function chainTrusted(chainPem: string, roots: readonly string[] = rootCertificates, now = Date.now()): boolean {
  let certs: X509Certificate[]
  try {
    certs = splitPem(chainPem).map((p) => new X509Certificate(p))
  } catch {
    return false
  }
  const inDate = (c: X509Certificate) => Date.parse(c.validFrom) <= now && now < Date.parse(c.validTo)
  const signs = (child: X509Certificate, parent: X509Certificate) => {
    try {
      return child.checkIssued(parent) && child.verify(parent.publicKey) && inDate(parent)
    } catch {
      return false
    }
  }
  let cur = certs[0]
  if (!cur || !inDate(cur)) return false
  const anchors = parsedRoots(roots)
  for (let depth = 0; depth < 6; depth++) {
    if (anchors.some((r) => signs(cur!, r))) return true
    const next = certs.find((c) => c !== cur && signs(cur!, c))
    if (!next) return false
    cur = next
  }
  return false
}

/** The DNS names a certificate covers (its subjectAltName), lowercase. */
export function certHosts(pem: string): string[] {
  try {
    const san = new X509Certificate(pem).subjectAltName ?? ''
    return san.split(/,\s*/).filter((s) => s.startsWith('DNS:')).map((s) => s.slice(4).toLowerCase())
  } catch {
    return []
  }
}

/**
 * When to renew: 30 days before the end, or a third of the lifetime for a shorter certificate
 * (Let's Encrypt's are 90 days today, heading for 45), so a renewal always has room for retries.
 */
export function renewAt(notBefore: number, notAfter: number): number {
  const day = 86_400_000
  const life = Math.max(0, notAfter - notBefore)
  // A "90-day" certificate runs 90 days less a second; anything from 89 days counts as one.
  return notAfter - (life >= 89 * day ? 30 * day : Math.floor(life / 3))
}

// ── The client ──────────────────────────────────────────────────────────

/** How the challenge records get published: through the name service, then checked in DNS. */
export type Dns01 = {
  publish(host: string, value: string): Promise<void>
  unpublish(host: string, value: string): Promise<void>
  /** Resolves once the record is visible where the CA will look (or gives up and lets the CA try). */
  visible(host: string, value: string): Promise<void>
}

type Directory = { newNonce: string; newAccount: string; newOrder: string; meta?: { termsOfService?: string } }
type Order = { status: string; authorizations: string[]; finalize: string; certificate?: string; error?: unknown }
type Authz = { status: string; identifier: { type: string; value: string }; challenges: { type: string; url: string; token: string; status: string; error?: { type?: string; detail?: string } }[]; wildcard?: boolean }

export type AcmeOptions = {
  directoryUrl: string
  /** The account key (EC P-256). */
  accountKey: KeyObject
  /** The account's URL from an earlier run with this key and directory; found or made when absent. */
  accountUrl?: string | null
  transport: Transport
  sleep?: (ms: number) => Promise<void>
  now?: () => number
  /** How long to wait for an authorization or an order to settle, each (ms). */
  pollLimitMs?: number
}

export class AcmeClient {
  accountUrl: string | null
  private o: AcmeOptions
  private dir: Directory | null = null
  private nonces: string[] = []
  private jwk: EcJwk
  private sleep: (ms: number) => Promise<void>
  private now: () => number

  constructor(o: AcmeOptions) {
    this.o = o
    this.accountUrl = o.accountUrl ?? null
    this.jwk = jwkOf(o.accountKey)
    this.sleep = o.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)))
    this.now = o.now ?? Date.now
  }

  /** The account key's thumbprint, which every key authorization ends with. */
  get thumbprint(): string {
    return thumbprint(this.jwk)
  }

  async directory(): Promise<Directory> {
    if (this.dir) return this.dir
    const r = await this.o.transport(this.o.directoryUrl, { method: 'GET' })
    if (r.status !== 200) throw problemOf(r, this.now())
    const d = parseJson<Directory>(r)
    if (!d.newNonce || !d.newAccount || !d.newOrder) throw new AcmeError('server', 'That is not an ACME directory.', r.status)
    return (this.dir = d)
  }

  private keepNonce(r: HttpReply): void {
    const n = r.headers['replay-nonce']
    if (n && /^[A-Za-z0-9_-]+$/.test(n)) this.nonces.push(n)
  }

  /**
   * Every address the directory and its replies hand out must be on the directory's own origin:
   * the CA's, which a reply can't point at another host (this network's router, say).
   */
  private ours(url: string): string {
    let ok = false
    try {
      ok = new URL(url).origin === new URL(this.o.directoryUrl).origin
    } catch {}
    if (!ok) throw new AcmeError('server', 'The certificate authority pointed at another host.', 0)
    return url
  }

  private async nonce(): Promise<string> {
    const kept = this.nonces.pop()
    if (kept) return kept
    const r = await this.o.transport(this.ours((await this.directory()).newNonce), { method: 'HEAD' })
    this.keepNonce(r)
    const n = this.nonces.pop()
    if (!n) throw problemOf(r, this.now())
    return n
  }

  /**
   * A signed request: [payload] as JSON, or null for POST-as-GET. The account's own key as `jwk`
   * for newAccount, its URL as `kid` for everything else. A `badNonce` is answered with a fresh
   * nonce in the same reply, so it's simply tried again (a few times).
   */
  private async post(url: string, payload: object | null, opts: { jwk?: boolean; accept?: string } = {}): Promise<HttpReply> {
    this.ours(url)
    for (let attempt = 0; ; attempt++) {
      const header: Record<string, unknown> = { alg: 'ES256', nonce: await this.nonce(), url }
      if (opts.jwk) header.jwk = this.jwk
      else {
        if (!this.accountUrl) throw new Error('No ACME account yet.')
        header.kid = this.accountUrl
      }
      const protectedB64 = b64url(JSON.stringify(header))
      const payloadB64 = payload === null ? '' : b64url(JSON.stringify(payload))
      const signature = sign('sha256', Buffer.from(`${protectedB64}.${payloadB64}`), { key: this.o.accountKey, dsaEncoding: 'ieee-p1363' })
      const r = await this.o.transport(url, {
        method: 'POST',
        headers: { 'content-type': 'application/jose+json', ...(opts.accept ? { accept: opts.accept } : {}) },
        body: JSON.stringify({ protected: protectedB64, payload: payloadB64, signature: b64url(signature) }),
      })
      this.keepNonce(r)
      if (r.status < 400) return r
      const err = problemOf(r, this.now())
      if (err.type === 'badNonce' && attempt < 4) continue
      throw err
    }
  }

  /** Finds or makes the account for this key (no contact address; the CA's terms accepted). */
  async account(): Promise<string> {
    if (this.accountUrl) return this.accountUrl
    const r = await this.post((await this.directory()).newAccount, { termsOfServiceAgreed: true }, { jwk: true })
    const url = r.headers.location
    if (!url) throw new AcmeError('server', 'The certificate authority made no account.', r.status)
    return (this.accountUrl = this.ours(url))
  }

  /** Polls [url] (an authorization or an order) until [done] says so, as often as Retry-After asks. */
  private async poll<T extends { status: string }>(url: string, done: (x: T) => boolean): Promise<T> {
    const limit = this.now() + (this.o.pollLimitMs ?? 3 * 60_000)
    for (;;) {
      const r = await this.post(url, null)
      const x = parseJson<T>(r)
      if (done(x)) return x
      if (this.now() > limit) throw new AcmeError('timeout', `The certificate authority is still at "${x.status}" after three minutes.`, 0)
      const at = retryAt(r.headers['retry-after'], this.now())
      await this.sleep(Math.min(30_000, Math.max(1_000, at ? at - this.now() : 2_000)))
    }
  }

  /**
   * A certificate for [hosts] through DNS-01: an order, a challenge record per pending
   * authorization (all published, then all checked visible, then all answered), the
   * authorizations' outcome, the CSR with a fresh key, the chain. The challenge records are
   * removed again whatever happens. Returns PEM: the chain (leaf first) and its private key.
   */
  async issue(hosts: string[], dns: Dns01): Promise<{ cert: string; key: string }> {
    await this.account()
    let r: HttpReply
    try {
      r = await this.post((await this.directory()).newOrder, { identifiers: hosts.map((value) => ({ type: 'dns', value })) })
    } catch (e) {
      // An account the CA no longer knows (deactivated, or a staging reset): made again, once.
      if (!(e instanceof AcmeError && e.type === 'accountDoesNotExist')) throw e
      this.accountUrl = null
      await this.account()
      r = await this.post((await this.directory()).newOrder, { identifiers: hosts.map((value) => ({ type: 'dns', value })) })
    }
    const orderUrl = r.headers.location
    if (!orderUrl) throw new AcmeError('server', 'The certificate authority gave the order no address.', r.status)
    let order = parseJson<Order>(r)

    const published: { host: string; value: string }[] = []
    try {
      const pending: { authz: string; url: string; host: string; value: string }[] = []
      for (const authzUrl of order.authorizations ?? []) {
        const a = parseJson<Authz>(await this.post(authzUrl, null))
        // Only the names asked for: a challenge record is never published for anything else.
        if (a.identifier?.type !== 'dns' || !hosts.includes(a.identifier.value)) throw new AcmeError('server', 'The certificate authority asked about a name that wasn’t requested.', 0)
        if (a.status === 'valid') continue
        if (a.status !== 'pending') throw new AcmeError('invalid', `The authorization for ${a.identifier?.value} is ${a.status}.`, 0)
        const ch = a.challenges.find((c) => c.type === 'dns-01')
        if (!ch) throw new AcmeError('invalid', `No DNS check offered for ${a.identifier.value}.`, 0)
        const value = dnsValue(ch.token, this.thumbprint)
        await dns.publish(a.identifier.value, value)
        published.push({ host: a.identifier.value, value })
        pending.push({ authz: authzUrl, url: ch.url, host: a.identifier.value, value })
      }
      for (const p of pending) await dns.visible(p.host, p.value)
      for (const p of pending) await this.post(p.url, {})
      for (const p of pending) {
        const a = await this.poll<Authz>(p.authz, (x) => x.status !== 'pending')
        if (a.status !== 'valid') {
          const why = a.challenges.find((c) => c.type === 'dns-01')?.error
          throw new AcmeError(why?.type?.replace(/^urn:ietf:params:acme:error:/, '') ?? 'invalid', `The DNS check for ${p.host} failed: ${why?.detail ?? a.status}.`, 0)
        }
      }
    } finally {
      for (const p of published) await dns.unpublish(p.host, p.value).catch(() => {})
    }

    order = await this.poll<Order>(orderUrl, (x) => x.status !== 'pending')
    const key = newEcKey()
    if (order.status === 'ready') {
      await this.post(order.finalize, { csr: b64url(makeCsr(key, hosts)) })
      order = await this.poll<Order>(orderUrl, (x) => x.status !== 'ready' && x.status !== 'processing')
    }
    if (order.status !== 'valid' || !order.certificate) throw new AcmeError('invalid', `The order ended as "${order.status}".`, 0)
    const chain = (await this.post(order.certificate, null, { accept: 'application/pem-certificate-chain' })).body.toString('utf8')
    const leaf = splitPem(chain)[0]
    if (!leaf) throw new AcmeError('server', 'The certificate authority sent no certificate.', 0)
    const x = new X509Certificate(leaf)
    const covered = certHosts(leaf)
    if (!x.checkPrivateKey(key) || hosts.some((h) => !covered.includes(h))) throw new AcmeError('server', 'The certificate does not match what was asked for.', 0)
    return { cert: splitPem(chain).join(''), key: key.export({ type: 'pkcs8', format: 'pem' }).toString() }
  }
}

function parseJson<T>(r: HttpReply): T {
  try {
    return JSON.parse(r.body.toString('utf8')) as T
  } catch {
    throw new AcmeError('server', `The certificate authority's reply wasn't JSON (HTTP ${r.status}).`, r.status)
  }
}
