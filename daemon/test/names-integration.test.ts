// The name end to end (names.ts, acme.ts, lan.ts, http.ts) against a hypernova.observer and a
// Let's Encrypt that live on this machine only (fixtures/names-stack.ts: the real Worker code, a
// stand-in Cloudflare API mirroring into pebble-challtestsrv's DNS, and Pebble). A test daemon's
// own servers and keeper: registration, the private record only, a certificate by DNS-01 through
// the Worker's challenge records, the listener serving it by SNI while the pinned certificate and
// mutual TLS stay at the address, the links, an address change, a renewal, a name the service
// forgot, release, and the fallbacks (a limit, silence, no Wi-Fi, an untrusted CA). Skipped when
// names/ has no node_modules or Pebble isn't installed.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { request as httpRequest } from 'node:http'
import { request as httpsRequest } from 'node:https'
import { createServer, type AddressInfo } from 'node:net'
import { connect as tlsConnect, rootCertificates, type TLSSocket } from 'node:tls'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'
import { execFileSync } from 'node:child_process'
import { X509Certificate } from 'node:crypto'
import { startNamesStack } from './fixtures/names-stack.ts'

const realHome = homedir()
const scratch = mkdtempSync(join(tmpdir(), 'hypernova-names-it-'))
const SUPPORT = join(scratch, 'support')
const LOGS = join(scratch, 'logs')
process.env.HYPERNOVA_SUPPORT = SUPPORT
process.env.HYPERNOVA_LOGS = LOGS
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
mkdirSync(process.env.HOME, { recursive: true })
const fakeClaude = join(scratch, 'claude-bin')
writeFileSync(fakeClaude, '#!/bin/sh\necho "9.9.9 (Claude Code)"\n')
chmodSync(fakeClaude, 0o755)
process.env.CLAUDE_BIN = fakeClaude
process.env.HYPERNOVA_NO_DEV_APK = '1'

const stack = await startNamesStack(join(scratch, 'stack'), realHome)

if (typeof stack === 'string') {
  test('the name end to end (skipped)', (t) => t.skip(stack))
} else {
  const freePort = () =>
    new Promise<number>((resolve) => {
      const s = createServer()
      s.listen(0, '127.0.0.1', () => {
        const p = (s.address() as AddressInfo).port
        s.close(() => resolve(p))
      })
    })
  const API = await freePort()
  const LOCAL = await freePort()
  process.env.HYPERNOVA_API_PORT = String(API)
  process.env.HYPERNOVA_LOCAL_PORT = String(LOCAL)
  process.env.HYPERNOVA_LAN_PORT = '0'
  // The keeper's environment: this stack, never the real service or Let's Encrypt.
  process.env.HYPERNOVA_NAMES_URL = stack.serviceUrl
  process.env.HYPERNOVA_ACME_DIRECTORY = stack.directory
  process.env.HYPERNOVA_ACME_CA = stack.acmeCaFile
  process.env.HYPERNOVA_NAMES_DNS = stack.dns
  process.env.HYPERNOVA_NAMES_ROOTS = stack.rootsFile

  const names = await import('../src/names.ts')
  const { startServers, startPairing } = await import('../src/http.ts')
  const { lanInfo } = await import('../src/lan.ts')
  const { serverIdentity } = await import('../src/tls.ts')
  const { addDevice, adminToken, settings } = await import('../src/store.ts')
  const { NameServiceClient, importServerKey, fromBase64 } = await import('../src/names-client.ts')

  const pebbleRoot = readFileSync(stack.rootsFile, 'utf8')
  const DOMAIN = 'hypernova.observer'
  let address: string | null = '192.168.77.7'
  names.setNamesConfig({ lanAddress: () => address, settleMs: 50, checkDelayMs: 150 })

  const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
  async function until(what: string, ok: () => boolean | Promise<boolean>, ms = 60_000): Promise<void> {
    const t0 = Date.now()
    while (!(await ok())) {
      if (Date.now() - t0 > ms) throw new Error(`timed out: ${what} (${JSON.stringify(names.nameStatus())})`)
      await sleep(50)
    }
  }
  const tryJson = (s: string) => {
    try {
      return JSON.parse(s)
    } catch {
      return null
    }
  }
  function local(method: string, path: string, body?: unknown): Promise<{ status: number; json: any }> {
    return new Promise((resolve, reject) => {
      const req = httpRequest({ host: '127.0.0.1', port: LOCAL, method, path, headers: { authorization: `Bearer ${adminToken}`, 'content-type': 'application/json' } }, (res) => {
        let data = ''
        res.on('data', (c) => (data += c))
        res.on('end', () => resolve({ status: res.statusCode ?? 0, json: tryJson(data) }))
      })
      req.on('error', reject)
      req.end(body === undefined ? undefined : JSON.stringify(body))
    })
  }
  type Identity = { key: string; cert: string }
  type Reply = { status: number; body: string; json: any; peer: X509Certificate | null; headers: Record<string, unknown> }
  /**
   * A request to the LAN listener: by name (SNI, the certificate checked against Pebble's root, as a
   * browser checks Let's Encrypt's), or at the address with the pin's leniency, as the app does.
   */
  function lan(path: string, opts: { name?: string; identity?: Identity; token?: string; method?: string; headers?: Record<string, string> } = {}): Promise<Reply> {
    const port = lanInfo()!.port
    return new Promise((resolve, reject) => {
      const headers: Record<string, string> = { host: `${opts.name ?? '127.0.0.1'}:${port}`, ...(opts.headers ?? {}) }
      if (opts.token) headers.authorization = `Bearer ${opts.token}`
      const req = httpsRequest(
        {
          host: '127.0.0.1',
          port,
          path,
          method: opts.method ?? 'GET',
          headers,
          agent: false,
          ...(opts.name ? { servername: opts.name, ca: [pebbleRoot], rejectUnauthorized: true } : { rejectUnauthorized: false }),
          key: opts.identity?.key,
          cert: opts.identity?.cert,
        },
        (res) => {
          const raw = (res.socket as TLSSocket).getPeerCertificate()?.raw
          let data = ''
          res.setEncoding('utf8')
          res.on('data', (c) => (data += c))
          res.on('end', () => resolve({ status: res.statusCode ?? 0, body: data, json: tryJson(data), peer: raw ? new X509Certificate(raw) : null, headers: res.headers }))
        },
      )
      req.on('error', reject)
      req.end()
    })
  }
  /** Just the certificate the listener presents for [servername] (none: an address). */
  function presented(servername?: string): Promise<X509Certificate> {
    return new Promise((resolve, reject) => {
      const s = tlsConnect({ host: '127.0.0.1', port: lanInfo()!.port, servername, rejectUnauthorized: false }, () => {
        resolve(new X509Certificate(s.getPeerCertificate().raw))
        s.destroy()
      })
      s.on('error', reject)
    })
  }
  const fingerprint = (c: X509Certificate | null) => c?.fingerprint256.replace(/:/g, '').toLowerCase() ?? ''
  const recordsOf = (host: string) => [...stack.records.values()].filter((r) => r.name === host)

  /** A paired phone with its device certificate, enrolled over loopback as in lan.test.ts. */
  async function phone(): Promise<{ id: string; token: string; identity: Identity }> {
    const id = 'namesPhone'
    const token = 'names-test-token-0123456789abcdef'
    addDevice({ id, name: 'Phone', model: 'Pixel', createdAt: Date.now(), lastSeenAt: Date.now() }, token)
    const dir = mkdtempSync(join(scratch, 'csr-'))
    execFileSync('/usr/bin/openssl', ['ecparam', '-genkey', '-name', 'prime256v1', '-noout', '-out', join(dir, 'k.pem')])
    execFileSync('/usr/bin/openssl', ['req', '-new', '-key', join(dir, 'k.pem'), '-subj', `/CN=${id}`, '-sha256', '-outform', 'DER', '-out', join(dir, 'r.der')])
    const reply = await new Promise<any>((resolve, reject) => {
      const req = httpRequest({ host: '127.0.0.1', port: API, method: 'POST', path: '/v1/device/cert', headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' } }, (res) => {
        let d = ''
        res.on('data', (c) => (d += c))
        res.on('end', () => resolve(JSON.parse(d)))
      })
      req.on('error', reject)
      req.end(JSON.stringify({ csr: readFileSync(join(dir, 'r.der')).toString('base64') }))
    })
    return { id, token, identity: { key: readFileSync(join(dir, 'k.pem'), 'utf8'), cert: new X509Certificate(Buffer.from(reply.cert, 'base64')).toString() } }
  }

  let closeServers: () => Promise<void>
  let keeper: InstanceType<typeof names.NameKeeper>
  before(async () => {
    closeServers = startServers()
    await until('the LAN listener', () => !!lanInfo()?.port, 5_000)
    mkdirSync(join(SUPPORT, 'android'), { recursive: true })
    writeFileSync(join(SUPPORT, 'android', 'hypernova.apk'), 'not really an apk')
    keeper = names.startNames()
  })
  after(async () => {
    names.stopNames()
    await closeServers()
    await stack.close()
  })

  test('nothing until a code is shown; then registers, publishes the private record only, gets a certificate by DNS-01 through the Worker, and uses the name', async () => {
    // Started, and a round run: no code shown, so nothing is registered or published.
    await keeper.tick()
    assert.equal(stack.records.size, 0)
    assert.equal(names.nameStatus().name, null)
    assert.equal(names.nameStatus().state, 'idle')
    // A code shown before Setup has said what the name is, and the menu bar's pairing code (which
    // never uses the name): nothing is made, and they don't count later.
    assert.equal((await local('GET', '/local/status?qr=1')).status, 200)
    await startPairing()
    await keeper.tick()
    assert.equal(stack.records.size, 0)
    assert.equal(names.nameStatus().showing, false)
    assert.equal(names.nameStatus().termsShown, false)
    assert.match(names.nameStatus().reason, /says what the private name is first/)
    assert.ok(!existsSync(join(SUPPORT, 'names', 'acme.json')), 'no ACME account, no terms accepted')
    // Setup shows the line about the name and the terms, and says so; then its code counts.
    assert.equal((await local('POST', '/local/settings', { nameTermsShown: true })).status, 200)
    await startPairing()
    await keeper.tick()
    assert.equal(stack.records.size, 0, 'the menu bar’s code still doesn’t count')
    // Setup's phone step asks for its status this way while it shows a code.
    assert.equal((await local('GET', '/local/status?qr=1')).status, 200)
    assert.equal(names.nameStatus().showing, true)
    await until('the name in use', () => names.nameStatus().inUse, 90_000)
    const s = names.nameStatus()
    assert.match(s.name ?? '', /^[a-z0-9]{10}$/)
    assert.equal(s.host, `l-${s.name}.${DOMAIN}`)
    assert.equal(s.state, 'ready')
    assert.equal(s.address, '192.168.77.7')
    assert.equal(s.resolves, true)
    assert.equal(s.reason, 'In use.')
    assert.equal(s.lastError, null)
    assert.equal(s.certificate?.trusted, true)
    assert.equal(s.certificate?.directory, 'custom')
    const day = 86_400_000
    assert.ok(Math.abs(s.certificate!.expiresAt - (Date.now() + 90 * day)) < day, String(s.certificate!.expiresAt))
    assert.equal(s.certificate!.renewsAt, s.certificate!.expiresAt - 30 * day)
    // At "Cloudflare": l-<name> → the Wi-Fi address; no public record; the challenge record gone again.
    assert.deepEqual(recordsOf(s.host!).map((r) => [r.type, r.content]), [['A', '192.168.77.7']])
    assert.deepEqual(recordsOf(`${s.name}.${DOMAIN}`), [])
    assert.deepEqual([...stack.records.values()].filter((r) => r.type === 'TXT'), [])
    assert.equal(names.namedOrigin(), `https://${s.host}:${lanInfo()!.port}`)
    // Kept private: the folder 0700, every file 0600.
    assert.equal(statSync(join(SUPPORT, 'names')).mode & 0o777, 0o700)
    for (const f of ['name.json', 'acme.json', 'certificate.json', 'state.json']) assert.equal(statSync(join(SUPPORT, 'names', f)).mode & 0o777, 0o600, f)
    // And no key ever reaches the log.
    const log = readFileSync(join(LOGS, 'daemon.log'), 'utf8')
    const saved = JSON.parse(readFileSync(join(SUPPORT, 'names', 'name.json'), 'utf8'))
    const cert = JSON.parse(readFileSync(join(SUPPORT, 'names', 'certificate.json'), 'utf8'))
    const account = JSON.parse(readFileSync(join(SUPPORT, 'names', 'acme.json'), 'utf8'))
    assert.ok(!log.includes('PRIVATE KEY'))
    assert.ok(!log.includes(saved.privateKey))
    for (const pem of [cert.key, account.key]) assert.ok(!log.includes(pem.split('\n')[1]))
    assert.match(log, new RegExp(`names: registered ${s.host}`))
    assert.match(log, /names: certificate for l-[a-z0-9]{10}\.hypernova\.observer until/)
  })

  test('the listener: the name’s certificate by SNI, with no client certificate used; the pinned one and mutual TLS at the address', async () => {
    const host = names.nameStatus().host!
    const pinned = serverIdentity()!.fingerprint
    const byName = await presented(host)
    assert.ok(byName.subjectAltName?.includes(`DNS:${host}`))
    assert.notEqual(fingerprint(byName), pinned)
    assert.equal(fingerprint(await presented()), pinned)
    // Any other name gets the pinned certificate, as before.
    assert.equal(fingerprint(await presented(`l-someoneelse.${DOMAIN}`)), pinned)
    // A browser's check passes (Pebble's root standing in for Let's Encrypt's).
    assert.equal((await lan('/download/android', { name: host })).status, 200)

    const p = await phone()
    const atAddress = await lan('/v1/snapshot', { identity: p.identity, token: p.token })
    assert.equal(atAddress.status, 200, atAddress.body)
    assert.equal(fingerprint(atAddress.peer), pinned)
    // Through the name the device certificate counts for nothing: those routes stay at the address.
    const viaName = await lan('/v1/snapshot', { name: host, identity: p.identity, token: p.token })
    assert.equal(viaName.status, 404)
  })

  test('through the name only the download page, the APK and a live code’s pairing page answer, with no CORS', async () => {
    const host = names.nameStatus().host!
    const evil = { origin: 'https://evil.example', 'access-control-request-method': 'GET', 'access-control-request-private-network': 'true' }
    for (const [method, path] of [['GET', '/v1/health'], ['POST', '/v1/pair'], ['POST', '/v1/device/cert'], ['OPTIONS', '/v1/health'], ['OPTIONS', '/v1/pair'], ['GET', '/pair'], ['GET', '/pair?c=bogus'], ['GET', '/qa'], ['HEAD', '/download/android'], ['POST', '/download/android']] as const) {
      const r = await lan(path, { name: host, method, headers: evil })
      assert.equal(r.status, 404, `${method} ${path}: ${r.status} ${r.body.slice(0, 80)}`)
      assert.equal(r.headers['access-control-allow-origin'], undefined, `${method} ${path}`)
      assert.equal(r.headers['access-control-allow-private-network'], undefined, `${method} ${path}`)
      assert.ok(!r.body.includes('protocol'), 'the Mac’s name isn’t told')
    }
    // At the address, health answers, with no CORS either.
    const health = await lan('/v1/health', { headers: evil })
    assert.equal(health.status, 200)
    assert.equal(health.headers['access-control-allow-origin'], undefined)
    assert.equal((await lan('/v1/snapshot', { method: 'OPTIONS', headers: evil })).status, 404)
    // OPTIONS at the address reaches no route (a route's handler doesn't check the method).
    for (const path of ['/download/android', '/download/android/Hypernova.apk', '/p/0123456789abcdef0123/', '/pair', '/qa']) {
      const r = await lan(path, { method: 'OPTIONS' })
      assert.equal(r.status, 404, `OPTIONS ${path}: ${r.status}`)
      assert.ok(!r.body.includes('<!doctype'), path)
    }
  })

  test('the pages through the name: no certificate warning, and the pairing page hands the app its usual route', async () => {
    const host = names.nameStatus().host!
    const port = lanInfo()!.port
    const named = await lan('/download/android', { name: host })
    assert.equal(named.status, 200)
    assert.ok(named.body.includes('Download the app'))
    assert.ok(!named.body.includes("can't vouch"), 'no warning through the name')
    const plain = await lan('/download/android')
    assert.ok(plain.body.includes("can't vouch"), 'the warning at the address')
    assert.equal((await lan('/download/android/Hypernova.apk', { name: host })).body, 'not really an apk')

    const pairing = await startPairing()
    const code = new URL(pairing.webLink).searchParams.get('c')!
    // A Host header naming somewhere else changes nothing on the page.
    const page = await lan(`/pair?c=${code}&n=Mac`, { name: host, headers: { host: 'attacker.example', 'x-forwarded-host': 'attacker.example', 'x-forwarded-proto': 'javascript' } })
    assert.equal(page.status, 200)
    assert.ok(!page.body.includes('attacker.example'))
    // The app's `u` is the base URL the code was made for, never the name; the page's own links are the name's.
    assert.ok(page.body.includes(`u=${encodeURIComponent(pairing.url)}`), page.body)
    assert.ok(!page.body.includes(`u=${encodeURIComponent(`https://${host}`)}`))
    assert.ok(page.body.includes(`href="https://${host}:${port}/download/android"`))
    assert.ok(page.body.includes(`fp=${serverIdentity()!.fingerprint}`))
    // At the address the pairing page stays closed, as before.
    assert.equal((await lan(`/pair?c=${code}`)).status, 401)
  })

  test('the download link and the pairing web link use the name; the app link keeps the pinned route', async () => {
    const host = names.nameStatus().host!
    const port = lanInfo()!.port
    const status = await local('GET', '/local/status')
    assert.equal(status.json.downloadUrl, `https://${host}:${port}/download/android`)
    assert.equal(status.json.downloadLocal, true)
    assert.equal(status.json.downloadTrusted, true)
    // No https base URL (the phone's way in is the local route): nothing else to offer.
    assert.equal(status.json.downloadAnywhere, null)
    assert.equal(status.json.name.inUse, true)
    const p = await startPairing()
    assert.ok(p.webLink.startsWith(`https://${host}:${port}/pair?c=`), p.webLink)
    assert.ok(p.link.startsWith('https://hypernova.observer/pair#v=1&u='), p.link)
    assert.ok(!p.link.includes(host), 'nor does the QR code')
    assert.ok(p.appLink.includes(`u=${encodeURIComponent('http://10.0.2.2:' + API)}`))
    assert.ok(p.appLink.includes(`fp=${serverIdentity()!.fingerprint}`) && p.appLink.includes(`&lp=${port}`))
    assert.ok(!p.appLink.includes(host), 'the app never gets the name')
    assert.equal(p.download, status.json.downloadUrl)
  })

  test('an address change is published after a quiet moment; meanwhile the links fall back to the address', async () => {
    const host = names.nameStatus().host!
    address = '192.168.77.8'
    keeper.addressChanged()
    assert.equal(names.nameStatus().inUse, false)
    assert.equal(names.namedOrigin(), null)
    const meanwhile = await local('GET', '/local/status')
    assert.equal(meanwhile.json.downloadTrusted, false)
    assert.ok(!String(meanwhile.json.downloadUrl).includes(host))
    await until('the new address in use', () => names.nameStatus().inUse && names.nameStatus().address === '192.168.77.8')
    assert.deepEqual(recordsOf(host).map((r) => r.content), ['192.168.77.8'])
    // No Wi-Fi at all: nothing is called, the reason says so, the old record stays.
    address = null
    keeper.addressChanged()
    await sleep(200)
    const s = names.nameStatus()
    assert.equal(s.state, 'idle')
    assert.equal(s.inUse, false)
    assert.equal(s.reason, 'No Wi‑Fi address to publish.')
    assert.equal((await local('GET', '/local/status')).json.downloadTrusted, false)
    address = '192.168.77.8'
    await until('back in use', () => names.nameStatus().inUse)
  })

  test('a renewal: a new certificate, served at once without a restart', async () => {
    const host = names.nameStatus().host!
    const before = fingerprint(await presented(host))
    keeper.forceRenewal()
    await keeper.tick()
    const after = await presented(host)
    assert.notEqual(fingerprint(after), before)
    assert.ok(after.subjectAltName?.includes(`DNS:${host}`))
    assert.equal(names.nameStatus().inUse, true)
    assert.deepEqual([...stack.records.values()].filter((r) => r.type === 'TXT'), [])
  })

  test('a name the service no longer knows is registered again, with a new certificate', async () => {
    const old = names.nameStatus()
    const saved = JSON.parse(readFileSync(join(SUPPORT, 'names', 'name.json'), 'utf8'))
    // Behind the keeper's back (as the 90-day sweep would): the name goes.
    await new NameServiceClient({ baseUrl: stack.serviceUrl }).release(await importServerKey(fromBase64(saved.privateKey), saved.publicKey), saved.name)
    address = '192.168.77.9'
    keeper.addressChanged()
    await until('a new name in use', () => names.nameStatus().inUse && names.nameStatus().name !== old.name)
    const s = names.nameStatus()
    assert.deepEqual(recordsOf(s.host!).map((r) => r.content), ['192.168.77.9'])
    assert.ok((await presented(s.host!)).subjectAltName?.includes(`DNS:${s.host}`))
    // The old name's certificate is no longer served.
    assert.equal(fingerprint(await presented(old.host!)), serverIdentity()!.fingerprint)
  })

  test('release before an uninstall: records gone, files gone, the pinned certificate only; the setting resumes and stops it', async () => {
    const s = names.nameStatus()
    const r = await local('POST', '/local/name/release')
    assert.equal(r.json.released, true)
    assert.equal(r.json.name, s.name)
    assert.deepEqual([...stack.records.values()].filter((x) => x.name.includes(s.name!)), [])
    assert.ok(!existsSync(join(SUPPORT, 'names', 'name.json')) && !existsSync(join(SUPPORT, 'names', 'certificate.json')))
    assert.ok(existsSync(join(SUPPORT, 'names', 'acme.json')), 'the account key is kept for a next name')
    assert.equal(fingerprint(await presented(s.host!)), serverIdentity()!.fingerprint)
    const after = names.nameStatus()
    assert.equal(after.state, 'off')
    assert.match(after.reason, /Released/)
    const status = await local('GET', '/local/status')
    assert.equal(status.json.downloadTrusted, false)
    assert.equal(status.json.name.inUse, false)
    // A while later nothing has been registered again.
    await sleep(300)
    assert.equal(names.nameStatus().name, null)

    // The setting on: a new name when a code is next shown (codes from before don't count); off:
    // given up, said so, and it stays off.
    assert.equal((await local('POST', '/local/settings', { nameService: true })).status, 200)
    await keeper.tick()
    assert.equal(names.nameStatus().name, null)
    await startPairing(false, true)
    await until('a new name after turning it on', () => names.nameStatus().inUse)
    const again = names.nameStatus()
    assert.notEqual(again.name, s.name)
    const offReply = await local('POST', '/local/settings', { nameService: false })
    assert.deepEqual(offReply.json.nameRelease, { released: true, name: again.name })
    assert.equal(settings.nameService, false)
    const off = names.nameStatus()
    assert.equal(off.state, 'off')
    assert.equal(off.reason, 'Turned off.')
    assert.deepEqual([...stack.records.values()].filter((x) => x.name.includes(again.name!)), [])
  })

  test('the fallbacks: a registration limit, a silent service, an untrusted CA; each says why and keeps the address', async () => {
    const cfg = (over: Partial<ReturnType<typeof names.envConfig>>) => () => ({ ...names.envConfig(), lanAddress: () => '192.168.77.20', settleMs: 50, checkDelayMs: 150, ...over })
    settings.nameService = true
    try {
      // This address's registrations for today used up: the keeper waits as long as the service says.
      stack.env.REGISTER_PER_IP_PER_DAY = '1'
      stack.callerIp = '8.8.4.4'
      await new NameServiceClient({ baseUrl: stack.serviceUrl }).register((await (await import('../src/names-client.ts')).generateServerKey()).publicKey)
      const limited = new names.NameKeeper(join(scratch, 'limited'), cfg({}), () => {})
      limited.want()
      await limited.tick()
      const ls = limited.status()
      assert.equal(ls.state, 'waiting')
      assert.equal(ls.inUse, false)
      assert.match(ls.lastError!.message, /limit/)
      assert.match(ls.reason, /limit/)
      // The end of the (UTC) day, or a minute from now when that's sooner (just before midnight).
      const endOfDay = Math.max((Math.floor(Date.now() / 86_400_000) + 1) * 86_400_000, Date.now() + 60_000)
      assert.ok(Math.abs(ls.nextAttemptAt! - endOfDay) < 5_000, `${ls.nextAttemptAt} vs ${endOfDay}`)
      // Asked again before then, it doesn't call.
      const calls = stack.records.size
      await limited.tick()
      assert.equal(limited.status().nextAttemptAt, ls.nextAttemptAt)
      assert.equal(stack.records.size, calls)
      limited.stop()

      // Nobody answering: a minute, then doubling.
      const closed = await freePort()
      const silent = new names.NameKeeper(join(scratch, 'silent'), cfg({ serviceUrl: `http://127.0.0.1:${closed}` }), () => {})
      silent.want()
      await silent.tick()
      const ss = silent.status()
      assert.equal(ss.lastError?.message, 'Couldn’t reach the name service.')
      assert.ok(Math.abs(ss.nextAttemptAt! - (Date.now() + 60_000)) < 5_000)
      assert.equal(ss.inUse, false)
      silent.stop()

      // A certificate nobody trusts (staging's, say): served by name for a test, never in the links.
      stack.env.REGISTER_PER_IP_PER_DAY = '5'
      stack.callerIp = '9.9.9.9'
      const untrusted = new names.NameKeeper(join(scratch, 'untrusted'), cfg({ roots: rootCertificates }), () => {})
      untrusted.want()
      await untrusted.tick()
      const us = untrusted.status()
      assert.equal(us.state, 'ready')
      assert.equal(us.certificate?.trusted, false)
      assert.equal(us.inUse, false)
      assert.match(us.reason, /don’t trust/)
      assert.ok(untrusted.route())
      assert.equal(untrusted.origin(), null)
      await untrusted.release()
      untrusted.stop()
    } finally {
      delete stack.env.REGISTER_PER_IP_PER_DAY
      stack.callerIp = '93.184.216.34'
      settings.nameService = false
    }
  })

  test('the address is withdrawn a while after the last code; renewals go on without it', async () => {
    settings.nameService = true
    stack.callerIp = '9.9.9.10'
    const k = new names.NameKeeper(join(scratch, 'linger'), () => ({ ...names.envConfig(), lanAddress: () => '192.168.77.40', settleMs: 50, checkDelayMs: 50, lingerMs: 1_500 }), () => {})
    try {
      k.want()
      await k.tick()
      await until('in use', async () => (await k.tick(), k.status().inUse), 20_000)
      const host = k.status().host!
      assert.deepEqual(recordsOf(host).map((r) => r.content), ['192.168.77.40'])
      await sleep(1_600)
      await k.tick()
      const s = k.status()
      assert.equal(s.address, null)
      assert.equal(s.showing, false)
      assert.equal(s.inUse, false)
      assert.equal(s.state, 'idle')
      assert.deepEqual(recordsOf(host), [], 'the address record is gone')
      // A renewal now: the challenge record only, no address record.
      const before = k.route()!.cert
      k.forceRenewal()
      await k.tick()
      assert.notEqual(k.route()!.cert, before)
      assert.deepEqual(recordsOf(host), [])
      assert.deepEqual([...stack.records.values()].filter((r) => r.type === 'TXT'), [])
      // A code shown again: published again.
      k.want()
      await k.tick()
      assert.deepEqual(recordsOf(host).map((r) => r.content), ['192.168.77.40'])
      await k.release()
    } finally {
      k.stop()
      settings.nameService = false
      stack.callerIp = '93.184.216.34'
    }
  })

  test('a release stops a round stuck waiting for DNS, and gives the name up within seconds', async () => {
    settings.nameService = true
    stack.callerIp = '9.9.9.11'
    // A DNS server that answers nothing: the round waits for the challenge record (two minutes, uncut).
    const { createSocket } = await import('node:dgram')
    const silentDns = createSocket('udp4')
    await new Promise<void>((r) => silentDns.bind(0, '127.0.0.1', r))
    const k = new names.NameKeeper(join(scratch, 'stuck'), () => ({ ...names.envConfig(), dnsServers: [`127.0.0.1:${silentDns.address().port}`], lanAddress: () => '192.168.77.41', settleMs: 50, checkDelayMs: 50, lingerMs: 60_000 }), () => {})
    try {
      k.want()
      void k.tick()
      await until('waiting for the challenge record', () => [...stack.records.values()].some((r) => r.type === 'TXT'), 20_000)
      const name = JSON.parse(readFileSync(join(scratch, 'stuck', 'name.json'), 'utf8')).name
      const t0 = Date.now()
      const r = await k.release()
      const took = Date.now() - t0
      assert.equal(r.released, true)
      assert.ok(took < 8_000, `${took} ms`)
      assert.deepEqual([...stack.records.values()].filter((x) => x.name.includes(name)), [])
      const err = await new NameServiceClient({ baseUrl: stack.serviceUrl }).register('x').catch((e) => e)
      assert.ok(err, 'the service still answers')
    } finally {
      k.stop()
      silentDns.close()
      settings.nameService = false
      stack.callerIp = '93.184.216.34'
    }
  })
}
