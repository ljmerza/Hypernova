// `hypernova debug on|off|status|bundle`: the server's debug log (daemon/src/debug-log.ts; docs/LINUX.md, "The
// debug log"). The switch goes through the daemon, which starts or stops at once; with the daemon down it's
// written in settings.json for its next start. `bundle` writes one file to share, scrubbed the same way as the
// log (debug-scrub.ts), and it stays on this computer until the owner sends it.
//
// debug-scrub.ts brings part of the daemon with it, so it's loaded only where it's needed: `status`, `on` and
// `off` work when the daemon is half removed.
import { chmodSync, existsSync, openSync, closeSync, readFileSync, readSync, readdirSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { hostname, release as osRelease } from 'node:os'
import { join } from 'node:path'
import { ApiError, health, local } from './api.ts'
import { bold, line, tilde, type Cli } from './context.ts'
import { doctorChecks, type DoctorOptions } from './doctor.ts'
import { stderrLog } from './launchd.ts'
import type { Flags } from './commands.ts'

const MAX_HOURS = 24
const HOUR = 3600_000
const DEBUG_FILES = ['debug.log', 'debug.log.1', 'debug.log.2', 'debug.log.3']
/** How much of each log goes in a bundle: recent, and small enough to send. */
const DEBUG_TAIL = 2 * 1024 * 1024
const DAEMON_TAIL = 512 * 1024
const OTHER_TAIL = 128 * 1024
/** Bundles kept in the log folder: the newest few, or they'd outgrow the logs themselves. */
const KEEP_BUNDLES = 3

type View = { on: boolean; until: number | null }

/** Stays on for how long, from now; "6h 12m". */
const left = (ms: number) => {
  const m = Math.max(1, Math.round(ms / 60_000))
  return m >= 60 ? `${Math.floor(m / 60)}h ${m % 60}m` : `${m}m`
}
const mb = (bytes: number) => `${(bytes / (1024 * 1024)).toFixed(1)} MB`

const settingsFile = (cli: Cli) => join(cli.support, 'settings.json')
function readSettings(cli: Cli): Record<string, unknown> {
  try {
    const o = JSON.parse(readFileSync(settingsFile(cli), 'utf8'))
    return o && typeof o === 'object' ? o : {}
  } catch {
    return {}
  }
}
/** The setting in its file, with the daemon down: its next start reads it. */
function writeUntil(cli: Cli, until: number | null): void {
  const o = readSettings(cli)
  if (until == null) delete o.debugUntil
  else o.debugUntil = until
  const tmp = `${settingsFile(cli)}.${process.pid}.tmp`
  writeFileSync(tmp, JSON.stringify(o, null, 2) + '\n', { mode: 0o600 })
  renameSync(tmp, settingsFile(cli))
}
/** The setting as the file has it, which is how it stands when nothing answers. */
function viewOnDisk(cli: Cli): View {
  const u = readSettings(cli).debugUntil
  return typeof u === 'number' && u > Date.now() ? { on: true, until: Math.min(u, Date.now() + MAX_HOURS * HOUR) } : { on: false, until: null }
}

function logsSize(cli: Cli): number {
  let total = 0
  for (const f of DEBUG_FILES) {
    try {
      total += statSync(join(cli.logs, f)).size
    } catch {}
  }
  return total
}

export async function debug(cli: Cli, flags: Flags, rest: string[]): Promise<number> {
  const word = rest[0] ?? 'status'
  if (word === 'bundle') return bundle(cli, flags)
  if (word !== 'on' && word !== 'off' && word !== 'status') {
    cli.sys.say('hypernova debug on|off|status|bundle: much more in a log of its own, off by itself after 24 hours (on --hours <n>; bundle --out <file>).')
    return 2
  }
  let hours = MAX_HOURS
  if (word === 'on' && flags.hours !== undefined) {
    hours = Number(flags.hours)
    if (!(hours > 0) || hours > MAX_HOURS) {
      line(cli, 'fail', `--hours takes more than 0, up to ${MAX_HOURS}: it never runs longer.`)
      return 2
    }
  }
  let view: View
  let running = true
  try {
    if (word !== 'status') await local(cli, 'POST', '/local/settings', word === 'on' ? { debugLog: true, debugHours: hours } : { debugLog: false })
    view = (await local<{ debug: View }>(cli, 'GET', '/v1/mac/debug')).debug
  } catch (e) {
    if (!(e instanceof ApiError) || e.status !== 0) {
      line(cli, 'fail', e instanceof Error ? e.message : String(e))
      return 1
    }
    running = false
    if (word !== 'status') writeUntil(cli, word === 'on' ? Date.now() + hours * HOUR : null)
    view = viewOnDisk(cli)
  }
  const file = join(cli.logs, 'debug.log')
  if (flags.json) {
    cli.sys.say(JSON.stringify({ ...view, running, log: file, bytes: logsSize(cli) }, null, 2))
    return 0
  }
  if (view.on) line(cli, 'ok', `Debug logging is on until ${new Date(view.until!).toLocaleString()} (${left(view.until! - Date.now())} left), then it turns itself off.`)
  else line(cli, 'info', 'Debug logging is off.', word === 'status' ? 'hypernova debug on turns it on, for 24 hours.' : undefined)
  if (!running) line(cli, 'warn', view.on ? 'The daemon isn’t running: it starts logging when it starts.' : 'The daemon isn’t running.')
  line(cli, 'info', `Log: ${tilde(cli, file)}${existsSync(file) ? ` (${mb(logsSize(cli))} of about 20 MB)` : ' (nothing yet)'}. Read it: hypernova logs --debug. To share: hypernova debug bundle.`)
  return 0
}

// ── bundle ────────────────────────────────────────────────────────────────

/** The last [max] bytes of a file, whole lines only; empty when it isn't there. */
function tailLines(file: string, max: number): string[] {
  let fd: number
  try {
    fd = openSync(file, 'r')
  } catch {
    return []
  }
  try {
    const size = statSync(file).size
    const len = Math.min(size, max)
    const buf = Buffer.alloc(len)
    readSync(fd, buf, 0, len, size - len)
    const lines = buf.toString('utf8').split('\n')
    if (len < size) lines.shift()
    if (lines[lines.length - 1] === '') lines.pop()
    return lines
  } finally {
    closeSync(fd)
  }
}

/** The debug log's recent lines across its rolled files, oldest first, up to [max] bytes. */
function recentDebug(cli: Cli, max: number): string[] {
  const out: string[][] = []
  let room = max
  for (const f of DEBUG_FILES) {
    if (room <= 0) break
    const lines = tailLines(join(cli.logs, f), room)
    out.unshift(lines)
    room -= lines.reduce((n, l) => n + l.length + 1, 0)
  }
  return out.flat()
}

function sourceFiles(dir: string): string[] {
  const out: string[] = []
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) out.push(...sourceFiles(join(dir, e.name)))
    else if (e.name.endsWith('.ts')) out.push(join(dir, e.name))
  }
  return out
}

/** Who this computer is, to take out by name, as whole words: its user, its name, its phones'. */
function namesOf(cli: Cli, machine: string | null): RegExp | null {
  const names = new Set<string>([cli.user, hostname(), ...(machine ? [machine] : [])])
  try {
    const list = JSON.parse(readFileSync(join(cli.support, 'devices.json'), 'utf8'))
    for (const d of Array.isArray(list) ? list : []) for (const v of [d?.name, d?.model]) if (typeof v === 'string') names.add(v)
  } catch {}
  const parts = [...names].filter((n) => n.trim().length >= 2).sort((a, b) => b.length - a.length).map((n) => n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
  return parts.length ? new RegExp(`(?<![\\w-])(?:${parts.join('|')})(?![\\w-])`, 'g') : null
}

/** A single file: the version, the platform, `hypernova doctor`, and the recent logs, scrubbed. Prints its path. */
export async function bundle(cli: Cli, flags: Flags, o: DoctorOptions = {}): Promise<number> {
  const { bundleDebugLine, bundleEverydayLine, logWords, scrubText, knownSecret } = await import('../debug-scrub.ts')
  // The live secrets, by value, whatever shape they turn up in.
  for (const f of ['admin-token', 'hook-secret']) {
    try {
      knownSecret(readFileSync(join(cli.support, f), 'utf8').trim())
    } catch {}
  }
  const names = namesOf(cli, (await health(cli.apiPort))?.name ?? null)
  const clean = (s: string) => {
    const out = s.split(cli.home).join('~')
    return names ? out.replace(names, '[name]') : out
  }
  const words = logWords(sourceFiles(join(import.meta.dirname, '..')).map((f) => readFileSync(f, 'utf8')))
  const everyday = (file: string, max: number) => tailLines(file, max).map((l) => clean(bundleEverydayLine(l, words)))

  let view: View = viewOnDisk(cli)
  let daemon: string | null = null
  try {
    view = (await local<{ debug: View }>(cli, 'GET', '/v1/mac/debug')).debug
  } catch {}
  try {
    daemon = (await local<{ daemonVersion?: string }>(cli, 'GET', '/local/status')).daemonVersion ?? null
  } catch {}
  const { VERSION } = await import('../util.ts')
  const checks = await doctorChecks(cli, o)

  const out: string[] = [
    'Hypernova debug bundle',
    `Written: ${new Date().toISOString()}`,
    'Scrubbed: no tokens, keys, certificates, codes, links, prompts, replies, commands, files, transcripts, session names or project paths; in the everyday logs only the daemon’s own words stay, the rest is … (docs/SECURITY-MODEL.md, "The server\'s debug log").',
    '',
    '== Version and platform ==',
    `hypernova ${VERSION}`,
    `daemon running: ${daemon ?? 'no answer'}`,
    `platform: ${cli.platform} ${cli.arch} (kernel ${osRelease()})${cli.wsl ? `, WSL ${cli.wsl.version}` : ''}`,
    `node: ${process.version}`,
    `debug logging: ${view.on ? `on until ${new Date(view.until!).toISOString()}` : 'off'}`,
    '',
    '== hypernova doctor ==',
    // Its own sentences, about programs and ports: a path in one is the program's, with the home folder as ~.
    ...checks.map((c) => `[${c.mark}] ${c.name}: ${clean(c.text)}${c.fix ? ` (fix: ${clean(c.fix)})` : ''}`).map((l) => clean(scrubLine(l, scrubText))),
    '',
    '== debug.log, recent lines ==',
    ...recentDebug(cli, DEBUG_TAIL).map((l) => clean(bundleDebugLine(l))),
    '',
    '== daemon.log, recent lines ==',
    ...everyday(join(cli.logs, 'daemon.log'), DAEMON_TAIL),
    '',
    '== terminals.log, recent lines ==',
    ...everyday(join(cli.logs, 'terminals.log'), OTHER_TAIL),
  ]
  // A Mac's service keeps what escapes the daemon's log in a file of its own (cli/launchd.ts).
  if (cli.platform === 'darwin') out.push('', '== the service’s standard error, recent lines ==', ...everyday(stderrLog(cli), OTHER_TAIL))

  const now = new Date().toISOString().replace(/[:.]/g, '-')
  const file = typeof flags.out === 'string' ? flags.out : join(cli.logs, `debug-bundle-${now}.txt`)
  writeFileSync(file, out.join('\n') + '\n', { mode: 0o600 })
  chmodSync(file, 0o600)
  if (typeof flags.out !== 'string') prune(cli)
  line(cli, 'ok', bold(cli, `Wrote ${tilde(cli, file)}`) + ` (${mb(statSync(file).size)}).`)
  line(cli, 'info', 'Scrubbed of secrets and of what’s said in sessions. It’s still about your server: have a look before you share it.')
  return 0
}

/** Doctor's sentences keep their programs' paths: only what looks secret goes. */
function scrubLine(l: string, scrubText: (s: string) => string): string {
  // Paths are what doctor is for: they're put back by being taken out of the scrubber's way.
  const kept: string[] = []
  const held = l.replace(/(?:~\/|\/)[\w.@%+-]+(?:\/[\w.@%+-]*)*/g, (p) => {
    kept.push(p)
    return `\u0000${kept.length - 1}\u0000`
  })
  return scrubText(held).replace(/\u0000(\d+)\u0000/g, (_m, i: string) => kept[Number(i)]!)
}

function prune(cli: Cli): void {
  const all = readdirSync(cli.logs).filter((f) => /^debug-bundle-.*\.txt$/.test(f)).sort()
  for (const f of all.slice(0, Math.max(0, all.length - KEEP_BUNDLES))) rmSync(join(cli.logs, f), { force: true })
}
