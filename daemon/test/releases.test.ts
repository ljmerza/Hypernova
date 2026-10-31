// The app's GitHub releases (src/releases.ts), against a fake GitHub on a local port: a newer
// release's APK is fetched, checked against its note's SHA-256 and offered; a wrong checksum, an
// older or equal release, a local build with a higher versionCode, GitHub down or rate-limited
// change nothing; and the phones hear {t:"app"} when the offer changes, without reconnecting.
import { test, before, after, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { createServer as createHttpServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-test-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
// Only <support>/android counts, whatever a checkout's dist/ holds.
process.env.HYPERNOVA_NO_DEV_APK = '1'
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
process.env.HYPERNOVA_API_PORT = String(API)
process.env.HYPERNOVA_LOCAL_PORT = String(await freePort())
process.env.HYPERNOVA_LAN_PORT = '0'

const { startServers, appInfo, announceApp } = await import('../src/http.ts')
const { releaseChecker, parseNote } = await import('../src/releases.ts')
const { addDevice } = await import('../src/store.ts')
const TOKEN = 'test-device-token-releases-0123456789'
addDevice({ id: 'device', name: 'Pixel', model: 'Pixel 9', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)
const DIR = join(process.env.HYPERNOVA_SUPPORT, 'android')

// ── The fake GitHub ──────────────────────────────────────────────────────
type Release = { tag: string; note: unknown; apk: Buffer; noAssets?: boolean }
let release: Release | null = null
/** An answer to the latest-release call instead of the release (a rate limit, an outage). */
let latestAnswer: { status: number; headers?: Record<string, string> } | null = null
let hits = { latest: 0, conditional: 0, note: 0, apk: 0 }
let gh: import('node:http').Server
let GH = ''
const etagOf = (r: Release) => `"${r.tag}-${createHash('sha256').update(JSON.stringify(r.note)).digest('hex').slice(0, 8)}"`
function fake(req: IncomingMessage, res: ServerResponse): void {
  const path = req.url ?? '/'
  if (path === '/repos/ljmerza/Hypernova/releases/latest') {
    hits.latest++
    if (latestAnswer) {
      res.writeHead(latestAnswer.status, latestAnswer.headers ?? {})
      return void res.end('{"message":"nope"}')
    }
    if (!release) {
      res.writeHead(404, { 'content-type': 'application/json' })
      return void res.end('{"message":"Not Found"}')
    }
    const etag = etagOf(release)
    if (req.headers['if-none-match'] === etag) {
      hits.conditional++
      res.writeHead(304, { etag })
      return void res.end()
    }
    const t = release.tag
    const assets = release.noAssets ? [] : [
      { name: `hypernova-${t}.apk`, size: release.apk.length, browser_download_url: `${GH}/dl/apk` },
      { name: `hypernova-server-${t}-linux.tar.gz`, size: 3, browser_download_url: `${GH}/dl/tgz` },
      { name: `hypernova-${t}.json`, size: 120, browser_download_url: `${GH}/dl/note` },
    ]
    res.writeHead(200, { 'content-type': 'application/json', etag, 'x-ratelimit-remaining': '59' })
    return void res.end(JSON.stringify({ tag_name: t, draft: false, prerelease: false, assets }))
  }
  // GitHub sends asset downloads on to its storage with a redirect.
  if (path === '/dl/note') {
    res.writeHead(302, { location: `${GH}/objects/note` })
    return void res.end()
  }
  if (path === '/objects/note' && release) {
    hits.note++
    res.writeHead(200, { 'content-type': 'application/octet-stream' })
    return void res.end(typeof release.note === 'string' ? release.note : JSON.stringify(release.note))
  }
  if (path === '/dl/apk' && release) {
    hits.apk++
    res.writeHead(200, { 'content-type': 'application/octet-stream', 'content-length': String(release.apk.length) })
    return void res.end(release.apk)
  }
  res.writeHead(404)
  res.end()
}

const sha = (b: Buffer) => createHash('sha256').update(b).digest('hex')
/** A release of [apk] with its note saying [code]; [sum] another checksum than the APK's own. */
function publishRelease(tag: string, code: number, apk: Buffer, sum = sha(apk)): void {
  release = { tag, apk, note: { versionCode: code, versionName: tag.slice(1), sha256: sum } }
}
/** What an admin's `hnctl apk` leaves: the APK and its note. */
function localApk(code: number, bytes: Buffer): void {
  mkdirSync(DIR, { recursive: true })
  writeFileSync(join(DIR, 'hypernova.apk'), bytes)
  writeFileSync(join(DIR, 'hypernova.json'), JSON.stringify({ versionCode: code, versionName: 'local' }))
}
const offeredApk = () => (existsSync(join(DIR, 'hypernova.apk')) ? readFileSync(join(DIR, 'hypernova.apk')) : null)
const leftovers = () => (existsSync(DIR) ? readdirSync(DIR).filter((f) => f.startsWith('.')) : [])

let published = 0
const checker = (extra: Partial<Parameters<typeof releaseChecker>[0]> = {}) =>
  releaseChecker({ api: GH, offered: () => appInfo()?.versionCode ?? null, published: () => published++, enabled: () => true, ...extra })

let close: () => Promise<void>
before(async () => {
  gh = createHttpServer(fake)
  await new Promise<void>((r) => gh.listen(0, '127.0.0.1', () => r()))
  GH = `http://127.0.0.1:${(gh.address() as { port: number }).port}`
  close = startServers()
})
after(async () => {
  await close?.()
  await new Promise((r) => gh.close(r))
  rmSync(scratch, { recursive: true, force: true })
})
beforeEach(() => {
  rmSync(DIR, { recursive: true, force: true })
  release = null
  latestAnswer = null
  hits = { latest: 0, conditional: 0, note: 0, apk: 0 }
  published = 0
})

test('a newer release: its APK is fetched, checked and offered, the note last', async () => {
  localApk(100, Buffer.from('old build'))
  const apk = Buffer.from('the v0.6.0 apk '.repeat(5000))
  publishRelease('v0.6.0', 200, apk)
  const r = await checker().check()
  assert.deepEqual(r, { kind: 'published', tag: 'v0.6.0', versionCode: 200, versionName: '0.6.0' })
  assert.deepEqual(offeredApk(), apk)
  assert.deepEqual(appInfo(), { versionCode: 200, versionName: '0.6.0' })
  const note = JSON.parse(readFileSync(join(DIR, 'hypernova.json'), 'utf8'))
  assert.equal(note.sha256, sha(apk))
  assert.equal(note.tag, 'v0.6.0')
  assert.equal(published, 1)
  assert.deepEqual(leftovers(), [])
})

test('with no APK on the server yet, the release is the one offered', async () => {
  const apk = Buffer.from('first apk')
  publishRelease('v0.5.0', 50, apk)
  assert.equal((await checker().check()).kind, 'published')
  assert.deepEqual(offeredApk(), apk)
})

test('a checksum that is not the APK\'s: refused, and what was offered stays', async () => {
  const old = Buffer.from('old build')
  localApk(100, old)
  publishRelease('v0.6.0', 200, Buffer.from('tampered or cut short'), sha(Buffer.from('the real apk')))
  const c = checker()
  assert.deepEqual(await c.check(), { kind: 'mismatch', tag: 'v0.6.0' })
  assert.deepEqual(offeredApk(), old)
  assert.equal(appInfo()?.versionCode, 100)
  assert.equal(published, 0)
  assert.deepEqual(leftovers(), [])
  // Tried again next time (the release is unchanged: a 304, the note not read again).
  assert.equal((await c.check()).kind, 'mismatch')
  assert.equal(hits.conditional, 1)
  assert.equal(hits.note, 1)
  assert.equal(hits.apk, 2)
})

test('an older or equal release is ignored, without downloading its APK', async () => {
  const old = Buffer.from('build 100')
  localApk(100, old)
  publishRelease('v0.4.0', 100, Buffer.from('equal'))
  assert.deepEqual(await checker().check(), { kind: 'not-newer', tag: 'v0.4.0', versionCode: 100, offered: 100 })
  publishRelease('v0.3.0', 40, Buffer.from('older'))
  assert.equal((await checker().check()).kind, 'not-newer')
  assert.equal(hits.apk, 0)
  assert.deepEqual(offeredApk(), old)
  assert.equal(published, 0)
})

test('a local build with a higher versionCode (hnctl apk, a dev build) is never replaced by a release', async () => {
  const dev = Buffer.from('dev build 900')
  localApk(900, dev)
  publishRelease('v0.6.0', 300, Buffer.from('release 300'))
  assert.equal((await checker().check()).kind, 'not-newer')
  assert.deepEqual(offeredApk(), dev)
  // An admin publishing a newer one while the release's APK comes: the admin's stays.
  localApk(100, Buffer.from('old'))
  let calls = 0
  const r = await checker({
    offered: () => {
      calls++
      if (calls === 2) localApk(950, dev)
      return appInfo()?.versionCode ?? null
    },
  }).check()
  assert.equal(r.kind, 'not-newer')
  assert.deepEqual(offeredApk(), dev)
  assert.equal(appInfo()?.versionCode, 950)
  assert.deepEqual(leftovers(), [])
  assert.equal(published, 0)
})

test('the ETag is sent again: an unchanged release is a 304, and a later local removal still gets the release', async () => {
  localApk(500, Buffer.from('local 500'))
  const apk = Buffer.from('release 300')
  publishRelease('v0.6.0', 300, apk)
  const c = checker()
  assert.equal((await c.check()).kind, 'not-newer')
  // `hnctl apk-clear`: nothing offered any more, and the same release (304) is now newer.
  rmSync(DIR, { recursive: true, force: true })
  assert.equal((await c.check()).kind, 'published')
  assert.equal(hits.conditional, 1)
  assert.equal(hits.note, 1)
  assert.deepEqual(offeredApk(), apk)
})

test('offline, GitHub failing or a release without a note: nothing changes and nothing throws', async () => {
  const old = Buffer.from('old build')
  localApk(100, old)
  const dead = `http://127.0.0.1:${await freePort()}`
  const off = await releaseChecker({ api: dead, offered: () => 100, published: () => published++, enabled: () => true }).check()
  assert.equal(off.kind, 'error')
  latestAnswer = { status: 502 }
  assert.deepEqual(await checker().check(), { kind: 'error', message: 'GitHub answered 502' })
  latestAnswer = null
  // A release from before the notes: no hypernova-<tag>.json, so no versionCode to compare.
  release = { tag: 'v0.5.0', apk: Buffer.from('x'), note: null, noAssets: true }
  assert.deepEqual(await checker().check(), { kind: 'no-note', tag: 'v0.5.0' })
  // A note that isn't one.
  release = { tag: 'v0.5.1', apk: Buffer.from('x'), note: '{"versionCode":"soon"}' }
  assert.equal((await checker().check()).kind, 'no-note')
  // No release at all.
  release = null
  assert.equal((await checker().check()).kind, 'none')
  assert.deepEqual(offeredApk(), old)
  assert.equal(published, 0)
})

test("GitHub's rate limit: no request until it resets", async () => {
  let clock = 1_000_000
  const reset = Math.floor(clock / 1000) + 3600
  latestAnswer = { status: 403, headers: { 'x-ratelimit-remaining': '0', 'x-ratelimit-reset': String(reset) } }
  const c = checker({ now: () => clock })
  assert.deepEqual(await c.check(), { kind: 'limited', until: reset * 1000 })
  latestAnswer = null
  publishRelease('v0.6.0', 200, Buffer.from('apk'))
  assert.equal((await c.check()).kind, 'limited')
  assert.equal(hits.latest, 1)
  clock = reset * 1000 + 1
  assert.equal((await c.check()).kind, 'published')
  // Retry-After (a secondary limit) counts too.
  latestAnswer = { status: 429, headers: { 'retry-after': '60' } }
  const d = checker({ now: () => clock })
  assert.deepEqual(await d.check(), { kind: 'limited', until: clock + 60_000 })
})

test('turned off (settings.releaseCheck false): GitHub is never asked', async () => {
  publishRelease('v0.6.0', 200, Buffer.from('apk'))
  assert.deepEqual(await checker({ enabled: () => false }).check(), { kind: 'off' })
  assert.equal(hits.latest, 0)
})

test('the version note: a versionCode, a versionName and a SHA-256, or nothing', () => {
  const s = 'A'.repeat(64)
  assert.deepEqual(parseNote({ versionCode: 5, versionName: '0.6.0', sha256: s }), { versionCode: 5, versionName: '0.6.0', sha256: 'a'.repeat(64) })
  assert.equal(parseNote({ versionCode: 5, versionName: '0.6.0' }), null)
  assert.equal(parseNote({ versionCode: 0, versionName: '0.6.0', sha256: s }), null)
  assert.equal(parseNote({ versionCode: 1.5, versionName: '0.6.0', sha256: s }), null)
  assert.equal(parseNote({ versionCode: 5, versionName: '0.6.0', sha256: 'xyz' }), null)
  assert.equal(parseNote(null), null)
})

test('a connected phone hears {t:"app"} when the offer changes, and nothing while it stays', async () => {
  const got: { t: string; app?: unknown }[] = []
  const ws = new WebSocket(`ws://127.0.0.1:${API}/v1/ws?token=${TOKEN}`)
  ws.onmessage = (m) => got.push(JSON.parse(String(m.data)))
  await new Promise<void>((resolve, reject) => {
    ws.onopen = () => resolve()
    ws.onerror = () => reject(new Error('refused'))
  })
  const waitFor = async (f: () => boolean) => {
    for (let i = 0; i < 100 && !f(); i++) await new Promise((r) => setTimeout(r, 20))
    assert.ok(f())
  }
  await waitFor(() => got.some((m) => m.t === 'snapshot'))
  // The servers' watch reads the offer at start; tests before this one changed it, so settle it.
  announceApp()
  const before = got.length
  const apk = Buffer.from('release apk for the socket')
  publishRelease('v0.7.0', 700, apk)
  const r = await releaseChecker({ api: GH, offered: () => appInfo()?.versionCode ?? null, published: announceApp, enabled: () => true }).check()
  assert.equal(r.kind, 'published')
  await waitFor(() => got.slice(before).some((m) => m.t === 'app'))
  assert.deepEqual(got.slice(before).filter((m) => m.t === 'app'), [{ t: 'app', app: { versionCode: 700, versionName: '0.7.0' } }])
  // Said once: the same offer again says nothing.
  announceApp()
  // An admin taking it away (`hnctl apk-clear`): the phones hear null.
  rmSync(DIR, { recursive: true, force: true })
  announceApp()
  await waitFor(() => got.slice(before).filter((m) => m.t === 'app').length === 2)
  assert.deepEqual(got.slice(before).filter((m) => m.t === 'app').at(-1), { t: 'app', app: null })
  ws.close()
})
