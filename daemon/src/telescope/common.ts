// What every Telescope source shares (docs/PROTOCOL.md, "Telescope").
import { mkdirSync, rmSync } from 'node:fs'
import { randomBytes } from 'node:crypto'
import { join } from 'node:path'
import { SHOTS_DIR } from '../shots.ts'

/** Where a picture can come from, in the order the decision lists them. */
export const SOURCES = ['web', 'emulator', 'simulator', 'terminal', 'image', 'window'] as const
export type Source = (typeof SOURCES)[number]

/** An answer for the phone: its status, a code a client can act on, and plain words. */
export const fail = (status: number, code: string, message: string, extra?: Record<string, unknown>) =>
  Object.assign(new Error(message), { status, code, ...(extra ? { extra } : {}) })

/** A picture as a source took it, before it becomes a token link. */
export type Capture = {
  png: Buffer
  /** What it shows, for a title: the page's, the phone's model, the file's or the app's name. */
  title: string
  /** Where it came from, in a few characters: "localhost:5173/settings", "Pixel 8", "chart.png". */
  label: string
  pageUrl?: string | null
  path?: string
  page?: number
  pages?: number
  device?: string
}

/**
 * A scratch file for a program to write its picture into (simctl, osascript): in a folder of the
 * daemon's own (0700), under a name nobody can guess, removed by the caller (and by reapShots,
 * should the daemon stop half way).
 */
export function scratchFile(ext = 'png'): { path: string; remove: () => void } {
  const dir = join(SHOTS_DIR, 'tmp')
  mkdirSync(dir, { recursive: true, mode: 0o700 })
  const path = join(dir, `${randomBytes(12).toString('hex')}.${ext}`)
  return { path, remove: () => rmSync(path, { force: true }) }
}
