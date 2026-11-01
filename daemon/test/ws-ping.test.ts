// The live feed's keep-alive (docs/PROTOCOL.md, "Live feed"): every phone socket hears
// `{"t":"ping"}` every 25 s after its snapshot, the phone's `{"t":"pong"}` is taken without a word,
// and the socket stays open through both. The phone declares a socket silent for 70 s dead, so the
// pings are what keep a quiet feed alive. The interval is lowered here (http.ts, wsTiming).
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { WebSocket } from 'ws'

const scratch = mkdtempSync(join(tmpdir(), 'hn-ping-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
mkdirSync(process.env.HOME, { recursive: true })
const fakeClaude = join(scratch, 'claude-bin')
writeFileSync(fakeClaude, '#!/bin/sh\necho "9.9.9 (Claude Code)"\n')
chmodSync(fakeClaude, 0o755)
process.env.CLAUDE_BIN = fakeClaude

const freePort = () =>
  new Promise<number>((resolve) => {
    const s = createServer()
    s.listen(0, '127.0.0.1', () => {
      const port = (s.address() as { port: number }).port
      s.close(() => resolve(port))
    })
  })
const API = await freePort()
process.env.HYPERNOVA_API_PORT = String(API)
process.env.HYPERNOVA_LOCAL_PORT = String(await freePort())
process.env.HYPERNOVA_LAN_PORT = '0'

const { startServers, wsTiming } = await import('../src/http.ts')
const { addDevice, settings } = await import('../src/store.ts')
settings.lanAccess = false
const TOKEN = 'ping-test-device-token-0123456789abcdef'
addDevice({ id: 'phone', name: 'Pixel', model: 'Pixel 9', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)

// The real interval, before this file lowers it.
const DEFAULT_PING_MS = wsTiming.pingMs

let close: () => Promise<void>
before(async () => {
  close = startServers()
  for (let i = 0; i < 100; i++) {
    try {
      if ((await fetch(`http://127.0.0.1:${API}/v1/health`)).ok) return
    } catch {}
    await new Promise((r) => setTimeout(r, 20))
  }
})
after(async () => {
  await close?.()
  rmSync(scratch, { recursive: true, force: true })
})

/** A phone's socket, with every message it hears and when, from its open. */
function phone(): Promise<{ ws: WebSocket; heard: { at: number; m: any }[]; openedAt: number; closed: () => boolean }> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${API}/v1/ws`, { headers: { authorization: `Bearer ${TOKEN}` } })
    const heard: { at: number; m: any }[] = []
    let closed = false
    let openedAt = 0
    ws.on('message', (d) => heard.push({ at: Date.now(), m: JSON.parse(String(d)) }))
    ws.on('close', () => (closed = true))
    ws.once('open', () => {
      openedAt = Date.now()
      resolve({ ws, heard, openedAt, closed: () => closed })
    })
    ws.once('error', reject)
  })
}

async function until(what: string, cond: () => boolean, ms = 5_000): Promise<void> {
  const t0 = Date.now()
  while (!cond()) {
    if (Date.now() - t0 > ms) throw new Error(`timed out waiting for ${what}`)
    await new Promise((r) => setTimeout(r, 10))
  }
}

test('a phone socket hears {t:"ping"} every 25 s after its snapshot, takes the pong and stays open', async () => {
  assert.equal(DEFAULT_PING_MS, 25_000, 'the phone counts on a ping every 25 s (its watchdog: 70 s)')
  wsTiming.pingMs = 150
  try {
    const p = await phone()
    const pings = () => p.heard.filter((h) => h.m.t === 'ping')
    await until('three pings', () => pings().length >= 3)
    // The snapshot first, always; then the pings, on their interval and not before it.
    assert.equal(p.heard[0]!.m.t, 'snapshot')
    assert.deepEqual(pings()[0]!.m, { t: 'ping' }, 'exactly {t:"ping"}, nothing more')
    assert.ok(pings()[0]!.at - p.openedAt >= 140, `the first ping waits its interval (${pings()[0]!.at - p.openedAt} ms)`)
    for (let i = 1; i < 3; i++) {
      const gap = pings()[i]!.at - pings()[i - 1]!.at
      assert.ok(gap >= 100 && gap < 1_000, `one interval between pings (${gap} ms)`)
    }

    // The phone answers each with a pong: taken without a word (no error, no close), the pings go on.
    const before = pings().length
    p.ws.send('{"t":"pong"}')
    p.ws.send('{"t":"pong"}')
    await until('more pings after the pongs', () => pings().length >= before + 2)
    assert.equal(p.closed(), false)
    assert.equal(p.ws.readyState, WebSocket.OPEN)
    // The daemon still answers this phone as before.
    const r = await fetch(`http://127.0.0.1:${API}/v1/snapshot`, { headers: { authorization: `Bearer ${TOKEN}` } })
    assert.equal(r.status, 200)

    // Closed by the phone: its pings stop with it (nothing is sent to a closed socket).
    p.ws.close()
    await until('closed', () => p.closed())
    const after = p.heard.length
    await new Promise((r) => setTimeout(r, 400))
    assert.equal(p.heard.length, after)
  } finally {
    wsTiming.pingMs = DEFAULT_PING_MS
  }
})

test('each socket keeps its own ping: a second phone feed starts its interval at its own open', async () => {
  wsTiming.pingMs = 200
  try {
    const a = await phone()
    await new Promise((r) => setTimeout(r, 120))
    const b = await phone()
    await until('a ping on each', () => a.heard.some((h) => h.m.t === 'ping') && b.heard.some((h) => h.m.t === 'ping'))
    const first = (p: typeof a) => p.heard.find((h) => h.m.t === 'ping')!.at - p.openedAt
    assert.ok(first(a) >= 190 && first(b) >= 190, `${first(a)} ms, ${first(b)} ms`)
    a.ws.close()
    b.ws.close()
    await until('both closed', () => a.closed() && b.closed())
  } finally {
    wsTiming.pingMs = DEFAULT_PING_MS
  }
})
