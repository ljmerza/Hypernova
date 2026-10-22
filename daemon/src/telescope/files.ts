// Telescope's image source: the last picture, chart or PDF page the session made, drawn on the Mac
// (draw.ts). Which file is the transcript's word (evidence.ts); whether it may be shown is decided
// here, on the file itself: only inside the session's folder (by real path, so a link can't lead
// out of it), never a file that holds credentials (secretfiles.ts), never Hypernova's own state,
// and only a file whose first bytes say it is a picture or a PDF, whatever its name says.
import { closeSync, openSync, readSync, realpathSync, statSync } from 'node:fs'
import { basename, isAbsolute, join, resolve, sep } from 'node:path'
import { HOME, SUPPORT } from '../util.ts'
import { isSecretFile } from '../secretfiles.ts'
import { drawFile } from './draw.ts'
import { fail, type Capture } from './common.ts'

/** The names a picture goes by. */
export const PICTURE_NAME = /\.(?:png|jpe?g|gif|webp|svg|pdf|tiff?|bmp|heic|heif)$/i
const MAX_BYTES = 100 * 1024 * 1024

export type PictureType = 'png' | 'jpeg' | 'gif' | 'webp' | 'pdf' | 'svg' | 'tiff' | 'bmp' | 'heic'

/** What a file's first bytes say it is, or null when it isn't a picture or a PDF. */
export function sniff(head: Buffer): PictureType | null {
  const at = (i: number, s: string) => head.toString('latin1', i, i + s.length) === s
  if (head.length >= 4 && head[0] === 0x89 && at(1, 'PNG')) return 'png'
  if (head.length >= 3 && head[0] === 0xff && head[1] === 0xd8 && head[2] === 0xff) return 'jpeg'
  if (at(0, 'GIF8')) return 'gif'
  if (at(0, 'RIFF') && at(8, 'WEBP')) return 'webp'
  if (at(0, 'II*\0') || at(0, 'MM\0*')) return 'tiff'
  if (at(0, 'BM') && head.length > 26) return 'bmp'
  if (at(4, 'ftyp') && /^(?:heic|heix|heim|heis|hevc|mif1|msf1)$/.test(head.toString('latin1', 8, 12))) return 'heic'
  const text = head.toString('latin1')
  const pdf = text.indexOf('%PDF-')
  if (pdf >= 0 && pdf < 1024) return 'pdf'
  // SVG is text: markup that opens an <svg> element early on (after an XML declaration, comments).
  if (/^﻿?\s*</.test(head.toString('utf8')) && /<svg[\s>]/i.test(text)) return 'svg'
  return null
}

let supportReal: string | null = null
const support = () => {
  if (supportReal === null) {
    try {
      supportReal = realpathSync(SUPPORT)
    } catch {
      supportReal = SUPPORT
    }
  }
  return supportReal
}

/**
 * A picture of the session's, as its real path, or null: inside the session's folder (never a
 * session at the root of the disk), not a credential file, not in Hypernova's own folder (its
 * screenshots, the phone's attachments), a regular file of at most 100 MB, named and shaped like a
 * picture or a PDF. `path` may be relative to the folder, or start with `~/`.
 */
export function sessionPicture(path: string, cwd: string): { real: string; type: PictureType; mtimeMs: number } | null {
  if (!path || path.includes('\0')) return null
  let root: string
  try {
    root = realpathSync(cwd)
  } catch {
    return null
  }
  if (root === '/') return null
  const full = path.startsWith('~/') ? join(HOME, path.slice(2)) : isAbsolute(path) ? path : resolve(cwd, path)
  if (!PICTURE_NAME.test(full)) return null
  let real: string
  try {
    real = realpathSync(full)
  } catch {
    return null
  }
  if (!real.startsWith(root + sep) || !PICTURE_NAME.test(real)) return null
  if (isSecretFile(full) || isSecretFile(real) || real.startsWith(support() + sep)) return null
  let st
  try {
    st = statSync(real)
  } catch {
    return null
  }
  if (!st.isFile() || st.size === 0 || st.size > MAX_BYTES) return null
  const head = Buffer.alloc(Math.min(4096, st.size))
  try {
    const fd = openSync(real, 'r')
    try {
      readSync(fd, head, 0, head.length, 0)
    } finally {
      closeSync(fd)
    }
  } catch {
    return null
  }
  const type = sniff(head)
  return type ? { real, type, mtimeMs: st.mtimeMs } : null
}

/** A picture the transcript points at: `wrote` when the session wrote the file itself (Write, Edit). */
export type PictureClue = { at: number; path: string; page: number | null; wrote: boolean }

/** How long before a session began a file may have been written and still count as its (clocks, a resumed run). */
const SLACK_MS = 60_000

/**
 * The newest picture the session made: one it wrote itself, or one a command it ran made (a chart a
 * script saved, a screenshot it took) while the session ran. A command that merely names an older
 * file (`ls`, a grep through the assets) doesn't make it the session's.
 */
export function pickPicture(clues: PictureClue[], cwd: string, since: number): { real: string; type: PictureType; page: number | null } | null {
  // A session in the home folder (or above it) holds every picture the owner has: one a command
  // merely named (an `ls ~/Pictures` during the session, a screenshot saved meanwhile) isn't its.
  // There, only a picture it wrote itself counts.
  const broad = atOrAboveHome(cwd)
  for (const c of [...clues].sort((a, b) => b.at - a.at)) {
    if (broad && !c.wrote) continue
    const p = sessionPicture(c.path, cwd)
    if (!p) continue
    if (!c.wrote && p.mtimeMs < since - SLACK_MS) continue
    return { real: p.real, type: p.type, page: c.page }
  }
  return null
}

function atOrAboveHome(cwd: string): boolean {
  try {
    const root = realpathSync(cwd), home = realpathSync(HOME)
    return root === home || home.startsWith(root.endsWith(sep) ? root : root + sep)
  } catch {
    return true
  }
}

/** That picture as a PNG; a PDF at the page asked for, else the page the transcript named, else its first. */
export async function capturePicture(pick: { real: string; type: PictureType; page: number | null }, page?: number | null): Promise<Capture> {
  const pdf = pick.type === 'pdf'
  const d = await drawFile(pick.real, pdf ? 'pdf' : 'image', (page ?? pick.page ?? 1) || 1)
  const name = basename(pick.real)
  return {
    png: d.png,
    title: name,
    label: pdf ? `${name} · page ${d.page} of ${d.pages}` : name,
    path: pick.real,
    ...(pdf ? { page: d.page, pages: d.pages } : {}),
  }
}

export const noPicture = () => fail(404, 'no_picture', 'This session hasn’t made a picture, a chart or a PDF in its folder.')
