// Tailscale built in (docs/PROTOCOL.md, "Tailscale built in"): the daemon starts and supervises
// `hypernova-tsnet` (daemon/tsnet, Go), its own node on the user's tailnet, which serves the phone
// API over the tailnet with HTTPS. The user signs in to Tailscale once, with the link this module
// hands to the Mac app and to `hn tailscale`, and never installs the Tailscale app.
//
// When the Tailscale app on this computer already serves the phone API (`tailscale serve`, the
// older setup), the helper isn't started: one node per machine, and the choice is reported as
// `mode: "system"`. The state (the node's key) lives in <support>/tsnet (0700).
import { spawn, type ChildProcess } from 'node:child_process'
import { existsSync, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { hostname, platform, arch } from 'node:os'
import { createInterface } from 'node:readline'
import { API_PORT, SUPPORT, log } from './util.ts'
import { settings, saveSettings } from './store.ts'
import { serverIdentity, TLS_DIR } from './tls.ts'
import { enableServe, tailscaleCached, tailscaleStatus } from './services.ts'
import { broadcast } from './hub.ts'

/** What the helper reports (one JSON line per change; daemon/tsnet/main.go `status`). */
export type HelperStatus = {
  state: 'NeedsLogin' | 'Starting' | 'Running' | 'Stopped' | 'NoState' | string
  loginUrl?: string
  dnsName?: string
  ips?: string[]
  account?: string
  tailnet?: string
  controlUrl?: string
  https?: boolean
  listening?: string
  tls?: 'tailscale' | 'pinned'
  error?: string
}

export type TsnetState = {
  /** Which Tailscale carries the phone API: the Tailscale app's `serve`, the built-in node, or neither. */
  mode: 'system' | 'builtIn' | 'off'
  /** Why, in plain words. */
  reason: string
  /** The helper binary is there (bundled, or HYPERNOVA_TSNET). */
  available: boolean
  /** The setting: the built-in node may run (default on). */
  enabled: boolean
  /** The helper's own word, while it runs; null otherwise. */
  helper: HelperStatus | null
  /** The base URL the phone should use for this route, once it's served. */
  url: string | null
  /** The coordination server when it isn't tailscale.com (Headscale). */
  controlUrl: string | null
}

export const TSNET_DIR = process.env.HYPERNOVA_TSNET_STATE ?? join(SUPPORT, 'tsnet')
/** How long the Tailscale app gets at boot before the built-in node takes over; tests shorten it. */
export const BOOT_GRACE_MS = Number(process.env.HYPERNOVA_TSNET_GRACE_MS ?? 20_000)
const RECHECK_MS = Number(process.env.HYPERNOVA_TSNET_RECHECK_MS ?? 60_000)
let graceDone = false
let autoServeAt = 0
/** `tailscale serve` is tried for the app at most this often (and never when HYPERNOVA_TSNET_AUTOSERVE=0). */
export const AUTOSERVE_EVERY_MS = Number(process.env.HYPERNOVA_TSNET_AUTOSERVE_MS ?? 10 * 60_000)
const autoServeAllowed = (): boolean => process.env.HYPERNOVA_TSNET_AUTOSERVE !== '0' && Date.now() - autoServeAt >= AUTOSERVE_EVERY_MS
let rechecking: Promise<void> | null = null
/** Re-checks in a row that found the Tailscale app no longer serving (system mode). */
let systemMisses = 0

/**
 * Which Tailscale should serve the phone now? Every minute, and right after `tailscale serve` is
 * turned on. While the built-in node runs and the Tailscale app starts serving, the helper stops
 * (one node per machine). While the app serves and then stops (quit, signed out, serve off,
 * uninstalled), the built-in node takes over after two checks in a row, so an app restarting
 * doesn't flap the route. A call during a check waits for it, then looks once more (the caller's
 * news may be newer).
 */
export async function recheckSystem(): Promise<void> {
  if (rechecking) await rechecking
  if (stopped || mode === 'off') return
  rechecking = (async () => {
    const sys = await tailscaleStatus(true)
    const serving = sys.installed && sys.running && sys.loggedIn && sys.serveOn
    if (mode === 'builtIn' && serving) {
      log('tsnet: the Tailscale app now serves the phone API; stopping the built-in node')
      await startTsnet()
    } else if (mode === 'system') {
      systemMisses = serving ? 0 : systemMisses + 1
      if (systemMisses >= 2) {
        systemMisses = 0
        log('tsnet: the Tailscale app no longer serves the phone API; starting the built-in node')
        await startTsnet()
      }
    }
  })()
  try {
    await rechecking
  } finally {
    rechecking = null
  }
}

/** The helper for this machine: HYPERNOVA_TSNET (tests, a fake) or the bundled daemon/bin one. */
export function helperPath(): string | null {
  const env = process.env.HYPERNOVA_TSNET
  if (env !== undefined) return env && existsSync(env) ? env : null
  const os = platform() === 'darwin' ? 'darwin' : 'linux'
  const cpu = arch() === 'arm64' ? 'arm64' : 'amd64'
  const p = join(dirname(fileURLToPath(import.meta.url)), '..', 'bin', `hypernova-tsnet-${os}-${cpu}`)
  return existsSync(p) ? p : null
}

/** The node's name on the tailnet: "hypernova-<host>", lowercase letters, digits and dashes (DNS). */
export function nodeName(host = hostname()): string {
  const clean = host.toLowerCase().replace(/\.local$/, '').replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 50)
  return `hypernova-${clean || 'host'}`
}

export const tsnetEnabled = (): boolean => settings.builtInTailscale !== false
export const controlUrl = (): string | null => process.env.HYPERNOVA_TSNET_CONTROL || settings.tailscaleControlUrl || null

let child: ChildProcess | null = null
let helper: HelperStatus | null = null
let mode: TsnetState['mode'] = 'off'
let reason = 'not started'
let stopped = false
let restarts = 0
let restartTimer: NodeJS.Timeout | null = null
let lastErr = ''
let healthyTimer: NodeJS.Timeout | null = null
let watcher: NodeJS.Timeout | null = null

/** The state without the sign-in link and its QR code: what goes to every feed. */
export function publicState(): TsnetState {
  const s = tsnetState()
  if (!s.helper) return s
  const { loginUrl: _l, qr: _q, ...rest } = s.helper as HelperStatus & { qr?: string }
  return { ...s, helper: rest }
}

/** `https://…`, or plain http only to a private address (a Headscale on the LAN, a test): what a control URL may be. */
export function controlUrlOk(url: string): boolean {
  let u: URL
  try {
    u = new URL(url)
  } catch {
    return false
  }
  if (u.username || u.password || u.search || u.hash || !u.hostname) return false
  if (u.protocol === 'https:') return true
  if (u.protocol !== 'http:') return false
  const h = u.hostname.replace(/^\[|\]$/g, '')
  if (h === 'localhost') return true
  // Decimal octets without leading zeros ("010" is octal to some resolvers).
  const m = /^(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})$/.exec(h)
  if (!m) return /^(fc|fd)[0-9a-f]{2}:/i.test(h) || h === '::1'
  const [a, b] = [Number(m[1]), Number(m[2])]
  return a === 127 || a === 10 || (a === 192 && b === 168) || (a === 172 && b >= 16 && b <= 31) || (a === 100 && b >= 64 && b <= 127)
}

/** The built-in route's base URL, when the helper serves: what the pairing link carries. */
export function tsnetBaseUrl(): string | null {
  return mode === 'builtIn' && helper?.state === 'Running' && helper.listening ? helper.listening : null
}

export function tsnetState(): TsnetState {
  return { mode, reason, available: helperPath() !== null, enabled: tsnetEnabled(), helper, url: tsnetBaseUrl(), controlUrl: controlUrl() }
}

/**
 * Decides which Tailscale serves the phone and starts the helper when it's the built-in one. The
 * Tailscale app already serving the API wins (no second node for one machine); otherwise the helper
 * runs when the setting allows and the binary is there. Called at start and when the setting changes.
 */
export async function startTsnet(): Promise<TsnetState> {
  stopped = false
  let sys = await tailscaleStatus(true)
  // At boot the Tailscale app may still be coming up: give it a moment before deciding it isn't serving.
  if (sys.installed && !sys.running && !graceDone) {
    graceDone = true
    await new Promise((r) => setTimeout(r, BOOT_GRACE_MS))
    sys = await tailscaleStatus(true)
  }
  graceDone = true
  if (!watcher) {
    // The app can start serving later (its own setup step, or the Mac app's "Turn on phone access"):
    // one node per machine, so the helper steps aside when that happens.
    watcher = setInterval(() => void recheckSystem(), RECHECK_MS)
    watcher.unref()
  }
  if (sys.installed && sys.running && sys.loggedIn && sys.serveOn) {
    mode = 'system'
    reason = `The Tailscale app on this computer already serves the phone API (${sys.url}); the built-in node stays off.`
    await stopHelper()
    return tsnetState()
  }
  // The Tailscale app is running and signed in but doesn't serve the phone API yet: use it (the
  // person already has it; a second node would be a second sign-in). Only when serve fails (HTTPS
  // certificates off in the tailnet, no permission) does the built-in node run. Tried at most every
  // ten minutes, so a refusal isn't repeated each minute.
  if (sys.installed && sys.running && sys.loggedIn && !sys.serveOn && autoServeAllowed()) {
    autoServeAt = Date.now()
    try {
      const st = await enableServe()
      if (st.serveOn) {
        mode = 'system'
        reason = `The Tailscale app on this computer serves the phone API (${st.url}), turned on by Hypernova; the built-in node stays off.`
        log('tsnet: tailscale serve turned on for the phone API')
        await stopHelper()
        return tsnetState()
      }
    } catch (e: any) {
      log('tsnet: tailscale serve could not be turned on:', String(e?.message ?? e).slice(0, 200))
    }
  }
  if (!tsnetEnabled()) {
    mode = 'off'
    reason = 'Built-in Tailscale is off in the settings.'
    await stopHelper()
    return tsnetState()
  }
  const bin = helperPath()
  if (!bin) {
    mode = 'off'
    reason = 'The Tailscale helper (hypernova-tsnet) is not bundled with this daemon.'
    return tsnetState()
  }
  mode = 'builtIn'
  reason = sys.installed ? 'The Tailscale app on this computer is not serving the phone API, so Hypernova runs its own node.' : 'Hypernova runs its own Tailscale node; nothing to install.'
  if (!child) spawnHelper(bin)
  return tsnetState()
}

function spawnHelper(bin: string): void {
  mkdirSync(TSNET_DIR, { recursive: true, mode: 0o700 })
  const id = serverIdentity()
  const args = ['--state', TSNET_DIR, '--hostname', nodeName(), '--port', String(API_PORT), '--target', `127.0.0.1:${API_PORT}`]
  const control = controlUrl()
  if (control) args.push('--control', control)
  if (id) args.push('--cert', join(TLS_DIR, 'server.crt'), '--key', join(TLS_DIR, 'server.key'))
  // The helper gets only what it needs: no daemon secrets, no log uploads to Tailscale (whatever
  // it keeps stays in its state folder). A pre-auth key (tests, Headscale) travels in a variable
  // the helper reads once, never on the command line.
  const env: Record<string, string> = { PATH: process.env.PATH ?? '/usr/bin:/bin', HOME: process.env.HOME ?? SUPPORT, TS_NO_LOGS_NO_SUPPORT: 'true', TS_LOGS_DIR: TSNET_DIR }
  if (process.env.HYPERNOVA_TSNET_AUTHKEY) {
    env.HYPERNOVA_TSNET_AUTHKEY = process.env.HYPERNOVA_TSNET_AUTHKEY
    args.push('--authkey-env', 'HYPERNOVA_TSNET_AUTHKEY')
  }
  const p = spawn(bin, args, { stdio: ['pipe', 'pipe', 'pipe'], env })
  child = p
  helper = { state: 'Starting' }
  log(`tsnet helper started (pid ${p.pid}, ${nodeName()}${control ? `, control ${control}` : ''})`)
  createInterface({ input: p.stdout! }).on('line', (line) => {
    let msg: any
    try {
      msg = JSON.parse(line)
    } catch {
      return
    }
    if (!msg || msg.t !== 'status') return
    const { t: _t, ...rest } = msg
    const next = rest as HelperStatus
    if (JSON.stringify(next) === JSON.stringify(helper)) return
    helper = next
    if (next.error && next.error !== lastErr) log('tsnet helper:', next.error)
    lastErr = next.error ?? ''
    if (next.state === 'Running' && next.listening) log(`tsnet serving ${next.listening} (${next.tls}, ${next.dnsName ?? '?'}, ${next.account ?? '?'})`)
    else if (next.state === 'NeedsLogin' && next.loginUrl) log('tsnet needs a sign-in')
    // The Mac app's feed follows this (it reads the sign-in link from /local/status itself; the
    // broadcast reaches every phone too, so the link stays out of it).
    broadcast({ t: 'tsnet', tsnet: publicState(), tailnet: tailnetNow() })
    // A node that has run for a minute has earned a fresh restart budget.
    if (next.state === 'Running') healthyTimer ??= setTimeout(() => { restarts = 0; healthyTimer = null }, 60_000)
  })
  createInterface({ input: p.stderr! }).on('line', (line) => {
    if (process.env.HYPERNOVA_TSNET_DEBUG) log('tsnet:', line)
  })
  p.on('error', (e) => log('tsnet helper error', e))
  p.on('exit', (code, signal) => {
    if (child !== p) return
    child = null
    helper = null
    if (stopped) return
    // Crashed, or killed by something else: back with a growing pause, at most a minute.
    const wait = Math.min(60_000, 2_000 * 2 ** Math.min(restarts++, 5))
    log(`tsnet helper exited (${code ?? signal}); again in ${wait / 1000}s`)
    restartTimer = setTimeout(() => {
      restartTimer = null
      if (!stopped && mode === 'builtIn') spawnHelper(bin)
    }, wait)
    restartTimer.unref()
    broadcast({ t: 'tsnet', tsnet: publicState(), tailnet: tailnetNow() })
  })
}

async function stopHelper(): Promise<void> {
  if (restartTimer) {
    clearTimeout(restartTimer)
    restartTimer = null
  }
  if (healthyTimer) {
    clearTimeout(healthyTimer)
    healthyTimer = null
  }
  const p = child
  if (!p) return
  child = null
  helper = null
  await new Promise<void>((resolve) => {
    const t = setTimeout(() => {
      p.kill('SIGKILL')
      resolve()
    }, 5_000)
    p.once('exit', () => {
      clearTimeout(t)
      resolve()
    })
    p.kill('SIGTERM')
  })
}

/** Stops the helper for good (the daemon is going, or a test is done). */
export async function stopTsnet(): Promise<void> {
  stopped = true
  await stopHelper()
}

function command(c: 'login' | 'logout' | 'status'): boolean {
  if (!child?.stdin?.writable) return false
  child.stdin.write(JSON.stringify({ c }) + '\n')
  return true
}

/** Asks for a (new) sign-in link; the helper reports it in its next status. */
export const tsnetLogin = (): boolean => command('login')
/** Signs the node out (another account, or a fresh start): a new sign-in link follows. */
export const tsnetLogout = (): boolean => command('logout')

/** The setting, kept, and the helper started or stopped at once. */
export async function setBuiltInTailscale(on: boolean): Promise<TsnetState> {
  settings.builtInTailscale = on
  saveSettings()
  if (!on) {
    mode = 'off'
    reason = 'Built-in Tailscale is off in the settings.'
    await stopHelper()
    broadcast({ t: 'tsnet', tsnet: publicState(), tailnet: tailnetNow() })
    return tsnetState()
  }
  return startTsnet()
}

/** A self-hoster's coordination server (Headscale), kept; the helper starts over against it. */
export async function setControlUrl(url: string | null): Promise<TsnetState> {
  if (url && !controlUrlOk(url)) throw new Error('A control server must be an https:// URL (plain http only on a private address).')
  // Kept as the URL parses it (lowercase host, no trailing slash), not as typed.
  settings.tailscaleControlUrl = url ? new URL(url).href.replace(/\/$/, '') : undefined
  saveSettings()
  await stopHelper()
  return startTsnet()
}

/**
 * The pairing link's tailnet hints: the computer's tailnet name and address, the account it signed
 * in with (so the phone can say "sign in as you@…") and the control server when it isn't
 * tailscale.com. From the built-in node, or from the Tailscale app when that is what serves.
 */
/**
 * Where the phone API is on the tailnet right now, with no waiting (the snapshot and `{t:"tsnet"}`
 * carry it): the built-in node's address while it serves, else the Tailscale app's `serve` as last
 * read; null when neither serves. A phone paired while the other one served (the Tailscale app,
 * then the built-in node after the app was quit) follows it, since its own node would otherwise
 * dial a machine that's gone. `control`: the coordination server (null: tailscale.com).
 */
export function tailnetNow(): { name: string; ip: string | null; url: string; control: string | null } | null {
  const url = tsnetBaseUrl()
  if (url && helper?.dnsName) return { name: helper.dnsName, ip: helper.ips?.find((x) => x.includes('.')) ?? null, url, control: controlUrl() }
  const sys = tailscaleCached()
  if (mode === 'system' && sys?.url && sys.dnsName) return { name: sys.dnsName, ip: sys.ip, url: sys.url, control: null }
  return null
}

export async function tailnetHints(): Promise<{ name: string | null; ip: string | null; account: string | null; control: string | null }> {
  if (mode === 'builtIn' && helper?.state === 'Running') {
    return { name: helper.dnsName ?? null, ip: helper.ips?.find((x) => x.includes('.')) ?? null, account: helper.account ?? null, control: controlUrl() }
  }
  const sys = await tailscaleStatus()
  if (sys.dnsName) return { name: sys.dnsName, ip: sys.ip, account: sys.account ?? null, control: null }
  return { name: null, ip: null, account: null, control: null }
}
