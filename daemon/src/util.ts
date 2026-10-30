// Paths, logging and small helpers shared by the daemon.
import { appendFileSync, statSync, closeSync, mkdirSync, openSync, readFileSync, realpathSync, renameSync, writeFileSync, chmodSync, existsSync } from 'node:fs'
import { homedir, hostname } from 'node:os'
import { basename, dirname, join } from 'node:path'
import { randomBytes, createHash, timingSafeEqual } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { ON_MAC, PLATFORM, claudeCandidates, isWsl, loginShell, onWindowsDrive, places } from './platform.ts'

export const HOME = homedir()

// Claude Code or Codex installed with `npm i -g` is a `#!/usr/bin/env node` script, which can't start
// on a service's PATH with no Node on it. This daemon's own Node goes last, so the owner's comes first.
{
  const parts = (process.env.PATH ?? '').split(':').filter(Boolean)
  const own = dirname(process.execPath)
  if (!parts.includes(own)) process.env.PATH = [...parts, own].join(':')
}
// The Mac's folders, or XDG's on Linux (platform.ts); HYPERNOVA_SUPPORT and HYPERNOVA_LOGS win.
const PLACES = places(PLATFORM, process.env, HOME)
export const SUPPORT = PLACES.support
export const LOGS = PLACES.logs
export const CLAUDE_DIR = process.env.CLAUDE_CONFIG_DIR ?? join(HOME, '.claude')
export const API_PORT = Number(process.env.HYPERNOVA_API_PORT ?? 47321)
export const LOCAL_PORT = Number(process.env.HYPERNOVA_LOCAL_PORT ?? 47322)
export const VERSION = '0.2.0'
export const PROTOCOL = 1

mkdirSync(SUPPORT, { recursive: true, mode: 0o700 })
mkdirSync(LOGS, { recursive: true, mode: 0o700 })

const LOG_FILE = join(LOGS, 'daemon.log')
// The log names devices and the tailnet account: the owner's alone, also where homes are 0755
// (Debian) and on a log folder made before.
try {
  chmodSync(LOGS, 0o700)
  closeSync(openSync(LOG_FILE, 'a', 0o600))
  chmodSync(LOG_FILE, 0o600)
} catch {}
/** A log file grows to this, then becomes `<name>.1` (the one before is dropped): about 10 MB at most per log. */
export const LOG_MAX_BYTES = 5 * 1024 * 1024
/** Rolls [file] to `<file>.1` when it has reached [max] bytes; true when it did. */
export function rollLog(file: string, max = LOG_MAX_BYTES): boolean {
  try {
    if (statSync(file).size < max) return false
    renameSync(file, file + '.1')
    closeSync(openSync(file, 'a', 0o600))
    chmodSync(file + '.1', 0o600)
    return true
  } catch {
    return false
  }
}
let sinceRollCheck = 0
export function log(...parts: unknown[]): void {
  const line = `${new Date().toISOString()} ${parts.map((p) => (typeof p === 'string' ? p : p instanceof Error ? p.stack ?? p.message : JSON.stringify(p))).join(' ')}`
  try {
    // Looked at on the first line and then every 200 lines, so a chatty day costs no stat per line.
    if (sinceRollCheck++ % 200 === 0) rollLog(LOG_FILE)
    appendFileSync(LOG_FILE, line + '\n', { mode: 0o600 })
  } catch {}
  // A terminal, or a container's log (`hypernova run` sets HYPERNOVA_LOG_STDOUT): the line there too.
  if (process.stdout.isTTY || process.env.HYPERNOVA_LOG_STDOUT) console.log(line)
}

export function readJson<T>(path: string, fallback: T): T {
  try {
    return JSON.parse(readFileSync(path, 'utf8')) as T
  } catch {
    return fallback
  }
}

/**
 * Atomic write (temp file + rename), 0600 by default. A missing folder is made first (0700, as it
 * may hold secrets): ~/.claude doesn't exist yet on a Mac where Claude Code has never run.
 */
export function writeJson(path: string, value: unknown, mode = 0o600): void {
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 })
  const tmp = `${path}.${process.pid}.tmp`
  writeFileSync(tmp, JSON.stringify(value, null, 2) + '\n', { mode })
  chmodSync(tmp, mode)
  renameSync(tmp, path)
}

/** Atomic write like [writeJson], for text. */
export function writeText(path: string, text: string, mode = 0o600): void {
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 })
  const tmp = `${path}.${process.pid}.tmp`
  writeFileSync(tmp, text, { mode })
  renameSync(tmp, path)
}

export const randomToken = (bytes = 32) => randomBytes(bytes).toString('base64url')
export const sha256 = (s: string) => createHash('sha256').update(s).digest('hex')

export function safeEqual(a: string, b: string): boolean {
  const x = Buffer.from(a)
  const y = Buffer.from(b)
  return x.length === y.length && timingSafeEqual(x, y)
}

let macNameCache: { at: number; v: string } | null = null
export function macName(): string {
  if (macNameCache && Date.now() - macNameCache.at < 60_000) return macNameCache.v
  let v = hostname()
  // A Linux machine goes by its host name; a Mac by the name the owner gave it in System Settings.
  if (ON_MAC) {
    try {
      v = execFileSync('/usr/sbin/scutil', ['--get', 'ComputerName'], { encoding: 'utf8' }).trim() || v
    } catch {}
  }
  macNameCache = { at: Date.now(), v }
  return v
}

export function trimText(s: string | null | undefined, max: number): string | null {
  if (s == null) return null
  return s.length > max ? s.slice(0, max - 1) + '…' : s
}

/** Keep the tail of long output (that's where errors and results are). */
export function tail(s: string, max = 64 * 1024): string {
  return s.length > max ? '…\n' + s.slice(s.length - max) : s
}

export function findClaude(): string | null {
  const candidates = [process.env.CLAUDE_BIN, ...claudeCandidates(PLATFORM, HOME)]
  for (const c of candidates) if (c && existsSync(c)) return c
  try {
    const p = execFileSync(loginShell(PLATFORM), ['-lc', 'command -v claude'], { encoding: 'utf8', timeout: 5000 }).trim() || null
    // Under WSL, never Claude Code's Windows install found through Windows' PATH.
    return p && PLATFORM === 'linux' && onWindowsDrive(p) && isWsl() ? null : p
  } catch {
    return null
  }
}

/** [findClaude], looked up again at most once a minute: the snapshot asks often, and a computer without Claude Code would ask its login shell each time. */
let claudeLooked: { at: number; bin: string | null } | null = null
export function claudeCached(): string | null {
  if (!claudeLooked || Date.now() - claudeLooked.at > 60_000) claudeLooked = { at: Date.now(), bin: findClaude() }
  return claudeLooked.bin
}

let claudeVersionCache: string | null = null
export function claudeVersion(): string | null {
  if (claudeVersionCache) return claudeVersionCache
  const bin = claudeCached()
  if (!bin) return null
  try {
    claudeVersionCache = execFileSync(bin, ['--version'], { encoding: 'utf8', timeout: 10000 }).trim().split(' ')[0] ?? null
  } catch {}
  return claudeVersionCache
}

/** Each Claude Code install's version by its real path and change time: one `--version` per install. */
const installedVersions = new Map<string, string | null>()
/**
 * The Claude Code installed now ([claudeCached]), following its own updates, unlike [claudeVersion]
 * (read once): `~/.local/bin/claude` then points at the new `versions/<version>`, a file named for its
 * version, so nothing is run for it. Any other install (npm's) is asked once with `--version`.
 */
export function installedClaudeVersion(): string | null {
  const bin = claudeCached()
  if (!bin) return null
  let real = bin
  let key = bin
  try {
    real = realpathSync(bin)
    key = `${real}:${statSync(real).mtimeMs}`
  } catch {}
  if (installedVersions.has(key)) return installedVersions.get(key)!
  let v = /^\d+\.\d+\.\d+$/.test(basename(real)) ? basename(real) : null
  if (!v) {
    try {
      v = /(\d+\.\d+\.\d+)/.exec(execFileSync(bin, ['--version'], { encoding: 'utf8', timeout: 10_000 }))?.[1] ?? null
    } catch {}
  }
  installedVersions.set(key, v)
  return v
}

/**
 * A `claude agents` job's process on an older Claude Code than [installed] (`claude respawn` puts it
 * on the installed one): both versions, for the phone. Undefined when it isn't, or either is unknown.
 * [runs] is its registry entry's `version`, as Claude Code writes it ("2.1.312").
 */
export function outdatedClaude(runs: unknown, installed: string | null): { runs: string; installed: string } | undefined {
  if (typeof runs !== 'string' || !installed) return undefined
  const parts = (v: string) => /^(\d+)\.(\d+)\.(\d+)/.exec(v.trim())?.slice(1).map(Number) ?? null
  const [a, b] = [parts(runs), parts(installed)]
  if (!a || !b) return undefined
  for (let i = 0; i < 3; i++) {
    if (a[i]! < b[i]!) return { runs: runs.trim(), installed }
    if (a[i]! > b[i]!) return undefined
  }
  return undefined
}

export function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms))
}

/**
 * A session's project as clients name it: its folder's name, except the home folder itself, which
 * is "Home" (audit P17: a session in ~ read as a project named after the user, like a
 * machine's name). Trailing slashes don't count.
 */
export function projectNameOf(cwd: string, home: string = process.env.HOME ?? HOME): string {
  const trim = (p: string) => (p.length > 1 ? p.replace(/\/+$/, '') : p)
  if (home && trim(cwd) === trim(home)) return 'Home'
  return basename(cwd)
}

export function projectSlug(cwd: string): string {
  return cwd.replace(/[^a-zA-Z0-9]/g, '-')
}
