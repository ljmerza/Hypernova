// Read aloud with Soniox, and plays the phone app streams (docs/PROTOCOL.md, "Read aloud"). Soniox is a stand-in on free ports that speaks its TTS protocol as documented: the key as
// the Authorization header on the upgrade, a config then text (text_end) per stream_id, base64 PCM
// with per-character timestamps, audio_end, terminated, error frames, cancel; and the models list
// (GET /v1/tts-models). The Mac's voice is the real `say`, which renders to a file and plays nothing.
import { test, before, after, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { createServer as httpServer, request } from 'node:http'
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
mkdirSync(join(process.env.HOME, 'Programming'), { recursive: true })
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
const TTS = await freePort()
const SXAPI = await freePort()
const CLOSED = await freePort()
process.env.HYPERNOVA_API_PORT = String(API)
process.env.HYPERNOVA_LOCAL_PORT = String(LOCAL)
// The LAN listener (lan.ts) takes a free port: test files run side by side.
process.env.HYPERNOVA_LAN_PORT = '0'
const TTS_URL = `ws://127.0.0.1:${TTS}/tts-websocket`
process.env.HYPERNOVA_SONIOX_TTS_WS = TTS_URL
process.env.HYPERNOVA_SONIOX_API = `http://127.0.0.1:${SXAPI}`

// A Soniox key, kept as speech to text keeps it (the same key reads aloud).
const GOOD = 'sx_good_0123456789abcdef0123456789abcdef'
mkdirSync(process.env.HYPERNOVA_SUPPORT, { recursive: true })
writeFileSync(join(process.env.HYPERNOVA_SUPPORT, 'stt.json'), JSON.stringify({ provider: 'soniox', apiKey: GOOD, savedAt: Date.now(), problem: null }))

// ── The Soniox stand-in ──────────────────────────────────────────────────
const RATE = 24_000
/** Each character the stand-in reads is this long. */
const MS_PER_CHAR = 20
/** What it saw, per connection. */
type Seen = { auth: string; configs: any[]; texts: any[]; cancels: number }
const seen: Seen[] = []
/** The next connection's behaviour: an error on piece n, frames held back, a dropped connection. */
const fake = { failOn: null as null | { piece: number; code: number; type: string }, slowMs: 0, dropOn: null as null | number, hang: null as null | 'first' | 'mid', truncate: false }
const pcmFor = (chars: number) => {
  const b = Buffer.alloc(chars * MS_PER_CHAR * (RATE / 1000) * 2)
  // A steady tone: loud enough to show on a wave.
  for (let i = 0; i < b.length / 2; i++) b.writeInt16LE(i % 24 < 12 ? 8000 : -8000, i * 2)
  return b
}
const tts = new WebSocketServer({ port: TTS, host: '127.0.0.1', path: '/tts-websocket', verifyClient: (info, done) => done(info.req.headers.authorization === `Bearer ${GOOD}`, 401) })
tts.on('connection', (ws, req) => {
  const s: Seen = { auth: String(req.headers.authorization ?? ''), configs: [], texts: [], cancels: 0 }
  seen.push(s)
  let piece = 0
  ws.on('message', async (data) => {
    const m = JSON.parse(String(data))
    if (m.keep_alive) return
    if (m.cancel) {
      s.cancels++
      return ws.send(JSON.stringify({ stream_id: m.stream_id, terminated: true }))
    }
    if (m.model) return void s.configs.push(m)
    if (typeof m.text !== 'string') return
    s.texts.push(m)
    const n = piece++
    const sid = m.stream_id
    if (fake.dropOn === n) return ws.terminate()
    if (fake.failOn?.piece === n) {
      ws.send(JSON.stringify({ stream_id: sid, error_code: fake.failOn.code, error_type: fake.failOn.type, error_message: 'Nope.', request_id: 'r-1', more_info: 'https://soniox.com/docs' }))
      return ws.send(JSON.stringify({ stream_id: sid, terminated: true }))
    }
    // Accepts the text, then says nothing at all.
    if (fake.hang === 'first') return
    // Two frames, each half the text, with its characters' start times (from this stream's start).
    const chars = [...m.text]
    const half = Math.ceil(chars.length / 2)
    for (const [k, part] of [chars.slice(0, half), chars.slice(half)].entries()) {
      if (fake.slowMs) await new Promise((r) => setTimeout(r, fake.slowMs))
      if (ws.readyState !== ws.OPEN) return
      if (k === 1 && fake.hang === 'mid') return
      const offset = k * half
      ws.send(
        JSON.stringify({
          stream_id: sid,
          audio: pcmFor(part.length).toString('base64'),
          audio_end: k === 1,
          timestamps: {
            characters: part,
            character_start_times_seconds: part.map((_, i) => ((offset + i) * MS_PER_CHAR) / 1000),
            character_end_times_seconds: part.map((_, i) => ((offset + i + 1) * MS_PER_CHAR) / 1000),
          },
        }),
      )
    }
    if (fake.truncate) ws.send(JSON.stringify({ stream_id: sid, error_code: 400, error_type: 'max_audio_duration_reached', error_message: 'Two minutes.' }))
    ws.send(JSON.stringify({ stream_id: sid, terminated: true }))
  })
})

/** The models list, as Soniox's `GET /v1/tts-models` has it. */
let listed = 0
const api = httpServer((req, res) => {
  if (req.url !== '/v1/tts-models' || req.headers.authorization !== `Bearer ${GOOD}`) {
    res.writeHead(401, { 'content-type': 'application/json' })
    return res.end(JSON.stringify({ status_code: 401, error_type: 'unauthenticated', message: 'Incorrect API key provided.' }))
  }
  listed++
  res.writeHead(200, { 'content-type': 'application/json' })
  res.end(
    JSON.stringify({
      models: [
        { id: 'tts-rt-v1', name: 'TTS v1', aliased_model_id: null, languages: [{ code: 'en', name: 'English' }], voices: [{ id: 'Old', description: 'v1 only', gender: 'male' }] },
        {
          id: 'tts-rt-v2',
          name: 'TTS v2',
          aliased_model_id: null,
          languages: [{ code: 'en', name: 'English' }, { code: 'ru', name: 'Russian' }],
          voices: [
            { id: 'Adrian', description: 'Warm and steady', gender: 'male' },
            { id: 'Maya', description: 'Bright', gender: 'female' },
          ],
        },
      ],
    }),
  )
})
api.listen(SXAPI, '127.0.0.1')

const { startServers } = await import('../src/http.ts')
const { addDevice, adminToken } = await import('../src/store.ts')
const { playLimits } = await import('../src/readaloud.ts')
const { pickModel, speechLanguage, ttsPieces, ttsText, ttsLimits } = await import('../src/tts.ts')
const PHONE = 'test-phone-token-0123456789abcdef'
const PHONE2 = 'test-phone2-token-0123456789abcdef'
addDevice({ id: 'phone', name: 'Pixel', model: 'P3', createdAt: Date.now(), lastSeenAt: Date.now() }, PHONE)
addDevice({ id: 'phone2', name: 'Other phone', model: 'P4', createdAt: Date.now(), lastSeenAt: Date.now() }, PHONE2)
const LIMITS = { ...playLimits }
const TLIMITS = { ...ttsLimits }

let close: () => Promise<void>
before(async () => {
  close = startServers()
  for (let i = 0; i < 50; i++) {
    try {
      if ((await send('GET', '/v1/health')).status === 200) return
    } catch {}
    await sleep(20)
  }
})
after(async () => {
  await close?.()
  for (const c of tts.clients) c.terminate()
  await new Promise((r) => tts.close(r))
  await new Promise((r) => api.close(r))
  rmSync(scratch, { recursive: true, force: true })
})
beforeEach(async () => {
  Object.assign(playLimits, LIMITS)
  Object.assign(ttsLimits, TLIMITS)
  Object.assign(fake, { failOn: null, slowMs: 0, dropOn: null, hang: null, truncate: false })
  process.env.HYPERNOVA_SONIOX_TTS_WS = TTS_URL
  seen.length = 0
  // Each test starts from Soniox and its default voice at normal speed.
  await send('POST', '/v1/speech/settings', PHONE, { engine: 'soniox', voice: 'Adrian', rate: 1 })
})

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
type Reply = { status: number; body: any; bytes: Buffer; headers: Record<string, any> }
function send(method: string, path: string, token: string | null = PHONE, body?: unknown, extra: Record<string, string> = {}): Promise<Reply> {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? undefined : JSON.stringify(body)
    const headers: Record<string, string> = { host: `127.0.0.1:${API}`, ...(data ? { 'content-type': 'application/json' } : {}), ...(token ? { authorization: `Bearer ${token}` } : {}), ...extra }
    const req = request({ host: '127.0.0.1', port: API, method, path, headers }, (res) => {
      const parts: Buffer[] = []
      res.on('data', (c) => parts.push(c))
      res.on('end', () => {
        const bytes = Buffer.concat(parts)
        let b: any = null
        try {
          b = JSON.parse(bytes.toString('utf8'))
        } catch {}
        resolve({ status: res.statusCode ?? 0, body: b, bytes, headers: res.headers })
      })
    })
    req.on('error', reject)
    req.end(data)
  })
}

/** A live feed (`/v1/ws`): every message it gets. */
class Feed {
  ws: WsClient
  got: any[] = []
  open: Promise<void>
  constructor(token: string, ip?: string, mac = false) {
    const headers: Record<string, string> = mac ? { authorization: `Bearer ${adminToken}` } : ip ? { 'x-forwarded-for': ip } : {}
    this.ws = new WsClient(`ws://127.0.0.1:${API}/v1/ws${mac ? '?client=mac' : `?token=${token}`}`, { headers })
    this.ws.on('message', (d) => this.got.push(JSON.parse(String(d))))
    this.open = new Promise((r) => this.ws.once('open', () => r()))
  }
  async until<T = any>(pred: (m: any) => boolean, ms = 4000): Promise<T> {
    const t0 = Date.now()
    for (;;) {
      const hit = this.got.find(pred)
      if (hit) return hit
      if (Date.now() - t0 > ms) throw new Error(`timed out; got ${JSON.stringify(this.got.map((m) => m.t))}`)
      await sleep(15)
    }
  }
  of(t: string) {
    return this.got.filter((m) => m.t === t)
  }
  json(m: object) {
    this.ws.send(JSON.stringify(m))
  }
  close() {
    this.ws.close()
  }
}

// ── Pure parts ─────────────────────────────────────────────────────────

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

test('square brackets are text, not Soniox v2 stage directions; the length stays', () => {
  assert.equal(ttsText('See [1] and [laughs]'), 'See (1) and (laughs)')
  assert.equal(ttsText('a[b]c').length, 5)
})

test('the main language is the one with most letters', () => {
  assert.equal(speechLanguage('Привет, это Hypernova'), 'ru')
  assert.equal(speechLanguage('The build passed: 12 tests'), 'en')
  assert.equal(speechLanguage('123 456'), 'en')
  assert.equal(speechLanguage('これはテストです'), 'ja')
})

test('the live model: v2 when listed, else the newest real-time one', () => {
  assert.equal(pickModel([{ id: 'tts-rt-v1', voices: [] }, { id: 'tts-rt-v2', voices: [{ id: 'Maya', gender: 'female' }] }])!.model, 'tts-rt-v2')
  assert.equal(pickModel([{ id: 'tts-rt-v1', voices: [] }, { id: 'tts-rt-v3', voices: [] }])!.model, 'tts-rt-v3')
  assert.equal(pickModel([{ id: 'tts-rt-v1-preview', aliased_model_id: 'tts-rt-v1', voices: [] }])!.model, 'tts-rt-v1')
  assert.equal(pickModel('nope'), null)
  assert.deepEqual(pickModel([{ id: 'tts-rt-v2', voices: [{ id: 'Maya', gender: 'female', description: 'Bright' }, { id: 'Maya' }] }])!.voices, [{ id: 'Maya', name: 'Maya', lang: '*', gender: 'female', description: 'Bright' }])
})

// ── The setting ────────────────────────────────────────────────────────

test('Soniox reads by default once a key is there; the snapshot and settings never carry the key', async () => {
  const snap = await send('GET', '/v1/snapshot')
  assert.equal(snap.body.speech.engine, 'soniox')
  assert.equal(snap.body.speech.using, 'soniox')
  assert.equal(snap.body.speech.soniox, true)
  const s = await send('GET', '/v1/speech/settings')
  assert.equal(s.body.speech.model, 'tts-rt-v2')
  assert.ok(!JSON.stringify(snap.body).includes(GOOD))
  assert.ok(!JSON.stringify(s.body).includes(GOOD))
})

test('voices: the Mac’s as before, Soniox’s when asked, from the live model', async () => {
  const sx = await send('GET', '/v1/speech/voices?engine=soniox')
  assert.equal(sx.status, 200)
  assert.equal(sx.body.engine, 'soniox')
  assert.equal(sx.body.model, 'tts-rt-v2')
  assert.deepEqual(sx.body.voices.map((v: any) => v.id), ['Adrian', 'Maya'])
  assert.equal(sx.body.voices[1].gender, 'female')
  const before = listed
  await send('GET', '/v1/speech/voices?engine=soniox')
  assert.equal(listed, before, 'the list is kept, not asked for again')
  const mac = await send('GET', '/v1/speech/voices')
  assert.equal(mac.body.engine, 'mac')
  assert.ok(Array.isArray(mac.body.voices))
})

test('settings: checked, kept, and told to every client', async () => {
  const feed = new Feed(PHONE)
  await feed.open
  assert.equal((await send('POST', '/v1/speech/settings', PHONE, { engine: 'bluetooth' })).body.error.code, 'bad_engine')
  assert.equal((await send('POST', '/v1/speech/settings', PHONE, { voice: 'Nobody' })).body.error.code, 'bad_voice')
  assert.equal((await send('POST', '/v1/speech/settings', PHONE, { rate: 'fast' })).body.error.code, 'bad_rate')
  const r = await send('POST', '/v1/speech/settings', PHONE, { voice: 'Maya', rate: 9 })
  assert.equal(r.body.speech.voice, 'Maya')
  assert.equal(r.body.speech.rate, 2)
  await feed.until((m) => m.t === 'speech' && m.speech.voice === 'Maya')
  const m = await send('POST', '/v1/speech/settings', PHONE, { engine: 'mac' })
  assert.equal(m.body.speech.using, 'mac')
  assert.equal(m.body.speech.note, null)
  const saved = JSON.parse(readFileSync(join(process.env.HYPERNOVA_SUPPORT!, 'speech.json'), 'utf8'))
  assert.equal(saved.engine, 'mac')
  feed.close()
})

// ── Whole clips ────────────────────────────────────────────────────────

test('a whole clip with Soniox: WAV of its PCM; brackets made text, the language from the letters', async () => {
  const text = 'Привет. Сборка прошла [1].'
  const r = await send('POST', '/v1/speech', PHONE, { text })
  assert.equal(r.status, 200)
  assert.equal(r.headers['content-type'], 'audio/wav')
  assert.equal(r.headers['x-speech-engine'], 'soniox')
  assert.equal(r.bytes.toString('ascii', 0, 4), 'RIFF')
  assert.equal(r.bytes.readUInt32LE(24), RATE)
  assert.equal(r.bytes.length - 44, pcmFor([...ttsText(text)].length).length)
  const s = seen.at(-1)!
  assert.equal(s.auth, `Bearer ${GOOD}`)
  assert.equal(s.configs[0].model, 'tts-rt-v2')
  assert.equal(s.configs[0].voice, 'Adrian')
  assert.equal(s.configs[0].audio_format, 'pcm_s16le')
  assert.equal(s.configs[0].sample_rate, RATE)
  assert.equal(s.configs[0].language, 'ru')
  assert.equal(s.configs[0].return_timestamps, true)
  assert.equal(s.configs[0].speed, 1)
  assert.equal(s.configs[0].api_key, undefined, 'the key goes as the header only')
  assert.equal(s.texts[0].text, 'Привет. Сборка прошла (1).')
  assert.equal(s.texts[0].text_end, true)
  // Read again: from memory, Soniox isn't asked.
  const n = seen.length
  const again = await send('POST', '/v1/speech', PHONE, { text })
  assert.deepEqual(again.bytes, r.bytes)
  assert.equal(seen.length, n)
})

/** The Mac’s voice is the real `say`: the tests where it reads run on a Mac only. */
const macVoice = { skip: process.platform !== 'darwin' && 'the Mac’s voice is a Mac’s' }

test('Soniox refusing the key: the Mac’s voice reads, and the setting says so in one line', macVoice, async () => {
  const feed = new Feed(PHONE)
  await feed.open
  process.env.HYPERNOVA_SONIOX_TTS_WS = `ws://127.0.0.1:${TTS}/elsewhere`
  const r = await send('POST', '/v1/speech', PHONE, { text: 'A fallback, please.' })
  assert.equal(r.status, 200)
  assert.equal(r.headers['x-speech-engine'], 'mac')
  assert.equal(r.bytes.toString('ascii', 0, 4), 'RIFF')
  const note = await feed.until((m) => m.t === 'speech' && m.speech.note)
  assert.match(note.speech.note, /Mac’s voice did/)
  assert.equal(note.speech.using, 'soniox', 'still chosen: the next read tries Soniox again')
  process.env.HYPERNOVA_SONIOX_TTS_WS = TTS_URL
  await send('POST', '/v1/speech', PHONE, { text: 'Back to Soniox.' })
  await feed.until((m) => m.t === 'speech' && m.speech.note === null)
  feed.close()
})

test('Soniox out of reach, and failing mid-reply: the Mac carries on from where it got to', macVoice, async () => {
  process.env.HYPERNOVA_SONIOX_TTS_WS = `ws://127.0.0.1:${CLOSED}/tts-websocket`
  const off = await send('POST', '/v1/speech', PHONE, { text: 'Nobody home.' })
  assert.equal(off.headers['x-speech-engine'], 'mac')
  process.env.HYPERNOVA_SONIOX_TTS_WS = TTS_URL
  fake.failOn = { piece: 1, code: 503, type: 'service_unavailable' }
  const text = 'First part is read by Soniox. '.repeat(12) + 'The rest by the Mac.'
  const r = await send('POST', '/v1/speech', PHONE, { text })
  assert.equal(r.status, 200)
  // Soniox's first piece, then the Mac's voice: longer than Soniox's piece alone.
  const first = ttsPieces(ttsText(text))[0]!
  assert.ok(r.bytes.length - 44 > pcmFor([...first.text].length).length)
  assert.equal(seen.at(-1)!.texts.length, 2)
})

test('Soniox hanging after the connection: the Mac’s voice takes over within the first-audio limit, and the note says so', macVoice, async () => {
  const feed = new Feed(PHONE)
  await feed.open
  Object.assign(ttsLimits, { firstAudioMs: 300 })
  fake.hang = 'first'
  const t0 = Date.now()
  const r = await send('POST', '/v1/speech', PHONE, { text: 'Nothing will come back.' })
  assert.equal(r.headers['x-speech-engine'], 'mac')
  assert.ok(Date.now() - t0 < 5000)
  const note = await feed.until((m) => m.t === 'speech' && m.speech.note)
  assert.match(note.speech.note, /didn’t start reading in time/)
  feed.close()
})

test('Soniox going quiet mid-piece is an idle timeout, and the Mac carries on', macVoice, async () => {
  Object.assign(ttsLimits, { idleMs: 300 })
  fake.hang = 'mid'
  const r = await send('POST', '/v1/speech', PHONE, { text: 'It starts and then it stops.' })
  assert.equal(r.headers['x-speech-engine'], 'mac', 'the Mac read what Soniox didn’t')
  assert.ok(r.bytes.length > 44)
  assert.match((await send('GET', '/v1/speech/settings')).body.speech.note, /stopped in the middle/)
})

test('a piece longer than any fixed limit is fine while frames keep coming', async () => {
  // Two frames 200 ms apart: 400 ms in all, over the 300 ms of idleness allowed, but never idle that long.
  Object.assign(ttsLimits, { idleMs: 300, firstAudioMs: 300 })
  fake.slowMs = 200
  const r = await send('POST', '/v1/speech', PHONE, { text: 'A long piece, made slowly.' })
  assert.equal(r.headers['x-speech-engine'], 'soniox')
  assert.equal((await send('GET', '/v1/speech/settings')).body.speech.note, null)
})

test('a piece Soniox cut off at its 2 minutes is not read whole: the Mac reads the rest', macVoice, async () => {
  fake.truncate = true
  const r = await send('POST', '/v1/speech', PHONE, { text: 'Cut off by the limit.' })
  assert.equal(r.status, 200)
  assert.match((await send('GET', '/v1/speech/settings')).body.speech.note, /cut that short/)
})

// ── Plays on the phone app ─────────────────────────────────────────────

test('a play is the asking phone’s: only it may fetch or stop it, besides the Mac app', async () => {
  const r = await send('POST', '/v1/speech/play', PHONE, { text: 'Read on this phone.', to: 'me' })
  assert.equal(r.status, 200)
  assert.equal(r.body.play.to.id, 'phone')
  assert.equal(r.body.play.source, 'phone')
  const id = r.body.play.id
  assert.equal((await send('GET', `/v1/speech/plays/${id}/audio`, PHONE2)).status, 404)
  assert.equal((await send('POST', `/v1/speech/plays/${id}/stop`, PHONE2)).status, 404)
  assert.equal((await send('POST', '/v1/speech/play', null, { text: 'x', to: 'me' })).status, 401)
  const stop = await send('POST', `/v1/speech/plays/${id}/stop`, PHONE)
  assert.equal(stop.body.state, 'stopped')
})

test('the phone’s own Read aloud plays on it; the Mac’s voice when chosen, as the same PCM', macVoice, async () => {
  const mac = new Feed('', undefined, true)
  await mac.open
  await send('POST', '/v1/speech/settings', PHONE, { engine: 'mac' })
  const r = await send('POST', '/v1/speech/play', PHONE, { text: 'Read on the phone itself.', to: 'me', sessionId: 's2' })
  assert.equal(r.status, 200)
  assert.equal(r.body.play.to.id, 'phone')
  assert.equal(r.body.play.engine, 'mac')
  const n = seen.length
  const audio = await send('GET', `/v1/speech/plays/${r.body.play.id}/audio`, PHONE)
  assert.equal(audio.status, 200)
  assert.equal(audio.headers['x-speech-engine'], 'mac')
  assert.ok(audio.bytes.length > 24_000, 'about a second of the Mac’s voice at least')
  assert.equal(seen.length, n, 'Soniox isn’t asked')
  const ws = new Feed(PHONE)
  await ws.open
  ws.json({ t: 'speech.progress', id: r.body.play.id, state: 'started', ms: 0, level: 0.5 })
  const sp = await mac.until((m) => m.t === 'speaking' && m.speaking.sessionId === 's2')
  assert.equal(sp.speaking.source, 'phone')
  ws.json({ t: 'speech.progress', id: r.body.play.id, state: 'ended', ms: audio.bytes.length / 48 })
  await mac.until((m) => m.t === 'speaking' && m.speaking.sessionId === 's2' && m.speaking.state === 'ended')
  for (const f of [mac, ws]) f.close()
})

test('a play whose Soniox stream is slow starts streaming before it is all made', async () => {
  fake.slowMs = 300
  const r = await send('POST', '/v1/speech/play', PHONE, { text: 'Streaming as it comes, piece by piece.', to: 'me' })
  assert.equal(r.status, 200)
  const t0 = Date.now()
  const times = await new Promise<number[]>((resolve, reject) => {
    const at: number[] = []
    const req = request({ host: '127.0.0.1', port: API, method: 'GET', path: `/v1/speech/plays/${r.body.play.id}/audio`, headers: { authorization: `Bearer ${PHONE}` } }, (res) => {
      assert.equal(res.statusCode, 200)
      res.on('data', () => at.push(Date.now() - t0))
      res.on('end', () => resolve(at))
    })
    req.on('error', reject)
    req.end()
  })
  // The first half came about 300 ms in, the second about 300 ms later, on the same stream.
  assert.ok(times.length >= 2, `${times.length} chunks`)
  assert.ok(times.at(-1)! - times[0]! >= 200, `first audio at ${times[0]} ms, the last at ${times.at(-1)} ms`)
})

test('the key and the text never reach the log', async () => {
  await send('POST', '/v1/speech', PHONE, { text: 'A secret plan for Thursday.' })
  const logText = readFileSync(join(process.env.HYPERNOVA_LOGS!, 'daemon.log'), 'utf8')
  assert.ok(!logText.includes(GOOD))
  assert.ok(!logText.includes('secret plan'))
  assert.ok(!logText.includes('Thursday'))
})
