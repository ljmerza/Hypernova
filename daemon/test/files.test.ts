// The phone's files (docs/PROTOCOL.md, "Files"): which folders it may browse (`session.browsable`),
// listings (huge folders cut, secrets and outside links marked), a file to view or download
// (ranges, names, types), previews that never serve keys, and error codes the phone acts on.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, closeSync, existsSync, mkdirSync, mkdtempSync, openSync, realpathSync, rmSync, symlinkSync, writeFileSync, ftruncateSync } from 'node:fs'
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

const HOME = process.env.HOME
const PROJECTS = join(HOME, 'Programming')
const APP = join(PROJECTS, 'app')
mkdirSync(join(APP, 'src'), { recursive: true })
const store = await import('../src/store.ts')
store.settings.projectsRoot = PROJECTS
const { startServers } = await import('../src/http.ts')
const hub = await import('../src/hub.ts')
const files = await import('../src/files.ts')
const { ATTACH_DIR } = await import('../src/attachments.ts')
const TOKEN = 'test-device-token-files-0123456789ab'
store.addDevice({ id: 'phone', name: 'Phone', model: 'Test', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)

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
  try {
    chmodSync(join(APP, 'locked'), 0o755)
  } catch {}
  rmSync(scratch, { recursive: true, force: true })
})

type Reply = { status: number; headers: import('node:http').IncomingHttpHeaders; body: Buffer; json: any }
function send(method: string, path: string, headers: Record<string, string> = {}, body?: string): Promise<Reply> {
  return new Promise((resolve, reject) => {
    const req = request({ host: '127.0.0.1', port: API, method, path, headers: { host: `127.0.0.1:${API}`, authorization: `Bearer ${TOKEN}`, ...headers } }, (res) => {
      const chunks: Buffer[] = []
      res.on('data', (c) => chunks.push(c))
      res.on('end', () => {
        const body = Buffer.concat(chunks)
        let json: any = null
        try {
          json = JSON.parse(body.toString('utf8'))
        } catch {}
        resolve({ status: res.statusCode ?? 0, headers: res.headers, body, json })
      })
    })
    req.on('error', reject)
    req.end(body)
  })
}
const get = (path: string, headers: Record<string, string> = {}) => send('GET', path, headers)
const q = (p: string) => encodeURIComponent(p)

test('with Browse outside projects off: only the projects folder; the snapshot says so', async () => {
  hub.upsertSession('f0000000-0000-4000-8000-000000000001', { cwd: HOME, origin: 'terminal' })
  hub.upsertSession('f0000000-0000-4000-8000-000000000002', { cwd: APP, origin: 'terminal' })
  // A folder that holds the home folder is no root either, whoever ran a session there.
  hub.upsertSession('f0000000-0000-4000-8000-000000000003', { cwd: '/', origin: 'terminal' })
  const s = (await get('/v1/snapshot')).json.sessions as any[]
  const of = (cwd: string) => s.find((x) => x.cwd === cwd)
  assert.equal(of(HOME).browsable, false)
  assert.equal(of(HOME).outsideProjects, true, 'the setting would open it')
  assert.equal(of(APP).browsable, true)
  assert.equal(of(APP).outsideProjects, false)
  assert.equal(of('/').browsable, false)
  assert.equal(of('/').outsideProjects, false)
  const r = await get(`/v1/files?path=${q(HOME)}`)
  assert.equal(r.status, 403)
  assert.equal(r.json.error.code, 'outside_projects')
  assert.equal(r.json.error.projectsRoot, PROJECTS)
  // The real next step: the setting would open it, so the phone offers it.
  assert.equal(r.json.error.setting, 'browseOutsideProjects')
  const etc = await get(`/v1/files?path=${q('/etc')}`)
  assert.equal(etc.json.error.code, 'outside_projects')
  assert.equal(etc.json.error.setting, undefined, 'the setting wouldn’t open /etc')
  // Claude Code's own folder and the daemon's, never: key places.
  assert.equal((await get(`/v1/files?path=${q(process.env.CLAUDE_CONFIG_DIR!)}`)).json.error.code, 'locked_folder')
  assert.equal((await get(`/v1/files?path=${q(process.env.HYPERNOVA_SUPPORT!)}`)).json.error.code, 'locked_folder')
  // A folder gone is no folder to browse; one in a place macOS guards (Documents, Desktop, a disk)
  // isn't even looked at until the phone asks (that would ask the owner on the Mac).
  assert.equal(files.browsable(join(APP, 'gone')), false)
  if (process.platform === 'darwin') {
    assert.equal(files.macGuarded(join(HOME, 'Documents', 'site')), true)
    assert.equal(files.browsable(join(HOME, 'Documents', 'site')), false, 'outside the projects folder')
    assert.equal(files.folderView(join(HOME, 'Documents', 'site')).outsideProjects, true)
    assert.equal(files.macGuarded(APP), false)
  }
})

test('Browse outside projects: on, sessions’ folders in the home folder open read-only; never outside it, its key places, keys, or a way round', async () => {
  const seen: any[] = []
  hub.tapBroadcast((m) => seen.push(m))
  // The home folder: a session in it and one in a folder of its own; keys, keychains, a browser's
  // profile, a key file, and plain notes.
  const WORK = join(HOME, 'work', 'site')
  mkdirSync(WORK, { recursive: true })
  mkdirSync(process.env.CLAUDE_CONFIG_DIR!, { recursive: true })
  writeFileSync(join(WORK, 'index.html'), '<h1>site</h1>')
  mkdirSync(join(HOME, '.ssh'), { recursive: true })
  writeFileSync(join(HOME, '.ssh', 'id_ed25519'), 'key')
  writeFileSync(join(HOME, '.ssh', 'config'), 'Host *')
  mkdirSync(join(HOME, '.aws'), { recursive: true })
  writeFileSync(join(HOME, '.aws', 'config'), '[default]')
  mkdirSync(join(HOME, '.config', 'gh'), { recursive: true })
  writeFileSync(join(HOME, '.config', 'gh', 'hosts.yml'), 'token: x')
  writeFileSync(join(HOME, '.config', 'page.html'), '<p>hi</p>')
  mkdirSync(join(HOME, 'Library', 'Keychains'), { recursive: true })
  writeFileSync(join(HOME, 'Library', 'Keychains', 'login.keychain-db'), 'k')
  mkdirSync(join(HOME, 'Library', 'Application Support', 'Google', 'Chrome', 'Default'), { recursive: true })
  writeFileSync(join(HOME, 'Library', 'Application Support', 'Google', 'Chrome', 'Default', 'Cookies'), 'c')
  mkdirSync(join(HOME, 'Library', 'Logs'), { recursive: true })
  writeFileSync(join(HOME, '.env'), 'TOKEN=1')
  writeFileSync(join(HOME, 'notes.txt'), 'plain words')
  // Links from a session's folder: to the keys, to a browser's profile, out of the home folder, to a key file.
  symlinkSync(join(HOME, '.ssh'), join(WORK, 'keys'))
  symlinkSync(join(HOME, 'Library', 'Application Support', 'Google', 'Chrome'), join(WORK, 'chrome'))
  symlinkSync('/etc', join(WORK, 'etc'))
  symlinkSync(join(HOME, '.aws', 'config'), join(WORK, 'aws-config'))
  hub.upsertSession('f0000000-0000-4000-8000-000000000011', { cwd: WORK, origin: 'terminal' })
  hub.upsertSession('f0000000-0000-4000-8000-000000000012', { cwd: '/etc', origin: 'terminal' })
  hub.upsertSession('f0000000-0000-4000-8000-000000000013', { cwd: join(HOME, '.ssh'), origin: 'terminal' })
  const snap = async () => (await get('/v1/snapshot')).json
  const sessionAt = async (cwd: string) => ((await snap()).sessions as any[]).find((x) => x.cwd === cwd)
  const code = async (route: 'files' | 'file', p: string) => (await get(`/v1/${route}?path=${q(p)}`)).json?.error?.code

  // Off: the session's folder is outside the projects folder, and the phone is told the setting opens it.
  assert.equal((await snap()).mac.files.browseOutsideProjects, false)
  assert.equal((await sessionAt(WORK)).browsable, false)
  assert.equal((await sessionAt(WORK)).outsideProjects, true)
  assert.equal((await get(`/v1/files?path=${q(WORK)}`)).json.error.setting, 'browseOutsideProjects')
  assert.equal(await code('file', join(HOME, 'notes.txt')), 'outside_projects')
  assert.equal((await get('/v1/mac/files')).json.files.browseOutsideProjects, false)
  assert.equal((await send('POST', '/v1/mac/files', { 'content-type': 'application/json' }, JSON.stringify({ browseOutsideProjects: 'yes' }))).json.error.code, 'bad_value')

  // On: said to every phone at once, with the sessions it opens.
  const on = await send('POST', '/v1/mac/files', { 'content-type': 'application/json' }, JSON.stringify({ browseOutsideProjects: true }))
  try {
    assert.equal(on.status, 200)
    assert.equal(on.json.files.browseOutsideProjects, true)
    assert.equal(store.settings.browseOutsideProjects, true, 'saved')
    assert.ok(seen.some((m) => m.t === 'files' && m.files.browseOutsideProjects === true))
    assert.ok(seen.some((m) => m.t === 'session' && m.session.cwd === WORK && m.session.browsable === true), 'the session’s browsable changes live')
    assert.ok(seen.some((m) => m.t === 'session' && m.session.cwd === HOME && m.session.browsable === true))
    assert.equal((await snap()).mac.files.browseOutsideProjects, true)
    assert.equal((await sessionAt(WORK)).browsable, true)
    assert.equal((await sessionAt(HOME)).browsable, true, 'the home folder itself')
    // Outside the home folder, or in a key place: never.
    assert.equal((await sessionAt('/etc')).browsable, false)
    assert.equal((await sessionAt('/etc')).outsideProjects, false)
    assert.equal((await sessionAt(join(HOME, '.ssh'))).browsable, false)
    assert.equal((await sessionAt(join(HOME, '.ssh'))).outsideProjects, false)
    const etc = await get(`/v1/files?path=${q('/etc')}`)
    assert.equal(etc.json.error.code, 'outside_projects')
    assert.equal(etc.json.error.setting, undefined)

    // The home folder: its key places are locked rows, the rest is there.
    const home = await get(`/v1/files?path=${q(HOME)}`)
    assert.equal(home.status, 200)
    const at = (l: any, n: string) => l.entries.find((e: any) => e.name === n)
    assert.equal(at(home.json, '.ssh').locked, 'folder')
    assert.equal(at(home.json, '.aws').locked, 'folder')
    assert.equal(at(home.json, '.claude').locked, 'folder', 'Claude Code’s own folder')
    assert.equal(at(home.json, '.env').locked, 'secret')
    assert.equal(at(home.json, 'Library').locked, undefined)
    assert.equal(at(home.json, '.config').locked, undefined)
    assert.equal(at(home.json, 'notes.txt').locked, undefined)
    const library = await get(`/v1/files?path=${q(join(HOME, 'Library'))}`)
    assert.equal(at(library.json, 'Keychains').locked, 'folder')
    assert.equal(at(library.json, 'Application Support').locked, 'folder', 'browsers’ profiles, the daemon’s state')
    assert.equal(at(library.json, 'Logs').locked, undefined)
    assert.equal(at((await get(`/v1/files?path=${q(join(HOME, '.config'))}`)).json, 'gh').locked, 'folder')
    // Nothing inside a key place, by any road: as asked, in another case, by `..`, through a link.
    for (const p of [
      join(HOME, '.ssh'),
      join(HOME, 'Library', 'Keychains'),
      join(HOME, 'Library', 'Application Support', 'Google', 'Chrome'),
      join(HOME, 'LIBRARY', 'keychains'),
      join(WORK, '..', '..', '.ssh'),
      join(WORK, 'keys'),
      join(WORK, 'chrome', 'Default'),
      process.env.HYPERNOVA_SUPPORT!,
    ]) assert.equal(await code('files', p), 'locked_folder', p)
    for (const p of [join(HOME, '.ssh', 'config'), join(HOME, '.aws', 'config'), join(WORK, 'keys', 'id_ed25519'), join(WORK, 'aws-config'), join(HOME, '.config', 'gh', 'hosts.yml'), join(HOME, 'Library', 'Keychains', 'login.keychain-db')]) {
      assert.equal(await code('file', p), 'locked_folder', p)
      assert.equal((await get(`/v1/file?path=${q(p)}&download=1`)).status, 403, p)
    }
    // Keys stay keys, out of a key place too.
    assert.equal(await code('file', join(HOME, '.env')), 'secret_file')
    // Out of the home folder by `..` or a link: outside.
    assert.equal(await code('files', join(WORK, '..', '..', '..')), 'outside_projects')
    assert.equal(await code('files', join(WORK, 'etc')), 'outside_projects')
    assert.equal(await code('file', join(WORK, 'etc', 'hosts')), 'outside_projects')
    // The session's folder: its links marked by where they lead.
    const work = await get(`/v1/files?path=${q(WORK)}`)
    assert.equal(work.status, 200)
    assert.equal(at(work.json, 'keys').locked, 'folder')
    assert.equal(at(work.json, 'chrome').locked, 'folder')
    assert.equal(at(work.json, 'etc').locked, 'outside')
    assert.equal(at(work.json, 'aws-config').locked, 'secret')
    assert.equal(at(work.json, 'index.html').locked, undefined)
    // Read-only, but readable and downloadable.
    const notes = await get(`/v1/file?path=${q(join(HOME, 'notes.txt'))}&download=1`)
    assert.equal(notes.status, 200)
    assert.equal(notes.body.toString(), 'plain words')
    // A preview there works, and never serves a key place under its folder.
    const pv = await send('POST', '/v1/preview', { 'content-type': 'application/json' }, JSON.stringify({ path: join(HOME, '.config', 'page.html') }))
    assert.equal(pv.status, 200)
    const base = new URL(pv.json.url).pathname.replace(/page\.html$/, '')
    assert.equal((await send('GET', base + 'page.html', { authorization: '' })).status, 200)
    assert.equal((await send('GET', base + 'gh/hosts.yml', { authorization: '' })).status, 404)
    // A preview of the home folder itself still isn't (it would serve all of it).
    assert.equal((await send('POST', '/v1/preview', { 'content-type': 'application/json' }, JSON.stringify({ path: join(HOME, 'notes.txt') }))).json.error.code, 'outside_projects')
  } finally {
    const off = await send('POST', '/v1/mac/files', { 'content-type': 'application/json' }, JSON.stringify({ browseOutsideProjects: false }))
    assert.equal(off.json.files.browseOutsideProjects, false)
  }
  // Off again: closed at once.
  assert.equal((await sessionAt(WORK)).browsable, false)
  assert.equal(await code('files', HOME), 'outside_projects')
  assert.equal(await code('file', join(HOME, 'notes.txt')), 'outside_projects')
  // The Mac's own route: the same setting, in its status too.
  const local = (method: string, path: string, body?: unknown) =>
    new Promise<any>((resolve, reject) => {
      const data = body === undefined ? undefined : JSON.stringify(body)
      const req = request({ host: '127.0.0.1', port: Number(process.env.HYPERNOVA_LOCAL_PORT), method, path, headers: { authorization: `Bearer ${store.adminToken}`, ...(data ? { 'content-type': 'application/json' } : {}) } }, (res) => {
        let text = ''
        res.on('data', (c) => (text += c))
        res.on('end', () => resolve(JSON.parse(text)))
      })
      req.on('error', reject)
      req.end(data)
    })
  await local('POST', '/local/settings', { browseOutsideProjects: true })
  assert.equal((await local('GET', '/local/status')).files.browseOutsideProjects, true)
  assert.equal((await sessionAt(WORK)).browsable, true)
  await local('POST', '/local/settings', { browseOutsideProjects: false })
  assert.equal((await local('GET', '/local/status')).files.browseOutsideProjects, false)
  for (const id of ['11', '12', '13']) hub.upsertSession(`f0000000-0000-4000-8000-0000000000${id}`, { cwd: APP })
})

test('the key places are the home folder’s, whatever their case, and include the daemon’s state and Claude Code’s', () => {
  const real = (p: string) => join(realpathSync(HOME), p)
  for (const p of ['.ssh', '.gnupg', '.aws', '.azure', '.config/gcloud', '.config/gh', '.kube', '.docker', '.password-store', 'Library/Keychains', 'Library/Cookies', 'Library/Mail', 'Library/Messages', 'Library/Application Support/Google/Chrome', '.mozilla', '.config/google-chrome', '.codex', '.gemini', '.config/github-copilot', '.config/configstore', '.config/rclone']) {
    assert.equal(files.keyPlace(real(p)), true, p)
    assert.equal(files.keyPlace(real(p).toUpperCase()), true, `${p} in capitals`)
    assert.equal(files.keyPlace(real(join(p, 'deep', 'inside'))), true, `inside ${p}`)
  }
  mkdirSync(process.env.CLAUDE_CONFIG_DIR!, { recursive: true })
  assert.equal(files.keyPlace(realpathSync(process.env.CLAUDE_CONFIG_DIR!)), true)
  assert.equal(files.keyPlace(realpathSync(process.env.HYPERNOVA_SUPPORT!)), true)
  for (const p of ['', 'notes.txt', 'Library', 'Library/Logs', '.config', 'Programming/app', '.sshd', 'work/.ssh-notes']) assert.equal(files.keyPlace(real(p)), false, p || 'the home folder')
  // The reach of a folder: the projects folder, the home folder, or nowhere.
  assert.equal(files.reach(realpathSync(APP)), 'projects')
  assert.equal(files.reach(realpathSync(HOME)), 'home')
  assert.equal(files.reach(real('.ssh')), 'none')
  assert.equal(files.reach('/etc'), 'none')
  assert.equal(files.reach('/'), 'none')
})

test('a listing: folders first, keys and links that lead outside marked, the folder’s own errors coded', async () => {
  writeFileSync(join(APP, '.env'), 'KEY=1\n')
  writeFileSync(join(APP, 'README'), 'Plain words\n')
  writeFileSync(join(APP, 'tool'), Buffer.from([0x7f, 0x45, 0x4c, 0x46, 0, 0, 1]))
  writeFileSync(join(APP, 'b.md'), '# b\n')
  symlinkSync('/etc', join(APP, 'etc-link'))
  const r = await get(`/v1/files?path=${q(APP)}`)
  assert.equal(r.status, 200)
  assert.equal(r.json.truncated, false)
  const names = r.json.entries.map((e: any) => e.name)
  assert.equal(names[0], 'etc-link') // a link to a folder is a folder
  assert.ok(names.indexOf('src') < names.indexOf('b.md'))
  const by = (n: string) => r.json.entries.find((e: any) => e.name === n)
  assert.equal(by('.env').locked, 'secret')
  assert.equal(by('etc-link').locked, 'outside')
  assert.equal(by('b.md').locked, undefined)
  assert.equal(by('README').mime, 'text/plain; charset=utf-8')
  assert.equal(by('tool').mime, 'application/octet-stream')

  const gone = await get(`/v1/files?path=${q(join(APP, 'nope'))}`)
  assert.equal(gone.status, 404)
  assert.equal(gone.json.error.code, 'not_found')
  assert.ok(!/ENOENT/.test(gone.json.error.message))
  const file = await get(`/v1/files?path=${q(join(APP, 'b.md'))}`)
  assert.equal(file.status, 400)
  assert.equal(file.json.error.code, 'not_a_dir')
  mkdirSync(join(APP, 'locked'))
  chmodSync(join(APP, 'locked'), 0o000)
  const locked = await get(`/v1/files?path=${q(join(APP, 'locked'))}`)
  assert.equal(locked.status, 403)
  assert.equal(locked.json.error.code, 'unreadable')
  mkdirSync(join(APP, '.ssh'))
  assert.equal((await get(`/v1/files?path=${q(join(APP, '.ssh'))}`)).json.error.code, 'secret_file')
})

test('a huge folder is cut at 2,000 entries, and listing it doesn’t hold the daemon up', async () => {
  const big = join(APP, 'big')
  mkdirSync(big)
  for (let i = 0; i < 12_000; i++) closeSync(openSync(join(big, `f${String(i).padStart(5, '0')}.txt`), 'w'))
  mkdirSync(join(big, 'zz-folder'))
  let worst = 0
  let last = performance.now()
  const tick = setInterval(() => {
    const now = performance.now()
    worst = Math.max(worst, now - last)
    last = now
  }, 5)
  const l = await files.listDir(big)
  clearInterval(tick)
  assert.equal(l.truncated, true)
  assert.equal(l.total, 12_001)
  assert.equal(l.entries.length, files.MAX_ENTRIES)
  assert.equal(l.entries[0]!.name, 'zz-folder')
  assert.ok(worst < 250, `the event loop stalled ${Math.round(worst)} ms`)
})

test('a file: its codes (gone, outside, keys, too large, a folder), then ranges, names and types for a download', async () => {
  const code = async (p: string, extra = '') => (await get(`/v1/file?path=${q(p)}${extra}`)).json?.error?.code
  assert.equal(await code(join(APP, 'missing.txt')), 'not_found')
  assert.equal(await code(join(HOME, 'notes.txt')), 'outside_projects')
  assert.equal(await code(join(APP, '.env')), 'secret_file')
  writeFileSync(join(APP, 'server.key'), 'k')
  assert.equal(await code(join(APP, 'server.key')), 'secret_file')
  assert.equal(await code(join(APP, 'src')), 'is_dir')
  assert.equal(await code(join(ATTACH_DIR, 'abcdef012345', 'photo.jpg')), 'attachment_gone')

  // 21 MB (sparse): too large to view, fine to download.
  const big = join(APP, 'build.apk')
  const fd = openSync(big, 'w')
  ftruncateSync(fd, 21 * 1024 * 1024)
  closeSync(fd)
  const view = await get(`/v1/file?path=${q(big)}`)
  assert.equal(view.status, 413)
  assert.equal(view.json.error.code, 'too_large')
  assert.equal(view.json.error.size, 21 * 1024 * 1024)
  assert.equal(view.json.error.max, files.VIEW_MAX)
  const head = await send('HEAD', `/v1/file?path=${q(big)}&download=1`)
  assert.equal(head.status, 200)
  assert.equal(head.headers['content-length'], String(21 * 1024 * 1024))
  assert.equal(head.headers['accept-ranges'], 'bytes')
  assert.equal(head.headers['content-type'], 'application/vnd.android.package-archive')

  // A dropped download carries on from where it stopped.
  const name = 'Отчёт «итог».csv'
  writeFileSync(join(APP, name), '0123456789abcdef')
  const part = await get(`/v1/file?path=${q(join(APP, name))}&download=1`, { range: 'bytes=10-' })
  assert.equal(part.status, 206)
  assert.equal(part.headers['content-range'], 'bytes 10-15/16')
  assert.equal(part.body.toString(), 'abcdef')
  const disp = String(part.headers['content-disposition'])
  assert.match(disp, /^attachment; filename="[^"]*\.csv"; filename\*=UTF-8''/)
  assert.equal(decodeURIComponent(disp.split("UTF-8''")[1]!), name)
  const tailRange = await get(`/v1/file?path=${q(join(APP, name))}&download=1`, { range: 'bytes=-4' })
  assert.equal(tailRange.body.toString(), 'cdef')
  // Changed since: the whole file again, not a piece of the new one.
  const whole = await get(`/v1/file?path=${q(join(APP, name))}&download=1`, { range: 'bytes=10-', 'if-range': '"old"' })
  assert.equal(whole.status, 200)
  assert.equal(whole.body.length, 16)
  const bad = await get(`/v1/file?path=${q(join(APP, name))}&download=1`, { range: 'bytes=99-' })
  assert.equal(bad.status, 416)
  assert.equal(bad.headers['content-range'], 'bytes */16')

  // No extension: its first bytes say what it is.
  const bin = await get(`/v1/file?path=${q(join(APP, 'tool'))}`)
  assert.equal(bin.headers['content-type'], 'application/octet-stream')
  const txt = await get(`/v1/file?path=${q(join(APP, 'README'))}`)
  assert.equal(txt.headers['content-type'], 'text/plain; charset=utf-8')
  assert.match(String(txt.headers['content-disposition']), /^inline;/)
})

test('previews never serve keys or dotfiles, and a preview of a key file is refused', async () => {
  const site = join(APP, 'site')
  mkdirSync(site)
  writeFileSync(join(site, 'index.html'), '<h1>hi</h1>')
  writeFileSync(join(site, 'style.css'), 'h1{}')
  writeFileSync(join(site, 'credentials.json'), '{"secret":1}')
  writeFileSync(join(site, 'server.key'), 'k')
  writeFileSync(join(site, 'cert.pem'), 'p')
  writeFileSync(join(site, '.env'), 'K=1')
  const r = await send('POST', '/v1/preview', { 'content-type': 'application/json' }, JSON.stringify({ path: join(site, 'index.html') }))
  assert.equal(r.status, 200)
  const base = new URL(r.json.url).pathname.replace(/index\.html$/, '')
  const at = (f: string) => send('GET', base + f, { authorization: '' })
  assert.equal((await at('index.html')).status, 200)
  assert.equal((await at('style.css')).status, 200)
  for (const f of ['credentials.json', 'server.key', 'cert.pem', '.env']) assert.equal((await at(f)).status, 404, f)
  const key = await send('POST', '/v1/preview', { 'content-type': 'application/json' }, JSON.stringify({ path: join(site, 'server.key') }))
  assert.equal(key.status, 403)
  assert.equal(key.json.error.code, 'secret_file')
  const out = await send('POST', '/v1/preview', { 'content-type': 'application/json' }, JSON.stringify({ path: join(HOME, 'x.html') }))
  assert.equal(out.json.error.code, 'outside_projects')
  // A folder's index.html is checked as itself: a link there (a cloned repository can carry one)
  // leading to a key or out of the folder isn't served, though the folder is inside it.
  mkdirSync(join(HOME, '.ssh'), { recursive: true })
  writeFileSync(join(HOME, '.ssh', 'id_ed25519'), 'PRIVATE KEY')
  writeFileSync(join(HOME, 'diary.txt'), 'private')
  mkdirSync(join(site, 'docs'))
  symlinkSync(join(HOME, '.ssh', 'id_ed25519'), join(site, 'docs', 'index.html'))
  mkdirSync(join(site, 'away'))
  symlinkSync(join(HOME, 'diary.txt'), join(site, 'away', 'index.html'))
  mkdirSync(join(site, 'fine'))
  symlinkSync(join(site, 'style.css'), join(site, 'fine', 'index.html'))
  for (const f of ['docs/', 'docs', 'away/', 'away/index.html']) {
    const r2 = await at(f)
    assert.equal(r2.status, 404, f)
    assert.ok(!r2.body.toString().includes('PRIVATE') && !r2.body.toString().includes('private'), f)
  }
  assert.equal((await at('fine/')).body.toString(), 'h1{}', 'a link that stays inside is fine')
})

test('a file the phone opens is never a link swapped in after the check', async () => {
  const { sendChecked } = files
  const target = join(APP, 'swap.txt')
  writeFileSync(target, 'project text')
  writeFileSync(join(HOME, 'secret-note.txt'), 'not for the phone')
  // Checked (fileFor), then replaced by a link to somewhere else before it's opened.
  const f = await files.fileFor(target, null)
  rmSync(target)
  symlinkSync(join(HOME, 'secret-note.txt'), target)
  const sent: Buffer[] = []
  const res = { headersSent: false, writeHead() { return this }, setHeader() {}, on() {}, end() {}, write: (c: Buffer) => void sent.push(c) } as any
  await assert.rejects(sendChecked({ headers: {}, method: 'GET' } as any, res, f, false), (e: any) => e.code === 'not_found')
  assert.equal(Buffer.concat(sent).toString(), '')
  rmSync(target)
})

test('a session isn’t started in a folder Hypernova can’t open: said at once, with a code', async () => {
  const r = await send('POST', '/v1/sessions', { 'content-type': 'application/json' }, JSON.stringify({ prompt: 'hi', projectPath: join(APP, 'locked') }))
  assert.equal(r.status, 409)
  assert.equal(r.json.error.code, 'unreadable')
})

test('the content disposition keeps any name, and ranges read as RFC 9110 says', () => {
  assert.equal(files.contentDisposition('a "b".txt'), `attachment; filename="a _b_.txt"; filename*=UTF-8''a%20%22b%22.txt`)
  assert.deepEqual(files.parseByteRange('bytes=0-0', 10), { start: 0, end: 0 })
  assert.deepEqual(files.parseByteRange('bytes=5-100', 10), { start: 5, end: 9 })
  assert.equal(files.parseByteRange('bytes=0-1,4-5', 10), null)
  assert.equal(files.parseByteRange('bytes=10-', 10), 'bad')
  assert.equal(files.parseByteRange(undefined, 10), null)
})

test('the projects folder on first run: where most repositories are, else ~/Projects made', () => {
  const was = store.settings.projectsRoot
  try {
    store.settings.projectsRoot = join(HOME, 'Projects')
    for (const [dir, n] of [['code', 2], ['src', 1]] as const) for (let i = 0; i < n; i++) mkdirSync(join(HOME, dir, `r${i}`, '.git'), { recursive: true })
    assert.equal(files.ensureProjectsRoot(), join(HOME, 'code'))
    assert.equal(store.settings.projectsRoot, join(HOME, 'code'))
    // One the owner chose that exists stays.
    assert.equal(files.ensureProjectsRoot(), join(HOME, 'code'))
    rmSync(join(HOME, 'code'), { recursive: true })
    rmSync(join(HOME, 'src'), { recursive: true })
    store.settings.projectsRoot = join(HOME, 'Projects')
    assert.equal(files.ensureProjectsRoot(), join(HOME, 'Projects'))
    assert.ok(existsSync(join(HOME, 'Projects')))
  } finally {
    store.settings.projectsRoot = was
  }
})

test('the APK download carries on from where it stopped, under its own name', async () => {
  mkdirSync(join(process.env.HYPERNOVA_SUPPORT!, 'android'), { recursive: true })
  writeFileSync(join(process.env.HYPERNOVA_SUPPORT!, 'android', 'hypernova.apk'), 'PK0123456789')
  const r = await send('GET', '/download/android/Hypernova.apk', { authorization: '', range: 'bytes=2-' })
  assert.equal(r.status, 206)
  assert.equal(r.body.toString(), '0123456789')
  assert.equal(r.headers['content-type'], 'application/vnd.android.package-archive')
  assert.match(String(r.headers['content-disposition']), /^attachment; filename="Hypernova\.apk"/)
})
