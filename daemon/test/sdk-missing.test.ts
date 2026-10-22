// A daemon with no Claude Code SDK in its packages, as the Mac app installs it until Setup has
// fetched it (src/sdk.ts, docs/PROTOCOL.md "Claude Code's SDK"): it starts and serves; the snapshot
// and /local/status say the SDK isn't there; what runs through it answers 409 `sdk_missing` in
// words (the "/" list, a session the daemon would run itself, Rewind) and nothing is made; once
// the package is there, the status says so without a restart.
//
// The daemon runs from a copy of src/ whose node_modules links every package but the SDK, in a
// scratch home with a stand-in Claude Code (fixtures/fake-claude.sh): no network, no real claude.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawn, type ChildProcess } from 'node:child_process'
import { chmodSync, copyFileSync, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, symlinkSync, utimesSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { fileURLToPath } from 'node:url'

const DAEMON = join(dirname(fileURLToPath(import.meta.url)), '..')
const SDK = '@anthropic-ai/claude-agent-sdk'
const scratch = mkdtempSync(join(realpathSync(tmpdir()), 'hn-sdk-missing-'))
const copy = join(scratch, 'daemon')
const support = join(scratch, 'support')
const home = join(scratch, 'home')
const claudeDir = join(scratch, 'claude')

// The daemon's sources and package files, and every installed package but the SDK (and its
// platform builds), linked: as the Mac app's bundle is before Setup fetches it.
cpSync(join(DAEMON, 'src'), join(copy, 'src'), { recursive: true })
for (const f of ['package.json', 'package-lock.json']) copyFileSync(join(DAEMON, f), join(copy, f))
const lock = JSON.parse(readFileSync(join(DAEMON, 'package-lock.json'), 'utf8'))
const pinned: string = lock.packages[`node_modules/${SDK}`].version
writeFileSync(join(copy, 'sdk-version'), pinned + '\n')
const modules = realpathSync(join(DAEMON, 'node_modules'))
mkdirSync(join(copy, 'node_modules'))
for (const name of readdirSync(modules)) {
  if (name.startsWith('.')) continue
  if (!name.startsWith('@')) {
    symlinkSync(join(modules, name), join(copy, 'node_modules', name))
    continue
  }
  mkdirSync(join(copy, 'node_modules', name))
  for (const pkg of readdirSync(join(modules, name))) {
    if (`${name}/${pkg}`.startsWith(SDK)) continue
    symlinkSync(join(modules, name, pkg), join(copy, 'node_modules', name, pkg))
  }
}
assert.ok(!existsSync(join(copy, 'node_modules', ...SDK.split('/'))))

// Claude Code is here (a stand-in, signed out), its hooks aren't: a new session would run through the SDK.
mkdirSync(join(home, 'Programming', 'repo'), { recursive: true })
const project = realpathSync(join(home, 'Programming', 'repo'))
const fake = join(scratch, 'claude-bin')
copyFileSync(join(DAEMON, 'test', 'fixtures', 'fake-claude.sh'), fake)
chmodSync(fake, 0o755)
mkdirSync(join(scratch, 'fake-state'))

// A conversation Claude Code wrote earlier, for Rewind (the daemon finds it at start).
const sid = randomUUID()
const [p1, a1, p2] = [randomUUID(), randomUUID(), randomUUID()]
const entry = (uuid: string, parentUuid: string | null, o: object) => ({ uuid, parentUuid, timestamp: new Date().toISOString(), sessionId: sid, cwd: project, isSidechain: false, userType: 'external', ...o })
mkdirSync(join(claudeDir, 'projects', 'repo'), { recursive: true })
const transcript = join(claudeDir, 'projects', 'repo', `${sid}.jsonl`)
writeFileSync(
  transcript,
  [
    entry(p1, null, { type: 'user', message: { role: 'user', content: 'First task' } }),
    entry(a1, p1, { type: 'assistant', message: { id: 'msg_a', type: 'message', role: 'assistant', model: 'claude-opus-5-5', content: [{ type: 'text', text: 'Done.' }], usage: { input_tokens: 1, output_tokens: 1 } } }),
    entry(p2, a1, { type: 'user', message: { role: 'user', content: 'Second task' } }),
  ].map((x) => JSON.stringify(x)).join('\n') + '\n',
)
utimesSync(transcript, new Date(), new Date())

const freePort = () =>
  new Promise<number>((resolve) => {
    const s = createServer()
    s.listen(0, '127.0.0.1', () => {
      const port = (s.address() as { port: number }).port
      s.close(() => resolve(port))
    })
  })
const API = await freePort()
const LOCAL = await freePort()
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

let daemon: ChildProcess
let output = ''
let token = ''
before(async () => {
  daemon = spawn(process.execPath, [join(copy, 'src', 'main.ts')], {
    cwd: copy,
    env: {
      PATH: '/usr/bin:/bin',
      HOME: home,
      ZDOTDIR: home,
      TMPDIR: process.env.TMPDIR ?? '/tmp',
      LANG: 'en_US.UTF-8',
      CLAUDE_CONFIG_DIR: claudeDir,
      CLAUDE_BIN: fake,
      FAKE_CLAUDE_STATE: join(scratch, 'fake-state'),
      HYPERNOVA_SUPPORT: support,
      HYPERNOVA_LOGS: join(scratch, 'logs'),
      HYPERNOVA_API_PORT: String(API),
      HYPERNOVA_LOCAL_PORT: String(LOCAL),
      HYPERNOVA_LAN_PORT: '0',
      HYPERNOVA_NO_DEV_APK: '1',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  daemon.stdout!.on('data', (d) => (output += d))
  daemon.stderr!.on('data', (d) => (output += d))
  for (let i = 0; i < 300; i++) {
    try {
      if ((await fetch(`http://127.0.0.1:${API}/v1/health`)).status === 200 && existsSync(join(support, 'admin-token'))) break
    } catch {}
    if (daemon.exitCode !== null) break
    await sleep(50)
  }
  token = readFileSync(join(support, 'admin-token'), 'utf8').trim()
})
after(async () => {
  if (daemon && daemon.exitCode === null) {
    daemon.kill('SIGTERM')
    for (let i = 0; i < 100 && daemon.exitCode === null; i++) await sleep(50)
    if (daemon.exitCode === null) daemon.kill('SIGKILL')
  }
  rmSync(scratch, { recursive: true, force: true })
})

async function call(method: string, path: string, body?: unknown): Promise<{ status: number; body: any }> {
  const r = await fetch(`http://127.0.0.1:${LOCAL}${path}`, { method, headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
  return { status: r.status, body: await r.json() }
}
const missing = (r: { status: number; body: any }) => {
  assert.equal(r.status, 409, JSON.stringify(r.body))
  assert.equal(r.body.error.code, 'sdk_missing')
  assert.match(r.body.error.message, /Claude Code’s SDK isn’t installed/)
}

test('starts and serves without the SDK, and says it isn’t there', async () => {
  assert.equal(daemon.exitCode, null, output)
  assert.equal((await fetch(`http://127.0.0.1:${API}/v1/health`)).status, 200)
  const st = await call('GET', '/local/status')
  assert.equal(st.status, 200)
  assert.deepEqual(st.body.claude.sdk, { installed: false, version: null, wanted: pinned })
  assert.equal(st.body.claude.path, fake)
  const snap = await call('GET', '/v1/snapshot')
  assert.equal(snap.body.claude.available, true)
  assert.equal(snap.body.claude.sdk, false)
})

test('what runs through it answers sdk_missing, and nothing is made', async () => {
  // The "/" list.
  missing(await call('GET', `/v1/commands?project=${encodeURIComponent(project)}`))
  // A session the daemon would run itself (no hooks here, so no shared terminal).
  missing(await call('POST', '/v1/sessions', { projectPath: project, prompt: '[Hypernova test] hello' }))
  assert.ok(!(await call('GET', '/v1/snapshot')).body.sessions.some((s: { cwd: string; id: string }) => s.cwd === project && s.id !== sid))
  // Rewind, on the conversation found at start.
  missing(await call('POST', `/v1/sessions/${sid}/rewind`, { eventId: p2 }))
  // Still up, and nothing in its log fell over.
  assert.equal((await fetch(`http://127.0.0.1:${API}/v1/health`)).status, 200)
  const log = readFileSync(join(scratch, 'logs', 'daemon.log'), 'utf8')
  assert.doesNotMatch(log, /uncaught|unhandled rejection/)
})

test('once the SDK is there (Setup fetched it), the status says so without a restart', async () => {
  symlinkSync(join(modules, ...SDK.split('/')), join(copy, 'node_modules', ...SDK.split('/')))
  let sdk: any = null
  // The status reads the folder at most every 5 s.
  for (let i = 0; i < 80 && !sdk?.installed; i++) {
    sdk = (await call('GET', '/local/status')).body.claude.sdk
    if (!sdk.installed) await sleep(100)
  }
  assert.deepEqual(sdk, { installed: true, version: pinned, wanted: pinned })
  assert.equal((await call('GET', '/v1/snapshot')).body.claude.sdk, true)
})
