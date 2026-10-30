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
import { cliContext, realSys, wslHere, type Cli, type Ran, type RunOptions, type Sys, type WslHere } from '../src/cli/context.ts'
import { doctor, lan, logs, name, pair, parseArgs, setup, status, uninstall } from '../src/cli/commands.ts'
import { CARRIED, enableLinger, installService, userManager, launcherText, ownLauncher, removeService, serviceState, servicePath, unitText, userBusEnv } from '../src/cli/service.ts'
import { downloadNode, majorOf, nodeArch, pickRelease, shaFor } from '../src/cli/node.ts'
import { daemonIn, findClaudeBin, integrationDir, isDaemonDir, setIntegration } from '../src/cli/install.ts'
import { claudeCandidates } from '../src/platform.ts'
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
    env: { HOME: home, PATH: '/usr/bin:/bin', ...env },
    support: join(home, '.local/share/hypernova'),
    logs: join(home, '.local/state/hypernova/logs'),
    user: 'ada',
    uid: 1000,
    tty: false,
    // Not WSL, whatever machine runs the test (WSL's own tests, at the end, say otherwise).
    wsl: null,
    sys,
    ...o,
  })
}

// ── A stand-in daemon ─────────────────────────────────────────────────────

type Fake = { api: number; local: number; devices: any[]; status: any; name: any; releaseOk: boolean; requests: string[]; bodies: any[]; pairLink: { link: string; appLink: string; download?: string }; close: () => Promise<void> }
async function fakeDaemon(): Promise<Fake> {
  const f: Fake = {
    api: 0,
    local: 0,
    devices: [],
    requests: [],
    bodies: [],
    status: {
      daemonVersion: '0.2.0',
      hooks: { installed: true },
      statusLine: { installed: true, supported: true },
      shell: { installed: true, supported: true },
      devices: [],
      sessions: [{ state: 'working', term: { id: 't1' } }, { state: 'idle' }],
      account: { state: 'ok' },
      lan: { port: 47323, addresses: ['192.168.1.20'], fingerprint: 'ab', deviceCert: false },
    },
    // The listener off: the daemon's link is the app's own (no address a browser could open).
    pairLink: { link: 'hypernova://pair?v=1&u=http%3A%2F%2F10.0.2.2%3A47321&c=CODE&n=box&fp=ab', appLink: 'hypernova://pair?v=1&u=http%3A%2F%2F10.0.2.2%3A47321&c=CODE&n=box&fp=ab' },
    name: { enabled: true, setting: true, termsShown: false, reason: 'Not made yet.', terms: 'https://letsencrypt.org/repository/', host: null },
    releaseOk: true,
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
    if (path === '/local/devices') return send({ devices: f.devices })
    if (path === '/local/pair/start') return send({ ...f.pairLink, expiresAt: Date.now() + 600_000, url: 'x' })
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
  const link = 'https://192.168.1.48:47323/pair?c=AbCdEfGhIjKlMnOpQrStUv&n=my-server&fp=' + 'a1'.repeat(32) + '&lan=192.168.1.48,192.168.1.76&lp=47323'
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

test('pair: the QR code and its link, and what to do when the local route is off', async () => {
  const sys = fakeSys(() => ({}))
  const c = ctx(sys, { apiPort: daemon.api, localPort: daemon.local })
  assert.equal(await pair(c, { 'no-wait': true }), 0)
  const text = sys.out.join('\n')
  assert.doesNotMatch(text, /Tailscale/)
  // The pairing link carries no local addresses here: the route is off, and it says so.
  assert.match(text, /The local network route is off, so a phone has no way in yet\.\n {6}hypernova lan on/)
  assert.ok(sys.out.some((l) => /^  [ ▀▄█]{20,}$/.test(l)), 'a QR code')
  // With no listener the daemon's link is the app's own: pasted in the app, never a site.
  assert.ok(sys.out.includes(`  ${daemon.pairLink.link}`))
  assert.match(text, /Paste the link in Hypernova \(Paste pairing link\):/)
  assert.doesNotMatch(text, /hypernova\.observer/)
  assert.match(text, /It works once, for 10 minutes \(until \d\d:\d\d\)\./)
  // --no-qr leaves the code out.
  const quiet = fakeSys(() => ({}))
  await pair(ctx(quiet, { apiPort: daemon.api, localPort: daemon.local }), { 'no-wait': true, 'no-qr': true })
  assert.ok(!quiet.out.some((l) => /█/.test(l)))
})

test('pair waits for the phone and names it', async () => {
  const sys = fakeSys(() => ({}))
  const c = ctx(sys, { apiPort: daemon.api, localPort: daemon.local })
  // The listener on (the daemon's shape since 2026-10-30): the QR code is this server's own pairing page.
  daemon.pairLink = {
    link: 'https://192.168.1.20:47323/pair?c=CODE&n=box&fp=ab&lan=192.168.1.20&lp=47323',
    appLink: 'hypernova://pair?v=1&u=https%3A%2F%2F192.168.1.20%3A47323&c=CODE&n=box&fp=ab&lan=192.168.1.20&lp=47323',
  }
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

test('pair with the local route on: the link, no warning, and where to get the app when the daemon knows', async () => {
  // The link carries the local route (the test before left lan= in it).
  const sys = fakeSys(() => ({}))
  assert.equal(await pair(ctx(sys, { apiPort: daemon.api, localPort: daemon.local }), { 'no-wait': true }), 0)
  const text = sys.out.join('\n')
  assert.ok(sys.out.includes(`  ${daemon.pairLink.link}`), text)
  assert.match(text, /Scan it with the phone’s camera, on this network or over your VPN into it\. The browser warns once about this server’s own certificate/)
  assert.doesNotMatch(text, /no way in|Tailscale|hypernova\.observer/)
  // Where to get the app is said whenever the daemon knows (the page the code opens without the
  // app points here): the listener at this server's address.
  assert.doesNotMatch(text, /The app, if the phone/, 'no download link given, none said')
  daemon.pairLink = { ...daemon.pairLink, download: 'https://192.168.1.20:47323/download/android' }
  try {
    const local = fakeSys(() => ({}))
    await pair(ctx(local, { apiPort: daemon.api, localPort: daemon.local }), { 'no-wait': true, 'no-qr': true })
    assert.match(local.out.join('\n'), /The app, if the phone doesn’t have it yet: https:\/\/192\.168\.1\.20:47323\/download\/android/)
  } finally {
    delete daemon.pairLink.download
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
  assert.match(text, new RegExp(`Phone API +127\\.0\\.0\\.1:${daemon.api}$`, 'm'))
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

test('doctor: each check with its fix; a missing login is a problem', async () => {
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
  // The daemon copied in whole, with JetBrains Mono beside it.
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

test('the private name: its terms said once, before the first code, and the daemon told; hypernova name on|off', async () => {
  const sys = fakeSys(() => ({}))
  const c = ctx(sys, { apiPort: daemon.api, localPort: daemon.local })
  // A daemon that hasn't been told yet (setup's test above told this stand-in already).
  daemon.name = { ...daemon.name, enabled: true, setting: true, termsShown: false }
  daemon.requests.length = 0
  assert.equal(await pair(c, { 'no-wait': true, 'no-qr': true }), 0)
  const text = sys.out.join('\n')
  assert.match(text, /A private name: .*the name service configured here.*Let’s Encrypt’s terms \(https:\/\/letsencrypt\.org\/repository\/\)/s)
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
  // On with no name service configured (the default since 2026-10-30): said, and nothing to make.
  const on = fakeSys(() => ({}))
  assert.equal(await name(ctx(on, { apiPort: daemon.api, localPort: daemon.local }), ['on']), 0)
  assert.match(on.out.join('\n'), /no name service is configured, so there is no name/)
  daemon.name = { ...daemon.name, service: 'https://names.example' }
  const onWith = fakeSys(() => ({}))
  assert.equal(await name(ctx(onWith, { apiPort: daemon.api, localPort: daemon.local }), ['on']), 0)
  assert.match(onWith.out.join('\n'), /Private name on: it’s made or published when a code is shown\./)
  delete daemon.name.service
  assert.equal(await name(ctx(fakeSys(() => ({})), { apiPort: daemon.api, localPort: daemon.local }), ['maybe']), 2)
})

test('uninstall without a terminal and without --yes refuses; --purge --yes gives up the name and says nothing of Tailscale', async () => {
  // No terminal (the test run has none): nothing is removed without --yes.
  let sys = fakeSys(() => ({}))
  let c = ctx(sys, { apiPort: daemon.api, localPort: daemon.local })
  mkdirSync(c.appDir, { recursive: true })
  assert.equal(await uninstall(c, {}), 1)
  assert.match(sys.out.join('\n'), /no terminal to ask in[^]*--yes/)
  assert.ok(existsSync(c.appDir))
  daemon.requests.length = 0
  sys = fakeSys(() => ({}))
  c = ctx(sys, { apiPort: daemon.api, localPort: daemon.local })
  assert.equal(await uninstall(c, { yes: true, purge: true }), 0)
  assert.ok(daemon.requests.includes('POST /local/name/release'), daemon.requests.join(' | '))
  assert.doesNotMatch(sys.out.join('\n'), /Tailscale|tailnet/)
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

// ── WSL on a Windows PC (cli/wsl.ts; experimental, never yet run on a real Windows PC) ─────────

const WSL2: WslHere = { version: 2, systemd: true, container: false, distro: 'Ubuntu' }
/** A stand-in system whose wslinfo prints [mode] (null: there's no wslinfo). */
const wslSys = (mode: string | null, have: string[] = []) =>
  fakeSys((cmd, args) => (cmd === 'wslinfo' ? (mode === null ? { code: null, missing: true } : { out: `${mode}\n` }) : claudeAnswers(true)(cmd, args)), have)
/** doctor's checks, as --json gives them. */
async function checksOf(c: Cli): Promise<{ name: string; mark: string; text: string; fix?: string }[]> {
  await doctor(c, { json: true }, { listeners: () => ['127.0.0.1'] })
  return JSON.parse((c.sys as ReturnType<typeof fakeSys>).out.join('\n'))
}

test('WSL is read from the kernel and systemd’s folder: its version, systemd, a container, the distribution’s name', () => {
  const wsl2 = () => 'Linux version 6.6.87.2-microsoft-standard-WSL2 (root@build) (gcc 13.2.0) #1 SMP'
  assert.deepEqual(wslHere({ WSL_DISTRO_NAME: 'Ubuntu-24.04' }, (p) => p === '/run/systemd/system', wsl2), { version: 2, systemd: true, container: false, distro: 'Ubuntu-24.04' })
  // A service started by systemd has no WSL_DISTRO_NAME; Docker Desktop's containers run on WSL's kernel.
  assert.deepEqual(wslHere({}, (p) => p === '/.dockerenv', wsl2), { version: 2, systemd: false, container: true, distro: null })
  assert.equal(wslHere({}, () => true, () => 'Linux version 6.8.0-45-generic (buildd@lcy02) (gcc 13.2.0)'), null)
})

test('doctor under WSL 2’s NAT: experimental, systemd, keeping WSL running, and no way in for the phone until networking is mirrored', async () => {
  const checks = await checksOf(ctx(wslSys('nat', ['openssl']), { apiPort: daemon.api, localPort: daemon.local, wsl: WSL2 }))
  const by = (n: string) => checks.find((x) => x.name === n)!
  assert.equal(by('wsl').mark, 'info')
  assert.match(by('wsl').text, /WSL 2 on Windows: experimental, not yet tried on a real Windows PC/)
  assert.equal(by('wsl-systemd').mark, 'ok')
  assert.match(by('wsl-awake').fix!, /%UserProfile%\\\.wslconfig[^]*\[general\]\n {2}instanceIdleTimeout=-1\nthen wsl --shutdown/)
  assert.match(by('wsl-awake').fix!, /https:\/\/learn\.microsoft\.com\/windows\/wsl\/wsl-config/)
  // Behind the NAT a phone has no way in: said as a problem worth fixing, with the way out.
  assert.equal(by('wsl-network').mark, 'warn')
  assert.match(by('wsl-network').text, /WSL’s NAT networking: a phone can’t reach this Linux on the Wi‑Fi\./)
  assert.match(by('wsl-network').fix!, /networkingMode=mirrored under \[wsl2\]/)
  assert.ok(!checks.some((x) => /Tailscale/.test(x.text + (x.fix ?? ''))))
  // The daemon's listener on (it was told so): on a network no phone is on.
  assert.equal(by('lan').mark, 'warn')
  assert.match(by('lan').text, /behind WSL’s NAT network no phone can reach it/)
  // No wslinfo (a WSL older than 2.0.4): taken as NAT, with how to get it.
  const old = await checksOf(ctx(wslSys(null, ['openssl']), { apiPort: daemon.api, localPort: daemon.local, wsl: WSL2 }))
  assert.match(old.find((x) => x.name === 'wsl-network')!.text, /wslinfo didn’t say: taken as NAT, the default/)
  assert.match(old.find((x) => x.name === 'wsl-network')!.fix!, /wsl --update/)
})

test('doctor under WSL: mirrored networking needs the Hyper-V firewall rule; no systemd says how to turn it on; WSL 1 and Docker Desktop are named', async () => {
  const mirrored = await checksOf(ctx(wslSys('mirrored', ['openssl']), { apiPort: daemon.api, localPort: daemon.local, wsl: WSL2 }))
  const net = mirrored.find((x) => x.name === 'wsl-network')!
  assert.equal(net.mark, 'info')
  assert.match(net.text, /mirrored networking: this Linux has Windows’ own addresses/)
  assert.match(net.fix!, /New-NetFirewallHyperVRule -Name Hypernova -DisplayName "Hypernova local route" -Direction Inbound -VMCreatorId '\{40E0AC32-46A5-438A-A0B2-2B479E8F2E90\}' -Protocol TCP -LocalPorts 47323/)
  assert.match(net.fix!, /learn\.microsoft\.com\/windows\/wsl\/networking/)
  // The local route as on any PC there.
  assert.equal(mirrored.find((x) => x.name === 'lan')!.mark, 'ok')

  const nosd = await checksOf(ctx(wslSys('nat', ['openssl']), { apiPort: daemon.api, localPort: daemon.local, wsl: { ...WSL2, systemd: false } }))
  const sd = nosd.find((x) => x.name === 'wsl-systemd')!
  assert.equal(sd.mark, 'warn')
  assert.match(sd.fix!, /\/etc\/wsl\.conf[^]*\n {2}\[boot\]\n {2}systemd=true\nthen in PowerShell on Windows: wsl --shutdown, open Ubuntu again and run hypernova setup/)
  assert.match(nosd.find((x) => x.name === 'service')!.fix!, /Turn on systemd for WSL \(above\), then hypernova setup/)

  const one = ctx(wslSys('nat', ['openssl']), { apiPort: daemon.api, localPort: daemon.local, wsl: { ...WSL2, version: 1, distro: 'Debian' } })
  const v1 = await checksOf(one)
  assert.equal(v1.find((x) => x.name === 'wsl')!.mark, 'warn')
  assert.match(v1.find((x) => x.name === 'wsl')!.fix!, /wsl --set-version Debian 2/)
  assert.ok(!(one.sys as ReturnType<typeof fakeSys>).calls.some((x) => x.cmd === 'wslinfo'), 'WSL 1 has no wslinfo to ask')

  const box = await checksOf(ctx(wslSys(null, ['openssl']), { apiPort: daemon.api, localPort: daemon.local, wsl: { ...WSL2, container: true, systemd: false } }))
  assert.match(box.find((x) => x.name === 'wsl')!.text, /Docker Desktop on Windows/)
  assert.ok(!box.some((x) => x.name === 'wsl-systemd' || x.name === 'wsl-awake'), 'a container has no systemd to turn on')
  assert.doesNotMatch(box.find((x) => x.name === 'wsl-network')!.fix ?? '', /mirrored/)

  // Not WSL: nothing of it.
  const plain = await checksOf(ctx(fakeSys(claudeAnswers(true), ['openssl']), { apiPort: daemon.api, localPort: daemon.local }))
  assert.ok(!plain.some((x) => x.name.startsWith('wsl')))
})

test('pair under WSL: behind the NAT a code says it has no way in; mirrored pairs on the Wi‑Fi', async () => {
  let sys = wslSys('nat')
  assert.equal(await pair(ctx(sys, { apiPort: daemon.api, localPort: daemon.local, wsl: WSL2 }), { 'no-wait': true, 'no-qr': true }), 0)
  let text = sys.out.join('\n')
  assert.match(text, /! WSL on Windows: WSL’s NAT network keeps this PC off the phone’s Wi‑Fi, so this code has no way in\.\n {6}Mirrored networking on Windows 11/)
  // Not the plain Linux words about the local route.
  assert.doesNotMatch(text, /local network route is off|Tailscale/)
  assert.ok(sys.out.includes(`  ${daemon.pairLink.link}`))

  sys = wslSys('mirrored')
  await pair(ctx(sys, { apiPort: daemon.api, localPort: daemon.local, wsl: WSL2 }), { 'no-wait': true, 'no-qr': true })
  text = sys.out.join('\n')
  assert.match(text, /WSL’s mirrored networking: a phone on the same Wi‑Fi can pair directly once Windows’ Hyper-V firewall lets port 47323 in/)
  assert.doesNotMatch(text, /no way in|Tailscale/)
})

test('hypernova lan on under WSL’s NAT says no phone can reach the port; setup without systemd says how to turn it on and touches no systemctl', async () => {
  const l = wslSys('nat')
  assert.equal(await lan(ctx(l, { apiPort: daemon.api, localPort: daemon.local, wsl: WSL2 }), ['on']), 0)
  assert.match(l.out.join('\n'), /! Behind WSL’s NAT network no phone can reach this port, and pairing links leave its addresses out\.\n {6}Mirrored networking makes it reachable/)
  const m = wslSys('mirrored')
  await lan(ctx(m, { apiPort: daemon.api, localPort: daemon.local, wsl: WSL2 }), ['on'])
  assert.doesNotMatch(m.out.join('\n'), /no phone can reach/)

  const sys = fakeSys((cmd, args) => {
    if (cmd === 'npm') return { code: 0 }
    if (cmd === 'tar') return null
    return claudeAnswers(false)(cmd === 'node' || cmd === process.execPath.split('/').pop() ? 'node' : cmd, args)
  }, ['systemctl', 'loginctl', 'make', 'g++', 'python3', 'npm', 'claude'])
  const c = ctx(sys, { apiPort: daemon.api, localPort: daemon.local, wsl: { ...WSL2, systemd: false } })
  assert.equal(await setup(c, { from: DAEMON }), 0, sys.out.join('\n'))
  const text = sys.out.join('\n')
  assert.match(text, /! WSL doesn’t run systemd here, so there’s no service yet[^]*\[boot\]\n {8}systemd=true[^]*wsl --shutdown/)
  assert.match(text, /Until then: hypernova run, in a terminal you keep open\./)
  assert.ok(!sys.calls.some((x) => x.cmd === 'systemctl' || x.cmd === 'loginctl'), 'no systemd to ask')
  assert.ok(!existsSync(c.unitFile))
  assert.match(text, /Keep WSL running for the phone: instanceIdleTimeout=-1 under \[general\] in %UserProfile%\\\.wslconfig on Windows/)
  assert.match(text, /Pair your phone: hypernova pair \(WSL keeps this PC off the Wi‑Fi unless its networking is mirrored\)/)
  await uninstall(c, { yes: true, purge: true })
})

test('under WSL, Claude Code’s Windows install on Windows’ PATH is never taken for the agent', (t) => {
  const win = '/mnt/c/Users/ada/AppData/Roaming/npm/claude'
  const on = (path: string): Sys => ({ ...fakeSys(() => ({})), which: (n) => (n === 'claude' ? path : null) })
  const c = ctx(on(win), { wsl: WSL2 })
  // A Claude Code in a system folder would be found first: the PATH isn't asked then.
  if (claudeCandidates('linux', c.home).some((p) => existsSync(p))) return t.skip('a Claude Code in a system folder here')
  assert.equal(findClaudeBin(c), null)
  // Not WSL: whatever the PATH has, as before.
  assert.equal(findClaudeBin(ctx(on(win))), win)
  // The Linux build inside WSL: found.
  assert.equal(findClaudeBin(ctx(on('/usr/local/bin/claude'), { wsl: WSL2 })), '/usr/local/bin/claude')
})
