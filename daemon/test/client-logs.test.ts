// POST /v1/client-logs over HTTP (docs/PROTOCOL.md, "Client logs"; client-logs.ts): a client's debug log
// kept as untrusted text in <support>/client-logs/<device>/ under the server's own name (folders 0700,
// files 0600), only for a paired device, plain text only, 6 MB at most, a rate limit, the newest few per
// device, and 30 days. One way: nothing reads them back. `hypernova client-logs` finds them.
import { test, before, after, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, utimesSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { basename, dirname, join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-client-logs-'))
const SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_SUPPORT = SUPPORT
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
const { CLIENT_LOGS_DIR, KEEP_PER_DEVICE, MAX_CLIENT_LOG, RATE_MAX, reapClientLogs, resetClientLogSlots } = await import('../src/client-logs.ts')
const { clientLogLines, listClientLogs } = await import('../src/cli/client-logs.ts')
const { clientLogs, parseArgs } = await import('../src/cli/commands.ts')
const TOKEN = 'client-logs-test-device-token-0123456789'
const OTHER = 'client-logs-test-other-token-9876543210'
addDevice({ id: 'phone1', name: 'Pixel‮', model: 'Pixel 9', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)
addDevice({ id: 'phone2', name: 'Other', model: 'Other', createdAt: Date.now(), lastSeenAt: Date.now() }, OTHER)

let close: () => Promise<void>
before(() => {
  close = startServers()
})
after(async () => {
  await close?.()
  rmSync(scratch, { recursive: true, force: true })
})
beforeEach(() => {
  resetClientLogSlots()
  rmSync(CLIENT_LOGS_DIR, { recursive: true, force: true })
})

type Reply = { status: number; json: any }
/** POST to [path]: [body] whole with its length, or (an array) in chunks with no length. */
function send(body: Buffer | Buffer[], o: { path?: string; token?: string | null; type?: string | null; method?: string; headers?: Record<string, string> } = {}): Promise<Reply> {
  return new Promise((resolve, reject) => {
    const headers: Record<string, string | number> = { host: `127.0.0.1:${API}`, ...(o.headers ?? {}) }
    const type = o.type === undefined ? 'text/plain; charset=utf-8' : o.type
    if (type) headers['content-type'] = type
    const token = o.token === undefined ? TOKEN : o.token
    if (token) headers.authorization = `Bearer ${token}`
    if (Buffer.isBuffer(body)) headers['content-length'] = body.length
    const req = request({ host: '127.0.0.1', port: API, method: o.method ?? 'POST', path: o.path ?? '/v1/client-logs', headers }, (res) => {
      let s = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (s += c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, json: s ? JSON.parse(s) : null }))
    })
    // A refusal can come while it's still sending.
    req.on('error', (e: any) => (e.code === 'EPIPE' || e.code === 'ECONNRESET' ? resolve({ status: 0, json: null }) : reject(e)))
    if (Buffer.isBuffer(body)) req.end(body)
    else {
      for (const b of body) req.write(b)
      req.end()
    }
  })
}

const logsOf = (device: string) => (existsSync(join(CLIENT_LOGS_DIR, device)) ? readdirSync(join(CLIENT_LOGS_DIR, device)) : [])

test('kept as it came, under the device, in a name of the server\'s, folders 0700 and files 0600', async () => {
  const text = Buffer.from('2026-11-02 10:00:00.000 I app  started\n')
  const r = await send(text)
  assert.equal(r.status, 200, JSON.stringify(r.json))
  const l = r.json.log
  assert.equal(dirname(l.path), join(CLIENT_LOGS_DIR, 'phone1'))
  assert.equal(basename(l.path), l.name)
  assert.match(l.name, /^\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}-\d{3}Z-[0-9a-f]{6}\.log$/)
  assert.equal(l.size, text.length)
  assert.ok(Math.abs(l.at - Date.now()) < 60_000)
  assert.deepEqual(readFileSync(l.path), text)
  assert.equal(statSync(l.path).mode & 0o777, 0o600)
  assert.equal(statSync(dirname(l.path)).mode & 0o777, 0o700)
  assert.equal(statSync(CLIENT_LOGS_DIR).mode & 0o777, 0o700)
})

test('only a paired device: no token and a wrong one are refused, and nothing is kept', async () => {
  assert.equal((await send(Buffer.from('x'), { token: null })).status, 401)
  assert.equal((await send(Buffer.from('x'), { token: 'not-the-token' })).status, 401)
  assert.ok(!existsSync(CLIENT_LOGS_DIR))
})

test('one way: no route reads a log back, and another verb is an unknown route', async () => {
  const { log } = (await send(Buffer.from('x'))).json
  for (const method of ['GET', 'PUT', 'DELETE']) {
    assert.equal((await send(Buffer.alloc(0), { method })).status, 404, method)
    assert.equal((await send(Buffer.alloc(0), { method, path: `/v1/client-logs/phone1/${log.name}` })).status, 404, method)
  }
  assert.ok(existsSync(log.path))
})

test('plain text only', async () => {
  for (const type of ['application/json', 'application/octet-stream', 'text/plainish', null]) {
    const r = await send(Buffer.from('{}'), { type })
    assert.equal(r.status, 415, String(type))
    assert.equal(r.json.error.code, 'not_text')
  }
  assert.ok(!existsSync(CLIENT_LOGS_DIR))
})

test('6 MB at most: refused by its length, or once a stream of no length passes it, with nothing left behind', async () => {
  const big = await send(Buffer.alloc(MAX_CLIENT_LOG + 1, 65))
  assert.equal(big.status, 413)
  assert.equal(big.json.error.code, 'too_large')
  const chunk = Buffer.alloc(1024 * 1024, 65)
  const streamed = await send(Array.from({ length: 7 }, () => chunk))
  assert.equal(streamed.status, 413)
  assert.equal(streamed.json.error.code, 'too_large')
  assert.deepEqual(logsOf('phone1'), [])
  // Exactly the limit goes.
  const most = await send(Array.from({ length: 6 }, () => chunk))
  assert.equal(most.status, 200, JSON.stringify(most.json))
  assert.equal(most.json.log.size, MAX_CLIENT_LOG)
})

test('an empty one is refused', async () => {
  const r = await send(Buffer.alloc(0))
  assert.equal(r.status, 400)
  assert.equal(r.json.error.code, 'empty_log')
  assert.deepEqual(logsOf('phone1'), [])
})

test('the rate limit is per device, says how long to wait, and keeps nothing', async () => {
  for (let i = 0; i < RATE_MAX; i++) assert.equal((await send(Buffer.from('x'))).status, 200)
  const r = await send(Buffer.from('x'))
  assert.equal(r.status, 429)
  assert.equal(r.json.error.code, 'too_many_logs')
  assert.ok(r.json.error.retryAfterSeconds > 0 && r.json.error.retryAfterSeconds <= 3600)
  assert.equal((await send(Buffer.from('x'), { token: OTHER })).status, 200)
  assert.ok(logsOf('phone1').length <= KEEP_PER_DEVICE)
})

test('the client chooses no part of the path, whatever it says, and its text is only ever bytes', async () => {
  const hostile = Buffer.from('../../../../etc/passwd\n$(touch /tmp/hn-pwned)\n<script>\u0000\n')
  const r = await send(hostile, {
    path: '/v1/client-logs?name=../../../evil.sh&path=/tmp/evil&device=phone2',
    headers: { 'x-file-name': '../../evil.sh', 'content-disposition': 'attachment; filename="../../evil.sh"' },
  })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  assert.equal(dirname(r.json.log.path), join(CLIENT_LOGS_DIR, 'phone1'))
  assert.match(r.json.log.name, /^[\dTZ-]+-[0-9a-f]{6}\.log$/)
  assert.deepEqual(readFileSync(r.json.log.path), hostile)
  assert.ok(!existsSync(join(SUPPORT, 'evil.sh')))
  assert.ok(!existsSync('/tmp/hn-pwned'))
  assert.deepEqual(readdirSync(CLIENT_LOGS_DIR), ['phone1'])
  // Nothing of it reaches the daemon's own log either.
  const daemonLog = join(process.env.HYPERNOVA_LOGS!, 'daemon.log')
  if (existsSync(daemonLog)) assert.ok(!readFileSync(daemonLog, 'utf8').includes('hn-pwned'))
})

test('only the newest few per device stay; another device\'s are its own', async () => {
  const names: string[] = []
  for (let i = 0; i < KEEP_PER_DEVICE + 2; i++) {
    names.push((await send(Buffer.from(`log ${i}`))).json.log.name)
    await new Promise((r) => setTimeout(r, 3))
  }
  const other = (await send(Buffer.from('other'), { token: OTHER })).json.log
  const kept = logsOf('phone1').sort()
  assert.equal(kept.length, KEEP_PER_DEVICE)
  assert.deepEqual(kept, [...names].sort().slice(-KEEP_PER_DEVICE))
  assert.ok(existsSync(other.path))
})

test('past 30 days they go, a stopped upload\'s leftovers and an emptied folder with them', async () => {
  const fresh = (await send(Buffer.from('fresh'))).json.log
  const stale = (await send(Buffer.from('stale'), { token: OTHER })).json.log
  const part = join(dirname(stale.path), 'leftover.log.part')
  writeFileSync(part, 'x', { mode: 0o600 })
  const old = new Date(Date.now() - 31 * 24 * 3600_000)
  utimesSync(stale.path, old, old)
  utimesSync(part, old, old)
  reapClientLogs()
  assert.ok(existsSync(fresh.path))
  assert.ok(!existsSync(stale.path))
  assert.ok(!existsSync(dirname(stale.path)))
})

test('`hypernova client-logs` lists them newest first with their paths and never their contents', async () => {
  assert.deepEqual(parseArgs(['client-logs', '--latest']), { cmd: 'client-logs', flags: { latest: true }, rest: [] })
  const printed = () => {
    const out: string[] = []
    return { out, cli: { support: SUPPORT, sys: { say: (l = '') => out.push(l) } } as any }
  }
  // Nothing yet: it says where to look and how they get here.
  const none = printed()
  assert.equal(await clientLogs(none.cli, {}), 0)
  assert.match(none.out.join('\n'), /No client logs yet/)
  const noneLatest = printed()
  assert.equal(await clientLogs(noneLatest.cli, { latest: true }), 1)
  assert.deepEqual(noneLatest.out, [])

  const first = (await send(Buffer.from('SECRET-OF-THE-LOG first'))).json.log
  await new Promise((r) => setTimeout(r, 20))
  const second = (await send(Buffer.from('SECRET-OF-THE-LOG second'), { token: OTHER })).json.log
  // A stopped upload isn't a log.
  writeFileSync(join(dirname(first.path), 'x.log.part'), 'y')
  const rows = listClientLogs(SUPPORT)
  assert.deepEqual(rows.map((r) => r.path), [second.path, first.path])
  assert.equal(rows[1]!.device, 'phone1')
  // The name a device gave itself has its controls taken out.
  assert.equal(rows[1]!.deviceName, 'Pixel')

  const table = printed()
  assert.equal(await clientLogs(table.cli, {}), 0)
  const text = table.out.join('\n')
  assert.ok(text.includes(first.path) && text.includes(second.path), text)
  assert.ok(text.indexOf(second.path) < text.indexOf(first.path))
  assert.ok(!text.includes('SECRET-OF-THE-LOG'))
  assert.deepEqual(clientLogLines(rows, SUPPORT).join('\n'), text)

  const latest = printed()
  assert.equal(await clientLogs(latest.cli, { latest: true }), 0)
  assert.deepEqual(latest.out, [second.path])

  const asJson = printed()
  assert.equal(await clientLogs(asJson.cli, { json: true }), 0)
  assert.equal(JSON.parse(asJson.out.join(''))[0].path, second.path)
})
