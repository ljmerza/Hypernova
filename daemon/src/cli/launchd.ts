// The daemon as a LaunchAgent on a Mac, the counterpart of service.ts's systemd user service: it
// starts when its owner logs in, launchd restarts it when it dies, and stopping it leaves the shared
// terminals' holders running. The texts and the parsing are pure functions; launchctl goes through
// cli.sys, so the tests see exactly what would run. Only CI's Mac runner runs this for real
// (scripts/test-macos-smoke.sh): this repository's servers are Linux machines.
//
// The plist's keys, the retry around `bootstrap` and the wait after `bootout` come from the Mac app's
// ServiceInstaller.swift (removed in aeeb2fa), which ran on real Macs. AbandonProcessGroup is new.
import { chmodSync, existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { LAUNCH_LABEL } from '../platform.ts'
import { UNIT, type Cli } from './context.ts'
import { CARRIED, type ServiceState } from './service.ts'

const LAUNCHCTL = '/bin/launchctl'

export const launchctl = (cli: Pick<Cli, 'sys'>, ...args: string[]) => cli.sys.run(LAUNCHCTL, args, { timeout: 30_000 })

/** The owner's login session at the screen, the domain a LaunchAgent lives in. */
export const guiDomain = (cli: Pick<Cli, 'uid'>) => `gui/${cli.uid}`
export const agentTarget = (cli: Pick<Cli, 'uid'>) => `${guiDomain(cli)}/${LAUNCH_LABEL}`

/** What the commands call the service: its label on a Mac, its unit on Linux. */
export const serviceName = (cli: Pick<Cli, 'platform'>) => (cli.platform === 'darwin' ? LAUNCH_LABEL : UNIT)
/** The line that starts a stopped daemon. */
export const startHint = (cli: Pick<Cli, 'platform' | 'uid'>) => (cli.platform === 'darwin' ? `launchctl kickstart -k ${agentTarget(cli)}` : 'systemctl --user start hypernova')
/** The line that restarts a daemon. */
export const restartHint = (cli: Pick<Cli, 'platform' | 'uid'>) => (cli.platform === 'darwin' ? `launchctl kickstart -k ${agentTarget(cli)}` : 'systemctl --user restart hypernova')

const xml = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

/** The agent's PATH: its Node first, then Claude Code's folder, Homebrew's and the system's. */
export function agentPath(cli: Pick<Cli, 'home'>, node: string): string {
  const seen = new Set<string>()
  // /usr/sbin and /sbin too: the daemon runs system tools such as scutil (the Mac's name).
  return [dirname(node), join(cli.home, '.local/bin'), '/opt/homebrew/bin', '/usr/local/bin', '/usr/bin', '/bin', '/usr/sbin', '/sbin'].filter((d) => !seen.has(d) && !!seen.add(d)).join(':')
}

/** Where launchd sends what escapes the daemon's own log (a crash before it can write one). */
export const stderrLog = (cli: Pick<Cli, 'logs'>) => join(cli.logs, 'daemon.stderr.log')

/** The LaunchAgent's plist for [node] running the installed daemon. */
export function plistText(cli: Pick<Cli, 'home' | 'daemonDir' | 'logs' | 'env'>, node: string): string {
  const env: [string, string][] = [['PATH', agentPath(cli, node)], ['HOME', cli.home], ['LANG', 'en_US.UTF-8'], ...CARRIED.flatMap((k): [string, string][] => (cli.env[k] ? [[k, cli.env[k]!]] : []))]
  const args = [node, '--disable-warning=ExperimentalWarning', join(cli.daemonDir, 'src', 'main.ts')]
  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<!-- Hypernova's server (written by "hypernova setup"; "hypernova uninstall" removes it). -->
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${LAUNCH_LABEL}</string>
  <key>ProgramArguments</key>
  <array>
${args.map((a) => `    <string>${xml(a)}</string>`).join('\n')}
  </array>
  <key>WorkingDirectory</key>
  <string>${xml(cli.daemonDir)}</string>
  <key>EnvironmentVariables</key>
  <dict>
${env.map(([k, v]) => `    <key>${k}</key>\n    <string>${xml(v)}</string>`).join('\n')}
  </dict>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>ThrottleInterval</key>
  <integer>10</integer>
  <key>ProcessType</key>
  <string>Interactive</string>
  <!-- launchd kills what's left in a dead job's process group unless told not to: systemd's
       KillMode=process here. Holders have a process group of their own (term/shared.ts), and this
       leaves the daemon's other children alone too, as on Linux. -->
  <key>AbandonProcessGroup</key>
  <true/>
  <!-- The daemon keeps its own log (hypernova logs), and rolls it, which would strand launchd's
       open file: launchd gets only what escapes it. -->
  <key>StandardOutPath</key>
  <string>/dev/null</string>
  <key>StandardErrorPath</key>
  <string>${xml(stderrLog(cli))}</string>
</dict>
</plist>
`
}

/** `launchctl print <target>`'s state and pid. Apple doesn't promise its format, so anything else is null. */
export function parseLaunchPrint(text: string): { state: string | null; pid: number | null } {
  const state = /^\s*state = (\S+)/m.exec(text)?.[1] ?? null
  const pid = Number(/^\s*pid = (\d+)/m.exec(text)?.[1]) || null
  return { state, pid }
}

/** Whether `launchctl print-disabled <domain>` lists the agent as disabled ("disabled", or `true` on older systems). */
export function disabledIn(text: string, label = LAUNCH_LABEL): boolean {
  const m = new RegExp(`"${label.replace(/\./g, '\\.')}"\\s*=>\\s*(\\S+)`).exec(text)
  return m?.[1] === 'disabled' || m?.[1] === 'true'
}

/** Whether this user has a login session at the screen: without one there's no domain for the agent (a Mac after a reboot, reached over ssh). */
export async function guiSession(cli: Pick<Cli, 'sys' | 'uid'>): Promise<{ ok: boolean; why: string }> {
  const r = await launchctl(cli, 'print', guiDomain(cli))
  if (r.code === 0) return { ok: true, why: '' }
  return { ok: false, why: (r.err || r.out).trim().split('\n')[0] || `launchctl print ${guiDomain(cli)} answered ${r.code}.` }
}

const pause = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** The agent stopped and unloaded, and launchd done with it: `bootstrap` right after fails with an I/O error otherwise. */
async function bootout(cli: Pick<Cli, 'sys' | 'uid'>, stepMs: number): Promise<void> {
  await launchctl(cli, 'bootout', agentTarget(cli))
  for (let i = 0; i < 20; i++) {
    if ((await launchctl(cli, 'print', agentTarget(cli))).code !== 0) return
    await pause(stepMs)
  }
}

/** Writes the plist and (re)loads it, which starts the daemon. Holders keep running. Throws with launchctl's words. */
export async function installAgent(cli: Cli, node: string, o: { stepMs?: number } = {}): Promise<void> {
  const stepMs = o.stepMs ?? 250
  mkdirSync(cli.unitDir, { recursive: true })
  mkdirSync(cli.logs, { recursive: true, mode: 0o700 })
  writeFileSync(cli.unitFile, plistText(cli, node), { mode: 0o644 })
  chmodSync(cli.unitFile, 0o644)
  await bootout(cli, stepMs)
  // A `launchctl disable` from before would keep it from loading; refusing isn't fatal by itself.
  await launchctl(cli, 'enable', agentTarget(cli))
  let last = { code: 0 as number | null, out: '', err: '' }
  for (let attempt = 0; attempt < 4; attempt++) {
    last = await launchctl(cli, 'bootstrap', guiDomain(cli), cli.unitFile)
    if (last.code === 0) return
    await pause(stepMs * 2 * (attempt + 1))
  }
  throw new Error(`launchctl bootstrap ${guiDomain(cli)}: ${(last.err || last.out).trim() || `exit ${last.code}`}`)
}

/** Stops the agent and removes its plist. Returns what couldn't be done. */
export async function removeAgent(cli: Cli, o: { stepMs?: number } = {}): Promise<string[]> {
  const notes: string[] = []
  if ((await launchctl(cli, 'print', agentTarget(cli))).code === 0) {
    await bootout(cli, o.stepMs ?? 250)
    if ((await launchctl(cli, 'print', agentTarget(cli))).code === 0) notes.push(`launchd still has ${LAUNCH_LABEL}: launchctl bootout ${agentTarget(cli)}`)
  }
  rmSync(cli.unitFile, { force: true })
  return notes
}

/** The agent as `status` and `doctor` report it. `enabled`: it comes back at the next login. */
export async function agentState(cli: Cli): Promise<ServiceState> {
  const installed = existsSync(cli.unitFile)
  const p = await launchctl(cli, 'print', agentTarget(cli))
  if (p.code !== 0) return { installed, enabled: false, active: false, pid: null }
  const { state, pid } = parseLaunchPrint(p.out)
  const disabled = disabledIn((await launchctl(cli, 'print-disabled', guiDomain(cli))).out)
  return { installed, enabled: installed && !disabled, active: state === 'running' || pid != null, pid }
}
