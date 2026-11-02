// `hypernova doctor`: every piece a server needs, checked, each with a plain fix. A failure
// is something that stops Hypernova working (exit 1); a warning is something worth doing; info is
// only worth knowing. The same checks feed `hypernova status`.
import { existsSync, readFileSync } from 'node:fs'
import { connect } from 'node:net'
import { networkInterfaces } from 'node:os'
import { join } from 'node:path'
import { LAUNCH_LABEL, machineWord, publicIPv4 } from '../platform.ts'
import { health, local } from './api.ts'
import { tilde, type Cli, UNIT } from './context.ts'
import { claudeSignedIn, claudeVersionOf, findClaudeBin, isDaemonDir, missingBuildTools, ptyWorks, BUILD_TOOLS } from './install.ts'
import { agentState, agentTarget, guiSession, restartHint, stderrLog } from './launchd.ts'
import { NODE_MAJOR, majorOf, recordedNode, versionOf } from './node.ts'
import { hasUserSystemd, lingering, serviceState } from './service.ts'
import { sdkAt } from '../sdk.ts'
import { behindNat, wslMachineChecks, wslNetworkCheck, wslNetworkingOf } from './wsl.ts'

export type Check = { name: string; mark: 'ok' | 'warn' | 'fail' | 'info'; text: string; fix?: string }

/** Whether something answers TCP on [host]:[port] (a refused or silent port says no within [ms]). */
export function answers(host: string, port: number, ms = 800): Promise<boolean> {
  return new Promise((resolve) => {
    const s = connect({ host, port })
    const done = (v: boolean) => {
      s.destroy()
      resolve(v)
    }
    s.setTimeout(ms, () => done(false))
    s.once('connect', () => done(true))
    s.once('error', () => done(false))
  })
}

/** This machine's own non-loopback IPv4 addresses. */
export function ownAddresses(ifaces = networkInterfaces()): string[] {
  const out: string[] = []
  for (const list of Object.values(ifaces)) for (const a of list ?? []) if (!a.internal && a.family === 'IPv4') out.push(a.address)
  return out
}

// ── Codex ─────────────────────────────────────────────────────────────────

/** `/local/status`'s `codex` (docs/PROTOCOL.md, "Codex"): null when it isn't installed here. */
export type CodexState = {
  available: boolean
  enabled: boolean
  connected: boolean
  version: string | null
  min: string
  path: string | null
  problem: string | null
  account: { signedIn: boolean; type: string | null; planType: string | null } | null
}

/** A ChatGPT plan as OpenAI names it ("prolite" is Pro Lite), as the apps say it (the Mac's CodexWords.planName). */
const planWord = (p: string): string => ({ prolite: 'Pro Lite' } as Record<string, string>)[p.toLowerCase()] ?? p.charAt(0).toUpperCase() + p.slice(1)

/** The lines to run for Codex on a server: npm's install, and the sign-in that needs no browser here. */
export const CODEX_INSTALL = 'npm i -g @openai/codex'
export const CODEX_LOGIN = 'codex login --device-auth'

/**
 * Codex as `doctor` and `status` see it. [alone]: no Claude Code here, so Codex is the agent and
 * what stops it stops Hypernova; beside Claude Code it's optional, and off or absent is only info.
 */
export function codexChecks(c: CodexState | null | undefined, alone: boolean): Check[] {
  const install = `${CODEX_INSTALL}   (needs Node.js and npm; or Codex's own build from its GitHub releases, in ~/.local/bin)\n${CODEX_LOGIN}\nhypernova codex on`
  if (!c) return [{ name: 'codex', mark: 'info', text: 'Codex isn’t installed (optional).', fix: install }]
  const at = c.path ? ` (${c.path})` : ''
  if (!c.available) return [{ name: 'codex', mark: alone ? 'fail' : 'warn', text: `Codex ${c.version ?? ''} is too old: Hypernova needs ${c.min} or later${at}.`.replace('  ', ' '), fix: 'codex update' }]
  if (!c.enabled) return [{ name: 'codex', mark: alone ? 'warn' : 'info', text: `Codex ${c.version ?? ''}${at}, off for Hypernova.`.replace('  ', ' '), fix: 'hypernova codex on' }]
  const out: Check[] = []
  if (c.problem) out.push({ name: 'codex', mark: 'warn', text: `Codex: ${c.problem}`, fix: 'hypernova logs' })
  else out.push({ name: 'codex', mark: c.connected ? 'ok' : 'info', text: `Codex ${c.version ?? ''}, on for Hypernova${c.connected ? '' : ' (connecting)'}${at}`.replace('  ', ' ') })
  if (c.account?.signedIn === false) out.push({ name: 'codex-login', mark: alone ? 'fail' : 'warn', text: 'Codex isn’t signed in.', fix: `${CODEX_LOGIN}   (or: printenv OPENAI_API_KEY | codex login --with-api-key)` })
  else if (c.account?.signedIn) out.push({ name: 'codex-login', mark: 'ok', text: `Codex signed in${c.account.type === 'apiKey' ? ' with an API key (every turn costs money)' : c.account.planType ? ` (ChatGPT ${planWord(c.account.planType)})` : ''}` })
  return out
}

// ── Who listens where ─────────────────────────────────────────────────────

export type Listener = { address: string; port: number }

/** One /proc/net/tcp address ("0100007F:B8A1", or 32 hex digits for tcp6) as text and a port. */
function procAddress(field: string): Listener | null {
  const [hex, portHex] = field.split(':')
  if (!hex || !portHex) return null
  const port = parseInt(portHex, 16)
  // The kernel prints each 32-bit word in the machine's order: little-endian on x86_64 and arm64.
  const bytes: number[] = []
  for (let w = 0; w < hex.length; w += 8) {
    const word = hex.slice(w, w + 8)
    for (let i = 6; i >= 0; i -= 2) bytes.push(parseInt(word.slice(i, i + 2), 16))
  }
  if (bytes.some((b) => Number.isNaN(b))) return null
  if (bytes.length === 4) return { address: bytes.join('.'), port }
  if (bytes.length !== 16) return null
  const zeros = (from: number, to: number) => bytes.slice(from, to).every((b) => b === 0)
  if (zeros(0, 16)) return { address: '::', port }
  if (zeros(0, 15) && bytes[15] === 1) return { address: '::1', port }
  if (zeros(0, 10) && bytes[10] === 0xff && bytes[11] === 0xff) return { address: `::ffff:${bytes.slice(12).join('.')}`, port }
  const groups: string[] = []
  for (let i = 0; i < 16; i += 2) groups.push(((bytes[i]! << 8) | bytes[i + 1]!).toString(16))
  return { address: groups.join(':'), port }
}

/** The listening sockets in a /proc/net/tcp or tcp6 table (state 0A). */
export function parseProcNet(text: string): Listener[] {
  const out: Listener[] = []
  for (const line of text.split('\n').slice(1)) {
    const f = line.trim().split(/\s+/)
    if (f[3] !== '0A') continue
    const l = procAddress(f[1] ?? '')
    if (l) out.push(l)
  }
  return out
}

/** Loopback: 127/8, ::1, or 127/8 written as IPv6. */
export const loopback = (address: string) => address.startsWith('127.') || address === '::1' || address.startsWith('::ffff:127.')

/**
 * The addresses something listens on at [port], from the kernel's own tables: exact, where
 * connecting to one's own addresses can be fooled (a proxy tool's tunnel interface accepts every
 * connection). Null where there are no such tables (not Linux).
 */
export function listenersOn(port: number, read: (file: string) => string = (f) => readFileSync(f, 'utf8')): string[] | null {
  let any = false
  const out: string[] = []
  for (const f of ['/proc/net/tcp', '/proc/net/tcp6']) {
    try {
      for (const l of parseProcNet(read(f))) if (l.port === port) out.push(l.address)
      any = true
    } catch {}
  }
  return any ? out : null
}

export type DoctorOptions = {
  /** Who listens on a port (tests stand in for /proc). */
  listeners?: (port: number) => string[] | null
}

export async function doctorChecks(cli: Cli, o: DoctorOptions = {}): Promise<Check[]> {
  const checks: Check[] = []
  const add = (c: Check) => checks.push(c)

  // The machine.
  const mac = cli.platform === 'darwin'
  add({ name: 'system', mark: cli.arch === 'x64' || cli.arch === 'arm64' ? 'ok' : 'warn', text: `${mac ? 'macOS' : 'Linux'} on ${cli.arch}`, ...(cli.arch === 'x64' || cli.arch === 'arm64' ? {} : { fix: 'Hypernova is built and tested on x86_64 and arm64 only.' }) })
  // git on a Mac is a stand-in that only offers to install Apple's Command Line Tools, until they're there.
  if (mac && (await cli.sys.run('xcode-select', ['-p'], { timeout: 10_000 })).code !== 0 && !['/opt/homebrew/bin/git', '/usr/local/bin/git'].some((g) => existsSync(g))) {
    add({ name: 'xcode', mark: 'warn', text: 'Apple’s Command Line Tools aren’t installed, so there’s no git: Claude Code and the app’s git views need it.', fix: 'xcode-select --install' })
  }
  // WSL on a Windows PC (experimental, cli/wsl.ts): what WSL does and doesn't do for the service.
  if (cli.wsl) for (const c of wslMachineChecks(cli.wsl)) add(c)
  const wslNet = cli.wsl ? await wslNetworkingOf(cli) : null
  const wslNat = !!cli.wsl && behindNat(cli.wsl, wslNet)
  if (cli.uid === 0) add({ name: 'user', mark: 'warn', text: 'Running as root: Claude Code’s sessions would have the whole server, and Claude Code refuses to skip permissions as root, so phone sessions run without the Bypass mode.', fix: 'Safer: adduser claude; loginctl enable-linger claude; su - claude, and run hypernova setup there.' })

  // Node and the daemon's files.
  const node = recordedNode(cli)
  const nodeVersion = node ? await versionOf(cli, node) : null
  if (!node || !nodeVersion) add({ name: 'node', mark: 'fail', text: 'No Node.js recorded for the service.', fix: 'Run hypernova setup (it downloads Node.js 24 from nodejs.org).' })
  else if (majorOf(nodeVersion) < NODE_MAJOR) add({ name: 'node', mark: 'fail', text: `Node.js ${nodeVersion} is older than ${NODE_MAJOR}.`, fix: 'Run hypernova setup: it downloads Node.js 24 into Hypernova’s own folder.' })
  else add({ name: 'node', mark: 'ok', text: `Node.js ${nodeVersion} (${tilde(cli, node)})` })

  const installed = isDaemonDir(cli.daemonDir)
  add(installed ? { name: 'daemon', mark: 'ok', text: `The daemon in ${tilde(cli, cli.daemonDir)}` } : { name: 'daemon', mark: 'fail', text: 'The daemon isn’t installed.', fix: `Run scripts/install-${mac ? 'macos' : 'linux'}.sh, or hypernova setup from the repository.` })
  if (installed && node) {
    const pty = await ptyWorks(cli, node, cli.daemonDir)
    if (pty.ok) add({ name: 'pty', mark: 'ok', text: 'Shared terminals: node-pty works' })
    else {
      const tools = missingBuildTools(cli)
      add({ name: 'pty', mark: 'fail', text: `Shared terminals can’t start: node-pty doesn’t load (${pty.why.slice(0, 160)}).`, fix: mac ? 'Run hypernova setup to install it again (if it has to be built: xcode-select --install first).' : tools.length ? `sudo apt-get install -y build-essential python3, then hypernova setup` : 'Run hypernova setup to build it again.' })
    }
  } else if (!installed) {
    const tools = missingBuildTools(cli)
    if (tools.length) add({ name: 'build', mark: 'warn', text: `Missing build tools: ${tools.join(', ')} (the terminal module is built from source on Linux).`, fix: 'sudo apt-get install -y build-essential python3' })
  }
  if (!cli.sys.which('openssl')) add({ name: 'openssl', mark: 'warn', text: 'No openssl: the local network route has no certificates.', fix: 'sudo apt-get install -y openssl' })

  // The service.
  const sd = mac ? { ok: false, why: '' } : await hasUserSystemd(cli)
  const wslNoSystemd = !!cli.wsl && !cli.wsl.container && cli.wsl.version === 2 && !cli.wsl.systemd
  if (mac) {
    const gui = await guiSession(cli)
    const st = await agentState(cli)
    if (!gui.ok) add({ name: 'service', mark: 'warn', text: `Nobody is logged in at this Mac’s screen, so the service has no session to run in: ${gui.why}`, fix: 'Log in at the screen (System Settings → Users & Groups → Automatic login, for a Mac that should come back by itself after a restart), then hypernova setup. Until then: hypernova run.' })
    else if (!st.installed) add({ name: 'service', mark: 'fail', text: `${LAUNCH_LABEL} isn’t installed.`, fix: 'Run hypernova setup.' })
    else if (!st.active) add({ name: 'service', mark: 'fail', text: `${LAUNCH_LABEL} isn’t running.`, fix: `${restartHint(cli)}, or hypernova setup; hypernova logs and ${tilde(cli, stderrLog(cli))} say why it stopped.` })
    else add({ name: 'service', mark: st.enabled ? 'ok' : 'warn', text: `${LAUNCH_LABEL} running${st.pid ? ` (pid ${st.pid})` : ''}${st.enabled ? ', starts when you log in' : ', but disabled for the next login'}`, ...(st.enabled ? {} : { fix: `launchctl enable ${agentTarget(cli)}` }) })
    add({ name: 'autostart', mark: 'info', text: 'A Mac starts its LaunchAgent at login, not at boot: after a restart the server waits for someone to log in, unless Automatic login is on (FileVault keeps that off).' })
  } else if (!sd.ok) add({ name: 'service', mark: 'warn', text: `No systemd user service here: ${sd.why}`, fix: wslNoSystemd ? 'Turn on systemd for WSL (above), then hypernova setup. Until then: hypernova run, in a terminal you keep open.' : 'Start the daemon yourself with hypernova run (a container’s command does), or log in as this user directly (ssh) so systemd starts your user manager.' })
  else {
    const st = await serviceState(cli)
    if (!st.installed) add({ name: 'service', mark: 'fail', text: `${UNIT} isn’t installed.`, fix: 'Run hypernova setup.' })
    else if (!st.active) add({ name: 'service', mark: 'fail', text: `${UNIT} isn’t running.`, fix: 'systemctl --user restart hypernova, then hypernova logs to see why it stopped.' })
    else add({ name: 'service', mark: st.enabled ? 'ok' : 'warn', text: `${UNIT} running${st.pid ? ` (pid ${st.pid})` : ''}${st.enabled ? ', starts at boot' : ', but not enabled at boot'}`, ...(st.enabled ? {} : { fix: 'systemctl --user enable hypernova' }) })
    const linger = await lingering(cli)
    if (linger === false) add({ name: 'linger', mark: 'warn', text: 'Lingering is off: the service stops when you log out and waits for a login after a reboot.', fix: `sudo loginctl enable-linger ${cli.user}` })
    else if (linger) add({ name: 'linger', mark: 'ok', text: 'Lingering on: runs with nobody logged in' })
  }

  // The daemon itself, and that nothing of it faces the network but the LAN listener.
  const api = await health(cli.apiPort)
  const loc = await health(cli.localPort)
  if (!api || !loc) add({ name: 'health', mark: 'fail', text: `The daemon doesn’t answer on 127.0.0.1:${cli.apiPort} and :${cli.localPort}.`, fix: `hypernova logs shows why; ${restartHint(cli)} starts it again.` })
  else add({ name: 'health', mark: 'ok', text: `Daemon answering as “${api.name}” on 127.0.0.1:${cli.apiPort} (phone API) and :${cli.localPort} (local)` })
  // The phone API and the local API listen on 127.0.0.1 only; phones come in through the LAN
  // listener, which has its own port and its own checks. Anything listening on those ports beyond
  // loopback (a port forward, another program) would put them on the network without them.
  const exposed: string[] = []
  const who = o.listeners ?? ((p: number) => listenersOn(p))
  for (const port of [cli.apiPort, cli.localPort]) {
    const bound = who(port)
    if (bound) {
      for (const a of bound) if (!loopback(a)) exposed.push(`${a.includes(':') ? `[${a}]` : a}:${port}`)
    } else {
      // No kernel table to read: ask each of this machine's addresses instead.
      for (const ip of ownAddresses()) if (await answers(ip, port)) exposed.push(`${ip}:${port}`)
    }
  }
  if (exposed.length) add({ name: 'exposure', mark: 'fail', text: `Something listens on ${exposed.join(', ')}: Hypernova’s ports must be on loopback only.`, fix: 'Another program (a proxy, a port forward) exposes them. Stop it; the phone reaches the daemon through the local network route.' })
  else if (api) add({ name: 'exposure', mark: 'ok', text: 'Phone API and local API on loopback only' })

  let status: any = null
  if (loc) {
    try {
      status = await local(cli, 'GET', '/local/status?fresh=1', undefined, 15_000)
    } catch {}
  }
  if (status?.lan && wslNat) {
    add({ name: 'lan', mark: 'warn', text: `Local network route on port ${status.lan.port}, but behind ${cli.wsl!.container ? 'the container’s' : 'WSL’s NAT'} network no phone can reach it`, fix: `hypernova lan off${cli.wsl!.container ? '' : '   (or mirrored networking, below)'}` })
  } else if (status?.lan) {
    const pub = ownAddresses().filter(publicIPv4)
    add({ name: 'lan', mark: pub.length ? 'warn' : 'ok', text: `Local network route on port ${status.lan.port}${status.lan.addresses?.length ? ` (${status.lan.addresses.join(', ')})` : ''}, device certificates required`, ...(pub.length ? { fix: `This server has a public address (${pub.join(', ')}): turn the local route off unless the phone shares its network:\n  hypernova lan off` } : {}) })
  } else if (status) add({ name: 'lan', mark: 'warn', text: 'Local network route off: it is the phone’s only way in, so no phone can reach this server.', fix: 'hypernova lan on   (only on a network you trust)' })

  // Claude Code. On a server with Codex alone (docs/PROTOCOL.md, "Which agent leads") its absence
  // and its missing hooks are nothing to fix.
  const claude = findClaudeBin(cli)
  const codex = status?.codex as CodexState | null | undefined
  const codexAlone = !claude && !!codex?.available
  if (codexAlone) add({ name: 'claude', mark: 'info', text: 'Claude Code isn’t installed: Hypernova works with Codex here.', fix: 'To add it: hypernova setup (Anthropic’s installer)' })
  else if (!claude) add({ name: 'claude', mark: 'fail', text: 'Claude Code isn’t installed, and neither is Codex: there’s no agent to work with.', fix: `hypernova setup installs Claude Code with Anthropic’s installer (or: curl -fsSL https://claude.ai/install.sh | bash).\nOr Codex: ${CODEX_INSTALL}, then ${CODEX_LOGIN} and hypernova codex on` })
  else {
    const v = await claudeVersionOf(cli, claude)
    add({ name: 'claude', mark: v ? 'ok' : 'fail', text: v ? `Claude Code ${v} (${tilde(cli, claude)})` : `Claude Code at ${tilde(cli, claude)} doesn’t run.`, ...(v ? {} : { fix: 'Install it again: curl -fsSL https://claude.ai/install.sh | bash' }) })
    const auth = v ? await claudeSignedIn(cli, claude) : null
    if (auth && !auth.loggedIn) add({ name: 'login', mark: 'fail', text: 'Claude Code isn’t signed in.', fix: `Run claude once on this ${machineWord(cli.platform)} and follow its sign-in.` })
    else if (auth) add({ name: 'login', mark: 'ok', text: `Claude Code signed in${auth.plan ? ` (${auth.plan})` : ''}` })
    // Claude Code's SDK in the daemon's packages (sdk.ts): the "/" list, Rewind and the sessions the
    // daemon runs itself go through it. `npm ci` puts it there; only a hand-made copy lacks it.
    if (installed) {
      const sdk = sdkAt(cli.daemonDir)
      add(sdk.installed
        ? { name: 'sdk', mark: 'ok', text: `Claude Code’s SDK ${sdk.version ?? ''} in the daemon’s packages`.replace('  ', ' ') }
        : { name: 'sdk', mark: 'warn', text: 'Claude Code’s SDK isn’t in the daemon’s packages: the “/” list, Rewind and the sessions the daemon runs itself won’t work.', fix: 'hypernova setup (it installs the daemon’s packages with npm)' })
    }
  }
  if (status && !codexAlone) {
    const hooks = status.hooks?.installed, shell = status.shell
    add({ name: 'integration', mark: hooks ? 'ok' : 'fail', text: hooks ? 'Hooks and the status line in Claude Code’s settings' : 'Claude Code’s hooks aren’t installed: the phone won’t see approvals.', ...(hooks ? {} : { fix: 'hypernova setup' }) })
    if (shell?.supported) add({ name: 'shell', mark: shell.installed ? 'ok' : 'warn', text: shell.installed ? '`claude` in your shell starts a shared terminal' : 'The `claude` shell function isn’t installed: sessions typed in a terminal aren’t shared with the phone.', ...(shell.installed ? {} : { fix: 'hypernova setup' }) })
    else add({ name: 'shell', mark: 'info', text: 'Your login shell isn’t bash or zsh: `claude` stays plain (phone sessions still share their terminals).' })
  }

  // Codex (optional beside Claude Code; the agent here without it). Hypernova never installs it.
  if (status) for (const c of codexChecks(codex, !claude)) add(c)

  if (cli.wsl) add(wslNetworkCheck(cli, cli.wsl, wslNet))

  const log = join(cli.logs, 'daemon.log')
  add({ name: 'logs', mark: 'info', text: `Log: ${tilde(cli, log)}${existsSync(log) ? '' : ' (not written yet)'}` })
  return checks
}

export { BUILD_TOOLS }
