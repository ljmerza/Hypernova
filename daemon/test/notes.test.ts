// "Note: …" from the glasses: a line in <Nebula>/notes.md with the time and the project, under a
// heading per day, and no Claude run. On free ports with scratch state, like nebula.test.ts.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-notes-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
mkdirSync(join(process.env.HOME, 'Programming'), { recursive: true })
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
// The LAN listener (lan.ts) takes a free port: test files run side by side.
process.env.HYPERNOVA_LAN_PORT = '0'

const { startServers } = await import('../src/http.ts')
const { addDevice } = await import('../src/store.ts')
const { addNote } = await import('../src/notes.ts')
const TOKEN = 'test-device-token-notes-0123456789ab'
addDevice({ id: 'glasses', name: 'Glasses', model: 'G2', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)
const HOME = process.env.HOME
const NOTES = join(HOME, 'Programming/Nebula/notes.md')

let close: () => Promise<void>
before(async () => {
  close = startServers()
  for (let i = 0; i < 50; i++) {
    try {
      if ((await call('GET', '/v1/health')).status === 200) return
    } catch {}
    await new Promise((r) => setTimeout(r, 20))
  }
})
after(async () => {
  await close?.()
  rmSync(scratch, { recursive: true, force: true })
})

function call(method: string, path: string, body?: unknown): Promise<{ status: number; json: any }> {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? undefined : JSON.stringify(body)
    const req = request({ host: '127.0.0.1', port: API, method, path, headers: { authorization: `Bearer ${TOKEN}`, ...(data ? { 'content-type': 'application/json' } : {}) } }, (res) => {
      let s = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (s += c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, json: s ? JSON.parse(s) : null }))
    })
    req.on('error', reject)
    req.end(data)
  })
}

const day = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
const hhmm = (d: Date) => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`

test('a note is a line in Nebula/notes.md, with the time and the project; Nebula is made for it', async () => {
  assert.equal(existsSync(NOTES), false)
  const r = await call('POST', '/v1/nebula/notes', { text: 'Comets should fade\nfaster at night.', project: 'hypernova' })
  assert.equal(r.status, 200)
  assert.equal(r.json.note.path, '~/Programming/Nebula/notes.md')
  assert.match(r.json.note.line, /^- \d\d:\d\d · hypernova · Comets should fade faster at night\.$/)
  const text = readFileSync(NOTES, 'utf8')
  assert.match(text, /^# Notes\n\n/)
  assert.ok(text.endsWith(`\n\n## ${day(new Date())}\n\n${r.json.note.line}\n`))
  // Without a project, the line has none; the same day's heading isn't repeated.
  assert.equal((await call('POST', '/v1/nebula/notes', { text: 'Buy a bell for the bike' })).status, 200)
  const again = readFileSync(NOTES, 'utf8')
  assert.equal(again.match(/^## /gm)?.length, 1)
  assert.match(again, /\n- \d\d:\d\d · Buy a bell for the bike\n$/)
})

test('a new day gets its own heading; a project name is one plain line', () => {
  const tomorrow = new Date(Date.now() + 26 * 3600_000)
  const r = addNote('Ask the owner about the ring', 'zero · stems\nnext', tomorrow)
  assert.equal(r.line, `- ${hhmm(tomorrow)} · zero - stems next · Ask the owner about the ring`)
  const text = readFileSync(NOTES, 'utf8')
  assert.equal(text.match(/^## /gm)?.length, 2)
  assert.ok(text.endsWith(`\n\n## ${day(tomorrow)}\n\n${r.line}\n`))
})

test('an empty or overlong note is refused, and only POST adds one', async () => {
  const empty = await call('POST', '/v1/nebula/notes', { text: '   ' })
  assert.equal(empty.status, 400)
  assert.equal(empty.json.error.code, 'empty')
  const long = await call('POST', '/v1/nebula/notes', { text: 'x'.repeat(4001) })
  assert.equal(long.status, 413)
  assert.equal(long.json.error.code, 'too_long')
  assert.equal((await call('GET', '/v1/nebula/notes')).status, 405)
})

test('notes follow Nebula when it moves', async () => {
  assert.equal((await call('POST', '/v1/nebula', { path: '~/Ideas/Nebula' })).status, 200)
  const r = await call('POST', '/v1/nebula/notes', { text: 'Moved' })
  assert.equal(r.json.note.path, '~/Ideas/Nebula/notes.md')
  assert.match(readFileSync(join(HOME, 'Ideas/Nebula/notes.md'), 'utf8'), /· Moved\n$/)
  await call('POST', '/v1/nebula', { path: join(HOME, 'Programming/Nebula') })
})
