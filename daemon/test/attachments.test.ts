// POST /v1/attachments over HTTP (docs/PROTOCOL.md, "Attachments"; attachments.ts): a picked file's
// bytes kept in <support>/attachments/<12 hex>/<name> (folder 0700, file 0600, the name made safe),
// only with the device's token, and 30 MB at most, whether the phone says its length or streams it.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { Agent, request } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { basename, dirname, join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-attach-'))
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

const { startServers } = await import('../src/http.ts')
const { addDevice } = await import('../src/store.ts')
const { ATTACH_DIR, MAX_ATTACHMENT } = await import('../src/attachments.ts')
const TOKEN = 'attach-test-device-token-0123456789abcdef'
addDevice({ id: 'device', name: 'Pixel', model: 'Pixel 9', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)

let close: () => Promise<void>
before(() => {
  close = startServers()
})
after(async () => {
  await close?.()
  rmSync(scratch, { recursive: true, force: true })
})

type Reply = { status: number; json: any }
/**
 * POST /v1/attachments?name=[name]: [body] whole with its length, or (an array) in chunks with no
 * length, as a stream of unknown size arrives.
 */
function upload(name: string, body: Buffer | Buffer[], token: string | null = TOKEN): Promise<Reply> {
  return new Promise((resolve, reject) => {
    const headers: Record<string, string | number> = { host: `127.0.0.1:${API}`, 'content-type': 'application/octet-stream' }
    if (token) headers.authorization = `Bearer ${token}`
    if (!Array.isArray(body)) headers['content-length'] = body.length
    // A connection of its own each time: one whose refused body wasn't read isn't used again.
    const req = request({ host: '127.0.0.1', port: API, method: 'POST', path: `/v1/attachments?name=${encodeURIComponent(name)}`, headers, agent: false }, (res) => {
      let s = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (s += c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, json: s ? JSON.parse(s) : null }))
    })
    // The daemon may answer and close before a refused body is all sent.
    req.on('error', (e: any) => (e.code === 'EPIPE' || e.code === 'ECONNRESET' ? undefined : reject(e)))
    if (Array.isArray(body)) {
      for (const b of body) req.write(b)
      req.end()
    } else req.end(body)
  })
}

test('a picked file is kept as it came, under a safe name, for the device alone', async () => {
  const bytes = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0, 1, 2, 3, 255])
  const r = await upload('../../screen "shot".png', bytes)
  assert.equal(r.status, 200, JSON.stringify(r.json))
  const a = r.json.attachment
  assert.equal(a.name, 'screen -shot-.png')
  assert.equal(a.size, bytes.length)
  assert.equal(a.mime, 'image/png')
  assert.equal(dirname(dirname(a.path)), ATTACH_DIR)
  assert.match(basename(dirname(a.path)), /^[0-9a-f]{12}$/)
  assert.deepEqual(readFileSync(a.path), bytes)
  assert.equal(statSync(a.path).mode & 0o777, 0o600)
  assert.equal(statSync(dirname(a.path)).mode & 0o777, 0o700)
  // Two of the same name never meet.
  const again = (await upload('../../screen "shot".png', Buffer.from('x'))).json.attachment
  assert.notEqual(again.path, a.path)
  // No name: "file".
  assert.equal((await upload('', Buffer.from('x'))).json.attachment.name, 'file')
})

/** One request over [agent] (kept alive): its status and body. */
function over(agent: Agent, method: string, path: string, body?: Buffer[]): Promise<{ status: number; conn: string | undefined }> {
  return new Promise((resolve, reject) => {
    const headers: Record<string, string> = { host: `127.0.0.1:${API}`, authorization: `Bearer ${TOKEN}` }
    const req = request({ host: '127.0.0.1', port: API, method, path, headers, agent }, (res) => {
      res.resume()
      res.on('end', () => resolve({ status: res.statusCode ?? 0, conn: res.headers.connection }))
    })
    req.on('error', (e: any) => (e.code === 'EPIPE' || e.code === 'ECONNRESET' ? undefined : reject(e)))
    if (body) for (const b of body) req.write(b)
    req.end()
  })
}

test('a refused upload closes its connection, so the next request on a kept-alive agent never hangs', async () => {
  const agent = new Agent({ keepAlive: true, maxSockets: 1 })
  try {
    const chunk = Buffer.alloc(1024 * 1024, 7)
    const refused = await over(agent, 'POST', '/v1/attachments?name=s.bin', Array.from({ length: 31 }, () => chunk))
    assert.equal(refused.status, 413)
    assert.equal(refused.conn, 'close')
    // The next request on the same agent answers (before the fix it waited behind the unread body).
    const next = await Promise.race([
      over(agent, 'GET', '/v1/snapshot'),
      new Promise<never>((_, rej) => setTimeout(() => rej(new Error('the next request hung')), 5_000)),
    ])
    assert.equal(next.status, 200)
  } finally {
    agent.destroy()
  }
})

test('only with the token', async () => {
  assert.equal((await upload('a.txt', Buffer.from('x'), null)).status, 401)
  assert.equal((await upload('a.txt', Buffer.from('x'), 'not-the-token')).status, 401)
})

test('30 MB at most: refused at once by its length, or once a stream of no length passes it, and nothing is kept', async () => {
  const big = await upload('big.bin', Buffer.alloc(MAX_ATTACHMENT + 1))
  assert.equal(big.status, 413)
  assert.equal(big.json.error.code, 'too_large')
  const chunk = Buffer.alloc(1024 * 1024, 7)
  const streamed = await upload('stream.bin', Array.from({ length: 31 }, () => chunk))
  assert.equal(streamed.status, 413)
  assert.equal(streamed.json.error.code, 'too_large')
  // Exactly 30 MB goes.
  const most = await upload('most.bin', Array.from({ length: 30 }, () => chunk))
  assert.equal(most.status, 200, JSON.stringify(most.json))
  assert.equal(most.json.attachment.size, MAX_ATTACHMENT)
  // The refused stream left no file behind.
  const { readdirSync } = await import('node:fs')
  const names = readdirSync(ATTACH_DIR).flatMap((d) => readdirSync(join(ATTACH_DIR, d)))
  assert.ok(!names.includes('stream.bin') && !names.includes('big.bin'), names.join(', '))
})
