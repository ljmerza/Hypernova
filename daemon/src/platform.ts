// The machine the daemon runs on: a Mac or a Linux server or PC (WSL on a Windows PC too,
// experimental), run from the command line only (docs/DECISIONS.md, "Direction: open source, self-hosted";
// docs/LINUX.md). Where state and logs live, which of the Mac's features exist here and
// why not, and where the programs the daemon runs are found.
//
// Everything here is a pure function of the platform, the environment and the home folder, so the
// tests can ask about either system from either one; the constants at the bottom are this
// process's answers. Nothing here imports the rest of the daemon (util.ts imports this).
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { isAbsolute, join } from 'node:path'
import type { NetworkInterfaceInfo } from 'node:os'

export type Platform = 'darwin' | 'linux'

/**
 * Where this fork's builds are published (the Linux server package and the Android app):
 * a phone gets the app there when this computer has none to hand out (a Linux package carries none).
 */
export const RELEASES = 'https://github.com/ljmerza/Hypernova/releases/latest'

/** macOS is the Mac; anything else is treated as Linux, the only other system Hypernova supports. */
export const platformOf = (os: NodeJS.Platform | string): Platform => (os === 'darwin' ? 'darwin' : 'linux')

// ── Where things live ─────────────────────────────────────────────────────

/**
 * An XDG base folder: the variable when it holds an absolute path (the spec says relative ones are
 * invalid and must be ignored), else its default under the home folder.
 */
function xdg(env: NodeJS.ProcessEnv, name: string, home: string, fallback: string): string {
  const v = env[name]
  return v && isAbsolute(v) ? v : join(home, fallback)
}

export type Places = {
  /** State: identity, tokens, devices, settings, terminals' sockets (0700). */
  support: string
  /** The daemon's own log folder (daemon.log). */
  logs: string
}

/**
 * Where the daemon keeps its state and logs. A Mac's are its own Library folders. On
 * Linux they follow the XDG base directories: state in ~/.local/share/hypernova, logs in
 * ~/.local/state/hypernova/logs. HYPERNOVA_SUPPORT and HYPERNOVA_LOGS win on both (test daemons).
 */
export function places(platform: Platform, env: NodeJS.ProcessEnv, home: string): Places {
  if (platform === 'darwin') {
    return {
      support: env.HYPERNOVA_SUPPORT ?? join(home, 'Library/Application Support/Hypernova'),
      logs: env.HYPERNOVA_LOGS ?? join(home, 'Library/Logs/Hypernova'),
    }
  }
  return {
    support: env.HYPERNOVA_SUPPORT ?? join(xdg(env, 'XDG_DATA_HOME', home, '.local/share'), 'hypernova'),
    logs: env.HYPERNOVA_LOGS ?? join(xdg(env, 'XDG_STATE_HOME', home, '.local/state'), 'hypernova', 'logs'),
  }
}

/** Linux: the folder systemd reads a user's own services from (~/.config/systemd/user). */
export const systemdUserDir = (env: NodeJS.ProcessEnv, home: string): string => join(xdg(env, 'XDG_CONFIG_HOME', home, '.config'), 'systemd', 'user')

/**
 * macOS: the LaunchAgent's label (cli/launchd.ts). The Mac app used it too, so an agent it left
 * behind is replaced rather than left fighting a second daemon for the ports.
 */
export const LAUNCH_LABEL = 'com.prudnikweb.hypernova.daemon'
/** macOS: the folder launchd reads a user's own agents from. */
export const launchAgentsDir = (home: string): string => join(home, 'Library', 'LaunchAgents')

/**
 * Where the `hypernova` command goes. ~/.local/bin is on PATH in Ubuntu's and Debian's default
 * ~/.profile, and Claude Code's official installer puts `claude` there too. A Mac doesn't have it on
 * PATH, so the shell integration's block adds it there (hooks.ts).
 */
export const userBinDir = (home: string): string => join(home, '.local', 'bin')

// ── What exists here ──────────────────────────────────────────────────────

/**
 * The Mac's features a Linux machine doesn't have. Clients that know a key hide that feature and
 * may show its sentence (Snapshot.mac.unavailable, docs/PROTOCOL.md); the rest of the API answers
 * 501 `unavailable` with the same sentence when asked anyway.
 */
export type Feature = 'battery' | 'awake' | 'macVoice' | 'openWindow'

export const LINUX_REASONS: Record<Feature, string> = {
  battery: 'Hypernova shows the battery of a Mac only.',
  awake: 'Stay awake is for a Mac. A Linux server stays on by itself; if this PC suspends, turn that off in its power settings.',
  macVoice: 'The phone reads aloud with its own voice.',
  openWindow: 'This server has no screen to open a window on. Attach from a terminal instead: hn attach, over SSH.',
}

/** Under WSL on a Windows PC, the reasons that would point at a server's ways (cli/wsl.ts). */
export const WSL_REASONS: Partial<Record<Feature, string>> = {
  awake: 'Stay awake is for a Mac. On Windows, keep the PC from sleeping in its power settings, and WSL running (docs/INSTALL.md, “Windows (experimental)”).',
  openWindow: 'Opening a window is for a Mac. On Windows, attach from a WSL terminal instead: hn attach.',
}

/** The features this platform lacks, each with the reason to show ([wsl]: Linux under WSL on Windows). */
export const unavailableOn = (platform: Platform, wsl = false): Partial<Record<Feature, string>> => (platform === 'darwin' ? {} : { ...LINUX_REASONS, ...(wsl ? WSL_REASONS : {}) })

/**
 * What the daemon's messages to the phone call this computer: "Mac" on a Mac, "server" on Linux
 * ("isn’t on the server", "this server"), the word the phone's own strings use there
 * (ComputerWords.kt). Messages about a Mac-only feature keep their "Mac".
 */
export const machineWord = (platform: Platform): string => (platform === 'darwin' ? 'Mac' : 'server')

// ── Programs ──────────────────────────────────────────────────────────────

/** The folders a Linux system keeps its programs in (a service's PATH may lack some). */
export const LINUX_BIN_DIRS = ['/usr/local/bin', '/usr/bin', '/bin', '/usr/local/sbin', '/usr/sbin', '/sbin']

/** A system program by name in [dirs], by full path; null when it isn't installed. */
export function findTool(name: string, dirs: string[] = LINUX_BIN_DIRS, exists: (p: string) => boolean = existsSync): string | null {
  for (const d of dirs) if (exists(join(d, name))) return join(d, name)
  return null
}

/** Where Claude Code may be, before asking a login shell: the official installer's place first. */
export function claudeCandidates(platform: Platform, home: string, readdir?: (p: string) => string[]): string[] {
  const own = platform === 'darwin'
    ? [join(home, '.local/bin/claude'), '/opt/homebrew/bin/claude', '/usr/local/bin/claude']
    : [join(home, '.local/bin/claude'), '/usr/local/bin/claude', '/usr/bin/claude']
  return [...own, ...packageBinDirs(home, readdir).map((d) => join(d, 'claude'))]
}

const listDir = (p: string): string[] => {
  try {
    return readdirSync(p)
  } catch {
    return []
  }
}
/** "v25.9.0" before "v22.1.0": the newest Node first. */
const newestFirst = (a: string, b: string): number => {
  const n = (v: string) => v.replace(/^v/, '').split('.').map((x) => Number.parseInt(x, 10) || 0)
  const [x, y] = [n(a), n(b)]
  for (let i = 0; i < 3; i++) if ((x[i] ?? 0) !== (y[i] ?? 0)) return (y[i] ?? 0) - (x[i] ?? 0)
  return 0
}
/**
 * Where Node's package managers put a command installed with `npm i -g` (nvm's Nodes, newest first;
 * Volta; npm's own prefix; Bun; pnpm): Claude Code and Codex can be there, on a PATH that only an
 * interactive shell's ~/.zshrc sets, which the service and a login shell never read.
 */
export function packageBinDirs(home: string, readdir: (p: string) => string[] = listDir): string[] {
  const nvm = join(home, '.nvm/versions/node')
  return [
    ...readdir(nvm).filter((v) => v.startsWith('v')).sort(newestFirst).map((v) => join(nvm, v, 'bin')),
    join(home, '.volta/bin'),
    join(home, '.npm-global/bin'),
    join(home, '.bun/bin'),
    join(home, 'Library/pnpm'),
    join(home, '.local/share/pnpm'),
  ]
}

/** The login shell that answers `command -v claude` when no known place has it. */
export const loginShell = (platform: Platform, exists: (p: string) => boolean = existsSync): string =>
  platform === 'darwin' ? '/bin/zsh' : exists('/bin/bash') ? '/bin/bash' : '/bin/sh'

// ── The shell ─────────────────────────────────────────────────────────────

export type ShellRc = { kind: 'zsh' | 'bash'; path: string; name: string }

/**
 * A Mac's first line in the shell block (hooks.ts): ~/.local/bin isn't on PATH there, as it is on
 * Ubuntu and Debian, and both `hypernova` and Claude Code's official install are in it.
 */
export const MAC_PATH_LINE = '[[ ":$PATH:" == *":$HOME/.local/bin:"* ]] || export PATH="$HOME/.local/bin:$PATH"'

/**
 * The startup file the `claude` function goes in, for the owner's login shell: ~/.zshrc for zsh
 * (in ZDOTDIR when set), and on Linux ~/.bashrc for bash, the default shell there. Null for any
 * other shell: nothing is added, and `claude` stays plain.
 */
export function shellRc(platform: Platform, shell: string | null | undefined, env: NodeJS.ProcessEnv, home: string): ShellRc | null {
  const s = shell ?? ''
  if (/zsh$/.test(s)) return { kind: 'zsh', path: join(env.ZDOTDIR ?? home, '.zshrc'), name: '~/.zshrc' }
  if (platform === 'linux' && /bash$/.test(s)) return { kind: 'bash', path: join(home, '.bashrc'), name: '~/.bashrc' }
  return null
}

// ── The local network ─────────────────────────────────────────────────────

/**
 * An IPv4 address the internet can reach: not private, loopback, link-local, CGNAT or reserved.
 * Reserved includes 198.18.0.0/15, which proxy tools' tunnel interfaces (Clash, sing-box and the
 * like) take on a PC, and the documentation ranges: none of them is a public address of the machine.
 */
export function publicIPv4(ip: string): boolean {
  const p = ip.split('.').map(Number)
  if (p.length !== 4 || p.some((n) => !Number.isInteger(n) || n < 0 || n > 255)) return false
  const [a, b, c] = p as [number, number, number]
  if (a === 0 || a === 10 || a === 127 || a >= 224) return false
  if (a === 172 && b >= 16 && b <= 31) return false
  if (a === 192 && b === 168) return false
  if (a === 169 && b === 254) return false
  if (a === 100 && b >= 64 && b <= 127) return false
  if (a === 198 && (b === 18 || b === 19)) return false
  if ((a === 192 && b === 0 && (c === 0 || c === 2)) || (a === 198 && b === 51 && c === 100) || (a === 203 && b === 0 && c === 113)) return false
  return true
}

/**
 * Whether the LAN listener starts when the owner hasn't said (docs/PROTOCOL.md, "Local network").
 * A Mac: yes. Linux: yes, unless the machine has a public IPv4 address of its own, as a VPS does.
 * The phone is never on such a server's network, so the local route can't help there, and its port
 * would face the internet (the listener drops strangers at accept, but a scan still finds a port).
 * `lanAccess: true` turns the listener on anyway (a VPN into that network, say).
 */
export function lanByDefault(platform: Platform, ifaces: NodeJS.Dict<NetworkInterfaceInfo[]>, where: { container?: boolean; cloud?: boolean; wslNat?: boolean } = { container: inContainer(), cloud: onCloudVm(), wslNat: wslBehindNatHere() }): boolean {
  if (platform === 'darwin') return true
  // A container's bridge address (172.17.x), a cloud VM's NATed one and WSL's own network behind
  // Windows (172.x) are private, yet no phone is ever on that network: the local route (and its
  // certificate) would serve nobody.
  if (where.container || where.cloud || where.wslNat) return false
  for (const list of Object.values(ifaces)) for (const a of list ?? []) if (!a.internal && a.family === 'IPv4' && publicIPv4(a.address)) return false
  return true
}

/** Interfaces that are virtual bridges, container or VM networks, or tunnels: never "the Wi-Fi". */
export const virtualInterface = (name: string): boolean => /^(docker|br-|virbr|veth|cni|flannel|cali|vboxnet|vmnet|lxc|lxd|podman|kube|tun|tap|wg|zt|tailscale|utun)/i.test(name)

/** Whether this process runs in a container (Docker's and Podman's marker files, or `HYPERNOVA_CONTAINER=1`). */
export function inContainer(env: NodeJS.ProcessEnv = process.env, exists: (p: string) => boolean = existsSync): boolean {
  if (env.HYPERNOVA_CONTAINER === '1') return true
  if (env.HYPERNOVA_CONTAINER === '0') return false
  return exists('/.dockerenv') || exists('/run/.containerenv')
}

/** Vendors whose virtual machines sit behind a NAT: a phone is never on their network. */
const CLOUD_VENDORS = /amazon ec2|google|digitalocean|hetzner|vultr|openstack|oracle|alibaba|linode|akamai|scaleway|ovh|upcloud|exoscale|tencent|huawei cloud/i
/** Microsoft's chassis asset tag on every Azure VM (a Surface laptop has another). */
const AZURE_TAG = '7783-7084-3265-9085-8269-3286-77'

/** Whether this machine is a cloud VM, from the firmware's names (DMI); false where they can't be read. */
export function onCloudVm(read: (f: string) => string = (f) => { try { return readFileSync(`/sys/class/dmi/id/${f}`, 'utf8').trim() } catch { return '' } }): boolean {
  if (process.env.HYPERNOVA_CLOUD === '1') return true
  if (process.env.HYPERNOVA_CLOUD === '0') return false
  return CLOUD_VENDORS.test(`${read('sys_vendor')} ${read('product_name')} ${read('bios_vendor')}`) || read('chassis_asset_tag') === AZURE_TAG
}

// ── WSL ───────────────────────────────────────────────────────────────────
// Windows Subsystem for Linux: a Linux on a Windows PC, where Hypernova runs as on any Linux
// (experimental: docs/INSTALL.md, "Windows (experimental)"). What differs is the network. Under WSL
// 2's default NAT this Linux sits behind Windows on a network of its own (172.x, or another private
// range), which no phone can reach, so the local route is off there. With
// mirrored networking (Windows 11 22H2 and later, `networkingMode=mirrored` in .wslconfig) it has
// Windows' own addresses, and WSL 1 shares Windows' network outright.

export type Wsl = { version: 1 | 2 }

const readProcVersion = (): string => {
  try {
    return readFileSync('/proc/version', 'utf8')
  } catch {
    return ''
  }
}

/**
 * Whether, and which, WSL this is: the kernel's release in /proc/version says "microsoft"
 * ("6.6.87.2-microsoft-standard-WSL2"; WSL 1's is "4.4.0-19041-Microsoft"). A WSL 2 with a kernel
 * of the owner's own may not say so; the variables WSL gives a session do (WSL_DISTRO_NAME,
 * WSL_INTEROP), though a service started by systemd has neither. `HYPERNOVA_WSL=1` or `0` decides.
 * Docker Desktop's containers on Windows run on the same kernel, so they count too.
 */
export function wslOf(env: NodeJS.ProcessEnv = process.env, read: () => string = readProcVersion): Wsl | null {
  if (env.HYPERNOVA_WSL === '0') return null
  const text = read()
  const release = /^Linux version (\S+)/.exec(text)?.[1] ?? text
  if (/microsoft/i.test(release)) return { version: /microsoft-standard|wsl2/i.test(release) ? 2 : 1 }
  if (env.HYPERNOVA_WSL === '1' || env.WSL_DISTRO_NAME || env.WSL_INTEROP) return { version: 2 }
  return null
}

/** Whether this is WSL (1 or 2). */
export const isWsl = (env: NodeJS.ProcessEnv = process.env, read: () => string = readProcVersion): boolean => wslOf(env, read) !== null

/**
 * A path on one of Windows' own drives as WSL mounts them (/mnt/c/…). WSL adds Windows' PATH to a
 * shell's, so `command -v claude` there can find Claude Code's or Codex's Windows install (an npm
 * shim under AppData): never the agent Hypernova runs, which must be the Linux build inside WSL.
 */
export const onWindowsDrive = (p: string): boolean => /^\/mnt\/[a-z]\//i.test(p)

/**
 * WSL's networking mode as `wslinfo --networking-mode` prints it (WSL 2.0.4 and later): "nat",
 * "mirrored", "none", "virtioproxy" and the like, in lower case; null when there's no answer (an
 * older WSL, or no wslinfo in a container). `HYPERNOVA_WSL_NETWORKING` stands in for it.
 */
export function wslNetworking(env: NodeJS.ProcessEnv = process.env, ask: () => string | null = askWslinfo): string | null {
  const v = (env.HYPERNOVA_WSL_NETWORKING || ask() || '').trim().toLowerCase()
  return /^[a-z]+$/.test(v) ? v : null
}

function askWslinfo(): string | null {
  for (const bin of ['/usr/bin/wslinfo', '/bin/wslinfo']) {
    if (!existsSync(bin)) continue
    try {
      return execFileSync(bin, ['--networking-mode'], { encoding: 'utf8', timeout: 3000, stdio: ['ignore', 'pipe', 'ignore'] })
    } catch {
      return null
    }
  }
  return null
}

/**
 * Whether this Linux is behind WSL 2's NAT, where none of its own addresses is one a phone can
 * reach: every mode but mirrored (and the deprecated bridged, which gives the VM an address on the
 * Wi-Fi). An unknown mode counts as NAT, WSL's default. WSL 1 shares Windows' own network.
 */
export const wslBehindNat = (wsl: Wsl | null, networking: string | null): boolean => !!wsl && wsl.version === 2 && networking !== 'mirrored' && networking !== 'bridged'

let wslHere: { wsl: Wsl | null; networking: string | null } | undefined
/** This process's WSL and its networking mode, asked once (they change only with a restart of WSL, which restarts the daemon). */
export function wslFacts(): { wsl: Wsl | null; networking: string | null } {
  if (!wslHere) {
    const wsl = process.platform === 'linux' || process.env.HYPERNOVA_WSL === '1' ? wslOf() : null
    wslHere = { wsl, networking: wsl ? wslNetworking() : null }
  }
  return wslHere
}
/** Whether this daemon is behind WSL's NAT (wslBehindNat, for this process). */
export const wslBehindNatHere = (): boolean => {
  const f = wslFacts()
  return wslBehindNat(f.wsl, f.networking)
}

/**
 * Claude Code refuses --allow-dangerously-skip-permissions (and bypass mode) when run as root,
 * unless IS_SANDBOX=1 says it is in one. On a root server a phone session started with the flag
 * would exit at once, so root runs without it: the Bypass mode is then not offered.
 */
export function bypassBlockedByRoot(uid: number | undefined = process.getuid?.(), env: NodeJS.ProcessEnv = process.env): boolean {
  return uid === 0 && env.IS_SANDBOX !== '1'
}

// ── This process ──────────────────────────────────────────────────────────

export const PLATFORM: Platform = platformOf(process.platform)
export const ON_MAC = PLATFORM === 'darwin'
/** This computer in messages: "Mac" or "server" ([machineWord]). */
export const MACHINE = machineWord(PLATFORM)
export const UNAVAILABLE = unavailableOn(PLATFORM, PLATFORM === 'linux' && isWsl())

/** Whether this machine has [f]. */
export const has = (f: Feature): boolean => !(f in UNAVAILABLE)

/** The answer to a request for a feature this machine lacks: 501 `unavailable`, with the reason. */
export function unavailable(f: Feature): Error & { status: number; code: string; extra: Record<string, unknown> } {
  return Object.assign(new Error(UNAVAILABLE[f] ?? LINUX_REASONS[f]), { status: 501, code: 'unavailable', extra: { feature: f } })
}
