// The demo server (demo/README.md): answers a phone like a real Hypernova server, from the made-up
// replies in demo/data/, for taking screenshots. It runs nothing and changes nothing: no Claude Code,
// no sessions, no state but its own pairing. One process per invented server, on loopback only.
//
//   node demo/server.ts                 every server in demo/data, on its own port
//   node demo/server.ts atlas           just that one
//
// The pairing and the mutual TLS are the daemon's own code (daemon/src/tls.ts, store.ts), run against
// a support folder of the demo's. daemon/src/lan.ts isn't imported: it brings in the hub, and with
// it everything a real server does. What it does for a connection is short, and is `certified` here.
import { spawn, type ChildProcess } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { createServer, type Server } from 'node:https'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { createRequire } from 'node:module'
import { homedir } from 'node:os'
import { join, resolve as resolvePath, sep } from 'node:path'
import type { Duplex } from 'node:stream'
import type { TLSSocket } from 'node:tls'
import { resolve } from './placeholders.ts'

const DATA = join(import.meta.dirname, 'data')
const DAEMON = join(import.meta.dirname, '..', 'daemon')
/** Ports clear of the real daemon's 47321-47323, of 5000-5010, and of each other. Override: HYPERNOVA_DEMO_PORT, the first of them. */
const FIRST_PORT = Number(process.env.HYPERNOVA_DEMO_PORT ?? 47341)
/** Where each server keeps its certificates and its paired phones. Never the real daemon's. */
const HOME = process.env.HYPERNOVA_DEMO_HOME ?? join(homedir(), '.cache', 'hypernova-demo')
/** What a phone is told to dial: the emulator's name for this computer's loopback. */
const PHONE_SEES = process.env.HYPERNOVA_DEMO_ADDRESS ?? '10.0.2.2'

const serverNames = () => readdirSync(DATA, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name).sort()

// ── Starting them: one process each, as each has its own support folder and the daemon reads that once ──
if (!process.env.HYPERNOVA_DEMO_CHILD) {
  const all = serverNames()
  const wanted = process.argv.slice(2)
  for (const w of wanted) if (!all.includes(w)) throw new Error(`No such demo server: ${w} (there's ${all.join(', ')})`)
  const kids: ChildProcess[] = []
  const stop = () => kids.forEach((k) => k.kill('SIGTERM'))
  process.on('SIGINT', stop)
  process.on('SIGTERM', stop)
  all.forEach((name, i) => {
    if (wanted.length && !wanted.includes(name)) return
    const support = join(HOME, name)
    const kid = spawn(process.execPath, [import.meta.filename], {
      stdio: 'inherit',
      env: {
        ...process.env,
        HYPERNOVA_DEMO_CHILD: name,
        HYPERNOVA_DEMO_CHILD_PORT: String(FIRST_PORT + i),
        // What daemon/src/util.ts and store.ts read as they load: nothing of the real install's.
        HYPERNOVA_SUPPORT: support,
        HYPERNOVA_LOGS: join(support, 'logs'),
      },
    })
    kid.on('exit', (code, signal) => {
      if (code) {
        console.error(`demo server ${name} exited with ${code}`)
        process.exitCode = code
      }
      if (!signal) stop()
    })
    kids.push(kid)
  })
} else {
  await serve(process.env.HYPERNOVA_DEMO_CHILD, Number(process.env.HYPERNOVA_DEMO_CHILD_PORT))
}

// ── One server ───────────────────────────────────────────────────────────
async function serve(name: string, port: number): Promise<void> {
  const support = resolvePath(process.env.HYPERNOVA_SUPPORT ?? '')
  if (!support.startsWith(resolvePath(HOME) + sep)) throw new Error(`Refusing to run on ${support}: not under ${HOME}`)
  mkdirSync(support, { recursive: true, mode: 0o700 })

  // Imported now: they read HYPERNOVA_SUPPORT as they load.
  const { serverIdentity, issueDeviceCert, certFingerprint, TlsError } = await import('../daemon/src/tls.ts')
  const { addDevice, deviceForCert, deviceForToken, getDevice, hasDeviceCert, listDevices, removeDevice, setDeviceCert } = await import('../daemon/src/store.ts')
  const { randomToken } = await import('../daemon/src/util.ts')
  const { WebSocketServer } = createRequire(join(DAEMON, 'package.json'))('ws') as typeof import('ws')

  const id = serverIdentity()
  if (!id) throw new Error('No certificates: is openssl installed?')

  const dir = join(DATA, name)
  /** A reply as a real server would send it now: read each time, so an edit shows without a restart. */
  const file = (path: string, fallback?: unknown): unknown => {
    const full = join(dir, path)
    if (!existsSync(full)) {
      if (fallback === undefined) throw new Refused(404, 'not_in_demo', `The demo has no ${path}.`)
      return fallback
    }
    return resolve(JSON.parse(readFileSync(full, 'utf8')))
  }
  const macOf = () => (file('snapshot.json') as { mac: { id: string; name: string } }).mac

  // The one live pairing code, in a file for scripts/demo-shots.sh; a new one when it's used.
  let code = ''
  const newCode = () => {
    code = randomToken(16)
    const q = new URLSearchParams({ v: '1', u: `https://${PHONE_SEES}:${port}`, c: code, n: macOf().name, fp: id.fingerprint, lan: PHONE_SEES, lp: String(port) })
    const link = `hypernova://pair?${q}`
    writeFileSync(join(support, 'pair-link'), link + '\n', { mode: 0o600 })
    return link
  }

  /** The phone's device certificate, as lan.ts's lanDevice reads one. */
  const certified = (socket: Duplex) => {
    const s = socket as TLSSocket
    if (!s.encrypted || !s.authorized) return null
    const c = s.getPeerCertificate()
    if (!c?.raw) return null
    const d = deviceForCert(certFingerprint(c.raw))
    return d && d.id === c.subject?.CN ? d : null
  }

  const snapshotFor = (deviceId: string | null): unknown => ({
    ...(file('snapshot.json') as object),
    // The listener's own details: what the real server says of itself, so not in the data.
    lan: { port, addresses: [PHONE_SEES], fingerprint: id.fingerprint, deviceCert: hasDeviceCert(deviceId ? getDevice(deviceId) : null) },
  })

  async function route(req: IncomingMessage, url: URL, deviceId: string | null): Promise<unknown> {
    const m = req.method ?? 'GET'
    const path = url.pathname
    if (path === '/v1/health') return { ok: true, protocol: 1, name: macOf().name }
    if (path === '/v1/pair' && m === 'POST') {
      const b = (await body(req)) as { code?: unknown; deviceName?: unknown; model?: unknown; installId?: unknown; appVersion?: unknown }
      if (String(b.code ?? '') !== code) throw new Refused(403, 'code_expired', 'This pairing code has expired or was already used.')
      newCode()
      const installId = typeof b.installId === 'string' ? b.installId : undefined
      for (const d of listDevices()) if (installId && d.installId === installId) removeDevice(d.id)
      const token = randomToken(32)
      const device = randomToken(8)
      addDevice({ id: device, name: String(b.deviceName ?? 'Phone').slice(0, 60), model: String(b.model ?? '').slice(0, 60), createdAt: Date.now(), lastSeenAt: Date.now(), ...(installId ? { installId } : {}) }, token)
      return { token, deviceId: device, mac: macOf() }
    }
    if (!deviceId) throw new Refused(401, 'unauthorized', 'This phone is not paired with this server.')
    if (path === '/v1/device/cert' && m === 'POST') {
      const b = (await body(req)) as { csr?: unknown }
      try {
        const issued = issueDeviceCert(deviceId, String(b.csr ?? ''))
        setDeviceCert(deviceId, issued.certFingerprint, issued.expiresAt)
        return { cert: issued.cert.toString('base64'), ca: issued.ca.toString('base64'), fingerprint: issued.fingerprint }
      } catch (e) {
        if (e instanceof TlsError) throw new Refused(e.code === 'no_tls' ? 503 : 400, e.code, e.message)
        throw e
      }
    }
    if (path === '/v1/usage/refresh' && m === 'POST') return { usage: (file('snapshot.json') as { usage: unknown }).usage }
    if (m !== 'GET') throw new Refused(404, 'not_in_demo', 'The demo server only shows things.')
    if (path === '/v1/snapshot') return snapshotFor(deviceId)
    if (path === '/v1/usage') return (file('snapshot.json') as { usage: unknown }).usage
    if (path === '/v1/stats') {
      const range = url.searchParams.get('range') ?? 'week'
      if (!/^(today|week|month|ytd|all)$/.test(range)) throw new Refused(400, 'bad_range', 'No such range.')
      return file(`stats-${range}.json`)
    }
    if (path === '/v1/models') return file('models.json')
    if (path === '/v1/commands') return file('commands.json')
    const runs = /^\/v1\/automations\/([\w-]+)\/runs$/.exec(path)
    if (runs) return file(`automations/${runs[1]}-runs.json`, { runs: [], more: false })
    const s = /^\/v1\/sessions\/([\w-]+)\/(.+)$/.exec(path)
    if (s) return sessionReply(s[1]!, s[2]!, url)
    throw new Refused(404, 'not_in_demo', `The demo has no ${path}.`)
  }

  function sessionReply(sessionId: string, what: string, url: URL): unknown {
    const at = (f: string, fallback?: unknown) => file(`sessions/${sessionId}/${f}`, fallback)
    switch (what) {
      case 'events': {
        const all = at('events.json', { events: [] }) as { events: unknown[] }
        const limit = Number(url.searchParams.get('limit')) || all.events.length
        return { ...all, events: all.events.slice(-limit) }
      }
      case 'agents': return at('agents.json', { agents: [], workflows: [] })
      case 'usage': return at('usage.json')
      case 'todos': return at('todos.json', { todos: [], updatedAt: null })
      case 'changes': return at('changes.json')
      case 'changes/diff': {
        const want = url.searchParams.get('path')
        const sdir = join(dir, 'sessions', sessionId)
        const names = existsSync(sdir) ? readdirSync(sdir).filter((n) => /^diff.*\.json$/.test(n)) : []
        for (const n of names) {
          const d = at(n) as { path: string }
          if (d.path === want) return d
        }
        throw new Refused(404, 'unchanged', 'That file has no changes.')
      }
    }
    const agent = /^agents\/([\w-]+)\/events$/.exec(what)
    if (agent) return at(`agent-${agent[1]}-events.json`, { events: [], cursor: null, full: true })
    throw new Refused(404, 'not_in_demo', `The demo has no ${what} for that session.`)
  }

  const handler = async (req: IncomingMessage, res: ServerResponse) => {
    const url = new URL(req.url ?? '/', 'http://x')
    const auth = String(req.headers.authorization ?? '')
    const device = auth.startsWith('Bearer ') ? deviceForToken(auth.slice(7)) : null
    try {
      // The local listener's rule (docs/PROTOCOL.md, "Local network"): health, pairing and the
      // certificate route are open; everything else wants this device's certificate.
      const open = (req.method === 'GET' && url.pathname === '/v1/health') || (req.method === 'POST' && (url.pathname === '/v1/pair' || url.pathname === '/v1/device/cert'))
      const cert = certified(req.socket)
      if (!cert && !open) throw new Refused(401, 'device_cert_required', 'This route needs your device certificate on the local network.')
      if (cert && device && cert.id !== device.id) throw new Refused(401, 'wrong_device', 'That token belongs to another device.')
      send(res, 200, await route(req, url, device?.id ?? null))
    } catch (e) {
      if (e instanceof Refused) {
        // What the app asks for and the demo hasn't got: how to find what a new screen needs.
        if (e.code === 'not_in_demo') console.log(`${name}: no reply for ${req.method} ${url.pathname}${url.search}`)
        send(res, e.status, { error: { code: e.code, message: e.message } })
      } else {
        console.error(`${name}: ${req.method} ${url.pathname}:`, e)
        send(res, 500, { error: { code: 'demo_failed', message: String(e) } })
      }
    }
  }

  // As daemon/src/lan.ts sets its listener up: the server's own certificate, and client
  // certificates asked for and checked against the device CA, a phone without one still let in.
  const server: Server = createServer({ key: id.key, cert: id.cert, ca: [id.ca], requestCert: true, rejectUnauthorized: false, minVersion: 'TLSv1.2' }, (req, res) => void handler(req, res))
  server.on('tlsClientError', () => {})
  const sockets = new WebSocketServer({ noServer: true, perMessageDeflate: true })
  server.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url ?? '/', 'http://x')
    const auth = String(req.headers.authorization ?? '')
    const token = auth.startsWith('Bearer ') ? auth.slice(7) : (url.searchParams.get('token') ?? '')
    const cert = certified(socket)
    const device = token ? deviceForToken(token) : null
    if (url.pathname !== '/v1/ws' || !cert || !device || cert.id !== device.id) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n')
      socket.destroy()
      return
    }
    sockets.handleUpgrade(req, socket, head, (ws) => {
      ws.send(JSON.stringify({ t: 'snapshot', snapshot: snapshotFor(device.id) }))
      const ping = setInterval(() => ws.readyState === ws.OPEN && ws.send('{"t":"ping"}'), 25_000)
      ws.on('close', () => clearInterval(ping))
      ws.on('error', () => {})
    })
  })
  server.on('error', (e) => {
    console.error(`${name}: can't listen on ${port}:`, e.message)
    process.exit(1)
  })
  // Loopback only: nothing here is for the network. The emulator comes in as 10.0.2.2.
  server.listen(port, '127.0.0.1', () => console.log(`${name}: https://127.0.0.1:${port}, pair with ${newCode()}`))
  const stop = () => {
    for (const ws of sockets.clients) ws.terminate()
    server.closeAllConnections()
    server.close(() => process.exit(0))
  }
  process.on('SIGTERM', stop)
  process.on('SIGINT', stop)
}

class Refused extends Error {
  status: number
  code: string
  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

function send(res: ServerResponse, status: number, value: unknown): void {
  const text = JSON.stringify(value)
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(text) })
  res.end(text)
}

async function body(req: IncomingMessage): Promise<unknown> {
  const parts: Buffer[] = []
  for await (const p of req) parts.push(p as Buffer)
  const text = Buffer.concat(parts).toString('utf8')
  try {
    return text ? JSON.parse(text) : {}
  } catch {
    return {}
  }
}
