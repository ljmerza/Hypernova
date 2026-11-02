// The `hypernova` command's Mac side (daemon/src/cli/launchd.ts and the darwin branches of the
// commands), mirroring linux-cli.test.ts: the plist it writes, the launchctl lines it runs, how it
// reads launchctl's answers, and setup, status, doctor, logs and uninstall against a stand-in daemon
// and a stand-in launchctl, in a scratch home. This runs on Linux too, so it proves what the code
// says, not what a Mac does: launchctl's answers here are what its documents and the old Mac app
// show. scripts/test-macos-smoke.sh, on CI's Mac runners, is the test against the real thing.
import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { createServer, type Server } from 'node:http'
import { tmpdir } from 'node:os'
import { execFileSync } from 'node:child_process'
import { join } from 'node:path'
import { cliContext, type Cli, type Ran, type RunOptions, type Sys } from '../src/cli/context.ts'
import { doctor, logs, pair, setup, status, uninstall } from '../src/cli/commands.ts'
import { agentPath, agentState, agentTarget, disabledIn, guiDomain, guiSession, installAgent, parseLaunchPrint, plistText, removeAgent, restartHint, serviceName, startHint, stderrLog } from '../src/cli/launchd.ts'
import { missingBuildTools } from '../src/cli/install.ts'
import { pickRelease } from '../src/cli/node.ts'
import { LAUNCH_LABEL, MAC_PATH_LINE, launchAgentsDir, places } from '../src/platform.ts'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-mac-cli-'))
after(() => rmSync(scratch, { recursive: true, force: true }))
const DAEMON = join(import.meta.dirname, '..')

type Call = { cmd: string; args: string[]; o?: RunOptions }
type Answer = Partial<Ran> | null
function fakeSys(answer: (cmd: string, args: string[]) => Answer, have: string[] = []): Sys & { calls: Call[]; out: string[] } {
  const calls: Call[] = []
  const out: string[] = []
  return {
    calls,
    out,
    async run(cmd, args, o) {
      calls.push({ cmd, args, o })
      return { code: 0, out: '', err: '', missing: false, ...answer(cmd.split('/').pop()!, args) }
    },
    which: (name) => (have.includes(name) ? `/usr/bin/${name}` : null),
    say: (line = '') => void out.push(line),
  }
}

let n = 0
/** A scratch home laid out as a Mac user's. */
function ctx(sys: Sys, o: Partial<Cli> = {}, env: NodeJS.ProcessEnv = {}): Cli {
  const home = join(scratch, `home-${++n}`)
  mkdirSync(home, { recursive: true })
  const e = { HOME: home, PATH: '/usr/bin:/bin', ...env }
  return cliContext({ platform: 'darwin', arch: 'arm64', home, env: e, ...places('darwin', e, home), user: 'ada', uid: 501, tty: false, wsl: null, sys, ...o })
}

/** A launchctl with a loaded or absent agent, in a session at the screen or not. */
function launchd(o: { gui?: boolean; loaded?: boolean; print?: string; disabled?: string; bootstrapFails?: number } = {}) {
  const st = { gui: o.gui ?? true, loaded: o.loaded ?? false, fails: o.bootstrapFails ?? 0 }
  const answer = (cmd: string, args: string[]): Answer => {
    if (cmd !== 'launchctl') return {}
    const [verb, what] = args
    if (verb === 'print') {
      if (what === 'gui/501') return st.gui ? { out: 'gui/501 = {\n}\n' } : { code: 113, err: 'Could not find domain for gui/501\n' }
      return st.loaded ? { out: o.print ?? '' } : { code: 113, err: 'Could not find service\n' }
    }
    if (verb === 'print-disabled') return { out: o.disabled ?? '' }
    if (verb === 'bootout') {
      st.loaded = false
      return {}
    }
    if (verb === 'bootstrap') {
      if (st.fails-- > 0) return { code: 5, err: 'Bootstrap failed: 5: Input/output error\n' }
      st.loaded = true
      return {}
    }
    return {}
  }
  return { st, answer }
}

const PRINT = `gui/501/${LAUNCH_LABEL} = {
	active count = 1
	path = /Users/ada/Library/LaunchAgents/${LAUNCH_LABEL}.plist
	type = LaunchAgent
	state = running

	program = /Users/ada/node
	pid = 4242
	immediate reason = speculative
	last exit code = (never exited)

	spawn type = interactive (4)
	endpoints = {
		"x" = {
			state = active
		}
	}
}
`

// ── A stand-in daemon ─────────────────────────────────────────────────────

let api = 0
let loc = 0
const servers: Server[] = []
const requests: string[] = []
before(async () => {
  const handler = (req: any, res: any) => {
    requests.push(`${req.method} ${req.url}`)
    const send = (o: unknown) => {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify(o))
    }
    const path = new URL(req.url, 'http://x').pathname
    if (path === '/v1/health') return send({ ok: true, protocol: 1, name: 'macbook' })
    if (path === '/local/status') return send({ daemonVersion: '1.0.0', hooks: { installed: true }, statusLine: { installed: true, supported: true }, shell: { installed: true, supported: true }, devices: [], sessions: [], account: { state: 'ok' }, lan: { port: 47323, addresses: ['192.168.1.20'], fingerprint: 'ab', deviceCert: false } })
    if (path === '/local/devices') return send({ devices: [] })
    if (path === '/local/pair/start') return send({ link: 'https://192.168.1.20:47323/pair?c=CODE', appLink: 'hypernova://pair?lan=192.168.1.20', expiresAt: Date.now() + 600_000, url: 'x' })
    if (path === '/local/settings') return req.resume().on('end', () => send({}))
    if (path.startsWith('/local/integration/')) return send({ hooks: { installed: path.endsWith('install') }, statusLine: { installed: true, supported: true }, shell: { installed: true, supported: true } })
    res.writeHead(404)
    res.end('{}')
  }
  for (const set of [(p: number) => (api = p), (p: number) => (loc = p)]) {
    const s = createServer(handler)
    await new Promise<void>((r) => s.listen(0, '127.0.0.1', r))
    set((s.address() as any).port)
    servers.push(s)
  }
})
after(() => Promise.all(servers.map((s) => new Promise<void>((r) => s.close(() => r())))).then(() => {}))
async function deadPort(): Promise<number> {
  const s = createServer()
  await new Promise<void>((r) => s.listen(0, '127.0.0.1', r))
  const p = (s.address() as any).port
  await new Promise<void>((r) => s.close(() => r()))
  return p
}
const claudeSays = (signedIn: boolean) => (cmd: string, args: string[]): Answer => {
  if (cmd === 'claude' && args[0] === '--version') return { out: '2.1.302 (Claude Code)\n' }
  if (cmd === 'claude' && args[0] === 'auth') return { code: signedIn ? 0 : 1, out: JSON.stringify({ loggedIn: signedIn, subscriptionType: signedIn ? 'max' : null }) }
  return null
}
const nodeSays = (cmd: string, args: string[]): Answer => {
  if (args[0] === '--version') return { out: 'v24.9.0\n' }
  if (args[0] === '-e') return { code: 0 } // node-pty works
  return null
}
/** launchctl, Claude Code and Node answered; [node] by this test's own program name. */
function machine(l: ReturnType<typeof launchd>, signedIn = false, more: (cmd: string, args: string[]) => Answer = () => null) {
  const own = process.execPath.split('/').pop()
  return (cmd: string, args: string[]): Answer => more(cmd, args) ?? claudeSays(signedIn)(cmd, args) ?? (cmd === 'node' || cmd === own ? nodeSays(cmd, args) : null) ?? l.answer(cmd, args)
}

// ── Where things go ───────────────────────────────────────────────────────

test('a Mac’s folders: Library’s, with the plist named for its label; HYPERNOVA_ variables still win', () => {
  const c = ctx(fakeSys(() => ({})))
  assert.equal(c.support, join(c.home, 'Library/Application Support/Hypernova'))
  assert.equal(c.logs, join(c.home, 'Library/Logs/Hypernova'))
  assert.equal(c.unitDir, launchAgentsDir(c.home))
  assert.equal(c.unitFile, join(c.home, 'Library/LaunchAgents', `${LAUNCH_LABEL}.plist`))
  assert.equal(c.launcher, join(c.home, '.local/bin/hypernova'))
  assert.equal(c.daemonDir, join(c.support, 'app/daemon'))
  const t = places('darwin', { HYPERNOVA_SUPPORT: '/s', HYPERNOVA_LOGS: '/l' }, '/Users/ada')
  assert.deepEqual(t, { support: '/s', logs: '/l' })
})

test('the words for the service: its label and launchctl on a Mac, its unit and systemctl on Linux', () => {
  const mac = { platform: 'darwin', uid: 501 } as const
  const linux = { platform: 'linux', uid: 1000 } as const
  assert.equal(serviceName(mac), LAUNCH_LABEL)
  assert.equal(serviceName(linux), 'hypernova.service')
  assert.equal(startHint(mac), `launchctl kickstart -k gui/501/${LAUNCH_LABEL}`)
  assert.equal(restartHint(mac), `launchctl kickstart -k gui/501/${LAUNCH_LABEL}`)
  assert.equal(startHint(linux), 'systemctl --user start hypernova')
  assert.equal(restartHint(linux), 'systemctl --user restart hypernova')
})

// ── The plist ─────────────────────────────────────────────────────────────

test('the plist: runs the installed daemon with its Node, kept alive, started at load, holders left alone, logs apart', () => {
  const c = ctx(fakeSys(() => ({})))
  const t = plistText(c, '/opt/node/bin/node')
  assert.ok(t.startsWith('<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE plist'))
  assert.match(t, new RegExp(`<key>Label</key>\\s*<string>${LAUNCH_LABEL.replace(/\./g, '\\.')}</string>`))
  assert.match(t, /<key>ProgramArguments<\/key>\s*<array>\s*<string>\/opt\/node\/bin\/node<\/string>\s*<string>--disable-warning=ExperimentalWarning<\/string>\s*<string>[^<]*\/Library\/Application Support\/Hypernova\/app\/daemon\/src\/main\.ts<\/string>\s*<\/array>/)
  assert.match(t, /<key>WorkingDirectory<\/key>\s*<string>[^<]*\/app\/daemon<\/string>/)
  for (const k of ['RunAtLoad', 'KeepAlive', 'AbandonProcessGroup']) assert.match(t, new RegExp(`<key>${k}</key>\\s*<true/>`), k)
  assert.match(t, /<key>ThrottleInterval<\/key>\s*<integer>10<\/integer>/)
  assert.match(t, /<key>StandardOutPath<\/key>\s*<string>\/dev\/null<\/string>/)
  assert.match(t, new RegExp(`<key>StandardErrorPath</key>\\s*<string>${stderrLog(c).replace(/[.]/g, '\\.')}</string>`))
  assert.match(t, new RegExp(`<key>HOME</key>\\s*<string>${c.home}</string>`))
  assert.match(t, /<key>LANG<\/key>\s*<string>en_US\.UTF-8<\/string>/)
  // Every element opened is closed, in order: what plutil checks on the Mac runner.
  const stack: string[] = []
  for (const m of t.replace(/<\?xml[^>]*>|<!DOCTYPE[^>]*>|<!--[\s\S]*?-->/g, '').matchAll(/<(\/?)([a-z]+)[^>]*?(\/?)>/gi)) {
    if (m[3]) continue
    if (m[1]) assert.equal(stack.pop(), m[2])
    else stack.push(m[2]!)
  }
  assert.deepEqual(stack, [])
})

test('the plist escapes what XML would take for markup, and carries a test daemon’s variables', () => {
  const c = ctx(fakeSys(() => ({})), { daemonDir: '/Users/a&b/<x>/daemon' }, { HYPERNOVA_SUPPORT: '/tmp/a&b', HYPERNOVA_API_PORT: '47999' })
  const t = plistText(c, '/opt/n&de/node')
  assert.match(t, /<string>\/opt\/n&amp;de\/node<\/string>/)
  assert.match(t, /<string>\/Users\/a&amp;b\/&lt;x&gt;\/daemon<\/string>/)
  assert.match(t, /<key>HYPERNOVA_SUPPORT<\/key>\s*<string>\/tmp\/a&amp;b<\/string>/)
  assert.match(t, /<key>HYPERNOVA_API_PORT<\/key>\s*<string>47999<\/string>/)
  assert.doesNotMatch(plistText(ctx(fakeSys(() => ({}))), '/n'), /HYPERNOVA_/)
})

test('the agent’s PATH: its Node first, then ~/.local/bin, Homebrew’s, the system’s, no repeats', () => {
  assert.equal(agentPath({ home: '/Users/ada' }, '/usr/local/bin/node'), '/usr/local/bin:/Users/ada/.local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin')
  assert.ok(agentPath({ home: '/Users/ada' }, '/Users/ada/Library/Application Support/Hypernova/node/v24.9.0/bin/node').startsWith('/Users/ada/Library/Application Support/Hypernova/node/v24.9.0/bin:/Users/ada/.local/bin:/opt/homebrew/bin'))
})

test('a Mac’s shell block puts ~/.local/bin on PATH only when it isn’t there', () => {
  const after = (path: string) => execFileSync('/bin/bash', ['-c', `${MAC_PATH_LINE}; printf %s "$PATH"`], { env: { HOME: '/Users/ada', PATH: path }, encoding: 'utf8' })
  assert.equal(after('/usr/bin:/bin'), '/Users/ada/.local/bin:/usr/bin:/bin')
  assert.equal(after('/usr/bin:/Users/ada/.local/bin:/bin'), '/usr/bin:/Users/ada/.local/bin:/bin')
})

// ── What launchctl says ───────────────────────────────────────────────────

test('launchctl print: the job’s own state and pid, not a nested one; a stopped job has no pid', () => {
  assert.deepEqual(parseLaunchPrint(PRINT), { state: 'running', pid: 4242 })
  assert.deepEqual(parseLaunchPrint('x = {\n\tstate = not running\n\tlast exit code = 1\n}\n'), { state: 'not', pid: null })
  assert.deepEqual(parseLaunchPrint(''), { state: null, pid: null })
})

test('launchctl print-disabled: both the words and the booleans older systems print', () => {
  assert.equal(disabledIn(`disabled services = {\n\t"${LAUNCH_LABEL}" => disabled\n}`), true)
  assert.equal(disabledIn(`disabled services = {\n\t"${LAUNCH_LABEL}" => true\n}`), true)
  assert.equal(disabledIn(`disabled services = {\n\t"${LAUNCH_LABEL}" => enabled\n}`), false)
  assert.equal(disabledIn(`disabled services = {\n\t"${LAUNCH_LABEL}" => false\n}`), false)
  assert.equal(disabledIn('disabled services = {\n\t"com.other" => disabled\n}'), false)
})

test('whether there’s a session at the screen, in launchctl’s words when there isn’t', async () => {
  assert.deepEqual(await guiSession({ sys: fakeSys(launchd().answer), uid: 501 }), { ok: true, why: '' })
  const no = await guiSession({ sys: fakeSys(launchd({ gui: false }).answer), uid: 501 })
  assert.equal(no.ok, false)
  assert.match(no.why, /Could not find domain/)
})

// ── Loading it ────────────────────────────────────────────────────────────

test('installing writes the plist 0644, unloads what’s there, enables and bootstraps it in the gui domain', async () => {
  const l = launchd({ loaded: true })
  const sys = fakeSys(l.answer)
  const c = ctx(sys)
  await installAgent(c, '/opt/node', { stepMs: 0 })
  assert.equal(statSync(c.unitFile).mode & 0o777, 0o644)
  assert.match(readFileSync(c.unitFile, 'utf8'), /<key>KeepAlive<\/key>/)
  assert.ok(existsSync(c.logs))
  const lines = sys.calls.filter((x) => x.cmd === '/bin/launchctl').map((x) => x.args.join(' '))
  const target = `gui/501/${LAUNCH_LABEL}`
  assert.deepEqual(lines, [`bootout ${target}`, `print ${target}`, `enable ${target}`, `bootstrap gui/501 ${c.unitFile}`])
  assert.equal(agentTarget(c), target)
  assert.equal(guiDomain(c), 'gui/501')
})

test('bootstrap is tried again while launchd lets go of the old job, and its own words come out when it never does', async () => {
  const sys = fakeSys(launchd({ bootstrapFails: 2 }).answer)
  await installAgent(ctx(sys), '/opt/node', { stepMs: 0 })
  assert.equal(sys.calls.filter((x) => x.args[0] === 'bootstrap').length, 3)
  const stuck = fakeSys(launchd({ bootstrapFails: 99 }).answer)
  await assert.rejects(installAgent(ctx(stuck), '/opt/node', { stepMs: 0 }), /launchctl bootstrap gui\/501: Bootstrap failed: 5: Input\/output error/)
  assert.equal(stuck.calls.filter((x) => x.args[0] === 'bootstrap').length, 4)
})

test('removing unloads the agent and takes its plist; one launchd keeps is said', async () => {
  const sys = fakeSys(launchd({ loaded: true }).answer)
  const c = ctx(sys)
  mkdirSync(c.unitDir, { recursive: true })
  writeFileSync(c.unitFile, 'x')
  assert.deepEqual(await removeAgent(c, { stepMs: 0 }), [])
  assert.ok(!existsSync(c.unitFile))
  assert.ok(sys.calls.some((x) => x.args[0] === 'bootout'))
  // Not loaded: no bootout to run, and nothing to say.
  const idle = fakeSys(launchd().answer)
  assert.deepEqual(await removeAgent(ctx(idle), { stepMs: 0 }), [])
  assert.ok(!idle.calls.some((x) => x.args[0] === 'bootout'))
  // launchd won't let go.
  const keeps = fakeSys((cmd, args) => (args[0] === 'print' ? { out: PRINT } : {}))
  const notes = await removeAgent(ctx(keeps), { stepMs: 0 })
  assert.match(notes[0]!, /launchd still has com\.prudnikweb\.hypernova\.daemon: launchctl bootout/)
})

test('the agent’s state: loaded and running, disabled, stopped, or not loaded at all', async () => {
  const running = ctx(fakeSys(launchd({ loaded: true, print: PRINT }).answer))
  mkdirSync(running.unitDir, { recursive: true })
  writeFileSync(running.unitFile, 'x')
  assert.deepEqual(await agentState(running), { installed: true, enabled: true, active: true, pid: 4242 })

  const off = ctx(fakeSys(launchd({ loaded: true, print: PRINT, disabled: `"${LAUNCH_LABEL}" => disabled` }).answer))
  mkdirSync(off.unitDir, { recursive: true })
  writeFileSync(off.unitFile, 'x')
  assert.equal((await agentState(off)).enabled, false)

  const stopped = ctx(fakeSys(launchd({ loaded: true, print: 'x = {\n\tstate = waiting\n}\n' }).answer))
  assert.deepEqual(await agentState(stopped), { installed: false, enabled: false, active: false, pid: null })

  assert.deepEqual(await agentState(ctx(fakeSys(launchd().answer))), { installed: false, enabled: false, active: false, pid: null })
})

// ── The commands ──────────────────────────────────────────────────────────

test('setup then uninstall on a Mac: the daemon, the command, the LaunchAgent, the integration; no systemd, no lingering', async () => {
  const l = launchd({ print: PRINT })
  const sys = fakeSys(machine(l), ['npm', 'claude'])
  const c = ctx(sys, { apiPort: api, localPort: loc })
  assert.equal(await setup(c, { from: DAEMON }), 0, sys.out.join('\n'))
  const text = sys.out.join('\n')
  assert.match(text, new RegExp(`✓ The service: ${LAUNCH_LABEL.replace(/\./g, '\\.')}, running, starts when you log in`))
  assert.match(readFileSync(c.unitFile, 'utf8'), /<key>AbandonProcessGroup<\/key>/)
  assert.ok(existsSync(join(c.daemonDir, 'src', 'main.ts')))
  assert.ok(existsSync(c.launcher))
  assert.ok(requests.includes('POST /local/integration/install'))
  assert.ok(!sys.calls.some((x) => /systemctl|loginctl/.test(x.cmd)), 'nothing of systemd')
  assert.match(text, /Sign in to Claude: run `claude` once on this Mac/)
  // ~/.local/bin isn't on this PATH: what a Mac says, not Ubuntu's login.
  assert.match(text, /! The hypernova command .*\n {6}Not on your PATH yet: open a new terminal/)
  assert.doesNotMatch(text, /Ubuntu/)
  // This test installs from a checkout; a Mac doesn't update itself either way (server-update.ts).
  assert.equal(JSON.parse(readFileSync(join(c.daemonDir, 'install.json'), 'utf8')).source, 'checkout')

  sys.calls.length = 0
  sys.out.length = 0
  assert.equal(await uninstall(c, { yes: true }), 0, sys.out.join('\n'))
  assert.ok(requests.includes('POST /local/integration/uninstall'))
  assert.ok(sys.calls.some((x) => x.args[0] === 'bootout'))
  assert.ok(!existsSync(c.unitFile))
  assert.ok(!existsSync(c.launcher))
  assert.ok(!existsSync(c.appDir))
  assert.ok(existsSync(c.support))
  const out = sys.out.join('\n')
  assert.match(out, new RegExp(`✓ ${LAUNCH_LABEL.replace(/\./g, '\\.')} stopped and removed`))
  assert.doesNotMatch(out, /Lingering|menu bar/)
  await uninstall(c, { yes: true, purge: true })
  assert.ok(!existsSync(c.support))
})

test('setup with nobody logged in at the screen says how, installs no agent and still finishes', async () => {
  const sys = fakeSys(machine(launchd({ gui: false })), ['npm', 'claude'])
  const c = ctx(sys, { apiPort: api, localPort: loc })
  assert.equal(await setup(c, { from: DAEMON }), 0, sys.out.join('\n'))
  const text = sys.out.join('\n')
  assert.match(text, /! Nobody is logged in at this Mac’s screen, so there’s no service yet: Could not find domain/)
  assert.match(text, /Automatic login/)
  assert.match(text, /Until then: hypernova run/)
  assert.ok(!existsSync(c.unitFile))
  assert.ok(!sys.calls.some((x) => x.args[0] === 'bootstrap'))
})

test('setup when launchd refuses: launchctl’s words and where macOS may have turned it off', async () => {
  const sys = fakeSys(machine(launchd({ bootstrapFails: 99 })), ['npm', 'claude'])
  const c = ctx(sys, { apiPort: api, localPort: loc })
  assert.equal(await setup(c, { from: DAEMON }), 1)
  const text = sys.out.join('\n')
  assert.match(text, /✗ The service couldn’t be installed\.\n {6}launchctl bootstrap gui\/501: Bootstrap failed: 5/)
  assert.match(text, /Login Items & Extensions/)
  assert.match(text, /Setup finished with problems/)
})

test('a Mac’s terminal module comes prebuilt: no build tools asked for, and if it fails, Apple’s Command Line Tools are named', async () => {
  assert.deepEqual(missingBuildTools(ctx(fakeSys(() => ({})))), [])
  const own = process.execPath.split('/').pop()
  const l = launchd()
  const sys = fakeSys((cmd, args) => ((cmd === 'node' || cmd === own) && args[0] === '-e' ? { code: 1, err: 'dlopen failed' } : machine(l)(cmd, args)), ['npm', 'claude'])
  const c = ctx(sys, { apiPort: api, localPort: loc })
  assert.equal(await setup(c, { from: DAEMON }), 1)
  const text = sys.out.join('\n')
  assert.match(text, /✗ The daemon couldn’t be installed\.\n {6}The terminal module \(node-pty\) doesn’t work after building: dlopen failed\n {6}On a Mac the terminal module comes prebuilt; building it needs Apple’s Command Line Tools: xcode-select --install/)
  assert.ok(sys.calls.some((x) => x.cmd.endsWith('npm') && x.args[0] === 'ci'), 'it tried')
  assert.doesNotMatch(text, /apt-get/)
  assert.ok(!existsSync(join(c.appDir, 'daemon.new')))
})

test('status on a Mac: the agent, its pid, when it starts', async () => {
  const sys = fakeSys(machine(launchd({ loaded: true, print: PRINT })))
  const c = ctx(sys, { apiPort: api, localPort: loc })
  mkdirSync(c.unitDir, { recursive: true })
  writeFileSync(c.unitFile, 'x')
  assert.equal(await status(c, {}), 0)
  const text = sys.out.join('\n')
  assert.match(text, new RegExp(`Daemon +running \\(pid 4242\\), ${LAUNCH_LABEL.replace(/\./g, '\\.')} active, starts when you log in`))
  assert.match(text, /Log +~\/Library\/Logs\/Hypernova\/daemon\.log/)
  const json = fakeSys(machine(launchd({ loaded: true, print: PRINT })))
  const cj = ctx(json, { apiPort: api, localPort: loc })
  mkdirSync(cj.unitDir, { recursive: true })
  writeFileSync(cj.unitFile, 'x')
  assert.equal(await status(cj, { json: true }), 0)
  assert.deepEqual(JSON.parse(json.out.join('\n')).service, { installed: true, enabled: true, active: true, pid: 4242 })
})

test('status and pair on a Mac with the daemon stopped say launchctl, not systemctl', async () => {
  const dead = await deadPort()
  const s = fakeSys(machine(launchd()))
  assert.equal(await status(ctx(s, { apiPort: dead, localPort: dead }), {}), 1)
  const text = s.out.join('\n')
  assert.match(text, /Daemon +not running, no service installed/)
  assert.match(text, new RegExp(`Start it: launchctl kickstart -k gui/501/${LAUNCH_LABEL.replace(/\./g, '\\.')} \\(or hypernova run\\)`))
  assert.doesNotMatch(text, /systemctl/)
  const p = fakeSys(machine(launchd()))
  assert.equal(await pair(ctx(p, { apiPort: dead, localPort: dead }), {}), 1)
  assert.match(p.out.join('\n'), /launchctl kickstart -k/)
  assert.doesNotMatch(p.out.join('\n'), /systemctl|menu bar/)
})

test('pair on a Mac makes its code like any server', async () => {
  const sys = fakeSys(machine(launchd()))
  assert.equal(await pair(ctx(sys, { apiPort: api, localPort: loc }), { 'no-wait': true, 'no-qr': true }), 0, sys.out.join('\n'))
  assert.match(sys.out.join('\n'), /https:\/\/192\.168\.1\.20:47323\/pair\?c=CODE/)
})

/** A Mac with the daemon installed, signed in and running. */
function installedMac(l: ReturnType<typeof launchd>, more?: (cmd: string, args: string[]) => Answer, signedIn = true) {
  const sys = fakeSys(machine(l, signedIn, more), ['openssl'])
  const c = ctx(sys, { apiPort: api, localPort: loc }, { CLAUDE_BIN: '/bin/sh' })
  sys.run = ((orig) => (cmd: string, args: string[], o?: RunOptions) => orig(cmd === '/bin/sh' ? 'claude' : cmd, args, o))(sys.run)
  mkdirSync(join(c.daemonDir, 'src'), { recursive: true })
  writeFileSync(join(c.daemonDir, 'package.json'), '{"name":"hypernova-daemon"}')
  writeFileSync(join(c.daemonDir, 'src', 'main.ts'), '')
  mkdirSync(c.unitDir, { recursive: true })
  writeFileSync(c.unitFile, 'x')
  writeFileSync(c.nodePathFile, process.execPath + '\n')
  return { sys, c }
}

test('doctor on a Mac: the system, the agent, that it starts at login, no systemd or lingering', async () => {
  const { sys, c } = installedMac(launchd({ loaded: true, print: PRINT }))
  assert.equal(await doctor(c, {}, { listeners: () => null }), 0, sys.out.join('\n'))
  const text = sys.out.join('\n')
  assert.match(text, /✓ macOS on arm64/)
  assert.match(text, new RegExp(`✓ ${LAUNCH_LABEL.replace(/\./g, '\\.')} running \\(pid 4242\\), starts when you log in`))
  assert.match(text, /· A Mac starts its LaunchAgent at login, not at boot/)
  assert.match(text, /✓ Claude Code signed in \(max\)/)
  assert.doesNotMatch(text, /systemd|systemctl|Lingering|menu bar|apt-get/)
})

test('doctor on a Mac says what’s wrong: not loaded, disabled, nobody at the screen, no Command Line Tools, a failing terminal module', async () => {
  const say = async (l: ReturnType<typeof launchd>, more?: (cmd: string, args: string[]) => Answer, drop?: (c: Cli) => void) => {
    const { sys, c } = installedMac(l, more)
    drop?.(c)
    const code = await doctor(c, { json: true }, { listeners: () => [] })
    return { code, checks: JSON.parse(sys.out.join('\n')) as { name: string; mark: string; text: string; fix?: string }[] }
  }
  const by = (r: Awaited<ReturnType<typeof say>>, name: string) => r.checks.find((x) => x.name === name)!

  const stopped = await say(launchd())
  assert.equal(stopped.code, 1)
  assert.equal(by(stopped, 'service').mark, 'fail')
  assert.match(by(stopped, 'service').text, /isn’t running/)
  assert.match(by(stopped, 'service').fix!, /launchctl kickstart -k/)
  assert.match(by(stopped, 'service').fix!, /daemon\.stderr\.log/)

  const gone = await say(launchd(), undefined, (c) => rmSync(c.unitFile))
  assert.match(by(gone, 'service').text, /isn’t installed/)
  assert.equal(by(gone, 'service').fix, 'Run hypernova setup.')

  const disabled = await say(launchd({ loaded: true, print: PRINT, disabled: `"${LAUNCH_LABEL}" => disabled` }))
  assert.equal(by(disabled, 'service').mark, 'warn')
  assert.match(by(disabled, 'service').fix!, /launchctl enable gui\/501\//)

  const noGui = await say(launchd({ gui: false }))
  assert.equal(by(noGui, 'service').mark, 'warn')
  assert.match(by(noGui, 'service').text, /Nobody is logged in at this Mac’s screen/)

  const pty = await say(launchd({ loaded: true, print: PRINT }), (cmd, args) => (args[0] === '-e' ? { code: 1, err: 'dlopen failed' } : null))
  assert.equal(by(pty, 'pty').mark, 'fail')
  assert.match(by(pty, 'pty').fix!, /xcode-select --install/)
  assert.doesNotMatch(by(pty, 'pty').fix!, /apt-get/)

  // A git of Homebrew's is as good, so this one's only said on a machine without either.
  const noTools = await say(launchd({ loaded: true, print: PRINT }), (cmd) => (cmd === 'xcode-select' ? { code: 2, err: 'unable to get active developer directory' } : null))
  const hasBrewGit = existsSync('/opt/homebrew/bin/git') || existsSync('/usr/local/bin/git')
  if (!hasBrewGit) {
    assert.equal(by(noTools, 'xcode').mark, 'warn')
    assert.equal(by(noTools, 'xcode').fix, 'xcode-select --install')
  }
})

test('logs on a Mac read Library’s folder', async () => {
  const sys = fakeSys(() => ({}))
  const c = ctx(sys)
  assert.equal(await logs(c, {}), 1)
  assert.match(sys.out[0]!, /No log yet at ~\/Library\/Logs\/Hypernova\/daemon\.log/)
  mkdirSync(c.logs, { recursive: true })
  writeFileSync(join(c.logs, 'daemon.log'), 'one\ntwo\n')
  sys.out.length = 0
  assert.equal(await logs(c, { n: '1' }), 0)
  assert.deepEqual(sys.out, ['two'])
})

// ── Node.js ───────────────────────────────────────────────────────────────

test('Node.js for a Mac: nodejs.org’s macOS tarball for this processor, not its .pkg', () => {
  const index = [
    { version: 'v26.1.0', files: ['linux-arm64', 'osx-arm64-tar', 'osx-x64-tar'] },
    { version: 'v24.9.0', files: ['linux-arm64', 'linux-x64', 'osx-arm64-tar', 'osx-x64-pkg', 'osx-x64-tar'] },
    { version: 'v24.8.0', files: ['linux-x64', 'osx-x64-pkg'] },
  ]
  assert.deepEqual(pickRelease(index, 'arm64', 24, 'https://d', 'darwin'), { version: 'v24.9.0', file: 'node-v24.9.0-darwin-arm64.tar.gz', url: 'https://d/v24.9.0/node-v24.9.0-darwin-arm64.tar.gz' })
  assert.equal(pickRelease(index, 'x64', 24, 'https://d', 'darwin')!.file, 'node-v24.9.0-darwin-x64.tar.gz')
  assert.equal(pickRelease([index[2]], 'x64', 24, 'https://d', 'darwin'), null)
  // Linux is as it was.
  assert.equal(pickRelease(index, 'arm64')!.file, 'node-v24.9.0-linux-arm64.tar.gz')
})
