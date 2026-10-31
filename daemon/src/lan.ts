// The LAN listener (docs/PROTOCOL.md, "Local network (direct HTTPS)"): the phone API over HTTPS on
// the local network (or the owner's own VPN into it), the phone's only route. The phone pins the daemon's own certificate and
// presents its device certificate (tls.ts); this file decides which connections get in at all,
// which device a connection belongs to, what the Mac's private addresses are (for the snapshot and
// the pairing link), and how often the pairing endpoint may be tried from here.
import { createServer, type Server } from 'node:https'
import type { IncomingMessage, ServerResponse } from 'node:http'
import type { Duplex } from 'node:stream'
import { createServer as createTcpServer, type Server as TcpServer, type Socket } from 'node:net'
import type { TLSSocket } from 'node:tls'
import { networkInterfaces, type NetworkInterfaceInfo } from 'node:os'
import { log } from './util.ts'
import { deviceForCert, saveSettings, settings, type Device } from './store.ts'
import { certFingerprint, serverIdentity } from './tls.ts'
import { broadcast } from './hub.ts'
import { PLATFORM, lanByDefault, virtualInterface, wslBehindNatHere } from './platform.ts'

/** 0 takes a free port (tests); the snapshot says which. */
export const LAN_PORT = Number(process.env.HYPERNOVA_LAN_PORT ?? 47323)

type Handler = (req: IncomingMessage, res: ServerResponse) => void
type Upgrade = (req: IncomingMessage, socket: Duplex, head: Buffer) => void

// ── Who may connect ──────────────────────────────────────────────────────
/** "::ffff:192.168.1.2" (a v4 peer on the dual-stack socket) as "192.168.1.2". */
export function plainIp(ip: string | undefined): string {
  const s = ip ?? ''
  return s.startsWith('::ffff:') && s.includes('.') ? s.slice(7) : s
}

/**
 * Loopback, RFC 1918, link-local, CGNAT (100.64/10, which VPNs hand out) or IPv6 ULA: the
 * addresses a phone on the same network, the hotspot or a VPN comes from. Anything else is
 * the internet, which this listener is not for.
 */
export function privateAddress(ip: string | undefined): boolean {
  const s = plainIp(ip)
  if (!s) return false
  if (s.includes('.')) {
    const p = s.split('.').map(Number)
    if (p.length !== 4 || p.some((n) => !Number.isInteger(n) || n < 0 || n > 255)) return false
    const [a, b] = p as [number, number, number, number]
    if (a === 127 || a === 10) return true
    if (a === 192 && b === 168) return true
    if (a === 172 && b >= 16 && b <= 31) return true
    if (a === 169 && b === 254) return true
    if (a === 100 && b >= 64 && b <= 127) return true
    return false
  }
  const v6 = s.split('%')[0]!.toLowerCase()
  if (v6 === '::1') return true
  if (/^fe[89ab][0-9a-f]:/.test(v6)) return true // fe80::/10
  if (/^f[cd][0-9a-f]{2}:/.test(v6)) return true // fc00::/7
  return false
}

/** An IPv6 address's eight 16-bit groups, `::` filled in and any zone dropped; null when it isn't one. */
function v6Groups(ip: string): number[] | null {
  const halves = ip.split('%')[0]!.toLowerCase().split('::')
  if (halves.length > 2) return null
  const head = halves[0] ? halves[0].split(':') : []
  const tail = halves[1] ? halves[1].split(':') : []
  const fill = halves.length === 2 ? 8 - head.length - tail.length : 0
  if (halves.length === 2 ? fill < 1 : head.length !== 8) return null
  const all = [...head, ...Array<string>(fill).fill('0'), ...tail]
  return all.every((g) => /^[0-9a-f]{1,4}$/.test(g)) ? all.map((g) => parseInt(g, 16)) : null
}

/**
 * The Mac's addresses a phone on the same network can reach: private IPv4 first (Wi-Fi, the hotspot
 * bridge, Ethernet), then IPv6 ULA. Never loopback, link-local (useless without a zone) or the
 * Tailscale's 100.64/10 and fd7a:115c:a1e0::/48 (a Tailscale the owner runs on this machine for
 * something else: the phone doesn't come in that way), however the interface writes them. [ifaces]: tests.
 */
export function lanAddresses(ifaces: NodeJS.Dict<NetworkInterfaceInfo[]> = networkInterfaces()): string[] {
  const v4: string[] = []
  const v6: string[] = []
  for (const [name, list] of Object.entries(ifaces)) {
    if (virtualInterface(name)) continue
    for (const a of list ?? []) {
      if (a.internal) continue
      if (a.family === 'IPv4') {
        // RFC 1918 only, so never 100.64/10.
        const [x, y] = a.address.split('.').map(Number) as [number, number]
        if (x === 10 || (x === 192 && y === 168) || (x === 172 && y >= 16 && y <= 31)) v4.push(a.address)
      } else {
        const g = v6Groups(a.address)
        const ula = !!g && (g[0]! & 0xfe00) === 0xfc00
        const tailnet = !!g && g[0] === 0xfd7a && g[1] === 0x115c && g[2] === 0xa1e0
        if (ula && !tailnet) v6.push(a.address.split('%')[0]!)
      }
    }
  }
  return [...new Set([...v4, ...v6])]
}

/**
 * The addresses a phone is told (the snapshot, the pairing link, the private name's record): the
 * local network's, except behind WSL's NAT (platform.ts), where every address this Linux has is on
 * Windows' private network for it and no phone can reach one, whatever `lanAccess` says.
 */
export function advertisedAddresses(ifaces: NodeJS.Dict<NetworkInterfaceInfo[]> = networkInterfaces(), behindWslNat: boolean = wslBehindNatHere()): string[] {
  return behindWslNat ? [] : lanAddresses(ifaces)
}

/**
 * The one address a phone's browser is sent to (the pairing and download links, the name's record): the
 * Wi-Fi's rather than a VM bridge's or a VPN's, so IPv4 first, and not a gateway-looking .1 when
 * there is another.
 */
export function pickLanAddress(addresses: string[]): string | null {
  const v4 = addresses.filter((a) => a.includes('.'))
  return v4.find((x) => !x.endsWith('.1')) ?? v4[0] ?? addresses[0] ?? null
}

/**
 * The addresses a pairing link carries (`lan`): the IPv4 ones whenever there is one (the Wi-Fi's,
 * and what a VPN into the network routes), else the IPv6 ULAs. The snapshot still says them all
 * once the phone is paired; the link only has to get it in, and a shorter link is a smaller QR code.
 */
export function pairingAddresses(addresses: string[]): string[] {
  const v4 = addresses.filter((a) => a.includes('.'))
  return v4.length ? v4 : addresses
}

/** `https://<address>:<port>`, an IPv6 address in brackets: the listener at one of its addresses. */
export function lanOrigin(address: string, port: number): string {
  const a = plainIp(address).split('%')[0]!
  return `https://${a.includes(':') ? `[${a}]` : a}:${port}`
}

// ── The listener ─────────────────────────────────────────────────────────
export type LanInfo = { port: number; addresses: string[]; fingerprint: string }

/**
 * What the listener holds, as anyone on a café's Wi-Fi can reach it (tests lower these, then restart
 * the listener). A phone needs a few connections at once (its requests and uploads, the live feed, a
 * terminal, a preview's page): 32 per source address and 64 in all, more closed at accept. A TLS
 * handshake gets 10 s; then a request's headers must be in within 15 s (counted from the handshake
 * for the first, so a peer that says nothing goes too); an idle keep-alive connection closes after
 * 10 s. A whole request may take 15 minutes (a big attachment over a slow link).
 */
export const lanLimits = { connections: 64, perSource: 32, handshakeMs: 10_000, headersMs: 15_000, keepAliveMs: 10_000, requestMs: 15 * 60_000 }

/** The port's own listener (plain TCP: the source filter, the limits); the TLS server behind it never listens itself. */
let server: TcpServer | null = null
let pinnedServer: Server | null = null
let port = 0
let onRequest: Handler | null = null
let onUpgrade: Upgrade | null = null
let watcher: NodeJS.Timeout | null = null
let lastAddresses = ''

/**
 * The owner's setting; unset, on, except on a Linux server with a public address of its own (a
 * VPS), where the phone is never on its network and the port would face the internet, and in a
 * container, on a cloud VM and behind WSL's NAT, where no phone is on its network (platform.ts).
 */
export const lanEnabled = (): boolean => settings.lanAccess ?? lanByDefault(PLATFORM, networkInterfaces())

/** What the snapshot and the pairing link say; `null` while the listener is off or couldn't start. */
export function lanInfo(): LanInfo | null {
  const id = server && serverIdentity()
  return id ? { port, addresses: advertisedAddresses(), fingerprint: id.fingerprint } : null
}

/**
 * The paired device a LAN connection's client certificate belongs to: signed by our CA (Node checked
 * that in the handshake), the device's active one, for a device that still exists. The certificate is
 * read once per socket, but the device is looked up on every request, so forgetting a device (or
 * replacing its certificate) cuts it off on open connections too.
 */
const peerOf = new WeakMap<TLSSocket, { fp: string; cn: string }>()
export function lanDevice(socket: Duplex): Device | null {
  const s = socket as TLSSocket
  if (!s.encrypted || !s.authorized) return null
  let peer = peerOf.get(s)
  if (!peer) {
    const c = s.getPeerCertificate()
    const cn = c?.subject?.CN
    peer = { fp: c?.raw ? certFingerprint(c.raw) : '', cn: typeof cn === 'string' ? cn : '' }
    peerOf.set(s, peer)
  }
  if (!peer.fp) return null
  const d = deviceForCert(peer.fp)
  return d && d.id === peer.cn ? d : null
}

/** Starts the listener when `lanAccess` allows; keeps the handlers for a later start from the setting. */
export function startLan(handler: Handler, upgrade: Upgrade): void {
  onRequest = handler
  onUpgrade = upgrade
  if (lanEnabled()) listen()
  if (!watcher) {
    lastAddresses = advertisedAddresses().join(',')
    // Interfaces come and go (Wi-Fi joins, the hotspot starts): a cheap read every 5 s, told only on a change.
    watcher = setInterval(() => {
      const now = advertisedAddresses().join(',')
      if (now === lastAddresses) return
      lastAddresses = now
      if (server) broadcast({ t: 'lan', lan: lanInfo() })
    }, 5000)
    watcher.unref()
  }
}

/**
 * The TLS server behind the port: the daemon's own certificate, client certificates asked for and
 * checked against the device CA. It never listens; the port's listener hands it its connections.
 * It's told it's listening all the same: that's when Node starts the clock on requests' headers and
 * idle connections.
 */
function innerServer(): Server | null {
  const id = serverIdentity()
  if (!id || !onRequest || !onUpgrade) return null
  const L = lanLimits
  // The phone pins `cert`; `ca` plus requestCert makes Node check a client certificate against the
  // device CA in the handshake (socket.authorized), while rejectUnauthorized false lets a phone with no
  // certificate yet still reach health, pairing and the certificate route.
  const s = createServer(
    {
      key: id.key,
      cert: id.cert,
      ca: [id.ca],
      requestCert: true,
      rejectUnauthorized: false,
      minVersion: 'TLSv1.2',
      handshakeTimeout: L.handshakeMs,
      headersTimeout: L.headersMs,
      keepAliveTimeout: L.keepAliveMs,
      requestTimeout: L.requestMs,
      // How often Node looks for connections past headersTimeout (30 s by default).
      connectionsCheckingInterval: Math.min(5_000, L.headersMs),
    },
    onRequest,
  )
  s.on('tlsClientError', () => {})
  s.on('upgrade', onUpgrade)
  s.emit('listening')
  return s
}

function listen(): void {
  if (server || !onRequest || !onUpgrade) return
  const id = serverIdentity()
  const pinned = id && innerServer()
  if (!id || !pinned) return
  pinnedServer = pinned
  const L = lanLimits
  // Dropped at accept, before any TLS: anything but the local network, the hotspot and a VPN's
  // ranges, and a source already holding its share. The rest is handed, unread, to the TLS server
  // (whose handshake must finish within handshakeMs).
  const open = new Map<string, number>()
  const s = createTcpServer((sock: Socket) => {
    const ip = plainIp(sock.remoteAddress)
    const n = (open.get(ip) ?? 0) + 1
    if (!privateAddress(ip) || n > L.perSource) return void sock.destroy()
    open.set(ip, n)
    sock.once('close', () => {
      const left = (open.get(ip) ?? 1) - 1
      if (left > 0) open.set(ip, left)
      else open.delete(ip)
    })
    sock.on('error', () => {})
    if (!pinnedServer || sock.destroyed) return void sock.destroy()
    pinnedServer.emit('connection', sock)
  })
  s.maxConnections = L.connections
  s.on('error', (e) => {
    // Another program on the port, or no leave to bind: no local route, the rest of the daemon runs.
    log('lan listener error', e)
    if (server === s) server = null
    s.close()
  })
  server = s
  s.listen(LAN_PORT, () => {
    port = (s.address() as { port: number }).port
    log(`lan api on *:${port} (fingerprint ${id.fingerprint.slice(0, 16)}…)`)
    broadcast({ t: 'lan', lan: lanInfo() })
  })
}

/** Closes the port and every connection the TLS server behind it holds. */
function closeAll(): Promise<unknown> {
  const s = server
  const inner = pinnedServer
  server = pinnedServer = null
  if (inner) {
    inner.closeAllConnections()
    // Never listened, so close() only stops its clocks (and says it wasn't running).
    inner.close(() => {})
  }
  return s ? new Promise((r) => s.close(r)) : Promise.resolve()
}

export async function stopLan(): Promise<void> {
  if (!server) return
  await closeAll()
  if (watcher) {
    clearInterval(watcher)
    watcher = null
  }
}

/** The Mac's "lanAccess" setting: kept, and the listener started or stopped at once. */
export function setLanAccess(on: boolean): void {
  if (lanEnabled() === on && !!server === on) return
  settings.lanAccess = on
  saveSettings()
  if (on) listen()
  else if (server) {
    void closeAll()
    log('lan listener off')
    broadcast({ t: 'lan', lan: null })
  }
}

// ── Pairing from the local network ───────────────────────────────────────
// Anyone on the Wi-Fi can reach /v1/pair here: 5 wrong codes per source address in 10 minutes, then
// a wait. Nothing across sources, so one peer can't lock everyone out: the code is 16 random bytes,
// which no number of addresses guesses. The table forgets its oldest sources past MAX_SOURCES, so
// many addresses can't grow it either.
const WINDOW = 10 * 60_000
const PER_SOURCE = 5
const MAX_SOURCES = 1024
const wrongBySource = new Map<string, number[]>()

function prune(now: number): void {
  for (const [ip, ts] of wrongBySource) {
    const left = ts.filter((t) => now - t < WINDOW)
    if (left.length) wrongBySource.set(ip, left)
    else wrongBySource.delete(ip)
  }
}

export type PairGate = { ok: true } | { ok: false; retryAfterSeconds: number }
/** May this source try a pairing code now? */
export function pairAllowed(ip: string): PairGate {
  const now = Date.now()
  prune(now)
  const mine = wrongBySource.get(plainIp(ip)) ?? []
  if (mine.length < PER_SOURCE) return { ok: true }
  return { ok: false, retryAfterSeconds: Math.max(1, Math.ceil((mine[0]! + WINDOW - now) / 1000)) }
}
/** A wrong code from this source. */
export function pairFailed(ip: string): void {
  const key = plainIp(ip)
  const mine = wrongBySource.get(key) ?? []
  // Re-added at the end, so the first key is always the source that failed longest ago.
  wrongBySource.delete(key)
  wrongBySource.set(key, [...mine, Date.now()])
  while (wrongBySource.size > MAX_SOURCES) wrongBySource.delete(wrongBySource.keys().next().value!)
}
/** Tests: forget every wrong try. */
export function resetPairLimits(): void {
  wrongBySource.clear()
}
