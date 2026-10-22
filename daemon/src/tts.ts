// Read aloud with Soniox's text-to-speech (docs/PROTOCOL.md, "Read aloud"): natural voices that
// speak Russian as well as English, over Soniox's real-time WebSocket, so the phone starts playing
// while the rest of a reply is still being made. The key is the one kept for speech to text
// (stt.ts); it goes to Soniox only, as the Authorization header, never into a log, an answer or a
// client. The text is never logged or written down either.
import { WebSocket, type RawData } from 'ws'
import { problemFor, sonioxKey } from './stt.ts'

/** Soniox's TTS endpoints; HYPERNOVA_SONIOX_TTS_WS and HYPERNOVA_SONIOX_API point the tests at a stand-in. */
const TTS_WS = () => process.env.HYPERNOVA_SONIOX_TTS_WS ?? 'wss://tts-rt.soniox.com/tts-websocket'
const SONIOX_API = () => (process.env.HYPERNOVA_SONIOX_API ?? 'https://api.soniox.com').replace(/\/+$/, '')

/**
 * The current model: generally available since 11 August 2026. tts-rt-v1 was retired on 31 August
 * (its requests go to v2), so the models list is asked which is live and v1 is only a last resort.
 */
export const TTS_MODEL = 'tts-rt-v2'
const OLDER_MODEL = 'tts-rt-v1'
/** A voice Soniox's own examples use with v2; the list (sonioxVoices) replaces it once it's known. */
export const DEFAULT_VOICE = 'Adrian'
/** 24 kHz 16-bit mono PCM: the phone plays it as it comes, and a WebView gets it wrapped as WAV. */
export const TTS_RATE = 24_000
export const BYTES_PER_MS = (TTS_RATE * 2) / 1000
/** Soniox's speed range; our rate (0.5–2, the Mac's) is clamped to it. */
export const sonioxSpeed = (rate: number) => Math.min(1.3, Math.max(0.7, Number.isFinite(rate) && rate > 0 ? rate : 1))

/**
 * Mutable only so the tests can shorten them. connectMs: reaching Soniox. firstAudioMs: a piece's
 * first audio after its text went. idleMs: silence between two frames once it is coming (a hang
 * must reach the Mac's voice within seconds, well inside the play's own no-audio watchdog, which is
 * 24 s: models list 4 + connect 4.5 + first audio 6). baseMs + perCharMs a character: the most a
 * whole piece may take, whatever its length (a long piece is up to 2 minutes of audio, and Soniox
 * may stream it near real time).
 */
export const ttsLimits = { connectMs: 4_000, firstAudioMs: 6_000, idleMs: 6_000, baseMs: 30_000, perCharMs: 150 }

export type TtsProblem = { code: 'no_key' | 'bad_key' | 'no_credit' | 'busy' | 'soniox' | 'unreachable' | 'stopped'; message: string }
const problem = (code: TtsProblem['code'], message: string) => Object.assign(new Error(message), { code, status: code === 'no_key' ? 409 : 502 })
const unreachable = () => problem('unreachable', 'Couldn’t reach Soniox from this Mac. Is it online?')

// ── Voices ───────────────────────────────────────────────────────────────

export type TtsVoice = { id: string; name: string; lang: string; gender: string | null; description: string | null }

/** The live model and its voices, for six hours (Soniox limits how often the list may be asked for). */
let listed: { key: string; at: number; model: string; voices: TtsVoice[] } | null = null
/** When the list last failed to come: a read doesn't wait for it again for a few minutes. */
let listFailedAt = 0
const LIST_MS = 6 * 3600_000

/** The model reads use: the one the list said is live, else v2. */
export const ttsModel = () => listed?.model ?? TTS_MODEL

/**
 * Of Soniox's models (`GET /v1/tts-models`), the one to use: v2 while it's listed, else the newest
 * real-time one that isn't an alias, else v1. Its voices, each with Soniox's gender and words.
 */
export function pickModel(models: unknown): { model: string; voices: TtsVoice[] } | null {
  if (!Array.isArray(models)) return null
  const real = models.filter((m: any) => m && typeof m.id === 'string')
  const m: any =
    real.find((x: any) => x.id === TTS_MODEL) ??
    real.filter((x: any) => /^tts-rt-v\d+$/.test(x.id) && !x.aliased_model_id).sort((a: any, b: any) => Number(b.id.slice(8)) - Number(a.id.slice(8)))[0] ??
    real.find((x: any) => x.id === OLDER_MODEL) ??
    real.find((x: any) => typeof x.aliased_model_id === 'string' && /^tts-rt-/.test(x.aliased_model_id))
  if (!m) return null
  const voices: TtsVoice[] = []
  for (const v of Array.isArray(m.voices) ? m.voices : []) {
    const id = typeof v?.id === 'string' ? v.id.trim() : ''
    if (!id || id.length > 50 || voices.some((x) => x.id === id)) continue
    const description = typeof v.description === 'string' && v.description.trim() ? v.description.trim().slice(0, 140) : null
    voices.push({ id, name: id, lang: '*', gender: typeof v.gender === 'string' ? v.gender : null, description })
  }
  return { model: m.aliased_model_id && typeof m.aliased_model_id === 'string' ? m.aliased_model_id : m.id, voices }
}

/** Soniox's voices (every one speaks every language), asked for once in six hours. */
export async function sonioxVoices(force = false): Promise<{ model: string; voices: TtsVoice[] }> {
  const key = sonioxKey()
  if (!key) throw problem('no_key', 'Connect Soniox first.')
  if (!force && listed && listed.key === key && Date.now() - listed.at < LIST_MS) return { model: listed.model, voices: listed.voices }
  let res: Response
  try {
    res = await fetch(`${SONIOX_API()}/v1/tts-models`, { headers: { Authorization: `Bearer ${key}` }, signal: AbortSignal.timeout(ttsLimits.connectMs) })
  } catch {
    throw unreachable()
  }
  if (!res.ok) {
    const body: any = await res.json().catch(() => null)
    const p = problemFor(res.status, String(body?.error_type ?? ''), String(body?.message ?? body?.error_message ?? ''))
    throw problem(p.code, p.message)
  }
  const picked = pickModel(((await res.json().catch(() => null)) as any)?.models)
  if (!picked) throw problem('soniox', 'Soniox listed no voices to read with.')
  listed = { key, at: Date.now(), ...picked }
  return picked
}

/** A voice for Soniox: the one asked for when the list has it (or the list isn't known yet), else the first listed. */
export function sonioxVoiceFor(wanted: unknown): string {
  const voices = listed?.voices ?? []
  const w = typeof wanted === 'string' ? wanted.trim() : ''
  if (w && (!voices.length || voices.some((v) => v.id === w))) return w
  return voices.find((v) => v.id === DEFAULT_VOICE)?.id ?? voices[0]?.id ?? DEFAULT_VOICE
}

// ── Text ─────────────────────────────────────────────────────────────────

/**
 * What Soniox reads: v2 takes words in square brackets as stage directions ("[whispering]",
 * "[laughs]"), and a reply's "[1]" or "[x]" is only text. Parentheses read the same and keep every
 * character where it was, so timings still point into the text as given.
 */
export const ttsText = (s: string) => s.replace(/\[/g, '(').replace(/]/g, ')')

/**
 * The main language of a piece, which Soniox needs (it still reads the odd word in another one):
 * by which letters it has most of. Code and product names are English.
 */
export function speechLanguage(text: string): string {
  const counts: Record<string, number> = {}
  const scripts: Array<[RegExp, string]> = [
    [/[Ѐ-ӿ]/, 'ru'], [/[a-z]/i, 'en'], [/[Ͱ-Ͽ]/, 'el'], [/[֐-׿]/, 'he'], [/[؀-ۿ]/, 'ar'],
    [/[ऀ-ॿ]/, 'hi'], [/[぀-ヿ]/, 'ja'], [/[가-힯]/, 'ko'], [/[一-鿿]/, 'zh'],
  ]
  for (const ch of text) {
    for (const [re, lang] of scripts) {
      if (re.test(ch)) {
        counts[lang] = (counts[lang] ?? 0) + 1
        break
      }
    }
  }
  // Kana with kanji is Japanese, not Chinese.
  if (counts.ja && counts.zh) counts.ja += counts.zh
  // Code and product names are English in any reply, so Latin letters count for less.
  if (counts.en) counts.en *= 0.6
  let best = 'en'
  for (const [lang, n] of Object.entries(counts)) if (n > (counts[best] ?? 0)) best = lang
  return best
}

/**
 * The text in pieces, each cut after a sentence where one ends near the limit (else between words):
 * Soniox makes at most 2 minutes of audio a stream, and the first piece is short so the voice starts
 * at once. `at` is where each starts in the text.
 */
export function ttsPieces(text: string, first = 320, max = 1100): Array<{ text: string; at: number }> {
  const out: Array<{ text: string; at: number }> = []
  let at = 0
  while (at < text.length) {
    const limit = out.length ? max : first
    let end = Math.min(text.length, at + limit)
    if (end < text.length) {
      const window = text.slice(at, end)
      const sentence = Math.max(...['. ', '! ', '? ', '… ', '\n', '.\n', '。'].map((m) => window.lastIndexOf(m)))
      const space = window.lastIndexOf(' ')
      if (sentence > limit / 3) end = at + sentence + 1
      else if (space > limit / 3) end = at + space + 1
    }
    const piece = text.slice(at, end)
    if (piece.trim()) out.push({ text: piece, at })
    at = end
  }
  return out
}

// ── Speaking ─────────────────────────────────────────────────────────────

/** Where the voice is: character `at` of the text starts `ms` into the audio. */
export type Mark = { at: number; ms: number }

export type TtsSink = {
  /** The next PCM, as it's made. */
  audio(pcm: Buffer): void
  /** When characters start, for text shown in step with the voice. */
  marks(m: Mark[]): void
  /** A piece is all made: everything before `upTo` in the text has been read. */
  pieceDone?(upTo: number): void
}

const parse = (data: RawData): Record<string, any> | null => {
  try {
    const m = JSON.parse(String(data))
    return m && typeof m === 'object' && !Array.isArray(m) ? m : null
  } catch {
    return null
  }
}

/**
 * Reads [text] with Soniox into [sink], piece by piece over one connection, as 24 kHz 16-bit mono
 * PCM as it's made, with each character's start time. Resolves once all of it is made; throws a
 * coded error (TtsProblem's codes) otherwise. [signal] stops it: Soniox is told to cancel and
 * nothing more comes. [base] places the marks: where this text starts in a longer one, and how
 * much audio comes before it.
 */
export async function sonioxSpeak(
  text: string,
  opts: { voice: unknown; rate: number },
  sink: TtsSink,
  signal?: AbortSignal,
  base: { at: number; ms: number } = { at: 0, ms: 0 },
): Promise<void> {
  const key = sonioxKey()
  if (!key) throw problem('no_key', 'Connect Soniox first.')
  if (signal?.aborted) throw problem('stopped', 'Stopped.')
  // The live model and its voices, once in six hours: a voice name Soniox doesn't have (the default,
  // before anyone opened the picker) would fail the read.
  // After a failure it isn't waited for on every read (a hanging Soniox would cost seconds each time).
  if (!listed && Date.now() - listFailedAt > 5 * 60_000) await sonioxVoices().catch(() => (listFailedAt = Date.now()))
  const ws = new WebSocket(TTS_WS(), { headers: { Authorization: `Bearer ${key}` }, handshakeTimeout: ttsLimits.connectMs, perMessageDeflate: false })
  ws.on('error', () => {})
  let current: string | null = null
  const stop = () => {
    try {
      if (current && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ stream_id: current, cancel: true }))
    } catch {}
    try {
      ws.terminate()
    } catch {}
  }
  signal?.addEventListener('abort', stop, { once: true })
  try {
    await new Promise<void>((resolve, reject) => {
      const t = setTimeout(() => reject(unreachable()), ttsLimits.connectMs + 500)
      ws.once('open', () => {
        clearTimeout(t)
        resolve()
      })
      ws.once('unexpected-response', (_req, res) => {
        clearTimeout(t)
        const p = problemFor(res.statusCode ?? 0, '', `Soniox answered ${res.statusCode}.`)
        reject(problem(p.code, p.message))
      })
      ws.once('close', () => {
        clearTimeout(t)
        reject(signal?.aborted ? problem('stopped', 'Stopped.') : unreachable())
      })
    })
    const model = ttsModel()
    const voice = sonioxVoiceFor(opts.voice)
    const speed = sonioxSpeed(opts.rate)
    let madeMs = base.ms
    const pieces = ttsPieces(ttsText(text))
    for (const [i, p] of pieces.entries()) {
      if (signal?.aborted) throw problem('stopped', 'Stopped.')
      const sid = `hn-${i}`
      current = sid
      const pieceStart = madeMs
      ws.send(JSON.stringify({ stream_id: sid, model, language: speechLanguage(p.text), voice, audio_format: 'pcm_s16le', sample_rate: TTS_RATE, speed, return_timestamps: true }))
      ws.send(JSON.stringify({ stream_id: sid, text: p.text, text_end: true }))
      await new Promise<void>((resolve, reject) => {
        let heard = false
        let cut = false
        // Characters as Soniox read them, counted across its frames (each frame has only its own).
        let chars = 0
        // Silence is what fails a piece (the first audio's wait is the longest), reset by every frame.
        let quiet: ReturnType<typeof setTimeout>
        const wait = () => {
          clearTimeout(quiet)
          quiet = setTimeout(() => fail(problem('soniox', heard ? 'Soniox stopped in the middle of reading.' : 'Soniox didn’t start reading in time.')), heard ? ttsLimits.idleMs : ttsLimits.firstAudioMs)
        }
        wait()
        const whole = setTimeout(() => fail(problem('soniox', 'Soniox took too long to read that.')), ttsLimits.baseMs + ttsLimits.perCharMs * p.text.length)
        const done = () => {
          clearTimeout(quiet)
          clearTimeout(whole)
          ws.off('message', onMessage)
          ws.off('close', onClose)
        }
        const fail = (e: Error) => {
          done()
          reject(e)
        }
        const onClose = () => fail(signal?.aborted ? problem('stopped', 'Stopped.') : problem('soniox', 'Soniox closed the connection while reading.'))
        const onMessage = (data: RawData) => {
          const m = parse(data)
          if (!m || (m.stream_id != null && m.stream_id !== sid)) return
          wait()
          if (m.error_code != null) {
            // The 2 minutes of audio a stream may make: what came is kept, but the piece was cut
            // short, so it isn't reported read whole (below).
            if (m.error_type === 'max_audio_duration_reached') {
              cut = true
              return
            }
            const pr = problemFor(Number(m.error_code), String(m.error_type ?? ''), String(m.error_message ?? ''))
            return fail(problem(pr.code, pr.message))
          }
          if (typeof m.audio === 'string' && m.audio) {
            const pcm = Buffer.from(m.audio, 'base64')
            // Whole samples only; an odd byte would shift everything after it into noise.
            const even = pcm.length % 2 ? pcm.subarray(0, pcm.length - 1) : pcm
            if (even.length) {
              heard = true
              madeMs += even.length / BYTES_PER_MS
              sink.audio(even)
            }
          }
          const ts = m.timestamps
          if (ts && Array.isArray(ts.characters) && Array.isArray(ts.character_start_times_seconds)) {
            const marks: Mark[] = []
            for (let k = 0; k < ts.characters.length; k++) {
              const s = Number(ts.character_start_times_seconds[k])
              if (Number.isFinite(s)) marks.push({ at: base.at + p.at + chars, ms: pieceStart + s * 1000 })
              chars += Math.max(1, String(ts.characters[k] ?? '').length)
            }
            if (marks.length) sink.marks(marks)
          }
          if (m.terminated === true) {
            done()
            // Cut off: the Mac's voice carries on from the word the voice got to.
            if (cut) return reject(problem('soniox', 'Soniox cut that short.'))
            sink.pieceDone?.(base.at + p.at + p.text.length)
            resolve()
          }
        }
        ws.on('message', onMessage)
        ws.on('close', onClose)
      })
      current = null
    }
  } finally {
    signal?.removeEventListener('abort', stop)
    try {
      ws.close(1000)
    } catch {}
    setTimeout(() => {
      try {
        ws.terminate()
      } catch {}
    }, 1000).unref()
  }
}

/** PCM in a WAV container, for a WebView's decodeAudioData (it takes WAV everywhere). */
export function wav(pcm: Buffer, rate = TTS_RATE): Buffer {
  const h = Buffer.alloc(44)
  h.write('RIFF', 0)
  h.writeUInt32LE(36 + pcm.length, 4)
  h.write('WAVE', 8)
  h.write('fmt ', 12)
  h.writeUInt32LE(16, 16)
  h.writeUInt16LE(1, 20)
  h.writeUInt16LE(1, 22)
  h.writeUInt32LE(rate, 24)
  h.writeUInt32LE(rate * 2, 28)
  h.writeUInt16LE(2, 32)
  h.writeUInt16LE(16, 34)
  h.write('data', 36)
  h.writeUInt32LE(pcm.length, 40)
  return Buffer.concat([h, pcm])
}

/** How loud 16-bit PCM is, 0..1 (RMS in dBFS from −60 to −10), for the Mac's wave when nothing else says. */
export function loudness(pcm: Buffer): number {
  const n = Math.floor(pcm.length / 2)
  if (!n) return 0
  let sum = 0
  for (let i = 0; i < n; i++) {
    const s = pcm.readInt16LE(i * 2) / 32768
    sum += s * s
  }
  const db = 10 * Math.log10(sum / n + 1e-12)
  return Math.min(1, Math.max(0, (db + 60) / 50))
}
