// Pairing the same phone again (docs/PROTOCOL.md, "Pairing"): an install that sends its installId
// takes its old place in the device list instead of adding a twin; another install doesn't.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-pair-'))
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

const { startServers, startPairing, cancelPairing } = await import('../src/http.ts')
const store = await import('../src/store.ts')

let close: () => Promise<void>
before(async () => {
  close = startServers()
  await new Promise((r) => setTimeout(r, 200))
})
after(async () => {
  await close?.()
  rmSync(scratch, { recursive: true, force: true })
})

function post(path: string, body: object, token?: string): Promise<{ status: number; json: any }> {
  return new Promise((resolve, reject) => {
    const req = request({ host: '127.0.0.1', port: API, method: 'POST', path, headers: { host: `127.0.0.1:${API}`, 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) } }, (res) => {
      let data = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (data += c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, json: data ? JSON.parse(data) : null }))
    })
    req.on('error', reject)
    req.end(JSON.stringify(body))
  })
}
const pair = async (body: object) => {
  const { code } = await startPairing(true)
  const r = await post('/v1/pair', { code, ...body })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  return r.json as { token: string; deviceId: string }
}

test('pairing the same install again replaces its device; its old token stops working', async () => {
  const install = 'install-0123456789abcdef'
  const first = await pair({ deviceName: 'Pixel', model: 'Pixel 9', installId: install })
  const second = await pair({ deviceName: 'Pixel', model: 'Pixel 9', installId: install })
  const mine = store.listDevices().filter((d) => d.installId === install)
  assert.equal(mine.length, 1)
  assert.equal(mine[0]!.id, second.deviceId)
  assert.equal(store.deviceForToken(first.token), null)
  assert.ok(store.deviceForToken(second.token))
  // Another install on another phone, the same name or not: a device of its own.
  await pair({ deviceName: 'Pixel', model: 'Pixel 9', installId: 'install-fedcba9876543210' })
  // An app that sends none (an older one), and another app with the same id as the phone app: kept apart.
  await pair({ deviceName: 'Pixel', model: 'Pixel 9' })
  await pair({ deviceName: 'Pixel', model: 'Pixel 9', installId: install, client: 'other-app' })
  assert.equal(store.listDevices().length, 4)
  assert.equal(store.listDevices().filter((d) => d.installId === install).length, 2)
})

test('a code the Mac stopped showing stops pairing at once; with no code, every live code ends', async () => {
  const shown = await startPairing(true)
  assert.equal(cancelPairing(shown.code), 1)
  const r = await post('/v1/pair', { code: shown.code, deviceName: 'Pixel' })
  assert.equal(r.status, 403)
  assert.equal(r.json.error.code, 'code_expired')
  assert.equal(cancelPairing(shown.code), 0)
  await startPairing(true)
  await startPairing(true)
  assert.equal(cancelPairing(), 2)
})
