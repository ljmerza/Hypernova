// The Mac's voices (docs/PROTOCOL.md, "Read aloud"): the Mac renders a reply with `say` and the
// phone plays the bytes. Also the speech companion's address, so the phone pairs once, not twice.
import { execFile, type ChildProcess } from 'node:child_process'
import { createHash, randomBytes } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { HOME, SUPPORT, readJson } from './util.ts'
import { serveConfig, tailscaleStatus } from './services.ts'
import { LINUX_REASONS, has } from './platform.ts'

const SAY = '/usr/bin/say'
/** Why there's no voice: a Mac without `say`, or a Linux server, which never has the Mac's voice. */
const noVoice = () => (has('macVoice') ? 'This Mac can’t read aloud.' : LINUX_REASONS.macVoice)
const TMP = join(SUPPORT, 'tmp')
/** The most one request may carry. */
export const MAX_SPEECH = 8000
/** `say -r` takes words per minute; this is its usual pace, rate 1. */
const WPM = 185

const fail = (status: number, code: string, message: string) => Object.assign(new Error(message), { status, code })

export type Voice = { id: string; name: string; lang: string }

/**
 * The voices in `say -v '?'`: `Samantha            en_US    # Hello! My name is Samantha.`. The
 * language is the last word before the `#`, and the name is everything before it: long names
 * have a single space there (`Eddy (English (US)) en_US    # …`).
 */
export function parseVoices(out: string): Voice[] {
  const voices: Voice[] = []
  for (const line of out.split('\n')) {
    const m = /^(.*?\S)\s+([a-z]{2,3}(?:[_-][A-Za-z0-9]+)*)\s+#/.exec(line)
    if (m) voices.push({ id: m[1]!, name: m[1]!, lang: m[2]!.replace(/_/g, '-') })
  }
  return voices
}

/** The list for the life of the process (voices only change when the owner installs one). */
let voiceList: Promise<Voice[]> | null = null
export function listVoices(): Promise<Voice[]> {
  if (!existsSync(SAY)) return Promise.resolve([])
  voiceList ??= new Promise<Voice[]>((resolve) =>
    execFile(SAY, ['-v', '?'], { timeout: 10_000, maxBuffer: 4 * 1024 * 1024 }, (e, out) => {
      const voices = e ? [] : parseVoices(String(out))
      // A failed listing isn't kept, so the next request tries again.
      if (!voices.length) voiceList = null
      resolve(voices)
    }),
  )
  return voiceList
}

/** The clip each paired device is waiting for: a newer request replaces it. */
type Job = { child: ChildProcess; replaced: boolean }
const rendering = new Map<string, Job>()

/**
 * Recent clips, in memory only (never on disk, and not by their text but its hash), so reading
 * the same reply again starts at once. At most 6 and 32 MB, each dropped 10 minutes after it was
 * made by a timer of its own, so the owner's replies don't sit in memory until the next request.
 */
const recent = new Map<string, { bytes: Buffer; timer: ReturnType<typeof setTimeout> }>()
export const RECENT_MS = 10 * 60_000
const RECENT_MAX = 6
const RECENT_BYTES = 32 * 1024 * 1024

/** A clip kept by keepClip, by the hash of its voice, rate and text. */
export function recentClip(key: string): Buffer | undefined {
  return recent.get(key)?.bytes
}

/** Keep a clip; the oldest go first past the count or the size. */
export function keepClip(key: string, bytes: Buffer): void {
  const before = recent.get(key)
  if (before) {
    clearTimeout(before.timer)
    recent.delete(key)
  }
  // A clip replaced or pushed out stops its timer, so this one only ever drops its own. Unref'd:
  // it never keeps the process alive by itself.
  const timer = setTimeout(() => recent.delete(key), RECENT_MS)
  timer.unref()
  recent.set(key, { bytes, timer })
  let total = 0
  for (const c of recent.values()) total += c.bytes.length
  for (const [k, c] of recent) {
    if (recent.size <= RECENT_MAX && total <= RECENT_BYTES) break
    clearTimeout(c.timer)
    recent.delete(k)
    total -= c.bytes.length
  }
}

/**
 * One clip as AAC in an M4A container (`audio/mp4`), which Web Audio decodes in every WebView.
 * The text goes to `say` on its standard input, not its arguments, so it never shows in the
 * process list; nothing logs or keeps it. [who] is the device asking: one clip at a time each.
 * [gone] fires when the phone stops waiting, which stops the rendering too.
 */
export async function speak(text: string, voice: unknown, rate: unknown, who: string, gone?: AbortSignal): Promise<Buffer> {
  if (!text.trim()) throw fail(400, 'empty', 'There’s nothing to read.')
  if (text.length > MAX_SPEECH) throw fail(413, 'too_long', `At most ${MAX_SPEECH} characters at a time.`)
  if (!existsSync(SAY)) throw fail(501, 'no_voice', noVoice())
  // A voice from the list, else the system voice (say's own default).
  const id = typeof voice === 'string' && voice ? ((await listVoices()).find((v) => v.id === voice)?.id ?? null) : null
  const r = Number(rate)
  const wpm = Math.round(WPM * Math.min(2, Math.max(0.5, Number.isFinite(r) && r > 0 ? r : 1)))
  // `[[` opens one of say's embedded commands (`[[rate 400]]`, `[[volm 0]]`); in a reply it's only text.
  const spoken = text.replace(/\[\[/g, '[ [')

  const before = rendering.get(who)
  if (before) {
    before.replaced = true
    before.child.kill()
  }
  const key = createHash('sha256').update(`${id ?? ''}\n${wpm}\n${spoken}`).digest('hex')
  const hit = recentClip(key)
  if (hit) return hit

  mkdirSync(TMP, { recursive: true, mode: 0o700 })
  const file = join(TMP, `say-${randomBytes(8).toString('hex')}.m4a`)
  const failed = () => fail(502, 'speech_failed', 'The Mac couldn’t read that aloud.')
  try {
    await new Promise<void>((resolve, reject) => {
      const args = [...(id ? ['-v', id] : []), '-r', String(wpm), '-o', file, '--file-format=m4af', '--data-format=aac']
      const job: Job = { child: null as unknown as ChildProcess, replaced: false }
      job.child = execFile(SAY, args, { timeout: 40_000 }, (e) => {
        if (rendering.get(who) === job) rendering.delete(who)
        if (!e) resolve()
        else reject(job.replaced ? fail(409, 'replaced', 'A newer request to read aloud replaced this one.') : failed())
      })
      rendering.set(who, job)
      job.child.stdin?.on('error', () => {})
      job.child.stdin?.end(spoken)
      if (gone?.aborted) job.child.kill()
      else gone?.addEventListener('abort', () => job.child.kill(), { once: true })
    })
    let bytes: Buffer
    try {
      bytes = readFileSync(file)
    } catch {
      throw failed()
    }
    if (!bytes.length) throw failed()
    keepClip(key, bytes)
    return bytes
  } finally {
    rmSync(file, { force: true })
  }
}

/**
 * The Mac's voice as 24 kHz 16-bit mono PCM, the shape the phone app plays (readaloud.ts): `say`
 * renders a WAV file, read and deleted at once. Same rules as speak(): the text goes in on stdin,
 * nothing logs it, 40 s at most; [signal] stops it.
 */
export async function sayPcm(text: string, voice: unknown, rate: unknown, signal?: AbortSignal): Promise<Buffer> {
  if (!existsSync(SAY)) throw fail(501, 'no_voice', noVoice())
  const id = typeof voice === 'string' && voice ? ((await listVoices()).find((v) => v.id === voice)?.id ?? null) : null
  const r = Number(rate)
  const wpm = Math.round(WPM * Math.min(2, Math.max(0.5, Number.isFinite(r) && r > 0 ? r : 1)))
  mkdirSync(TMP, { recursive: true, mode: 0o700 })
  const file = join(TMP, `say-${randomBytes(8).toString('hex')}.wav`)
  try {
    await new Promise<void>((resolve, reject) => {
      const args = [...(id ? ['-v', id] : []), '-r', String(wpm), '-o', file, '--file-format=WAVE', '--data-format=LEI16@24000']
      const child = execFile(SAY, args, { timeout: 40_000 }, (e) => (e ? reject(fail(502, 'speech_failed', 'The Mac couldn’t read that aloud.')) : resolve()))
      child.stdin?.on('error', () => {})
      child.stdin?.end(text.replace(/\[\[/g, '[ ['))
      if (signal?.aborted) child.kill()
      else signal?.addEventListener('abort', () => child.kill(), { once: true })
    })
    const bytes = readFileSync(file)
    // The samples only: `say` puts a chunk of its own (FLLR) before them.
    const at = dataChunk(bytes)
    if (!at) throw fail(502, 'speech_failed', 'The Mac couldn’t read that aloud.')
    return at
  } finally {
    rmSync(file, { force: true })
  }
}

/** A WAV file's samples (its `data` chunk), whole samples only. */
function dataChunk(file: Buffer): Buffer | null {
  if (file.length < 12 || file.toString('ascii', 0, 4) !== 'RIFF') return null
  let at = 12
  while (at + 8 <= file.length) {
    const size = file.readUInt32LE(at + 4)
    if (file.toString('ascii', at, at + 4) === 'data') {
      const pcm = file.subarray(at + 8, Math.min(file.length, at + 8 + size))
      return pcm.length % 2 ? pcm.subarray(0, pcm.length - 1) : pcm
    }
    at += 8 + size + (size % 2)
  }
  return null
}

/** Clips left behind by a daemon that stopped while rendering. */
export function cleanSpeechTemp(): void {
  try {
    for (const f of readdirSync(TMP)) if (/^say-[0-9a-f]+\.(m4a|wav)$/.test(f)) rmSync(join(TMP, f), { force: true })
  } catch {}
}

// ── The speech companion's address ───────────────────────────────────────
// Hyperscribe Companion recognises speech on this Mac; the phone streams the glasses' microphone
// straight to it over Tailscale. Its settings name its port and pairing token.
const COMPANION_SETTINGS = () => join(HOME, 'Library/Application Support/Hyperscribe Companion/settings.json')
export const COMPANION_PORT = 7443

/** The companion's port from its settings (7443 unless the owner changed it). */
export function companionPort(): number {
  const s = readJson<any>(COMPANION_SETTINGS(), null)
  return Number.isInteger(s?.port) && s.port >= 1024 && s.port <= 65535 ? s.port : COMPANION_PORT
}

/**
 * The companion's pairing address, from its settings, this Mac's Tailscale name and what
 * `tailscale serve` routes; null when any of them is missing. Only its tailnet https route, never
 * a LAN address: plain ws:// would expose the token, the audio and the words.
 */
export function companionAddress(settings: any, dnsName: string | null, serve: Record<string, any> | null): string | null {
  if (!settings || typeof settings !== 'object' || settings.tailscale === false) return null
  const token = typeof settings.token === 'string' ? settings.token : ''
  if (!/^[\w-]{1,128}$/.test(token) || !dnsName || !serve) return null
  const port = Number.isInteger(settings.port) && settings.port >= 1024 && settings.port <= 65535 ? settings.port : COMPANION_PORT
  // `tailscale serve --https=<port>` shows as an https TCP port and a web route for "<name>:<port>".
  const web = serve.Web?.[`${dnsName}:${port}`]
  if (serve.TCP?.[String(port)]?.HTTPS !== true || !web || typeof web.Handlers !== 'object') return null
  return `wss://${dnsName}:${port}/ws?token=${encodeURIComponent(token)}`
}

/** Read on every request: "New code" in the companion changes its token. */
export async function companionUrl(): Promise<string | null> {
  const settings = readJson<any>(COMPANION_SETTINGS(), null)
  if (!settings?.token) return null
  const ts = await tailscaleStatus()
  return companionAddress(settings, ts.dnsName, ts.dnsName ? await serveConfig() : null)
}
