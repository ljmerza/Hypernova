// What every `hypernova` command works from: the machine, where Hypernova's files go on it, the
// daemon's ports, and the few ways it touches the system (running a program, printing), passed in
// so the tests can stand in for systemctl, loginctl and the rest. Nothing here imports the daemon:
// the command must work when the daemon isn't installed, isn't running, or is half removed.
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { homedir, userInfo } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { type Platform, LINUX_BIN_DIRS, platformOf, places, systemdUserDir, userBinDir } from '../platform.ts'

export type Ran = { code: number | null; out: string; err: string; missing: boolean }

export type RunOptions = {
  env?: NodeJS.ProcessEnv
  cwd?: string
  input?: string
  timeout?: number
  /** The program talks to the person directly (an installer's progress, sudo's password). */
  inherit?: boolean
}

/** The system, as the commands use it. */
export type Sys = {
  run(cmd: string, args: string[], o?: RunOptions): Promise<Ran>
  /** A program by name on PATH or in the system's folders; null when it isn't installed. */
  which(name: string): string | null
  /** A line for the person (stdout). */
  say(line?: string): void
}

export type Cli = {
  platform: Platform
  arch: string
  env: NodeJS.ProcessEnv
  home: string
  user: string
  uid: number
  /** State, and the daemon's log folder (platform.ts). */
  support: string
  logs: string
  apiPort: number
  localPort: number
  /** <support>/app: the installed daemon and the font it draws the terminal with. */
  appDir: string
  daemonDir: string
  /** Where Hypernova keeps the Node.js it downloads, and the file naming the Node the service runs. */
  nodeRoot: string
  nodePathFile: string
  /** ~/.local/bin/hypernova */
  launcher: string
  unitDir: string
  unitFile: string
  /** The daemon this command runs from (a checkout, an unpacked tarball, or the installed one). */
  selfDaemon: string
  /** Colours and the QR code's own colours: a terminal on the other end. */
  tty: boolean
  sys: Sys
}

/** The service's name with systemd. */
export const UNIT = 'hypernova.service'

export function cliContext(o: Partial<Cli> = {}): Cli {
  const env = o.env ?? process.env
  const home = o.home ?? env.HOME ?? homedir()
  const platform = o.platform ?? platformOf(process.platform)
  const where = places(platform, env, home)
  const support = o.support ?? where.support
  // HYPERNOVA_APP and its companions put the program (the daemon, its Node, the command) apart from
  // the state: a container keeps them in the image, so a rebuilt image runs its new daemon with the
  // same volume (Dockerfile). Unset, everything lives under the support folder.
  const abs = (v: string | undefined) => (v && v.startsWith('/') ? v : undefined)
  const appDir = abs(env.HYPERNOVA_APP) ?? join(support, 'app')
  const unitDir = systemdUserDir(env, home)
  let user = env.USER ?? ''
  try {
    user ||= userInfo().username
  } catch {}
  return {
    platform,
    arch: process.arch,
    env,
    home,
    user,
    uid: process.getuid?.() ?? -1,
    support,
    logs: o.logs ?? where.logs,
    apiPort: Number(env.HYPERNOVA_API_PORT ?? 47321),
    localPort: Number(env.HYPERNOVA_LOCAL_PORT ?? 47322),
    appDir,
    daemonDir: join(appDir, 'daemon'),
    nodeRoot: abs(env.HYPERNOVA_NODE_ROOT) ?? join(support, 'node'),
    nodePathFile: abs(env.HYPERNOVA_NODE_PATH) ?? join(support, 'node-path'),
    launcher: abs(env.HYPERNOVA_LAUNCHER) ?? join(userBinDir(home), 'hypernova'),
    unitDir,
    unitFile: join(unitDir, UNIT),
    selfDaemon: resolve(dirname(fileURLToPath(import.meta.url)), '..', '..'),
    tty: !!process.stdout.isTTY,
    sys: o.sys ?? realSys(env),
    ...o,
  }
}

/** The real system: programs run without a shell, by full path or found on PATH. */
export function realSys(env: NodeJS.ProcessEnv = process.env): Sys {
  return {
    run(cmd, args, o = {}) {
      return new Promise((done) => {
        let out = '', err = ''
        let child: ReturnType<typeof spawn>
        try {
          child = spawn(cmd, args, { env: o.env ?? env, cwd: o.cwd, stdio: o.inherit ? ['inherit', 'inherit', 'inherit'] : ['pipe', 'pipe', 'pipe'] })
        } catch (e: any) {
          return done({ code: null, out: '', err: String(e?.message ?? e), missing: e?.code === 'ENOENT' })
        }
        const timer = o.timeout ? setTimeout(() => child.kill('SIGKILL'), o.timeout) : null
        child.stdout?.on('data', (d) => (out += d))
        child.stderr?.on('data', (d) => (err += d))
        child.on('error', (e: NodeJS.ErrnoException) => {
          if (timer) clearTimeout(timer)
          done({ code: null, out, err: err || e.message, missing: e.code === 'ENOENT' })
        })
        child.on('close', (code) => {
          if (timer) clearTimeout(timer)
          done({ code, out, err, missing: false })
        })
        if (!o.inherit) {
          child.stdin?.on('error', () => {})
          child.stdin?.end(o.input ?? '')
        }
      })
    },
    which(name) {
      const dirs = [...(env.PATH ?? '').split(':').filter(Boolean), ...LINUX_BIN_DIRS]
      for (const d of dirs) if (existsSync(join(d, name))) return join(d, name)
      return null
    },
    say(line = '') {
      process.stdout.write(line + '\n')
    },
  }
}

/** A path under the home folder written with `~`, as people read it. */
export const tilde = (cli: Pick<Cli, 'home'>, p: string) => (p === cli.home ? '~' : p.startsWith(cli.home + '/') ? '~' + p.slice(cli.home.length) : p)

// ── Output ────────────────────────────────────────────────────────────────

export type Mark = 'ok' | 'warn' | 'fail' | 'info' | 'doing'
const SYMBOL: Record<Mark, string> = { ok: '✓', warn: '!', fail: '✗', info: '·', doing: '→' }
const COLOUR: Record<Mark, string> = { ok: '32', warn: '33', fail: '31', info: '2', doing: '36' }

/** One line of a report: a mark, the words, and on the next line what to do about it. */
export function line(cli: Pick<Cli, 'tty' | 'sys'>, mark: Mark, text: string, fix?: string): void {
  const sym = cli.tty ? `\x1b[${COLOUR[mark]}m${SYMBOL[mark]}\x1b[0m` : SYMBOL[mark]
  cli.sys.say(`  ${sym} ${text}`)
  if (fix) for (const l of fix.split('\n')) cli.sys.say(`      ${cli.tty ? `\x1b[2m${l}\x1b[0m` : l}`)
}

export const bold = (cli: Pick<Cli, 'tty'>, s: string) => (cli.tty ? `\x1b[1m${s}\x1b[0m` : s)
