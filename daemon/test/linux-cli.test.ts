// The `hypernova` command for Linux (daemon/src/cli/): its arguments, the systemd unit and the
// launcher it writes, lingering, Node.js picked and downloaded (checksum checked), the QR code, and
// setup, pair, status, doctor, logs and uninstall run against a stand-in daemon (a small HTTP
// server on loopback) and a stand-in system (systemctl, loginctl, npm, claude answered here), in a
// scratch home. Nothing touches this Mac's own Hypernova, systemd or Claude Code.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { createServer, type Server } from 'node:http'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { execFileSync } from 'node:child_process'
import { cliContext, realSys, type Cli, type Ran, type RunOptions, type Sys } from '../src/cli/context.ts'
import { doctor, logs, name, pair, parseArgs, setup, status, tailscale, uninstall } from '../src/cli/commands.ts'
import { CARRIED, enableLinger, installService, userManager, launcherText, ownLauncher, removeService, serviceState, servicePath, unitText, userBusEnv } from '../src/cli/service.ts'
import { downloadNode, majorOf, nodeArch, pickRelease, shaFor } from '../src/cli/node.ts'
import { daemonIn, integrationDir, isDaemonDir, setIntegration } from '../src/cli/install.ts'
import { listenersOn, loopback, parseProcNet } from '../src/cli/doctor.ts'
import { encodeQr, formatBits, qrLines, versionFor } from '../src/cli/qr.ts'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-cli-'))
after(() => rmSync(scratch, { recursive: true, force: true }))
const DAEMON = join(import.meta.dirname, '..')

// ── A stand-in system ─────────────────────────────────────────────────────

type Call = { cmd: string; args: string[]; o?: RunOptions }
type Answer = Partial<Ran> | null
/**
 * Programs answered by [answer] (by base name); `null` from it runs the real program. `which` finds
 * the names in [have]. What it prints is kept in `out`.
 */
function fakeSys(answer: (cmd: string, args: string[]) => Answer, have: string[] = []): Sys & { calls: Call[]; out: string[] } {
  const real = realSys()
  const calls: Call[] = []
  const out: string[] = []
  return {
    calls,
    out,
    async run(cmd, args, o) {
      calls.push({ cmd, args, o })
      const a = answer(cmd.split('/').pop()!, args)
      if (a === null) return real.run(cmd, args, o)
      return { code: 0, out: '', err: '', missing: false, ...a }
    },
    which: (name) => (have.includes(name) ? `/usr/bin/${name}` : null),
    say: (line = '') => void out.push(line),
  }
}

let n = 0
/** A scratch home and support folder for one test, as a Linux user's. */
function ctx(sys: Sys, o: Partial<Cli> = {}, env: NodeJS.ProcessEnv = {}): Cli {
  const home = join(scratch, `home-${++n}`)
  mkdirSync(home, { recursive: true })
  return cliContext({
    platform: 'linux',
    home,
    env: { HOME: home, PATH: '/usr/bin:/bin', HYPERNOVA_TAILSCALE: join(scratch, 'no-tailscale'), ...env },
    support: join(home, '.local/share/hypernova'),
    logs: join(home, '.local/state/hypernova/logs'),
    user: 'ada',
    uid: 1000,
    tty: false,
    sys,
    ...o,
  })
}

// ── A stand-in daemon ─────────────────────────────────────────────────────

type Fake = { api: number; local: number; devices: any[]; status: any; name: any; releaseOk: boolean; requests: string[]; bodies: any[]; pairLink: { link: string; appLink: string; download?: string }; serve: () => { url: string } | { error: string }; close: () => Promise<void> }
async function fakeDaemon(): Promise<Fake> {
  const f: Fake = {
    api: 0,
    local: 0,
    devices: [],
    requests: [],
    bodies: [],
    status: {
      daemonVersion: '0.2.0',
      tailscale: { installed: false, loggedIn: false, httpsEnabled: false, serveOn: false, url: null },
      hooks: { installed: true },
      statusLine: { installed: true, supported: true },
      shell: { installed: true, supported: true },
      devices: [],
      sessions: [{ state: 'working', term: { id: 't1' } }, { state: 'idle' }],
      account: { state: 'ok' },
      lan: { port: 47323, addresses: ['192.168.1.20'], fingerprint: 'ab', deviceCert: false },
    },
    pairLink: { link: 'https://hypernova.observer/pair#v=1&u=x&c=CODE&n=box', appLink: 'hypernova://pair?v=1&u=x&c=CODE&n=box' },
    name: { enabled: true, setting: true, termsShown: false, reason: 'Not made yet.', terms: 'https://letsencrypt.org/repository/', host: null },
    releaseOk: true,
    serve: () => ({ url: 'https://box.tail.ts.net:47321' }),
    close: async () => {},
  }
  const servers: Server[] = []
  const handler = (req: any, res: any) => {
    f.requests.push(`${req.method} ${req.url}`)
    const send = (o: unknown) => {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify(o))
    }
    const path = new URL(req.url, 'http://x').pathname
    if (path === '/v1/health') return send({ ok: true, protocol: 1, name: 'box' })
    if (path === '/local/status') return send(f.status)
    if (path === '/local/tsnet') {
      // An older daemon has no such route: the command then treats the node as absent.
      if (!f.status.tsnet) { res.writeHead(404, { 'content-type': 'application/json' }); return res.end('{"error":{"code":"not_found"}}') }
      return send(f.status.tsnet)
    }
    if (path === '/local/devices') return send({ devices: f.devices })
    if (path === '/local/pair/start') return send({ ...f.pairLink, expiresAt: Date.now() + 600_000, url: 'x' })
    if (path === '/local/tailscale/serve') {
      const r = f.serve()
      if ('error' in r) {
        res.writeHead(500, { 'content-type': 'application/json' })
        return res.end(JSON.stringify({ error: { code: 'tailscale', message: r.error } }))
      }
      f.status.tailscale = { ...f.status.tailscale, serveOn: true, url: r.url }
      return send(f.status.tailscale)
    }
    if (path === '/local/tsnet/logout') return send({ ok: true })
    if (path === '/local/name/release') return send({ released: true, name: 'k3x9mq2z7a' })
    if (path === '/local/name') return send(f.name)
    if (path === '/local/settings') {
      let body = ''
      req.on('data', (c: Buffer) => (body += c))
      return req.on('end', () => {
        const b = JSON.parse(body || '{}')
        f.bodies.push(b)
        if (b.nameTermsShown === true) f.name = { ...f.name, termsShown: true }
        if (typeof b.nameService === 'boolean') f.name = { ...f.name, enabled: b.nameService, setting: b.nameService }
        send(b.nameService === false ? { nameRelease: { released: f.releaseOk, name: 'k3x9mq2z7a' } } : {})
      })
    }
    if (path.startsWith('/local/integration/')) return send({ hooks: { installed: path.endsWith('install') }, statusLine: { installed: true, supported: true }, shell: { installed: true, supported: true } })
    res.writeHead(404)
    res.end('{}')
  }
  for (const k of ['api', 'local'] as const) {
    const s = createServer(handler)
    await new Promise<void>((r) => s.listen(0, '127.0.0.1', r))
    f[k] = (s.address() as any).port
    servers.push(s)
  }
  f.close = () => Promise.all(servers.map((s) => new Promise<void>((r) => s.close(() => r())))).then(() => {})
  return f
}
let daemon: Fake
before(async () => {
  daemon = await fakeDaemon()
})
after(() => daemon.close())
/** A port nothing listens on (taken, then let go). */
async function deadPort(): Promise<number> {
  const s = createServer()
  await new Promise<void>((r) => s.listen(0, '127.0.0.1', r))
  const p = (s.address() as any).port
  await new Promise<void>((r) => s.close(() => r()))
  return p
}

// ── Arguments ─────────────────────────────────────────────────────────────

test('arguments: the command, flags with and without values, and the words left over', () => {
  assert.deepEqual(parseArgs([]), { cmd: 'help', flags: {}, rest: [] })
  assert.deepEqual(parseArgs(['setup', '--from', '/x', '--no-claude']), { cmd: 'setup', flags: { from: '/x', 'no-claude': true }, rest: [] })
  assert.deepEqual(parseArgs(['logs', '-n', '20', '-f']), { cmd: 'logs', flags: { n: '20', f: true }, rest: [] })
  assert.deepEqual(parseArgs(['lan', 'on', '--from=/y']), { cmd: 'lan', flags: { from: '/y' }, rest: ['on'] })
  // A flag that takes no value doesn't swallow the next word.
  assert.deepEqual(parseArgs(['pair', '--no-wait', 'extra']), { cmd: 'pair', flags: { 'no-wait': true }, rest: ['extra'] })
})

// ── The service ───────────────────────────────────────────────────────────

test('the unit: runs the installed daemon with its Node, restarts on failure, leaves holders running, starts at boot', () => {
  const c = ctx(fakeSys(() => ({})), {}, { HYPERNOVA_API_PORT: '49311', HYPERNOVA_LOCAL_PORT: '49312', UNRELATED: 'x' })
  const node = join(c.support, 'node/v24.9.0/bin/node')
  const u = unitText(c, node)
  assert.match(u, new RegExp(`^ExecStart="${node.replace(/[.]/g, '\\.')}" --disable-warning=ExperimentalWarning "${join(c.daemonDir, 'src/main.ts').replace(/[.]/g, '\\.')}"$`, 'm'))
  assert.match(u, /^Restart=on-failure$/m)
  assert.match(u, /^RestartSec=5$/m)
  assert.match(u, /^KillMode=process$/m)
  assert.match(u, /^WantedBy=default\.target$/m)
  assert.match(u, /^Type=simple$/m)
  assert.match(u, new RegExp(`^WorkingDirectory=${c.daemonDir}$`, 'm'))
  // The test daemon's own ports come along; nothing else from the environment does.
  assert.match(u, /^Environment="HYPERNOVA_API_PORT=49311"$/m)
  assert.match(u, /^Environment="HYPERNOVA_LOCAL_PORT=49312"$/m)
  assert.doesNotMatch(u, /UNRELATED/)
  assert.match(u, /^Environment="PATH=[^"]*\/node\/v24\.9\.0\/bin:[^"]*\.local\/bin:/m)
  assert.match(u, /^Environment="LANG=C\.UTF-8"$/m)
  // systemd's specifiers and quotes stay literal.
  const odd = unitText({ ...c, daemonDir: '/opt/100%/da"emon' }, '/n')
  assert.match(odd, /^ExecStart="\/n" --disable-warning=ExperimentalWarning "\/opt\/100%%\/da\\"emon\/src\/main\.ts"$/m)
  assert.ok(CARRIED.includes('HYPERNOVA_SUPPORT'))
})

test('the service’s PATH: its Node first, then ~/.local/bin (Claude Code), then the system, no repeats', () => {
  assert.equal(servicePath({ home: '/home/ada' }, '/usr/bin/node'), '/usr/bin:/home/ada/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/sbin:/bin')
})

test('the launcher: runs the installed CLI with the recorded Node, carries a test daemon’s folders, is recognised as ours', () => {
  const c = ctx(fakeSys(() => ({})), {}, { HYPERNOVA_SUPPORT: "/tmp/it's" })
  const t = launcherText(c, '/opt/node/bin/node')
  assert.ok(t.startsWith('#!/bin/sh\n'))
  assert.match(t, /^export HYPERNOVA_SUPPORT='\/tmp\/it'\\''s'$/m)
  assert.match(t, /^N='\/opt\/node\/bin\/node'$/m)
  assert.match(t, new RegExp(`^C='${join(c.daemonDir, 'src/cli/hypernova.ts').replace(/[.]/g, '\\.')}'$`, 'm'))
  assert.match(t, /^exec "\$N" --disable-warning=ExperimentalWarning "\$C" "\$@"$/m)
  const p = join(scratch, 'launcher')
  writeFileSync(p, t)
  assert.equal(ownLauncher(p), true)
  writeFileSync(p, '#!/bin/sh\necho someone else’s hypernova\n')
  assert.equal(ownLauncher(p), false)
  assert.equal(ownLauncher(join(scratch, 'nothing')), false)
  // The script itself runs: with its files gone, it says so.
  writeFileSync(p, t)
  chmodSync(p, 0o755)
  assert.throws(() => execFileSync(p, ['status'], { stdio: 'pipe' }), /its files are gone/)
})

test('systemctl --user finds the user’s manager without a login session’s variables', () => {
  const runtime = '/run/user/1000'
  const env = userBusEnv({ env: { PATH: '/bin' }, uid: 1000 }, (p) => p === runtime || p === `${runtime}/bus`)
  assert.equal(env.XDG_RUNTIME_DIR, runtime)
  assert.equal(env.DBUS_SESSION_BUS_ADDRESS, `unix:path=${runtime}/bus`)
  // What the session already has wins.
  const own = userBusEnv({ env: { XDG_RUNTIME_DIR: '/r', DBUS_SESSION_BUS_ADDRESS: 'x' }, uid: 1000 }, () => true)
  assert.equal(own.XDG_RUNTIME_DIR, '/r')
  assert.equal(own.DBUS_SESSION_BUS_ADDRESS, 'x')
  // No manager folder (no login, no lingering yet): nothing made up.
  assert.equal(userBusEnv({ env: {}, uid: 1000 }, () => false).XDG_RUNTIME_DIR, undefined)
})

test('lingering: already on, turned on by loginctl, else by sudo without a prompt, else the owner is asked', async () => {
  const run = (linger: string, own: number, sudo: number | null) => {
    const sys = fakeSys((cmd, args) => {
      if (cmd === 'loginctl' && args[0] === 'show-user') return { out: linger + '\n' }
      if (cmd === 'loginctl' && args[0] === 'enable-linger') return { code: own }
      if (cmd === 'sudo') return { code: sudo ?? 1 }
      return {}
    }, sudo === null ? ['loginctl'] : ['loginctl', 'sudo'])
    return { sys, result: enableLinger(ctx(sys)) }
  }
  assert.equal(await run('yes', 1, 1).result, 'already')
  assert.equal(await run('no', 0, 1).result, 'done')
  const viaSudo = run('no', 1, 0)
  assert.equal(await viaSudo.result, 'done')
  assert.deepEqual(viaSudo.sys.calls.at(-1)!.args, ['-n', 'loginctl', 'enable-linger', 'ada'])
  assert.equal(await run('no', 1, 1).result, 'needs_sudo')
  assert.equal(await run('no', 1, null).result, 'needs_sudo')
  assert.equal(await enableLinger(ctx(fakeSys(() => ({})))), 'no_loginctl')
})

test('without a login session (su, sudo -u), lingering starts the user’s manager and setup waits for it', async () => {
  let lingered = false
  let asked = 0
  const sys = fakeSys((cmd, args) => {
    if (cmd === 'systemctl' && args[1] === 'show-environment') return lingered && ++asked > 2 ? {} : { code: 1, err: 'Failed to connect to bus: No medium found' }
    if (cmd === 'loginctl' && args[0] === 'show-user') return { out: lingered ? 'yes\n' : 'no\n' }
    if (cmd === 'loginctl' && args[0] === 'enable-linger') return { code: 1, err: 'Access denied' }
    if (cmd === 'sudo') {
      lingered = true
      return {}
    }
    return {}
  }, ['systemctl', 'loginctl', 'sudo'])
  const m = await userManager(ctx(sys), 5000)
  assert.deepEqual(m, { ok: true, why: '', linger: 'done' })
  // Lingering refused: no manager, and why.
  const no = fakeSys((cmd, args) => (cmd === 'systemctl' ? { code: 1, err: 'Failed to connect to bus: No medium found' } : cmd === 'loginctl' && args[0] === 'show-user' ? { out: 'no\n' } : { code: 1 }), ['systemctl', 'loginctl'])
  assert.deepEqual(await userManager(ctx(no), 600), { ok: false, why: 'Failed to connect to bus: No medium found', linger: 'needs_sudo' })
  // No systemd at all: nothing tried.
  const none = fakeSys(() => ({}))
  assert.equal((await userManager(ctx(none))).ok, false)
  assert.equal(none.calls.length, 0)
})

test('installing the service writes the unit, reloads, enables and restarts it; removing undoes it', async () => {
  const sys = fakeSys((cmd, args) => {
    if (cmd !== 'systemctl') return {}
    if (args[1] === 'is-enabled') return { out: 'enabled\n' }
    if (args[1] === 'is-active') return { out: 'active\n' }
    if (args[1] === 'show') return { out: '4242\n' }
    return {}
  }, ['systemctl'])
  const c = ctx(sys)
  await installService(c, '/opt/node')
  assert.ok(readFileSync(c.unitFile, 'utf8').includes('ExecStart="/opt/node"'))
  const sc = sys.calls.filter((x) => x.cmd === 'systemctl').map((x) => x.args.join(' '))
  assert.deepEqual(sc, ['--user daemon-reload', '--user enable hypernova.service', '--user restart hypernova.service'])
  assert.deepEqual(await serviceState(c), { installed: true, enabled: true, active: true, pid: 4242 })
  sys.calls.length = 0
  assert.deepEqual(await removeService(c), [])
  assert.ok(!existsSync(c.unitFile))
  assert.ok(sys.calls.some((x) => x.args.join(' ') === '--user disable --now hypernova.service'))
  // systemctl's own words when it refuses.
  const no = fakeSys((cmd, args) => (cmd === 'systemctl' && args[1] === 'enable' ? { code: 1, err: 'Failed to connect to bus' } : {}), ['systemctl'])
  await assert.rejects(installService(ctx(no), '/opt/node'), /systemctl --user enable hypernova\.service: Failed to connect to bus/)
})

// ── Node.js ───────────────────────────────────────────────────────────────

test('Node.js: the newest 24 with a build for this processor, its checksum from SHASUMS256.txt', () => {
  const index = [
    { version: 'v26.0.0', files: ['linux-x64', 'linux-arm64'] },
    { version: 'v24.9.0', files: ['linux-x64'] },
    { version: 'v24.10.1', files: ['linux-arm64', 'linux-x64'] },
    { version: 'v24.2.0', files: ['linux-arm64'] },
    { version: 'v24.9.0', files: ['linux-arm64'] },
  ]
  assert.deepEqual(pickRelease(index, 'arm64', 24, 'https://d'), { version: 'v24.10.1', file: 'node-v24.10.1-linux-arm64.tar.gz', url: 'https://d/v24.10.1/node-v24.10.1-linux-arm64.tar.gz' })
  assert.equal(pickRelease(index, 'x64')?.version, 'v24.10.1')
  assert.equal(pickRelease(index, 'ia32'), null)
  assert.equal(pickRelease([{ version: 'v23.1.0', files: ['linux-x64'] }], 'x64'), null)
  assert.equal(pickRelease('nonsense', 'x64'), null)
  assert.equal(nodeArch('x64'), 'x64')
  assert.equal(nodeArch('arm'), null)
  assert.equal(majorOf("v24.9.0"), 24)
  assert.equal(majorOf('garbage'), 0)
  const sums = `${'a'.repeat(64)}  node-v24.10.1-linux-x64.tar.gz\n${'B'.repeat(64)}  node-v24.10.1-linux-arm64.tar.gz\nshort  node-v24.10.1.tar.gz\n`
  assert.equal(shaFor(sums, 'node-v24.10.1-linux-arm64.tar.gz'), 'b'.repeat(64))
  assert.equal(shaFor(sums, 'node-v24.10.1.tar.gz'), null)
  assert.equal(shaFor(sums, 'missing'), null)
})

test('Node.js is downloaded into Hypernova’s own folder, and a download that fails its checksum is thrown away', async () => {
  // A stand-in nodejs.org with one release whose "node" prints its version.
  const work = join(scratch, 'dist-src')
  const ver = 'v24.4.0'
  const name = `node-${ver}-linux-arm64`
  mkdirSync(join(work, name, 'bin'), { recursive: true })
  writeFileSync(join(work, name, 'bin', 'node'), `#!/bin/sh\necho ${ver}\n`, { mode: 0o755 })
  execFileSync('tar', ['-czf', join(work, `${name}.tar.gz`), '-C', work, name], { env: { ...process.env, COPYFILE_DISABLE: '1' } })
  const tarball = readFileSync(join(work, `${name}.tar.gz`))
  let sum = createHash('sha256').update(tarball).digest('hex')
  const s = createServer((req, res) => {
    if (req.url === '/index.json') return res.end(JSON.stringify([{ version: ver, files: ['linux-arm64', 'linux-x64'] }]))
    if (req.url === `/${ver}/SHASUMS256.txt`) return res.end(`${sum}  ${name}.tar.gz\n`)
    if (req.url === `/${ver}/${name}.tar.gz`) return res.end(tarball)
    res.writeHead(404).end()
  })
  await new Promise<void>((r) => s.listen(0, '127.0.0.1', r))
  try {
    const dist = `http://127.0.0.1:${(s.address() as any).port}`
    const c = ctx(realSys(), { arch: 'arm64' }, { HYPERNOVA_NODE_DIST: dist })
    const said: string[] = []
    const node = await downloadNode(c, (x) => said.push(x))
    assert.equal(node, join(c.nodeRoot, ver, 'bin', 'node'))
    assert.equal(execFileSync(node, { encoding: 'utf8' }).trim(), ver)
    assert.match(said[0]!, /Downloading Node\.js v24\.4\.0 from 127\.0\.0\.1/)
    // A changed download: refused, and nothing half-unpacked stays.
    sum = 'f'.repeat(64)
    const c2 = ctx(realSys(), { arch: 'arm64' }, { HYPERNOVA_NODE_DIST: dist })
    await assert.rejects(downloadNode(c2), /didn’t match nodejs\.org’s checksum/)
    assert.ok(!existsSync(join(c2.nodeRoot, ver)))
    // No build for this processor.
    await assert.rejects(downloadNode(ctx(realSys(), { arch: 'ppc64' }, { HYPERNOVA_NODE_DIST: dist })), /no Node\.js 24 for Linux on ppc64/)
  } finally {
    await new Promise<void>((r) => s.close(() => r()))
  }
})

// ── The daemon's files ────────────────────────────────────────────────────

test('the daemon is found in a checkout, its daemon folder or an unpacked tarball, and nowhere else', () => {
  assert.equal(isDaemonDir(DAEMON), true)
  assert.equal(daemonIn(DAEMON), DAEMON)
  assert.equal(daemonIn(join(DAEMON, '..')), DAEMON)
  assert.equal(daemonIn(scratch), null)
  // The integration runs from a daemon whose packages are installed: the installed one first.
  const have = new Set(['/i/daemon/src/cli/integration.ts', '/i/daemon/node_modules/ws', '/repo/daemon/src/cli/integration.ts', '/repo/daemon/node_modules/ws'])
  assert.equal(integrationDir({ daemonDir: '/i/daemon', selfDaemon: '/repo/daemon' }, (p) => have.has(p)), '/i/daemon')
  assert.equal(integrationDir({ daemonDir: '/none', selfDaemon: '/repo/daemon' }, (p) => have.has(p)), '/repo/daemon')
  // A tarball's daemon has no packages until it's installed.
  assert.equal(integrationDir({ daemonDir: '/none', selfDaemon: '/tarball/daemon' }, (p) => p.endsWith('integration.ts')), null)
})

test('with no daemon running, the integration runs the daemon’s own code in a process of its own', async () => {
  // A scratch home, Claude folder and support folder: the real ones are never touched. The
  // command runs from this checkout, which has the daemon's packages.
  const home = join(scratch, 'int-home')
  mkdirSync(home, { recursive: true })
  const env = { ...process.env, HOME: home, ZDOTDIR: home, CLAUDE_CONFIG_DIR: join(home, '.claude'), HYPERNOVA_SUPPORT: join(home, 'support'), HYPERNOVA_LOGS: join(home, 'logs'), HYPERNOVA_LOCAL_PORT: String(await deadPort()) }
  const c = cliContext({ platform: 'linux', home, env, support: env.HYPERNOVA_SUPPORT, logs: env.HYPERNOVA_LOGS, localPort: Number(env.HYPERNOVA_LOCAL_PORT), daemonDir: join(home, 'not-installed'), selfDaemon: DAEMON, sys: realSys(env), tty: false })
  const on = await setIntegration(c, true)
  assert.equal(on.hooks.installed, true)
  const settings = JSON.parse(readFileSync(join(home, '.claude', 'settings.json'), 'utf8'))
  const url = settings.hooks.PreToolUse[0].hooks[0].url as string
  assert.ok(url.startsWith(`http://127.0.0.1:${env.HYPERNOVA_LOCAL_PORT}/hooks/`), url)
  // The `claude` function's hn is there before the daemon's first start.
  assert.match(readFileSync(join(env.HYPERNOVA_SUPPORT, 'bin', 'hn'), 'utf8'), /term\/cli\.ts/)
  const off = await setIntegration(c, false)
  assert.equal(off.hooks.installed, false)
  assert.equal(JSON.stringify(JSON.parse(readFileSync(join(home, '.claude', 'settings.json'), 'utf8')).hooks ?? {}).includes('/hooks/'), false)
  // Nothing to run it from: a plain answer.
  await assert.rejects(setIntegration({ ...c, selfDaemon: scratch }, true), /isn’t installed/)
})

test('who listens where, from the kernel’s tables: IPv4 and IPv6, listening sockets only', () => {
  // /proc/net/tcp as Linux prints it (x86_64 and arm64: each word little-endian).
  const tcp = `  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 0100007F:B8A1 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 1 1 0000000000000000 100 0 0 10 0
   1: 0100007F:B8A2 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 2 1 0000000000000000 100 0 0 10 0
   2: 00000000:B8A3 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 3 1 0000000000000000 100 0 0 10 0
   3: 0100007F:B8A1 0100007F:D431 01 00000000:00000000 00:00000000 00000000  1000        0 4 1 0000000000000000 20 4 30 10 -1
   4: 1400A8C0:0016 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 5 1 0000000000000000 100 0 0 10 0
`
  const tcp6 = `  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 00000000000000000000000001000000:B8A1 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 6 1 0000000000000000 100 0 0 10 0
   1: 00000000000000000000000000000000:B8A3 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 7 1 0000000000000000 100 0 0 10 0
   2: 0000000000000000FFFF00000100007F:B8A2 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 8 1 0000000000000000 100 0 0 10 0
`
  assert.deepEqual(parseProcNet(tcp), [
    { address: '127.0.0.1', port: 47265 },
    { address: '127.0.0.1', port: 47266 },
    { address: '0.0.0.0', port: 47267 },
    { address: '192.168.0.20', port: 22 },
  ])
  assert.deepEqual(parseProcNet(tcp6), [
    { address: '::1', port: 47265 },
    { address: '::', port: 47267 },
    { address: '::ffff:127.0.0.1', port: 47266 },
  ])
  const read = (f: string) => (f.endsWith('6') ? tcp6 : tcp)
  assert.deepEqual(listenersOn(47265, read), ['127.0.0.1', '::1'])
  assert.deepEqual(listenersOn(47267, read), ['0.0.0.0', '::'])
  assert.deepEqual(listenersOn(1, read), [])
  // No tables (not Linux): nothing known.
  assert.equal(listenersOn(47265, () => { throw new Error('ENOENT') }), null)
  assert.ok(['127.0.0.1', '127.1.2.3', '::1', '::ffff:127.0.0.1'].every(loopback))
  assert.ok(!['0.0.0.0', '::', '192.168.0.20', '::ffff:10.0.0.1'].some(loopback))
})

// ── The QR code ───────────────────────────────────────────────────────────

test('the QR code: the smallest version, the finder and timing patterns, the format bits it says', () => {
  assert.equal(versionFor(14, 'M'), 1)
  assert.equal(versionFor(15, 'M'), 2)
  assert.equal(versionFor(2953, 'L'), 40)
  assert.equal(versionFor(2954, 'L'), null)
  assert.throws(() => encodeQr('x'.repeat(3000), 'L'), /too long/)
  // A pairing link of a typical length.
  const link = 'https://hypernova.observer/pair#v=1&u=https%3A%2F%2Fmy-vps.tail1234.ts.net%3A47321&c=AbCdEfGhIjKlMnOpQrStUv&n=my-vps&fp=' + 'a1'.repeat(32) + '&lan=10.0.0.5&lp=47323&ts=my-vps.tail1234.ts.net&ti=100.64.0.7'
  const qr = encodeQr(link, 'M')
  assert.equal(qr.size, qr.version * 4 + 17)
  const m = qr.modules
  // Finder patterns: a dark ring, a light ring, a dark 3×3 core, in three corners.
  for (const [ox, oy] of [[0, 0], [qr.size - 7, 0], [0, qr.size - 7]]) {
    for (let y = 0; y < 7; y++) for (let x = 0; x < 7; x++) {
      const d = Math.max(Math.abs(x - 3), Math.abs(y - 3))
      assert.equal(m[oy! + y]![ox! + x], d !== 2, `finder at ${ox},${oy}`)
    }
  }
  for (let i = 8; i < qr.size - 8; i++) {
    assert.equal(m[6]![i], i % 2 === 0)
    assert.equal(m[i]![6], i % 2 === 0)
  }
  // The format bits around the top-left finder read back as level M and the mask chosen.
  const bits = formatBits('M', qr.mask)
  const read = [0, 1, 2, 3, 4, 5, 7, 8].map((y) => m[y]![8]).concat([m[8]![7]!, ...[5, 4, 3, 2, 1, 0].map((x) => m[8]![x]!)])
  assert.deepEqual(read, Array.from({ length: 15 }, (_, i) => ((bits >>> i) & 1) === 1))
  // The always-dark module.
  assert.equal(m[qr.size - 8]![8], true)
  // Half blocks: two module rows a line, with a quiet zone; colours only for a terminal.
  const lines = qrLines(qr, { quiet: 2 })
  assert.equal(lines.length, Math.ceil((qr.size + 4) / 2))
  assert.ok(lines.every((l) => [...l].length === qr.size + 4 && /^[ ▀▄█]+$/.test(l)))
  assert.ok(qrLines(qr, { ansi: true })[0]!.startsWith('\x1b[38;5;16;48;5;231m'))
})

// ── The commands, against the stand-in daemon ─────────────────────────────

const claudeAnswers = (signedIn: boolean) => (cmd: string, args: string[]): Answer => {
  if (cmd === 'claude' && args[0] === '--version') return { out: '2.1.302 (Claude Code)\n' }
  if (cmd === 'claude' && args[0] === 'auth') return { code: signedIn ? 0 : 1, out: JSON.stringify({ loggedIn: signedIn, subscriptionType: signedIn ? 'max' : null, email: 'never@kept' }) }
  if (cmd === 'node' && args[0] === '--version') return { out: 'v24.9.0\n' }
  if (cmd === 'node' && args[0] === '-e') return { code: 0 } // node-pty works
  return {}
}

test('pair: the QR code and its link when there’s no tailnet, with what to do about it', async () => {
  const sys = fakeSys(() => ({}))
  const c = ctx(sys, { apiPort: daemon.api, localPort: daemon.local })
  assert.equal(await pair(c, { 'no-wait': true }), 0)
  const text = sys.out.join('\n')
  assert.match(text, /Tailscale isn’t installed: this code pairs a phone on the same network only/)
  // The pairing link carries no local addresses here: the route is off, and it says so.
  assert.match(text, /The local network route is off too/)
  assert.ok(sys.out.some((l) => /^  [ ▀▄█]{20,}$/.test(l)), 'a QR code')
  // The same hypernova.observer link as over a tailnet: the app opens it either way.
  assert.ok(sys.out.includes(`  ${daemon.pairLink.link}`))
  assert.match(text, /it opens the Hypernova app/)
  assert.match(text, /It works once, for 10 minutes \(until \d\d:\d\d\)\./)
  // --no-qr leaves the code out.
  const quiet = fakeSys(() => ({}))
  await pair(ctx(quiet, { apiPort: daemon.api, localPort: daemon.local }), { 'no-wait': true, 'no-qr': true })
  assert.ok(!quiet.out.some((l) => /█/.test(l)))
})

test('pair waits for the phone and names it', async () => {
  const sys = fakeSys(() => ({}))
  const c = ctx(sys, { apiPort: daemon.api, localPort: daemon.local })
  daemon.pairLink = { link: daemon.pairLink.link, appLink: 'hypernova://pair?v=1&u=x&c=CODE&n=box&fp=ab&lan=192.168.1.20&lp=47323' }
  const t = setTimeout(() => daemon.devices.push({ id: 'd1', name: 'Pixel', model: 'Pixel 9' }), 300)
  try {
    assert.equal(await pair(c, {}), 0)
  } finally {
    clearTimeout(t)
    daemon.devices.length = 0
  }
  const text = sys.out.join('\n')
  assert.doesNotMatch(text, /local network route is off/)
  assert.match(text, /Waiting for the phone/)
  assert.match(text, /Paired with Pixel \(Pixel 9\)\./)
})

test('pair over the tailnet: tailscale serve turned on through the daemon, the https link in the code', async () => {
  // A stand-in tailscale command, so the command knows Tailscale is installed; the daemon answers for it.
  const ts = join(scratch, 'tailscale')
  writeFileSync(ts, '#!/bin/sh\nexit 0\n', { mode: 0o755 })
  const saved = structuredClone(daemon.status.tailscale)
  try {
    daemon.status.tailscale = { installed: true, loggedIn: true, httpsEnabled: true, serveOn: false, url: null }
    const sys = fakeSys(() => ({}))
    assert.equal(await pair(ctx(sys, { apiPort: daemon.api, localPort: daemon.local }, { HYPERNOVA_TAILSCALE: ts }), { 'no-wait': true }), 0)
    assert.ok(daemon.requests.includes('POST /local/tailscale/serve'))
    const text = sys.out.join('\n')
    assert.ok(sys.out.includes(`  ${daemon.pairLink.link}`), text)
    assert.match(text, /Scan it with the phone’s camera/)
    assert.doesNotMatch(text, /same network only/)
    // Where to get the app is said whenever the daemon knows (the page the code opens without the
    // app points here): the tailnet's page, or one on the local network's name (the daemon's names.ts).
    assert.doesNotMatch(text, /The app, if the phone/, 'no download link given, none said')
    daemon.pairLink = { ...daemon.pairLink, download: 'https://l-k3x9mq2z7a.hypernova.observer:47323/download/android' }
    const named = fakeSys(() => ({}))
    await pair(ctx(named, { apiPort: daemon.api, localPort: daemon.local }, { HYPERNOVA_TAILSCALE: ts }), { 'no-wait': true, 'no-qr': true })
    assert.match(named.out.join('\n'), /The app, if the phone doesn’t have it yet: https:\/\/l-k3x9mq2z7a\.hypernova\.observer:47323\/download\/android/)
    delete daemon.pairLink.download

    // Tailscale on Linux lets only root or its operator change serve: the fix names this user.
    daemon.status.tailscale = { installed: true, loggedIn: true, httpsEnabled: true, serveOn: false, url: null }
    daemon.serve = () => ({ error: 'Access denied: serve config denied. Use sudo, or set an operator.' })
    const denied = fakeSys(() => ({}))
    await pair(ctx(denied, { apiPort: daemon.api, localPort: daemon.local }, { HYPERNOVA_TAILSCALE: ts }), { 'no-wait': true })
    assert.match(denied.out.join('\n'), /! Tailscale lets only root or its operator change serve\.\n {6}Make yourself its operator once: sudo tailscale set --operator=ada/)
    // The same link, for the local network now.
    assert.ok(denied.out.includes(`  ${daemon.pairLink.link}`))

    // Signed out, or a tailnet without HTTPS certificates: said plainly, with where to fix it.
    daemon.status.tailscale = { installed: true, loggedIn: false, httpsEnabled: false, serveOn: false, url: null }
    const out = fakeSys(() => ({}))
    await pair(ctx(out, { apiPort: daemon.api, localPort: daemon.local }, { HYPERNOVA_TAILSCALE: ts }), { 'no-wait': true })
    assert.match(out.out.join('\n'), /Tailscale is installed but not signed in\.\n {6}sudo tailscale up/)
    daemon.status.tailscale = { installed: true, loggedIn: true, httpsEnabled: false, serveOn: false, url: null }
    const nocert = fakeSys(() => ({}))
    await pair(ctx(nocert, { apiPort: daemon.api, localPort: daemon.local }, { HYPERNOVA_TAILSCALE: ts }), { 'no-wait': true })
    assert.match(nocert.out.join('\n'), /HTTPS certificates off[^]*login\.tailscale\.com\/admin\/dns/)
  } finally {
    daemon.status.tailscale = saved
    daemon.serve = () => ({ url: 'https://box.tail.ts.net:47321' })
  }
})

test('pair, status and doctor when the daemon isn’t running', async () => {
  const dead = await deadPort()
  const p = fakeSys(claudeAnswers(false))
  assert.equal(await pair(ctx(p, { apiPort: dead, localPort: dead }), {}), 1)
  assert.match(p.out.join('\n'), /The daemon isn’t running\./)
  const s = fakeSys(claudeAnswers(false))
  assert.equal(await status(ctx(s, { apiPort: dead, localPort: dead }), {}), 1)
  assert.match(s.out.join('\n'), /Daemon +not running/)
  assert.match(s.out.join('\n'), /Start it: systemctl --user start hypernova \(or hypernova run\)/)
})

test('status: what runs, how phones reach it, Claude Code and the sessions', async () => {
  const sys = fakeSys(claudeAnswers(true))
  const c = ctx(sys, { apiPort: daemon.api, localPort: daemon.local }, { CLAUDE_BIN: '/bin/sh' })
  // CLAUDE_BIN stands for Claude Code; its answers come from the stand-in system.
  sys.run = ((orig) => (cmd: string, args: string[], o?: RunOptions) => orig(cmd === '/bin/sh' ? 'claude' : cmd, args, o))(sys.run)
  assert.equal(await status(c, {}), 0)
  const text = sys.out.join('\n')
  assert.match(text, /^Hypernova 0\.2\.0 on box$/m)
  assert.match(text, /Daemon +running/)
  assert.match(text, new RegExp(`Phone API +127\\.0\\.0\\.1:${daemon.api} \\(no Tailscale yet\\)`))
  assert.match(text, /Local route +port 47323 on 192\.168\.1\.20/)
  assert.match(text, /Claude Code +2\.1\.302, signed in \(max\)/)
  assert.doesNotMatch(text, /never@kept/)
  assert.match(text, /Integration +hooks, status line, claude function/)
  assert.match(text, /Sessions +1 working, 1 idle; 1 in shared terminals/)
  const json = fakeSys(claudeAnswers(true))
  await status(ctx(json, { apiPort: daemon.api, localPort: daemon.local }), { json: true })
  const o = JSON.parse(json.out.join('\n'))
  assert.equal(o.running, true)
  assert.equal(o.name, 'box')
})

test('doctor: each check with its fix; a missing login is a problem, a missing Tailscale worth a look', async () => {
  const sys = fakeSys(claudeAnswers(false), ['openssl', 'make', 'g++', 'python3'])
  const c = ctx(sys, { apiPort: daemon.api, localPort: daemon.local }, { CLAUDE_BIN: '/bin/sh' })
  sys.run = ((orig) => (cmd: string, args: string[], o?: RunOptions) => orig(cmd === '/bin/sh' ? 'claude' : cmd, args, o))(sys.run)
  // A recorded Node and an installed daemon.
  mkdirSync(join(c.daemonDir, 'src'), { recursive: true })
  writeFileSync(join(c.daemonDir, 'package.json'), '{"name":"hypernova-daemon"}')
  writeFileSync(join(c.daemonDir, 'src', 'main.ts'), '')
  writeFileSync(c.nodePathFile, process.execPath + '\n')
  sys.run = ((orig) => (cmd: string, args: string[], o?: RunOptions) => orig(cmd === process.execPath ? 'node' : cmd, args, o))(sys.run)
  // Who listens on the ports, as the kernel's table would say (this Mac has none to read).
  assert.equal(await doctor(c, {}, { listeners: () => ['127.0.0.1'] }), 1)
  const text = sys.out.join('\n')
  assert.match(text, /✓ Linux on /)
  assert.match(text, /✓ Node\.js v24\.9\.0/)
  assert.match(text, /✓ The daemon in ~\/\.local\/share\/hypernova\/app\/daemon/)
  assert.match(text, /✓ Shared terminals: node-pty works/)
  assert.match(text, /! No systemd user service here: This system has no systemd/)
  assert.match(text, /✓ Daemon answering as “box”/)
  assert.match(text, /✓ Phone API and local API on loopback only/)
  assert.match(text, /✗ Claude Code isn’t signed in\.\n {6}Run claude once on this server/)
  assert.match(text, /! Tailscale isn’t installed[^\n]*\n {6}curl -fsSL https:\/\/tailscale\.com\/install\.sh \| sh/)
  assert.match(text, /· Telescope web pages/)
  assert.match(text, /1 problem to fix, \d+ worth a look\./)
  // A port forward on all addresses: a problem, named.
  const open = fakeSys(claudeAnswers(true))
  await doctor(ctx(open, { apiPort: daemon.api, localPort: daemon.local }), {}, { listeners: (p) => (p === daemon.api ? ['127.0.0.1', '0.0.0.0', '::'] : ['::1']) })
  assert.match(open.out.join('\n'), new RegExp(`✗ Something listens on 0\\.0\\.0\\.0:${daemon.api}, \\[::\\]:${daemon.api}: Hypernova’s ports must be on loopback only\\.`))
  const json = fakeSys(claudeAnswers(true), ['openssl'])
  await doctor(ctx(json, { apiPort: daemon.api, localPort: daemon.local }), { json: true }, { listeners: () => [] })
  const checks = JSON.parse(json.out.join('\n'))
  assert.ok(checks.every((x: any) => typeof x.name === 'string' && ['ok', 'warn', 'fail', 'info'].includes(x.mark) && x.text))
  // No daemon installed and no build tools: the fix is apt's.
  assert.equal(checks.find((x: any) => x.name === 'daemon').mark, 'fail')
  assert.match(checks.find((x: any) => x.name === 'build').fix, /sudo apt-get install -y build-essential python3/)
})

test('logs: the last lines of the daemon’s log, or a plain word when there’s none yet', async () => {
  const sys = fakeSys(() => ({}))
  const c = ctx(sys)
  assert.equal(await logs(c, {}), 1)
  assert.match(sys.out[0]!, /No log yet at ~\/\.local\/state\/hypernova\/logs\/daemon\.log/)
  mkdirSync(c.logs, { recursive: true })
  writeFileSync(join(c.logs, 'daemon.log'), Array.from({ length: 300 }, (_, i) => `line ${i + 1}`).join('\n') + '\n')
  sys.out.length = 0
  assert.equal(await logs(c, { n: '3' }), 0)
  assert.deepEqual(sys.out, ['line 298', 'line 299', 'line 300'])
  sys.out.length = 0
  await logs(c, {})
  assert.equal(sys.out.length, 100)
  assert.equal(sys.out[0], 'line 201')
})

test('setup then uninstall: Node, the daemon, the command, the service with lingering, the integration; never the login', async () => {
  let lingers = false
  const sys = fakeSys((cmd, args) => {
    if (cmd === 'systemctl') return args[1] === 'is-active' ? { out: 'active\n' } : {}
    if (cmd === 'loginctl' && args[0] === 'show-user') return { out: lingers ? 'yes\n' : 'no\n' }
    if (cmd === 'loginctl' && args[0] === 'enable-linger') return (lingers = true), { code: 0 }
    if (cmd === 'loginctl') return { code: 0 }
    if (cmd === 'npm') return { code: 0 } // the packages "install"
    if (cmd === 'tar') return null
    return claudeAnswers(false)(cmd === 'node' || cmd === process.execPath.split('/').pop() ? 'node' : cmd, args)
  }, ['systemctl', 'loginctl', 'make', 'g++', 'python3', 'npm', 'claude'])
  const c = ctx(sys, { apiPort: daemon.api, localPort: daemon.local })
  assert.equal(await setup(c, { from: DAEMON }), 0, sys.out.join('\n'))
  const text = sys.out.join('\n')
  // Node: this test's own (24 or newer), recorded for the service.
  assert.equal(readFileSync(c.nodePathFile, 'utf8').trim(), process.execPath)
  // The daemon copied in whole, with the font Telescope draws the terminal with.
  assert.ok(existsSync(join(c.daemonDir, 'src', 'main.ts')))
  assert.ok(existsSync(join(c.daemonDir, 'src', 'cli', 'integration.ts')))
  assert.ok(existsSync(join(c.appDir, 'Fonts', 'JetBrainsMono.ttf')))
  assert.ok(!existsSync(join(c.appDir, 'daemon.new')) && !existsSync(join(c.appDir, 'daemon.old')))
  assert.ok(sys.calls.some((x) => x.cmd.endsWith('npm') && x.args[0] === 'ci' && x.args.includes('--omit=dev')))
  // The command and the service.
  assert.ok(ownLauncher(c.launcher))
  assert.match(readFileSync(c.unitFile, 'utf8'), /Restart=on-failure/)
  assert.ok(sys.calls.some((x) => x.cmd === 'loginctl' && x.args.join(' ') === 'enable-linger ada'))
  assert.match(text, /✓ The service: hypernova\.service, running, starts at boot with nobody logged in/)
  // The integration through the running daemon.
  assert.ok(daemon.requests.includes('POST /local/integration/install'))
  // Claude Code is there (the stand-in's), not signed in: the owner is told, nothing signs in for him.
  assert.ok(!sys.calls.some((x) => x.args.includes('login') || x.args.includes('setup-token')))
  assert.match(text, /Sign in to Claude: run `claude` once on this server/)
  assert.match(text, /Pair your phone: hypernova pair/)

  sys.calls.length = 0
  sys.out.length = 0
  assert.equal(await uninstall(c, { yes: true }), 0)
  assert.ok(daemon.requests.includes('POST /local/integration/uninstall'))
  // The name goes too, so its record doesn't linger in DNS.
  assert.ok(daemon.requests.includes('POST /local/name/release'))
  assert.match(sys.out.join('\n'), /k3x9mq2z7a under hypernova\.observer given up/)
  assert.ok(sys.calls.some((x) => x.cmd === 'systemctl' && x.args.join(' ') === '--user disable --now hypernova.service'))
  assert.ok(!existsSync(c.unitFile))
  assert.ok(!existsSync(c.launcher))
  assert.ok(!existsSync(c.appDir))
  // Paired phones and settings stay for a reinstall; --purge takes them.
  assert.ok(existsSync(c.support))
  assert.match(sys.out.join('\n'), /Claude Code and its sign-in stay/)
  // Lingering may serve the owner's other services: left on, with how to turn it off.
  assert.ok(!sys.calls.some((x) => x.args.includes('disable-linger')))
  assert.match(sys.out.join('\n'), /Lingering stays on for ada .*sudo loginctl disable-linger ada/)
  await uninstall(c, { yes: true, purge: true })
  assert.ok(!existsSync(c.support))
})

test('setup and pair point a Mac to its menu bar app', async () => {
  for (const f of [setup, pair, uninstall]) {
    const sys = fakeSys(() => ({}))
    assert.equal(await f(ctx(sys, { platform: 'darwin' }), { yes: true }), 1)
    assert.match(sys.out.join('\n'), /menu bar app/)
  }
})

test('setup stops at a missing build tool with the apt line that fixes it', async () => {
  const sys = fakeSys((cmd, args) => (cmd === 'node' && args[0] === '-e' ? { code: 1, err: 'Cannot find module node-pty' } : claudeAnswers(false)(cmd === process.execPath.split('/').pop() ? 'node' : cmd, args)), ['systemctl'])
  const c = ctx(sys, { apiPort: daemon.api, localPort: daemon.local })
  assert.equal(await setup(c, { from: DAEMON, 'no-claude': true }), 1)
  assert.match(sys.out.join('\n'), /✗ The daemon couldn’t be installed\.\n {6}Building the terminal module needs make, g\+\+, python3[^]*sudo apt-get install -y build-essential python3/)
  assert.ok(!existsSync(join(c.appDir, 'daemon.new')))
  assert.deepEqual(readdirSync(c.appDir).filter((x) => x.startsWith('daemon')), [])
})

test('the built-in Tailscale node (the daemon\'s tsnet.ts): status, doctor and pair say so, and pair shows its sign-in code first', async () => {
  const saved = structuredClone(daemon.status)
  const builtIn = { mode: 'builtIn', reason: 'nothing to install', available: true, enabled: true, helper: { state: 'Running', dnsName: 'hypernova-box.tail.ts.net', account: 'owner@example.com', listening: 'https://100.64.0.2:47321', tls: 'pinned' }, url: 'https://100.64.0.2:47321', controlUrl: null }
  try {
    daemon.status.tsnet = builtIn
    // status: the row names the node and the account.
    let sys = fakeSys(claudeAnswers(true))
    await status(ctx(sys, { apiPort: daemon.api, localPort: daemon.local }), {})
    assert.match(sys.out.join('\n'), /Phone API +127\.0\.0\.1:\d+ → https:\/\/100\.64\.0\.2:47321 \(built-in Tailscale, owner@example\.com\)/)
    // doctor: ok, with the pinned note.
    sys = fakeSys(claudeAnswers(true), ['openssl', 'make', 'g++', 'python3'])
    await doctor(ctx(sys, { apiPort: daemon.api, localPort: daemon.local }), { json: true })
    const checks = JSON.parse(sys.out.join('\n')) as { name: string; mark: string; text: string }[]
    const ts = checks.find((c) => c.name === 'tailscale')!
    assert.equal(ts.mark, 'ok')
    assert.match(ts.text, /built-in Tailscale, owner@example\.com; no HTTPS certificates/)
    // pair: the node runs, so straight to the code: the daemon's link (hypernova.observer's, the details after its #).
    sys = fakeSys(claudeAnswers(true))
    assert.equal(await pair(ctx(sys, { apiPort: daemon.api, localPort: daemon.local }), { 'no-wait': true, 'no-qr': true }), 0)
    assert.match(sys.out.join('\n'), /https:\/\/hypernova\.observer\/pair#v=1&u=x&c=CODE/)
    assert.match(sys.out.join('\n'), /it opens the Hypernova app/)
    assert.doesNotMatch(sys.out.join('\n'), /Tailscale isn’t installed/)
    // Needing a sign-in: the code is the sign-in link, and pair waits (here: not long).
    daemon.status.tsnet = { ...builtIn, url: null, helper: { state: 'NeedsLogin', loginUrl: 'https://login.tailscale.com/a/abc123' } }
    sys = fakeSys(claudeAnswers(true))
    // --no-wait (or no terminal at all): the sign-in is shown, nothing waits for it, the code comes at once.
    const t0 = Date.now()
    assert.equal(await pair(ctx(sys, { apiPort: daemon.api, localPort: daemon.local }), { 'no-wait': true, 'no-qr': true }), 0)
    assert.ok(Date.now() - t0 < 1500)
    assert.match(sys.out.join('\n'), /Sign in to connect your devices/)
    assert.match(sys.out.join('\n'), /it opens the Hypernova app/)
    assert.doesNotMatch(sys.out.join('\n'), /Waiting for the sign-in/)
    // --local: no Tailscale at all.
    sys = fakeSys(claudeAnswers(true))
    assert.equal(await pair(ctx(sys, { apiPort: daemon.api, localPort: daemon.local }), { local: true, 'no-wait': true, 'no-qr': true }), 0)
    assert.doesNotMatch(sys.out.join('\n'), /Sign in to connect your devices/)
    // In a terminal it waits for the sign-in to finish (here: not long).
    sys = fakeSys(claudeAnswers(true))
    const c = ctx(sys, { apiPort: daemon.api, localPort: daemon.local, tty: true })
    const t = setTimeout(() => { daemon.status.tsnet = builtIn }, 2500)
    assert.equal(await pair(c, { 'no-qr': true, 'no-wait-phone': true }), 0)
    clearTimeout(t)
    const text = sys.out.join('\n')
    assert.match(text, /Sign in to connect your devices/)
    assert.match(text, /https:\/\/login\.tailscale\.com\/a\/abc123/)
    assert.match(text, /Signed in as owner@example\.com/)
    // hypernova tailscale: the state in a line.
    sys = fakeSys(claudeAnswers(true))
    assert.equal(await tailscale(ctx(sys, { apiPort: daemon.api, localPort: daemon.local }), { 'no-qr': true }, []), 0)
  } finally {
    daemon.status = saved
  }
})

test('the private name: its terms said once, before the first code, and the daemon told; hypernova name on|off', async () => {
  const sys = fakeSys(() => ({}))
  const c = ctx(sys, { apiPort: daemon.api, localPort: daemon.local })
  // A daemon that hasn't been told yet (setup's test above told this stand-in already).
  daemon.name = { ...daemon.name, enabled: true, setting: true, termsShown: false }
  daemon.requests.length = 0
  assert.equal(await pair(c, { 'no-wait': true, 'no-qr': true }), 0)
  const text = sys.out.join('\n')
  assert.match(text, /A private name: .*hypernova\.observer.*Let’s Encrypt’s terms \(https:\/\/letsencrypt\.org\/repository\/\)/s)
  assert.match(text, /hypernova name off/)
  // Said, then the daemon told so, and only then the code (which counts for the name) asked for.
  const said = daemon.requests.indexOf('POST /local/settings')
  const code = daemon.requests.indexOf('POST /local/pair/start?name=1')
  assert.ok(said >= 0 && code > said, daemon.requests.join(' | '))
  assert.equal(daemon.name.termsShown, true)
  // Once: not on the next pair, on this or another shell.
  const again = fakeSys(() => ({}))
  await pair(ctx(again, { apiPort: daemon.api, localPort: daemon.local }), { 'no-wait': true, 'no-qr': true })
  assert.doesNotMatch(again.out.join('\n'), /A private name/)
  // Off: given up, and said so; a release that didn't get through is said too, with exit 1.
  const off = fakeSys(() => ({}))
  assert.equal(await name(ctx(off, { apiPort: daemon.api, localPort: daemon.local }), ['off']), 0)
  assert.match(off.out.join('\n'), /Private name off; k3x9mq2z7a was given up/)
  daemon.releaseOk = false
  const stuck = fakeSys(() => ({}))
  assert.equal(await name(ctx(stuck, { apiPort: daemon.api, localPort: daemon.local }), ['off']), 1)
  assert.match(stuck.out.join('\n'), /couldn’t be given up just now: its records stay in DNS/)
  daemon.releaseOk = true
  // While off, nothing is said on pair.
  daemon.name.termsShown = false
  const fresh = fakeSys(() => ({}))
  await pair(ctx(fresh, { apiPort: daemon.api, localPort: daemon.local }), { 'no-wait': true, 'no-qr': true })
  assert.doesNotMatch(fresh.out.join('\n'), /A private name/)
  assert.equal(await name(ctx(fakeSys(() => ({})), { apiPort: daemon.api, localPort: daemon.local }), ['on']), 0)
  assert.equal(await name(ctx(fakeSys(() => ({})), { apiPort: daemon.api, localPort: daemon.local }), ['maybe']), 2)
})

test('uninstall without a terminal and without --yes refuses; --purge signs the built-in node out of the tailnet', async () => {
  const saved = structuredClone(daemon.status)
  try {
    // No terminal (the test run has none): nothing is removed without --yes.
    let sys = fakeSys(() => ({}))
    let c = ctx(sys, { apiPort: daemon.api, localPort: daemon.local })
    mkdirSync(c.appDir, { recursive: true })
    assert.equal(await uninstall(c, {}), 1)
    assert.match(sys.out.join('\n'), /no terminal to ask in[^]*--yes/)
    assert.ok(existsSync(c.appDir))
    // --purge --yes with the built-in node running: signed out first, said so.
    daemon.status.tsnet = { mode: 'builtIn', reason: '', available: true, enabled: true, helper: { state: 'Running', listening: 'https://100.64.0.2:47321' }, url: 'x', controlUrl: null }
    daemon.requests.length = 0
    sys = fakeSys(() => ({}))
    c = ctx(sys, { apiPort: daemon.api, localPort: daemon.local })
    assert.equal(await uninstall(c, { yes: true, purge: true }), 0)
    const i = daemon.requests.indexOf('POST /local/tsnet/logout')
    assert.ok(i >= 0 && i < daemon.requests.indexOf('POST /local/name/release') + 1, daemon.requests.join(' | '))
    assert.match(sys.out.join('\n'), /built-in Tailscale node signed out of your tailnet/)
    // Without --purge the node stays, and the person is told where.
    mkdirSync(join(c.support, 'tsnet'), { recursive: true })
    sys = fakeSys(() => ({}))
    c = ctx(sys, { apiPort: daemon.api, localPort: daemon.local })
    mkdirSync(join(c.support, 'tsnet'), { recursive: true })
    daemon.requests.length = 0
    await uninstall(c, { yes: true })
    assert.ok(!daemon.requests.includes('POST /local/tsnet/logout'))
    assert.match(sys.out.join('\n'), /Tailscale node stays in your tailnet/)
  } finally {
    daemon.status = saved
  }
})

test('hypernova tailscale on|off|control choose between the Tailscale app and the built-in node, through the settings', async () => {
  const run = async (rest: string[]) => {
    const sys = fakeSys(() => ({}))
    daemon.bodies.length = 0
    const code = await tailscale(ctx(sys, { apiPort: daemon.api, localPort: daemon.local }), { 'no-qr': true }, rest)
    return { code, text: sys.out.join('\n'), body: daemon.bodies[0] }
  }
  let r = await run(['off'])
  assert.deepEqual(r.body, { builtInTailscale: false })
  assert.match(r.text, /Built-in Tailscale off: use the Tailscale app/)
  r = await run(['on'])
  assert.deepEqual(r.body, { builtInTailscale: true })
  r = await run(['control', 'https://hs.example.com'])
  assert.deepEqual(r.body, { tailscaleControlUrl: 'https://hs.example.com' })
  assert.equal(r.code, 0)
  r = await run(['control', 'none'])
  assert.deepEqual(r.body, { tailscaleControlUrl: '' })
  r = await run(['control'])
  assert.equal(r.code, 2)
})

test('hypernova logs --terminals reads the shared terminals’ log', async () => {
  const sys = fakeSys(() => ({}))
  const c = ctx(sys, { apiPort: daemon.api, localPort: daemon.local })
  mkdirSync(c.logs, { recursive: true })
  writeFileSync(join(c.logs, 'terminals.log'), '2026-01-01T00:00:00Z [abc] holder started\n')
  assert.equal(await logs(c, { terminals: true }), 0)
  assert.match(sys.out.join('\n'), /holder started/)
})

test('status shows the private name, and doctor warns when it has an error', async () => {
  const saved = daemon.name
  try {
    daemon.name = { ...daemon.name, enabled: true, host: 'l-k3x9.hypernova.observer', reason: 'Ready.', lastError: null }
    let sys = fakeSys(claudeAnswers(true))
    await status(ctx(sys, { apiPort: daemon.api, localPort: daemon.local }), {})
    assert.match(sys.out.join('\n'), /Name +l-k3x9\.hypernova\.observer \(Ready\.\)/)
    daemon.name = { ...daemon.name, lastError: { at: 1, message: 'the name service said 429' } }
    sys = fakeSys(claudeAnswers(true), ['openssl', 'make', 'g++', 'python3'])
    await doctor(ctx(sys, { apiPort: daemon.api, localPort: daemon.local }), { json: true })
    const checks = JSON.parse(sys.out.join('\n')) as { name: string; mark: string; text: string }[]
    assert.equal(checks.find((x) => x.name === 'name')?.mark, 'warn')
  } finally {
    daemon.name = saved
  }
})
