// The server's debug log (daemon/src/debug-log.ts, debug-scrub.ts; docs/SECURITY-MODEL.md, "The server's debug
// log"), on free ports with scratch state: off writes nothing, on writes what it should, 24 hours ends it, and
// every kind of secret and of conversation, planted in every way, is looked for in the file itself and in
// `hypernova debug bundle`'s.
import { after, before, mock, test } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { createServer as createHttpServer, request, type Server } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import WebSocket from 'ws'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-debug-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
process.env.HYPERNOVA_NO_DEV_APK = '1'
mkdirSync(process.env.HOME, { recursive: true })
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
process.env.HYPERNOVA_API_PORT = String(API)
process.env.HYPERNOVA_LOCAL_PORT = String(LOCAL)
process.env.HYPERNOVA_LAN_PORT = '0'

const { dbg, dbgError, dbgProc, debugFile, debugOn, debugView, DebugWriter, flushDebug, setDebug } = await import('../src/debug-log.ts')
const scrub = await import('../src/debug-scrub.ts')
const { startServers } = await import('../src/http.ts')
const { addDevice, adminToken, hookSecret, settings } = await import('../src/store.ts')
const { cliContext } = await import('../src/cli/context.ts')
const { debug, bundle } = await import('../src/cli/debug.ts')
const { logs } = await import('../src/cli/commands.ts')

const DEVICE_TOKEN = 'dGVzdC1kZXZpY2UtdG9rZW4tMDEyMzQ1Njc4OWFiY2RlZg'
addDevice({ id: 'devicedev1', name: 'Ada’s Pixel', model: 'Pixel 9', createdAt: Date.now(), lastSeenAt: Date.now() }, DEVICE_TOKEN)

/** Every kind of secret, each with what must never be in a file. */
const PEM_CERT = '-----BEGIN CERTIFICATE-----\nMIIBszCCAVmgAwIBAgIUSECRETCERTSECRETCERT\n-----END CERTIFICATE-----'
const PEM_KEY = '-----BEGIN PRIVATE KEY-----\nMIGHAgEAMBMGByqGSM49SECRETKEYSECRETKEY\n-----END PRIVATE KEY-----'
const PAIR_CODE = 'Zm9vYmFyQ29kZTEyMzQ1Ng'
const TICKET = 'dGlja2V0LXNlY3JldC12YWx1ZQ'
const PROVIDER_KEY = 'sk-or-v1-0123456789abcdef0123456789abcdef'
const FIRE = 'hnf_0123456789abcdef0123456789abcdef'
const BEARER = 'YmVhcmVyLXNlY3JldC12YWx1ZQ'
const ENV_VALUE = 'hunter2isaplainpassword'
const SHORT_PASSWORD = 'hunter'
const WEBHOOK = 'https://hooks.example.com/services/T000SECRET/B000SECRET/XXXXSECRET'
const SECRETS = [adminToken, hookSecret, DEVICE_TOKEN, 'MIIBszCCAVmgAwIBAgIUSECRETCERTSECRETCERT', 'MIGHAgEAMBMGByqGSM49SECRETKEYSECRETKEY', PAIR_CODE, TICKET, PROVIDER_KEY, FIRE, BEARER, ENV_VALUE, `=${SHORT_PASSWORD}`, 'T000SECRET', 'XXXXSECRET']
/** What's said in a session and where it runs. */
const WORDS = ['please deploy the plan', 'rm -rf', 'the reply from Claude', 'contents of a file', 'Payroll project', 'secret-project', '/home/ada', 'quarterly-report.pdf']

function clean(text: string): void {
  for (const s of SECRETS) assert.ok(!text.includes(s), `a secret in the file: ${s}\n${text}`)
  for (const w of WORDS) assert.ok(!text.includes(w), `words from a session in the file: ${w}\n${text}`)
}
const fileText = async () => {
  await flushDebug()
  return existsSync(debugFile()) ? readFileSync(debugFile(), 'utf8') : ''
}

let close: () => Promise<void>
before(async () => {
  close = startServers()
  for (let i = 0; i < 50; i++) {
    try {
      if ((await send(API, 'GET', '/v1/health')).status === 200) return
    } catch {}
    await new Promise((r) => setTimeout(r, 100))
  }
})
after(async () => {
  setDebug(false)
  await close()
  rmSync(scratch, { recursive: true, force: true })
})

function send(port: number, method: string, path: string, body?: unknown, headers: Record<string, string> = {}): Promise<{ status: number; body: any }> {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? undefined : JSON.stringify(body)
    const req = request({ host: '127.0.0.1', port, method, path, headers: { ...(data ? { 'content-type': 'application/json' } : {}), ...headers } }, (res) => {
      let t = ''
      res.on('data', (d) => (t += d))
      res.on('end', () => {
        let j: any = t
        try {
          j = JSON.parse(t)
        } catch {}
        resolve({ status: res.statusCode ?? 0, body: j })
      })
    })
    req.on('error', reject)
    req.end(data)
  })
}

// ── Off ──────────────────────────────────────────────────────────────────

test('off by default: no file, and a line builds nothing', async () => {
  assert.equal(debugOn(), false)
  assert.deepEqual(debugView(), { on: false, until: null })
  let read = 0
  const fields = {
    get boom() {
      read++
      return 'x'
    },
  }
  dbg('http', 'request', fields)
  dbgError('error', 'uncaught', new Error('boom'), fields)
  assert.equal(dbgProc('claude', ['--help'], fields)(0), undefined)
  await send(API, 'GET', '/v1/health')
  await flushDebug()
  assert.equal(read, 0, 'a field was read while off')
  assert.equal(existsSync(debugFile()), false, 'off writes no file')
})

// ── On: the shape ────────────────────────────────────────────────────────

test('on: lines with a time and a tag, mode 0600 in a 0700 folder, and off writes nothing more', async () => {
  const seen = setDebug(true)
  assert.equal(seen.on, true)
  assert.ok(Math.abs(seen.until! - (Date.now() + 24 * 3600_000)) < 5000)
  dbg('session', 'state', { session: '1a2b3c4d-1111-4222-8333-444455556666', from: 'idle', to: 'working', n: 2, big: true })
  const text = await fileText()
  assert.match(text, /^\d{4}-\d\d-\d\dT[\d:.]+Z debug on until=/m)
  assert.match(text, /Z session state session=1a2b3c4d from=idle to=working n=2 big=true$/m)
  assert.equal(statSync(debugFile()).mode & 0o777, 0o600)
  assert.equal(statSync(process.env.HYPERNOVA_LOGS!).mode & 0o777, 0o700)
  const daemon = readFileSync(join(process.env.HYPERNOVA_LOGS!, 'daemon.log'), 'utf8')
  assert.match(daemon, /debug log on until .* \(cli\)/)
  assert.ok(!daemon.includes('session state'), 'the everyday log gets no debug lines')

  setDebug(false)
  await flushDebug()
  await new Promise((r) => setTimeout(r, 50))
  const size = statSync(debugFile()).size
  dbg('http', 'request', { method: 'GET' })
  await send(API, 'GET', '/v1/health')
  await flushDebug()
  assert.equal(statSync(debugFile()).size, size, 'off writes nothing more')
})

// ── On: the rules ────────────────────────────────────────────────────────

test('secrets and conversations, planted through every door, stay out of the file', async () => {
  rmSync(debugFile(), { force: true })
  setDebug(true)
  const everywhere = [
    adminToken, hookSecret, DEVICE_TOKEN, PEM_CERT, PEM_KEY, PAIR_CODE, TICKET, PROVIDER_KEY, FIRE, `Bearer ${BEARER}`, `Authorization: Bearer ${BEARER}`,
    `${WEBHOOK}`, `https://192.168.1.20:47323/pair?c=${PAIR_CODE}&n=box`, `hypernova://pair?v=1&u=http%3A%2F%2Fx&c=${PAIR_CODE}&n=box`, `/pair?c=${PAIR_CODE}`, `/v1/terms/abc/ws?ticket=${TICKET}`,
    `ANTHROPIC_API_KEY=${PROVIDER_KEY}`, `MY_TOKEN=${ENV_VALUE}`, `PASSWORD=${SHORT_PASSWORD}`, `x-api-key: ${ENV_VALUE}`, `cookie: hn_preview=${ENV_VALUE}`, `--token ${ENV_VALUE}`, `--password=${ENV_VALUE}`,
    ...WORDS, 'rm -rf /home/ada/secret-project', 'cat /home/ada/secret-project/quarterly-report.pdf',
  ]
  for (const key of ['token', 'deviceToken', 'adminToken', 'authorization', 'auth', 'cookie', 'cert', 'key', 'privateKey', 'fingerprint', 'code', 'pairingCode', 'link', 'ticket', 'url', 'apiKey', 'env', 'headers', 'prompt', 'reply', 'text', 'body', 'message', 'args', 'argv', 'input', 'output', 'command', 'transcript', 'title', 'name', 'sessionName', 'cwd', 'path', 'project', 'folder', 'file', 'stderr', 'whatever', 'x', 'why', 'device', 'session', 'route', 'flags', 'says']) {
    for (const v of everywhere) dbg('http', 'request', { [key]: v })
  }
  // A field that holds the daemon's own word takes one word that isn't a key: nothing longer gets through.
  for (const key of ['reason', 'tool', 'state', 'bin', 'ip']) {
    for (const v of everywhere.filter((x) => x !== 'secret-project' && x !== 'quarterly-report.pdf')) dbg('http', 'request', { [key]: v })
  }
  // An event's or a field's name is checked as well.
  for (const v of everywhere) dbg('http', v)
  for (const v of everywhere) dbg('http', 'event', { [v]: 'x' })
  // Things that aren't strings: MCP's env, headers, a whole body, a Buffer, a list.
  for (const v of [{ env: { TOKEN: ENV_VALUE } }, { headers: { authorization: `Bearer ${BEARER}` } }, [PROVIDER_KEY, 'please deploy the plan'], Buffer.from(ENV_VALUE)]) dbg('mcp', 'route', { env: v, thing: v })
  // A program: its name and flags, never a value.
  const ended = dbgProc('/home/ada/secret-project/bin/claude', ['--resume', 'abc', '--api-key', ENV_VALUE, `--token=${ENV_VALUE}`, '--model=opus', '-p', 'please deploy the plan', '--', 'please deploy the plan'], { session: 'sid12345678' })
  ended(0)
  // Errors: whatever the message quotes, and a stack that goes through a project.
  const quoting = [new Error(`Failed: please deploy the plan ${PROVIDER_KEY}`), new Error(`open '/home/ada/secret-project/quarterly-report.pdf'`), Object.assign(new Error(`ENOENT: no such file or directory, open '/home/ada/secret-project/x'`), { code: 'ENOENT', syscall: 'open' })]
  try {
    JSON.parse('please deploy the plan')
  } catch (e) {
    quoting.push(e as Error)
  }
  const inProject = new TypeError('Cannot read properties of undefined (reading \'foo\')')
  inProject.stack = `TypeError: Cannot read properties of undefined (reading 'foo')\n    at thing (/home/ada/secret-project/run.js:1:2)\n    at Object.other (file:///x/daemon/src/hub.ts:10:3)\n    at async node:internal/x:1:1`
  for (const e of [...quoting, inProject]) dbgError('error', 'uncaught', e)
  dbgError('error', 'uncaught', `a string thrown: please deploy the plan`)

  const text = await fileText()
  clean(text)
  assert.match(text, /proc start bin=claude .*session=sid12345 flags=--resume,--api-key,--token,--model,-p/)
  assert.match(text, /proc exit bin=claude .*exit=0 ms=/)
  assert.match(text, /error uncaught err=TypeError says=Cannot_read_properties_of_undefined_\(reading_'foo'\)/)
  assert.match(text, /^ {4}at thing \[path\]:1:2$/m)
  assert.match(text, /^ {4}at Object\.other src\/hub\.ts:10:3$/m)
  assert.match(text, /error uncaught err=Error errno=ENOENT syscall=open says=\[\d+_chars\]/)
  assert.match(text, /prompt=\[\d+ chars\]/)
  setDebug(false)
})

test('routes keep their own words and sessions\' short ids, and nothing else', () => {
  assert.equal(scrub.routeOf('/v1/sessions/1a2b3c4d-1111-4222-8333-444455556666/prompt?x=1'), '/v1/sessions/1a2b3c4d/prompt')
  assert.equal(scrub.routeOf(`/hooks/${hookSecret}`), '/hooks/:x')
  assert.equal(scrub.routeOf('/p/abcdef0123456789/some/deep/secret-file.html'), '/p/:x')
  assert.equal(scrub.routeOf(`/v1/terms/t-1/ws?ticket=${TICKET}`), '/v1/terms/:x/ws')
  assert.equal(scrub.routeOf('/local/automations/abc/fire'), '/local/automations/:x/fire')
  assert.equal(scrub.routeOf(`/pair?c=${PAIR_CODE}`), '/pair')
  assert.equal(scrub.routeOf('/'), '/')
  assert.equal(scrub.routeOf('/v1/agents/mcp/my-private-server'), '/v1/agents/mcp/:x')
})

test('a command line: flags and known sub-commands only', () => {
  assert.equal(scrub.flagsOf(['--bg', '--resume', 'abc', '--model=opus', '--', '--not-a-flag', 'a prompt']), '--bg,--resume,--model')
  assert.equal(scrub.subOf(['plugin', 'install', '--json', 'my-secret-plugin']), 'plugin,install')
  assert.equal(scrub.subOf(['attach', 'f00d0001']), 'attach')
  assert.equal(scrub.subOf(['a prompt']), '')
  assert.equal(scrub.binOf('/home/ada/.local/bin/claude'), 'claude')
})

// ── The daemon, end to end ───────────────────────────────────────────────

test('requests, sockets and hooks: their lines, and no body, header or query in them', async () => {
  rmSync(debugFile(), { force: true })
  const on = await send(LOCAL, 'POST', '/local/settings', { debugLog: true, debugHours: 2 }, { authorization: `Bearer ${adminToken}` })
  assert.equal(on.status, 200)
  assert.ok(Math.abs(on.body.debugUntil - (Date.now() + 2 * 3600_000)) < 5000)
  assert.equal((await send(LOCAL, 'POST', '/local/settings', { debugLog: true, debugHours: 25 }, { authorization: `Bearer ${adminToken}` })).status, 400)
  assert.equal((await send(LOCAL, 'POST', '/local/settings', { debugLog: true, debugHours: 0 }, { authorization: `Bearer ${adminToken}` })).status, 400)
  // A phone's own request; a wrong token; a hook, its secret in the path, a prompt in its body.
  await send(API, 'GET', `/v1/snapshot?secret=${ENV_VALUE}`, undefined, { authorization: `Bearer ${DEVICE_TOKEN}` })
  await send(API, 'GET', '/v1/snapshot', undefined, { authorization: `Bearer ${BEARER}` })
  await send(LOCAL, 'POST', `/hooks/${hookSecret}`, { hook_event_name: 'UserPromptSubmit', session_id: '1a2b3c4d-1111-4222-8333-444455556666', cwd: '/home/ada/secret-project', prompt: 'please deploy the plan', tool_name: 'Bash', tool_input: { command: 'rm -rf /home/ada/secret-project' } })
  await send(API, 'POST', `/v1/pair`, { code: PAIR_CODE, deviceName: 'Ada’s Pixel' })
  await send(API, 'GET', `/p/${ENV_VALUE}/secret-file.html`)
  // The live feed, as a phone: what it hears and what it says.
  const ws = new WebSocket(`ws://127.0.0.1:${API}/v1/ws`, { headers: { authorization: `Bearer ${DEVICE_TOKEN}` } })
  await new Promise<void>((resolve, reject) => {
    ws.once('message', () => resolve())
    ws.once('error', reject)
  })
  ws.send(JSON.stringify({ t: 'speech_state', text: 'please deploy the plan' }))
  await new Promise((r) => setTimeout(r, 100))
  ws.close(1000, 'bye now')
  await new Promise((r) => ws.once('close', r))
  await new Promise((r) => setTimeout(r, 100))
  const bad = new WebSocket(`ws://127.0.0.1:${API}/v1/ws?token=${BEARER}`)
  await new Promise((r) => bad.once('error', r))
  const text = await fileText()
  clean(text)
  assert.match(text, /http request via=api method=GET route=\/v1\/snapshot status=200 ms=\d+ device=devicede in=\d+ out=\d+/)
  assert.match(text, /http request via=api method=GET route=\/v1\/snapshot status=401/)
  assert.match(text, /http request via=local method=POST route=\/hooks\/:x status=200/)
  assert.match(text, /hook received hook=UserPromptSubmit session=1a2b3c4d tool=Bash/)
  assert.match(text, /session new session=1a2b3c4d/)
  assert.match(text, /http request via=api method=POST route=\/v1\/pair status=403/)
  assert.match(text, /http request via=api method=GET route=\/p\/:x status=404/)
  assert.match(text, /ws open kind=feed device=devicede via=api/)
  assert.match(text, /ws out device=devicede type=snapshot size=\d+/)
  assert.match(text, /ws in device=devicede type=speech_state size=\d+/)
  assert.match(text, /ws close kind=feed device=devicede code=1000 reason=bye_now ms=\d+/)
  assert.match(text, /ws refused route=\/v1\/ws reason=not-paired via=api/)
  setDebug(false)
})

test('a phone turns it on and off on its own route, and every client hears', async () => {
  const auth = { authorization: `Bearer ${DEVICE_TOKEN}` }
  assert.equal((await send(API, 'POST', '/v1/mac/debug', {}, auth)).status, 400)
  assert.equal((await send(API, 'POST', '/v1/mac/debug', { on: true })).status, 401)
  const ws = new WebSocket(`ws://127.0.0.1:${API}/v1/ws`, { headers: auth })
  const heard: any[] = []
  ws.on('message', (d) => heard.push(JSON.parse(String(d))))
  await new Promise((r) => ws.once('open', r))
  await new Promise((r) => setTimeout(r, 100))
  assert.equal(heard[0].t, 'snapshot')
  assert.deepEqual(heard[0].snapshot.mac.debug, { on: false, until: null })
  const on = await send(API, 'POST', '/v1/mac/debug', { on: true }, auth)
  assert.equal(on.status, 200)
  assert.equal(on.body.debug.on, true)
  assert.ok(Math.abs(on.body.debug.until - (Date.now() + 24 * 3600_000)) < 5000)
  assert.deepEqual((await send(API, 'GET', '/v1/mac/debug', undefined, auth)).body, on.body)
  await new Promise((r) => setTimeout(r, 100))
  assert.deepEqual(heard.find((m) => m.t === 'debug')?.debug, on.body.debug)
  assert.match(readFileSync(join(process.env.HYPERNOVA_LOGS!, 'daemon.log'), 'utf8'), /debug log on until .* \(devicedev1\)/)
  const off = await send(API, 'POST', '/v1/mac/debug', { on: false }, auth)
  assert.deepEqual(off.body.debug, { on: false, until: null })
  ws.close()
})

// ── 24 hours ─────────────────────────────────────────────────────────────

test('it ends by itself after 24 hours, on a fake clock, with nothing being logged', async () => {
  const start = Date.now()
  mock.timers.enable({ apis: ['Date', 'setInterval'], now: start })
  try {
    setDebug(true)
    assert.equal(debugOn(), true)
    assert.equal(settings.debugUntil, start + 24 * 3600_000)
    mock.timers.tick(24 * 3600_000 - 1000)
    assert.equal(debugView().on, true)
    assert.equal(settings.debugUntil, start + 24 * 3600_000)
    // The once-a-minute look, not a line, finds it.
    mock.timers.tick(61_000)
    assert.equal(settings.debugUntil, undefined)
    assert.deepEqual(debugView(), { on: false, until: null })
    const saved = JSON.parse(readFileSync(join(process.env.HYPERNOVA_SUPPORT!, 'settings.json'), 'utf8'))
    assert.equal(saved.debugUntil, undefined)
    // --hours asks for more: still 24.
    setDebug(true, 72)
    assert.ok(debugView().until! - Date.now() <= 24 * 3600_000)
    setDebug(false)
  } finally {
    mock.timers.reset()
  }
  const text = await fileText()
  assert.match(text, /debug off reason=expired/)
  assert.match(readFileSync(join(process.env.HYPERNOVA_LOGS!, 'daemon.log'), 'utf8'), /debug log off: its time ran out/)
})

test('a line itself notices the time is up (a clock moved, a machine asleep)', async () => {
  const start = Date.now()
  mock.timers.enable({ apis: ['Date'], now: start })
  try {
    setDebug(true, 1)
    mock.timers.setTime(start + 3600_000 + 1)
    dbg('http', 'request', { late: true })
    assert.equal(debugOn(), false)
  } finally {
    mock.timers.reset()
  }
  assert.ok(!(await fileText()).includes('late=true'))
})

/** What a new start of the daemon makes of a saved setting. */
function restart(until: number | undefined): { view: { on: boolean; until: number | null }; saved: unknown } {
  const support = join(scratch, `restart-${Math.random()}`)
  mkdirSync(support, { recursive: true })
  writeFileSync(join(support, 'settings.json'), JSON.stringify({ projectsRoot: '/x', ...(until === undefined ? {} : { debugUntil: until }) }))
  const out = execFileSync(process.execPath, ['--disable-warning=ExperimentalWarning', '--input-type=module', '-e', `
    const { debugView } = await import(${JSON.stringify(join(import.meta.dirname, '..', 'src', 'debug-log.ts'))})
    const { readFileSync } = await import('node:fs')
    console.log(JSON.stringify({ view: debugView(), saved: JSON.parse(readFileSync(process.env.HYPERNOVA_SUPPORT + '/settings.json', 'utf8')).debugUntil ?? null }))
    process.exit(0)
  `], { env: { ...process.env, HYPERNOVA_SUPPORT: support, HYPERNOVA_LOGS: join(support, 'logs') }, encoding: 'utf8' })
  return JSON.parse(out)
}

test('a restart keeps it within its time, and never past 24 hours from now', () => {
  const soon = Date.now() + 3600_000
  assert.deepEqual(restart(soon), { view: { on: true, until: soon }, saved: soon })
  // Run out while the daemon was down: off, and the setting forgotten.
  assert.deepEqual(restart(Date.now() - 1000), { view: { on: false, until: null }, saved: null })
  // Written by hand for next year: 24 hours.
  const far = restart(Date.now() + 365 * 24 * 3600_000)
  assert.equal(far.view.on, true)
  assert.ok(far.view.until! <= Date.now() + 24 * 3600_000)
  assert.equal(far.saved, far.view.until)
  assert.deepEqual(restart(undefined), { view: { on: false, until: null }, saved: null })
})

// ── The file's size ──────────────────────────────────────────────────────

test('it rolls: about 20 MB in all, the oldest going first, 0600', async () => {
  const dir = join(scratch, 'roll')
  const w = new DebugWriter(join(dir, 'debug.log'), 2000, 4)
  for (let i = 0; i < 2000; i++) {
    w.write(`line ${String(i).padStart(5, '0')} ${'x'.repeat(40)}`)
    if (i % 10 === 0) await w.flush()
  }
  await w.flush()
  const files = readdirSync(dir).sort()
  assert.deepEqual(files, ['debug.log', 'debug.log.1', 'debug.log.2', 'debug.log.3'])
  let total = 0
  for (const f of files) {
    const s = statSync(join(dir, f))
    assert.equal(s.mode & 0o777, 0o600, f)
    assert.ok(s.size <= 2000, `${f} is ${s.size} bytes`)
    total += s.size
  }
  assert.ok(total <= 4 * 2000)
  const newest = readFileSync(join(dir, 'debug.log'), 'utf8')
  assert.match(newest, /line 01999 /)
  assert.ok(!files.some((f) => readFileSync(join(dir, f), 'utf8').includes('line 00000 ')), 'the oldest lines are gone')
  assert.equal(statSync(dir).mode & 0o777, 0o700)
})

test('a disk that can\'t keep up drops lines and says so, and never waits', async () => {
  const dir = join(scratch, 'drop')
  const w = new DebugWriter(join(dir, 'debug.log'))
  const line = 'y'.repeat(1000)
  const t0 = performance.now()
  for (let i = 0; i < 4000; i++) w.write(line)
  assert.ok(performance.now() - t0 < 500, 'writing is only queueing')
  await w.flush()
  const text = readFileSync(join(dir, 'debug.log'), 'utf8')
  assert.match(text, /debug dropped lines=\d+/)
})

test('what\'s queued goes out on the way out', () => {
  const dir = join(scratch, 'exit')
  mkdirSync(dir, { recursive: true })
  const w = new DebugWriter(join(dir, 'debug.log'))
  w.write('the last word')
  w.flushSync()
  assert.match(readFileSync(join(dir, 'debug.log'), 'utf8'), /the last word/)
})

// ── The code ─────────────────────────────────────────────────────────────

test('no line is made from a string built at the call: every event is a fixed word', () => {
  const src = join(import.meta.dirname, '..', 'src')
  const walk = (d: string): string[] => readdirSync(d, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? walk(join(d, e.name)) : e.name.endsWith('.ts') ? [join(d, e.name)] : []))
  let calls = 0
  for (const f of walk(src)) {
    if (f.endsWith('debug-log.ts')) continue
    for (const m of readFileSync(f, 'utf8').matchAll(/\bdbg(?:Error)?\(\s*(.{0,60})/g)) {
      calls++
      const event = /^(?:area|'[a-z]+'), '([a-z-]+)'/.exec(m[1]!)?.[1]
      assert.ok(event && scrub.EVENTS.has(event), `${f}: dbg(${m[1]}`)
    }
  }
  assert.ok(calls > 40, `only ${calls} calls found`)
})

// ── The command ──────────────────────────────────────────────────────────

type Say = { out: string[] }
function ctx(o: { platform?: 'linux' | 'darwin'; localPort?: number; apiPort?: number } = {}): ReturnType<typeof cliContext> & { sys: Say } {
  const home = join(scratch, `cli-${Math.random().toString(36).slice(2)}`)
  mkdirSync(home, { recursive: true })
  const out: string[] = []
  const sys = { out, async run() { return { code: 0, out: '', err: '', missing: false } }, which: () => null, say: (l = '') => void out.push(l) }
  const support = join(home, 'state')
  const logsDir = join(home, 'logs')
  mkdirSync(support, { recursive: true })
  mkdirSync(logsDir, { recursive: true })
  // Ports nothing answers on, unless a test says.
  return cliContext({ platform: o.platform ?? 'linux', home, env: { HOME: home, PATH: '/usr/bin:/bin' }, support, logs: logsDir, user: 'ada', uid: 1000, tty: false, wsl: null, sys, localPort: o.localPort ?? 1, apiPort: o.apiPort ?? 1 } as never) as never
}

async function standIn(): Promise<{ port: number; bodies: any[]; view: { on: boolean; until: number | null }; server: Server }> {
  const st = { port: 0, bodies: [] as any[], view: { on: false, until: null as number | null }, server: null as unknown as Server }
  st.server = createHttpServer((req, res) => {
    let b = ''
    req.on('data', (d) => (b += d))
    req.on('end', () => {
      res.setHeader('content-type', 'application/json')
      if (req.url === '/local/settings') {
        const j = JSON.parse(b)
        st.bodies.push(j)
        st.view = j.debugLog ? { on: true, until: Date.now() + (j.debugHours ?? 24) * 3600_000 } : { on: false, until: null }
        return res.end('{}')
      }
      res.end(JSON.stringify({ debug: st.view }))
    })
  })
  await new Promise<void>((r) => st.server.listen(0, '127.0.0.1', () => r()))
  st.port = (st.server.address() as { port: number }).port
  return st
}

test('hypernova debug on, --hours, status and off, through the daemon', async () => {
  const d = await standIn()
  const c = ctx({ localPort: d.port })
  assert.equal(await debug(c, {}, ['on']), 0)
  assert.deepEqual(d.bodies.at(-1), { debugLog: true, debugHours: 24 })
  assert.match(c.sys.out.join('\n'), /Debug logging is on until .*\(24h 0m left\), then it turns itself off/)
  assert.match(c.sys.out.join('\n'), /debug\.log/)
  assert.equal(await debug(c, { hours: '2' }, ['on']), 0)
  assert.deepEqual(d.bodies.at(-1), { debugLog: true, debugHours: 2 })
  c.sys.out.length = 0
  assert.equal(await debug(c, { json: true }, ['status']), 0)
  const j = JSON.parse(c.sys.out.join('\n'))
  assert.equal(j.on, true)
  assert.equal(j.running, true)
  assert.equal(j.log, join(c.logs, 'debug.log'))
  assert.equal(await debug(c, {}, ['off']), 0)
  assert.deepEqual(d.bodies.at(-1), { debugLog: false })
  for (const hours of ['0', '25', 'lots']) {
    const was = d.bodies.length
    assert.equal(await debug(c, { hours }, ['on']), 2)
    assert.equal(d.bodies.length, was, 'nothing asked of the daemon')
  }
  assert.equal(await debug(c, {}, ['sideways']), 2)
  d.server.close()
})

test('with the daemon down, it\'s written for its next start', async () => {
  const c = ctx()
  writeFileSync(join(c.support, 'settings.json'), JSON.stringify({ projectsRoot: '/keep' }))
  assert.equal(await debug(c, { hours: '3' }, ['on']), 0)
  let saved = JSON.parse(readFileSync(join(c.support, 'settings.json'), 'utf8'))
  assert.equal(saved.projectsRoot, '/keep')
  assert.ok(Math.abs(saved.debugUntil - (Date.now() + 3 * 3600_000)) < 5000)
  assert.match(c.sys.out.join('\n'), /isn’t running: it starts logging when it starts/)
  c.sys.out.length = 0
  assert.equal(await debug(c, {}, ['status']), 0)
  assert.match(c.sys.out.join('\n'), /Debug logging is on/)
  assert.equal(await debug(c, {}, ['off']), 0)
  saved = JSON.parse(readFileSync(join(c.support, 'settings.json'), 'utf8'))
  assert.equal(saved.debugUntil, undefined)
  assert.equal(saved.projectsRoot, '/keep')
})

test('hypernova logs --debug reads the debug log, not the daemon\'s', async () => {
  const c = ctx()
  assert.equal(await logs(c, { debug: true }), 1)
  assert.match(c.sys.out.join('\n'), /hypernova debug on starts one/)
  writeFileSync(join(c.logs, 'daemon.log'), 'everyday\n')
  writeFileSync(join(c.logs, 'debug.log'), 'one\ntwo\nthree\n')
  c.sys.out.length = 0
  assert.equal(await logs(c, { debug: true, n: '2' }), 0)
  assert.deepEqual(c.sys.out, ['two', 'three'])
})

/** Each kind planted, as the logs might hold it: a line of debug.log that's not in the writer's shape, and everyday lines. */
function plant(c: { logs: string; support: string }): void {
  const stamp = new Date().toISOString()
  writeFileSync(join(c.support, 'admin-token'), `${adminToken}\n`)
  writeFileSync(join(c.support, 'hook-secret'), `${hookSecret}\n`)
  writeFileSync(join(c.support, 'devices.json'), JSON.stringify([{ id: 'devicedev1', name: 'Ada’s Pixel', model: 'Pixel 9' }]))
  const secretLines = [`admin ${adminToken}`, `hook /hooks/${hookSecret}`, `device ${DEVICE_TOKEN}`, PEM_CERT.replace(/\n/g, ' '), PEM_KEY.replace(/\n/g, ' '), `code=${PAIR_CODE}`, `link https://192.168.1.20:47323/pair?c=${PAIR_CODE}&n=box`, `hypernova://pair?c=${PAIR_CODE}`, `Authorization: Bearer ${BEARER}`, `ticket /v1/terms/abc/ws?ticket=${TICKET}`, `provider ${PROVIDER_KEY}`, `fire ${FIRE}`, `MY_TOKEN=${ENV_VALUE}`, `PASSWORD=${SHORT_PASSWORD}`, WEBHOOK]
  const wordLines = ['[claude] 1a2b3c4d please deploy the plan', 'started shared terminal t1 for 1a2b3c4d in /home/ada/secret-project', 'attachment quarterly-report.pdf 12 KB', 'paired Ada’s Pixel Pixel 9', 'claude --bg started nothing: the reply from Claude', 'project created /home/ada/Payroll project', 'file send failed /home/ada/secret-project/contents of a file ENOENT', 'run failed x Error: please deploy the plan', 'Error: rm -rf /home/ada/secret-project failed']
  const lines = [...[...secretLines, ...wordLines].map((l) => `${stamp} ${l}`), '    at thing (/home/ada/secret-project/run.js:1:2)', 'Error: please deploy the plan']
  writeFileSync(join(c.logs, 'debug.log'), [`${stamp} http request method=GET route=/v1/health status=200`, `${stamp} debug on until=x`, ...lines].join('\n') + '\n')
  writeFileSync(join(c.logs, 'debug.log.1'), `${stamp} session state session=older1 from=idle to=working\n`)
  writeFileSync(join(c.logs, 'daemon.log'), [`${stamp} hypernova daemon 1.2.3 starting on linux (pid 42, api 47321, local 47322)`, `${stamp} account: ok again`, ...lines].join('\n') + '\n')
  writeFileSync(join(c.logs, 'terminals.log'), lines.join('\n') + '\n')
  writeFileSync(join(c.logs, 'daemon.stderr.log'), lines.join('\n') + '\n')
}

for (const platform of ['linux', 'darwin'] as const) {
  test(`hypernova debug bundle on ${platform}: one file, scrubbed, its path printed`, async () => {
    const c = ctx({ platform })
    plant(c)
    assert.equal(await bundle(c, {}, { listeners: () => [] }), 0)
    const printed = c.sys.out.join('\n')
    const name = readdirSync(c.logs).find((f) => f.startsWith('debug-bundle-'))!
    assert.ok(name, 'a file was written')
    assert.ok(printed.includes(name), `its path is printed: ${printed}`)
    const file = join(c.logs, name)
    assert.equal(statSync(file).mode & 0o777, 0o600)
    const text = readFileSync(file, 'utf8')
    clean(text)
    assert.ok(!text.includes('Ada’s Pixel') && !text.includes('Pixel 9'), 'the phone’s name')
    assert.ok(!text.includes('ada'), 'the user’s name')
    assert.ok(!text.includes(c.home), 'the home folder')
    // What it's for is there.
    assert.match(text, /== Version and platform ==/)
    assert.match(text, /^hypernova \d+\.\d+\.\d+/m)
    assert.match(text, new RegExp(`platform: ${platform} `))
    assert.match(text, /== hypernova doctor ==\n\[\w+\] /)
    assert.match(text, /http request method=GET route=\/v1\/health status=200/)
    assert.match(text, /session state session=older1 from=idle to=working/)
    assert.match(text, /hypernova daemon 1\.2\.3 starting on linux \(pid 42, api 47321, local 47322\)/)
    assert.match(text, /account: ok again/)
    assert.match(text, /started shared terminal … for 1a2b3c4d in \[path\]/)
    assert.match(text, /^ {4}at thing \[path\]:1:2$/m)
    if (platform === 'darwin') assert.match(text, /the service’s standard error/)
  })
}

test('the bundle goes where it\'s told, and keeps only the newest few in the log folder', async () => {
  const c = ctx()
  plant(c)
  const out = join(c.home, 'mine.txt')
  assert.equal(await bundle(c, { out }, { listeners: () => [] }), 0)
  assert.equal(statSync(out).mode & 0o777, 0o600)
  clean(readFileSync(out, 'utf8'))
  for (let i = 0; i < 5; i++) {
    writeFileSync(join(c.logs, `debug-bundle-2020-01-0${i + 1}.txt`), 'old')
  }
  assert.equal(await bundle(c, {}, { listeners: () => [] }), 0)
  assert.equal(readdirSync(c.logs).filter((f) => f.startsWith('debug-bundle-')).length, 3)
})
