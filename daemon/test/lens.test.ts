// What the glasses app (Hypernova Lens) asks of the daemon (docs/PROTOCOL.md, "Read aloud",
// "Show me", "The speech companion"): Mac voices, screenshots of the page a session built, the
// speech companion's address, and the small things around them. Everything runs on free ports
// with scratch state; Chrome and Tailscale are stand-ins, except a few captures with the real
// Chrome when this Mac has it.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawn, type ChildProcess } from 'node:child_process'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, realpathSync, rmSync, statSync, symlinkSync, utimesSync, writeFileSync } from 'node:fs'
import { createServer as createHttpServer, request, type Server } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hypernova-test-')))
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

// Tailscale: this Mac is "mac.tail.ts.net" and serves whatever FAKE_TS_SERVE says.
const fakeTs = join(scratch, 'tailscale')
writeFileSync(
  fakeTs,
  `#!${process.execPath}\nconst a = process.argv.slice(2)\nif (a[0] === 'serve') console.log(process.env.FAKE_TS_SERVE || '{}')\nelse console.log(JSON.stringify({ BackendState: 'Running', Self: { DNSName: 'mac.tail.ts.net.', TailscaleIPs: ['100.64.0.1'] }, CertDomains: ['mac.tail.ts.net'] }))\n`,
)
chmodSync(fakeTs, 0o755)
process.env.HYPERNOVA_TAILSCALE = fakeTs
// The daemon hands Tailscale the environment it started with.
const SERVING = { TCP: { '7443': { HTTPS: true } }, Web: { 'mac.tail.ts.net:7443': { Handlers: { '/': { Proxy: 'http://127.0.0.1:7443' } } } } }
process.env.FAKE_TS_SERVE = JSON.stringify(SERVING)

// Chrome: the DevTools stand-in, through a wrapper (exec keeps the pipe on fds 3 and 4).
const fakeChrome = join(scratch, 'chrome')
const fixture = join(dirname(fileURLToPath(import.meta.url)), 'fixtures', 'fake-chrome.mjs')
writeFileSync(fakeChrome, `#!/bin/sh\nexec "${process.execPath}" "${fixture}" "$@"\n`)
chmodSync(fakeChrome, 0o755)
const chromeLog = join(scratch, 'chrome.log')
process.env.HYPERNOVA_CHROME = fakeChrome
process.env.FAKE_CHROME_LOG = chromeLog
process.env.FAKE_CHROME_TITLE = 'Hyperscribe — Export'

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
// The LAN listener (lan.ts) takes a free port: test files run side by side.
process.env.HYPERNOVA_LAN_PORT = '0'

const { startServers, startPairing } = await import('../src/http.ts')
const { addDevice, listDevices } = await import('../src/store.ts')
const { upsertSession, transcriptPathFor } = await import('../src/hub.ts')
const { parseVoices, companionAddress, keepClip, recentClip, RECENT_MS } = await import('../src/speech.ts')
const { backgroundOf, findChrome, localTarget, loopbackUrls, lsofNames, mayVisit, noPreloading, render, taskOutputTail, SHOTS_DIR } = await import('../src/shots.ts')

const TOKEN = 'test-lens-token-0123456789abcdef'
const OTHER = 'test-other-token-0123456789abcdef'
addDevice({ id: 'glasses', name: 'Glasses', model: 'G2', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)
addDevice({ id: 'phone', name: 'Phone', model: 'P3', createdAt: Date.now(), lastSeenAt: Date.now() }, OTHER)
const AUTH = { authorization: `Bearer ${TOKEN}` }

// The "dev server" a session started: a tiny page on a loopback port.
let dev: Server
let DEV = 0
/** Requests Chrome made ahead of time (speculation rules), which the dev server notes. */
const speculative: string[] = []
let close: () => Promise<void>
const children: ChildProcess[] = []
before(async () => {
  dev = createHttpServer((q, r) => {
    if (q.headers['sec-purpose']) speculative.push(`${q.url} ${q.headers['sec-purpose']}`)
    // Where its pages send the browser on to, for the real Chrome: on this Mac, to the daemon, off the Mac.
    const away: Record<string, string> = { '/hop': `http://127.0.0.1:${DEV}/landed`, '/to-daemon': `http://127.0.0.1:${LOCAL}/qa`, '/off-mac': 'http://example.invalid/' }
    if (away[q.url!]) return void r.writeHead(302, { location: away[q.url!] }).end()
    const page = (title: string, extra = '') => `<!doctype html><meta charset="utf-8"><title>${title}</title>${extra}<body style="margin:0;background:#14110E;color:#7FFFA0;font:600 48px system-ui"><h1 style="margin:40px">It works.</h1>`
    if (q.url === '/script-to-daemon') return void r.writeHead(200, { 'content-type': 'text/html' }).end(page('Leaving', `<script>setTimeout(() => location.href = 'http://127.0.0.1:${LOCAL}/qa', 50)</script>`))
    if (q.url === '/set-cookie') return void r.writeHead(200, { 'content-type': 'text/html', 'set-cookie': 'leak=from-an-earlier-capture; Path=/; Max-Age=3600' }).end(page('Cookie set'))
    if (q.url === '/cookie') return void r.writeHead(200, { 'content-type': 'text/html' }).end(page('', "<script>document.title = 'cookie:' + document.cookie</script>"))
    if (q.url === '/speculates') return void r.writeHead(200, { 'content-type': 'text/html' }).end(page('Speculates', '<script type="speculationrules">{"prefetch":[{"source":"list","urls":["/prefetched"]}],"prerender":[{"source":"list","urls":["/prerendered"]}]}</script>'))
    r.writeHead(200, { 'content-type': 'text/html; charset=utf-8' })
    r.end(page(q.url === '/landed' ? 'Landed' : 'Dev server'))
  })
  await new Promise<void>((r) => dev.listen(0, '127.0.0.1', () => r()))
  DEV = (dev.address() as { port: number }).port
  close = startServers()
  for (let i = 0; i < 50; i++) {
    try {
      if ((await send('GET', '/v1/health')).status === 200) break
    } catch {}
    await new Promise((r) => setTimeout(r, 20))
  }
})
after(async () => {
  for (const c of children) c.kill('SIGKILL')
  dev?.closeAllConnections()
  dev?.close()
  await close?.()
  rmSync(scratch, { recursive: true, force: true })
})

type Reply = { status: number; headers: import('node:http').IncomingHttpHeaders; body: Buffer }
function send(method: string, path: string, headers: Record<string, string> = {}, body?: string | object, port = API): Promise<Reply> {
  const data = body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body)
  return new Promise((resolve, reject) => {
    const req = request({ host: '127.0.0.1', port, method, path, headers: { host: `127.0.0.1:${port}`, ...(data ? { 'content-type': 'application/json' } : {}), ...headers } }, (res) => {
      const chunks: Buffer[] = []
      res.on('data', (c) => chunks.push(c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, headers: res.headers, body: Buffer.concat(chunks) }))
    })
    req.on('error', reject)
    req.end(data)
  })
}
const json = (r: Reply) => JSON.parse(r.body.toString('utf8'))
const errorCode = (r: Reply) => json(r).error?.code

// ── Transcript fixtures (hand-written in Claude Code 2.1's shapes) ───────
let tick = 0
const at = () => new Date(Date.now() - 60_000 + tick++ * 100).toISOString()
const line = (o: object) => JSON.stringify(o)
const prompt = (uuid: string, text: string, cwd: string) => line({ uuid, parentUuid: null, timestamp: at(), cwd, isSidechain: false, type: 'user', message: { role: 'user', content: text } })
const said = (uuid: string, text: string, cwd: string) => line({ uuid, parentUuid: null, timestamp: at(), cwd, isSidechain: false, type: 'assistant', message: { id: `m-${uuid}`, role: 'assistant', model: 'claude-test', content: [{ type: 'text', text }] } })
const tool = (id: string, name: string, input: object, out: string, cwd: string, result: object = {}) => [
  line({ uuid: `a-${id}`, parentUuid: null, timestamp: at(), cwd, isSidechain: false, type: 'assistant', message: { id: `m-${id}`, role: 'assistant', model: 'claude-test', content: [{ type: 'tool_use', id, name, input }] } }),
  line({ uuid: `r-${id}`, parentUuid: null, timestamp: at(), cwd, isSidechain: false, type: 'user', message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: id, content: out }] }, toolUseResult: result }),
]
const bash = (id: string, command: string, stdout: string, cwd: string) => tool(id, 'Bash', { command }, stdout, cwd, { stdout, stderr: '' })
const background = (id: string, command: string, taskId: string, file: string, cwd: string) =>
  tool(id, 'Bash', { command, run_in_background: true }, `Command running in background with ID: ${taskId}. Output is being written to: ${file}. You will be notified when it completes.`, cwd, { stdout: '', stderr: '', backgroundTaskId: taskId })

/** A session in its own folder, with these transcript lines. */
let sessions = 0
function session(lines: string[], title = 'Building the export page'): { id: string; cwd: string } {
  const id = `00000000-0000-4000-8000-${String(++sessions).padStart(12, '0')}`
  const cwd = join(scratch, 'projects', `app${sessions}`)
  mkdirSync(cwd, { recursive: true })
  const path = transcriptPathFor(cwd, id)
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, [prompt(`p-${id}`, title, cwd), ...lines.flat()].join('\n') + '\n')
  upsertSession(id, { cwd, transcriptPath: path, title })
  return { id, cwd }
}
const shoot = (id: string, body: object = {}) => send('POST', `/v1/sessions/${id}/screenshot`, AUTH, body)
const lastNavigation = () => (existsSync(chromeLog) ? readFileSync(chromeLog, 'utf8').trim().split('\n').at(-1) : undefined)

// ── Read aloud ───────────────────────────────────────────────────────────
test("voices from `say -v '?'`: the language is the last word before #, the name all before it", () => {
  const out = [
    'Albert              en_US    # Hello! My name is Albert.',
    'Eddy (English (US)) en_US    # Hello! My name is Eddy.',
    'Majed               ar_001   # مرحبًا! اسمي ماجد.',
    'Grandma (French (Canada)) fr_CA    # Bonjour! Je m’appelle Grandma.',
    '',
    'not a voice line',
  ].join('\n')
  assert.deepEqual(parseVoices(out), [
    { id: 'Albert', name: 'Albert', lang: 'en-US' },
    { id: 'Eddy (English (US))', name: 'Eddy (English (US))', lang: 'en-US' },
    { id: 'Majed', name: 'Majed', lang: 'ar-001' },
    { id: 'Grandma (French (Canada))', name: 'Grandma (French (Canada))', lang: 'fr-CA' },
  ])
})

const hasSay = existsSync('/usr/bin/say')
test('read aloud: the Mac renders AAC in M4A, keeps nothing on disk, and checks the text first', { skip: !hasSay && 'no /usr/bin/say' }, async () => {
  const voices = json(await send('GET', '/v1/speech/voices', AUTH)).voices as { id: string; lang: string }[]
  assert.ok(voices.length > 0)
  assert.ok(voices.every((v) => v.id && /^[a-z]{2,3}(-\w+)*$/.test(v.lang)), JSON.stringify(voices.slice(0, 3)))
  assert.equal((await send('GET', '/v1/speech/voices')).status, 401)

  const clip = await send('POST', '/v1/speech', AUTH, { text: 'Fixed the export. Three files changed, 48 tests passed.', voice: voices[0]!.id, rate: 1.2 })
  assert.equal(clip.status, 200)
  assert.equal(clip.headers['content-type'], 'audio/mp4')
  assert.equal(clip.headers['access-control-allow-origin'], '*')
  assert.equal(clip.body.toString('latin1', 4, 11), 'ftypM4A')
  // An unknown voice is the system voice; a rate out of range is clamped.
  const other = await send('POST', '/v1/speech', AUTH, { text: 'Done.', voice: 'Nobody At All', rate: 9 })
  assert.equal(other.status, 200)
  assert.deepEqual(readdirSync(join(process.env.HYPERNOVA_SUPPORT!, 'tmp')), [])
  // The same clip again comes from memory, at once.
  const t = Date.now()
  const again = await send('POST', '/v1/speech', AUTH, { text: 'Fixed the export. Three files changed, 48 tests passed.', voice: voices[0]!.id, rate: 1.2 })
  assert.ok(Date.now() - t < 300, `${Date.now() - t} ms`)
  assert.deepEqual(again.body, clip.body)

  assert.equal(errorCode(await send('POST', '/v1/speech', AUTH, { text: '   ' })), 'empty')
  const long = await send('POST', '/v1/speech', AUTH, { text: 'a'.repeat(8001) })
  assert.equal(long.status, 413)
  assert.equal(errorCode(long), 'too_long')
  assert.equal((await send('POST', '/v1/speech', AUTH, { text: 'a'.repeat(8000).replace(/a/g, 'ab ').slice(0, 8000) })).status, 200)
  assert.equal((await send('POST', '/v1/speech', {}, { text: 'hi' })).status, 401)
})

test('read aloud: a newer clip for the same device replaces the one rendering; another device is left alone', { skip: !hasSay && 'no /usr/bin/say' }, async () => {
  const long = 'This sentence is long enough to keep the voice busy for a while. '.repeat(110)
  const first = send('POST', '/v1/speech', AUTH, { text: long })
  const elsewhere = send('POST', '/v1/speech', { authorization: `Bearer ${OTHER}` }, { text: long + ' Again.' })
  await new Promise((r) => setTimeout(r, 400))
  const second = await send('POST', '/v1/speech', AUTH, { text: 'Short one.' })
  assert.equal(second.status, 200)
  const replaced = await first
  assert.equal(replaced.status, 409)
  assert.equal(errorCode(replaced), 'replaced')
  assert.equal((await elsewhere).status, 200)
})

test('read aloud: a kept clip goes 10 minutes after it was made, on a timer that never holds the daemon up', (t) => {
  // Unref'd: it isn't among what keeps the process alive.
  const timeouts = () => process.getActiveResourcesInfo().filter((r) => r === 'Timeout').length
  const n = timeouts()
  keepClip('real-timer', Buffer.from('x'))
  assert.equal(timeouts(), n)

  t.mock.timers.enable({ apis: ['setTimeout'] })
  const min = 60_000
  keepClip('a', Buffer.from('first'))
  t.mock.timers.tick(5 * min)
  keepClip('b', Buffer.from('second'))
  t.mock.timers.tick(3 * min)
  // The same clip again starts its 10 minutes over, and the old timer can't drop the new one.
  keepClip('a', Buffer.from('first, again'))
  t.mock.timers.tick(RECENT_MS - 8 * min) // 10 min since the first 'a'
  assert.equal(recentClip('a')?.toString(), 'first, again')
  assert.equal(recentClip('b')?.toString(), 'second')
  t.mock.timers.tick(5 * min) // 10 min since 'b'
  assert.equal(recentClip('b'), undefined)
  assert.equal(recentClip('a')?.toString(), 'first, again')
  t.mock.timers.tick(3 * min - 1)
  assert.equal(recentClip('a')?.toString(), 'first, again')
  t.mock.timers.tick(1) // 10 min since the second 'a'
  assert.equal(recentClip('a'), undefined)
  // No request needed: nothing but the timers ran. Past six, the oldest goes first.
  for (let i = 1; i <= 7; i++) keepClip(`n${i}`, Buffer.from(String(i)))
  assert.equal(recentClip('n1'), undefined)
  assert.equal(recentClip('n7')?.toString(), '7')
  t.mock.timers.tick(RECENT_MS)
  for (let i = 1; i <= 7; i++) assert.equal(recentClip(`n${i}`), undefined)
})

// ── The speech companion ─────────────────────────────────────────────────
test("the companion's address: its token, over its tailnet https route only", async () => {
  const serving = SERVING
  const settings = { port: 7443, token: 'ABCDEFGHJKLMNPQR', tailscale: true }
  assert.equal(companionAddress(settings, 'mac.tail.ts.net', serving), 'wss://mac.tail.ts.net:7443/ws?token=ABCDEFGHJKLMNPQR')
  assert.equal(companionAddress({ ...settings, port: 8443 }, 'mac.tail.ts.net', serving), null) // that port isn't routed
  assert.equal(companionAddress(settings, 'other.tail.ts.net', serving), null)
  assert.equal(companionAddress(settings, null, serving), null)
  assert.equal(companionAddress(settings, 'mac.tail.ts.net', {}), null)
  assert.equal(companionAddress({ ...settings, tailscale: false }, 'mac.tail.ts.net', serving), null)
  assert.equal(companionAddress({ ...settings, token: '' }, 'mac.tail.ts.net', serving), null)
  assert.equal(companionAddress({ port: 7443 }, 'mac.tail.ts.net', serving), null)

  // Not installed: null. Installed and routed: read from its settings on each request.
  assert.deepEqual(json(await send('GET', '/v1/speech/companion', AUTH)), { url: null })
  const file = join(process.env.HOME!, 'Library/Application Support/Hyperscribe Companion/settings.json')
  mkdirSync(dirname(file), { recursive: true })
  writeFileSync(file, JSON.stringify(settings))
  assert.deepEqual(json(await send('GET', '/v1/speech/companion', AUTH)), { url: 'wss://mac.tail.ts.net:7443/ws?token=ABCDEFGHJKLMNPQR' })
  writeFileSync(file, JSON.stringify({ ...settings, token: 'NEWCODE234567890' }))
  assert.deepEqual(json(await send('GET', '/v1/speech/companion', AUTH)), { url: 'wss://mac.tail.ts.net:7443/ws?token=NEWCODE234567890' })
  assert.equal((await send('GET', '/v1/speech/companion')).status, 401)
})

// ── Small things ─────────────────────────────────────────────────────────
test('pairing keeps which app paired ("lens"), so the Mac can name it', async () => {
  const pair = async (client: unknown) => {
    const { code } = await startPairing(true)
    const r = await send('POST', '/v1/pair', {}, { code, deviceName: 'Even G2 · Lens', model: 'Even G2 · Hypernova Lens', appVersion: '0.3.0', client })
    assert.equal(r.status, 200)
    return listDevices().find((d) => d.id === json(r).deviceId)
  }
  assert.equal((await pair('lens'))?.client, 'lens')
  assert.equal((await pair('<script>'))?.client, undefined)
  assert.equal((await pair(undefined))?.client, undefined)
})

test('events: turn=last starts at the last prompt, output=<n> keeps the end of long tool output', async () => {
  const s = session([prompt('p2', 'Run the tests', '/x'), ...bash('tb1', 'npm test', `${'noise\n'.repeat(2000)}48 tests passed`, '/x'), said('s2', 'All 48 pass.', '/x')])
  const all = json(await send('GET', `/v1/sessions/${s.id}/events?limit=120`, AUTH)).events
  assert.equal(all[0].text, 'Building the export page')
  const last = json(await send('GET', `/v1/sessions/${s.id}/events?limit=120&turn=last&output=4096`, AUTH)).events
  assert.deepEqual(
    last.map((e: any) => e.kind),
    ['user', 'tool', 'assistant'],
  )
  assert.equal(last[0].text, 'Run the tests')
  assert.ok(last[1].output.length <= 4096 + 2 && last[1].output.endsWith('48 tests passed'))
  assert.ok(all[2].output.length > 10000)
})

// ── Show me: where the page is ───────────────────────────────────────────
test('addresses a dev server prints: coloured or not, the last first, never the daemon', () => {
  const vite = '\x1b[32m\x1b[1mVITE\x1b[22m v7.1.0\x1b[39m  ready\n  \x1b[32m➜\x1b[39m  \x1b[1mLocal\x1b[22m:   \x1b[36mhttp://localhost:\x1b[1m5173\x1b[22m/\x1b[39m\n  ➜  Network: use --host'
  assert.deepEqual(loopbackUrls(vite), ['http://localhost:5173/'])
  assert.deepEqual(loopbackUrls('Serving HTTP on :: port 8000 (http://[::]:8000/) ...'), ['http://[::1]:8000/'])
  assert.deepEqual(loopbackUrls('listening on http://0.0.0.0:3000, then http://127.0.0.1:3001/admin.'), ['http://127.0.0.1:3001/admin', 'http://127.0.0.1:3000'])
  assert.deepEqual(loopbackUrls(`see http://127.0.0.1:${API}/v1/health and http://localhost:47322/qa`), [])
  assert.deepEqual(loopbackUrls('https://example.com:8080/ http://10.0.0.2:3000/'), [])
})

test('a page asked for by address: http(s) on loopback only, never the daemon', () => {
  assert.equal(localTarget('http://localhost:5173/settings').toString(), 'http://localhost:5173/settings')
  assert.equal(localTarget('http://[::1]:8000').toString(), 'http://[::1]:8000/')
  assert.equal(localTarget('https://user:pw@127.0.0.1:8443/').toString(), 'https://127.0.0.1:8443/')
  for (const bad of ['http://example.com/', 'http://192.168.1.2:3000/', 'file:///etc/passwd', `http://127.0.0.1:${API}/`, `http://localhost:${LOCAL}/qa`, 'http://localhost:47321/', 'http://localhost.evil.com:3000/', 'javascript:alert(1)']) {
    assert.throws(() => localTarget(bad), (e: any) => e.code === 'not_local', bad)
  }
  assert.throws(() => localTarget('not a url'), (e: any) => e.code === 'bad_url')
})

test('every main-frame address a capture goes to: loopback only, never the daemon, its own preview folder alone', () => {
  for (const ok of ['http://localhost:5173/', 'http://127.0.0.1:3000/landed?x=1', 'https://[::1]:8443/app', 'http://127.1:8080/', 'http://user:pw@localhost:5173/']) assert.ok(mayVisit(ok), ok)
  const bad = [
    'http://example.com/',
    'https://example.invalid/',
    'http://192.168.1.2:3000/',
    'http://100.64.0.1:3000/',
    'http://localhost.evil.com:5173/',
    'http://localhost:5173@evil.com/',
    `http://127.0.0.1:${LOCAL}/qa`,
    `http://localhost:${API}/v1/snapshot`,
    'http://127.0.0.1:47321/',
    'http://[::1]:47322/qa',
    'http://0.0.0.0:3000/',
    'file:///etc/hosts',
    'data:text/html,hi',
    'javascript:alert(1)',
    'chrome://settings',
    'about:blank',
    'not a url',
  ]
  for (const url of bad) assert.equal(mayVisit(url), false, url)

  // Rule 3's preview lives on the daemon's port: its folder, and nothing else there.
  const preview = `http://127.0.0.1:${API}/p/tok-123/index.html`
  for (const ok of [preview, `http://127.0.0.1:${API}/p/tok-123/about.html`, `http://127.0.0.1:${API}/p/tok-123/docs/a.html?x=1#y`, 'http://localhost:5173/']) assert.ok(mayVisit(ok, preview), ok)
  for (const url of [
    `http://127.0.0.1:${API}/v1/snapshot`,
    `http://127.0.0.1:${API}/p/other/index.html`,
    `http://127.0.0.1:${API}/p/tok-1234/index.html`,
    `http://127.0.0.1:${API}/p/tok-123/../../v1/snapshot`,
    `http://127.0.0.1:${API}/p/tok-123/%2e%2e/%2E%2E/v1/snapshot`,
    `http://localhost:${API}/p/tok-123/index.html`,
    `http://127.0.0.1:${LOCAL}/p/tok-123/index.html`,
    `http://127.0.0.1:${LOCAL}/qa`,
  ])
    assert.equal(mayVisit(url, preview), false, url)
})

test("the capture profile has Chrome's preloading off, whatever Chrome saved there", () => {
  const profile = join(scratch, 'profile-prefs')
  const file = join(profile, 'Default', 'Preferences')
  const prefs = () => JSON.parse(readFileSync(file, 'utf8'))
  noPreloading(profile)
  assert.deepEqual(prefs(), { net: { network_prediction_options: 2 } })
  writeFileSync(file, JSON.stringify({ browser: { has_seen_welcome_page: true }, net: { network_prediction_options: 3, other: 1 } }))
  noPreloading(profile)
  assert.deepEqual(prefs(), { browser: { has_seen_welcome_page: true }, net: { network_prediction_options: 2, other: 1 } })
  for (const broken of ['{not json', '[]', '{"net": "x"}']) {
    writeFileSync(file, broken)
    noPreloading(profile)
    assert.equal(prefs().net.network_prediction_options, 2, broken)
  }
})

test("a background command's output file is read only where Claude Code writes them", () => {
  assert.deepEqual(backgroundOf('Command running in background with ID: b8fkrkbas. Output is being written to: /private/tmp/x/tasks/b8fkrkbas.output. You will be notified.'), { id: 'b8fkrkbas', file: '/private/tmp/x/tasks/b8fkrkbas.output' })
  assert.deepEqual(backgroundOf('Command did not complete within its 120s timeout and was moved to the background (ID: b9x). Output is being written to: /tmp/y/tasks/b9x.output.'), { id: 'b9x', file: '/tmp/y/tasks/b9x.output' })
  const tasks = join(scratch, 'claude-501', 'proj', 'sess', 'tasks')
  mkdirSync(tasks, { recursive: true })
  writeFileSync(join(tasks, 'b1.output'), `${'x'.repeat(100_000)}\n  ➜  Local:   http://localhost:5173/\n`)
  assert.match(taskOutputTail(join(tasks, 'b1.output'), 'b1'), /Local: {3}http:\/\/localhost:5173\/\n$/)
  assert.ok(Buffer.byteLength(taskOutputTail(join(tasks, 'b1.output'), 'b1')) <= 64 * 1024 + 4) // the last 64 KB
  assert.equal(taskOutputTail(join(tasks, 'b1.output'), 'other'), '') // not that command's file
  // Not in a tasks folder, or named otherwise, or a link out of the temp folder: nothing.
  const other = join(scratch, 'claude-501', 'proj', 'sess', 'logs')
  mkdirSync(other, { recursive: true })
  writeFileSync(join(other, 'b2.output'), 'http://localhost:5173/')
  assert.equal(taskOutputTail(join(other, 'b2.output'), 'b2'), '')
  writeFileSync(join(tasks, 'notes.txt'), 'http://localhost:5173/')
  assert.equal(taskOutputTail(join(tasks, 'notes.txt'), null), '')
  symlinkSync('/etc/hosts', join(tasks, 'b3.output'))
  assert.equal(taskOutputTail(join(tasks, 'b3.output'), 'b3'), '')
  assert.equal(taskOutputTail('/etc/hosts', null), '')
  assert.equal(taskOutputTail(join(tasks, 'missing.output'), 'missing'), '')
})

test('lsof -F output, as pid → names', () => {
  assert.deepEqual([...lsofNames('p452\nf10\nn*:7000\nf11\nn[::1]:7000\np552\nfcwd\nn/Users/me/app\n')], [
    [452, ['*:7000', '[::1]:7000']],
    [552, ['/Users/me/app']],
  ])
})

// ── Show me: over HTTP ───────────────────────────────────────────────────
test('show me: the address a foreground server command printed, never one only mentioned; the picture needs no token', async () => {
  const s = session([
    ...bash('t1', 'curl -s http://127.0.0.1:' + API + '/v1/health', `{"ok":true} http://127.0.0.1:${API}/`, '/x'),
    ...bash('t2', 'npm run dev', `> vite\n\n  VITE v7 ready in 300 ms\n\n  ➜  Local:   http://localhost:${DEV}/\n`, '/x'),
    ...bash('t3', 'curl -s http://localhost:47322/qa', 'http://localhost:47322/qa', '/x'),
  ])
  const r = await shoot(s.id, { width: 640, height: 480 })
  assert.equal(r.status, 200, r.body.toString())
  const { shot } = json(r)
  assert.match(shot.url, /^\/v1\/shots\/[0-9a-f]{24}\.png$/)
  assert.equal(shot.width, 640)
  assert.equal(shot.height, 480)
  assert.equal(shot.title, 'Hyperscribe — Export')
  assert.equal(shot.source, 'url')
  assert.equal(shot.pageUrl, `http://localhost:${DEV}/`)
  assert.ok(Math.abs(shot.at - Date.now()) < 10_000)
  assert.equal(lastNavigation(), `http://localhost:${DEV}/`)
  assert.equal(r.headers['access-control-allow-origin'], '*')

  // The picture: no bearer header, CORS (a canvas reads it), cached privately, private on disk.
  const img = await send('GET', shot.url, { origin: 'https://plugin.example' })
  assert.equal(img.status, 200)
  assert.equal(img.headers['content-type'], 'image/png')
  assert.equal(img.headers['access-control-allow-origin'], '*')
  assert.equal(img.headers['cache-control'], 'private, max-age=43200')
  assert.equal(img.body.readUInt32BE(16), 640)
  const file = join(SHOTS_DIR, shot.url.split('/').at(-1))
  assert.equal(statSync(file).mode & 0o777, 0o600)
  assert.equal(statSync(SHOTS_DIR).mode & 0o777, 0o700)
  // Unknown, malformed or expired: gone.
  for (const p of ['/v1/shots/0123456789abcdef01234567.png', '/v1/shots/..%2F..%2Fdevices.json.png', '/v1/shots/x.png']) {
    const gone = await send('GET', p)
    assert.equal(gone.status, 404, p)
    assert.equal(errorCode(gone), 'shot_expired')
    assert.equal(gone.headers['access-control-allow-origin'], '*')
  }
  const old = (Date.now() - 13 * 3600_000) / 1000
  utimesSync(file, old, old)
  assert.equal((await send('GET', shot.url)).status, 404)
})

test("show me: a background server's address, from its output file or, once that is gone, from Claude reading it", async () => {
  const tasks = join(scratch, 'claude-501', 'proj', 'sess2', 'tasks')
  mkdirSync(tasks, { recursive: true })
  writeFileSync(join(tasks, 'bgdev1.output'), `> next dev\n   ▲ Next.js 16\n   - Local:        http://localhost:${DEV}\n   - Network:      http://192.168.1.20:${DEV}\n`)
  const a = session([...background('t1', 'npm run dev', 'bgdev1', join(tasks, 'bgdev1.output'), '/x'), said('s1', 'The dev server is running.', '/x')])
  const r = await shoot(a.id)
  assert.equal(r.status, 200, r.body.toString())
  assert.equal(json(r).shot.pageUrl, `http://localhost:${DEV}`)
  assert.equal(json(r).shot.width, 1280)
  assert.equal(json(r).shot.height, 800)

  // Claude Code removed the file; Claude had read it with tail.
  const gone = join(tasks, 'bgdev2.output')
  const b = session([
    ...background('t1', 'pnpm dev', 'bgdev2', gone, '/x'),
    ...bash('t2', `sleep 2; tail -5 ${gone}`, `  ➜  Local:   http://127.0.0.1:${DEV}/\n`, '/x'),
  ])
  const r2 = await shoot(b.id)
  assert.equal(r2.status, 200, r2.body.toString())
  assert.equal(json(r2).shot.pageUrl, `http://127.0.0.1:${DEV}/`)
})

test('show me: a server listening in the session’s folder, whoever started it', async () => {
  const s = session([said('s1', 'Started it in another terminal.', '/x')])
  // A server whose working folder is inside the session's (the background case once more).
  const sub = join(s.cwd, 'web')
  mkdirSync(sub)
  const child = spawn(process.execPath, ['-e', "require('http').createServer((q,r)=>r.end('<title>Folder app</title>ok')).listen(0,'127.0.0.1',function(){console.log(this.address().port)})"], { cwd: sub, stdio: ['ignore', 'pipe', 'ignore'] })
  children.push(child)
  const port = await new Promise<number>((resolve) => child.stdout!.once('data', (d) => resolve(Number(String(d).trim()))))
  const r = await shoot(s.id)
  assert.equal(r.status, 200, r.body.toString())
  assert.equal(json(r).shot.pageUrl, `http://localhost:${port}/`)
  child.kill('SIGKILL')
})

test('show me: else the newest HTML file the session wrote, through a preview so its links work', async () => {
  const s = session([])
  const page = join(s.cwd, 'site', 'index.html')
  mkdirSync(dirname(page))
  writeFileSync(page, '<!doctype html><title>Static</title><link rel="stylesheet" href="style.css"><h1>Hi</h1>')
  writeFileSync(transcriptPathFor(s.cwd, s.id), [prompt('p0', 'Make a page', s.cwd), ...tool('w1', 'Write', { file_path: page, content: '…' }, 'File created', s.cwd, { type: 'create', filePath: page, content: '<h1>Hi</h1>' })].join('\n') + '\n')
  upsertSession(s.id, { parse: null })
  const r = await shoot(s.id)
  assert.equal(r.status, 200, r.body.toString())
  const { shot } = json(r)
  assert.equal(shot.pageUrl, null)
  assert.equal(shot.path, realpathSync(page))
  const nav = lastNavigation()!
  assert.match(nav, new RegExp(`^http://127\\.0\\.0\\.1:${API}/p/[\\w-]+/index\\.html$`))
  // That preview serves the file and its neighbours.
  assert.equal((await send('GET', new URL(nav).pathname)).status, 200)
})

test('show me: the errors', async () => {
  const s = session([])
  const nothing = await shoot(s.id)
  assert.equal(nothing.status, 404)
  assert.equal(errorCode(nothing), 'nothing_to_show')
  assert.equal(errorCode(await shoot('no-such-session')), 'no_session')
  assert.equal((await send('POST', `/v1/sessions/${s.id}/screenshot`, {}, {})).status, 401)
  for (const url of ['http://example.com/', `http://127.0.0.1:${API}/v1/snapshot`, `http://localhost:${LOCAL}/local/status`]) {
    const r = await shoot(s.id, { url })
    assert.equal(r.status, 403, url)
    assert.equal(errorCode(r), 'not_local')
  }
  // An address that answers is shot as asked; one where nothing answers says so.
  const asked = await shoot(s.id, { url: `http://localhost:${DEV}/settings`, width: 100, height: 99999 })
  assert.equal(asked.status, 200)
  assert.equal(json(asked).shot.pageUrl, `http://localhost:${DEV}/settings`)
  assert.deepEqual([json(asked).shot.width, json(asked).shot.height], [320, 2560])
  const closed = await freePort()
  const none = await shoot(s.id, { url: `http://localhost:${closed}/` })
  assert.equal(none.status, 502)
  assert.equal(errorCode(none), 'shot_failed')

  try {
    process.env.FAKE_CHROME_MODE = 'garbage'
    assert.equal(errorCode(await shoot(s.id, { url: `http://localhost:${DEV}/` })), 'shot_failed')
    process.env.FAKE_CHROME_MODE = 'refused'
    assert.equal(errorCode(await shoot(s.id, { url: `http://localhost:${DEV}/` })), 'shot_failed')
    process.env.FAKE_CHROME_MODE = 'ok'
    process.env.FAKE_CHROME_DELAY = '600'
    const first = shoot(s.id, { url: `http://localhost:${DEV}/` })
    await new Promise((r) => setTimeout(r, 150))
    const busy = await shoot(s.id, { url: `http://localhost:${DEV}/` })
    assert.equal(busy.status, 409)
    assert.equal(errorCode(busy), 'busy')
    assert.equal((await first).status, 200)
    process.env.FAKE_CHROME_DELAY = '0'
    process.env.HYPERNOVA_CHROME = join(scratch, 'no-chrome-here')
    const none2 = await shoot(s.id, { url: `http://localhost:${DEV}/` })
    assert.equal(none2.status, 501)
    assert.equal(errorCode(none2), 'no_browser')
  } finally {
    process.env.FAKE_CHROME_MODE = 'ok'
    process.env.FAKE_CHROME_DELAY = '0'
    process.env.HYPERNOVA_CHROME = fakeChrome
  }
})

test('show me: each capture in a throwaway context with downloads denied, and every main-frame address checked', async () => {
  const s = session([])
  const calls = join(scratch, 'chrome-calls.log')
  const read = () =>
    readFileSync(calls, 'utf8')
      .trim()
      .split('\n')
      .map((l) => ({ method: l.slice(0, l.indexOf(' ')), params: JSON.parse(l.slice(l.indexOf(' ') + 1)) }))
  process.env.FAKE_CHROME_CALLS = calls
  try {
    // A redirect elsewhere on this Mac's loopback is followed…
    process.env.FAKE_CHROME_REDIRECT = `http://127.0.0.1:${DEV}/landed`
    const ok = await shoot(s.id, { url: `http://localhost:${DEV}/` })
    assert.equal(ok.status, 200, ok.body.toString())
    assert.equal(json(ok).shot.pageUrl, `http://localhost:${DEV}/`)
    const log = read()
    const first = (m: string) => log.findIndex((c) => c.method === m)
    const params = (m: string) => log[first(m)]?.params
    assert.deepEqual(params('Target.createBrowserContext'), { disposeOnDetach: true })
    assert.deepEqual(params('Browser.setDownloadBehavior'), { behavior: 'deny', browserContextId: 'C1' })
    assert.deepEqual(params('Target.createTarget'), { url: 'about:blank', browserContextId: 'C1' })
    assert.deepEqual(params('Fetch.enable'), { patterns: [{ resourceType: 'Document', requestStage: 'Request' }] })
    assert.ok(first('Browser.setDownloadBehavior') < first('Target.createTarget') && first('Fetch.enable') < first('Page.navigate'))
    assert.equal(log.filter((c) => c.method === 'Fetch.continueRequest').length, 2)
    assert.deepEqual(params('Target.disposeBrowserContext'), { browserContextId: 'C1' })

    // …one to the daemon's own ports, or off the Mac, isn't, and the capture says where it went.
    for (const to of [`http://127.0.0.1:${LOCAL}/qa`, `http://localhost:${API}/v1/snapshot`, 'http://127.0.0.1:47322/qa', 'http://192.168.1.2:3000/', 'https://example.com/']) {
      writeFileSync(calls, '')
      process.env.FAKE_CHROME_REDIRECT = to
      const r = await shoot(s.id, { url: `http://localhost:${DEV}/` })
      assert.equal(r.status, 403, to)
      assert.equal(errorCode(r), 'not_local')
      assert.ok(json(r).error.message.includes(new URL(to).host), json(r).error.message)
      const blocked = read()
      assert.deepEqual(blocked.filter((c) => c.method.startsWith('Fetch.') && c.method !== 'Fetch.enable').map((c) => c.method), ['Fetch.continueRequest', 'Fetch.failRequest'])
      assert.equal(blocked.find((c) => c.method === 'Fetch.failRequest')?.params.errorReason, 'BlockedByClient')
      assert.ok(blocked.some((c) => c.method === 'Target.disposeBrowserContext'), 'the context goes on failure too')
    }

    // A request that names no frame is checked like the main frame's: refused before it's sent.
    process.env.FAKE_CHROME_FRAME = 'none'
    process.env.FAKE_CHROME_REDIRECT = `http://127.0.0.1:${LOCAL}/qa`
    writeFileSync(calls, '')
    assert.equal(errorCode(await shoot(s.id, { url: `http://localhost:${DEV}/` })), 'not_local')
    assert.equal(read().find((c) => c.method === 'Fetch.failRequest')?.params.errorReason, 'BlockedByClient')
    process.env.FAKE_CHROME_REDIRECT = `http://127.0.0.1:${DEV}/landed`
    assert.equal((await shoot(s.id, { url: `http://localhost:${DEV}/` })).status, 200)
  } finally {
    delete process.env.FAKE_CHROME_REDIRECT
    delete process.env.FAKE_CHROME_CALLS
    delete process.env.FAKE_CHROME_FRAME
  }
})

// ── The real Chrome, when this Mac has it ────────────────────────────────
const realChrome = (() => {
  const env = process.env.HYPERNOVA_CHROME
  delete process.env.HYPERNOVA_CHROME
  const c = findChrome()
  process.env.HYPERNOVA_CHROME = env
  return c
})()
test('headless Chrome draws the page at the size asked for', { skip: !realChrome && 'no Chrome or Chromium on this Mac', timeout: 60_000 }, async () => {
  process.env.HYPERNOVA_CHROME = realChrome!
  try {
    const shot = await render(`http://localhost:${DEV}/`, 640, 400)
    assert.equal(shot.title, 'Dev server')
    assert.deepEqual([shot.width, shot.height], [640, 400])
    const png = readFileSync(join(SHOTS_DIR, `${shot.token}.png`))
    assert.equal(png.readUInt32BE(16), 640)
    assert.equal(png.readUInt32BE(20), 400)
  } finally {
    process.env.HYPERNOVA_CHROME = fakeChrome
  }
})

test('headless Chrome: a page can’t take it off the Mac or onto the daemon, and no capture sees another’s cookies', { skip: !realChrome && 'no Chrome or Chromium on this Mac', timeout: 90_000 }, async () => {
  process.env.HYPERNOVA_CHROME = realChrome!
  try {
    // Redirects on this Mac's loopback are followed.
    assert.equal((await render(`http://localhost:${DEV}/hop`, 640, 400)).title, 'Landed')
    for (const path of ['/to-daemon', '/off-mac', '/script-to-daemon']) {
      await assert.rejects(render(`http://localhost:${DEV}${path}`, 640, 400), (e: any) => e.status === 403 && e.code === 'not_local', path)
    }
    // A cookie one capture got is gone by the next (they'd share it: cookies ignore the port).
    assert.equal((await render(`http://127.0.0.1:${DEV}/set-cookie`, 640, 400)).title, 'Cookie set')
    assert.equal((await render(`http://127.0.0.1:${DEV}/cookie`, 640, 400)).title, 'cookie:')
    // Speculation rules fetch nothing ahead: those requests never reach the Fetch check, and a page
    // fetched that way would be shown without being asked about. Chrome keeps the setting.
    assert.equal((await render(`http://localhost:${DEV}/speculates`, 640, 400)).title, 'Speculates')
    assert.deepEqual(speculative, [])
    assert.equal(JSON.parse(readFileSync(join(SHOTS_DIR, 'chrome', 'Default', 'Preferences'), 'utf8')).net?.network_prediction_options, 2)
  } finally {
    process.env.HYPERNOVA_CHROME = fakeChrome
  }
})

test('headless Chrome: an HTML file, through its preview on the daemon’s port, may move within its folder and nowhere else there', { skip: !realChrome && 'no Chrome or Chromium on this Mac', timeout: 90_000 }, async () => {
  /** A session that wrote site/index.html with this script, next to site/next.html. */
  const wrote = (script: string) => {
    const s = session([])
    const page = join(s.cwd, 'site', 'index.html')
    mkdirSync(dirname(page))
    writeFileSync(page, `<!doctype html><title>First page</title><script>${script}</script>`)
    writeFileSync(join(s.cwd, 'site', 'next.html'), '<!doctype html><title>Next page</title>')
    writeFileSync(transcriptPathFor(s.cwd, s.id), [prompt('p0', 'Make a page', s.cwd), ...tool('w1', 'Write', { file_path: page, content: '…' }, 'File created', s.cwd, { type: 'create', filePath: page, content: '…' })].join('\n') + '\n')
    upsertSession(s.id, { parse: null })
    return s
  }
  process.env.HYPERNOVA_CHROME = realChrome!
  try {
    const within = await shoot(wrote("location.href = 'next.html'").id)
    assert.equal(within.status, 200, within.body.toString())
    assert.equal(json(within).shot.title, 'Next page')
    for (const to of ['/v1/snapshot', `http://127.0.0.1:${LOCAL}/qa`, '/p/someone-elses/index.html']) {
      const r = await shoot(wrote(`location.href = '${to}'`).id)
      assert.equal(r.status, 403, `${to}: ${r.body}`)
      assert.equal(errorCode(r), 'not_local')
    }
  } finally {
    process.env.HYPERNOVA_CHROME = fakeChrome
  }
})
