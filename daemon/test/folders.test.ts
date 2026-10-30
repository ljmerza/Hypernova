// The new-session sheet's folder picker (docs/PROTOCOL.md, "Folders"): a folder's own folders, names
// only; the projects folder always, anywhere else with Browse outside projects on; never a key place
// or a folder of keys; dot-folders only when asked; its error codes.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, mkdirSync, mkdtempSync, realpathSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-test-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'home', 'Library', 'Application Support', 'Hypernova')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'home', '.claude')
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

const HOME = realpathSync(process.env.HOME)
// The owner's projects folder is outside the home folder (/media/cubxi/docker): so is this one.
const PROJECTS = join(scratch, 'docker')
const store = await import('../src/store.ts')
store.settings.projectsRoot = PROJECTS
const { startServers } = await import('../src/http.ts')
const files = await import('../src/files.ts')
const TOKEN = 'test-device-token-folders-0123456789'
store.addDevice({ id: 'phone', name: 'Phone', model: 'Test', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)

// The projects folder: folders, a file, a dot-folder, a folder of keys, links to a folder and a file.
for (const d of ['bin', 'env', 'volumes', 'projects/hypernova', 'Pokemon', '.git', '.ssh']) mkdirSync(join(PROJECTS, d), { recursive: true })
writeFileSync(join(PROJECTS, 'docker-compose.base.yml'), 'networks: {}\n')
symlinkSync(join(PROJECTS, 'projects'), join(PROJECTS, 'src'))
symlinkSync(join(PROJECTS, 'docker-compose.base.yml'), join(PROJECTS, 'base.yml'))
// The home folder: a folder of work, its key places, a link to one, and Claude Code's own folder.
mkdirSync(join(HOME, 'work', 'site'), { recursive: true })
mkdirSync(join(HOME, '.ssh'), { recursive: true })
mkdirSync(join(HOME, '.config', 'gh'), { recursive: true })
mkdirSync(join(HOME, '.config', 'nvim'), { recursive: true })
mkdirSync(process.env.CLAUDE_CONFIG_DIR!, { recursive: true })
symlinkSync(join(HOME, '.ssh'), join(HOME, 'keys'))
writeFileSync(join(HOME, 'notes.txt'), 'plain words')

let close: () => Promise<void>
before(async () => {
  close = startServers()
  for (let i = 0; i < 50; i++) {
    try {
      if ((await get('/v1/health')).status === 200) return
    } catch {}
    await new Promise((r) => setTimeout(r, 20))
  }
})
after(async () => {
  await close?.()
  rmSync(scratch, { recursive: true, force: true })
})

type Reply = { status: number; json: any }
function get(path: string): Promise<Reply> {
  return new Promise((resolve, reject) => {
    const req = request({ host: '127.0.0.1', port: API, method: 'GET', path, headers: { host: `127.0.0.1:${API}`, authorization: `Bearer ${TOKEN}` } }, (res) => {
      const chunks: Buffer[] = []
      res.on('data', (c) => chunks.push(c))
      res.on('end', () => {
        let json: any = null
        try {
          json = JSON.parse(Buffer.concat(chunks).toString('utf8'))
        } catch {}
        resolve({ status: res.statusCode ?? 0, json })
      })
    })
    req.on('error', reject)
    req.end()
  })
}
const q = (p: string) => encodeURIComponent(p)
const names = (r: Reply) => (r.json.folders as any[]).map((f) => f.name)

test('the projects folder: every folder directly in it, by name, never a file; dot-folders only when asked, never one of keys', async () => {
  const r = await get(`/v1/folders?path=${q(PROJECTS)}`)
  assert.equal(r.status, 200)
  assert.equal(r.json.path, realpathSync(PROJECTS))
  assert.equal(r.json.parent, realpathSync(scratch))
  assert.equal(r.json.home, HOME)
  // A link to a folder is one; a link to a file and the file aren't.
  assert.deepEqual(names(r), ['bin', 'env', 'Pokemon', 'projects', 'src', 'volumes'])
  assert.equal(r.json.truncated, false)
  assert.equal(r.json.total, 6)
  assert.deepEqual(Object.keys(r.json.folders[0]).sort(), ['name', 'path'], 'names and paths only')
  assert.equal(r.json.folders[0].path, join(realpathSync(PROJECTS), 'bin'))
  // Shown with hidden=1: .git, but never .ssh.
  const all = await get(`/v1/folders?path=${q(PROJECTS)}&hidden=1`)
  assert.deepEqual(names(all), ['.git', 'bin', 'env', 'Pokemon', 'projects', 'src', 'volumes'])
  // Inside it, deeper.
  assert.deepEqual(names(await get(`/v1/folders?path=${q(join(PROJECTS, 'projects'))}`)), ['hypernova'])
})

test('with Browse outside projects off: nothing outside the projects folder, and the phone is told the setting opens it', async () => {
  files.setBrowseOutside(false)
  for (const p of ['~', '/', HOME, '/etc']) {
    const r = await get(`/v1/folders?path=${q(p)}`)
    assert.equal(r.status, 403, p)
    assert.equal(r.json.error.code, 'outside_projects')
    assert.equal(r.json.error.setting, 'browseOutsideProjects')
    assert.equal(r.json.error.projectsRoot, PROJECTS)
  }
  // No path is the home folder.
  assert.equal((await get('/v1/folders')).json.error.code, 'outside_projects')
})

test('with Browse outside projects on: anywhere, from the home folder (`~`) to `/`; never a key place or a link to one', async () => {
  files.setBrowseOutside(true)
  try {
    const home = await get('/v1/folders')
    assert.equal(home.status, 200)
    assert.equal(home.json.path, HOME)
    // keys leads to ~/.ssh; Library holds the daemon's state, but isn't a key place itself.
    assert.deepEqual(names(home), ['Library', 'work'])
    assert.deepEqual(names(await get(`/v1/folders?path=${q('~')}`)), ['Library', 'work'])
    assert.deepEqual(names(await get(`/v1/folders?path=${q('~/work')}`)), ['site'])
    // Hidden ones: .config, but never .ssh or Claude Code's own folder; inside .config, never gh.
    assert.deepEqual(names(await get(`/v1/folders?hidden=1`)), ['.config', 'Library', 'work'])
    assert.deepEqual(names(await get(`/v1/folders?path=${q('~/.config')}&hidden=1`)), ['nvim'])
    // The key places themselves, however they're reached.
    for (const p of ['~/.ssh', join(HOME, 'keys'), process.env.CLAUDE_CONFIG_DIR!, process.env.HYPERNOVA_SUPPORT!, '~/.config/gh', join(PROJECTS, '.ssh')]) {
      const r = await get(`/v1/folders?path=${q(p)}`)
      assert.equal(r.status, 403, p)
      assert.equal(r.json.error.code, 'locked_folder', p)
    }
    // The top of the disk: no parent.
    const root = await get(`/v1/folders?path=${q('/')}`)
    assert.equal(root.status, 200)
    assert.equal(root.json.parent, null)
    assert.ok(names(root).includes('etc'))
  } finally {
    files.setBrowseOutside(false)
  }
})

test('a path that isn’t a folder: said with a code, never Node’s words', async () => {
  const gone = await get(`/v1/folders?path=${q(join(PROJECTS, 'nope'))}`)
  assert.equal(gone.status, 404)
  assert.equal(gone.json.error.code, 'not_found')
  assert.ok(!/ENOENT/.test(gone.json.error.message))
  const file = await get(`/v1/folders?path=${q(join(PROJECTS, 'docker-compose.base.yml'))}`)
  assert.equal(file.status, 400)
  assert.equal(file.json.error.code, 'not_a_dir')
  const relative = await get(`/v1/folders?path=${q('projects/hypernova')}`)
  assert.equal(relative.status, 400)
  assert.equal(relative.json.error.code, 'bad_path')
})
