// The LAN listener (lan.ts, tls.ts; docs/PROTOCOL.md, "Local network (direct HTTPS)"): who gets
// in, the certificates made once and kept, mutual TLS gating, device certificates issued, replaced
// and run out, the snapshot's `lan`, the {t:"lan"} message, the pairing limits, a WebSocket over
// the listener with a client certificate, previews and the checklist there, and what the listener
// holds (connections, timeouts). The phone is played by Node's https client with the server's
// certificate pinned by fingerprint, as the app does.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { request as httpRequest } from 'node:http'
import { request as httpsRequest } from 'node:https'
import { connect as tcpConnect, createServer, type Socket } from 'node:net'
import { connect as tlsConnect, type TLSSocket } from 'node:tls'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { execFileSync } from 'node:child_process'
import { X509Certificate } from 'node:crypto'
import { WebSocket } from 'ws'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-lan-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
mkdirSync(process.env.HOME, { recursive: true })
const fakeClaude = join(scratch, 'claude-bin')
writeFileSync(fakeClaude, '#!/bin/sh\necho "9.9.9 (Claude Code)"\n')
chmodSync(fakeClaude, 0o755)
process.env.CLAUDE_BIN = fakeClaude

const freePort = () =>
  new Promise<number>((resolve) => {
    const s = createServer()
    s.listen(0, '127.0.0.1', () => {
      const port = (s.address() as { port: number }).port
      s.close(() => resolve(port))
    })
  })
const API = await freePort()
const LOCAL = await freePort()
process.env.HYPERNOVA_API_PORT = String(API)
process.env.HYPERNOVA_LOCAL_PORT = String(LOCAL)
process.env.HYPERNOVA_LAN_PORT = '0'
// Not WSL, whatever machine runs the test: behind WSL's NAT no address is advertised (test/wsl.test.ts).
process.env.HYPERNOVA_WSL = '0'

const { privateAddress, lanAddresses, pairingAddresses, pickLanAddress, pairAllowed, pairFailed, resetPairLimits, lanLimits, lanInfo, setLanAccess } = await import('../src/lan.ts')
const { serverIdentity, TLS_DIR } = await import('../src/tls.ts')
const { startServers, startPairing } = await import('../src/http.ts')
const { addDevice, adminToken, getDevice, setDeviceCert, settings } = await import('../src/store.ts')
// On, as the owner can say: unset, it's off in a container and on a cloud VM such as a CI runner (platform.ts lanByDefault).
settings.lanAccess = true

const TOKEN_A = 'lan-test-token-a-0123456789abcdef'
const TOKEN_B = 'lan-test-token-b-0123456789abcdef'
addDevice({ id: 'phoneA', name: 'Phone A', model: 'Pixel', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN_A)
addDevice({ id: 'phoneB', name: 'Phone B', model: 'Pixel', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN_B)

type Reply = { status: number; body: string; json: any; fingerprint: string | null }
function loop(port: number, method: string, path: string, headers: Record<string, string> = {}, body?: string): Promise<Reply> {
  return new Promise((resolve, reject) => {
    const req = httpRequest({ host: '127.0.0.1', port, method, path, headers: { host: `127.0.0.1:${port}`, 'content-type': 'application/json', ...headers } }, (res) => {
      let data = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (data += c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, body: data, json: tryJson(data), fingerprint: null }))
    })
    req.on('error', reject)
    req.end(body)
  })
}
const tryJson = (s: string) => {
  try {
    return JSON.parse(s)
  } catch {
    return null
  }
}

/** A phone's client key and certificate (PEM), once it has one. */
type Identity = { key: string; cert: string; fingerprint: string }
/** The phone: HTTPS to the LAN listener, the server's certificate pinned by fingerprint, its own certificate when it has one. */
function lan(port: number, method: string, path: string, opts: { token?: string; identity?: Identity; body?: string; headers?: Record<string, string>; host?: string } = {}): Promise<Reply> {
  return new Promise((resolve, reject) => {
    const host = opts.host ?? '127.0.0.1'
    const headers: Record<string, string> = { host: `${host.includes(':') ? `[${host}]` : host}:${port}`, 'content-type': 'application/json', ...(opts.headers ?? {}) }
    if (opts.token) headers.authorization = `Bearer ${opts.token}`
    const req = httpsRequest(
      { host, port, method, path, headers, rejectUnauthorized: false, key: opts.identity?.key, cert: opts.identity?.cert, agent: false },
      (res) => {
        const peer = (res.socket as import('node:tls').TLSSocket).getPeerCertificate()
        const fingerprint = peer?.raw ? new X509Certificate(peer.raw).fingerprint256.replace(/:/g, '').toLowerCase() : null
        let data = ''
        res.setEncoding('utf8')
        res.on('data', (c) => (data += c))
        res.on('end', () => resolve({ status: res.statusCode ?? 0, body: data, json: tryJson(data), fingerprint }))
      },
    )
    req.on('error', reject)
    req.end(opts.body)
  })
}

/** What the phone does: an EC P-256 key in its keystore and a CSR with CN=<deviceId>, as base64 DER. */
function csrFor(deviceId: string, curve = 'prime256v1'): { key: string; csr: string } {
  const dir = mkdtempSync(join(scratch, 'csr-'))
  const key = join(dir, 'key.pem')
  const out = join(dir, 'csr.der')
  execFileSync('/usr/bin/openssl', ['ecparam', '-genkey', '-name', curve, '-noout', '-out', key])
  execFileSync('/usr/bin/openssl', ['req', '-new', '-key', key, '-subj', `/CN=${deviceId}`, '-sha256', '-outform', 'DER', '-out', out])
  return { key: readFileSync(key, 'utf8'), csr: readFileSync(out).toString('base64') }
}
/** The whole dance over loopback: ask for the certificate with the bearer token, keep it. */
async function enroll(deviceId: string, token: string): Promise<Identity> {
  const { key, csr } = csrFor(deviceId)
  const r = await loop(API, 'POST', '/v1/device/cert', { authorization: `Bearer ${token}` }, JSON.stringify({ csr }))
  assert.equal(r.status, 200, r.body)
  const cert = new X509Certificate(Buffer.from(r.json.cert, 'base64'))
  return { key, cert: cert.toString(), fingerprint: cert.fingerprint256.replace(/:/g, '').toLowerCase() }
}

let close: () => Promise<void>
let LAN = 0
let FP = ''
before(async () => {
  close = startServers()
  for (let i = 0; i < 100; i++) {
    try {
      const r = await loop(API, 'GET', '/v1/snapshot', { authorization: `Bearer ${TOKEN_A}` })
      if (r.status === 200 && r.json.lan?.port) {
        LAN = r.json.lan.port
        FP = r.json.lan.fingerprint
        return
      }
    } catch {}
    await new Promise((r) => setTimeout(r, 30))
  }
  throw new Error('the LAN listener did not start')
})
after(async () => {
  await close?.()
  rmSync(scratch, { recursive: true, force: true })
})

test('only the local network, the hotspot, link-local and the tailnet get in; the internet is dropped at accept', () => {
  for (const ok of ['127.0.0.1', '10.0.2.2', '192.168.1.20', '172.16.0.9', '172.31.255.1', '169.254.3.4', '100.64.0.1', '100.127.255.254', '::1', 'fe80::1%en0', 'fd00::5', 'fc12::1', '::ffff:192.168.0.3']) assert.ok(privateAddress(ok), ok)
  for (const bad of ['8.8.8.8', '172.32.0.1', '172.15.0.1', '100.63.255.255', '100.128.0.0', '2001:db8::1', '::ffff:8.8.8.8', '', undefined, 'nonsense', '192.168.1']) assert.ok(!privateAddress(bad as string), String(bad))
})

test('the advertised addresses are private IPv4 and ULA only, never loopback, link-local or the tailnet', () => {
  for (const a of lanAddresses()) {
    assert.ok(privateAddress(a), a)
    assert.ok(!a.startsWith('127.') && !a.startsWith('169.254.') && !a.startsWith('100.') && a !== '::1' && !/^fe[89ab]/i.test(a) && !/^fd7a:115c:a1e0:/i.test(a), a)
  }
})

test('the tailnet’s 100.64/10 and fd7a:115c:a1e0::/48 are never advertised, however an interface writes them', () => {
  const at = (address: string, internal = false) => ({ address, family: address.includes(':') ? 'IPv6' : 'IPv4', internal, netmask: '', mac: '00:00:00:00:00:00', cidr: null, scopeid: 0 })
  const got = lanAddresses({
    en0: [at('192.168.1.20'), at('fe80::1c2b:3a4d:5e6f:7a8b%en0'), at('fd12:3456:789a::5')],
    // Tailscale's own interface: its IPv4 and its IPv6, compressed, in capitals, written out, with a zone.
    utun4: [at('100.101.102.103'), at('fd7a:115c:a1e0::1234:5678'), at('FD7A:115C:A1E0:AB12:4843:CD96:6258:B240'), at('fd7a:115c:a1e0:0:0:0:0:9'), at('fd7a:115c:a1e0::9%utun4')],
    utun5: [at('100.64.0.1'), at('100.127.255.254')],
    bridge100: [at('172.20.10.1'), at('10.0.0.2')],
    lo0: [at('127.0.0.1', true), at('::1', true)],
    // Next to the tailnet's /48 but outside it, a global address, a bare ULA, garbage.
    en5: [at('fd7a:115d::1'), at('fd7a:115c:a1e1::1'), at('2001:db8::1'), at('fc00::7'), at('fd7a::115c::1'), at('fdzz::1')],
  } as any)
  assert.deepEqual(got, ['192.168.1.20', '172.20.10.1', '10.0.0.2', 'fd12:3456:789a::5', 'fd7a:115d::1', 'fd7a:115c:a1e1::1', 'fc00::7'])
})

test('the server certificate and the device CA are made once, kept private, and the fingerprint is the SHA-256 of the DER', () => {
  const id = serverIdentity()!
  assert.ok(id)
  assert.equal((statSync(TLS_DIR).mode & 0o777).toString(8), '700')
  for (const f of ['server.key', 'server.crt', 'ca.key', 'ca.crt']) {
    assert.ok(existsSync(join(TLS_DIR, f)), f)
    assert.equal((statSync(join(TLS_DIR, f)).mode & 0o777).toString(8), '600', f)
  }
  const x = new X509Certificate(id.cert)
  assert.equal(id.fingerprint, x.fingerprint256.replace(/:/g, '').toLowerCase())
  assert.match(id.fingerprint, /^[0-9a-f]{64}$/)
  assert.equal(FP, id.fingerprint)
  // Ten years, P-256, self-signed.
  assert.ok(new Date(x.validTo).getTime() - Date.now() > 9.9 * 365 * 24 * 3600_000)
  assert.equal(x.publicKey.asymmetricKeyDetails?.namedCurve, 'prime256v1')
  assert.equal(x.issuer, x.subject)
  const ca = new X509Certificate(id.ca)
  assert.ok(ca.ca)
  // Asked again, the same identity comes back from the files, not a new one.
  const before = readFileSync(join(TLS_DIR, 'server.crt'), 'utf8')
  assert.equal(serverIdentity()!.fingerprint, id.fingerprint)
  assert.equal(readFileSync(join(TLS_DIR, 'server.crt'), 'utf8'), before)
})

test('the listener speaks TLS with the pinned certificate, and without a device certificate only health, pairing and the certificate route answer', async () => {
  const h = await lan(LAN, 'GET', '/v1/health')
  assert.equal(h.status, 200)
  assert.equal(h.fingerprint, FP)
  assert.equal(h.json.ok, true)
  // Pairing is reachable (closed right now, as no code is shown).
  const p = await lan(LAN, 'POST', '/v1/pair', { body: JSON.stringify({ code: 'x' }) })
  assert.equal(p.status, 403)
  assert.equal(p.json.error.code, 'pairing_closed')
  // Anything else: 401, even with a good bearer token.
  const s = await lan(LAN, 'GET', '/v1/snapshot', { token: TOKEN_A })
  assert.equal(s.status, 401)
  assert.equal(s.json.error.code, 'device_cert_required')
  assert.equal(s.json.error.message.includes('certificate'), true)
  const a = await lan(LAN, 'GET', '/v1/health', { headers: { 'access-control-request-method': 'GET' } })
  assert.equal(a.status, 200)
  // A browser's preflight gets nothing.
  const o = await lan(LAN, 'OPTIONS', '/v1/snapshot', { headers: { origin: 'https://plugin.example', 'access-control-request-method': 'GET' } })
  assert.equal(o.status, 404)
  // The Mac app's local API is not here.
  const l = await lan(LAN, 'GET', '/local/status', { token: adminToken })
  assert.equal(l.status, 401)
})

test('the snapshot says deviceCert false until the phone asks for its certificate, then true; the Mac app never', async () => {
  const s0 = await loop(API, 'GET', '/v1/snapshot', { authorization: `Bearer ${TOKEN_A}` })
  assert.deepEqual(Object.keys(s0.json.lan).sort(), ['addresses', 'deviceCert', 'fingerprint', 'port'])
  assert.equal(s0.json.lan.deviceCert, false)
  assert.equal(s0.json.lan.port, LAN)
  const id = await enroll('phoneA', TOKEN_A)
  assert.ok(id)
  const s1 = await loop(API, 'GET', '/v1/snapshot', { authorization: `Bearer ${TOKEN_A}` })
  assert.equal(s1.json.lan.deviceCert, true)
  const mac = await loop(LOCAL, 'GET', '/local/status', { authorization: `Bearer ${adminToken}` })
  assert.equal(mac.status, 200)
  assert.equal(mac.json.lan.deviceCert, false)
  assert.equal(mac.json.lan.fingerprint, FP)
  const other = await loop(API, 'GET', '/v1/snapshot', { authorization: `Bearer ${TOKEN_B}` })
  assert.equal(other.json.lan.deviceCert, false)
})

test('POST /v1/device/cert: a certificate from the device CA, CN the device id, two years, client auth; the request must be sound', async () => {
  const { csr } = csrFor('phoneB')
  const r = await loop(API, 'POST', '/v1/device/cert', { authorization: `Bearer ${TOKEN_B}` }, JSON.stringify({ csr }))
  assert.equal(r.status, 200, r.body)
  assert.equal(r.json.fingerprint, FP)
  const cert = new X509Certificate(Buffer.from(r.json.cert, 'base64'))
  const ca = new X509Certificate(Buffer.from(r.json.ca, 'base64'))
  assert.equal(cert.subject, 'CN=phoneB')
  assert.equal(cert.issuer, ca.subject)
  assert.ok(cert.verify(ca.publicKey))
  assert.ok(!cert.ca)
  assert.deepEqual(cert.keyUsage, ['1.3.6.1.5.5.7.3.2']) // clientAuth
  const days = (new Date(cert.validTo).getTime() - Date.now()) / 86400_000
  assert.ok(days > 729 && days < 731, String(days))
  assert.equal(ca.toString(), serverIdentity()!.ca)
  // Not a phone: the Mac app's admin token gets none.
  const m = await loop(API, 'POST', '/v1/device/cert', { authorization: `Bearer ${adminToken}` }, JSON.stringify({ csr }))
  assert.equal(m.status, 401)
  // Another device's name in the request, a wrong curve, garbage: 400 bad_csr, nothing issued.
  for (const [name, body] of [
    ['another CN', JSON.stringify({ csr: csrFor('phoneA').csr })],
    ['P-384', JSON.stringify({ csr: csrFor('phoneB', 'secp384r1').csr })],
    ['garbage', JSON.stringify({ csr: Buffer.from('0'.repeat(200)).toString('base64') })],
    ['empty', '{}'],
  ] as const) {
    const bad = await loop(API, 'POST', '/v1/device/cert', { authorization: `Bearer ${TOKEN_B}` }, body)
    assert.equal(bad.status, 400, name)
    assert.equal(bad.json.error.code, 'bad_csr', name)
  }
  // A tampered request (its signature no longer checks) is refused too.
  const der = Buffer.from(csr, 'base64')
  der[der.length - 3] ^= 0xff
  const t = await loop(API, 'POST', '/v1/device/cert', { authorization: `Bearer ${TOKEN_B}` }, JSON.stringify({ csr: der.toString('base64') }))
  assert.equal(t.status, 400)
  assert.equal(t.json.error.code, 'bad_csr')
  // The folder holds no leftovers from any of that.
  assert.ok(!readdirSync(TLS_DIR).some((f) => f.startsWith('csr-')))
})

test('with its certificate the phone reaches the whole API; the token must be the same device’s; a new certificate replaces the old', async () => {
  const idA = await enroll('phoneA', TOKEN_A)
  const s = await lan(LAN, 'GET', '/v1/snapshot', { token: TOKEN_A, identity: idA })
  assert.equal(s.status, 200, s.body)
  assert.equal(s.json.lan.deviceCert, true)
  assert.equal(s.fingerprint, FP)
  // The certificate alone doesn't stand in for the token on a token route.
  const noToken = await lan(LAN, 'GET', '/v1/snapshot', { identity: idA })
  assert.equal(noToken.status, 401)
  assert.equal(noToken.json.error.code, 'unauthorized')
  // Phone A's certificate with phone B's token: no.
  const mixed = await lan(LAN, 'GET', '/v1/snapshot', { token: TOKEN_B, identity: idA })
  assert.equal(mixed.status, 401)
  assert.equal(mixed.json.error.code, 'wrong_device')
  // The admin token counts for nothing on this listener, certificate or not.
  const admin = await lan(LAN, 'GET', '/v1/snapshot', { token: adminToken, identity: idA })
  assert.equal(admin.status, 401)
  // A second certificate for A: the first stops working at once.
  const idA2 = await enroll('phoneA', TOKEN_A)
  assert.notEqual(idA2.fingerprint, idA.fingerprint)
  const old = await lan(LAN, 'GET', '/v1/snapshot', { token: TOKEN_A, identity: idA })
  assert.equal(old.status, 401)
  assert.equal(old.json.error.code, 'device_cert_required')
  const fresh = await lan(LAN, 'GET', '/v1/snapshot', { token: TOKEN_A, identity: idA2 })
  assert.equal(fresh.status, 200)
  // The daemon keeps the fingerprint with the device, for the Mac's list.
  const devs = await loop(LOCAL, 'GET', '/local/devices', { authorization: `Bearer ${adminToken}` })
  assert.equal(devs.json.devices.find((d: any) => d.id === 'phoneA').certFingerprint, idA2.fingerprint)
  // A certificate from another CA (self-made) is not a device certificate.
  const dir = mkdtempSync(join(scratch, 'rogue-'))
  execFileSync('/usr/bin/openssl', ['ecparam', '-genkey', '-name', 'prime256v1', '-noout', '-out', join(dir, 'k.pem')])
  execFileSync('/usr/bin/openssl', ['req', '-new', '-x509', '-key', join(dir, 'k.pem'), '-subj', '/CN=phoneA', '-days', '2', '-out', join(dir, 'c.pem')])
  const rogue = await lan(LAN, 'GET', '/v1/snapshot', { token: TOKEN_A, identity: { key: readFileSync(join(dir, 'k.pem'), 'utf8'), cert: readFileSync(join(dir, 'c.pem'), 'utf8'), fingerprint: '' } })
  assert.equal(rogue.status, 401)
  assert.equal(rogue.json.error.code, 'device_cert_required')
})

test('the WebSocket over the listener needs the device certificate; the snapshot it opens with carries lan', async () => {
  const idB = await enroll('phoneB', TOKEN_B)
  const first = await new Promise<any>((resolve, reject) => {
    const ws = new WebSocket(`wss://127.0.0.1:${LAN}/v1/ws?token=${TOKEN_B}`, { rejectUnauthorized: false, key: idB.key, cert: idB.cert })
    ws.once('message', (m) => {
      resolve(JSON.parse(String(m)))
      ws.close()
    })
    ws.once('error', reject)
  })
  assert.equal(first.t, 'snapshot')
  assert.equal(first.snapshot.lan.deviceCert, true)
  assert.equal(first.snapshot.lan.port, LAN)
  // No certificate: refused at the upgrade, token or not.
  const refused = await new Promise<number>((resolve) => {
    const ws = new WebSocket(`wss://127.0.0.1:${LAN}/v1/ws?token=${TOKEN_B}`, { rejectUnauthorized: false })
    ws.once('unexpected-response', (_req, res) => resolve(res.statusCode ?? 0))
    ws.once('error', () => resolve(-1))
    ws.once('open', () => resolve(0))
  })
  assert.equal(refused, 401)
  // Another device's token with this certificate: refused too.
  const mixed = await new Promise<number>((resolve) => {
    const ws = new WebSocket(`wss://127.0.0.1:${LAN}/v1/ws?token=${TOKEN_A}`, { rejectUnauthorized: false, key: idB.key, cert: idB.cert })
    ws.once('unexpected-response', (_req, res) => resolve(res.statusCode ?? 0))
    ws.once('error', () => resolve(-1))
    ws.once('open', () => resolve(0))
  })
  assert.equal(mixed, 401)
})

test('forgetting a device revokes its certificate at once, on the connection it holds too', async () => {
  const TOKEN_C = 'lan-test-token-c-0123456789abcdef'
  addDevice({ id: 'phoneC', name: 'Phone C', model: 'Pixel', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN_C)
  const idC = await enroll('phoneC', TOKEN_C)
  // One keep-alive connection for both requests, so the second rides the handshake of the first.
  const { Agent } = await import('node:https')
  const agent = new Agent({ keepAlive: true, maxSockets: 1, rejectUnauthorized: false, key: idC.key, cert: idC.cert })
  const on = (method: string, path: string, token: string) =>
    new Promise<{ status: number; json: any; reused: boolean }>((resolve, reject) => {
      const req = httpsRequest({ host: '127.0.0.1', port: LAN, method, path, agent, headers: { authorization: `Bearer ${token}` } }, (res) => {
        let data = ''
        res.setEncoding('utf8')
        res.on('data', (c) => (data += c))
        res.on('end', () => resolve({ status: res.statusCode ?? 0, json: tryJson(data), reused: !!(req as any).reusedSocket }))
      })
      req.on('error', reject)
      req.end()
    })
  const ok = await on('GET', '/v1/snapshot', TOKEN_C)
  assert.equal(ok.status, 200)
  // Forgotten from the Mac's list.
  const del = await loop(LOCAL, 'DELETE', '/local/devices/phoneC', { authorization: `Bearer ${adminToken}` })
  assert.equal(del.json.ok, true)
  const gone = await on('GET', '/v1/snapshot', TOKEN_C)
  assert.equal(gone.status, 401)
  assert.equal(gone.reused, true, 'the same connection')
  agent.destroy()
  // And DELETE /v1/devices/me by the phone itself, over the listener with its certificate.
  const TOKEN_D = 'lan-test-token-d-0123456789abcdef'
  addDevice({ id: 'phoneD', name: 'Phone D', model: 'Pixel', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN_D)
  const idD = await enroll('phoneD', TOKEN_D)
  const me = await lan(LAN, 'DELETE', '/v1/devices/me', { token: TOKEN_D, identity: idD })
  assert.equal(me.status, 200)
  const after = await lan(LAN, 'GET', '/v1/snapshot', { token: TOKEN_D, identity: idD })
  assert.equal(after.status, 401)
  assert.equal(after.json.error.code, 'device_cert_required')
})

test('pairing over the listener: open while a code is shown, 5 wrong codes per source then a wait, a right code pairs and the links carry fp, lan and lp', async () => {
  resetPairLimits()
  const started = await startPairing(true)
  // Nothing follows the listener's port (the tailnet hints went with Tailscale).
  assert.match(started.appLink, new RegExp(`&fp=${FP}&lan=[^&]*&lp=${LAN}$`))
  const link = new URL(started.appLink)
  assert.equal(link.searchParams.get('fp'), FP)
  assert.equal(link.searchParams.get('lp'), String(LAN))
  const advertised = (link.searchParams.get('lan') ?? '').split(',').filter(Boolean)
  assert.deepEqual(advertised, pairingAddresses(lanAddresses()))
  // As the links write them: an IPv6 ULA's colons percent-encoded. (The page check below compared
  // the decoded list with the written one, so it failed on any machine with a ULA, such as z2.)
  const written = `&fp=${FP}&lan=${advertised.map(encodeURIComponent).join(',')}&lp=${LAN}`
  // The pairing page puts the daemon's own listener in the app link, whatever
  // its query says: the query is anyone's to write.
  const page = await loop(API, 'GET', `/pair?c=${started.code}&n=Mac`)
  assert.equal(page.status, 200)
  assert.ok(page.body.includes(written), 'the app link on the page')
  const odd = await loop(API, 'GET', `/pair?c=${started.code}&n=Mac&fp=<script>&lan=x&lp=1`)
  assert.ok(!odd.body.includes('fp=<script>') && !odd.body.includes('fp=&#60;script') && !odd.body.includes('lan=x'), 'the query is ignored')
  assert.ok(odd.body.includes(written), 'the daemon\'s own values instead')
  // Five wrong codes from this source: the sixth waits, right or wrong.
  for (let i = 0; i < 5; i++) {
    const w = await lan(LAN, 'POST', '/v1/pair', { body: JSON.stringify({ code: `wrong${i}`, deviceName: 'P' }) })
    assert.equal(w.status, 403, w.body)
    assert.equal(w.json.error.code, 'code_expired')
  }
  const sixth = await lan(LAN, 'POST', '/v1/pair', { body: JSON.stringify({ code: started.code, deviceName: 'P' }) })
  assert.equal(sixth.status, 429)
  assert.equal(sixth.json.error.code, 'too_many_tries')
  assert.ok(sixth.json.error.retryAfterSeconds > 0 && sixth.json.error.retryAfterSeconds <= 600)
  // The loopback phone API keeps today's rules: no limit, no "closed".
  resetPairLimits()
  const paired = await lan(LAN, 'POST', '/v1/pair', { body: JSON.stringify({ code: started.code, deviceName: 'Phone E', model: 'Pixel' }) })
  assert.equal(paired.status, 200, paired.body)
  assert.ok(paired.json.token && paired.json.deviceId)
  // Right after pairing: its certificate, over the listener, with the token alone (no certificate yet).
  const { key, csr } = csrFor(paired.json.deviceId)
  const c = await lan(LAN, 'POST', '/v1/device/cert', { token: paired.json.token, body: JSON.stringify({ csr }) })
  assert.equal(c.status, 200, c.body)
  const cert = new X509Certificate(Buffer.from(c.json.cert, 'base64'))
  const s = await lan(LAN, 'GET', '/v1/snapshot', { token: paired.json.token, identity: { key, cert: cert.toString(), fingerprint: '' } })
  assert.equal(s.status, 200)
  assert.equal(s.json.lan.deviceCert, true)
  // No code shown any more: closed.
  const closed = await lan(LAN, 'POST', '/v1/pair', { body: JSON.stringify({ code: 'x' }) })
  assert.equal(closed.json.error.code, 'pairing_closed')
})

test('no lock across sources: one peer’s wrong codes never hold another back, and the table stays bounded', async () => {
  resetPairLimits()
  const { pairAllowed, pairFailed } = await import('../src/lan.ts')
  // Over the listener: 127.0.0.1 used up its tries; the phone at ::1 still pairs with the right code.
  const started = await startPairing(true)
  for (let i = 0; i < 5; i++) assert.equal((await lan(LAN, 'POST', '/v1/pair', { body: JSON.stringify({ code: `wrong${i}` }) })).status, 403)
  assert.equal((await lan(LAN, 'POST', '/v1/pair', { body: JSON.stringify({ code: started.code }) })).status, 429)
  const v6 = await lan(LAN, 'POST', '/v1/pair', { host: '::1', body: JSON.stringify({ code: started.code, deviceName: 'Phone at ::1' }) })
  assert.equal(v6.status, 200, v6.body)
  // Many sources, each with its 5: every one of them waits, a new one doesn't.
  resetPairLimits()
  for (let i = 0; i < 300; i++) for (let j = 0; j < 5; j++) pairFailed(`10.0.${i >> 8}.${i & 255}`)
  assert.equal(pairAllowed('10.0.0.7').ok, false)
  assert.equal(pairAllowed('::ffff:10.0.1.20').ok, false)
  assert.equal(pairAllowed('192.168.1.50').ok, true)
  // Thousands of addresses: the oldest are forgotten (a few more tries at an unguessable code), the newest still wait.
  for (let i = 0; i < 3000; i++) for (let j = 0; j < 5; j++) pairFailed(`fd00::${i.toString(16)}`)
  assert.equal(pairAllowed('10.0.0.7').ok, true, 'forgotten')
  assert.equal(pairAllowed('fd00::0').ok, true, 'forgotten')
  assert.equal(pairAllowed(`fd00::${(2999).toString(16)}`).ok, false)
  resetPairLimits()
})

test('a phone on another private subnet (the owner’s VPN, 192.168.2.x, into a server on 192.168.1.x) gets in, with tries of its own', () => {
  // The accept filter is the private ranges, never "this computer's own subnet": a VPN's clients
  // come from a subnet of their own, routed to the LAN.
  for (const vpn of ['192.168.2.37', '::ffff:192.168.2.37', '10.8.0.2', '172.16.5.9', '100.64.10.2', 'fd42::2']) assert.ok(privateAddress(vpn), vpn)
  resetPairLimits()
  for (let i = 0; i < 5; i++) pairFailed('192.168.1.30')
  assert.equal(pairAllowed('192.168.1.30').ok, false)
  assert.equal(pairAllowed('192.168.2.37').ok, true, 'a VPN client is a source of its own')
  assert.equal(pairAllowed('::ffff:192.168.2.37').ok, true)
  resetPairLimits()
})

test('the addresses a pairing link carries: IPv4 whenever there is one (a smaller QR code), else the ULAs', () => {
  assert.deepEqual(pairingAddresses(['192.168.1.48', '192.168.1.76', 'fdae:adb2:db54:680a::5', 'fdae:adb2:db54:680a::6']), ['192.168.1.48', '192.168.1.76'])
  assert.deepEqual(pairingAddresses(['fd12::5']), ['fd12::5'])
  assert.deepEqual(pairingAddresses([]), [])
})

test('the pairing links point at this server on the local network (its listener), never hypernova.observer', async () => {
  const p = await startPairing()
  assert.ok(!JSON.stringify(p).includes('hypernova.observer'), JSON.stringify(p))
  const addresses = pairingAddresses(lanAddresses())
  const at = pickLanAddress(addresses)
  if (!at) {
    // No private address on this machine (a bare CI box): the app's own link, for Paste link.
    assert.equal(p.link, p.appLink)
    return
  }
  const origin = `https://${at.includes(':') ? `[${at}]` : at}:${LAN}`
  // The QR code and the printed link: this server's own pairing page, with the listener in it.
  assert.equal(p.link, p.webLink)
  assert.ok(p.link.startsWith(`${origin}/pair?c=${p.code}&n=`), p.link)
  const q = new URL(p.link).searchParams
  assert.equal(q.get('fp'), FP)
  assert.equal(q.get('lp'), String(LAN))
  assert.deepEqual(q.get('lan')!.split(','), addresses)
  // `u` is the listener too, so each server's pairing has its own base URL.
  assert.equal(new URL(p.appLink).searchParams.get('u'), origin)
  assert.equal(p.url, origin)
  assert.ok(p.download?.startsWith(`https://`) && p.download.includes(`:${LAN}/download/android`), p.download)
  // A development build's code keeps the emulator's address, and the app link.
  const dev = await startPairing(true)
  assert.equal(new URL(dev.appLink).searchParams.get('u'), `http://10.0.2.2:${API}`)
  assert.equal(dev.link, dev.appLink)
})

test('the pairing page on the listener (what the QR code opens): no device certificate, only for a code being shown, and it hands the app this listener', async () => {
  resetPairLimits()
  const p = await startPairing()
  const page = await lan(LAN, 'GET', `/pair?c=${p.code}&n=Box`)
  assert.equal(page.status, 200, page.body)
  assert.equal(page.fingerprint, FP)
  // The address the browser came in on (here 127.0.0.1), read from the socket.
  const here = `https://127.0.0.1:${LAN}`
  assert.ok(page.body.includes(`intent://pair?v=1&u=${encodeURIComponent(here)}&c=${p.code}&n=Box&fp=${FP}&lan=`), page.body)
  assert.ok(page.body.includes(`S.browser_fallback_url=${encodeURIComponent(`${here}/download/android`)}`), 'falls back to this server’s download page')
  assert.ok(!page.body.includes('hypernova.observer'))
  // A Host or X-Forwarded-Host header moves nothing.
  const moved = await lan(LAN, 'GET', `/pair?c=${p.code}`, { headers: { host: 'evil.example', 'x-forwarded-host': 'evil.example' } })
  assert.equal(moved.status, 200)
  assert.ok(!moved.body.includes('evil.example'), 'the socket’s address, not the request’s')
  // Without a code being shown there is no page.
  assert.equal((await lan(LAN, 'GET', '/pair?c=nope')).status, 404)
  assert.equal((await lan(LAN, 'GET', '/pair')).status, 404)
  // The page spends nothing: the app then pairs with the code, after which the page is gone.
  const paired = await lan(LAN, 'POST', '/v1/pair', { body: JSON.stringify({ code: p.code, deviceName: 'Phone from the page' }) })
  assert.equal(paired.status, 200, paired.body)
  assert.equal((await lan(LAN, 'GET', `/pair?c=${p.code}`)).status, 404)
})

test('lanAccess off closes the listener and tells the feed {t:"lan", lan: null}; on again, a new {t:"lan"}', async () => {
  const messages: any[] = []
  const ws = new WebSocket(`ws://127.0.0.1:${API}/v1/ws?token=${TOKEN_A}`)
  await new Promise<void>((resolve, reject) => {
    ws.on('message', (m) => {
      const msg = JSON.parse(String(m))
      messages.push(msg)
      if (msg.t === 'snapshot') resolve()
    })
    ws.once('error', reject)
  })
  const waitFor = (f: (m: any) => boolean) =>
    new Promise<any>((resolve, reject) => {
      const found = messages.find(f)
      if (found) return resolve(found)
      const t = setTimeout(() => reject(new Error('no message')), 3000)
      ws.on('message', (m) => {
        const msg = JSON.parse(String(m))
        if (f(msg)) {
          clearTimeout(t)
          resolve(msg)
        }
      })
    })
  const off = await loop(LOCAL, 'POST', '/local/settings', { authorization: `Bearer ${adminToken}` }, JSON.stringify({ lanAccess: false }))
  assert.equal(off.status, 200)
  assert.equal(off.json.lanAccess, false)
  const gone = await waitFor((m) => m.t === 'lan' && m.lan === null)
  assert.equal(gone.lan, null)
  const s = await loop(API, 'GET', '/v1/snapshot', { authorization: `Bearer ${TOKEN_A}` })
  assert.equal(s.json.lan, null)
  await assert.rejects(lan(LAN, 'GET', '/v1/health'), 'the port is closed')
  // The link keeps `fp` (the certificate the phone pins) but names no listener.
  const noLan = await startPairing(true)
  assert.ok(noLan.appLink.includes('&fp='))
  assert.ok(!noLan.appLink.includes('&lan=') && !noLan.appLink.includes('&lp='))
  messages.length = 0
  const on = await loop(LOCAL, 'POST', '/local/settings', { authorization: `Bearer ${adminToken}` }, JSON.stringify({ lanAccess: true }))
  assert.equal(on.json.lanAccess, true)
  const back = await waitFor((m) => m.t === 'lan' && m.lan)
  assert.equal(back.lan.fingerprint, FP)
  assert.equal(back.lan.deviceCert, undefined, 'not per device in the broadcast')
  assert.ok(back.lan.port > 0)
  LAN = back.lan.port
  const h = await lan(LAN, 'GET', '/v1/health')
  assert.equal(h.fingerprint, FP, 'the same identity after a restart of the listener')
  ws.close()
})

test('a new device certificate closes the WebSockets opened with the old one (4002); the phone’s other sockets stay', async () => {
  const TOKEN_F = 'lan-test-token-f-0123456789abcdef'
  addDevice({ id: 'phoneF', name: 'Phone F', model: 'Pixel', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN_F)
  const idF = await enroll('phoneF', TOKEN_F)
  /** A feed that has its snapshot, and how it closes. */
  const feed = (url: string, opts: object) =>
    new Promise<{ ws: WebSocket; closed: Promise<number> }>((resolve, reject) => {
      const ws = new WebSocket(url, opts)
      const closed = new Promise<number>((r) => ws.once('close', (code) => r(code)))
      ws.once('message', () => resolve({ ws, closed }))
      ws.once('error', reject)
    })
  const overLan = await feed(`wss://127.0.0.1:${LAN}/v1/ws?token=${TOKEN_F}`, { rejectUnauthorized: false, key: idF.key, cert: idF.cert })
  const overLoopback = await feed(`ws://127.0.0.1:${API}/v1/ws?token=${TOKEN_F}`, {})
  const idF2 = await enroll('phoneF', TOKEN_F)
  assert.equal(await Promise.race([overLan.closed, new Promise((r) => setTimeout(() => r('open'), 3000))]), 4002)
  assert.equal(await Promise.race([overLoopback.closed, new Promise((r) => setTimeout(() => r('open'), 200))]), 'open', 'no certificate there, nothing to replace')
  overLoopback.ws.close()
  // The new certificate opens one at once.
  const again = await feed(`wss://127.0.0.1:${LAN}/v1/ws?token=${TOKEN_F}`, { rejectUnauthorized: false, key: idF2.key, cert: idF2.cert })
  again.ws.close()
})

test('the daemon keeps a device certificate’s expiry: deviceCert is false once it has run out, or when it was never recorded', async () => {
  const TOKEN_G = 'lan-test-token-g-0123456789abcdef'
  addDevice({ id: 'phoneG', name: 'Phone G', model: 'Pixel', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN_G)
  const idG = await enroll('phoneG', TOKEN_G)
  const validTo = Date.parse(new X509Certificate(idG.cert).validTo)
  const devs = await loop(LOCAL, 'GET', '/local/devices', { authorization: `Bearer ${adminToken}` })
  assert.equal(devs.json.devices.find((d: any) => d.id === 'phoneG').certExpiresAt, validTo)
  const snap = async () => (await loop(API, 'GET', '/v1/snapshot', { authorization: `Bearer ${TOKEN_G}` })).json.lan.deviceCert
  assert.equal(await snap(), true)
  setDeviceCert('phoneG', idG.fingerprint, Date.now() - 1000)
  assert.equal(await snap(), false, 'run out: the app asks for a new one')
  setDeviceCert('phoneG', idG.fingerprint, validTo)
  assert.equal(await snap(), true)
  delete getDevice('phoneG')!.certExpiresAt
  assert.equal(await snap(), false, 'no expiry on record counts as none')
})

test('a preview and the checklist asked for over the listener open there with the device certificate (the app’s WebView), and on the loopback phone API by the same path (a browser behind a proxy)', async () => {
  const TOKEN_H = 'lan-test-token-h-0123456789abcdef'
  addDevice({ id: 'phoneH', name: 'Phone H', model: 'Pixel', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN_H)
  const idH = await enroll('phoneH', TOKEN_H)
  const dir = join(settings.projectsRoot, 'lan-preview')
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, 'index.html'), '<link rel="stylesheet" href="app.css"><h1>Over the LAN</h1>')
  writeFileSync(join(dir, 'app.css'), 'h1{color:#7FFFA0}')
  // What the phone sends: the Mac's address on the Wi-Fi as the host.
  const origin = `https://192.168.1.5:${LAN}`
  const headers = { host: `192.168.1.5:${LAN}` }
  const p = await lan(LAN, 'POST', '/v1/preview', { token: TOKEN_H, identity: idH, headers, body: JSON.stringify({ path: join(dir, 'index.html') }) })
  assert.equal(p.status, 200, p.body)
  const url = new URL(p.json.url)
  assert.equal(url.origin, origin, 'the route it was asked on')
  // The WebView: the pin and the device certificate, no bearer token.
  const page = await lan(LAN, 'GET', url.pathname, { identity: idH, headers })
  assert.equal(page.status, 200, page.body)
  assert.ok(page.body.includes('Over the LAN'))
  const css = await lan(LAN, 'GET', url.pathname.replace(/index\.html$/, 'app.css'), { identity: idH, headers })
  assert.equal(css.status, 200)
  // A browser has no device certificate: not here, but the same path on the loopback phone API works.
  const bare = await lan(LAN, 'GET', url.pathname, { headers })
  assert.equal(bare.status, 401)
  assert.equal(bare.json.error.code, 'device_cert_required')
  assert.equal((await loop(API, 'GET', url.pathname, { 'x-forwarded-for': '100.64.0.2' })).status, 200)
  // The checklist, likewise: its page and the routes it calls, from the page's own origin.
  const q = await lan(LAN, 'GET', '/v1/qa', { token: TOKEN_H, identity: idH, headers })
  assert.equal(q.status, 200)
  const qa = new URL(q.json.url)
  assert.equal(qa.origin, origin)
  const key = qa.searchParams.get('k')!
  const qp = await lan(LAN, 'GET', qa.pathname + qa.search, { identity: idH, headers })
  assert.equal(qp.status, 200, qp.body)
  assert.match(qp.body, /<html/i)
  assert.equal((await lan(LAN, 'GET', `/qa/state?k=${key}`, { identity: idH, headers })).status, 200)
  const saved = await lan(LAN, 'POST', `/qa/save?k=${key}`, { identity: idH, headers: { ...headers, origin }, body: JSON.stringify({ item: 'lan', status: 'pass' }) })
  assert.equal(saved.status, 200, saved.body)
  // Another site's page can't post to it.
  assert.equal((await lan(LAN, 'POST', `/qa/save?k=${key}`, { identity: idH, headers: { ...headers, origin: 'https://evil.example' }, body: '{}' })).status, 403)
  assert.equal((await lan(LAN, 'GET', qa.pathname + qa.search, { headers })).status, 401)
  assert.equal((await loop(API, 'GET', qa.pathname + qa.search, { 'x-forwarded-for': '100.64.0.2' })).status, 200)
})

// ── What the listener holds ──────────────────────────────────────────────
const defaults = { ...lanLimits }
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
/** The listener again with these limits (read when it starts); its port once it answers. */
async function restartLan(limits: Partial<typeof lanLimits>): Promise<number> {
  Object.assign(lanLimits, defaults, limits)
  setLanAccess(false)
  setLanAccess(true)
  for (let i = 0; i < 200; i++) {
    const p = lanInfo()?.port
    try {
      if (p && (await lan(p, 'GET', '/v1/health')).status === 200) return p
    } catch {}
    await sleep(20)
  }
  throw new Error('the listener did not come back')
}
/** A plain TCP connection that sends nothing, and when it was closed (ms after it connected). */
function quiet(port: number, host = '127.0.0.1'): Promise<{ sock: Socket; closed: Promise<number> }> {
  return new Promise((resolve, reject) => {
    const sock = tcpConnect(port, host)
    sock.once('error', reject)
    sock.once('connect', () => {
      const t0 = Date.now()
      sock.removeListener('error', reject)
      sock.on('error', () => {})
      resolve({ sock, closed: new Promise((r) => sock.once('close', () => r(Date.now() - t0))) })
    })
  })
}
/** The closing time, or null when it's still open after [ms]. */
const closedWithin = (closed: Promise<number>, ms: number) => Promise.race([closed, sleep(ms).then(() => null)])

test('at most perSource connections from one address and connections in all: the next is closed at accept, another address still gets in', async () => {
  let port = await restartLan({ perSource: 3 })
  const held = []
  for (let i = 0; i < 3; i++) held.push(await quiet(port))
  await sleep(100)
  const fourth = await quiet(port)
  assert.notEqual(await closedWithin(fourth.closed, 1000), null, 'the fourth from 127.0.0.1 is closed')
  for (const h of held) assert.equal(await closedWithin(h.closed, 50), null, 'the first three stay')
  // Its share used up, 127.0.0.1 holds no one else back.
  assert.equal((await lan(port, 'GET', '/v1/health', { host: '::1' })).status, 200)
  // One gone, room for one more.
  held.pop()!.sock.destroy()
  await sleep(100)
  assert.equal((await lan(port, 'GET', '/v1/health')).status, 200)
  for (const h of held) h.sock.destroy()

  port = await restartLan({ connections: 4 })
  const all = []
  for (let i = 0; i < 4; i++) all.push(await quiet(port))
  await sleep(100)
  const fifth = await quiet(port, '::1')
  assert.notEqual(await closedWithin(fifth.closed, 1000), null, 'the fifth, from anywhere, is closed')
  for (const h of all) assert.equal(await closedWithin(h.closed, 50), null)
  for (const h of all) h.sock.destroy()
  await sleep(100)
  assert.equal((await lan(port, 'GET', '/v1/health')).status, 200)
})

test('a peer that never finishes the handshake, says nothing after it, trickles its headers or idles after a response is closed; a slow body is not', async () => {
  const port = await restartLan({ handshakeMs: 300, headersMs: 400, keepAliveMs: 300 })
  // No TLS at all.
  const raw = await quiet(port)
  const rawAt = await closedWithin(raw.closed, 3000)
  assert.ok(rawAt !== null && rawAt >= 250, String(rawAt))
  const tls = (onUp: (t: TLSSocket) => void) =>
    new Promise<{ at: number; got: string }>((resolve, reject) => {
      let got = ''
      let t0 = 0
      const t = tlsConnect({ host: '127.0.0.1', port, rejectUnauthorized: false }, () => {
        t0 = Date.now()
        onUp(t)
      })
      t.setEncoding('utf8')
      t.on('data', (d) => (got += d))
      t.on('error', () => {})
      t.once('close', () => resolve({ at: Date.now() - t0, got }))
      setTimeout(() => reject(new Error('still open')), 5000).unref()
    })
  // The handshake, then nothing.
  const silent = await tls(() => {})
  assert.ok(silent.at >= 350 && silent.at < 3000, String(silent.at))
  // Headers that never end, a line every 100 ms (never quiet for long).
  const trickle = await tls((t) => {
    t.write('GET /v1/health HTTP/1.1\r\nHost: x\r\n')
    const more = setInterval(() => (t.writable ? t.write('X-More: 1\r\n') : clearInterval(more)), 100)
    t.once('close', () => clearInterval(more))
  })
  assert.ok(trickle.at < 3000, String(trickle.at))
  assert.match(trickle.got, /^HTTP\/1\.1 408/)
  // A whole request, then quiet on the kept-alive connection.
  const kept = await tls((t) => t.write('GET /v1/health HTTP/1.1\r\nHost: x\r\n\r\n'))
  assert.match(kept.got, /^HTTP\/1\.1 200/)
  assert.ok(kept.at < 3000, String(kept.at))
  // A body slower than headersMs still gets its answer (only the headers are on that clock).
  const slow = await tls((t) => {
    t.write('POST /v1/pair HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: 12\r\nConnection: close\r\n\r\n')
    setTimeout(() => t.write('{"code":"x"}'), 700)
  })
  assert.match(slow.got, /^HTTP\/1\.1 403/, slow.got)
  assert.match(slow.got, /"code":"(pairing_closed|code_expired)"/, 'read and answered')
  resetPairLimits()
  await restartLan({})
})
