// Telescope (docs/PROTOCOL.md, "Telescope"; DECISIONS, "Telescope"): what the session in front of
// you is building, as a picture for the glasses, from wherever it shows: its web page (headless
// Chrome, as "show me" did), the Android emulator or a phone (adb), the iOS Simulator (simctl), its
// live terminal (drawn), the last picture or PDF page it made, or a window of the Mac app it
// builds (through the Mac app). Picked automatically, newest first among the sources the
// transcript points at, or named. Every picture becomes a token link served like a screenshot
// (`GET /v1/shots/<token>.png`, 12 hours), and a picture that hasn't changed since the last one
// keeps that one's link, so a client watching it reloads nothing.
import { createHash } from 'node:crypto'
import { readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import type { Ev } from '../normalize.ts'
import { SHOTS_DIR, dimension, findPage, pngSize, reapShots, render, saveShot, shotPath } from '../shots.ts'
import { captureAndroid } from './android.ts'
import { captureSimulator } from './simulator.ts'
import { captureTerminal } from './terminal.ts'
import { capturePicture, noPicture, pickPicture } from './files.ts'
import { captureWindow } from './window.ts'
import { clues, plan, usedDevice, type Clue } from './evidence.ts'
import { SOURCES, fail, scratchFile, type Capture, type Source } from './common.ts'
import { runFile } from './run.ts'
import type { DeviceKind } from './android.ts'
import { ON_MAC } from '../platform.ts'
import { shrinkPng } from './png.ts'

export type TelescopeShot = {
  /** "/v1/shots/<24 hex>.png", relative to the base URL. */
  url: string
  width: number
  height: number
  source: Source
  /** What it shows: the page's title, "Android emulator · Pixel 8", "chart.png", "Hypernova · Settings". */
  title: string
  /** Where it came from, short: "localhost:5173/settings", "Pixel 8", "report.pdf · page 2 of 9", "terminal". */
  label: string
  pageUrl: string | null
  path?: string
  page?: number
  pages?: number
  device?: string
  /** The picture's fingerprint: the same means nothing changed. */
  digest: string
  /** Taken (or found unchanged) at, epoch ms. */
  at: number
  /** The telescope chose the source itself. */
  auto: boolean
  /**
   * Picking by itself, it passed over a source the session points at for a reason the owner can
   * fix in a word or a click (Screen Recording, the Mac app closed, several devices): what to do.
   */
  skipped?: { source: Source; code: string; message: string }
}

type Session = { id: string; cwd: string; title: string; projectName: string; createdAt: number; term: { id: string } | null }

/** A web page as wide as two lens pictures and as high: the whole of it fills the glasses, a quarter is pixel for pixel. */
const WEB_W = 1152
const WEB_H = 512

/** Sessions being captured: one at a time each (a watching client asks again only after an answer). */
const busy = new Set<string>()
/** Each session's last picture, so an unchanged one keeps its link. */
const last = new Map<string, { digest: string; token: string; shot: TelescopeShot }>()
/** Each session's newest pictures taken while watching: only the newest two are kept on disk. */
const watched = new Map<string, string[]>()
const WATCH_KEEP = 2

/**
 * The longer side a picture is kept at. The lens draws a zoomed quarter at 576 × 256 and the phone's
 * card fits its screen: a phone's screen at its own 1260 × 2800 (2.5 MB of PNG) would cost tens of
 * megabytes a watched minute over cellular, for nothing anyone sees.
 */
export const MAX_SIDE = 1600

/** What a source can take for this session; `ask`: the owner named the source himself. */
async function capture(source: Source, s: Session, events: Ev[], list: Clue[], body: any, ask: boolean): Promise<Capture> {
  switch (source) {
    case 'web': {
      const { target, pageUrl, path } = await findPage(s, events, body?.url)
      const r = await render(target, dimension(body?.width, WEB_W), dimension(body?.height, WEB_H), !!path)
      const file = join(SHOTS_DIR, `${r.token}.png`)
      const png = readFileSync(file)
      rmSync(file, { force: true })
      const label = pageUrl ? pageUrl.replace(/^https?:\/\//, '').replace(/\/$/, '') : (path ?? '').split('/').pop() ?? ''
      return { png, title: r.title || s.title, label, pageUrl, ...(path ? { path } : {}) }
    }
    case 'emulator':
      return captureAndroid({ asked: body?.device == null || body.device === '' ? null : String(body.device), used: usedDevice(list), prefer: deviceKind(body?.prefer) })
    case 'simulator':
      return captureSimulator()
    case 'terminal':
      return captureTerminal(s)
    case 'image': {
      const pick = pickPicture(list.flatMap((c) => (c.source === 'image' ? [c] : [])), s.cwd, s.createdAt)
      if (!pick) throw noPicture()
      const page = Number(body?.page)
      return capturePicture(pick, Number.isInteger(page) && page > 0 && page < 100_000 ? page : null)
    }
    case 'window':
      return captureWindow(s.cwd, ask)
  }
}

const deviceKind = (v: unknown): DeviceKind | null => (v === 'phone' || v === 'emulator' ? v : null)

/** Not there for this session (or not on this Mac): the next source may be. */
const MISSES = new Set(['nothing_to_show', 'no_emulator', 'no_adb', 'no_device', 'which_device', 'no_simulator', 'simulator_off', 'no_terminal', 'no_picture', 'no_app', 'no_window', 'no_mac_app', 'screen_recording', 'no_browser', 'unavailable', 'unsupported_picture'])
/** Of those, the ones the owner can fix in a word or a click, and the word for the lens. */
const FIXABLE: Record<string, string> = {
  screen_recording: 'Say “telescope window” to allow Screen Recording.',
  no_mac_app: 'Open Hypernova on the Mac to see the app’s window.',
  which_device: 'Several Android devices: say “telescope phone” or “telescope emulator”.',
}

/**
 * POST /v1/sessions/{id}/telescope `{source?, url?, device?, page?, width?, height?}`: a picture
 * of what the session builds, from the source named, or picked: each source its transcript points
 * at, newest first, then a page served from its folder, then its terminal.
 */
export async function telescope(s: Session, events: Ev[], body: any): Promise<TelescopeShot> {
  const asked = body?.source == null || body.source === '' || body.source === 'auto' ? null : String(body.source)
  if (asked !== null && !(SOURCES as readonly string[]).includes(asked)) throw fail(400, 'bad_source', 'Telescope shows web, emulator, simulator, terminal, image or window.')
  if (busy.has(s.id)) throw fail(409, 'busy', 'The telescope is already pointed at this session.')
  busy.add(s.id)
  try {
    const list = clues(events, s.cwd)
    const watch = body?.watch === true
    if (asked) return await keep(s, asked as Source, await capture(asked as Source, s, events, list, body, true), false, watch)
    // A named address is a web page, whatever else the session did.
    const order: Source[] = typeof body?.url === 'string' && body.url.trim() ? ['web'] : plan(list)
    let first: any = null
    let skipped: TelescopeShot['skipped']
    for (const source of order) {
      // Windows only once the owner has allowed Screen Recording: picking by itself, the telescope
      // never puts that question on the Mac.
      try {
        const shot = await keep(s, source, await capture(source, s, events, list, body, false), true, watch)
        return skipped ? { ...shot, skipped } : shot
      } catch (e: any) {
        if (!MISSES.has(e?.code)) throw e
        const pointed = list.some((c) => c.source === source)
        // The newest source the transcript points at says best why there's nothing (no emulator
        // running, say); without any, the plain "nothing to show".
        if (!first && pointed) first = e
        // A source the session points at, passed over for something the owner can fix: whatever is
        // shown instead says so, or he'd never learn why "telescope" shows his terminal.
        if (!skipped && pointed && FIXABLE[e.code]) skipped = { source, code: e.code, message: FIXABLE[e.code]! }
      }
    }
    throw first ?? fail(404, 'nothing_to_show', 'This session hasn’t started a page, an app or a picture to show.')
  } finally {
    busy.delete(s.id)
  }
}

/** The capture as a token link: the last one's when nothing changed. `watch`: a watching client's look. */
async function keep(s: Session, source: Source, c: Capture, auto: boolean, watch = false): Promise<TelescopeShot> {
  if (!pngSize(c.png)) throw fail(502, 'capture_failed', 'That gave no picture.')
  // The fingerprint of what the source gave, before any shrinking: the same picture is found
  // unchanged without being shrunk again.
  const digest = createHash('sha256').update(c.png).digest('hex').slice(0, 24)
  const prev = last.get(s.id)
  const now = Date.now()
  // The same pixels from the same place (two blank pages of a PDF are two pictures).
  if (prev && prev.digest === digest && prev.shot.source === source && prev.shot.label === c.label && shotPath(prev.token)) {
    const shot = { ...prev.shot, auto, at: now }
    prev.shot = shot
    return shot
  }
  const png = await shrink(c.png)
  const size = pngSize(png)!
  const token = saveShot(png)
  reapShots()
  // While watching, each new frame replaces the one before on the lens and the phone: only the
  // newest two stay (the one just sent may still be loading), not a minute's worth for 24 hours.
  // The picture the owner asked for before watching stays like any other.
  if (watch) {
    const frames = [...(watched.get(s.id) ?? []), token]
    while (frames.length > WATCH_KEEP) {
      const p = shotPath(frames.shift()!)
      if (p) rmSync(p, { force: true })
    }
    watched.set(s.id, frames)
  } else watched.delete(s.id)
  const shot: TelescopeShot = {
    url: `/v1/shots/${token}.png`,
    width: size.width,
    height: size.height,
    source,
    title: c.title,
    label: c.label,
    pageUrl: c.pageUrl ?? null,
    ...(c.path ? { path: c.path } : {}),
    ...(c.page ? { page: c.page, pages: c.pages } : {}),
    ...(c.device ? { device: c.device } : {}),
    digest,
    at: now,
    auto,
  }
  last.set(s.id, { digest, token, shot })
  return shot
}

/**
 * A picture longer than MAX_SIDE, shrunk to it on the Mac (`sips`, by full path with fixed
 * arguments, through scratch files of the daemon's own); as it was when it fits, or when sips
 * fails (a larger picture beats none).
 */
export async function shrink(png: Buffer): Promise<Buffer> {
  const size = pngSize(png)
  if (!size || Math.max(size.width, size.height) <= MAX_SIDE) return png
  // No sips off the Mac: shrunk in TypeScript (png.ts), or kept as it came when it can't be read.
  if (!ON_MAC) return shrinkPng(png, MAX_SIDE) ?? png
  const src = scratchFile(), out = scratchFile()
  try {
    writeFileSync(src.path, png, { mode: 0o600 })
    const r = await runFile('/usr/bin/sips', ['-s', 'format', 'png', '-Z', String(MAX_SIDE), src.path, '--out', out.path], { timeout: 15_000, max: 1024 * 1024 })
    if (r.code !== 0) return png
    const small = readFileSync(out.path)
    const got = pngSize(small)
    return got && Math.max(got.width, got.height) <= MAX_SIDE ? small : png
  } catch {
    return png
  } finally {
    src.remove()
    out.remove()
  }
}
