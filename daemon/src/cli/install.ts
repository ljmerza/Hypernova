// The pieces `hypernova setup` puts in place on Linux, each one safe to run again: the daemon's
// files in <support>/app (with node-pty built for this machine), Claude Code through Anthropic's
// official installer, and the integration (hooks, status line, the `claude` shell function).
import { chmodSync, cpSync, existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { claudeCandidates, onWindowsDrive } from '../platform.ts'
import { local, waitHealthy, health } from './api.ts'
import { recordedNode } from './node.ts'
import type { Cli } from './context.ts'

// ── The daemon's files ─────────────────────────────────────────────────────

/** The programs node-pty needs to build on Linux (it ships prebuilt binaries for macOS and Windows only). */
export const BUILD_TOOLS = ['make', 'g++', 'python3'] as const

/** Build tools that are missing, by name. */
export const missingBuildTools = (cli: Cli): string[] => BUILD_TOOLS.filter((t) => !cli.sys.which(t))

/** JetBrains Mono beside a daemon folder: an unpacked tarball's Fonts/, or the repository's Android copy. */
export function fontNear(daemon: string): string | null {
  const c = [join(daemon, '..', 'Fonts', 'JetBrainsMono.ttf'), join(daemon, '..', 'android', 'app', 'src', 'main', 'res', 'font', 'jetbrains_mono.ttf')]
  return c.find((p) => existsSync(p)) ?? null
}

/** A folder that holds the daemon: its package.json names it and its entry point is there. */
export function isDaemonDir(dir: string): boolean {
  try {
    return JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8')).name === 'hypernova-daemon' && existsSync(join(dir, 'src', 'main.ts'))
  } catch {
    return false
  }
}

/** [from] as a daemon folder: itself, or its `daemon/` (a repository checkout, an unpacked tarball). */
export function daemonIn(from: string): string | null {
  for (const d of [from, join(from, 'daemon')]) if (isDaemonDir(d)) return resolve(d)
  return null
}

/**
 * Whether node-pty loads and runs a program in a terminal here, in [dir] with [node]: what the
 * shared terminals need. The answer is node-pty's own error when it doesn't.
 */
export async function ptyWorks(cli: Cli, node: string, dir: string): Promise<{ ok: boolean; why: string }> {
  const probe = "const p=require(require('path').join(process.cwd(),'node_modules','node-pty')).spawn('/bin/sh',['-c','exit 7'],{});p.onExit(e=>process.exit(e.exitCode===7?0:3))"
  const r = await cli.sys.run(node, ['-e', probe], { cwd: dir, timeout: 20_000 })
  return { ok: r.code === 0, why: (r.err || r.out).trim().split('\n').slice(-3).join(' ') || `exit ${r.code}` }
}

/** `npm ci` for the daemon's own packages (no developer tools), with [node]'s npm. */
async function npmCi(cli: Cli, node: string, dir: string): Promise<void> {
  const npm = existsSync(join(dirname(node), 'npm')) ? join(dirname(node), 'npm') : cli.sys.which('npm')
  if (!npm) throw new Error('npm isn’t installed next to Node.js. Install Node.js 24 from nodejs.org (the install script does), then run hypernova setup again.')
  const env = { ...cli.env, PATH: `${dirname(node)}:${cli.env.PATH ?? '/usr/bin:/bin'}` }
  const r = await cli.sys.run(npm, ['ci', '--omit=dev', '--no-audit', '--no-fund', '--loglevel=error'], { cwd: dir, env, timeout: 20 * 60_000 })
  if (r.code !== 0) {
    const tail = (r.err || r.out).trim().split('\n').slice(-12).join('\n')
    throw new Error(`npm ci failed in ${dir}:\n${tail}`)
  }
}

/**
 * The daemon in <support>/app/daemon, from [from] (a checkout, an unpacked tarball), with its
 * packages installed and node-pty built: a new copy is prepared beside the old one and swapped in
 * by renames, so a failed install leaves the running one as it was. The packages are reused when
 * the lockfile hasn't changed. Running from the installed copy, only the packages are checked.
 */
export async function installDaemon(cli: Cli, node: string, from: string, say: (s: string) => void = () => {}): Promise<{ copied: boolean; built: boolean }> {
  const src = daemonIn(from)
  if (!src) throw new Error(`${from} doesn’t hold Hypernova’s daemon (no daemon/package.json there).`)
  mkdirSync(cli.appDir, { recursive: true, mode: 0o700 })
  const font = fontNear(src)
  if (font && resolve(font) !== resolve(join(cli.appDir, 'Fonts', 'JetBrainsMono.ttf'))) {
    mkdirSync(join(cli.appDir, 'Fonts'), { recursive: true })
    cpSync(font, join(cli.appDir, 'Fonts', 'JetBrainsMono.ttf'))
  }
  const inPlace = resolve(src) === resolve(cli.daemonDir)
  if (inPlace) {
    if ((await ptyWorks(cli, node, cli.daemonDir)).ok) return { copied: false, built: false }
    const tools = missingBuildTools(cli)
    if (tools.length) throw new Error(buildToolsMessage(tools))
    say('Installing the daemon’s packages and building its terminal module (a minute or two)…')
    await npmCi(cli, node, cli.daemonDir)
    const pty = await ptyWorks(cli, node, cli.daemonDir)
    if (!pty.ok) throw new Error(`The terminal module (node-pty) doesn’t work after building: ${pty.why}`)
    return { copied: false, built: true }
  }
  const staging = join(cli.appDir, 'daemon.new')
  rmSync(staging, { recursive: true, force: true })
  mkdirSync(staging, { recursive: true })
  for (const f of ['src', 'package.json', 'package-lock.json']) if (existsSync(join(src, f))) cpSync(join(src, f), join(staging, f), { recursive: true })
  // The same lockfile as the copy in place: its packages (node-pty built for this machine) move over.
  const sameLock = (() => {
    try {
      return readFileSync(join(staging, 'package-lock.json'), 'utf8') === readFileSync(join(cli.daemonDir, 'package-lock.json'), 'utf8') && existsSync(join(cli.daemonDir, 'node_modules'))
    } catch {
      return false
    }
  })()
  let built = false
  if (sameLock && (await ptyWorks(cli, node, cli.daemonDir)).ok) {
    cpSync(join(cli.daemonDir, 'node_modules'), join(staging, 'node_modules'), { recursive: true, verbatimSymlinks: true })
  } else {
    const tools = missingBuildTools(cli)
    if (tools.length) {
      rmSync(staging, { recursive: true, force: true })
      throw new Error(buildToolsMessage(tools))
    }
    say('Installing the daemon’s packages and building its terminal module (a minute or two)…')
    try {
      await npmCi(cli, node, staging)
    } catch (e) {
      rmSync(staging, { recursive: true, force: true })
      throw e
    }
    built = true
  }
  const pty = await ptyWorks(cli, node, staging)
  if (!pty.ok) {
    rmSync(staging, { recursive: true, force: true })
    throw new Error(`The terminal module (node-pty) doesn’t work after building: ${pty.why}`)
  }
  // Swapped in by renames. Holders started from the old copy keep running: Linux keeps a file
  // that's open (or mapped) until its last user lets go.
  const old = join(cli.appDir, 'daemon.old')
  rmSync(old, { recursive: true, force: true })
  if (existsSync(cli.daemonDir)) renameSync(cli.daemonDir, old)
  renameSync(staging, cli.daemonDir)
  rmSync(old, { recursive: true, force: true })
  return { copied: true, built }
}

export const buildToolsMessage = (tools: string[]) =>
  `Building the terminal module needs ${tools.join(', ')}. Install them with:\n  sudo apt-get install -y build-essential python3\nthen run hypernova setup again.`

// ── Claude Code ───────────────────────────────────────────────────────────

/** Claude Code's command, where the official installer and packages put it, else on PATH. */
export function findClaudeBin(cli: Cli): string | null {
  const env = cli.env.CLAUDE_BIN
  if (env && existsSync(env)) return env
  const found = claudeCandidates(cli.platform, cli.home).find((p) => existsSync(p)) ?? cli.sys.which('claude')
  // Under WSL, Windows' PATH can name Claude Code's Windows install: not the one to run (cli/wsl.ts).
  return found && cli.wsl && onWindowsDrive(found) ? null : found
}

export async function claudeVersionOf(cli: Cli, bin: string): Promise<string | null> {
  const r = await cli.sys.run(bin, ['--version'], { timeout: 15_000 })
  return r.code === 0 ? (r.out.trim().split(/\s+/)[0] ?? null) : null
}

/**
 * Whether Claude Code is signed in, from `claude auth status --json`: it reads the login and
 * changes nothing (exit 1 means "not signed in", with the same JSON). Null when it can't tell. Its
 * output names the account, so only these two facts are kept.
 */
export async function claudeSignedIn(cli: Cli, bin: string): Promise<{ loggedIn: boolean; plan: string | null } | null> {
  const r = await cli.sys.run(bin, ['auth', 'status', '--json'], { timeout: 15_000 })
  try {
    const o = JSON.parse(r.out)
    if (typeof o?.loggedIn !== 'boolean') return null
    return { loggedIn: o.loggedIn, plan: typeof o.subscriptionType === 'string' ? o.subscriptionType : null }
  } catch {
    return null
  }
}

/** Anthropic's official installer (HYPERNOVA_CLAUDE_INSTALLER names a local script instead, for tests). */
export const CLAUDE_INSTALLER = 'https://claude.ai/install.sh'

/**
 * Installs Claude Code with Anthropic's own installer, run as this user, its progress on the
 * screen. It installs the program only: signing in stays the owner's, with `claude`.
 */
export async function installClaude(cli: Cli): Promise<string> {
  const local = cli.env.HYPERNOVA_CLAUDE_INSTALLER
  const bash = cli.sys.which('bash') ?? '/bin/bash'
  let r
  if (local) r = await cli.sys.run(bash, [local], { inherit: true, timeout: 15 * 60_000 })
  else {
    if (!cli.sys.which('curl')) throw new Error('Installing Claude Code needs curl: sudo apt-get install -y curl')
    r = await cli.sys.run(bash, ['-c', `set -o pipefail; curl -fsSL ${CLAUDE_INSTALLER} | bash`], { inherit: true, timeout: 15 * 60_000 })
  }
  if (r.code !== 0) throw new Error(`Claude Code’s installer stopped (exit ${r.code}). Run it yourself to see why: curl -fsSL ${CLAUDE_INSTALLER} | bash`)
  const bin = findClaudeBin(cli)
  if (!bin) throw new Error('Claude Code’s installer finished, but there’s no claude in ~/.local/bin.')
  return bin
}

/**
 * Sessions in `claude agents` (the daemon's settings.claudeAgents) are on for a Linux install:
 * written into <support>/settings.json while it doesn't say, before the service (re)starts and
 * reads it (docs/DECISIONS.md, "claude agents on by default"). Never over the owner's own false, nor
 * over a settings.json that can't be read. `set`: turned on now; `kept`: it already said either way.
 */
export function defaultClaudeAgents(cli: Pick<Cli, 'support'>): 'set' | 'kept' {
  const file = join(cli.support, 'settings.json')
  let s: Record<string, unknown> = {}
  if (existsSync(file)) {
    try {
      const o = JSON.parse(readFileSync(file, 'utf8'))
      if (!o || typeof o !== 'object' || Array.isArray(o)) return 'kept'
      s = o
    } catch {
      return 'kept'
    }
  }
  if (typeof s.claudeAgents === 'boolean') return 'kept'
  mkdirSync(cli.support, { recursive: true, mode: 0o700 })
  const tmp = `${file}.${process.pid}.tmp`
  writeFileSync(tmp, JSON.stringify({ ...s, claudeAgents: true }, null, 2) + '\n', { mode: 0o600 })
  chmodSync(tmp, 0o600)
  renameSync(tmp, file)
  return 'set'
}

// ── The integration ───────────────────────────────────────────────────────

export type Integration = { hooks: { installed: boolean }; statusLine: { installed: boolean; supported: boolean }; shell: { installed: boolean; supported: boolean } }

/**
 * Hooks, the status line tap and the `claude` function, put in by the daemon itself (it knows its
 * port and hook secret), as the Mac app asks it to. With no daemon running (a container without
 * systemd, set up before its first start), the daemon's same code runs in a process of its own
 * (integration.ts), from a daemon folder that has its packages: the installed one, else the one
 * this command runs from. Not in this process: a checkout or a tarball has no packages, and the
 * daemon's code imports them.
 */
export async function setIntegration(cli: Cli, on: boolean): Promise<Integration> {
  if (await health(cli.localPort, 2000)) return local<Integration>(cli, 'POST', `/local/integration/${on ? 'install' : 'uninstall'}`)
  const dir = integrationDir(cli)
  if (!dir) throw new Error('The daemon isn’t installed, so nothing knows its hooks. Run hypernova setup first.')
  const node = recordedNode(cli) ?? process.execPath
  const r = await cli.sys.run(node, ['--disable-warning=ExperimentalWarning', join(dir, 'src', 'cli', 'integration.ts'), on ? 'install' : 'uninstall'], { cwd: dir, timeout: 60_000 })
  const last = r.out.trim().split('\n').pop() ?? ''
  if (r.code !== 0) throw new Error((r.err || r.out).trim().split('\n').slice(-3).join(' ') || `exit ${r.code}`)
  try {
    return JSON.parse(last) as Integration
  } catch {
    throw new Error(`The integration step said something unexpected: ${last.slice(0, 200)}`)
  }
}

/** A daemon folder whose code can run: it holds the daemon and its packages. */
export function integrationDir(cli: Pick<Cli, 'daemonDir' | 'selfDaemon'>, exists: (p: string) => boolean = existsSync): string | null {
  for (const d of [cli.daemonDir, cli.selfDaemon]) if (exists(join(d, 'src', 'cli', 'integration.ts')) && exists(join(d, 'node_modules', 'ws'))) return d
  return null
}


export { waitHealthy }
