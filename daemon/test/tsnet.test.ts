// Tailscale built in (tsnet.ts; docs/PROTOCOL.md, "Tailscale built in"): the helper's lifecycle
// with a stand-in (test/fixtures/fake-tsnet.mjs), the choice between the Tailscale app and the
// built-in node, what the pairing link carries, the local endpoints, and the settings.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { request as httpRequest } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-tsnet-'))
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

// The Tailscale app, as the file ts-mode says (the daemon hands Tailscale the environment it
// started with, so a file, not a variable): "serving" (logged in, serve on), "idle" (serve off),
// "stopped" (the app quit: serve's settings still listed, the backend stopped).
const fakeTs = join(scratch, 'tailscale')
const tsMode = join(scratch, 'ts-mode')
writeFileSync(
  fakeTs,
  `#!${process.execPath}\nconst a = process.argv.slice(2)\nconst mode = require('node:fs').readFileSync(${JSON.stringify(tsMode)}, 'utf8').trim()\nif (a.includes('--bg') && mode === 'idle') require('node:fs').writeFileSync(${JSON.stringify(tsMode)}, 'serving')\nif (a[0] === 'serve') console.log(mode === 'serving' || mode === 'stopped' ? JSON.stringify({ TCP: { [process.env.HYPERNOVA_API_PORT]: { HTTPS: true } }, Web: { ['mac.tail.ts.net:' + process.env.HYPERNOVA_API_PORT]: { Handlers: { '/': { Proxy: 'http://127.0.0.1:' + process.env.HYPERNOVA_API_PORT } } } } }) : '{}')\nelse console.log(JSON.stringify({ BackendState: mode === 'stopped' ? 'Stopped' : 'Running', Self: { DNSName: 'mac.tail.ts.net.', TailscaleIPs: ['100.64.0.1'] }, CertDomains: ['mac.tail.ts.net'], CurrentTailnet: { Name: 'owner@example.com' } }))\n`,
)
chmodSync(fakeTs, 0o755)
const noTs = join(scratch, 'no-tailscale')
process.env.HYPERNOVA_TAILSCALE = noTs

// The helper: a wrapper around the fixture (the daemon runs the path it's given as a program).
const fixture = join(dirname(fileURLToPath(import.meta.url)), 'fixtures', 'fake-tsnet.mjs')
const fakeHelper = join(scratch, 'hypernova-tsnet')
writeFileSync(fakeHelper, `#!/bin/sh\nexec "${process.execPath}" "${fixture}" "$@"\n`)
chmodSync(fakeHelper, 0o755)
// The stand-in reads its mode and logs its arguments next to the state folder (see the fixture).
const argsLog = join(process.env.HYPERNOVA_SUPPORT, 'fake-tsnet-args.log')
const setMode = (m: string) => writeFileSync(join(process.env.HYPERNOVA_SUPPORT!, 'fake-tsnet-mode'), m)
mkdirSync(process.env.HYPERNOVA_SUPPORT, { recursive: true, mode: 0o700 })
setMode('running')
process.env.HYPERNOVA_TSNET = fakeHelper
process.env.HYPERNOVA_TSNET_GRACE_MS = '80'
process.env.HYPERNOVA_TSNET_AUTOSERVE_MS = '0'
// Off in the tests below that mean "the app is not serving"; the one test of the automatic serve turns it on.
process.env.HYPERNOVA_TSNET_AUTOSERVE = '0'
process.env.HYPERNOVA_TSNET_RECHECK_MS = '150'
process.env.HYPERNOVA_TSNET_AUTHKEY = 'tskey-test-secret'
const lastRun = () => JSON.parse(readFileSync(argsLog, 'utf8').trim().split('\n').pop()!) as { args: string[]; env: Record<string, string> }

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

const { startTsnet, stopTsnet, tsnetState, nodeName, helperPath, setBuiltInTailscale, setControlUrl, tailnetHints, tailnetNow, recheckSystem, controlUrlOk, publicState, TSNET_DIR } = await import('../src/tsnet.ts')
const { startServers, startPairing, apkInfo } = await import('../src/http.ts')
const { lanInfo } = await import('../src/lan.ts')
const { request: httpsRequest } = await import('node:https')
const { adminToken, settings } = await import('../src/store.ts')
const { statSync } = await import('node:fs')

function local(method: string, path: string, body?: string): Promise<{ status: number; json: any }> {
  return new Promise((resolve, reject) => {
    const req = httpRequest({ host: '127.0.0.1', port: LOCAL, method, path, headers: { authorization: `Bearer ${adminToken}`, 'content-type': 'application/json' } }, (res) => {
      let data = ''
      res.on('data', (c) => (data += c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, json: data ? JSON.parse(data) : null }))
    })
    req.on('error', reject)
    req.end(body)
  })
}
/** The snapshot as the phone API hands it out (the admin token, directly on loopback). */
function phoneSnapshot(): Promise<any> {
  return new Promise((resolve, reject) => {
    const req = httpRequest({ host: '127.0.0.1', port: API, method: 'GET', path: '/v1/snapshot', headers: { authorization: `Bearer ${adminToken}` } }, (res) => {
      let data = ''
      res.on('data', (c) => (data += c))
      res.on('end', () => resolve(JSON.parse(data)))
    })
    req.on('error', reject)
    req.end()
  })
}
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
/** Waits until the state satisfies [ok] (the helper speaks asynchronously). */
async function until(ok: () => boolean, ms = 4000): Promise<void> {
  const t0 = Date.now()
  while (!ok()) {
    if (Date.now() - t0 > ms) throw new Error('timed out waiting for the helper')
    await sleep(25)
  }
}

let closeServers: () => Promise<void>
before(() => {
  closeServers = startServers()
})
after(async () => {
  await stopTsnet()
  await closeServers()
  rmSync(scratch, { recursive: true, force: true })
})

test('the node is named after the host, in DNS letters', () => {
  assert.equal(nodeName('MacBook Air.local'), 'hypernova-macbook-air')
  assert.equal(nodeName('Nikita’s Mac'), 'hypernova-nikita-s-mac')
  assert.equal(nodeName(''), 'hypernova-host')
  assert.equal(helperPath(), fakeHelper)
})

test('with the Tailscale app serving, the built-in node stays off (one node per machine)', async () => {
  process.env.HYPERNOVA_TAILSCALE = fakeTs
  writeFileSync(tsMode, 'serving')
  const st = await startTsnet()
  assert.equal(st.mode, 'system')
  assert.match(st.reason, /already serves/)
  assert.equal(st.helper, null)
  assert.equal(st.url, null)
  // The pairing link still hints at the tailnet, from the app's own status.
  const p = await startPairing()
  assert.equal(p.url, `https://mac.tail.ts.net:${API}`)
  assert.match(p.appLink, /&ts=mac\.tail\.ts\.net&ti=100\.64\.0\.1&ta=owner%40example\.com/)
  assert.ok(!p.appLink.includes('&tc='))
})

test('the Tailscale app running and signed in but not serving: Hypernova turns serve on and the built-in node stays off; when serve fails the node runs', async () => {
  process.env.HYPERNOVA_TAILSCALE = fakeTs
  writeFileSync(tsMode, 'idle')
  delete process.env.HYPERNOVA_TSNET_AUTOSERVE
  const st = await startTsnet()
  assert.equal(st.mode, 'system')
  assert.match(st.reason, /turned on by Hypernova/)
  assert.equal(st.helper, null)
  assert.equal(readFileSync(tsMode, 'utf8'), 'serving')
  // Serve refused (the fake stays idle with --bg ignored): the built-in node takes over.
  process.env.HYPERNOVA_TSNET_AUTOSERVE = '0'
  writeFileSync(tsMode, 'idle')
  const st2 = await startTsnet()
  assert.equal(st2.mode, 'builtIn')
  assert.equal(readFileSync(tsMode, 'utf8'), 'idle')
  await stopTsnet()
  writeFileSync(tsMode, 'idle')
  process.env.HYPERNOVA_TAILSCALE = noTs
})

test('without the app serving, the helper runs, reports, and the pairing link follows it', async () => {
  process.env.HYPERNOVA_TAILSCALE = noTs
  const st = await startTsnet()
  assert.equal(st.mode, 'builtIn')
  await until(() => tsnetState().helper?.state === 'Running')
  const now = tsnetState()
  assert.equal(now.url, `https://hypernova-test.tail.ts.net:${API}`)
  assert.equal(now.helper?.account, 'owner@example.com')
  assert.equal(now.helper?.tls, 'tailscale')
  // The state folder is the daemon's own, closed to others.
  assert.equal(statSync(TSNET_DIR).mode & 0o777, 0o700)
  // Started with the right arguments: its state, name, port, target, and the daemon's own certificate.
  const { args, env } = lastRun()
  assert.equal(args[args.indexOf('--state') + 1], TSNET_DIR)
  assert.equal(args[args.indexOf('--hostname') + 1], nodeName())
  assert.equal(args[args.indexOf('--port') + 1], String(API))
  assert.equal(args[args.indexOf('--target') + 1], `127.0.0.1:${API}`)
  assert.ok(args.includes('--cert') && args.includes('--key'))
  assert.ok(!args.includes('--control'))
  // The pre-auth key travels in the environment, never on the command line; and the helper sees
  // only what it needs (no daemon secrets, no log uploads).
  assert.ok(!args.some((a) => a.includes('tskey-')), 'the key is not on argv')
  assert.equal(args[args.indexOf('--authkey-env') + 1], 'HYPERNOVA_TSNET_AUTHKEY')
  assert.equal(env.HYPERNOVA_TSNET_AUTHKEY, 'tskey-test-secret')
  assert.equal(env.TS_NO_LOGS_NO_SUPPORT, 'true')
  assert.equal(env.HYPERNOVA_SUPPORT, undefined)
  assert.equal(env.CLAUDE_CONFIG_DIR, undefined)
  const p = await startPairing()
  assert.equal(p.url, `https://hypernova-test.tail.ts.net:${API}`)
  assert.match(p.appLink, /&ts=hypernova-test\.tail\.ts\.net&ti=100\.64\.0\.9&ta=owner%40example\.com/)
  // The code carries https://hypernova.observer/pair with the app link's details after its `#` (the
  // app opens it; a phone not yet signed in can't open the computer's own page); the web link exists
  // too, without the account.
  assert.equal(p.link, `https://hypernova.observer/pair#${p.appLink.slice('hypernova://pair?'.length)}`)
  assert.match(p.webLink, /^https:\/\/hypernova-test\.tail\.ts\.net:\d+\/pair\?c=/)
  assert.ok(!p.webLink.includes('&ta='), 'the account stays out of the web link')
  // The phone gets the app over the local network while the built-in node is the only tailnet route.
  assert.match(p.download, /^https:\/\/(\d+\.\d+\.\d+\.\d+|\[[0-9a-f:]+\]):\d+\/download\/android$/)
  const status = await local('GET', '/local/status')
  assert.equal(status.json.downloadUrl, p.download)
  assert.equal(status.json.downloadLocal, true)
  // A published release URL is never the local download: no checksum to compare.
  process.env.HYPERNOVA_APK_URL = 'https://example.com/Hypernova.apk'
  try {
    const pub = await local('GET', '/local/status')
    assert.equal(pub.json.downloadUrl, 'https://example.com/Hypernova.apk')
    assert.equal(pub.json.downloadLocal, false)
  } finally { delete process.env.HYPERNOVA_APK_URL }
  // The feed never carries the sign-in link.
  assert.equal(publicState().helper?.loginUrl, undefined)
  assert.deepEqual(await tailnetHints(), { name: 'hypernova-test.tail.ts.net', ip: '100.64.0.9', account: 'owner@example.com', control: null })
})

test('the local API reports it, and login/logout reach the helper', async () => {
  const r = await local('GET', '/local/status')
  assert.equal(r.json.tsnet.mode, 'builtIn')
  assert.equal(r.json.tsnet.helper.state, 'Running')
  const out = await local('POST', '/local/tsnet/logout')
  assert.equal(out.json.ok, true)
  await until(() => tsnetState().helper?.state === 'NeedsLogin')
  const needs = (await local('GET', '/local/tsnet')).json
  assert.equal(needs.helper.loginUrl, 'https://login.tailscale.com/a/def456')
  assert.ok(needs.helper.qr.includes('█'))
  assert.equal(needs.url, null, 'no base URL while signed out')
  // Signed out, the pairing link falls back to the development address (nothing better to say).
  const p = await startPairing()
  assert.ok(!p.appLink.includes('&ts='))
  await local('POST', '/local/tsnet/login')
  await until(() => tsnetState().helper?.state === 'Running')
})

test('a crashed helper comes back by itself', async () => {
  await setBuiltInTailscale(false)
  assert.equal(tsnetState().mode, 'off')
  assert.equal(settings.builtInTailscale, false)
  assert.equal(tsnetState().helper, null)
  setMode('crash')
  const st = await setBuiltInTailscale(true)
  assert.equal(st.mode, 'builtIn')
  // The first copy exits with 3 at once; the next comes after 2 s, and by then it's told to run.
  await until(() => tsnetState().helper === null, 3000)
  setMode('running')
  await until(() => tsnetState().helper?.state === 'Running', 6000)
})

test('a control server (Headscale) is passed on, and shows in the pairing link', async () => {
  // https, or plain http only to a private address.
  assert.ok(controlUrlOk('https://headscale.example'))
  assert.ok(controlUrlOk('http://192.168.0.125:49508'))
  assert.ok(controlUrlOk('http://100.64.0.1:8080'))
  assert.ok(!controlUrlOk('http://headscale.example:8080'))
  assert.ok(!controlUrlOk('ftp://x'))
  assert.ok(!controlUrlOk('https://user:pw@headscale.example'))
  await assert.rejects(setControlUrl('http://headscale.example:8080'), /https/)
  const bad = await local('POST', '/local/settings', JSON.stringify({ tailscaleControlUrl: 'http://headscale.example:8080' }))
  assert.equal(bad.status, 400)
  const st = await setControlUrl('https://headscale.example:8080')
  assert.equal(st.controlUrl, 'https://headscale.example:8080')
  await until(() => tsnetState().helper?.state === 'Running')
  const { args } = lastRun()
  assert.equal(args[args.indexOf('--control') + 1], 'https://headscale.example:8080')
  const p = await startPairing()
  assert.match(p.appLink, /&tc=https%3A%2F%2Fheadscale\.example%3A8080/)
  // The settings route takes it too, "" meaning tailscale.com again.
  const r = await local('POST', '/local/settings', JSON.stringify({ tailscaleControlUrl: '' }))
  assert.equal(r.json.tailscaleControlUrl, undefined)
  await until(() => tsnetState().helper?.state === 'Running')
  assert.equal(tsnetState().controlUrl, null)
})

test('a helper that ignores SIGTERM is killed when the setting goes off', async () => {
  setMode('slow-exit')
  await setControlUrl(null) // restarts it as slow-exit
  await until(() => tsnetState().helper?.state === 'Running')
  const t0 = Date.now()
  await setBuiltInTailscale(false)
  assert.ok(Date.now() - t0 < 7000, 'SIGKILL after 5 s at most')
  assert.equal(tsnetState().helper, null)
  setMode('running')
})

test('when the tailnet has no HTTPS certificates the node serves the pinned certificate on its address', async () => {
  setMode('pinned')
  await setBuiltInTailscale(true)
  await until(() => tsnetState().helper?.state === 'Running')
  const now = tsnetState()
  assert.equal(now.helper?.tls, 'pinned')
  assert.equal(now.url, `https://100.64.0.9:${API}`)
  const p = await startPairing()
  assert.equal(p.url, `https://100.64.0.9:${API}`)
  // The phone pins the LAN listener's certificate, whose fingerprint the link carries.
  assert.match(p.appLink, /&fp=[0-9a-f]{64}&/)
  setMode('running')
})

test('the Tailscale app starting to serve later stops the built-in node (one node per machine)', async () => {
  process.env.HYPERNOVA_TAILSCALE = fakeTs
  writeFileSync(tsMode, 'idle')
  await setBuiltInTailscale(true)
  await until(() => tsnetState().helper?.state === 'Running')
  assert.equal(tsnetState().mode, 'builtIn')
  writeFileSync(tsMode, 'serving')
  // The periodic re-check (150 ms here) notices.
  await until(() => tsnetState().mode === 'system', 3000)
  assert.equal(tsnetState().helper, null)
  // And at once after "turn on phone access".
  writeFileSync(tsMode, 'idle')
  await startTsnet()
  await until(() => tsnetState().helper?.state === 'Running')
  writeFileSync(tsMode, 'serving')
  await recheckSystem()
  assert.equal(tsnetState().mode, 'system')
  writeFileSync(tsMode, 'idle')
  process.env.HYPERNOVA_TAILSCALE = noTs
})

test('the Tailscale app stopping to serve hands over to the built-in node', async () => {
  process.env.HYPERNOVA_TAILSCALE = fakeTs
  writeFileSync(tsMode, 'serving')
  await startTsnet()
  assert.equal(tsnetState().mode, 'system')
  assert.equal(tsnetState().helper, null)
  // Phones hear where the API is on the tailnet (the snapshot's mac.tailnet): the app's serve.
  assert.deepEqual(tailnetNow(), { name: 'mac.tail.ts.net', ip: '100.64.0.1', url: `https://mac.tail.ts.net:${API}`, control: null })
  // Quit (serve's settings still listed), signed out or uninstalled: after two re-checks in a row
  // (150 ms each here) the built-in node takes over, so the phone keeps a route.
  writeFileSync(tsMode, 'stopped')
  await until(() => tsnetState().mode === 'builtIn', 3000)
  await until(() => tsnetState().helper?.state === 'Running')
  // A phone paired with the app's address follows the node from here (data/Pairing.kt TailnetFollow).
  assert.deepEqual(tailnetNow(), { name: 'hypernova-test.tail.ts.net', ip: '100.64.0.9', url: `https://hypernova-test.tail.ts.net:${API}`, control: null })
  const snap = await phoneSnapshot()
  assert.equal(snap.mac.tailnet.url, `https://hypernova-test.tail.ts.net:${API}`)
  // And nothing offers the quit app's address any more: pairing and the download use the node's.
  const p = await startPairing()
  assert.ok(!p.url.includes('mac.tail.ts.net'), p.url)
  assert.ok(!p.download.includes('mac.tail.ts.net'), p.download)
  // And the app serving again steps it aside, as before.
  writeFileSync(tsMode, 'serving')
  await until(() => tsnetState().mode === 'system', 3000)
  writeFileSync(tsMode, 'idle')
  process.env.HYPERNOVA_TAILSCALE = noTs
})

test('the pairing page builds its app link from the daemon\'s own hints, never the query', async () => {
  process.env.HYPERNOVA_TAILSCALE = noTs
  await startTsnet()
  await until(() => tsnetState().helper?.state === 'Running')
  const live = await startPairing()
  const page = await new Promise<string>((resolve, reject) => {
    const req = httpRequest({ host: '127.0.0.1', port: API, method: 'GET', path: `/pair?c=${live.code}&n=Mac&ts=evil.example&tc=https%3A%2F%2Fevil.example&ta=x%40evil.example&fp=` + 'a'.repeat(64) }, (res) => {
      let data = ''
      res.on('data', (c) => (data += c))
      res.on('end', () => resolve(data))
    })
    req.on('error', reject)
    req.end()
  })
  assert.ok(!page.includes('evil.example'))
  assert.ok(!page.includes('a'.repeat(64)))
  assert.ok(page.includes('ts=hypernova-test.tail.ts.net'))
})

/** A phone with no device certificate yet, on the LAN listener: Node's https client with the server's certificate pinned by fingerprint. */
function overLan(method: string, path: string): Promise<{ status: number; body: string; type: string }> {
  const lan = lanInfo()!
  return new Promise((resolve, reject) => {
    const req = httpsRequest({ host: '127.0.0.1', port: lan.port, method, path, rejectUnauthorized: false, checkServerIdentity: () => undefined }, (res) => {
      let data = ''
      res.on('data', (c) => (data += c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, body: data, type: String(res.headers['content-type'] ?? '') }))
    })
    req.on('error', reject)
    req.end()
  })
}

test('the LAN listener serves the download page and the APK to a phone without a certificate, and nothing else of the pages', async () => {
  // No APK is bundled in this scratch daemon (and a checkout's dist/ is ignored): the page says
  // so (404), with no certificate asked.
  process.env.HYPERNOVA_NO_DEV_APK = '1'
  const page = await overLan('GET', '/download/android')
  assert.equal(page.status, 404)
  assert.ok(page.body.includes('Get Hypernova for Android'))
  const apk = await overLan('GET', '/download/android/Hypernova.apk')
  assert.equal(apk.status, 404)
  assert.equal(apkInfo(), null)
  // The pairing page is not served there (the built-in route's code carries the app link itself).
  assert.equal((await overLan('GET', '/pair?c=x')).status, 401)
  // Nor anything a device needs its certificate for.
  assert.equal((await overLan('GET', '/v1/snapshot')).status, 401)
  assert.equal((await overLan('GET', '/qa')).status, 401)
})

test('the pairing page tells the tailnet hints only for a live code', async () => {
  process.env.HYPERNOVA_TAILSCALE = noTs
  await startTsnet()
  await until(() => tsnetState().helper?.state === 'Running')
  const get = (path: string) =>
    new Promise<string>((resolve, reject) => {
      const req = httpRequest({ host: '127.0.0.1', port: API, method: 'GET', path }, (res) => {
        let data = ''
        res.on('data', (c) => (data += c))
        res.on('end', () => resolve(data))
      })
      req.on('error', reject)
      req.end()
    })
  // No code, or a made-up one: the page is there, but says nothing about this computer.
  for (const path of ['/pair', '/pair?c=made-up']) {
    const page = await get(path)
    assert.ok(page.includes('Pair with'), path)
    assert.ok(!page.includes('ts=') && !page.includes('ta=') && !page.includes('tc=') && !page.includes('fp='), `${path}: no hints`)
  }
  const live = await startPairing()
  const page = await get(`/pair?c=${live.code}`)
  assert.ok(page.includes('ts=hypernova-test.tail.ts.net') && page.includes('ta=owner%40example.com') && page.includes('&fp='))
})
