// Speech to text through Soniox (docs/PROTOCOL.md, "Speech to text") and the Lens guide ("The Lens
// guide"). Soniox is a stand-in on a free port that speaks its real-time protocol as documented
// (auth header, config first, binary audio, tokens final and not, an empty frame to end, finished,
// error frames); the daemon runs on free ports with scratch state.
import { test, before, after, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { WebSocket as WsClient, WebSocketServer } from 'ws'

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hypernova-test-')))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
mkdirSync(join(process.env.HOME, 'Programming', 'hyperscribe'), { recursive: true })
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
const SONIOX = await freePort()
process.env.HYPERNOVA_API_PORT = String(API)
process.env.HYPERNOVA_LOCAL_PORT = String(LOCAL)
// The LAN listener (lan.ts) takes a free port: test files run side by side.
process.env.HYPERNOVA_LAN_PORT = '0'
process.env.HYPERNOVA_SONIOX_WS = `ws://127.0.0.1:${SONIOX}/transcribe-websocket`

// ── The Soniox stand-in ──────────────────────────────────────────────────
const GOOD = 'sx_good_0123456789abcdef0123456789abcdef'
const POOR = 'sx_poor_0123456789abcdef0123456789abcdef'
/** What the stand-in saw, per stream: [order] is each message as it came (config, audio, keepalive, end). */
type Seen = { auth: string; config: any; audio: number; keepalives: number; ended: boolean; order: string[] }
const seen: Seen[] = []
/**
 * The next stream's behaviour: words to say, or an error to send after the first audio; [openDelayMs]
 * holds the handshake back, as a slow link to Soniox would.
 */
const fake = { script: [] as any[], failWith: null as null | { code: number; type: string }, silentEnd: false, openDelayMs: 0 }
const sx = new WebSocketServer({
  port: SONIOX,
  host: '127.0.0.1',
  path: '/transcribe-websocket',
  verifyClient: (_info, done) => (fake.openDelayMs ? setTimeout(() => done(true), fake.openDelayMs) : done(true)),
})
sx.on('connection', (ws, req) => {
  const s: Seen = { auth: String(req.headers.authorization ?? ''), config: null, audio: 0, keepalives: 0, ended: false, order: [] }
  seen.push(s)
  const key = s.auth.replace(/^Bearer /, '')
  const error = (code: number, type: string, message: string) => {
    ws.send(JSON.stringify({ tokens: [], error_code: code, error_type: type, error_message: message, request_id: 'r-1' }))
    ws.close(1000)
  }
  const script = [...fake.script]
  let pending: any[] = []
  ws.on('message', (data, binary) => {
    if (!binary) {
      const text = String(data)
      s.order.push(s.config === null ? 'config' : text === '' ? 'end' : 'keepalive')
      if (s.config === null) {
        s.config = JSON.parse(text)
        if (key !== GOOD && key !== POOR) return error(401, 'unauthenticated', 'Incorrect API key provided. You can get an API key at https://console.soniox.com')
        return
      }
      if (text === '') {
        s.ended = true
        if (fake.silentEnd) return
        // End of audio: what wasn't final becomes final, then finished, then the server closes.
        if (pending.length) ws.send(JSON.stringify({ tokens: pending.map((t) => ({ ...t, is_final: true })), final_audio_proc_ms: 900, total_audio_proc_ms: 900 }))
        ws.send(JSON.stringify({ tokens: [], final_audio_proc_ms: 900, total_audio_proc_ms: 900, finished: true }))
        ws.close(1000)
        return
      }
      if (JSON.parse(text).type === 'keepalive') s.keepalives++
      return
    }
    s.audio += (data as Buffer).length
    s.order.push('audio')
    if (key === POOR) return error(402, 'organization_balance_exhausted', 'Organization balance exhausted.')
    if (fake.failWith) return error(fake.failWith.code, fake.failWith.type, 'Nope.')
    const next = script.shift()
    if (next) {
      pending = next.filter((t: any) => !t.is_final)
      ws.send(JSON.stringify({ tokens: next, final_audio_proc_ms: 100, total_audio_proc_ms: 200 }))
    }
  })
})

const { startServers } = await import('../src/http.ts')
const { addDevice, adminToken } = await import('../src/store.ts')
const { captions, contextTerms, languageHints, LENS_TERMS, sttLimits, sttStreams } = await import('../src/stt.ts')
const TOKEN = 'test-lens-token-0123456789abcdef'
const OTHER = 'test-phone-token-0123456789abcdef'
addDevice({ id: 'glasses', name: 'Glasses', model: 'G2', createdAt: Date.now(), lastSeenAt: Date.now(), client: 'lens' }, TOKEN)
addDevice({ id: 'phone', name: 'Phone', model: 'P3', createdAt: Date.now(), lastSeenAt: Date.now() }, OTHER)
const AUTH = { authorization: `Bearer ${TOKEN}` }
const LIMITS = { ...sttLimits }

let close: () => Promise<void>
before(async () => {
  close = startServers()
  for (let i = 0; i < 50; i++) {
    try {
      if ((await send(API, 'GET', '/v1/health')).status === 200) return
    } catch {}
    await sleep(20)
  }
})
after(async () => {
  await close?.()
  for (const c of sx.clients) c.terminate()
  await new Promise((r) => sx.close(r))
  rmSync(scratch, { recursive: true, force: true })
})
beforeEach(() => {
  Object.assign(sttLimits, LIMITS)
  Object.assign(fake, { script: [], failWith: null, silentEnd: false, openDelayMs: 0 })
})

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
type Reply = { status: number; body: any; text: string }
function send(port: number, method: string, path: string, headers: Record<string, string> = {}, body?: unknown): Promise<Reply> {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? undefined : JSON.stringify(body)
    const req = request({ host: '127.0.0.1', port, method, path, headers: { host: `127.0.0.1:${port}`, ...(data ? { 'content-type': 'application/json' } : {}), ...headers } }, (res) => {
      let text = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (text += c))
      res.on('end', () => {
        let b: any = null
        try {
          b = JSON.parse(text)
        } catch {}
        resolve({ status: res.statusCode ?? 0, body: b, text })
      })
    })
    req.on('error', reject)
    req.end(data)
  })
}

/** A phone's stream, as Lens' companion client drives it: every message it gets, in order. */
class Phone {
  ws: WebSocket
  got: any[] = []
  closed: number | null = null
  private waiters: Array<() => void> = []
  constructor(token = TOKEN) {
    this.ws = new WebSocket(`ws://127.0.0.1:${API}/v1/stt/ws?token=${token}`)
    this.ws.binaryType = 'arraybuffer'
    this.ws.onmessage = (m) => {
      this.got.push(JSON.parse(String(m.data)))
      this.wake()
    }
    this.ws.onclose = (e) => {
      this.closed = e.code
      this.wake()
    }
  }
  private wake() {
    for (const w of this.waiters.splice(0)) w()
  }
  async until(pred: (got: any[]) => boolean, ms = 4000): Promise<any[]> {
    const t0 = Date.now()
    while (!pred(this.got)) {
      if (Date.now() - t0 > ms) throw new Error(`timed out; got ${JSON.stringify(this.got)} closed ${this.closed}`)
      await new Promise<void>((r) => {
        this.waiters.push(r)
        setTimeout(r, 50)
      })
    }
    return this.got
  }
  of(type: string) {
    return this.got.filter((m) => m.type === type)
  }
  json(m: object) {
    this.ws.send(JSON.stringify(m))
  }
  audio(bytes = 1280) {
    this.ws.send(new Uint8Array(bytes))
  }
  async started(language = 'en') {
    await this.until((g) => g.some((m) => m.type === 'hello'))
    this.json({ type: 'start', language })
    await this.until((g) => g.some((m) => m.type === 'started' || m.type === 'error'))
  }
}

const token = (text: string, is_final: boolean) => ({ text, is_final, start_ms: 0, end_ms: 100, confidence: 0.9 })

// ── Words ────────────────────────────────────────────────────────────────
test('captions split between words: a word Soniox has half settled stays whole on the second side', () => {
  assert.deepEqual(captions('', 'Hel'), { finalText: '', interimText: 'Hel' })
  assert.deepEqual(captions('Hel', 'lo'), { finalText: '', interimText: 'Hello' })
  assert.deepEqual(captions('Run the te', 'sts now'), { finalText: 'Run the', interimText: 'tests now' })
  assert.deepEqual(captions('Run the ', 'tests'), { finalText: 'Run the', interimText: 'tests' })
  assert.deepEqual(captions('Run the', ' tests'), { finalText: 'Run the', interimText: 'tests' })
  assert.deepEqual(captions(' Hello  world.', ''), { finalText: 'Hello world.', interimText: '' })
})

test('language hints: the setting with English beside it; "auto" hints English and Russian', () => {
  assert.deepEqual(languageHints('en'), ['en'])
  assert.deepEqual(languageHints('ru'), ['ru', 'en'])
  assert.deepEqual(languageHints('auto'), ['en', 'ru'])
  assert.deepEqual(languageHints(undefined), ['en', 'ru'])
  assert.deepEqual(languageHints('<script>'), ['en', 'ru'])
})

test('context terms: only the projects in use, as words, each once, twenty at most; then Lens\'s command words', () => {
  const now = Date.UTC(2026, 9, 15)
  const day = 86_400_000
  const p = (name: string, sessionCount: number, daysAgo: number | null) => ({ name, sessionCount, lastActivityAt: daysAgo == null ? null : now - daysAgo * day })
  const terms = contextTerms([
    p('fuel_charge_statistics', 0, 400), // an old folder never used with Claude: stays on the Mac
    p('hyper-scribe', 3, 2),
    p('Hyper_Scribe', 1, 1), // the same words, another case: once
    p('hypernova', 9, 0), // already there as "Hypernova"
    p('0stems', 2, 5),
    p('fresh-idea', 0, 3), // just made, no session yet
    p('no-date', 0, null),
  ], now)
  // Newest first: hypernova (already in), Hyper_Scribe, hyper-scribe (the same words), fresh-idea, 0stems.
  // Then the words Lens acts on, so "Telescope terminal" isn't heard as "To the scope terminal".
  assert.deepEqual(terms, ['Claude Code', 'Hypernova', 'Hyper Scribe', 'fresh idea', '0stems', ...LENS_TERMS])
  for (const w of ['Telescope', 'debrief', 'eclipse', 'note', 'terminal', 'emulator', 'simulator']) assert.ok(terms.includes(w), w)
  // Many projects: still twenty of them and the rest; a project named like a command word goes once.
  const many = contextTerms(Array.from({ length: 60 }, (_, i) => p(`project_${i}`, 1, i)), now)
  assert.equal(many.length, 20 + LENS_TERMS.length)
  assert.deepEqual(many.slice(0, 4), ['Claude Code', 'Hypernova', 'project 0', 'project 1'])
  assert.deepEqual(many.slice(20), LENS_TERMS)
  const named = contextTerms([p('terminal', 1, 0)], now)
  assert.equal(named.filter((t) => t.toLowerCase() === 'terminal').length, 1)
})

// ── The key ──────────────────────────────────────────────────────────────
test('no key: the snapshot says voice is off, and a stream says to connect Soniox on the Mac', async () => {
  const snap = await send(API, 'GET', '/v1/snapshot', AUTH)
  assert.deepEqual(snap.body.stt, { provider: null, ready: false, error: null })
  const p = new Phone()
  await p.until((g) => g.length > 0 && p.closed !== null)
  assert.equal(p.got[0].type, 'error')
  assert.equal(p.got[0].code, 'no_key')
  assert.match(p.got[0].message, /Soniox/)
})

test('a key Soniox refuses is never kept; a good one is tested by a real stream, kept 0600 and never sent back', async () => {
  const bad = await send(API, 'POST', '/v1/stt/key', AUTH, { apiKey: 'sx_wrong_0123456789abcdef0123456789' })
  assert.equal(bad.status, 400)
  assert.equal(bad.body.error.code, 'bad_key')
  assert.equal(existsSync(join(process.env.HYPERNOVA_SUPPORT!, 'stt.json')), false)
  assert.equal((await send(API, 'POST', '/v1/stt/key', AUTH, { apiKey: 'has space in it 0123456789' })).body.error.code, 'bad_key')
  assert.equal((await send(API, 'POST', '/v1/stt/key', AUTH, { apiKey: '' })).body.error.code, 'no_key')
  assert.equal((await send(API, 'POST', '/v1/stt/key', AUTH, { provider: 'other', apiKey: GOOD })).body.error.code, 'bad_provider')

  // Everyone on the live feed hears that voice is ready.
  const feed = new WebSocket(`ws://127.0.0.1:${API}/v1/ws?token=${OTHER}`)
  const heard: any[] = []
  feed.onmessage = (m) => heard.push(JSON.parse(String(m.data)))
  await new Promise((r) => (feed.onopen = r))

  const before = seen.length
  const ok = await send(API, 'POST', '/v1/stt/key', AUTH, { apiKey: `  ${GOOD}\n` })
  assert.equal(ok.status, 200)
  assert.equal(ok.body.provider, 'soniox')
  assert.equal(ok.body.ready, true)
  assert.equal(ok.body.checked, true)
  assert.equal(ok.body.problem, null)
  assert.equal(ok.body.last4, GOOD.slice(-4))
  assert.equal(ok.body.model, 'stt-rt-v5')
  assert.ok(!ok.text.includes(GOOD), 'the key never comes back')
  // The test was a real stream: the key in the header, config first, some audio, then the end.
  const probe = seen[before]!
  assert.equal(probe.auth, `Bearer ${GOOD}`)
  assert.equal(probe.config.api_key, undefined, 'never in the config (deprecated there)')
  assert.equal(probe.config.context, undefined, 'a key\u2019s test sends no project names')
  assert.ok(probe.audio > 0 && probe.ended)

  const file = join(process.env.HYPERNOVA_SUPPORT!, 'stt.json')
  assert.equal(statSync(file).mode & 0o777, 0o600)
  assert.equal(JSON.parse(readFileSync(file, 'utf8')).apiKey, GOOD)
  const info = await send(API, 'GET', '/v1/stt', AUTH)
  assert.ok(!info.text.includes(GOOD))
  assert.equal(info.body.last4, GOOD.slice(-4))
  const snap = await send(API, 'GET', '/v1/snapshot', AUTH)
  assert.deepEqual(snap.body.stt, { provider: 'soniox', ready: true, error: null })
  assert.ok(!snap.text.includes(GOOD))
  await sleep(50)
  assert.deepEqual(heard.find((m) => m.t === 'stt'), { t: 'stt', stt: { provider: 'soniox', ready: true, error: null } })
  feed.close()
  // The Mac app sets it the same way, on its own port with the admin token.
  assert.equal((await send(LOCAL, 'GET', '/v1/stt', { authorization: `Bearer ${adminToken}` })).body.ready, true)
  // No token, no key.
  assert.equal((await send(API, 'POST', '/v1/stt/key', {}, { apiKey: GOOD })).status, 401)
})

// ── Streams ──────────────────────────────────────────────────────────────
test('a stream: hello, start, started, captions as Soniox settles words, and the whole text when the phone stops', async () => {
  fake.script = [
    [token('Hel', false)],
    [token('Hel', true), token('lo', false)],
    [token('lo', true), token(' wor', false)],
    [token(' world', false)],
  ]
  const p = new Phone()
  await p.until((g) => g.some((m) => m.type === 'hello'))
  const hello = p.of('hello')[0]
  assert.equal(hello.ok, true)
  assert.equal(hello.modelLabel, 'Soniox')
  assert.equal(hello.languages, '*')
  p.json({ type: 'start', language: 'ru' })
  await p.until((g) => g.some((m) => m.type === 'started'))
  const s = seen.at(-1)!
  assert.equal(s.auth, `Bearer ${GOOD}`)
  assert.equal(s.config.model, 'stt-rt-v5')
  assert.equal(s.config.audio_format, 'pcm_s16le')
  assert.equal(s.config.sample_rate, 16000)
  assert.equal(s.config.num_channels, 1)
  assert.deepEqual(s.config.language_hints, ['ru', 'en'])
  assert.equal(s.config.api_key, undefined)
  assert.ok(s.config.context.terms.includes('hyperscribe'), 'project names help "in hyperscribe, …"')
  for (let i = 0; i < 4; i++) p.audio()
  await p.until((g) => g.filter((m) => m.type === 'snapshot').length >= 4)
  assert.deepEqual(
    p.of('snapshot').map((m) => [m.finalText, m.interimText, m.finished]),
    [
      ['', 'Hel', false],
      ['', 'Hello', false],
      ['Hello', 'wor', false],
      ['Hello', 'world', false],
    ],
  )
  // Lens pings every 3 s and calls the link dead after 7 s of silence.
  p.json({ type: 'ping' })
  await p.until((g) => g.some((m) => m.type === 'pong'))
  p.json({ type: 'stop' })
  await p.until(() => p.closed !== null)
  const last = p.of('snapshot').at(-1)
  assert.deepEqual([last.finalText, last.interimText, last.finished], ['Hello world', '', true])
  assert.equal(p.closed, 1000)
  assert.ok(s.ended, 'the end of the audio went to Soniox')
  assert.equal(s.audio, 4 * 1280)
  await sleep(30)
  assert.equal(sttStreams(), 0)
})

test('Soniox markers (<end>, <fin>) are not words', async () => {
  fake.script = [[token('Status', true), token('<end>', true)], [token(' please', false)]]
  const p = new Phone()
  await p.started()
  p.audio()
  p.audio()
  await p.until((g) => g.filter((m) => m.type === 'snapshot').length >= 2)
  assert.deepEqual(p.of('snapshot').map((m) => `${m.finalText}|${m.interimText}`), ['Status|', 'Status|please'])
  p.ws.close()
})

test('streams need a paired device; each device has one, and a new one replaces the old', async () => {
  for (const url of [`ws://127.0.0.1:${API}/v1/stt/ws`, `ws://127.0.0.1:${API}/v1/stt/ws?token=wrong`]) {
    const refused = await new Promise<boolean>((resolve) => {
      const ws = new WebSocket(url)
      ws.onopen = () => resolve(false)
      ws.onerror = () => resolve(true)
    })
    assert.ok(refused, url)
  }
  const a = new Phone()
  await a.started()
  const other = new Phone(OTHER)
  await other.started()
  const b = new Phone()
  await b.started()
  await a.until(() => a.closed !== null)
  assert.equal(a.closed, 4000)
  assert.equal(a.of('error')[0]?.code, 'replaced')
  assert.equal(other.closed, null, 'another device keeps its stream')
  assert.equal(b.closed, null)
  b.ws.close()
  other.ws.close()
})

test('two minutes at most: then the words so far come back, finished, and listening stops', async () => {
  sttLimits.maxMs = 500 // 16 000 bytes of audio
  fake.script = [[token('A', true), token(' long', false)]]
  const p = new Phone()
  await p.started()
  for (let i = 0; i < 14; i++) p.audio()
  await p.until(() => p.closed !== null)
  const err = p.of('error')[0]
  assert.equal(err.code, 'too_long')
  assert.equal(err.finished, true)
  assert.equal(err.finalText, 'A long')
  assert.ok(seen.at(-1)!.ended, 'Soniox was told the audio ended')
  assert.ok(seen.at(-1)!.audio <= 16_000)
})

test('a microphone that stalls: keepalives to Soniox, then the stream closes', async () => {
  sttLimits.keepaliveMs = 200
  sttLimits.idleMs = 1600
  const p = new Phone()
  await p.started()
  await p.until(() => p.closed !== null, 6000)
  assert.equal(p.of('error')[0].code, 'idle')
  assert.ok(seen.at(-1)!.keepalives >= 1)
})

test('Soniox not finishing after the end: the phone still gets the words, as final', async () => {
  sttLimits.finishMs = 300
  fake.silentEnd = true
  fake.script = [[token('Ship', true), token(' it', false)]]
  const p = new Phone()
  await p.started()
  p.audio()
  await p.until((g) => g.some((m) => m.type === 'snapshot'))
  p.json({ type: 'stop' })
  await p.until(() => p.closed !== null)
  const last = p.of('snapshot').at(-1)
  assert.deepEqual([last.finalText, last.finished], ['Ship it', true])
})

test('a stop while Soniox is still being reached keeps the audio: config, every frame held, then the end', async () => {
  // A quick "yes" over a slow link: the phone lets go before Soniox has even answered.
  fake.openDelayMs = 300
  fake.script = [[token('Yes', false)]]
  const p = new Phone()
  await p.until((g) => g.some((m) => m.type === 'hello'))
  p.json({ type: 'start', language: 'en' })
  for (let i = 0; i < 5; i++) p.audio()
  p.json({ type: 'stop' })
  await p.until(() => p.closed !== null)
  const s = seen.at(-1)!
  assert.deepEqual(s.order, ['config', 'audio', 'audio', 'audio', 'audio', 'audio', 'end'])
  assert.equal(s.audio, 5 * 1280)
  const last = p.of('snapshot').at(-1)
  assert.deepEqual([last.finalText, last.interimText, last.finished], ['Yes', '', true])
  assert.equal(p.of('error').length, 0)
  assert.equal(p.closed, 1000)
  // A link that never comes still ends in `unreachable`, stop or not.
  Object.assign(sttLimits, { connectMs: 150 })
  fake.openDelayMs = 1000
  const q = new Phone()
  await q.until((g) => g.some((m) => m.type === 'hello'))
  q.json({ type: 'start', language: 'en' })
  q.audio()
  q.json({ type: 'stop' })
  await q.until(() => q.closed !== null)
  assert.equal(q.of('error')[0]?.code, 'unreachable')
})

test('the phone app streams as Lens does: its own device, the token in the header, start at once, and the release timed in the log', async () => {
  fake.script = [[token('Add a dark', true), token(' mode', false)], [token(' mode', true), token(' toggle', false)]]
  // As OkHttp opens it: the device token as a header, no query.
  const ws = new WsClient(`ws://127.0.0.1:${API}/v1/stt/ws`, { headers: { authorization: `Bearer ${OTHER}` } })
  const got: any[] = []
  let closed: number | null = null
  ws.on('message', (d) => got.push(JSON.parse(String(d))))
  ws.on('close', (code) => (closed = code))
  await new Promise((r) => ws.once('open', r))
  // Android sends start as soon as the socket opens (not after hello), then the audio it held.
  ws.send(JSON.stringify({ type: 'start', language: 'auto' }))
  ws.send(Buffer.alloc(1280))
  ws.send(Buffer.alloc(1280))
  const wait = async (pred: () => boolean) => {
    for (let i = 0; i < 200 && !pred(); i++) await sleep(20)
    assert.ok(pred(), `timed out; got ${JSON.stringify(got)}`)
  }
  await wait(() => got.filter((m) => m.type === 'snapshot').length >= 2)
  assert.equal(got[0].type, 'hello')
  assert.ok(got.some((m) => m.type === 'started'))
  assert.deepEqual(seen.at(-1)!.config.language_hints, ['en', 'ru'])
  ws.send(JSON.stringify({ type: 'stop' }))
  await wait(() => closed !== null)
  const last = got.filter((m) => m.type === 'snapshot').at(-1)
  assert.deepEqual([last.finalText, last.interimText, last.finished], ['Add a dark mode toggle', '', true])
  assert.equal(closed, 1000)
  await sleep(30)
  // One line: how long, how it ended, and from letting go to the last words. Never the words.
  const log = readFileSync(join(process.env.HYPERNOVA_LOGS!, 'daemon.log'), 'utf8')
  const line = log.split('\n').filter((l) => / stt stream phone /.test(l)).at(-1) ?? ''
  assert.match(line, /stt stream phone \d+\.\ds done \d+ms$/)
  assert.ok(!/dark|toggle/i.test(log))
})

test('an account Soniox stops (no balance): the stream says so, the snapshot too, and a test that passes clears it', async () => {
  // The same key, now on an account with no balance.
  fake.failWith = { code: 402, type: 'organization_balance_exhausted' }
  const p = new Phone()
  await p.started()
  p.audio()
  await p.until(() => p.closed !== null)
  assert.equal(p.of('error')[0].code, 'no_credit')
  assert.match(p.of('error')[0].message, /balance/)
  let snap = (await send(API, 'GET', '/v1/snapshot', AUTH)).body
  assert.equal(snap.stt.ready, false)
  assert.match(snap.stt.error, /balance/)
  // Credit added: the test goes through, and voice is ready again.
  fake.failWith = null
  const t = await send(API, 'POST', '/v1/stt/test', AUTH)
  assert.equal(t.body.ok, true)
  snap = (await send(API, 'GET', '/v1/snapshot', AUTH)).body
  assert.deepEqual(snap.stt, { provider: 'soniox', ready: true, error: null })
  // A transient refusal (Soniox busy) is the stream's to say, not the key's.
  fake.failWith = { code: 429, type: 'max_concurrent_connections_reached' }
  const q = new Phone()
  await q.started()
  q.audio()
  await q.until(() => q.closed !== null)
  assert.equal(q.of('error')[0].code, 'busy')
  assert.equal((await send(API, 'GET', '/v1/snapshot', AUTH)).body.stt.ready, true)
})

test('nothing said and no key is ever written to the log', async () => {
  const log = readFileSync(join(process.env.HYPERNOVA_LOGS!, 'daemon.log'), 'utf8')
  assert.ok(!log.includes(GOOD))
  assert.ok(!/Hello world|Ship it|Status/.test(log))
  assert.match(log, /stt stream glasses/)
})

test('removing the key ends open streams and voice is off', async () => {
  const p = new Phone()
  await p.started()
  const r = await send(API, 'DELETE', '/v1/stt/key', AUTH)
  assert.equal(r.status, 200)
  assert.equal(r.body.provider, null)
  await p.until(() => p.closed !== null)
  assert.equal(p.of('error')[0].code, 'no_key')
  assert.equal(existsSync(join(process.env.HYPERNOVA_SUPPORT!, 'stt.json')), false)
  assert.equal((await send(API, 'POST', '/v1/stt/test', AUTH)).body.error.code, 'no_key')
})

test('a key Soniox takes but whose account has no balance is kept, and says so', async () => {
  const r = await send(API, 'POST', '/v1/stt/key', AUTH, { apiKey: POOR })
  assert.equal(r.status, 200)
  assert.equal(r.body.ready, false)
  assert.match(r.body.error, /balance/)
  await send(API, 'DELETE', '/v1/stt/key', AUTH)
})

test('a key saved while Soniox is busy is kept, checked, with Soniox\u2019s words; one it can\u2019t reach is kept unchecked', async () => {
  fake.failWith = { code: 429, type: 'max_concurrent_connections_reached' }
  const busy = await send(API, 'POST', '/v1/stt/key', AUTH, { apiKey: GOOD })
  assert.equal(busy.status, 200)
  assert.equal(busy.body.checked, true, 'Soniox answered')
  assert.match(busy.body.problem, /limit of streams/)
  assert.equal(busy.body.ready, true)
  fake.failWith = null
  Object.assign(sttLimits, { connectMs: 200, finishMs: 200 })
  fake.silentEnd = true
  const away = await send(API, 'POST', '/v1/stt/key', AUTH, { apiKey: GOOD })
  assert.equal(away.body.checked, false)
  assert.match(away.body.problem, /reach Soniox/)
  await send(API, 'DELETE', '/v1/stt/key', AUTH)
})

test('a key removed while another one is being tried stays removed', async () => {
  // Soniox never finishes the test, so it runs until its time is up.
  Object.assign(sttLimits, { connectMs: 300, finishMs: 300 })
  fake.silentEnd = true
  const saving = send(API, 'POST', '/v1/stt/key', AUTH, { apiKey: GOOD })
  await sleep(120)
  assert.equal((await send(API, 'DELETE', '/v1/stt/key', AUTH)).status, 200)
  const r = await saving
  assert.equal(r.status, 409)
  assert.equal(r.body.error.code, 'removed')
  assert.equal(existsSync(join(process.env.HYPERNOVA_SUPPORT!, 'stt.json')), false)
  assert.equal((await send(API, 'GET', '/v1/stt', AUTH)).body.provider, null)
})
