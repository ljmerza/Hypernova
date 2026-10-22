// The Codex CLI on this computer and the shared app-server it runs (docs/specs/codex-app-server.md).
import { execFile, execFileSync } from 'node:child_process'
import { existsSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { HOME } from '../util.ts'
import { PLATFORM, loginShell, packageBinDirs } from '../platform.ts'

/**
 * The version Hypernova's use of the app-server was checked against (2026-10-21). Later versions
 * are taken too: the handshake and the account read tell whether one still speaks the protocol.
 */
export const CODEX_MIN = '0.163.0'

const candidates = () => [
  ...(PLATFORM === 'darwin'
    ? [join(HOME, '.local/bin/codex'), '/opt/homebrew/bin/codex', '/usr/local/bin/codex']
    : [join(HOME, '.local/bin/codex'), '/usr/local/bin/codex', '/usr/bin/codex']),
  ...packageBinDirs(HOME).map((d) => join(d, 'codex')),
]

/** Where `command -v codex` in a login shell pointed, looked up once (it takes a moment). */
let fromShell: string | null | undefined
/** The codex binary: HYPERNOVA_CODEX, the usual places, then the login shell's PATH. Null when there's none. */
export function findCodex(): string | null {
  for (const c of [process.env.HYPERNOVA_CODEX, ...candidates()]) if (c && existsSync(c)) return c
  if (process.env.HYPERNOVA_CODEX) return null
  if (fromShell === undefined) {
    try {
      const p = execFileSync(loginShell(PLATFORM), ['-lc', 'command -v codex'], { encoding: 'utf8', timeout: 5000 }).trim()
      fromShell = p.startsWith('/') && existsSync(p) ? p : null
    } catch {
      fromShell = null
    }
  }
  return fromShell
}

/** "1.2.3" against "1.2.0": whether [v] is at least [min], part by part. */
export function atLeast(v: string, min: string): boolean {
  const a = v.split(/[.-]/).map((x) => Number.parseInt(x, 10) || 0)
  const b = min.split(/[.-]/).map((x) => Number.parseInt(x, 10) || 0)
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const d = (a[i] ?? 0) - (b[i] ?? 0)
    if (d) return d > 0
  }
  return true
}

/** Each binary's version by its path and change time, so a check costs one `--version` per install. */
const versions = new Map<string, string | null>()
/** "0.163.0" from `codex --version` ("codex-cli 0.163.0"); null when it doesn't answer. */
export function codexVersion(bin: string): string | null {
  let key = bin
  try {
    key = `${bin}:${statSync(bin).mtimeMs}`
  } catch {}
  if (versions.has(key)) return versions.get(key)!
  let v: string | null = null
  try {
    v = /(\d+\.\d+\.\d+)/.exec(execFileSync(bin, ['--version'], { encoding: 'utf8', timeout: 10_000 }))?.[1] ?? null
  } catch {}
  versions.set(key, v)
  return v
}

const run = (bin: string, args: string[], ms: number) =>
  new Promise<string>((resolve, reject) => execFile(bin, args, { timeout: ms, encoding: 'utf8' }, (e, out, err) => (e ? reject(new Error((err || e.message).trim())) : resolve(out))))

/**
 * The shared app-server's socket, starting it when it isn't running (`codex app-server daemon
 * start`; Hypernova never stops or restarts it). HYPERNOVA_CODEX_SOCKET: a test's own server.
 */
export async function daemonSocket(bin: string): Promise<string> {
  if (process.env.HYPERNOVA_CODEX_SOCKET) return process.env.HYPERNOVA_CODEX_SOCKET
  const read = async () => {
    try {
      const o = JSON.parse(await run(bin, ['app-server', 'daemon', 'version'], 15_000))
      if (o?.status !== 'running' || typeof o.socketPath !== 'string') return null
      return (o.socketPath as string).replace(/^~(?=\/)/, HOME)
    } catch {
      return null
    }
  }
  const now = await read()
  if (now) return now
  await run(bin, ['app-server', 'daemon', 'start'], 60_000)
  const started = await read()
  if (!started) throw new Error('Codex’s app server didn’t start. Run `codex app-server daemon start` to see why.')
  return started
}
