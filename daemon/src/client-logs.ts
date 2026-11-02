// A client's debug log, sent on purpose from its Settings (docs/PROTOCOL.md, "Client logs"), kept in
// <support>/client-logs/<device>/ for the owner and his agents to read on this server
// (`hypernova client-logs`). One way: no route reads them back. The contents are untrusted text from
// the device: stored as bytes, never parsed, run or written to the daemon's own log.
import { chmodSync, createWriteStream, lstatSync, mkdirSync, readdirSync, renameSync, rmSync, statSync } from 'node:fs'
import { once } from 'node:events'
import { randomBytes } from 'node:crypto'
import { join } from 'node:path'
import { SUPPORT, log } from './util.ts'

export const CLIENT_LOGS_DIR = join(SUPPORT, 'client-logs')
/** A client's 5 MB of log, its crash file and a little room. */
export const MAX_CLIENT_LOG = 6 * 1024 * 1024
/** The newest this many per device. */
export const KEEP_PER_DEVICE = 5
const KEEP_MS = 30 * 24 * 3600_000
/** Attempts a device may make in [RATE_WINDOW_MS]: a person tapping Send, not a loop. */
export const RATE_MAX = 10
const RATE_WINDOW_MS = 3600_000

export type ClientLog = { path: string; name: string; size: number; at: number }

const fail = (status: number, code: string, message: string, extra?: Record<string, unknown>) => Object.assign(new Error(message), { status, code, extra })

/** The attempts each device made lately, oldest first. In memory: a restart forgets them, which costs nothing. */
const attempts = new Map<string, number[]>()

/** Takes one of the device's attempts, or refuses with how long to wait. Called before the body is read. */
export function takeClientLogSlot(device: string, now = Date.now()): void {
  const kept = (attempts.get(device) ?? []).filter((t) => now - t < RATE_WINDOW_MS)
  if (kept.length >= RATE_MAX) {
    attempts.set(device, kept)
    const retryAfterSeconds = Math.ceil((kept[0]! + RATE_WINDOW_MS - now) / 1000)
    throw fail(429, 'too_many_logs', 'That’s a lot of logs in an hour. Try again later.', { retryAfterSeconds })
  }
  attempts.set(device, [...kept, now])
}

/** A test's clean start. */
export function resetClientLogSlots(): void {
  attempts.clear()
}

/** Only a plain text upload: this route has no other use for what it's sent. */
export function isClientLogType(contentType: string | undefined): boolean {
  return /^text\/plain\s*(;|$)/i.test(contentType ?? '')
}

/** The device's folder, 0700 whatever was there. A device id is the daemon's own, and is checked anyway: it becomes a path. */
function deviceDir(device: string): string {
  if (!/^[\w-]{1,64}$/.test(device)) throw fail(400, 'bad_device', 'That device can’t send logs.')
  for (const dir of [CLIENT_LOGS_DIR, join(CLIENT_LOGS_DIR, device)]) {
    mkdirSync(dir, { recursive: true, mode: 0o700 })
    if (!lstatSync(dir).isDirectory()) throw fail(500, 'bad_folder', 'The folder for client logs isn’t a folder.')
    chmodSync(dir, 0o700)
  }
  return join(CLIENT_LOGS_DIR, device)
}

/** The server's own name for it: when it came in, and a little randomness. Nothing of the client's. */
function logName(now: Date): string {
  return `${now.toISOString().replace(/[:.]/g, '-')}-${randomBytes(3).toString('hex')}.log`
}

/**
 * Saves an upload as it arrives, never whole in memory, under a name of the server's choosing. It takes
 * its real name only once it's all here, so a stopped upload never looks like a log.
 */
export async function saveClientLog(device: string, body: AsyncIterable<Buffer>, now = new Date()): Promise<ClientLog> {
  const dir = deviceDir(device)
  const name = logName(now)
  const path = join(dir, name)
  const part = `${path}.part`
  // `wx`: a link or an old file at this name is never followed or written through.
  const out = createWriteStream(part, { mode: 0o600, flags: 'wx' })
  out.on('error', (e: NodeJS.ErrnoException) => {
    if (e.code !== 'ERR_STREAM_DESTROYED') log('client log write failed', e.code ?? '')
  })
  let size = 0
  try {
    try {
      for await (const chunk of body) {
        size += chunk.length
        if (size > MAX_CLIENT_LOG) throw fail(413, 'too_large', 'That log is too big (6 MB at most).')
        if (!out.write(chunk)) await once(out, 'drain')
      }
    } catch (e: any) {
      throw e?.status ? e : fail(400, 'aborted', 'The upload stopped.')
    }
    await new Promise<void>((res, rej) => out.end((e?: Error | null) => (e ? rej(e) : res())))
    if (!size) throw fail(400, 'empty_log', 'That log is empty.')
    chmodSync(part, 0o600)
    renameSync(part, path)
  } catch (e) {
    out.destroy()
    rmSync(part, { force: true })
    throw e
  }
  keepNewest(dir)
  return { path, name, size, at: now.getTime() }
}

/** Only the newest [KEEP_PER_DEVICE] stay. The names start with the time, so they sort by it. */
function keepNewest(dir: string): void {
  const logs = readdirSync(dir).filter((n) => n.endsWith('.log')).sort()
  for (const old of logs.slice(0, Math.max(0, logs.length - KEEP_PER_DEVICE))) rmSync(join(dir, old), { force: true })
}

/** Removes what's past 30 days, a stopped upload's leftovers among it, and a folder left empty. */
export function reapClientLogs(now = Date.now()): void {
  let devices: string[]
  try {
    devices = readdirSync(CLIENT_LOGS_DIR)
  } catch {
    return
  }
  let n = 0
  for (const d of devices) {
    const dir = join(CLIENT_LOGS_DIR, d)
    try {
      for (const f of readdirSync(dir)) {
        const file = join(dir, f)
        if (now - statSync(file).mtimeMs > KEEP_MS) {
          rmSync(file, { force: true })
          n++
        }
      }
      if (!readdirSync(dir).length) rmSync(dir, { recursive: true, force: true })
    } catch {}
  }
  if (n) log(`removed ${n} old client log${n === 1 ? '' : 's'}`)
}
