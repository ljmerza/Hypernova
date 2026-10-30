// Read aloud with the Mac's voices (docs/PROTOCOL.md, "Read aloud"): the setting, whole clips and
// the phone's own plays; and the small things around them: which app paired, a long tool output cut
// to its end. Everything runs on free ports with scratch state; Tailscale is a stand-in. The Mac's
// voice is the real `say`, so the tests where it reads run on a Mac only.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'

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
const { addDevice, adminToken, listDevices } = await import('../src/store.ts')
const { upsertSession, transcriptPathFor } = await import('../src/hub.ts')
const { parseVoices, keepClip, recentClip, RECENT_MS } = await import('../src/speech.ts')
const { playLimits, ttsPieces } = await import('../src/readaloud.ts')

const TOKEN = 'test-phone-token-0123456789abcdef'
const OTHER = 'test-other-token-0123456789abcdef'
addDevice({ id: 'phone', name: 'Phone', model: 'P3', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)
addDevice({ id: 'phone2', name: 'Other phone', model: 'P4', createdAt: Date.now(), lastSeenAt: Date.now() }, OTHER)
const AUTH = { authorization: `Bearer ${TOKEN}` }

let close: () => Promise<void>
before(async () => {
  close = startServers()
  for (let i = 0; i < 50; i++) {
    try {
      if ((await send('GET', '/v1/health')).status === 200) break
    } catch {}
    await new Promise((r) => setTimeout(r, 20))
  }
})
after(async () => {
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

test('pieces cut after sentences, the first one short, and keep where each starts', () => {
  const text = 'One sentence here. '.repeat(40) + 'And the end.'
  const pieces = ttsPieces(text)
  assert.ok(pieces.length >= 2)
  assert.ok(pieces[0]!.text.length <= 320)
  assert.ok(pieces.every((p) => p.text.length <= 1100))
  assert.ok(pieces.every((p) => text.slice(p.at, p.at + p.text.length) === p.text))
  assert.ok(pieces.slice(0, -1).every((p) => /[.!?]\s?$/.test(p.text.trimEnd())), 'cut after a sentence')
  assert.equal(pieces.map((p) => p.text).join(''), text)
  // One long word: cut anyway.
  assert.ok(ttsPieces('x'.repeat(2500)).every((p) => p.text.length <= 1100))
})

test('the setting: the Mac’s voice only, checked and kept; an older Soniox engine is refused', async () => {
  const snap = json(await send('GET', '/v1/snapshot', AUTH))
  assert.deepEqual(snap.speech, { engine: 'mac', using: 'mac', macVoice: null, rate: 1 })
  assert.equal(snap.stt, undefined)
  assert.equal(errorCode(await send('POST', '/v1/speech/settings', AUTH, { engine: 'soniox' })), 'bad_engine')
  assert.equal(errorCode(await send('POST', '/v1/speech/settings', AUTH, { rate: 'fast' })), 'bad_rate')
  const r = json(await send('POST', '/v1/speech/settings', AUTH, { engine: 'mac', rate: 9 }))
  assert.deepEqual(r.speech, { engine: 'mac', using: 'mac', macVoice: null, rate: 2 })
  assert.deepEqual(JSON.parse(readFileSync(join(process.env.HYPERNOVA_SUPPORT!, 'speech.json'), 'utf8')), { rate: 2 })
  await send('POST', '/v1/speech/settings', AUTH, { rate: 1 })
  // The routes Soniox had are gone.
  assert.equal((await send('GET', '/v1/stt', AUTH)).status, 404)
  assert.equal((await send('GET', '/v1/speech/companion', AUTH)).status, 404)
  const voices = json(await send('GET', '/v1/speech/voices?engine=soniox', AUTH))
  assert.equal(voices.engine, 'mac')
})

test('a play is the asking phone’s: only it may fetch or stop it, besides the Mac app', async () => {
  const r = await send('POST', '/v1/speech/play', AUTH, { text: 'Read on this phone.', to: 'me' })
  assert.equal(r.status, 200)
  const play = json(r).play
  assert.deepEqual([play.to.id, play.source, play.engine, play.sampleRate, play.format], ['phone', 'phone', 'mac', 24_000, 'pcm_s16le'])
  const other = { authorization: `Bearer ${OTHER}` }
  assert.equal((await send('GET', `/v1/speech/plays/${play.id}/audio`, other)).status, 404)
  assert.equal((await send('POST', `/v1/speech/plays/${play.id}/stop`, other)).status, 404)
  assert.equal((await send('POST', '/v1/speech/play', {}, { text: 'x', to: 'me' })).status, 401)
  assert.equal(errorCode(await send('POST', '/v1/speech/play', { authorization: `Bearer ${adminToken}` }, { text: 'x' }, LOCAL)), 'bad_target')
  assert.equal(errorCode(await send('POST', '/v1/speech/play', AUTH, { text: '  ' })), 'empty')
  assert.equal((await send('POST', `/v1/speech/plays/${play.id}/stop`, AUTH)).status, 200)
})

test('the phone’s own Read aloud plays on it in the Mac’s voice, as 24 kHz PCM', { skip: !hasSay && 'no /usr/bin/say' }, async () => {
  Object.assign(playLimits, { startMs: 12_000 })
  const r = json(await send('POST', '/v1/speech/play', AUTH, { text: 'Read on the phone itself.', to: 'me', sessionId: 's2' }))
  const audio = await send('GET', `/v1/speech/plays/${r.play.id}/audio`, AUTH)
  assert.equal(audio.status, 200)
  assert.equal(audio.headers['x-speech-engine'], 'mac')
  assert.equal(audio.headers['x-sample-rate'], '24000')
  assert.ok(audio.body.length > 24_000, 'about a second of the Mac’s voice at least')
})

test('the text never reaches the log', async () => {
  await send('POST', '/v1/speech', AUTH, { text: 'A secret plan for Thursday.' })
  await send('POST', '/v1/speech/play', AUTH, { text: 'Another secret for Friday.', to: 'me' })
  const logText = readFileSync(join(process.env.HYPERNOVA_LOGS!, 'daemon.log'), 'utf8')
  assert.ok(!logText.includes('secret plan') && !logText.includes('Thursday'))
  assert.ok(!logText.includes('Another secret') && !logText.includes('Friday'))
})

// ── Small things ─────────────────────────────────────────────────────────
test('pairing keeps which app paired, so the Mac can name it', async () => {
  const pair = async (client: unknown) => {
    const { code } = await startPairing(true)
    const r = await send('POST', '/v1/pair', {}, { code, deviceName: 'Pixel', model: 'Pixel 9', appVersion: '0.3.0', client })
    assert.equal(r.status, 200)
    return listDevices().find((d) => d.id === json(r).deviceId)
  }
  assert.equal((await pair('other-app'))?.client, 'other-app')
  assert.equal((await pair('<script>'))?.client, undefined)
  assert.equal((await pair(undefined))?.client, undefined)
})

test('events: output=<n> keeps the end of long tool output', async () => {
  const s = session([prompt('p2', 'Run the tests', '/x'), ...bash('tb1', 'npm test', `${'noise\n'.repeat(2000)}48 tests passed`, '/x'), said('s2', 'All 48 pass.', '/x')])
  const all = json(await send('GET', `/v1/sessions/${s.id}/events?limit=120`, AUTH)).events
  assert.equal(all[0].text, 'Building the export page')
  assert.ok(all[2].output.length > 10000)
  const cut = json(await send('GET', `/v1/sessions/${s.id}/events?limit=120&output=4096`, AUTH)).events
  assert.deepEqual(
    cut.map((e: any) => e.kind),
    ['user', 'user', 'tool', 'assistant'],
  )
  assert.ok(cut[2].output.length <= 4096 + 2 && cut[2].output.endsWith('48 tests passed'))
})
