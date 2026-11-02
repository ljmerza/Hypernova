// The server's debug log (owner, 2026-11-02; docs/LINUX.md, "The debug log"; docs/SECURITY-MODEL.md, "The
// server's debug log"): `debug.log` beside daemon.log, off by default, off by itself within 24 hours. While
// it's on, the main paths say what they do through [dbg]; while it's off, [dbg] returns on its first line,
// having built no string. The everyday log() doesn't change.
//
// What may be written is debug-scrub.ts's to say, at the write, whatever a call passes. Lines stay on this
// computer: no route sends them to a phone, and nothing here goes anywhere else.
import { appendFileSync } from 'node:fs'
import { appendFile, chmod, mkdir, rename, rm, stat } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { adminToken, hookSecret, saveSettings, settings } from './store.ts'
import { LOGS, log } from './util.ts'
import { binOf, errorParts, flagsOf, formatLine, knownSecret, subOf, type Fields } from './debug-scrub.ts'

export const DEBUG_HOURS = 24
const HOUR = 3600_000
/** Each file, and how many: about 20 MB in all, the oldest going first. */
export const DEBUG_FILE_BYTES = 5 * 1024 * 1024
export const DEBUG_FILES = 4
/** Written out this often, or sooner past [BATCH]. */
const FLUSH_MS = 250
const BATCH = 64 * 1024
/** What may wait for a slow disk; lines past it are counted and dropped, never waited for. */
const QUEUE_MAX = 2 * 1024 * 1024

export const debugFile = () => join(LOGS, 'debug.log')

export type Tag = 'debug' | 'http' | 'ws' | 'tls' | 'pair' | 'session' | 'hook' | 'proc' | 'automation' | 'usage' | 'release' | 'update' | 'mcp' | 'plugin' | 'error'

// ── The file ─────────────────────────────────────────────────────────────

/**
 * Appends without holding the event loop: lines queue here, and go out in batches on the thread pool, each
 * waiting for the last. Rolls `<file>` to `<file>.1`, and so on, before a batch would pass [fileBytes]. Mode
 * 0600 in a 0700 folder. No file is held open, so one deleted by hand starts again with the next batch.
 */
export class DebugWriter {
  private readonly file: string
  private readonly fileBytes: number
  private readonly files: number
  private queue: string[] = []
  private queued = 0
  private dropped = 0
  private modesSet = false
  private timer: NodeJS.Timeout | null = null
  private flushing: Promise<void> | null = null

  constructor(file: string, fileBytes = DEBUG_FILE_BYTES, files = DEBUG_FILES) {
    this.file = file
    this.fileBytes = fileBytes
    this.files = files
  }

  write(line: string): void {
    if (this.queued > QUEUE_MAX) {
      this.dropped++
      return
    }
    this.queue.push(line)
    this.queued += line.length + 1
    this.schedule(this.queued >= BATCH ? 0 : FLUSH_MS)
  }

  private schedule(ms: number): void {
    if (this.flushing) return
    if (this.timer) {
      if (ms > 0) return
      clearTimeout(this.timer)
    }
    this.timer = setTimeout(() => {
      this.timer = null
      void this.flush()
    }, ms)
    this.timer.unref()
  }

  /** Everything queued, written. */
  flush(): Promise<void> {
    if (this.flushing) return this.flushing
    this.flushing = this.drain().finally(() => {
      this.flushing = null
      if (this.queue.length) this.schedule(0)
    })
    return this.flushing
  }

  private async drain(): Promise<void> {
    while (this.queue.length) {
      const said = this.dropped ? `${new Date().toISOString()} debug dropped lines=${this.dropped}\n` : ''
      this.dropped = 0
      // One batch at a time, so a roll happens between batches and no file passes its size.
      let n = 0
      let bytes = 0
      while (n < this.queue.length && bytes < BATCH) bytes += this.queue[n++]!.length + 1
      const data = Buffer.from(said + this.queue.slice(0, n).join('\n') + '\n')
      this.queue = this.queue.slice(n)
      this.queued -= bytes
      try {
        await this.append(data)
      } catch {
        // No disk space, a folder gone: those lines are lost, and it tries again with the next.
      }
    }
  }

  private async append(data: Buffer): Promise<void> {
    if (!this.modesSet) {
      const dir = dirname(this.file)
      await mkdir(dir, { recursive: true, mode: 0o700 })
      await chmod(dir, 0o700)
    }
    const size = await stat(this.file).then((s) => s.size, () => 0)
    if (size > 0 && size + data.length > this.fileBytes) await this.roll()
    await appendFile(this.file, data, { mode: 0o600 })
    if (!this.modesSet) await chmod(this.file, 0o600)
    this.modesSet = true
  }

  private async roll(): Promise<void> {
    await rm(`${this.file}.${this.files - 1}`, { force: true })
    for (let i = this.files - 2; i >= 1; i--) await rename(`${this.file}.${i}`, `${this.file}.${i + 1}`).catch(() => {})
    await rename(this.file, `${this.file}.1`)
  }

  /** The daemon is ending: what's queued goes out now, in one blocking write. */
  flushSync(): void {
    if (!this.queue.length) return
    const text = this.queue.join('\n') + '\n'
    this.queue = []
    this.queued = 0
    try {
      appendFileSync(this.file, text, { mode: 0o600 })
    } catch {}
  }
}

// ── On and off ───────────────────────────────────────────────────────────

const writer = new DebugWriter(debugFile())
/** When it ends (epoch ms); 0 is off, the one thing a line looks at. */
let until = 0
let watch: NodeJS.Timeout | null = null
let changed: (() => void) | null = null

/** Every client hears when it goes off by itself, as for a change from anywhere else. */
export function onDebugChange(f: () => void): void {
  changed = f
}

export type DebugView = { on: boolean; until: number | null }
export const debugView = (): DebugView => (debugOn() ? { on: true, until } : { on: false, until: null })

/** Whether a line would be written: for building what's only worth building then. */
export function debugOn(): boolean {
  if (until === 0) return false
  if (Date.now() < until) return true
  expire()
  return false
}

function expire(): void {
  dbgEnd('expired')
  settle(0)
  log('debug log off: its time ran out')
  changed?.()
}

function dbgEnd(why: string): void {
  writer.write(formatLine(Date.now(), 'debug', 'off', { reason: why }))
}

/** A look once a minute while it's on, so it's off on time with nothing being logged, and after a sleep. */
function arm(): void {
  if (watch) clearInterval(watch)
  watch = setInterval(() => debugOn(), 60_000)
  watch.unref()
}

function settle(to: number): void {
  until = to
  if (to) {
    settings.debugUntil = to
    knownSecret(adminToken)
    knownSecret(hookSecret)
    arm()
  } else {
    delete settings.debugUntil
    if (watch) clearInterval(watch)
    watch = null
    void writer.flush()
  }
  saveSettings()
}

/** Turned on for [hours] (24 at most, the default), or off. [by]: who, for the logs: `cli`, or a device's id. */
export function setDebug(on: boolean, hours: number = DEBUG_HOURS, by = 'cli'): DebugView {
  if (!on) {
    if (until) {
      dbgEnd('turned_off')
      settle(0)
      log(`debug log off (${by})`)
      changed?.()
    }
    return debugView()
  }
  const to = Date.now() + Math.min(hours, DEBUG_HOURS) * HOUR
  const fresh = until === 0
  settle(to)
  log(`debug log on until ${new Date(to).toISOString()} (${by})`)
  dbg('debug', fresh ? 'on' : 'extended', { until: new Date(to).toISOString(), by })
  changed?.()
  return debugView()
}

/** The setting as this start finds it (<support>/settings.json): on still within its time, and never for more than 24 hours from now. */
function restore(): void {
  const saved = settings.debugUntil
  if (saved === undefined) return
  const now = Date.now()
  if (typeof saved !== 'number' || !(saved > now)) {
    delete settings.debugUntil
    saveSettings()
    return
  }
  settle(Math.min(saved, now + DEBUG_HOURS * HOUR))
  dbg('debug', 'resumed', { until: new Date(until).toISOString() })
}

// ── Lines ────────────────────────────────────────────────────────────────

/** One line, scrubbed at the write. Keep [event] a fixed word: it's checked, and a test looks at the calls. */
export function dbg(tag: Tag, event: string, fields?: Fields): void {
  if (!debugOn()) return
  writer.write(formatLine(Date.now(), tag, event, fields))
}

/** An error: its name, its code and its stack's frames. Its words only when they're V8's own: others may quote anything. */
export function dbgError(tag: Tag, event: string, e: unknown, fields?: Fields): void {
  if (!debugOn()) return
  const { fields: f, frames } = errorParts(e)
  writer.write([formatLine(Date.now(), tag, event, { ...fields, ...f }), ...frames].join('\n'))
}

const NO_END = (): void => {}
/**
 * A program started: a line, and what to call when it ends for the line with its exit code and how long it
 * ran. Its name and flags only: never a flag's value, nor anything else on the line.
 */
export function dbgProc(file: string, args: readonly string[], fields?: Fields): (exit: number | null, signal?: string | null) => void {
  if (!debugOn()) return NO_END
  const t0 = performance.now()
  const bin = binOf(file)
  const sub = subOf(args)
  dbg('proc', 'start', { bin, sub, ...fields, flags: flagsOf(args) })
  return (exit, signal) => dbg('proc', 'exit', { bin, sub, ...fields, exit, signal, ms: Math.round(performance.now() - t0) })
}

/** An exit code out of execFile's error (none: it ended well; not a number: it never ran, or was killed). */
export const exitOf = (e: unknown): number | null => (e ? (typeof (e as { code?: unknown }).code === 'number' ? (e as { code: number }).code : null) : 0)

/** Tests: what's waiting, written. */
export const flushDebug = (): Promise<void> => writer.flush()

// A last word on the way out, whichever way out it is.
process.on('exit', () => writer.flushSync())
restore()
