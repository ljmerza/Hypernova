// The name under hypernova.observer, the parts that need no network (names.ts, acme.ts, lan.ts):
// the copy of the name service's client, the SNI peek on real ClientHellos, the Wi-Fi pick, the
// CSR (checked by openssl), the DNS-01 value, trust in a chain, when to renew, Retry-After, how
// errors back off, the configuration, and the status before anything is registered. The round
// trip against the Worker and a real ACME server is names-integration.test.ts.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { createServer, type AddressInfo } from 'node:net'
import { connect as tlsConnect, rootCertificates } from 'node:tls'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { execFileSync } from 'node:child_process'
import { createHash, createPrivateKey, X509Certificate } from 'node:crypto'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-names-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
mkdirSync(process.env.HOME, { recursive: true })
const fakeClaude = join(scratch, 'claude-bin')
writeFileSync(fakeClaude, '#!/bin/sh\necho "9.9.9 (Claude Code)"\n')
chmodSync(fakeClaude, 0o755)
process.env.CLAUDE_BIN = fakeClaude
delete process.env.HYPERNOVA_NAMES_URL
delete process.env.HYPERNOVA_ACME_DIRECTORY

const here = dirname(fileURLToPath(import.meta.url))
const { clientHelloName, pickLanAddress } = await import('../src/lan.ts')
const acme = await import('../src/acme.ts')
const names = await import('../src/names.ts')
const { NameServiceError } = await import('../src/names-client.ts')
const OPENSSL = existsSync('/usr/bin/openssl') ? '/usr/bin/openssl' : 'openssl'

test('the daemon’s copy of the name service client is the reference client, byte for byte', (t) => {
  const reference = join(here, '..', '..', 'names', 'client.ts')
  if (!existsSync(reference)) return t.skip('no names/ in this checkout')
  // The Worker imports its signing scheme from names/client.ts; the daemon ships without names/,
  // so it carries a copy. Edit names/client.ts, then: cp names/client.ts daemon/src/names-client.ts
  assert.equal(readFileSync(join(here, '..', 'src', 'names-client.ts'), 'utf8'), readFileSync(reference, 'utf8'))
})

/** The first bytes a TLS client sends: Node's own, as a browser would, with [servername] or none. */
function clientHello(opts: { servername?: string; host?: string; maxVersion?: 'TLSv1.2' | 'TLSv1.3' }): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const srv = createServer((sock) => {
      sock.once('data', (d: Buffer) => {
        resolve(d)
        sock.destroy()
        srv.close()
      })
    })
    srv.listen(0, '127.0.0.1', () => {
      const port = (srv.address() as AddressInfo).port
      const c = tlsConnect({ host: opts.host ?? '127.0.0.1', port, servername: opts.servername, maxVersion: opts.maxVersion, rejectUnauthorized: false })
      c.on('error', () => {})
    })
    srv.on('error', reject)
  })
}

test('the SNI peek finds the name in a real ClientHello, none for an IP address, and waits for a split one', async () => {
  const named = await clientHello({ servername: 'l-k3x9mq2z7a.hypernova.observer' })
  assert.equal(clientHelloName(named), 'l-k3x9mq2z7a.hypernova.observer')
  // TLS 1.2 only, upper case and a trailing dot: the same host.
  assert.equal(clientHelloName(await clientHello({ servername: 'L-K3X9MQ2Z7A.Hypernova.Observer.', maxVersion: 'TLSv1.2' })), 'l-k3x9mq2z7a.hypernova.observer')
  // The phone app connects to the address: TLS names nothing then.
  assert.equal(clientHelloName(await clientHello({})), null)
  // Arriving in pieces: undefined until the record is whole.
  for (const cut of [1, 4, 5, 40, named.length - 1]) assert.equal(clientHelloName(named.subarray(0, cut)), undefined, String(cut))
  // Not TLS at all, or not a ClientHello: no name (the pinned listener refuses it as before).
  assert.equal(clientHelloName(Buffer.from('GET / HTTP/1.1\r\nHost: x\r\n\r\n')), null)
  const notHello = Buffer.from(named)
  notHello[5] = 0x02
  assert.equal(clientHelloName(notHello), null)
  // Lengths that run past the record: no name, no throw.
  const broken = Buffer.from(named)
  broken.writeUInt16BE(0xffff, 5 + 4 + 2 + 32 + 1 + broken[5 + 4 + 2 + 32]!)
  assert.equal(clientHelloName(broken), null)
})

test('the address a browser is sent to: Wi-Fi IPv4, not a gateway-looking .1, then anything', () => {
  assert.equal(pickLanAddress(['192.168.64.1', '192.168.0.149', 'fd12::5']), '192.168.0.149')
  assert.equal(pickLanAddress(['192.168.64.1']), '192.168.64.1')
  assert.equal(pickLanAddress(['fd12::5']), 'fd12::5')
  assert.equal(pickLanAddress([]), null)
})

test('the certificate request: openssl reads it, its signature checks, the names are in the SAN', () => {
  const key = acme.newEcKey()
  const hosts = ['l-k3x9mq2z7a.hypernova.observer']
  const der = acme.makeCsr(key, hosts)
  const file = join(scratch, 'req.der')
  writeFileSync(file, der)
  const text = execFileSync(OPENSSL, ['req', '-inform', 'DER', '-in', file, '-noout', '-text', '-verify'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
  assert.match(text, /Subject: ?\/?CN ?= ?l-k3x9mq2z7a\.hypernova\.observer/)
  assert.match(text, /DNS:l-k3x9mq2z7a\.hypernova\.observer/)
  assert.match(text, /ecdsa-with-SHA256/)
  const verified = execFileSync(OPENSSL, ['req', '-inform', 'DER', '-in', file, '-noout', '-verify'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
  assert.ok(/verify OK/.test(verified) || verified === '', verified)
  // Two names, and a first one too long for a CN: no CN, both in the SAN.
  const long = 'l-' + 'a'.repeat(60) + '.hypernova.observer'
  writeFileSync(file, acme.makeCsr(key, [long, 'k3x9mq2z7a.hypernova.observer']))
  const two = execFileSync(OPENSSL, ['req', '-inform', 'DER', '-in', file, '-noout', '-text'], { encoding: 'utf8' })
  assert.ok(two.includes(`DNS:${long}`) && two.includes('DNS:k3x9mq2z7a.hypernova.observer'))
  assert.ok(!/CN ?=/.test(two))
  for (const bad of [[], ['*.hypernova.observer'], ['UPPER.example'], ['bad_name.example'], ['x']]) assert.throws(() => acme.makeCsr(key, bad), String(bad))
})

test('the DNS-01 value and the key thumbprint', () => {
  const key = createPrivateKey(acme.newEcKey().export({ type: 'pkcs8', format: 'pem' }))
  const jwk = acme.jwkOf(key)
  assert.deepEqual(Object.keys(jwk), ['crv', 'kty', 'x', 'y'])
  const thumb = acme.thumbprint(jwk)
  assert.match(thumb, /^[A-Za-z0-9_-]{43}$/)
  const expected = createHash('sha256').update(JSON.stringify({ crv: 'P-256', kty: 'EC', x: jwk.x, y: jwk.y })).digest('base64url')
  assert.equal(thumb, expected)
  assert.equal(acme.dnsValue('tok_en-1', thumb), createHash('sha256').update(`tok_en-1.${thumb}`).digest('base64url'))
  // An Ed25519 or RSA key is refused for the account.
  assert.throws(() => acme.jwkOf(createPrivateKey(execFileSync(OPENSSL, ['genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:1024'], { stdio: ['ignore', 'pipe', 'ignore'] }).toString())))
})

/** A root, an intermediate and a leaf for [host], made by openssl. */
function makeChain(host: string): { root: string; chain: string; leafOnly: string } {
  const d = mkdtempSync(join(scratch, 'chain-'))
  const f = (n: string) => join(d, n)
  const run = (args: string[]) => execFileSync(OPENSSL, args, { stdio: ['ignore', 'pipe', 'pipe'] })
  for (const k of ['root', 'int', 'leaf']) run(['ecparam', '-genkey', '-name', 'prime256v1', '-noout', '-out', f(`${k}.key`)])
  run(['req', '-new', '-x509', '-key', f('root.key'), '-out', f('root.crt'), '-days', '30', '-subj', '/CN=Test Root', '-addext', 'basicConstraints=critical,CA:TRUE', '-addext', 'keyUsage=critical,keyCertSign,cRLSign'])
  writeFileSync(f('ca.ext'), 'basicConstraints=critical,CA:TRUE,pathlen:0\nkeyUsage=critical,keyCertSign,cRLSign\n')
  run(['req', '-new', '-key', f('int.key'), '-out', f('int.csr'), '-subj', '/CN=Test Intermediate'])
  run(['x509', '-req', '-in', f('int.csr'), '-CA', f('root.crt'), '-CAkey', f('root.key'), '-set_serial', '2', '-days', '20', '-extfile', f('ca.ext'), '-out', f('int.crt')])
  writeFileSync(f('leaf.ext'), `basicConstraints=critical,CA:FALSE\nsubjectAltName=DNS:${host}\n`)
  run(['req', '-new', '-key', f('leaf.key'), '-out', f('leaf.csr'), '-subj', `/CN=${host}`])
  run(['x509', '-req', '-in', f('leaf.csr'), '-CA', f('int.crt'), '-CAkey', f('int.key'), '-set_serial', '3', '-days', '10', '-extfile', f('leaf.ext'), '-out', f('leaf.crt')])
  const r = (n: string) => readFileSync(f(n), 'utf8')
  return { root: r('root.crt'), chain: r('leaf.crt') + r('int.crt'), leafOnly: r('leaf.crt') }
}

test('a chain is trusted only when it leads to a known root, in date', () => {
  const host = 'l-k3x9mq2z7a.hypernova.observer'
  const { root, chain, leafOnly } = makeChain(host)
  assert.equal(acme.chainTrusted(chain, [root]), true)
  // Staging's, or a test CA's, against the real roots: not trusted.
  assert.equal(acme.chainTrusted(chain), false)
  assert.equal(acme.chainTrusted(chain, rootCertificates), false)
  // The intermediate missing: no path.
  assert.equal(acme.chainTrusted(leafOnly, [root]), false)
  // Past the leaf's end, or before it starts.
  const leaf = new X509Certificate(leafOnly)
  assert.equal(acme.chainTrusted(chain, [root], Date.parse(leaf.validTo) + 1000), false)
  assert.equal(acme.chainTrusted(chain, [root], Date.parse(leaf.validFrom) - 86_400_000), false)
  assert.equal(acme.chainTrusted('not a certificate', [root]), false)
  assert.deepEqual(acme.certHosts(chain), [host])
  assert.equal(acme.splitPem(chain).length, 2)
})

test('renewal: 30 days before the end of a 90-day certificate, a third of the life of a shorter one', () => {
  const day = 86_400_000
  assert.equal(acme.renewAt(0, 90 * day), 60 * day)
  assert.equal(acme.renewAt(0, 90 * day - 1000), 60 * day - 1000)
  assert.equal(acme.renewAt(0, 45 * day), 30 * day)
  assert.equal(acme.renewAt(0, 6 * day), 4 * day)
  assert.equal(acme.renewAt(0, 365 * day), 335 * day)
})

test('Retry-After: seconds or a date', () => {
  const now = Date.parse('2026-10-18T00:00:00Z')
  assert.equal(acme.retryAt('120', now), now + 120_000)
  assert.equal(acme.retryAt('Sun, 18 Oct 2026 01:00:00 GMT', now), now + 3_600_000)
  assert.equal(acme.retryAt('soon', now), null)
  assert.equal(acme.retryAt(undefined, now), null)
})

test('what goes wrong backs off: as long as a limit says, doubling for silence, an hour for a clock', () => {
  const now = 1_000_000_000_000
  const H = 3_600_000
  const limited = names.classify(new NameServiceError(429, 'too_many_registrations', 'This address registered enough names for today.', 7200), 'service', now)
  assert.equal(limited.kind, 'rate')
  assert.equal(names.nextAttempt(limited, 1, 'service', now), now + 7_200_000)
  // A limit with no Retry-After waits an hour; never less than a minute.
  assert.equal(names.nextAttempt({ kind: 'rate', retryAt: null, message: '' }, 1, 'service', now), now + H)
  assert.equal(names.nextAttempt({ kind: 'rate', retryAt: now + 5, message: '' }, 1, 'service', now), now + 60_000)
  const clock = names.classify(new NameServiceError(401, 'expired', 'The timestamp is outside the 5 minute window', null), 'service', now)
  assert.equal(clock.kind, 'clock')
  assert.match(clock.message, /clock/)
  assert.equal(names.nextAttempt(clock, 3, 'service', now), now + H)
  const down = names.classify(new TypeError('fetch failed'), 'service', now)
  assert.equal(down.kind, 'unreachable')
  assert.equal(down.message, 'Couldn’t reach the name service.')
  assert.deepEqual([1, 2, 3, 7, 30].map((n) => names.nextAttempt(down, n, 'service', now) - now), [60_000, 120_000, 240_000, H, H])
  assert.deepEqual([1, 2, 8].map((n) => names.nextAttempt(down, n, 'acme', now) - now), [300_000, 600_000, 6 * H])
  assert.equal(names.classify(new NameServiceError(502, 'dns_upstream', 'x'), 'service', now).kind, 'server')
  assert.equal(names.classify(new NameServiceError(400, 'lan_not_private', 'x'), 'service', now).kind, 'refused')
  const caLimit = names.classify(new acme.AcmeError('rateLimited', 'too many certificates', 429, now + 86_400_000), 'acme', now)
  assert.equal(names.nextAttempt(caLimit, 1, 'acme', now), now + 86_400_000)
  const failedCheck = names.classify(new acme.AcmeError('dns', 'No TXT record found', 400), 'acme', now)
  assert.equal(failedCheck.kind, 'invalid')
  assert.deepEqual([1, 2, 9].map((n) => names.nextAttempt(failedCheck, n, 'acme', now) - now), [H, 2 * H, 24 * H])
  assert.equal(names.classify(new acme.AcmeError('connection', 'x', 0), 'acme', now).message, 'Couldn’t reach Let’s Encrypt.')
})

test('the configuration: no name service unless one is configured (never hypernova.observer by default), production certificates unless said otherwise', () => {
  assert.equal(names.envConfig({ HYPERNOVA_SUPPORT: '/tmp/x' }).serviceUrl, null)
  // Since 2026-10-30 an unset nameServiceUrl is no service at all (docs/DECISIONS.md, "Pairing is local only").
  assert.equal(names.envConfig({}).serviceUrl, null)
  assert.notEqual(names.envConfig({}).serviceUrl, names.DEFAULT_SERVICE)
  assert.equal(names.envConfig({ HYPERNOVA_NAMES_URL: 'off' }).serviceUrl, null)
  assert.equal(names.envConfig({ HYPERNOVA_NAMES_URL: 'https://names.example/' }).serviceUrl, 'https://names.example')
  assert.equal(names.envConfig({ HYPERNOVA_SUPPORT: '/tmp/x', HYPERNOVA_NAMES_URL: 'http://127.0.0.1:9/' }).serviceUrl, 'http://127.0.0.1:9')
  assert.equal(names.DEFAULT_DIRECTORY, 'production')
  assert.equal(names.envConfig({}).directoryUrl, acme.LETS_ENCRYPT.production)
  assert.equal(names.directoryUrl('production'), acme.LETS_ENCRYPT.production)
  assert.equal(names.directoryUrl('https://127.0.0.1:14000/dir'), 'https://127.0.0.1:14000/dir')
  assert.equal(names.directoryUrl('http://plain.example/dir'), acme.LETS_ENCRYPT.production)
  assert.equal(names.directoryLabel(acme.LETS_ENCRYPT.production), 'production')
  assert.equal(names.directoryLabel('https://127.0.0.1:14000/dir'), 'custom')
})

test('the challenge record check gives up quietly when nothing answers, and lets the CA look', async () => {
  // A DNS server that takes every query and answers none (a network that drops DNS to the outside).
  const { createSocket } = await import('node:dgram')
  const silent = createSocket('udp4')
  await new Promise<void>((r) => silent.bind(0, '127.0.0.1', r))
  try {
    const t0 = Date.now()
    await names.waitForTxt('_acme-challenge.l-x.hypernova.observer', 'v', 'hypernova.observer', [`127.0.0.1:${silent.address().port}`], 200, async () => {})
    assert.ok(Date.now() - t0 < 20_000)
  } finally {
    silent.close()
  }
})

test('without a name service the keeper is off and says why; the status before it starts is off too', () => {
  const k = new names.NameKeeper(join(scratch, 'k1'), () => ({ ...names.envConfig({ HYPERNOVA_SUPPORT: 'x' }), lanAddress: () => '192.168.77.7' }), () => {})
  const s = k.status()
  assert.equal(s.enabled, false)
  assert.equal(s.state, 'off')
  assert.equal(s.inUse, false)
  assert.match(s.reason, /HYPERNOVA_NAMES_URL/)
  assert.equal(k.route(), null)
  assert.equal(k.origin(), null)
  assert.deepEqual({ ...names.nameStatus(), reason: '' }, { enabled: false, setting: true, state: 'off', name: null, host: null, address: null, showing: false, resolves: null, certificate: null, inUse: false, reason: '', lastError: null, nextAttemptAt: null, service: null, terms: 'https://letsencrypt.org/repository/', termsShown: true })
  assert.equal(names.namedOrigin(), null)
})

test('the ACME client against a scripted server: a refused nonce is retried with the fresh one, a rate limit says when', async () => {
  const sent: { url: string; nonce?: string; kid?: string; jwk?: boolean }[] = []
  let nonce = 0
  let refusedOnce = false
  const reply = (status: number, body: unknown, headers: Record<string, string> = {}) => ({ status, headers: { 'replay-nonce': `n${++nonce}`, ...headers }, body: Buffer.from(JSON.stringify(body)) })
  const transport: import('../src/acme.ts').Transport = async (url, init) => {
    if (url.endsWith('/dir')) return reply(200, { newNonce: 'https://ca.test/nonce', newAccount: 'https://ca.test/acct', newOrder: 'https://ca.test/order' })
    if (url.endsWith('/nonce')) return reply(200, '')
    const jws = JSON.parse(init.body!)
    const header = JSON.parse(Buffer.from(jws.protected, 'base64url').toString())
    sent.push({ url, nonce: header.nonce, kid: header.kid, jwk: !!header.jwk })
    assert.equal(header.alg, 'ES256')
    assert.equal(header.url, url)
    if (url.endsWith('/acct')) {
      if (!refusedOnce) {
        refusedOnce = true
        return reply(400, { type: 'urn:ietf:params:acme:error:badNonce', detail: 'stale' })
      }
      return reply(201, { status: 'valid' }, { location: 'https://ca.test/acct/7' })
    }
    return reply(429, { type: 'urn:ietf:params:acme:error:rateLimited', detail: 'too many new orders' }, { 'retry-after': '3600' })
  }
  const now = Date.now()
  const client = new acme.AcmeClient({ directoryUrl: 'https://ca.test/dir', accountKey: acme.newEcKey(), transport, now: () => now })
  const dns = { publish: async () => {}, unpublish: async () => {}, visible: async () => {} }
  const err = await client.issue(['l-k3x9mq2z7a.hypernova.observer'], dns).catch((e) => e)
  assert.ok(err instanceof acme.AcmeError)
  assert.equal(err.type, 'rateLimited')
  assert.equal(err.retryAt, now + 3_600_000)
  assert.equal(client.accountUrl, 'https://ca.test/acct/7')
  // newAccount twice (the second with the nonce the refusal carried), by key; the order by account URL.
  assert.deepEqual(sent.map((s) => [s.url.replace('https://ca.test/', ''), s.jwk, s.kid ?? null]), [['acct', true, null], ['acct', true, null], ['order', false, 'https://ca.test/acct/7']])
  assert.equal(sent[1]!.nonce, 'n3')
})


// ── A scripted name service, for what the real one never does ─────────────

type Call = { method: string; url: string; body: string }
type Answer = { status: number; body?: unknown; headers?: Record<string, string> } | 'hang'
async function fakeService(answer: (c: Call) => Answer): Promise<{ url: string; calls: Call[]; close: () => void }> {
  const { createServer: http } = await import('node:http')
  const calls: Call[] = []
  const srv = http(async (req, res) => {
    const chunks: Buffer[] = []
    for await (const c of req) chunks.push(c as Buffer)
    const call = { method: req.method ?? '', url: req.url ?? '', body: Buffer.concat(chunks).toString() }
    calls.push(call)
    const a = answer(call)
    if (a === 'hang') return
    res.writeHead(a.status, { 'content-type': 'application/json', ...(a.headers ?? {}) })
    res.end(typeof a.body === 'string' ? a.body : JSON.stringify(a.body ?? {}))
  })
  await new Promise<void>((r) => srv.listen(0, '127.0.0.1', r))
  return { url: `http://127.0.0.1:${(srv.address() as AddressInfo).port}`, calls, close: () => (srv.closeAllConnections(), srv.close()) }
}
const reg = (name: string) => ({ status: 201, body: { name, fqdn: `${name}.hypernova.observer`, lanFqdn: `l-${name}.hypernova.observer`, domain: 'hypernova.observer' } })
const { settings } = await import('../src/store.ts')
settings.lanAccess = true
// The tests below play a daemon whose Setup has shown what the name is (the first test checks the gate).
settings.nameTermsShown = true
const keeperFor = (dir: string, over: Partial<import('../src/names.ts').NamesConfig>) =>
  new names.NameKeeper(join(scratch, dir), () => ({ ...names.envConfig({}), lanAddress: () => '192.168.77.30', settleMs: 50, checkDelayMs: 50, lingerMs: 60_000, ...over }), () => {})

test('a service that publishes the public address anyway: the name is given up at once, no certificate is asked for', async () => {
  const svc = await fakeService((c) => {
    if (c.url === '/v1/register') return reg('oldw1')
    if (c.url.endsWith('/address')) return { status: 200, body: { name: 'oldw1', fqdn: 'oldw1.hypernova.observer', public: { type: 'A', address: '203.0.113.9' }, lanFqdn: 'l-oldw1.hypernova.observer', lan: ['192.168.77.30'] } }
    if (c.method === 'DELETE' && c.url === '/v1/names/oldw1') return { status: 200, body: { released: 'oldw1' } }
    return { status: 404, body: { error: 'not_found' } }
  })
  const k = keeperFor('oldworker', { serviceUrl: svc.url })
  try {
    k.want()
    await k.tick()
    assert.deepEqual(svc.calls.map((c) => `${c.method} ${c.url}`), ['POST /v1/register', 'PUT /v1/names/oldw1/address', 'DELETE /v1/names/oldw1'])
    assert.ok(JSON.parse(svc.calls[1]!.body).public === false)
    const s = k.status()
    assert.equal(s.enabled, false)
    assert.equal(s.state, 'off')
    assert.equal(s.address, null)
    assert.equal(s.name, null)
    assert.match(s.reason, /Redeploy the name service/)
    assert.ok(!existsSync(join(k.dir, 'name.json')) && !existsSync(join(k.dir, 'certificate.json')))
    // It stays given up: another round calls nothing, until the setting resumes it.
    await k.tick()
    assert.equal(svc.calls.length, 3)
    k.resume()
    assert.equal(k.status().enabled, true)
  } finally {
    k.stop()
    svc.close()
  }
})

test('the name service’s replies are checked: no redirects, a capped body, a registration naming only a name under the domain, clean messages', async () => {
  const open: { close: () => void }[] = []
  try {
    // A redirect elsewhere is refused, and nothing reaches the other place.
    const sink = await fakeService(() => ({ status: 200, body: {} }))
    open.push(sink)
    const redirecting = await fakeService(() => ({ status: 307, headers: { location: `${sink.url}/internal/admin` } }))
    open.push(redirecting)
    const r1 = keeperFor('redirect', { serviceUrl: redirecting.url })
    r1.want()
    await r1.tick()
    assert.equal(sink.calls.length, 0)
    assert.equal(r1.status().lastError?.message, 'Couldn’t reach the name service.')
    r1.stop()
    // A registration naming anything but <name>, l-<name> and the expected domain is refused, and nothing is stored.
    for (const bad of [
      { name: '../../admin?x=', fqdn: 'evil.example', lanFqdn: 'victim.example', domain: 'example' },
      { name: 'abc', fqdn: 'abc.hypernova.observer', lanFqdn: 'victim.example', domain: 'hypernova.observer' },
      { name: 'abc', fqdn: 'abc.evil.example', lanFqdn: 'l-abc.evil.example', domain: 'evil.example' },
    ]) {
      const svc = await fakeService((c) => (c.url === '/v1/register' ? { status: 201, body: bad } : { status: 500 }))
      open.push(svc)
      const k = keeperFor('badreg' + svc.url.slice(-5), { serviceUrl: svc.url })
      k.want()
      await k.tick()
      assert.deepEqual(svc.calls.map((c) => c.url), ['/v1/register'], JSON.stringify(bad))
      assert.match(k.status().lastError?.message ?? '', /something other than a name under hypernova\.observer/)
      assert.equal(k.status().name, null)
      assert.ok(!existsSync(join(k.dir, 'name.json')))
      k.stop()
      svc.close()
    }
    assert.throws(() => names.checkRegistration({ name: 'ABC', fqdn: 'ABC.hypernova.observer', lanFqdn: 'l-ABC.hypernova.observer', domain: 'hypernova.observer' }, 'hypernova.observer'))
    assert.deepEqual(names.checkRegistration(reg('k3x9mq2z7a').body, 'hypernova.observer'), reg('k3x9mq2z7a').body)
    assert.equal(names.domainOf('https://api.hypernova.observer'), 'hypernova.observer')
    assert.equal(names.domainOf('https://api.names.example.org'), 'names.example.org')
    assert.equal(names.domainOf('http://127.0.0.1:9'), 'hypernova.observer')
    // A huge reply is cut off unread; a long message full of control characters reaches the status short and clean.
    const huge = await fakeService(() => ({ status: 400, body: 'x'.repeat(1 << 20) }))
    open.push(huge)
    await assert.rejects(names.serviceFetch(huge.url + '/v1/register', { method: 'POST' }), /too large/)
    huge.close()
    open.push(await fakeService(() => ({ status: 200 })))
    const noisy = await fakeService(() => ({ status: 400, body: { error: 'bad', message: 'Line one\n\u001b[31mred‮' + 'y'.repeat(20_000) } }))
    open.push(noisy)
    const k = keeperFor('noisy', { serviceUrl: noisy.url })
    k.want()
    await k.tick()
    const msg = k.status().lastError!.message
    assert.ok(msg.length < 260, String(msg.length))
    assert.ok(!/[\u0000-\u001f‮]/.test(msg), msg)
    assert.match(msg, /^The name service refused: Line one \[31mred y+/)
    k.stop()
    noisy.close()
    redirecting.close()
    sink.close()
    assert.equal(names.clean('a\u0000b⁦c\n\nd', 4), 'a b')
  } finally {
    for (const o of open) o.close()
  }
})

test('a release that can’t reach the service keeps the key and tries again; a new service gives the old name up first', async () => {
  let down = true
  const svc = await fakeService((c) => {
    if (c.url === '/v1/register') return reg('keep1')
    if (c.url.endsWith('/address')) return { status: 200, body: { name: 'keep1', public: null, lan: ['192.168.77.30'] } }
    if (c.method === 'DELETE' && c.url === '/v1/names/keep1') return down ? { status: 503, body: { error: 'down', message: 'Down for a moment.' } } : { status: 200, body: { released: 'keep1' } }
    return { status: 404, body: { error: 'not_found' } }
  })
  const k = keeperFor('pending', { serviceUrl: svc.url, directoryUrl: 'https://127.0.0.1:1/dir' })
  try {
    k.want()
    await k.tick()
    assert.equal(k.status().name, 'keep1')
    const r = await k.release()
    assert.equal(r.released, false)
    const saved = JSON.parse(readFileSync(join(k.dir, 'name.json'), 'utf8'))
    assert.equal(saved.pendingRelease, true)
    assert.ok(saved.privateKey, 'the key stays, so the name can still be given up')
    assert.match(k.status().reason, /^Giving the name up: the name service couldn’t be reached, so l-keep1\.hypernova\.observer stays in DNS/)
    // Said first, whatever the setting: off doesn't hide a name still in DNS.
    settings.nameService = false
    assert.match(k.status().reason, /^Giving the name up/)
    delete settings.nameService
    assert.equal(k.route(), null)
    // Reachable again: the next round (past its wait) gives it up and forgets it.
    down = false
    const state = JSON.parse(readFileSync(join(k.dir, 'state.json'), 'utf8'))
    state.service.until = 0
    writeFileSync(join(k.dir, 'state.json'), JSON.stringify(state))
    const again = new names.NameKeeper(k.dir, () => ({ ...names.envConfig({}), serviceUrl: svc.url, lanAddress: () => '192.168.77.30', settleMs: 50, checkDelayMs: 50, lingerMs: 60_000 }), () => {})
    await again.tick()
    assert.ok(!existsSync(join(k.dir, 'name.json')))
    assert.equal(svc.calls.filter((c) => c.method === 'DELETE').length, 2)
    again.stop()

    // A name with one service, the setting now pointing at another: the old one is given up there first.
    const other = await fakeService((c) => (c.url === '/v1/register' ? reg('new22') : c.url.endsWith('/address') ? { status: 200, body: { public: null } } : { status: 404 }))
    const first = keeperFor('moved', { serviceUrl: svc.url, directoryUrl: 'https://127.0.0.1:1/dir' })
    first.want()
    await first.tick()
    first.stop()
    const before = svc.calls.length
    const moved = keeperFor('moved', { serviceUrl: other.url, directoryUrl: 'https://127.0.0.1:1/dir' })
    moved.want()
    await moved.tick()
    assert.deepEqual(svc.calls.slice(before).map((c) => `${c.method} ${c.url}`), ['DELETE /v1/names/keep1'])
    assert.equal(moved.status().name, 'new22')
    moved.stop()
    other.close()
  } finally {
    k.stop()
    svc.close()
  }
})

test('nothing is registered until a code is shown', async () => {
  const svc = await fakeService(() => ({ status: 500 }))
  const k = keeperFor('lazy', { serviceUrl: svc.url })
  await k.tick()
  assert.equal(svc.calls.length, 0)
  const s = k.status()
  assert.equal(s.state, 'idle')
  assert.equal(s.showing, false)
  assert.match(s.reason, /only while a download or pairing code is shown/)
  k.stop()
  svc.close()
})

test('the ACME client stays on the directory’s host and asks only about the names requested', async () => {
  const reply = (status: number, body: unknown, headers: Record<string, string> = {}) => ({ status, headers: { 'replay-nonce': 'n' + Math.random().toString(36).slice(2), ...headers }, body: Buffer.from(JSON.stringify(body)) })
  const asked: string[] = []
  const elsewhere: import('../src/acme.ts').Transport = async (url) => {
    asked.push(url)
    return reply(200, { newNonce: 'https://192.168.1.1/nonce', newAccount: 'https://ca.test/acct', newOrder: 'https://ca.test/order' })
  }
  const c1 = new acme.AcmeClient({ directoryUrl: 'https://ca.test/dir', accountKey: acme.newEcKey(), transport: elsewhere })
  const e1 = await c1.issue(['l-k3x9mq2z7a.hypernova.observer'], { publish: async () => {}, unpublish: async () => {}, visible: async () => {} }).catch((e) => e)
  assert.equal(e1.type, 'server')
  assert.deepEqual(asked, ['https://ca.test/dir'])

  const published: string[] = []
  const other: import('../src/acme.ts').Transport = async (url, init) => {
    if (url.endsWith('/dir')) return reply(200, { newNonce: 'https://ca.test/nonce', newAccount: 'https://ca.test/acct', newOrder: 'https://ca.test/order' })
    if (url.endsWith('/nonce')) return reply(200, '')
    if (url.endsWith('/acct')) return reply(201, {}, { location: 'https://ca.test/acct/1' })
    if (url.endsWith('/order')) return reply(201, { status: 'pending', authorizations: ['https://ca.test/authz/1'], finalize: 'https://ca.test/fin' }, { location: 'https://ca.test/order/1' })
    return reply(200, { status: 'pending', identifier: { type: 'dns', value: 'victim.example' }, challenges: [{ type: 'dns-01', url: 'https://ca.test/ch', token: 't' }] })
  }
  const c2 = new acme.AcmeClient({ directoryUrl: 'https://ca.test/dir', accountKey: acme.newEcKey(), transport: other })
  const e2 = await c2.issue(['l-k3x9mq2z7a.hypernova.observer'], { publish: async (h) => void published.push(h), unpublish: async () => {}, visible: async () => {} }).catch((e) => e)
  assert.equal(e2.type, 'server')
  assert.match(e2.message, /wasn’t requested/)
  assert.deepEqual(published, [])
})

test('a sleep a release can stop', async () => {
  const ctrl = new AbortController()
  const pause = names.abortableSleep(ctrl.signal)
  const t0 = Date.now()
  const p = pause(60_000)
  setTimeout(() => ctrl.abort(), 20)
  await assert.rejects(p)
  assert.ok(Date.now() - t0 < 2_000)
  await assert.rejects(pause(10))
})

test('nothing is made before the terms were shown, nor for a code shown while the name was off', async () => {
  const svc = await fakeService((c) => (c.url === '/v1/register' ? reg('gate1') : c.url.endsWith('/address') ? { status: 200, body: { public: null } } : { status: 404 }))
  const k = keeperFor('gate', { serviceUrl: svc.url, directoryUrl: 'https://127.0.0.1:1/dir' })
  try {
    settings.nameTermsShown = false
    k.want()
    await k.tick()
    assert.equal(svc.calls.length, 0)
    assert.equal(k.status().showing, false, 'a code before the terms doesn’t count')
    assert.match(k.status().reason, /says what the private name is first/)
    assert.equal(k.status().termsShown, false)
    // Off: a code shown meanwhile doesn't count once it's on again.
    settings.nameTermsShown = true
    settings.nameService = false
    k.want(Date.now() + 10 * 60_000)
    delete settings.nameService
    await k.tick()
    assert.equal(svc.calls.length, 0)
    assert.equal(k.status().showing, false)
    // Told, and a code shown: made.
    k.want()
    await k.tick()
    assert.deepEqual(svc.calls.map((c) => c.url), ['/v1/register', '/v1/names/gate1/address'])
  } finally {
    settings.nameTermsShown = true
    delete settings.nameService
    k.stop()
    svc.close()
  }
})

test('a round asked for while one runs follows it at once (an address change during a publish)', async () => {
  let slow = true
  const addresses: string[] = []
  const svc = await fakeService((c) => {
    if (c.url === '/v1/register') return reg('again1')
    if (c.url.endsWith('/address')) {
      addresses.push(JSON.parse(c.body).lan[0])
      return { status: 200, body: { public: null } }
    }
    return { status: 404 }
  })
  let address = '192.168.77.8'
  const k = new names.NameKeeper(join(scratch, 'again'), () => ({ ...names.envConfig({}), serviceUrl: svc.url, directoryUrl: 'https://127.0.0.1:1/dir', lanAddress: () => address, settleMs: 10, checkDelayMs: 50, lingerMs: 60_000, fetch: async (i, init) => { if (slow) await new Promise((r) => setTimeout(r, 300)); return names.serviceFetch(i, init) } }), () => {})
  try {
    k.want()
    const first = k.tick()
    await new Promise((r) => setTimeout(r, 100))
    // The address changes while the first publish is in flight; the settle fires a round now.
    address = '192.168.77.9'
    slow = false
    void k.tick()
    await first
    const t0 = Date.now()
    while (!addresses.includes('192.168.77.9') && Date.now() - t0 < 5_000) await new Promise((r) => setTimeout(r, 20))
    assert.deepEqual(addresses, ['192.168.77.8', '192.168.77.9'])
    assert.ok(Date.now() - t0 < 2_000, 'at once, not an hour later')
  } finally {
    k.stop()
    svc.close()
  }
})
