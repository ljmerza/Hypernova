// The app's GitHub releases (docs/PROTOCOL.md, "The app update"; docs/SECURITY-MODEL.md): the
// daemon asks GitHub for Hypernova's latest release at start and every 6 hours, reads the version
// note the release workflow attaches (`hypernova-vX.Y.Z.json`: versionCode, versionName, the APK's
// SHA-256), and when that release is newer than the APK it hands out, downloads its APK into
// <support>/android/, checks the SHA-256, and only then puts it where the phone's update comes from.
// An APK an admin put there (`hnctl apk`, a dev build) with a higher versionCode is never replaced.
// Never fails the daemon: offline, GitHub down or a rate limit is a line in the log, then the next
// check. Settings: `releaseCheck` (unset: on).
import { createHash } from 'node:crypto'
import { once } from 'node:events'
import { chmodSync, createWriteStream, mkdirSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { settings } from './store.ts'
import { SUPPORT, VERSION, log } from './util.ts'

export const RELEASE_REPO = 'ljmerza/Hypernova'
/** GitHub's API; a test points it at a fake one (HYPERNOVA_GITHUB_API, or the checker's `api`). */
export const GITHUB_API = process.env.HYPERNOVA_GITHUB_API ?? 'https://api.github.com'
export const CHECK_EVERY = 6 * 3600_000
/** The first look, a little after start (the servers up, the network there after a boot). */
const FIRST_CHECK = 30_000
const NOTE_MAX = 64 * 1024
const APK_MAX = 256 * 1024 * 1024

/** The release's `hypernova-<tag>.json`. */
export type ReleaseNote = { versionCode: number; versionName: string; sha256: string }
/** What the last release read was; `note` null: it carries no version note (a release from before the notes). */
type Known = { tag: string; note: ReleaseNote | null; apk: { url: string; size: number | null } | null }
export type Outcome =
  | { kind: 'off' }
  | { kind: 'limited'; until: number }
  | { kind: 'error'; message: string }
  | { kind: 'none' }
  | { kind: 'no-note'; tag: string }
  | { kind: 'not-newer'; tag: string; versionCode: number; offered: number }
  | { kind: 'mismatch'; tag: string }
  | { kind: 'published'; tag: string; versionCode: number; versionName: string }

export type CheckerOptions = {
  /** GitHub's API base URL (default [GITHUB_API]). */
  api?: string
  repo?: string
  /** Where the APK goes (default <support>/android). */
  dir?: string
  /** The versionCode of the APK handed out now (http.ts appInfo), null for none. */
  offered: () => number | null
  /** A new APK is in place: the phones hear of it (http.ts announceApp). */
  published: () => void
  /** Default: settings.releaseCheck, unset on. */
  enabled?: () => boolean
  now?: () => number
  /** Per request (default 30 s for the release and its note, 10 minutes for the APK). */
  timeoutMs?: number
}

/** The note, as the release workflow writes it; null for anything else. */
export function parseNote(v: unknown): ReleaseNote | null {
  if (!v || typeof v !== 'object') return null
  const o = v as Record<string, unknown>
  const code = o.versionCode
  const name = o.versionName
  const sha = o.sha256
  if (typeof code !== 'number' || !Number.isSafeInteger(code) || code <= 0) return null
  if (typeof name !== 'string' || name.length > 64) return null
  if (typeof sha !== 'string' || !/^[0-9a-fA-F]{64}$/.test(sha)) return null
  return { versionCode: code, versionName: name, sha256: sha.toLowerCase() }
}

/** One release checker (the daemon starts one; tests make their own, against a fake GitHub). */
export function releaseChecker(o: CheckerOptions) {
  const api = (o.api ?? GITHUB_API).replace(/\/+$/, '')
  const repo = o.repo ?? RELEASE_REPO
  const dir = o.dir ?? join(SUPPORT, 'android')
  const now = o.now ?? Date.now
  const enabled = o.enabled ?? (() => settings.releaseCheck !== false)
  // Plain http only when the API itself is (a test's fake GitHub); GitHub's own links are https.
  const plainOk = api.startsWith('http://')
  // The release last read and its ETag: a 304 means it's still the one, so no second look at its note.
  let etag: string | null = null
  let known: Known | null = null
  let limitedUntil = 0
  let running: Promise<Outcome> | null = null
  let lastLogged = ''
  let first: ReturnType<typeof setTimeout> | null = null
  let timer: ReturnType<typeof setInterval> | null = null

  const headers = (accept: string): Record<string, string> => ({ accept, 'user-agent': `hypernova-daemon/${VERSION}` })
  const timeout = (long = false) => AbortSignal.timeout(o.timeoutMs ?? (long ? 10 * 60_000 : 30_000))
  const okUrl = (u: unknown): u is string => {
    if (typeof u !== 'string') return false
    try {
      const p = new URL(u).protocol
      return p === 'https:' || (plainOk && p === 'http:')
    } catch {
      return false
    }
  }

  /** A rate limit's end from GitHub's headers (Retry-After, or X-RateLimit-Remaining 0 and its reset), else null. */
  function limitEnd(res: Response): number | null {
    const retry = Number(res.headers.get('retry-after'))
    if (Number.isFinite(retry) && retry > 0) return now() + retry * 1000
    if (res.headers.get('x-ratelimit-remaining') === '0') {
      const reset = Number(res.headers.get('x-ratelimit-reset'))
      if (Number.isFinite(reset) && reset > 0) return reset * 1000
    }
    return null
  }

  /** The release's own JSON as the APK and its note to fetch. */
  function assetsOf(body: unknown): { tag: string; note: { url: string; size: number | null } | null; apk: { url: string; size: number | null } | null } | null {
    const r = body as { tag_name?: unknown; assets?: unknown }
    if (!r || typeof r.tag_name !== 'string' || !/^[\w.+-]{1,64}$/.test(r.tag_name)) return null
    const tag = r.tag_name
    const assets = Array.isArray(r.assets) ? (r.assets as { name?: unknown; browser_download_url?: unknown; size?: unknown }[]) : []
    const find = (name: string) => {
      const a = assets.find((x) => x && x.name === name && okUrl(x.browser_download_url))
      return a ? { url: a.browser_download_url as string, size: typeof a.size === 'number' ? a.size : null } : null
    }
    return { tag, note: find(`hypernova-${tag}.json`), apk: find(`hypernova-${tag}.apk`) }
  }

  async function readNote(url: string): Promise<ReleaseNote | null> {
    const res = await fetch(url, { headers: headers('application/octet-stream'), signal: timeout() })
    if (!res.ok) {
      await res.body?.cancel()
      throw new Error(`the version note answered ${res.status}`)
    }
    if (Number(res.headers.get('content-length')) > NOTE_MAX) {
      await res.body?.cancel()
      throw new Error('the version note is too big')
    }
    const buf = Buffer.from(await res.arrayBuffer())
    if (buf.length > NOTE_MAX) throw new Error('the version note is too big')
    try {
      return parseNote(JSON.parse(buf.toString('utf8')))
    } catch {
      return null
    }
  }

  /** The APK into [tmp], its SHA-256 counted on the way; its digest, or an error. */
  async function download(url: string, tmp: string, size: number | null): Promise<string> {
    const res = await fetch(url, { headers: headers('application/octet-stream'), signal: timeout(true) })
    if (!res.ok || !res.body) {
      await res.body?.cancel()
      throw new Error(`the APK answered ${res.status}`)
    }
    if (Number(res.headers.get('content-length')) > APK_MAX) {
      await res.body.cancel()
      throw new Error('the APK is too big')
    }
    const hash = createHash('sha256')
    const out = createWriteStream(tmp, { mode: 0o600 })
    let got = 0
    try {
      for await (const chunk of res.body as unknown as AsyncIterable<Uint8Array>) {
        got += chunk.length
        if (got > APK_MAX) throw new Error('the APK is too big')
        hash.update(chunk)
        if (!out.write(chunk)) await once(out, 'drain')
      }
    } finally {
      out.end()
      await once(out, 'close').catch(() => {})
    }
    if (size != null && got !== size) throw new Error(`the APK came with ${got} bytes, not ${size}`)
    return hash.digest('hex')
  }

  /**
   * In place, as `hnctl apk` does it: no note = no offer, so the old note goes first, then the new
   * APK, then its note; each rename is atomic in the same folder, so the daemon never pairs one
   * build's version with another's file.
   */
  function publish(tmpApk: string, k: Known & { note: ReleaseNote }): void {
    const tmpNote = join(dir, `.hypernova.json.${process.pid}.new`)
    writeFileSync(tmpNote, JSON.stringify({ versionCode: k.note.versionCode, versionName: k.note.versionName, sha256: k.note.sha256, tag: k.tag, source: 'github' }) + '\n', { mode: 0o600 })
    chmodSync(tmpNote, 0o600)
    rmSync(join(dir, 'hypernova.json'), { force: true })
    renameSync(tmpApk, join(dir, 'hypernova.apk'))
    renameSync(tmpNote, join(dir, 'hypernova.json'))
  }

  async function run(): Promise<Outcome> {
    if (!enabled()) return { kind: 'off' }
    if (now() < limitedUntil) return { kind: 'limited', until: limitedUntil }
    const h = headers('application/vnd.github+json')
    h['x-github-api-version'] = '2022-11-28'
    if (etag && known) h['if-none-match'] = etag
    const res = await fetch(`${api}/repos/${repo}/releases/latest`, { headers: h, signal: timeout() })
    if (res.status === 304 && known) {
      await res.body?.cancel()
    } else if (res.status === 403 || res.status === 429) {
      await res.body?.cancel()
      const end = limitEnd(res) ?? (res.status === 429 ? now() + 3600_000 : null)
      if (end == null) return { kind: 'error', message: `GitHub answered ${res.status}` }
      limitedUntil = end
      return { kind: 'limited', until: end }
    } else if (res.status === 404) {
      // No release published (or the repository is gone): nothing to offer.
      await res.body?.cancel()
      etag = null
      known = null
      return { kind: 'none' }
    } else if (!res.ok) {
      await res.body?.cancel()
      return { kind: 'error', message: `GitHub answered ${res.status}` }
    } else {
      const found = assetsOf(await res.json())
      if (!found) return { kind: 'error', message: 'the latest release has no tag' }
      const note = found.note ? await readNote(found.note.url) : null
      known = { tag: found.tag, note, apk: found.apk }
      etag = res.headers.get('etag')
    }
    const k = known!
    if (!k.note || !k.apk) return { kind: 'no-note', tag: k.tag }
    const offered = o.offered() ?? 0
    if (k.note.versionCode <= offered) return { kind: 'not-newer', tag: k.tag, versionCode: k.note.versionCode, offered }
    mkdirSync(dir, { recursive: true, mode: 0o700 })
    const tmp = join(dir, `.hypernova.apk.${process.pid}.download`)
    try {
      const digest = await download(k.apk.url, tmp, k.apk.size)
      if (digest !== k.note.sha256) return { kind: 'mismatch', tag: k.tag }
      // An admin may have put a newer one there while this one came.
      const still = o.offered() ?? 0
      if (k.note.versionCode <= still) return { kind: 'not-newer', tag: k.tag, versionCode: k.note.versionCode, offered: still }
      publish(tmp, k as Known & { note: ReleaseNote })
    } finally {
      rmSync(tmp, { force: true })
    }
    o.published()
    return { kind: 'published', tag: k.tag, versionCode: k.note.versionCode, versionName: k.note.versionName }
  }

  /** What happened, in the log once (the same outcome every 6 hours says nothing new). */
  function note(r: Outcome): void {
    const line =
      r.kind === 'published' ? `release ${r.tag}: its APK (${r.versionName}, ${r.versionCode}) is now the one the phones are offered`
      : r.kind === 'mismatch' ? `release ${r.tag}: the APK's SHA-256 isn't the one its note gives; not offered`
      : r.kind === 'not-newer' ? `release ${r.tag} (${r.versionCode}) isn't newer than the APK handed out (${r.offered})`
      : r.kind === 'no-note' ? `release ${r.tag} has no hypernova-${r.tag}.json and .apk; nothing to offer from it`
      : r.kind === 'limited' ? `GitHub's rate limit: no release check until ${new Date(r.until).toISOString()}`
      : r.kind === 'error' ? `release check failed: ${r.message}`
      : ''
    if (!line || (line === lastLogged && r.kind !== 'published')) return
    lastLogged = line
    log(line)
  }

  /** One check now; a check under way is the one. Never throws. */
  function check(): Promise<Outcome> {
    if (running) return running
    running = run()
      .catch((e: unknown): Outcome => ({ kind: 'error', message: e instanceof Error ? (e.cause instanceof Error ? `${e.message}: ${e.cause.message}` : e.message) : String(e) }))
      .then((r) => {
        note(r)
        return r
      })
      .finally(() => {
        running = null
      })
    return running
  }

  function start(): () => void {
    first = setTimeout(() => void check(), FIRST_CHECK)
    first.unref()
    timer = setInterval(() => void check(), CHECK_EVERY)
    timer.unref()
    return stop
  }
  function stop(): void {
    if (first) clearTimeout(first)
    if (timer) clearInterval(timer)
  }
  return { check, start, stop }
}

let daemonChecker: ReturnType<typeof releaseChecker> | null = null
/** The daemon's checker (main.ts): at start and every 6 hours. */
export function startReleaseCheck(o: CheckerOptions): void {
  daemonChecker = releaseChecker(o)
  daemonChecker.start()
}
/** `releaseCheck` turned on (POST /local/settings): a look now rather than in up to 6 hours. */
export function checkReleaseNow(): void {
  if (daemonChecker) void daemonChecker.check()
}
