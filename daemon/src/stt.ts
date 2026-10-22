// Speech to text through Soniox's real-time API (docs/PROTOCOL.md, "Speech to text"). Lens streams
// the glasses' microphone, and the Android app the phone's (both 16 kHz 16-bit mono PCM), to the
// daemon on the phone API; the daemon relays it to Soniox with the owner's key and sends the words
// back as they settle, in the shape Lens already reads from Hyperscribe Companion (hello, start,
// started, snapshot, stop, error, ping).
// The key lives only on this Mac (<support>/stt.json, 0600) and never goes back to a client: they
// see whether voice is ready and the key's last four characters. Audio, words and the key are never
// logged or written down.
import type { IncomingMessage } from 'node:http'
import type { Duplex } from 'node:stream'
import { rmSync } from 'node:fs'
import { join } from 'node:path'
import { WebSocket, WebSocketServer, type RawData } from 'ws'
import { SUPPORT, VERSION, log, readJson, writeJson } from './util.ts'
import { broadcast } from './hub.ts'
import { listProjects } from './services.ts'

/** Soniox's real-time endpoint; HYPERNOVA_SONIOX_WS points the tests at a stand-in. */
const SONIOX_WS = () => process.env.HYPERNOVA_SONIOX_WS ?? 'wss://stt-rt.soniox.com/transcribe-websocket'
/** The current real-time model (stt-rt-v4 is an alias of it since June 2026). */
export const SONIOX_MODEL = 'stt-rt-v5'
/** Where the owner gets a key. */
export const SONIOX_CONSOLE = 'https://console.soniox.com'
const FILE = join(SUPPORT, 'stt.json')

/** The glasses' audio: 16 kHz, 16-bit, mono. */
const BYTES_PER_SECOND = 16_000 * 2

/**
 * Limits for one stream. Mutable only so the tests can shorten them.
 * - maxMs: the most audio one hold may carry (a prompt, not a meeting); then what was said is kept.
 * - startMs: from connecting to `start`.
 * - idleMs: no audio frames at all (a mic that stalled; silence still comes as frames).
 * - keepaliveMs: Soniox closes a stream with no audio or keepalive for 20 s.
 * - connectMs: reaching Soniox. finishMs: Soniox's last words after the end of the audio.
 */
export const sttLimits = { maxMs: 120_000, startMs: 15_000, idleMs: 20_000, keepaliveMs: 8_000, connectMs: 8_000, finishMs: 5_000 }
/** A frame from the phone: the glasses send 40 ms (1280 bytes); anything this big is not audio. */
const MAX_FRAME = 64 * 1024

const fail = (status: number, code: string, message: string) => Object.assign(new Error(message), { status, code })

// ── The key ──────────────────────────────────────────────────────────────

type ProblemCode = 'bad_key' | 'no_credit' | 'unreachable' | 'busy' | 'soniox'
type Problem = { code: ProblemCode; message: string }
type Saved = { provider: 'soniox'; apiKey: string; savedAt: number; problem: Problem | null }

let saved: Saved | null = (() => {
  const s = readJson<Saved | null>(FILE, null)
  return s && s.provider === 'soniox' && typeof s.apiKey === 'string' && s.apiKey ? { ...s, problem: s.problem ?? null } : null
})()

/** A key Soniox refused, or an account that can't pay: voice isn't ready until it's fixed. */
const blocking = (p: Problem | null) => p?.code === 'bad_key' || p?.code === 'no_credit'

/** What every client sees (the snapshot's `stt`): whether voice works, never the key. */
export function sttState(): { provider: 'soniox' | null; ready: boolean; error: string | null } {
  return { provider: saved ? 'soniox' : null, ready: !!saved && !blocking(saved.problem), error: saved?.problem?.message ?? null }
}

/** `GET /v1/stt`: the state plus the key's last four characters, the model and where to get a key. */
export function sttInfo() {
  return { ...sttState(), last4: saved ? saved.apiKey.slice(-4) : null, model: SONIOX_MODEL, savedAt: saved?.savedAt ?? null, keysUrl: SONIOX_CONSOLE }
}

/**
 * The key, for read aloud (tts.ts), which sends it to Soniox and nowhere else. Never for a client,
 * an answer or a log.
 */
export const sonioxKey = (): string | null => saved?.apiKey ?? null

/** Read aloud follows the key: Soniox's voices while it works, the Mac's when it doesn't (readaloud.ts). */
const watchers = new Set<() => void>()
export function onSttChange(fn: () => void): void {
  watchers.add(fn)
}

let lastSent = JSON.stringify(sttState())
function changed(): void {
  const now = JSON.stringify(sttState())
  if (now === lastSent) return
  lastSent = now
  broadcast({ t: 'stt', stt: sttState() })
  for (const fn of watchers) {
    try {
      fn()
    } catch {}
  }
}

function store(next: Saved | null): void {
  saved = next
  if (next) writeJson(FILE, next)
  else rmSync(FILE, { force: true })
  changed()
}

/** The last account-level news from Soniox; a stream that works clears it. */
function note(p: Problem | null): void {
  if (!saved) return
  if (JSON.stringify(saved.problem) === JSON.stringify(p)) return
  store({ ...saved, problem: p })
}

/** Soniox keys are long runs of letters and digits; spaces or line breaks mean a bad paste. */
function cleanKey(raw: unknown): string {
  const k = typeof raw === 'string' ? raw.trim() : ''
  if (!k) throw fail(400, 'no_key', 'Paste your Soniox API key.')
  if (!/^[\x21-\x7e]{16,512}$/.test(k)) throw fail(400, 'bad_key', 'That doesn’t look like a Soniox API key.')
  return k
}

/** Plain words for an error Soniox sent (`error_code` and `error_type`, docs "Errors"). */
export function problemFor(status: number, type: string, message: string): Problem {
  if (status === 401 || type === 'unauthenticated' || type === 'permission_denied' || type === 'temp_api_key_session_expired' || status === 403) {
    return { code: 'bad_key', message: 'Soniox didn’t accept the key. Paste a new one from console.soniox.com.' }
  }
  if (status === 402) return { code: 'no_credit', message: 'Your Soniox balance is used up. Add credit at console.soniox.com.' }
  if (status === 429) return { code: 'busy', message: 'Soniox is at its limit of streams at once. Try again in a moment.' }
  if (status >= 500 || status === 408) return { code: 'soniox', message: 'Soniox had a problem with that stream. Try again.' }
  // A request Soniox found wrong (a model or setting it doesn't take): its own words, briefly.
  const words = String(message || 'Soniox refused the stream.').replace(/\s*\(?request ID[^)]*\)?/gi, '').replace(/https?:\/\/\S+/g, '').trim()
  return { code: 'soniox', message: words.length > 160 ? words.slice(0, 159) + '…' : words }
}
const unreachable = (): Problem => ({ code: 'unreachable', message: 'Couldn’t reach Soniox from this Mac. Is it online?' })

/**
 * Soniox's config for one stream: our audio, the language hints and words worth knowing. [terms]
 * are the context's words (contextTerms(); none for a key's test, which hears only silence).
 */
export function sonioxConfig(language: unknown, terms: string[] = contextTerms()): Record<string, unknown> {
  return {
    model: SONIOX_MODEL,
    audio_format: 'pcm_s16le',
    sample_rate: 16_000,
    num_channels: 1,
    language_hints: languageHints(language),
    // Project names, so "in hyperscribe, …" and "new session in 0stems" route (Lens parses them).
    ...(terms.length ? { context: { terms } } : {}),
  }
}

/** A project folder with no sessions still counts if it changed this recently (one just made). */
const RECENT_MS = 14 * 86_400_000
/** Enough for the projects in use; a long list of every folder helps recognition no more. */
const MAX_TERMS = 20
/**
 * Lens's own command words, which Soniox otherwise hears as ordinary English: "Telescope terminal"
 * came back as "To the scope terminal" and went to Claude as a prompt. With the names of the
 * sources Telescope looks at. A fixed few, on top of the projects' twenty.
 */
export const LENS_TERMS = ['Telescope', 'debrief', 'eclipse', 'note', 'emulator', 'simulator', 'terminal', 'web page', 'window', 'PDF']

/**
 * The words Soniox gets as context: "Claude Code", "Hypernova", then the projects in use (with
 * sessions, or a folder changed in the last two weeks), newest first, twenty in all; then Lens's
 * command words (LENS_TERMS). Every other folder in the projects root stays on the Mac. A name in
 * snake_case or kebab-case goes as the words it's made of ("fuel charge statistics"), which is
 * what gets said and what Lens matches; nothing goes twice, whatever its case.
 */
export function contextTerms(projects: Array<{ name: string; lastActivityAt: number | null; sessionCount: number }> = safeProjects(), now = Date.now()): string[] {
  const out: string[] = []
  const seen = new Set<string>()
  const add = (t: string, max = MAX_TERMS) => {
    const k = t.toLowerCase()
    if (!t || seen.has(k) || out.length >= max) return
    seen.add(k)
    out.push(t)
  }
  add('Claude Code')
  add('Hypernova')
  const used = projects
    .filter((p) => p.sessionCount > 0 || (p.lastActivityAt != null && now - p.lastActivityAt < RECENT_MS))
    .sort((a, b) => (b.lastActivityAt ?? 0) - (a.lastActivityAt ?? 0))
  for (const p of used) {
    const words = String(p.name ?? '').replace(/[_-]+/g, ' ').replace(/\s+/g, ' ').trim()
    if (words.length <= 60) add(words)
  }
  for (const t of LENS_TERMS) add(t, Infinity)
  return out
}

function safeProjects() {
  try {
    return listProjects()
  } catch {
    return []
  }
}

/**
 * Hints, not limits (Soniox still hears any language): what Lens' setting asks for, with English
 * beside it (code and product names are English); "auto" hints the owner's two, English and Russian.
 */
export function languageHints(language: unknown): string[] {
  const l = typeof language === 'string' ? language.trim().toLowerCase() : ''
  if (!/^[a-z]{2,3}$/.test(l)) return ['en', 'ru']
  return l === 'en' ? ['en'] : [l, 'en']
}

function connectSoniox(key: string): WebSocket {
  return new WebSocket(SONIOX_WS(), { headers: { Authorization: `Bearer ${key}` }, handshakeTimeout: sttLimits.connectMs, perMessageDeflate: false })
}

/**
 * The honest test of a key: a quarter of a second of silence through a real stream. It checks the
 * key, the account's balance, the model and the way out of this Mac at once, for a fraction of a
 * cent. Resolves to null when it went through, else what went wrong.
 */
function probe(key: string): Promise<Problem | null> {
  return new Promise((resolve) => {
    let done = false
    const ws = connectSoniox(key)
    const end = (p: Problem | null) => {
      if (done) return
      done = true
      clearTimeout(timer)
      try {
        ws.terminate()
      } catch {}
      resolve(p)
    }
    const timer = setTimeout(() => end(unreachable()), sttLimits.connectMs + sttLimits.finishMs)
    ws.on('open', () => {
      ws.send(JSON.stringify(sonioxConfig('en', [])))
      ws.send(Buffer.alloc(BYTES_PER_SECOND / 4))
      ws.send('')
    })
    ws.on('message', (data) => {
      const m = parse(data)
      if (!m) return
      if (m.error_code != null) end(problemFor(Number(m.error_code), String(m.error_type ?? ''), String(m.error_message ?? '')))
      else if (m.finished === true) end(null)
    })
    ws.on('unexpected-response', (_req, res) => end(problemFor(res.statusCode ?? 0, '', `Soniox answered ${res.statusCode}.`)))
    ws.on('error', () => end(unreachable()))
    ws.on('close', () => end(unreachable()))
  })
}

/** One test at a time: each one opens a stream at Soniox. */
let probing: Promise<unknown> = Promise.resolve()
function probeOnce(key: string): Promise<Problem | null> {
  const run = probing.then(() => probe(key))
  probing = run.catch(() => {})
  return run
}

/** Moves on whenever the key is removed, so a save whose test was still running then never brings it back. */
let keyGen = 0

/**
 * Saves a key once Soniox has taken it. A key Soniox refuses isn't kept (400 `bad_key`). One it
 * takes but can't use yet (no balance) is kept and says so. When Soniox can't be reached the key is
 * kept unchecked (`checked: false`): the next stream tells. Any other answer (Soniox busy, or a
 * problem on its side) keeps the key too, with Soniox's words in `problem`.
 */
export async function saveKey(raw: unknown): Promise<ReturnType<typeof sttInfo> & { checked: boolean; problem: string | null }> {
  const key = cleanKey(raw)
  const gen = keyGen
  const p = await probeOnce(key)
  if (p?.code === 'bad_key') throw fail(400, 'bad_key', 'Soniox didn’t accept that key. Copy it again from console.soniox.com.')
  // Removed on another screen while Soniox was trying this one: the removal stands.
  if (gen !== keyGen) throw fail(409, 'removed', 'The Soniox key was removed meanwhile. Connect it again to use it.')
  for (const s of streams.values()) s.drop('replaced', 'The Soniox key changed. Hold to talk again.')
  // Only what is about the account stays with the key; a network hiccup is the next stream's to tell.
  store({ provider: 'soniox', apiKey: key, savedAt: Date.now(), problem: p?.code === 'no_credit' ? p : null })
  log('stt', 'soniox key saved', p ? `(${p.code})` : '')
  return { ...sttInfo(), checked: p?.code !== 'unreachable', problem: p && p.code !== 'no_credit' ? p.message : null }
}

export function removeKey(): void {
  keyGen++
  for (const s of streams.values()) s.drop('no_key', 'Soniox was disconnected on your Mac.')
  if (saved) log('stt', 'soniox key removed')
  store(null)
}

/** `POST /v1/stt/test`: the saved key through a real stream, in plain words. */
export async function testKey(): Promise<{ ok: boolean; message: string } & ReturnType<typeof sttInfo>> {
  if (!saved) throw fail(409, 'no_key', 'Connect Soniox first.')
  const key = saved.apiKey
  const p = await probeOnce(key)
  // What it found is about this key only: one removed or replaced meanwhile keeps its own state.
  if (saved?.apiKey === key && (!p || blocking(p))) note(p)
  const message = !p ? 'Soniox heard the test. Voice is ready.' : p.message
  return { ok: !p, message, ...sttInfo() }
}

/** The REST routes under /v1/stt (also on the Mac's port with the admin token). */
export async function sttApi(path: string, method: string, body: () => Promise<any>): Promise<unknown> {
  if (path === '/v1/stt' && method === 'GET') return sttInfo()
  if (path === '/v1/stt/key' && method === 'POST') {
    const b = await body()
    if (b?.provider != null && b.provider !== 'soniox') throw fail(400, 'bad_provider', 'Only Soniox can be connected here.')
    return saveKey(b?.apiKey)
  }
  if (path === '/v1/stt/key' && method === 'DELETE') {
    removeKey()
    return sttInfo()
  }
  if (path === '/v1/stt/test' && method === 'POST') return testKey()
  throw fail(404, 'not_found', 'Unknown endpoint.')
}

// ── Streams ─────────────────────────────────────────────────────────────

function parse(data: RawData | string): Record<string, any> | null {
  try {
    const m = JSON.parse(String(data))
    return m && typeof m === 'object' && !Array.isArray(m) ? m : null
  } catch {
    return null
  }
}

/**
 * The words so far, as Lens shows them: settled text, then what may still change. The split falls
 * between words: Soniox settles pieces of words, and a word half settled stays whole in the second
 * part, so the phone's join of the two with a space never splits one.
 */
export function captions(final: string, interim: string): { finalText: string; interimText: string } {
  let cut = final.length
  if (interim && !/^\s/.test(interim) && final && !/\s$/.test(final)) {
    const m = /\s\S*$/.exec(final)
    cut = m ? m.index : 0
  }
  const all = final + interim
  const tidy = (s: string) => s.replace(/\s+/g, ' ').trim()
  return { finalText: tidy(all.slice(0, cut)), interimText: tidy(all.slice(cut)) }
}

/** Soniox's markers (an endpoint, a manual finalize): not words. */
const MARKERS = new Set(['<end>', '<fin>'])

/** The open stream of each paired device (the Mac app's own is "mac"). */
const streams = new Map<string, Relay>()

/**
 * One stream: a phone on one side, Soniox on the other. Speaks Hyperscribe Companion's protocol v1
 * to the phone, so Lens' companion client works unchanged.
 */
class Relay {
  private client: WebSocket
  private who: string
  private up: WebSocket | null = null
  private phase: 'hello' | 'connecting' | 'live' | 'ending' | 'done' = 'hello'
  private final = ''
  private interim = ''
  private lastCaption = ''
  private bytes = 0
  private held: Buffer[] = []
  private heldBytes = 0
  private started = Date.now()
  private lastAudio = Date.now()
  private lastKeepalive = Date.now()
  private timers: ReturnType<typeof setTimeout>[] = []
  private tick: ReturnType<typeof setInterval> | null = null
  /** Why the stream ends when Soniox's last words arrive: the phone stopped, or the time ran out. */
  private ending: 'stop' | 'too_long' = 'stop'
  /**
   * The phone let go while Soniox was still being reached: once it opens, the audio held so far
   * goes, then the end. Else a quick "yes" over a slow link would come back empty.
   */
  private stopWanted = false
  /** When the phone let go, and how long its last words took to leave from then (the log's last figure). */
  private stopAt: number | null = null
  private releaseMs: number | null = null
  private outcome = 'closed'

  constructor(client: WebSocket, who: string) {
    this.client = client
    this.who = who
    streams.get(who)?.drop('replaced', 'Another stream from this device took over.', 4000)
    streams.set(who, this)
    client.on('message', (data, isBinary) => (isBinary ? this.audio(data) : this.control(String(data))))
    client.on('close', () => this.cleanup())
    client.on('error', () => {})
    if (!saved) {
      this.send({ type: 'error', code: 'no_key', message: 'Connect Soniox in Hypernova on your Mac first.' })
      this.phase = 'done'
      this.outcome = 'no key'
      client.close(1000)
      return
    }
    this.send({ type: 'hello', ok: true, name: 'Hypernova', version: VERSION, model: SONIOX_MODEL, modelLabel: 'Soniox', languages: '*', busy: false })
    this.later(sttLimits.startMs, () => this.phase === 'hello' && this.drop('idle', 'The stream never started.'))
  }

  private send(m: object): void {
    if (this.client.readyState === WebSocket.OPEN) this.client.send(JSON.stringify(m))
  }

  private later(ms: number, fn: () => void): void {
    const t = setTimeout(fn, ms)
    t.unref()
    this.timers.push(t)
  }

  private control(text: string): void {
    const m = parse(text)
    if (!m) return
    if (m.type === 'ping') return this.send({ type: 'pong' })
    if (m.type === 'start' && this.phase === 'hello') return this.connect(m.language)
    if (m.type === 'stop') return this.stop('stop')
  }

  private connect(language: unknown): void {
    const key = saved?.apiKey
    if (!key) return this.drop('no_key', 'Connect Soniox in Hypernova on your Mac first.')
    this.phase = 'connecting'
    this.started = this.lastAudio = this.lastKeepalive = Date.now()
    const up = (this.up = connectSoniox(key))
    up.on('open', () => {
      if (this.phase !== 'connecting') return
      up.send(JSON.stringify(sonioxConfig(language)))
      for (const b of this.held) up.send(b)
      this.held = []
      this.heldBytes = 0
      this.phase = 'live'
      this.send({ type: 'started' })
      this.tick = setInterval(() => this.check(), 1000)
      this.tick.unref()
      if (this.stopWanted) this.end(this.ending)
    })
    up.on('message', (data) => this.fromSoniox(data))
    up.on('unexpected-response', (_req, res) => this.refused(problemFor(res.statusCode ?? 0, '', `Soniox answered ${res.statusCode}.`)))
    up.on('error', () => {
      if (this.phase === 'connecting') this.refused(unreachable())
    })
    up.on('close', () => {
      if (this.phase === 'ending') this.finish()
      else if (this.phase !== 'done') this.drop('lost', 'Soniox closed the stream. What you said so far is kept.')
    })
  }

  /** Once a second: the time cap, a stalled microphone, and Soniox's keepalive while no audio comes. */
  private check(): void {
    if (this.phase !== 'live') return
    const now = Date.now()
    if (now - this.started > sttLimits.maxMs + 15_000) return this.stop('too_long')
    if (now - this.lastAudio > sttLimits.idleMs) return this.drop('idle', 'No sound from the microphone for a while, so listening stopped.')
    if (now - this.lastAudio > sttLimits.keepaliveMs && now - this.lastKeepalive > sttLimits.keepaliveMs && this.up?.readyState === WebSocket.OPEN) {
      this.lastKeepalive = now
      this.up.send('{"type":"keepalive"}')
    }
  }

  private audio(data: RawData): void {
    if ((this.phase !== 'connecting' && this.phase !== 'live') || this.stopWanted) return
    const buf = Array.isArray(data) ? Buffer.concat(data) : Buffer.isBuffer(data) ? data : Buffer.from(data as ArrayBuffer)
    if (buf.length % 2 || buf.length > MAX_FRAME) return this.drop('bad_audio', 'That wasn’t 16-bit audio.')
    this.lastAudio = Date.now()
    this.bytes += buf.length
    if (this.bytes > (sttLimits.maxMs / 1000) * BYTES_PER_SECOND) return this.stop('too_long')
    if (this.phase === 'live' && this.up?.readyState === WebSocket.OPEN) this.up.send(buf)
    else if (this.heldBytes + buf.length <= BYTES_PER_SECOND * 10) {
      this.held.push(buf)
      this.heldBytes += buf.length
    }
  }

  private fromSoniox(data: RawData): void {
    const m = parse(data)
    if (!m || this.phase === 'done') return
    if (m.error_code != null) {
      const p = problemFor(Number(m.error_code), String(m.error_type ?? ''), String(m.error_message ?? ''))
      // The longest stream Soniox takes: what was said is kept, as at our own cap.
      if (m.error_type === 'max_duration_reached') return this.finish('too_long')
      return this.refused(p)
    }
    if (Array.isArray(m.tokens) && m.tokens.length) {
      let interim = ''
      for (const t of m.tokens) {
        const text = typeof t?.text === 'string' ? t.text : ''
        if (!text || MARKERS.has(text)) continue
        if (t.is_final === true) this.final += text
        else interim += text
      }
      this.interim = interim
      this.caption(false)
    }
    // A stream that works shows the account is fine again.
    if (saved?.problem) note(null)
    if (m.finished === true) this.finish()
  }

  private caption(finished: boolean): void {
    const c = captions(this.final, this.interim)
    const key = `${c.finalText}\n${c.interimText}\n${finished}`
    if (key === this.lastCaption) return
    this.lastCaption = key
    this.send({ type: 'snapshot', ...c, finished })
  }

  /**
   * The phone let go (or the time ran out): Soniox finishes the words it has, then we close. While
   * Soniox is still being reached, the stop waits for it, so the audio held meanwhile still counts;
   * a connection that fails still ends in `unreachable`. Before `start` there is no audio at all.
   */
  private stop(why: 'stop' | 'too_long'): void {
    this.stopAt ??= Date.now()
    if (this.phase === 'hello') {
      this.ending = why
      return this.finish()
    }
    if (this.phase === 'connecting') {
      this.ending = why
      this.stopWanted = true
      return
    }
    if (this.phase === 'live') this.end(why)
  }

  /** Tells Soniox the audio ended; its last words close the stream, or the time does. */
  private end(why: 'stop' | 'too_long'): void {
    this.phase = 'ending'
    this.ending = why
    try {
      this.up?.send('')
    } catch {}
    this.later(sttLimits.finishMs, () => this.phase === 'ending' && this.finish())
  }

  /** The last words are leaving for the phone: how long that took from its stop, for the log. */
  private released(): void {
    if (this.stopAt != null && this.releaseMs == null) this.releaseMs = Date.now() - this.stopAt
  }

  /** The last words: everything said is settled now. */
  private finish(why: 'stop' | 'too_long' = this.ending): void {
    if (this.phase === 'done') return
    this.phase = 'done'
    this.final += this.interim
    this.interim = ''
    const c = captions(this.final, '')
    this.released()
    if (why === 'too_long') {
      // An error that carries the whole text: Lens keeps the words and stops listening.
      this.send({ type: 'error', code: 'too_long', message: `${Math.round(sttLimits.maxMs / 60_000)} minutes at most at a time. What you said is kept.`, finished: true, ...c })
      this.outcome = 'too long'
    } else {
      this.caption(true)
      this.outcome = 'done'
    }
    this.close()
  }

  /** Soniox refused or couldn't be reached. An account problem is remembered (the snapshot says so). */
  private refused(p: Problem): void {
    if (blocking(p)) note(p)
    this.drop(p.code, p.message)
  }

  /** Ends the stream with an error; words already heard go with it, as the companion does. */
  drop(code: string, message: string, closeCode = 1000): void {
    if (this.phase === 'done') return
    const live = this.phase === 'live' || this.phase === 'ending'
    this.phase = 'done'
    this.outcome = code
    if (live) this.released()
    if (live) this.send({ type: 'error', code, message, finished: true, ...captions(this.final + this.interim, '') })
    else this.send({ type: 'error', code, message })
    this.close(closeCode)
  }

  private close(code = 1000): void {
    try {
      if (this.client.readyState === WebSocket.OPEN) this.client.close(code)
    } catch {}
    this.cleanup()
  }

  private cleanup(): void {
    for (const t of this.timers) clearTimeout(t)
    this.timers = []
    if (this.tick) clearInterval(this.tick)
    this.tick = null
    if (this.up) {
      try {
        this.up.terminate()
      } catch {}
      this.up = null
    }
    if (this.phase !== 'done') {
      this.phase = 'done'
      this.outcome = 'phone left'
    }
    if (streams.get(this.who) === this) {
      streams.delete(this.who)
      // How long, how it ended, and from letting go to the last words; never what was said.
      log('stt', 'stream', this.who, `${(this.bytes / BYTES_PER_SECOND).toFixed(1)}s`, this.outcome, ...(this.releaseMs != null ? [`${this.releaseMs}ms`] : []))
    }
  }
}

const wss = new WebSocketServer({ noServer: true, perMessageDeflate: false, maxPayload: MAX_FRAME * 2 })

/**
 * `GET /v1/stt/ws`, once http.ts has checked the token: [who] is the paired device (one stream
 * each; a new one replaces the old), [onSocket] lets it track the socket (forgetting the device
 * closes it).
 */
export function sttUpgrade(req: IncomingMessage, socket: Duplex, head: Buffer, who: string, onSocket: (ws: WebSocket) => void): void {
  wss.handleUpgrade(req, socket, head, (ws) => {
    onSocket(ws)
    new Relay(ws, who)
  })
}

/** Open streams (tests). */
export const sttStreams = () => streams.size
