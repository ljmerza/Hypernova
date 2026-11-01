// The server updating itself (src/server-update.ts, src/server-update.sh), against a fake GitHub on
// a local port, with the install and the restart stood in for: off by default; a newer release's
// package is downloaded, checked against the note's serverSha256, unpacked and handed to the
// installer; a wrong checksum, an older or equal release, a note without the package's checksum or
// an install the installer doesn't manage change nothing; it waits while a session works or an
// approval waits; failures wait before the next try; the new daemon says it updated. Then
// server-update.sh itself, with stand-ins for install-linux.sh, systemctl and curl: an update that
// takes, an install that stops before the swap, and a new daemon that doesn't answer (rolled back).
import { test, before, after, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { execFileSync, spawnSync } from 'node:child_process'
import { chmodSync, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { createServer as createHttpServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-server-update-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
mkdirSync(process.env.HOME, { recursive: true })

const { serverUpdater, installKind, semver, newer } = await import('../src/server-update.ts')
const { releaseChecker, parseNote } = await import('../src/releases.ts')
const { installSource } = await import('../src/cli/install.ts')
const { settings } = await import('../src/store.ts')
type ApplyJob = import('../src/server-update.ts').ApplyJob

const sha = (b: Buffer) => createHash('sha256').update(b).digest('hex')

/** A server package as pack-linux.sh makes it: one hypernova-<v>/ with the daemon and the install script. */
function tarball(version: string): Buffer {
  const d = mkdtempSync(join(scratch, 'pack-'))
  const top = join(d, `hypernova-${version}`)
  mkdirSync(join(top, 'daemon', 'src'), { recursive: true })
  mkdirSync(join(top, 'scripts'), { recursive: true })
  writeFileSync(join(top, 'daemon', 'package.json'), JSON.stringify({ name: 'hypernova-daemon', version }))
  writeFileSync(join(top, 'daemon', 'src', 'main.ts'), '')
  writeFileSync(join(top, 'scripts', 'install-linux.sh'), '#!/bin/sh\nexit 0\n', { mode: 0o755 })
  const out = join(d, 'p.tar.gz')
  execFileSync('tar', ['-czf', out, '-C', d, `hypernova-${version}`])
  const b = readFileSync(out)
  rmSync(d, { recursive: true, force: true })
  return b
}

// ── The fake GitHub ──────────────────────────────────────────────────────
let pkg: Buffer = Buffer.alloc(0)
let hits = { tgz: 0, latest: 0 }
let latest: unknown = null
let gh: import('node:http').Server
let GH = ''
function fake(req: IncomingMessage, res: ServerResponse): void {
  const path = req.url ?? '/'
  if (path === '/dl/tgz') {
    res.writeHead(302, { location: `${GH}/objects/tgz` })
    return void res.end()
  }
  if (path === '/objects/tgz') {
    hits.tgz++
    res.writeHead(200, { 'content-type': 'application/octet-stream', 'content-length': String(pkg.length) })
    return void res.end(pkg)
  }
  if (path === '/repos/ljmerza/Hypernova/releases/latest' && latest) {
    hits.latest++
    res.writeHead(200, { 'content-type': 'application/json' })
    return void res.end(JSON.stringify(latest))
  }
  if (path === '/dl/note' && latest) {
    res.writeHead(200)
    return void res.end(JSON.stringify(note))
  }
  if (path === '/dl/apk') {
    res.writeHead(200, { 'content-length': String(apk.length) })
    return void res.end(apk)
  }
  res.writeHead(404)
  res.end()
}
let note: unknown = null
const apk = Buffer.from('the apk')

before(async () => {
  gh = createHttpServer(fake)
  await new Promise<void>((r) => gh.listen(0, '127.0.0.1', () => r()))
  GH = `http://127.0.0.1:${(gh.address() as { port: number }).port}`
})
after(async () => {
  await new Promise((r) => gh.close(r))
  rmSync(scratch, { recursive: true, force: true })
})

let n = 0
let dir = ''
let stateFile = ''
beforeEach(() => {
  n++
  dir = join(scratch, `updates-${n}`)
  stateFile = join(scratch, `state-${n}.json`)
  hits = { tgz: 0, latest: 0 }
  latest = null
  delete settings.autoUpdate
})

const release = (tag: string, sum: string | null = sha(pkg)) => ({ tag, sha256: sum, tarball: { url: `${GH}/dl/tgz`, size: pkg.length } })
function updater(extra: Partial<Parameters<typeof serverUpdater>[0]> = {}) {
  const applied: ApplyJob[] = []
  const u = serverUpdater({
    version: '0.5.0', dir, stateFile, logFile: join(scratch, 'update.log'),
    enabled: () => true, managed: () => ({ ok: true }), busy: () => ({ working: 0, approvals: 0 }),
    apply: async (j) => void applied.push(j),
    quietMs: 0, pollMs: 5, watchMs: 0,
    ...extra,
  })
  return { u, applied }
}

test('versions: three numbers, compared as numbers; anything else is no version', () => {
  assert.deepEqual(semver('v0.10.2'), [0, 10, 2])
  assert.deepEqual(semver('1.2.3'), [1, 2, 3])
  assert.equal(semver('v1.2.3-rc1'), null)
  assert.equal(semver('v0.2.0-ljm.4'), null)
  assert.ok(newer([0, 10, 0], [0, 9, 9]))
  assert.ok(!newer([0, 9, 0], [0, 9, 0]))
  assert.ok(!newer([0, 8, 9], [0, 9, 0]))
})

test('the note: serverSha256 is read when it is one, and an older note still works for the APK', () => {
  const s = 'a'.repeat(64)
  assert.deepEqual(parseNote({ versionCode: 1, versionName: '0.9.0', sha256: s, serverSha256: 'B'.repeat(64) }), { versionCode: 1, versionName: '0.9.0', sha256: s, serverSha256: 'b'.repeat(64) })
  assert.deepEqual(parseNote({ versionCode: 1, versionName: '0.9.0', sha256: s }), { versionCode: 1, versionName: '0.9.0', sha256: s, serverSha256: null })
  assert.equal(parseNote({ versionCode: 1, versionName: '0.9.0', sha256: s, serverSha256: 'nope' })?.serverSha256, null)
})

test('on by default (owner, 2026-11-01); turned off, nothing is downloaded', async () => {
  pkg = tarball('0.9.0')
  const { u } = updater({ enabled: undefined })
  assert.equal(settings.autoUpdate, undefined)
  assert.equal(u.view().auto, true)
  settings.autoUpdate = false
  try {
    const off = updater({ enabled: undefined })
    assert.deepEqual(await off.u.consider(release('v0.9.0')), { kind: 'off' })
    assert.equal(off.u.view().auto, false)
    assert.equal(hits.tgz, 0)
    assert.equal(off.applied.length, 0)
  } finally {
    delete settings.autoUpdate
  }
})

test('a newer release: downloaded, checked, unpacked and handed to the installer; the new daemon says so', async () => {
  pkg = tarball('0.9.0')
  let changed = 0
  const { u, applied } = updater({ changed: () => changed++ })
  const r = await u.consider(release('v0.9.0'))
  assert.deepEqual(r, { kind: 'launched', tag: 'v0.9.0', forced: false })
  assert.equal(hits.tgz, 1)
  assert.equal(applied.length, 1)
  const j = applied[0]!
  assert.equal(j.tag, 'v0.9.0')
  assert.equal(j.from, '0.5.0')
  assert.equal(j.to, '0.9.0')
  assert.ok(existsSync(join(j.unpacked, 'scripts', 'install-linux.sh')))
  assert.equal(JSON.parse(readFileSync(join(j.unpacked, 'daemon', 'package.json'), 'utf8')).version, '0.9.0')
  assert.ok(existsSync(join(j.workDir, 'hypernova-server-v0.9.0-linux.tar.gz')))
  assert.deepEqual(readdirSync(dir).filter((f) => f.startsWith('.')), [])
  assert.equal(u.view().state, 'installing')
  assert.equal(u.view().target, 'v0.9.0')
  assert.ok(changed >= 2)
  // A second look while it installs: nothing more.
  assert.equal((await u.consider(release('v0.9.0'))).kind, 'busy')
  // The new daemon (0.9.0) at start: it updated, and says from what.
  const { u: next } = updater({ version: '0.9.0' })
  assert.equal(next.started(), true)
  const last = next.view().last
  assert.equal(last?.from, '0.5.0')
  assert.equal(last?.to, '0.9.0')
  assert.equal(next.view().state, 'idle')
  // Once only.
  assert.equal(updater({ version: '0.9.0' }).u.started(), false)
})

test('a checksum that is not the package\'s: thrown away, nothing installed, tried again later', async () => {
  pkg = tarball('0.9.0')
  let t = 1_000_000
  const { u, applied } = updater({ now: () => t })
  assert.deepEqual(await u.consider(release('v0.9.0', sha(Buffer.from('another package')))), { kind: 'mismatch', tag: 'v0.9.0' })
  assert.equal(applied.length, 0)
  assert.equal(readdirSync(dir).flatMap((d) => (d.startsWith('.') ? [d] : readdirSync(join(dir, d)))).filter((f) => f.endsWith('.tar.gz')).length, 0)
  assert.equal(u.view().state, 'idle')
  // Rate-limited: not before an hour has passed, then again.
  assert.equal((await u.consider(release('v0.9.0'))).kind, 'later')
  assert.equal(hits.tgz, 1)
  t += 3600_001
  assert.equal((await u.consider(release('v0.9.0'))).kind, 'launched')
})

test('an older or equal release, or a tag that is no version: nothing downloaded (never a downgrade)', async () => {
  pkg = tarball('0.5.0')
  const { u, applied } = updater()
  assert.equal((await u.consider(release('v0.5.0'))).kind, 'not-newer')
  assert.equal((await u.consider(release('v0.4.9'))).kind, 'not-newer')
  assert.equal((await u.consider(release('v0.2.0-ljm.9'))).kind, 'not-a-version')
  assert.equal(hits.tgz, 0)
  assert.equal(applied.length, 0)
})

test('a release whose note has no serverSha256, or no server package: not installed', async () => {
  pkg = tarball('0.9.0')
  const { u, applied } = updater()
  assert.equal((await u.consider(release('v0.9.0', null))).kind, 'no-package')
  assert.equal((await u.consider({ tag: 'v0.9.0', sha256: sha(pkg), tarball: null })).kind, 'no-package')
  assert.equal(hits.tgz, 0)
  assert.equal(applied.length, 0)
})

test('a package whose daemon is another version than its tag: not installed', async () => {
  pkg = tarball('0.4.0')
  const { u, applied } = updater()
  assert.equal((await u.consider(release('v0.9.0'))).kind, 'error')
  assert.equal(applied.length, 0)
})

test('an install the installer does not manage: skipped, with why', async () => {
  pkg = tarball('0.9.0')
  const { u, applied } = updater({ managed: () => ({ ok: false, reason: 'Installed from a checkout' }) })
  assert.deepEqual(await u.consider(release('v0.9.0')), { kind: 'unavailable', reason: 'Installed from a checkout' })
  assert.equal(u.view().available, false)
  assert.equal(u.view().reason, 'Installed from a checkout')
  assert.equal(hits.tgz, 0)
  assert.equal(applied.length, 0)
})

test('it waits while a session works or an approval waits, and installs once it is quiet', async () => {
  pkg = tarball('0.9.0')
  let b = { working: 1, approvals: 0 }
  let polls = 0
  const { u, applied } = updater({
    busy: () => {
      polls++
      if (polls === 4) b = { working: 0, approvals: 1 }
      if (polls === 8) b = { working: 0, approvals: 0 }
      return b
    },
  })
  const p = u.consider(release('v0.9.0'))
  await new Promise((r) => setTimeout(r, 15))
  assert.equal(applied.length, 0)
  assert.equal(u.view().state, 'waiting')
  assert.equal((await p).kind, 'launched')
  assert.ok(polls >= 8)
  assert.equal(applied.length, 1)
})

test('quiet means quiet for a while: a short gap between turns is not enough', async () => {
  pkg = tarball('0.9.0')
  let t = 0
  const seen: number[] = []
  const { u, applied } = updater({
    now: () => t,
    sleep: async () => void (t += 10_000),
    quietMs: 60_000,
    // Quiet for 20 s, working again, then quiet for good.
    busy: () => {
      seen.push(t)
      return { working: t >= 20_000 && t < 40_000 ? 1 : 0, approvals: 0 }
    },
  })
  assert.equal((await u.consider(release('v0.9.0'))).kind, 'launched')
  assert.equal(applied.length, 1)
  assert.ok(t >= 100_000, `installed at ${t}`)
})

test('busy for longer than the cap: it goes ahead, and says it was forced', async () => {
  pkg = tarball('0.9.0')
  let t = 0
  const { u } = updater({ now: () => t, sleep: async () => void (t += 3600_000), maxWaitMs: 5 * 3600_000, busy: () => ({ working: 2, approvals: 0 }) })
  assert.deepEqual(await u.consider(release('v0.9.0')), { kind: 'launched', tag: 'v0.9.0', forced: true })
})

test('turned off while it waits: nothing installed', async () => {
  pkg = tarball('0.9.0')
  let on = true
  let polls = 0
  const { u, applied } = updater({ enabled: () => on, busy: () => (++polls === 3 && (on = false), { working: 1, approvals: 0 }) })
  assert.equal((await u.consider(release('v0.9.0'))).kind, 'cancelled')
  assert.equal(applied.length, 0)
})

test('an install that could not start, or that the unit says failed: the server stays, and tries later', async () => {
  pkg = tarball('0.9.0')
  const { u } = updater({ apply: async () => { throw new Error('systemd-run: no user bus') } })
  assert.equal((await u.consider(release('v0.9.0'))).kind, 'error')
  assert.equal(u.view().state, 'idle')
  assert.equal((await u.consider(release('v0.9.0'))).kind, 'later')
  assert.equal(u.settle(), 'none')
})

test('the unit says the install went back: recorded as a failure at the old daemon\'s start', async () => {
  pkg = tarball('0.9.0')
  const { u } = updater({ apply: async (j) => writeFileSync(j.result, JSON.stringify({ tag: j.tag, code: 4, at: 1 })) })
  assert.equal((await u.consider(release('v0.9.0'))).kind, 'launched')
  // The old daemon (still 0.5.0), restarted on its old files.
  const { u: again } = updater()
  assert.equal(again.started(), false)
  assert.equal(again.view().state, 'idle')
  assert.equal(again.view().last, null)
  assert.equal((await again.consider(release('v0.9.0'))).kind, 'later')
})

test('an install that never comes back within the hour: a failure, the server as it was', async () => {
  pkg = tarball('0.9.0')
  let t = 5_000_000
  const { u } = updater({ now: () => t })
  assert.equal((await u.consider(release('v0.9.0'))).kind, 'launched')
  assert.equal(u.settle(), 'running')
  t += 3600_001
  assert.equal(u.settle(), 'failed')
  assert.equal(u.view().state, 'idle')
})

test('the release check hands the release over after the APK, with the package and its checksum', async () => {
  pkg = tarball('0.9.0')
  note = { versionCode: 9, versionName: '0.9.0', sha256: sha(apk), serverSha256: sha(pkg) }
  latest = { tag_name: 'v0.9.0', assets: [
    { name: 'hypernova-v0.9.0.apk', size: apk.length, browser_download_url: `${GH}/dl/apk` },
    { name: 'hypernova-server-v0.9.0-linux.tar.gz', size: pkg.length, browser_download_url: `${GH}/dl/tgz` },
    { name: 'hypernova-v0.9.0.json', size: 100, browser_download_url: `${GH}/dl/note` },
  ] }
  const got: unknown[] = []
  let order: string[] = []
  const c = releaseChecker({
    api: GH, dir: join(scratch, `android-${n}`), offered: () => 1, published: () => order.push('apk'), enabled: () => true,
    server: { enabled: () => true, consider: async (r) => void (order.push('server'), got.push(r)) },
  })
  assert.equal((await c.check()).kind, 'published')
  assert.deepEqual(order, ['apk', 'server'])
  assert.deepEqual(got, [{ tag: 'v0.9.0', sha256: sha(pkg), tarball: { url: `${GH}/dl/tgz`, size: pkg.length } }])
  // releaseCheck off and autoUpdate on: the release is still read for the server, the APK left alone.
  order = []
  const c2 = releaseChecker({ api: GH, dir: join(scratch, `android2-${n}`), offered: () => 1, published: () => order.push('apk'), enabled: () => false, server: { enabled: () => true, consider: async () => void order.push('server') } })
  assert.equal((await c2.check()).kind, 'off')
  assert.deepEqual(order, ['server'])
  // Both off: GitHub isn't asked.
  const before = hits.latest
  const c3 = releaseChecker({ api: GH, offered: () => 1, published: () => {}, enabled: () => false, server: { enabled: () => false, consider: async () => assert.fail('asked') } })
  assert.equal((await c3.check()).kind, 'off')
  assert.equal(hits.latest, before)
})

// ── Which installs update themselves ─────────────────────────────────────
function install(o: { marker?: unknown; unit?: string; env?: NodeJS.ProcessEnv; self?: string; container?: boolean; systemdRun?: boolean; platform?: 'linux' | 'darwin' } = {}) {
  const app = '/home/u/.local/share/hypernova/app'
  const files: Record<string, string> = {}
  if (o.marker !== undefined) files[`${app}/daemon/install.json`] = JSON.stringify(o.marker)
  files['/home/u/.config/systemd/user/hypernova.service'] = o.unit ?? `ExecStart="/n/node" --x "${app}/daemon/src/main.ts"`
  return installKind({
    platform: o.platform ?? 'linux',
    env: o.env ?? { INVOCATION_ID: 'abc', HYPERNOVA_CONTAINER: o.container ? '1' : '0' },
    self: o.self ?? `${app}/daemon`,
    appDir: app,
    unitFile: '/home/u/.config/systemd/user/hypernova.service',
    exists: () => false,
    read: (p) => {
      if (!(p in files)) throw new Error('ENOENT')
      return files[p]!
    },
    real: (p) => p,
    which: () => o.systemdRun ?? true,
  })
}

test('managed: a release package\'s install, in <support>/app, run by its systemd service', () => {
  assert.deepEqual(install({ marker: { source: 'package' } }), { ok: true })
})

test('not managed: a checkout\'s install (hnctl update), one from before the marker, a test copy, a container, no service', () => {
  const why = (m: ReturnType<typeof install>) => (m.ok ? 'ok' : m.reason)
  assert.match(why(install({ marker: { source: 'checkout' } })), /checkout/)
  assert.match(why(install({})), /Installed before servers could update themselves/)
  assert.match(why(install({ marker: { source: 'package' }, self: '/media/x/hypernova/daemon' })), /doesn’t run from its installed copy/)
  assert.match(why(install({ marker: { source: 'package' }, container: true })), /container/)
  assert.match(why(install({ marker: { source: 'package' }, env: { HYPERNOVA_CONTAINER: '0' } })), /systemd service/)
  assert.match(why(install({ marker: { source: 'package' }, unit: 'ExecStart=/other/main.ts' })), /hypernova setup/)
  assert.match(why(install({ marker: { source: 'package' }, systemdRun: false })), /systemd-run/)
  assert.match(why(install({ marker: { source: 'package' }, platform: 'darwin' })), /Linux only/)
})

test('setup marks where an install comes from: a git checkout or a package', () => {
  const repo = join(scratch, 'repo')
  mkdirSync(join(repo, '.git'), { recursive: true })
  mkdirSync(join(repo, 'daemon'), { recursive: true })
  assert.equal(installSource(join(repo, 'daemon')), 'checkout')
  // A worktree's .git is a file.
  const wt = join(scratch, 'wt')
  mkdirSync(join(wt, 'daemon'), { recursive: true })
  writeFileSync(join(wt, '.git'), 'gitdir: /x')
  assert.equal(installSource(join(wt, 'daemon')), 'checkout')
  const unpacked = join(scratch, 'unpacked', 'hypernova-0.9.0')
  mkdirSync(join(unpacked, 'daemon'), { recursive: true })
  assert.equal(installSource(join(unpacked, 'daemon')), 'package')
})

// ── server-update.sh, with stand-ins ─────────────────────────────────────
const SCRIPT = join(import.meta.dirname, '..', 'src', 'server-update.sh')
/**
 * One run of the script: an installed daemon at 0.5.0, a package whose install-linux.sh [installs]
 * (swaps 0.9.0 in, the service restarted: a new MainPID) or stops with [stopWith], and a daemon
 * that [answers] (curl) or not.
 */
function runScript(o: { installs: boolean; stopWith?: number; answers: boolean; restarts?: boolean }) {
  const root = mkdtempSync(join(scratch, 'sh-'))
  const app = join(root, 'app')
  const bin = join(root, 'bin')
  mkdirSync(join(app, 'daemon'), { recursive: true })
  mkdirSync(bin)
  writeFileSync(join(app, 'daemon', 'package.json'), JSON.stringify({ name: 'hypernova-daemon', version: '0.5.0' }, null, 2))
  writeFileSync(join(app, 'daemon', 'old-file'), 'old')
  const src = join(root, 'pkg', 'hypernova-0.9.0')
  mkdirSync(join(src, 'scripts'), { recursive: true })
  mkdirSync(join(src, 'daemon'), { recursive: true })
  writeFileSync(join(src, 'daemon', 'package.json'), JSON.stringify({ name: 'hypernova-daemon', version: '0.9.0' }, null, 2))
  const calls = join(root, 'calls')
  writeFileSync(join(root, 'pid'), '100')
  writeFileSync(join(src, 'scripts', 'install-linux.sh'), `#!/bin/bash
echo "install $*" >> "${calls}"
${o.installs ? `rm -rf "${app}/daemon" && cp -R "${src}/daemon" "${app}/daemon"` : ''}
${o.installs && o.restarts !== false ? `echo 200 > "${root}/pid"` : ''}
exit ${o.stopWith ?? 0}
`, { mode: 0o755 })
  writeFileSync(join(bin, 'systemctl'), `#!/bin/bash
echo "systemctl $*" >> "${calls}"
case "$*" in *MainPID*) cat "${root}/pid" ;; *restart*) echo 300 > "${root}/pid" ;; esac
`, { mode: 0o755 })
  writeFileSync(join(bin, 'curl'), `#!/bin/bash\nexit ${o.answers ? 0 : 7}\n`, { mode: 0o755 })
  writeFileSync(join(bin, 'sleep'), '#!/bin/bash\nexit 0\n', { mode: 0o755 })
  const result = join(root, 'result.json')
  const r = spawnSync('/bin/bash', [SCRIPT, src, 'v0.9.0', result, app, '1', join(root, 'update.log'), '100'], { env: { ...process.env, PATH: `${bin}:${process.env.PATH}` }, encoding: 'utf8' })
  return {
    status: r.status,
    result: JSON.parse(readFileSync(result, 'utf8')) as { tag: string; code: number },
    version: JSON.parse(readFileSync(join(app, 'daemon', 'package.json'), 'utf8')).version as string,
    calls: existsSync(calls) ? readFileSync(calls, 'utf8') : '',
    left: readdirSync(app).sort(),
    log: readFileSync(join(root, 'update.log'), 'utf8'),
  }
}

test('server-update.sh: an update that takes, with the install script\'s own flags', () => {
  const r = runScript({ installs: true, answers: true })
  assert.equal(r.result.tag, 'v0.9.0')
  assert.equal(r.result.code, 0)
  assert.equal(r.version, '0.9.0')
  assert.match(r.calls, /install --from .*hypernova-0\.9\.0 --yes --no-claude/)
  assert.doesNotMatch(r.calls, /restart/)
  assert.deepEqual(r.left, ['daemon'])
})

test('server-update.sh: an install that stops before the swap leaves the daemon as it was', () => {
  const r = runScript({ installs: false, stopWith: 1, answers: true })
  assert.equal(r.result.code, 1)
  assert.equal(r.version, '0.5.0')
  assert.doesNotMatch(r.calls, /restart/)
  assert.deepEqual(r.left, ['daemon'])
})

test('server-update.sh: a new daemon that does not answer is swapped back out, and the service restarted on the old', () => {
  const r = runScript({ installs: true, answers: false })
  assert.equal(r.result.code, 4)
  assert.equal(r.version, '0.5.0')
  assert.match(r.calls, /systemctl --user restart hypernova\.service/)
  assert.deepEqual(r.left, ['daemon'])
  assert.match(r.log, /going back to 0\.5\.0/)
})

test('server-update.sh: a swap the service did not restart into is restarted', () => {
  // install-linux.sh swaps the files but leaves the old process (MainPID stays 100).
  const r = runScript({ installs: true, answers: true, restarts: false })
  assert.equal(r.result.code, 0)
  assert.equal(r.version, '0.9.0')
  assert.match(r.calls, /systemctl --user restart hypernova\.service/)
})
