// Nebula, where new sessions from the glasses are born: its folder (moved from the phone, kept in the
// home folder), and new projects in it, named in kebab-case, unique, and git init'ed. On free ports
// with scratch state, like http.test.ts.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-nebula-'))
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
const { kebab, uniqueIn } = await import('../src/nebula.ts')
const TOKEN = 'test-device-token-nebula-0123456789'
addDevice({ id: 'glasses', name: 'Glasses', model: 'G2', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)
const HOME = process.env.HOME

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

test('folder names are plain kebab-case, and unique among the folders there (as macOS compares them)', () => {
  assert.equal(kebab('Landing page — bakery, with menu!'), 'landing-page-bakery-with-menu')
  assert.equal(kebab('Café crème'), 'cafe-creme')
  assert.equal(kebab('../../etc'), 'etc')
  assert.equal(kebab('   '), 'session')
  assert.equal(kebab('x'.repeat(80)).length, 40)
  const dir = join(HOME, 'unique-test')
  mkdirSync(join(dir, 'landing-page'), { recursive: true })
  mkdirSync(join(dir, 'Landing-Page-2'))
  assert.equal(uniqueIn(dir, 'landing-page'), 'landing-page-3')
  assert.equal(uniqueIn(dir, 'blog'), 'blog')
  assert.equal(uniqueIn(join(HOME, 'missing'), 'blog'), 'blog')
})

test('Nebula is <projects>/Nebula until moved, and made only when the first project starts there', async () => {
  const r = await call('GET', '/v1/nebula')
  assert.equal(r.status, 200)
  assert.deepEqual(r.json.nebula, { path: '~/Programming/Nebula', default: '~/Programming/Nebula', exists: false })
  assert.equal(existsSync(join(HOME, 'Programming/Nebula')), false)
})

test('a new project in Nebula: kebab-case, unique, git init’ed; a second with the same name gets -2', async () => {
  const a = await call('POST', '/v1/projects', { name: 'landing-page-bakery', nebula: true })
  assert.equal(a.status, 200)
  assert.equal(a.json.project.path, join(HOME, 'Programming/Nebula/landing-page-bakery'))
  assert.equal(a.json.project.name, 'landing-page-bakery')
  assert.ok(existsSync(join(a.json.project.path, '.git')), 'git init’ed')
  const b = await call('POST', '/v1/projects', { name: 'Landing page bakery', nebula: true })
  assert.equal(b.json.project.name, 'landing-page-bakery-2')
  assert.equal((await call('GET', '/v1/nebula')).json.nebula.exists, true)
  // Without `nebula`, a project is made in the projects folder as before.
  const c = await call('POST', '/v1/projects', { name: 'plain', git: false })
  assert.equal(c.json.project.path, join(HOME, 'Programming/plain'))
  // Nebula holds projects; it isn't listed as one itself.
  const names = (await call('GET', '/v1/projects')).json.projects.map((p: { name: string }) => p.name)
  assert.ok(names.includes('plain') && !names.includes('Nebula'), names.join(', '))
})

test('Nebula moves to a folder inside the home folder, never outside it, into Library or a hidden folder', async () => {
  for (const path of ['/tmp/elsewhere', '~', '~/../other', '~/Library/Nebula', '~/.config/nebula', 'relative/path', '']) {
    const r = await call('POST', '/v1/nebula', { path })
    assert.equal(r.status, 400, path)
    assert.equal(r.json.error.code, 'bad_path', path)
  }
  writeFileSync(join(HOME, 'a-file'), 'x')
  assert.equal((await call('POST', '/v1/nebula', { path: '~/a-file' })).status, 400)
  const moved = await call('POST', '/v1/nebula', { path: '~/Ideas/Nebula' })
  assert.equal(moved.status, 200)
  assert.deepEqual(moved.json.nebula, { path: '~/Ideas/Nebula', default: '~/Programming/Nebula', exists: false })
  const p = await call('POST', '/v1/projects', { name: 'weather-bot', nebula: true })
  assert.equal(p.json.project.path, join(HOME, 'Ideas/Nebula/weather-bot'))
  // Back to the default (an absolute path works too).
  const back = await call('POST', '/v1/nebula', { path: join(HOME, 'Programming/Nebula') })
  assert.equal(back.json.nebula.path, '~/Programming/Nebula')
})
