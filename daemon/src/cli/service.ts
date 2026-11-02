// The daemon as a systemd user service on Linux, the counterpart of the Mac's LaunchAgent (launchd.ts): started
// at boot without anyone logged in (lingering), restarted when it fails, and stopped on its own when
// asked, leaving the shared terminals' holders (the owner's Claude sessions) running, as launchd
// does. Plus the `hypernova` launcher in ~/.local/bin. The texts are pure functions; the commands
// that load them go through cli.sys, so the tests see exactly what would run.
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync, chmodSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { UNIT, type Cli } from './context.ts'

/** The HYPERNOVA_ variables a daemon was set up with (a test copy's folders, other ports), carried into the service. */
export const CARRIED = ['HYPERNOVA_SUPPORT', 'HYPERNOVA_LOGS', 'HYPERNOVA_API_PORT', 'HYPERNOVA_LOCAL_PORT', 'HYPERNOVA_LAN_PORT', 'HYPERNOVA_APP', 'HYPERNOVA_NODE_ROOT', 'HYPERNOVA_NODE_PATH', 'HYPERNOVA_LAUNCHER'] as const

/** A value in a unit file's Environment= line: quoted, with systemd's specifiers and escapes kept literal. */
const unitQuote = (s: string) => `"${s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/%/g, '%%')}"`

/** The service's PATH: its Node first, then where Claude Code and the system keep their programs. */
export function servicePath(cli: Pick<Cli, 'home'>, node: string): string {
  const seen = new Set<string>()
  return [dirname(node), join(cli.home, '.local/bin'), '/usr/local/sbin', '/usr/local/bin', '/usr/sbin', '/usr/bin', '/sbin', '/bin'].filter((d) => !seen.has(d) && !!seen.add(d)).join(':')
}

/** The unit file for [node] running the installed daemon. */
export function unitText(cli: Pick<Cli, 'home' | 'daemonDir' | 'env'>, node: string): string {
  const env = [`PATH=${servicePath(cli, node)}`, 'LANG=C.UTF-8', ...CARRIED.flatMap((k) => (cli.env[k] ? [`${k}=${cli.env[k]}`] : []))]
  return `# Hypernova's daemon (written by \`hypernova setup\`; \`hypernova uninstall\` removes it).
[Unit]
Description=Hypernova, a control room for Claude Code
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=${unitQuote(node)} --disable-warning=ExperimentalWarning ${unitQuote(join(cli.daemonDir, 'src', 'main.ts'))}
WorkingDirectory=${cli.daemonDir.replace(/%/g, '%%')}
${env.map((e) => `Environment=${unitQuote(e)}`).join('\n')}
Restart=on-failure
RestartSec=5
# Stopping or restarting ends the daemon alone: the shared terminals' holders, each holding one of
# your Claude sessions, keep running through an update or a crash, as they do on a Mac.
KillMode=process
# The daemon keeps its own log (hypernova logs); the journal gets only what escapes it.
StandardOutput=null

[Install]
WantedBy=default.target
`
}

/** The `hypernova` command: this Node running the installed daemon's CLI. */
export function launcherText(cli: Pick<Cli, 'daemonDir' | 'env'>, node: string): string {
  const q = (s: string) => `'${s.replace(/'/g, `'\\''`)}'`
  const env = CARRIED.flatMap((k) => (cli.env[k] ? [`export ${k}=${q(cli.env[k]!)}`] : [])).join('\n')
  return `#!/bin/sh
# Hypernova's command (written by \`hypernova setup\`; \`hypernova uninstall\` removes it).
${env ? env + '\n' : ''}N=${q(node)}
C=${q(join(cli.daemonDir, 'src', 'cli', 'hypernova.ts'))}
if [ ! -x "$N" ] || [ ! -f "$C" ]; then
  echo "hypernova: its files are gone. Install it again: curl -fsSL https://ljmerza.github.io/Hypernova/get.sh | bash" >&2
  exit 1
fi
exec "$N" --disable-warning=ExperimentalWarning "$C" "$@"
`
}

/** The launcher is Hypernova's own (so uninstall never removes someone else's `hypernova`). */
export const ownLauncher = (path: string): boolean => {
  try {
    return readFileSync(path, 'utf8').includes("# Hypernova's command (written by `hypernova setup`")
  } catch {
    return false
  }
}

export function writeLauncher(cli: Cli, node: string): void {
  mkdirSync(dirname(cli.launcher), { recursive: true })
  writeFileSync(cli.launcher, launcherText(cli, node), { mode: 0o755 })
  chmodSync(cli.launcher, 0o755)
}

// ── systemd ───────────────────────────────────────────────────────────────

/**
 * The environment systemctl --user needs to find the user's manager. A shell from `su` or `sudo
 * -u`, or a script with a scrubbed environment, lacks XDG_RUNTIME_DIR; the manager's folder is
 * /run/user/<uid> all the same (it exists once the user is logged in or lingering).
 */
export function userBusEnv(cli: Pick<Cli, 'env' | 'uid'>, exists: (p: string) => boolean = existsSync): NodeJS.ProcessEnv {
  const env = { ...cli.env }
  const runtime = env.XDG_RUNTIME_DIR || `/run/user/${cli.uid}`
  if (!env.XDG_RUNTIME_DIR && exists(runtime)) env.XDG_RUNTIME_DIR = runtime
  if (!env.DBUS_SESSION_BUS_ADDRESS && exists(join(runtime, 'bus'))) env.DBUS_SESSION_BUS_ADDRESS = `unix:path=${join(runtime, 'bus')}`
  return env
}

export const systemctl = (cli: Cli, ...args: string[]) => cli.sys.run('systemctl', ['--user', ...args], { env: userBusEnv(cli), timeout: 30_000 })

/** Whether this machine has systemd and the user's own manager answers (a container usually has neither). */
export async function hasUserSystemd(cli: Cli): Promise<{ ok: boolean; why: string }> {
  if (!cli.sys.which('systemctl')) return { ok: false, why: 'This system has no systemd (a container?).' }
  const r = await systemctl(cli, 'show-environment')
  if (r.code === 0) return { ok: true, why: '' }
  return { ok: false, why: (r.err || r.out).trim().split('\n')[0] || 'systemctl --user didn’t answer.' }
}

/** `loginctl show-user`'s Linger: whether the user's services run with nobody logged in. */
export async function lingering(cli: Cli): Promise<boolean | null> {
  if (!cli.sys.which('loginctl')) return null
  const r = await cli.sys.run('loginctl', ['show-user', cli.user, '--property=Linger', '--value'], { timeout: 10_000 })
  if (r.code !== 0) return null
  return r.out.trim() === 'yes'
}

/**
 * Lingering on: the user's own manager starts at boot, so the service runs before (and without)
 * any login, like a VPS needs. loginctl allows it for one's own user on most systems; else sudo,
 * without a password prompt in the way (-n). Returns how it went.
 */
export async function enableLinger(cli: Cli): Promise<'already' | 'done' | 'needs_sudo' | 'no_loginctl'> {
  const now = await lingering(cli)
  if (now === null && !cli.sys.which('loginctl')) return 'no_loginctl'
  if (now) return 'already'
  if ((await cli.sys.run('loginctl', ['enable-linger', cli.user], { timeout: 15_000 })).code === 0) return 'done'
  if (cli.sys.which('sudo') && (await cli.sys.run('sudo', ['-n', 'loginctl', 'enable-linger', cli.user], { timeout: 15_000 })).code === 0) return 'done'
  return 'needs_sudo'
}

/**
 * The user's own systemd manager, ready for the service, with lingering on. Over `ssh` it's
 * already running (the login started it). From `su`, `sudo -u` or a script without a login
 * session it isn't: lingering starts it, so it's turned on first, then the manager is waited for
 * (up to [waitMs]). `linger` says how lingering went; `ok: false` with `why` when there's no
 * manager to use (no systemd at all, a container).
 */
export async function userManager(cli: Cli, waitMs = 15_000): Promise<{ ok: boolean; why: string; linger: Awaited<ReturnType<typeof enableLinger>> }> {
  let sd = await hasUserSystemd(cli)
  if (!cli.sys.which('systemctl')) return { ...sd, linger: 'no_loginctl' }
  const linger = await enableLinger(cli)
  if (!sd.ok && (linger === 'done' || linger === 'already')) {
    for (const until = Date.now() + waitMs; !sd.ok && Date.now() < until; ) {
      await new Promise((r) => setTimeout(r, 500))
      sd = await hasUserSystemd(cli)
    }
  }
  return { ...sd, linger }
}

/** Writes the unit, reloads systemd, enables it at boot and (re)starts it now. Throws with systemctl's words. */
export async function installService(cli: Cli, node: string): Promise<void> {
  mkdirSync(cli.unitDir, { recursive: true })
  writeFileSync(cli.unitFile, unitText(cli, node), { mode: 0o644 })
  for (const args of [['daemon-reload'], ['enable', UNIT], ['restart', UNIT]]) {
    const r = await systemctl(cli, ...args)
    if (r.code !== 0) throw new Error(`systemctl --user ${args.join(' ')}: ${(r.err || r.out).trim() || `exit ${r.code}`}`)
  }
}

/** Stops and disables the service and removes its unit. Returns what couldn't be done. */
export async function removeService(cli: Cli): Promise<string[]> {
  const notes: string[] = []
  if ((await hasUserSystemd(cli)).ok) {
    const r = await systemctl(cli, 'disable', '--now', UNIT)
    if (r.code !== 0 && existsSync(cli.unitFile)) notes.push(`systemctl --user disable --now ${UNIT}: ${(r.err || r.out).trim()}`)
  }
  rmSync(cli.unitFile, { force: true })
  if ((await hasUserSystemd(cli)).ok) await systemctl(cli, 'daemon-reload')
  return notes
}

export type ServiceState = { installed: boolean; enabled: boolean; active: boolean; pid: number | null }

export async function serviceState(cli: Cli): Promise<ServiceState> {
  const installed = existsSync(cli.unitFile)
  if (!(await hasUserSystemd(cli)).ok) return { installed, enabled: false, active: false, pid: null }
  const enabled = (await systemctl(cli, 'is-enabled', UNIT)).out.trim() === 'enabled'
  const active = (await systemctl(cli, 'is-active', UNIT)).out.trim() === 'active'
  const pid = Number((await systemctl(cli, 'show', UNIT, '--property=MainPID', '--value')).out.trim()) || null
  return { installed, enabled, active, pid }
}
