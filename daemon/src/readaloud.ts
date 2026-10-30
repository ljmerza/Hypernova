// Read aloud (docs/PROTOCOL.md, "Read aloud"): which voice reads replies, Soniox's natural voices
// or the Mac's own, and plays: speech made here and played by the Hypernova app on the phone,
// natively, through whatever the phone plays to (Bluetooth headphones too), screen off or not.
//
// The text is in memory only, never logged or written down; the log has one line a play (who, the
// engine, how long, how it ended).
import type { IncomingMessage, ServerResponse } from 'node:http'
import { createHash, randomBytes } from 'node:crypto'
import { join } from 'node:path'
import { WebSocket, type RawData } from 'ws'
import { SUPPORT, log, readJson, writeJson } from './util.ts'
import { broadcast } from './hub.ts'
import { toMacApps } from './mac-feed.ts'
import { listDevices } from './store.ts'
import { onSttChange, sttState } from './stt.ts'
import { MAX_SPEECH, listVoices, sayPcm, speak } from './speech.ts'
import { BYTES_PER_MS, TTS_RATE, sonioxSpeak, sonioxVoiceFor, sonioxVoices, ttsModel, ttsPieces, wav, type Mark } from './tts.ts'

const fail = (status: number, code: string, message: string) => Object.assign(new Error(message), { status, code })

// ── The setting ──────────────────────────────────────────────────────────

export type Engine = 'soniox' | 'mac'
type Saved = { engine?: Engine; voice?: string; macVoice?: string | null; rate?: number }
const FILE = join(SUPPORT, 'speech.json')
let saved: Saved = (() => {
  const s = readJson<any>(FILE, {})
  return {
    ...(s?.engine === 'soniox' || s?.engine === 'mac' ? { engine: s.engine } : {}),
    ...(typeof s?.voice === 'string' && s.voice ? { voice: s.voice } : {}),
    ...(typeof s?.macVoice === 'string' && s.macVoice ? { macVoice: s.macVoice } : {}),
    ...(typeof s?.rate === 'number' && Number.isFinite(s.rate) ? { rate: clampRate(s.rate) } : {}),
  }
})()

function clampRate(r: number): number {
  return Math.round(Math.min(2, Math.max(0.5, r)) * 100) / 100
}

/** The last time Soniox couldn't read and the Mac's voice did, in one line; the next good read clears it. */
let fallbackNote: string | null = null

/** What every client sees (the snapshot's `speech`, and `{t:"speech"}` on each change). */
export type SpeechState = {
  /** Chosen: Soniox once a key is there, unless the owner picked the Mac's voice. */
  engine: Engine
  /** What reads now: the Mac's voice while Soniox can't (no key, a key it refused). */
  using: Engine
  /** Soniox's voice, and the Mac's (null: the Mac's own default). */
  voice: string
  macVoice: string | null
  /** 0.5–2; Soniox reads at 0.7–1.3 of it. */
  rate: number
  model: string
  /** A Soniox key is kept and Soniox takes it. */
  soniox: boolean
  /** Why the Mac's voice reads although Soniox was chosen, in one line; else null. */
  note: string | null
}

export function speechState(): SpeechState {
  const stt = sttState()
  const soniox = stt.provider === 'soniox' && stt.ready
  const engine: Engine = saved.engine ?? (stt.provider === 'soniox' ? 'soniox' : 'mac')
  const using: Engine = engine === 'soniox' && soniox ? 'soniox' : 'mac'
  const note =
    engine !== 'soniox' ? null
    : !stt.provider ? 'No Soniox key on this Mac, so the Mac’s voice reads.'
    : !soniox ? `${stt.error ?? 'Soniox isn’t taking the key'} Until then the Mac’s voice reads.`
    : fallbackNote
  return { engine, using, voice: sonioxVoiceFor(saved.voice), macVoice: saved.macVoice ?? null, rate: saved.rate ?? 1, model: ttsModel(), soniox, note }
}

let lastSent = ''
function changed(): void {
  const now = JSON.stringify(speechState())
  if (now === lastSent) return
  lastSent = now
  broadcast({ t: 'speech', speech: JSON.parse(now) })
}
onSttChange(changed)
setTimeout(() => (lastSent = JSON.stringify(speechState())), 0)

/** `POST /v1/speech/settings`: any of the engine, either voice, the rate. */
export async function saveSpeech(b: any): Promise<SpeechState> {
  const next: Saved = { ...saved }
  if (b?.engine !== undefined) {
    if (b.engine !== 'soniox' && b.engine !== 'mac') throw fail(400, 'bad_engine', 'Read aloud with Soniox or the Mac’s voice.')
    next.engine = b.engine
  }
  if (b?.voice !== undefined) {
    const v = typeof b.voice === 'string' ? b.voice.trim() : ''
    if (!/^[\w .'()-]{1,50}$/.test(v)) throw fail(400, 'bad_voice', 'That isn’t one of Soniox’s voices.')
    // Checked against Soniox's list when it can be had; offline, the name is kept and tried later.
    const list = await sonioxVoices().catch(() => null)
    if (list && !list.voices.some((x) => x.id === v)) throw fail(400, 'bad_voice', 'That isn’t one of Soniox’s voices.')
    next.voice = v
  }
  if (b?.macVoice !== undefined) {
    if (b.macVoice === null || b.macVoice === '') delete next.macVoice
    else if (typeof b.macVoice !== 'string' || !(await listVoices()).some((x) => x.id === b.macVoice)) throw fail(400, 'bad_voice', 'That isn’t one of this Mac’s voices.')
    else next.macVoice = b.macVoice
  }
  if (b?.rate !== undefined) {
    if (typeof b.rate !== 'number' || !Number.isFinite(b.rate)) throw fail(400, 'bad_rate', 'Speed is a number from 0.5 to 2.')
    next.rate = clampRate(b.rate)
  }
  saved = next
  writeJson(FILE, saved)
  changed()
  return speechState()
}

/** `GET /v1/speech/voices?engine=`: the Mac's voices, or Soniox's. */
export async function voicesFor(engine: string | null): Promise<unknown> {
  if (engine === 'soniox') {
    const { model, voices } = await sonioxVoices()
    changed()
    return { engine: 'soniox', model, voices }
  }
  return { engine: 'mac', voices: await listVoices() }
}

// ── Recent reads ─────────────────────────────────────────────────────────
// In memory only, by a hash of the voice, the speed and the text, so a reply read again starts at
// once: at most 6 and 32 MB, each dropped 10 minutes after it was made (speech.ts keeps the Mac's
// whole clips the same way).

type Kept = { pcm: Buffer; marks: Mark[]; engine: Engine; timer: ReturnType<typeof setTimeout> }
const kept = new Map<string, Kept>()
const KEEP_MS = 10 * 60_000
const KEEP_BYTES = 32 * 1024 * 1024

function keep(key: string, k: Omit<Kept, 'timer'>): void {
  const before = kept.get(key)
  if (before) clearTimeout(before.timer)
  kept.delete(key)
  const timer = setTimeout(() => kept.delete(key), KEEP_MS)
  timer.unref()
  kept.set(key, { ...k, timer })
  let total = 0
  for (const c of kept.values()) total += c.pcm.length
  for (const [k2, c] of kept) {
    if (kept.size <= 6 && total <= KEEP_BYTES) break
    clearTimeout(c.timer)
    kept.delete(k2)
    total -= c.pcm.length
  }
}

const keyOf = (engine: Engine, voice: string | null, rate: number, text: string) => createHash('sha256').update(`${engine}\n${voice ?? ''}\n${rate}\n${text}`).digest('hex')

// ── Making the audio ─────────────────────────────────────────────────────

type Sink = { audio(pcm: Buffer): void; marks(m: Mark[]): void; pieceDone(upTo: number): void; engine(e: Engine): void }

/** Whether a failure is Soniox's (then the Mac's voice takes over), or the read was stopped. */
const stopped = (e: any, signal: AbortSignal) => signal.aborted || e?.code === 'stopped'

/**
 * The whole [text] as 24 kHz PCM into [sink]: Soniox while it can, else the Mac's voice, from the
 * start or from where Soniox stopped (the sentence it was in). Each fallback leaves a note.
 */
async function make(text: string, s: SpeechState, out: Sink, signal: AbortSignal, wantVoice?: string | null): Promise<{ engine: Engine; whole: boolean }> {
  let from = 0
  let lastAt = -1
  // How much audio is made, so the Mac's voice carrying on after Soniox lines its marks up.
  let madeMs = 0
  const sink: Sink = { ...out, audio: (b) => ((madeMs += b.length / BYTES_PER_MS), out.audio(b)) }
  if (s.using === 'soniox') {
    sink.engine('soniox')
    let doneUpTo = 0
    try {
      await sonioxSpeak(
        text,
        { voice: wantVoice ?? s.voice, rate: s.rate },
        {
          audio: sink.audio,
          marks: (m) => {
            lastAt = m[m.length - 1]!.at
            sink.marks(m)
          },
          pieceDone: (upTo) => {
            doneUpTo = upTo
            sink.pieceDone(upTo)
          },
        },
        signal,
      )
      if (fallbackNote) {
        fallbackNote = null
        changed()
      }
      return { engine: 'soniox', whole: true }
    } catch (e: any) {
      if (stopped(e, signal)) throw fail(409, 'stopped', 'Stopped.')
      // Mid-reply: carry on from the start of the word the voice had got to (never back past a
      // piece that was read whole).
      from = Math.max(doneUpTo, lastAt >= 0 ? text.lastIndexOf(' ', lastAt) + 1 : 0)
      fallbackNote = `Soniox couldn’t read (${String(e?.message ?? 'it failed').replace(/\.$/, '')}), so the Mac’s voice did.`
      log('speech', 'soniox failed, the Mac reads', e?.code ?? '')
      changed()
    }
  }
  sink.engine('mac')
  const rest = text.slice(from)
  for (const p of ttsPieces(rest, 400, 2000)) {
    if (signal.aborted) throw fail(409, 'stopped', 'Stopped.')
    const pcm = await sayPcm(p.text, s.macVoice, s.rate, signal).catch((e) => {
      throw signal.aborted ? fail(409, 'stopped', 'Stopped.') : e
    })
    // The Mac's voice says nothing of its timing: each piece is spread evenly over its own audio.
    const start = madeMs
    sink.marks([{ at: from + p.at, ms: start }, { at: from + p.at + p.text.length, ms: start + pcm.length / BYTES_PER_MS }])
    sink.audio(pcm)
    sink.pieceDone(from + p.at + p.text.length)
  }
  return { engine: from > 0 ? 'soniox' : 'mac', whole: from === 0 }
}

// ── The phone apps that play ────────────────────────────────────────────

/** A phone's live feed (`/v1/ws`): its plays' stops go to it, their progress comes from it. */
type Feed = { ws: WebSocket; device: string }
const feeds = new Set<Feed>()

function toDevice(device: string, msg: object): number {
  const s = JSON.stringify(msg)
  let n = 0
  for (const f of feeds) {
    if (f.device !== device || f.ws.readyState !== WebSocket.OPEN) continue
    try {
      f.ws.send(s)
      n++
    } catch {}
  }
  return n
}

/**
 * Called by http.ts for every live feed of a paired device: it reports how a play goes
 * (`{t:"speech.progress", …}`).
 */
export function speechFeed(device: string | null, ws: WebSocket): void {
  if (!device) return
  const f: Feed = { ws, device }
  feeds.add(f)
  ws.on('message', (data: RawData, binary: boolean) => {
    if (binary) return
    const text = String(data)
    if (text.length > 4096 || !text.includes('"speech.')) return
    let m: any
    try {
      m = JSON.parse(text)
    } catch {
      return
    }
    if (m?.t === 'speech.progress' && typeof m.id === 'string') plays.get(m.id)?.report(device, m)
  })
  ws.on('close', () => {
    feeds.delete(f)
    // A phone gone mid-play (the app killed, the network lost): nobody hears the rest. A feed that
    // only reconnects comes back within moments, and the audio's own stream plays on meanwhile.
    const at = Date.now()
    setTimeout(() => {
      if ([...feeds].some((x) => x.device === device)) return
      for (const p of plays.values()) if (p.target === device && p.live && p.createdAt <= at) p.end('stopped', 'lost')
    }, playLimits.lostMs).unref()
  })
}

// ── Plays ────────────────────────────────────────────────────────────────

type PlayState = 'making' | 'playing' | 'ended' | 'stopped' | 'failed'
const plays = new Map<string, Play>()
/** Mutable only so the tests can shorten them. */
export const playLimits = { startMs: 12_000, silentMs: 15_000, lostMs: 4_000, keepMs: 60_000, macMs: 120 }
/** The most one play may read: a long reply with its facts. */
export const MAX_PLAY = 40_000

class Play {
  readonly id = randomBytes(9).toString('base64url')
  readonly target: string
  readonly sessionId: string | null
  readonly source = 'phone'
  readonly of: number
  engine: Engine
  pcm: Buffer[] = []
  bytes = 0
  marks: Mark[] = []
  made = false
  failed: (Error & { code?: string }) | null = null
  state: PlayState = 'making'
  why: string | null = null
  ms = 0
  level = 0
  started = false
  readonly abort = new AbortController()
  readonly createdAt = Date.now()
  private firstAudioAt: number | null = null
  private heardAt = Date.now()
  private macAt = 0
  private waiters = new Set<() => void>()
  private tick: ReturnType<typeof setInterval>

  constructor(o: { target: string; sessionId: string | null; of: number; engine: Engine }) {
    this.target = o.target
    this.sessionId = o.sessionId
    this.of = o.of
    this.engine = o.engine
    this.tick = setInterval(() => this.check(), 1000)
    this.tick.unref()
  }

  get live(): boolean {
    return this.state === 'making' || this.state === 'playing'
  }

  view() {
    return { id: this.id, sessionId: this.sessionId, source: this.source, engine: this.engine, sampleRate: TTS_RATE, format: 'pcm_s16le', of: this.of }
  }

  // The audio, as it's made.
  audio(b: Buffer): void {
    this.pcm.push(b)
    this.bytes += b.length
    this.firstAudioAt ??= Date.now()
    this.wake()
  }
  done(): void {
    this.made = true
    this.wake()
  }
  failedWith(e: Error): void {
    this.failed = e
    this.made = true
    this.wake()
    if (!this.bytes && this.live) this.end('failed', (e as any).code ?? 'speech_failed')
  }
  private wake(): void {
    for (const w of [...this.waiters]) w()
  }

  /** `GET …/audio`: what's made so far, then the rest as it comes; ends when all is made. */
  stream(res: ServerResponse): void {
    res.writeHead(200, { 'content-type': 'audio/pcm', 'x-sample-rate': String(TTS_RATE), 'x-speech-engine': this.engine, 'cache-control': 'no-store' })
    let i = 0
    const pump = () => {
      while (i < this.pcm.length) res.write(this.pcm[i++])
      if (this.made || !this.live) {
        this.waiters.delete(pump)
        res.end()
      }
    }
    this.waiters.add(pump)
    res.on('close', () => this.waiters.delete(pump))
    pump()
  }

  /** Waits for the first audio (or the end): an error before any audio is the answer instead. */
  firstAudio(): Promise<void> {
    if (this.bytes || this.made || !this.live) return Promise.resolve()
    return new Promise((resolve) => {
      const w = () => {
        if (this.bytes || this.made || !this.live) {
          this.waiters.delete(w)
          resolve()
        }
      }
      this.waiters.add(w)
    })
  }

  /** The phone's word on how the play goes. Only the phone playing it is heard. */
  report(device: string, m: any): void {
    if (device !== this.target || !this.live) return
    this.heardAt = Date.now()
    const ms = Number(m.ms)
    if (Number.isFinite(ms) && ms >= 0) this.ms = Math.min(ms, 3600_000)
    const level = Number(m.level)
    this.level = Number.isFinite(level) ? Math.min(1, Math.max(0, level)) : 0
    switch (m.state) {
      case 'started':
      case 'playing':
        this.started = true
        if (this.state !== 'playing') {
          this.state = 'playing'
          return this.relay(true)
        }
        return this.relay(false)
      case 'ended':
        // All it was sent, but the reading broke off before the end of the text: not heard whole.
        return this.failed ? this.end('stopped', 'cut') : this.end('ended', null)
      case 'stopped':
        return this.end('stopped', typeof m.why === 'string' ? m.why.slice(0, 40) : 'phone')
      case 'failed':
        return this.end('failed', typeof m.why === 'string' ? m.why.slice(0, 40) : 'phone')
    }
  }

  /** The Mac app shows it speaking (a few times a second at most). */
  private relay(now: boolean): void {
    const t = Date.now()
    if (now || t - this.macAt >= playLimits.macMs) {
      this.macAt = t
      toMacApps({ t: 'speaking', speaking: { id: this.id, sessionId: this.sessionId, source: this.source, state: this.state, level: Math.round(this.level * 100) / 100, engine: this.engine } })
    }
  }

  /** Over: played to the end, stopped (by whom: `why`), or never played. */
  end(state: 'ended' | 'stopped' | 'failed', why: string | null): void {
    if (!this.live) return
    this.state = state
    this.why = why
    if (state === 'ended') this.ms = Math.max(this.ms, this.bytes / BYTES_PER_MS)
    else {
      this.abort.abort()
      // The phone stops at once, if it isn't the one that stopped it.
      if (why !== 'phone') toDevice(this.target, { t: 'speech.stop', id: this.id })
    }
    this.level = 0
    this.relay(true)
    this.wake()
    clearInterval(this.tick)
    log('speech', 'play', this.source, this.engine, `${(this.bytes / BYTES_PER_MS / 1000).toFixed(1)}s`, state, why ?? '')
    setTimeout(() => plays.delete(this.id), playLimits.keepMs).unref()
  }

  /** Once a second: a phone that never starts, or goes quiet mid-play. */
  private check(): void {
    if (!this.live) return
    const now = Date.now()
    if (!this.started) {
      const since = this.firstAudioAt ?? this.createdAt
      if (now - since > playLimits.startMs * (this.firstAudioAt ? 1 : 2)) this.end('failed', 'no_start')
    } else if (now - this.heardAt > playLimits.silentMs) this.end('stopped', 'lost')
  }
}

/**
 * `POST /v1/speech/play`: [who] asks for [text] to be read, and plays it itself (`to: "me"`, the
 * phone's own Read aloud).
 */
export async function startPlay(b: any, who: string): Promise<{ play: ReturnType<Play['view']> & { to: { id: string; name: string } } }> {
  const text = typeof b?.text === 'string' ? b.text : ''
  if (!text.trim()) throw fail(400, 'empty', 'There’s nothing to read.')
  if (text.length > MAX_PLAY) throw fail(413, 'too_long', `At most ${MAX_PLAY} characters at a time.`)
  if (who === 'mac') throw fail(400, 'bad_target', 'The Mac app doesn’t play speech.')
  const target = who
  const s = speechState()
  const sessionId = typeof b?.sessionId === 'string' && /^[\w-]{1,80}$/.test(b.sessionId) ? b.sessionId : null
  // One play at a time for each phone.
  for (const p of plays.values()) if (p.live && p.target === target) p.end('stopped', 'replaced')
  const play = new Play({ target, sessionId, of: text.length, engine: s.using })
  plays.set(play.id, play)
  void produce(play, text, s)
  const name = listDevices().find((d) => d.id === target)?.name ?? 'Phone'
  return { play: { ...play.view(), to: { id: target, name } } }
}

async function produce(p: Play, text: string, s: SpeechState): Promise<void> {
  const voice = s.using === 'soniox' ? s.voice : s.macVoice
  const key = keyOf(s.using, voice, s.rate, text)
  const hit = kept.get(key)
  if (hit) {
    p.engine = hit.engine
    p.marks = hit.marks
    p.audio(hit.pcm)
    return p.done()
  }
  try {
    const r = await make(text, s, { audio: (b) => p.live && p.audio(b), marks: (m) => p.marks.push(...m), pieceDone: () => {}, engine: (e) => (p.engine = e) }, p.abort.signal)
    // A very long play isn't kept: keep() would drop it at once, after a second copy of it was made.
    if (r.whole && p.live && p.bytes <= KEEP_BYTES) keep(key, { pcm: Buffer.concat(p.pcm), marks: p.marks, engine: r.engine })
    p.done()
  } catch (e: any) {
    if (p.abort.signal.aborted) return p.done()
    log('speech', 'play failed', e?.code ?? '')
    p.failedWith(e)
  }
}

/** `GET /v1/speech/plays/<id>/audio`: only the phone playing it (or the Mac app). */
async function streamPlay(id: string, who: string, res: ServerResponse): Promise<void> {
  const p = plays.get(id)
  if (!p || (who !== p.target && who !== 'mac')) throw fail(404, 'no_play', 'That reading is over.')
  await p.firstAudio()
  if (!p.bytes && p.failed) throw fail(502, (p.failed as any).code === 'no_voice' ? 'no_voice' : 'speech_failed', p.failed.message)
  if (!p.bytes && !p.live) throw fail(410, 'play_over', 'That reading was stopped.')
  p.stream(res)
}

/** `POST /v1/speech/plays/<id>/stop`: the phone playing it, or the Mac app. */
function stopPlay(id: string, who: string): { ok: true; state: PlayState } {
  const p = plays.get(id)
  if (!p || (who !== p.target && who !== 'mac')) throw fail(404, 'no_play', 'That reading is over.')
  p.end('stopped', who === p.target ? 'phone' : 'asked')
  return { ok: true, state: p.state }
}

// ── A whole clip (the phone's older way) ──────────────────────────────

const clipping = new Map<string, AbortController>()

/**
 * `POST /v1/speech`: the whole clip at once. With Soniox, WAV (decodeAudioData takes it in every
 * WebView); with the Mac's voice, AAC in M4A as before. A Mac voice asked for by an older client
 * only counts while the Mac's voice reads; a rate other than 1 always does.
 */
export async function speakClip(b: any, who: string, gone: AbortSignal): Promise<{ bytes: Buffer; type: string; engine: Engine }> {
  const text = typeof b?.text === 'string' ? b.text : ''
  const s = speechState()
  const rate = typeof b?.rate === 'number' && Number.isFinite(b.rate) && b.rate !== 1 ? clampRate(b.rate) : s.rate
  const mac = async () => ({ bytes: await speak(text, typeof b?.voice === 'string' && b.voice ? b.voice : s.macVoice, rate, who, gone), type: 'audio/mp4', engine: 'mac' as Engine })
  if (s.using !== 'soniox' || b?.engine === 'mac') return mac()
  if (!text.trim()) throw fail(400, 'empty', 'There’s nothing to read.')
  if (text.length > MAX_SPEECH) throw fail(413, 'too_long', `At most ${MAX_SPEECH} characters at a time.`)
  // One clip at a time per device: a newer request replaces the one being made.
  clipping.get(who)?.abort()
  const ac = new AbortController()
  clipping.set(who, ac)
  gone.addEventListener('abort', () => ac.abort(), { once: true })
  const key = keyOf('soniox', s.voice, rate, text)
  try {
    const hit = kept.get(key)
    if (hit) return { bytes: wav(hit.pcm), type: 'audio/wav', engine: hit.engine }
    const pcm: Buffer[] = []
    const marks: Mark[] = []
    let engine: Engine = 'soniox'
    const r = await make(text, { ...s, rate }, { audio: (x) => pcm.push(x), marks: (m) => marks.push(...m), pieceDone: () => {}, engine: (e) => (engine = e) }, ac.signal).catch((e) => {
      throw ac.signal.aborted && !gone.aborted ? fail(409, 'replaced', 'A newer request to read aloud replaced this one.') : e
    })
    const all = Buffer.concat(pcm)
    if (r.whole && all.length <= KEEP_BYTES) keep(key, { pcm: all, marks, engine })
    return { bytes: wav(all), type: 'audio/wav', engine }
  } finally {
    if (clipping.get(who) === ac) clipping.delete(who)
  }
}

// ── Routes ───────────────────────────────────────────────────────────────

/**
 * Every `/v1/speech…` route but the companion's (speech.ts): true when it answered. [who] is the
 * paired device, or "mac" for the Mac app's admin token.
 */
export async function speechRoute(path: string, method: string, req: IncomingMessage, res: ServerResponse, who: string, body: () => Promise<any>): Promise<boolean> {
  const json = (status: number, v: unknown) => {
    res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
    res.end(JSON.stringify(v))
    return true
  }
  if (path === '/v1/speech/voices' && method === 'GET') {
    const engine = new URL(req.url ?? '/', 'http://x').searchParams.get('engine')
    return json(200, await voicesFor(engine))
  }
  if (path === '/v1/speech/settings' && method === 'GET') return json(200, { speech: speechState() })
  if (path === '/v1/speech/settings' && method === 'POST') return json(200, { speech: await saveSpeech(await body()) })
  if (path === '/v1/speech/play' && method === 'POST') return json(200, await startPlay(await body(), who))
  const m = /^\/v1\/speech\/plays\/([\w-]{1,40})\/(audio|stop)$/.exec(path)
  if (m && m[2] === 'audio' && method === 'GET') {
    await streamPlay(m[1]!, who, res)
    return true
  }
  if (m && m[2] === 'stop' && method === 'POST') return json(200, stopPlay(m[1]!, who))
  if (path === '/v1/speech' && method === 'POST') {
    const b = await body()
    const gone = new AbortController()
    res.on('close', () => gone.abort())
    const clip = await speakClip(b, who, gone.signal)
    res.writeHead(200, { 'content-type': clip.type, 'content-length': clip.bytes.length, 'cache-control': 'no-store', 'x-speech-engine': clip.engine })
    res.end(clip.bytes)
    return true
  }
  return false
}

/** Plays under way (tests). */
export const livePlays = () => [...plays.values()].filter((p) => p.live).length
