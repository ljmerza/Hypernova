// The machine the daemon runs on: a Mac (with the menu bar app) or a Linux server or PC, run from
// the command line only (docs/DECISIONS.md, "Direction: open source, self-hosted, Tailscale
// inside"; docs/LINUX.md). Where state and logs live, which of the Mac's features exist here and
// why not, and where the programs the daemon runs are found.
//
// Everything here is a pure function of the platform, the environment and the home folder, so the
// tests can ask about either system from either one; the constants at the bottom are this
// process's answers. Nothing here imports the rest of the daemon (util.ts imports this).
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { isAbsolute, join } from 'node:path'
import type { NetworkInterfaceInfo } from 'node:os'

export type Platform = 'darwin' | 'linux'

/**
 * Where Hypernova's builds are published (the Mac's disk image, the Linux package, the Android app):
 * a phone gets the app there when this computer has none to hand out (a Linux package carries none).
 */
export const RELEASES = 'https://github.com/prudnik-web/Hypernova/releases/latest'

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
 * Where the daemon keeps its state and logs. The Mac's are the ones the menu bar app knows. On
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
 * Linux: where the `hypernova` command goes. ~/.local/bin is on PATH in Ubuntu's and Debian's
 * default ~/.profile, and Claude Code's official installer puts `claude` there too.
 */
export const userBinDir = (home: string): string => join(home, '.local', 'bin')

// ── What exists here ──────────────────────────────────────────────────────

/**
 * The Mac's features a Linux machine doesn't have. Clients that know a key hide that feature and
 * may show its sentence (Snapshot.mac.unavailable, docs/PROTOCOL.md); the rest of the API answers
 * 501 `unavailable` with the same sentence when asked anyway.
 */
export type Feature = 'battery' | 'awake' | 'macVoice' | 'openWindow' | 'appWindow' | 'simulator'

export const LINUX_REASONS: Record<Feature, string> = {
  battery: 'Hypernova shows the battery of a Mac only.',
  awake: 'Stay awake is for a Mac. A Linux server stays on by itself; if this PC suspends, turn that off in its power settings.',
  macVoice: 'The Mac’s voice isn’t on Linux. Add a Soniox key to read aloud from this server.',
  openWindow: 'This server has no screen to open a window on. Attach from a terminal instead: hn attach, over SSH.',
  appWindow: 'App windows can be shown from a Mac only.',
  simulator: 'The iOS Simulator runs on a Mac only.',
}

/** The features this platform lacks, each with the reason to show. */
export const unavailableOn = (platform: Platform): Partial<Record<Feature, string>> => (platform === 'darwin' ? {} : { ...LINUX_REASONS })

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

/** Tailscale's command line: the Mac app's, Homebrew's; on Linux the package's. */
export function tailscaleCandidates(platform: Platform): string[] {
  return platform === 'darwin'
    ? ['/Applications/Tailscale.app/Contents/MacOS/Tailscale', '/opt/homebrew/bin/tailscale', '/usr/local/bin/tailscale']
    : ['/usr/bin/tailscale', '/usr/local/bin/tailscale', '/usr/sbin/tailscale']
}

/**
 * adb: the SDK the environment names, Android Studio's default SDK, then a package's. On Linux
 * Android Studio puts the SDK in ~/Android/Sdk, and Debian's `adb` package in /usr/bin (with the
 * SDK pieces under /usr/lib/android-sdk).
 */
export function adbCandidates(platform: Platform, env: NodeJS.ProcessEnv, home: string): string[] {
  const sdks = platform === 'darwin'
    ? [env.ANDROID_HOME, env.ANDROID_SDK_ROOT, join(home, 'Library/Android/sdk')]
    : [env.ANDROID_HOME, env.ANDROID_SDK_ROOT, join(home, 'Android/Sdk'), '/usr/lib/android-sdk']
  const fromSdk = sdks.map((d) => (d ? join(d, 'platform-tools', 'adb') : '')).filter(Boolean)
  return platform === 'darwin' ? [...fromSdk, '/opt/homebrew/bin/adb', '/usr/local/bin/adb'] : [...fromSdk, '/usr/bin/adb', '/usr/local/bin/adb']
}

/**
 * Headless Chrome for Telescope's web pages: Google Chrome, else Chromium. On Linux, Google's
 * package, then the distributions' Chromium (Debian's `chromium`, older Ubuntus' `chromium-browser`,
 * the snap).
 */
export function chromeCandidates(platform: Platform, home: string): string[] {
  if (platform === 'darwin') {
    const apps = ['Google Chrome.app/Contents/MacOS/Google Chrome', 'Chromium.app/Contents/MacOS/Chromium']
    return apps.flatMap((a) => ['/Applications', join(home, 'Applications')].map((d) => join(d, a)))
  }
  return ['/usr/bin/google-chrome-stable', '/usr/bin/google-chrome', '/opt/google/chrome/chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser', '/snap/bin/chromium']
}

// ── The shell ─────────────────────────────────────────────────────────────

export type ShellRc = { kind: 'zsh' | 'bash'; path: string; name: string }

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
 * Such a server is reached through the tailnet only; `lanAccess: true` turns the listener on anyway.
 */
export function lanByDefault(platform: Platform, ifaces: NodeJS.Dict<NetworkInterfaceInfo[]>, where: { container?: boolean; cloud?: boolean } = { container: inContainer(), cloud: onCloudVm() }): boolean {
  if (platform === 'darwin') return true
  // A container's bridge address (172.17.x) and a cloud VM's NATed one are private, yet no phone
  // is ever on that network: the local route (and its certificate) would serve nobody.
  if (where.container || where.cloud) return false
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
export const UNAVAILABLE = unavailableOn(PLATFORM)

/** Whether this machine has [f]. */
export const has = (f: Feature): boolean => !(f in UNAVAILABLE)

/** The answer to a request for a feature this machine lacks: 501 `unavailable`, with the reason. */
export function unavailable(f: Feature): Error & { status: number; code: string; extra: Record<string, unknown> } {
  return Object.assign(new Error(UNAVAILABLE[f] ?? LINUX_REASONS[f]), { status: 501, code: 'unavailable', extra: { feature: f } })
}
