// An agent's environment variables (agent-env.ts): what may be seen, what may be set, on the server whatever the app
// sends. Every file here is a scratch one.
import { test, before, after, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-agent-env-')))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.CODEX_HOME = join(scratch, 'codex')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
process.env.HYPERNOVA_WSL = '0'
const CLAUDE = process.env.CLAUDE_CONFIG_DIR
mkdirSync(process.env.HOME, { recursive: true })
mkdirSync(CLAUDE, { recursive: true })
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

const { guardOf, shownValue, changeWords } = await import('../src/agent-env.ts')
const { folderId, MASK } = await import('../src/mcp-view.ts')
const { startServers } = await import('../src/http.ts')
const { addDevice, settings } = await import('../src/store.ts')
const { useCodex } = await import('../src/mcp-codex.ts')
settings.lanAccess = false
const TOKEN = 'agent-env-test-device-token-0123456789abcd'
addDevice({ id: 'phone', name: 'Pixel', model: 'Pixel 9', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)

let close: () => Promise<void>
before(async () => {
  close = startServers()
  for (let i = 0; i < 100; i++) {
    try {
      if ((await fetch(`http://127.0.0.1:${API}/v1/health`)).ok) return
    } catch {}
    await new Promise((r) => setTimeout(r, 20))
  }
})
after(async () => {
  await close?.()
  rmSync(scratch, { recursive: true, force: true })
})

const USER = join(CLAUDE, 'settings.json')
const SUPPORT = process.env.HYPERNOVA_SUPPORT
beforeEach(() => {
  rmSync(USER, { force: true })
  rmSync(join(CLAUDE, '.claude.json'), { force: true })
  rmSync(join(SUPPORT, 'settings-backups'), { recursive: true, force: true })
})

const PLANT = 'PLANTEDenvSecret0123456789abcdefABCDEF'
const sha = (s: string) => createHash('sha256').update(s).digest('hex')
const call = async (method: string, path: string, body?: unknown, token: string | null = TOKEN) => {
  const r = await fetch(`http://127.0.0.1:${API}${path}`, { method, headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), 'content-type': 'application/json' }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
  const text = await r.text()
  assert.ok(!text.includes(PLANT), `a secret reached the phone from ${path}: ${text}`)
  return { status: r.status, body: JSON.parse(text) as any, text }
}
const list = async (folder?: string) => {
  const r = await call('GET', `/v1/agents/env?agent=claude${folder ? `&folder=${folder}` : ''}`)
  assert.equal(r.status, 200, r.text)
  return r.body
}
const by = (b: any, scope: string) => Object.fromEntries(b.vars.filter((v: any) => v.scope === scope).map((v: any) => [v.name, v]))
const put = (name: string, value: string, expected: unknown, more: object = {}) => call('POST', '/v1/agents/env', { agent: 'claude', scope: 'user', name, value, expected, ...more })
const flip = (name: string, expected: unknown, more: object = {}) => call('POST', '/v1/agents/env', { agent: 'claude', scope: 'user', name, flip: true, expected, ...more })
const del = (name: string, expected: unknown, more: object = {}) => call('POST', '/v1/agents/env/remove', { agent: 'claude', scope: 'user', name, expected, ...more })
const fileEnv = (file = USER) => JSON.parse(readFileSync(file, 'utf8')).env
const hashOf = (file = USER) => sha(readFileSync(file, "utf8"))
const project = (name: string) => {
  const d = join(scratch, 'projects', `${name}-${Math.random().toString(36).slice(2, 7)}`)
  mkdirSync(join(d, '.claude'), { recursive: true })
  return d
}

test('the list: flags, plain values, secrets by name and by look, no secret in the raw body, no path', async () => {
  writeFileSync(USER, JSON.stringify({ hooks: { Stop: [{ hooks: [{ type: 'command', command: PLANT }] }] }, env: {
    A_FLAG: '1', B_OFF: 'false', C_TEXT: 'hello there', MAX_OUTPUT_TOKENS: '64000', DISABLE_AUTH: '1',
    API_TOKEN: PLANT, MY_PASSWORD: 'hunter2', OTHER: `sk-ant-api03-${PLANT}`, SOME_URL: `https://u:${PLANT}@x.example/m?key=${PLANT}&page=2`,
    JSON_TRUE: true, JSON_NUM: 1, COUNT: 12, [`sk-ant-api03-${PLANT}`]: 'odd', nested: { x: PLANT },
  } }))
  const b = await list()
  const u = by(b, 'user')
  assert.equal(u.A_FLAG.flag, true)
  assert.equal(u.A_FLAG.value, '1')
  assert.equal(u.B_OFF.flag, false)
  assert.equal(u.C_TEXT.value, 'hello there')
  assert.equal(u.C_TEXT.flag, undefined)
  assert.equal(u.MAX_OUTPUT_TOKENS.masked, false)
  assert.equal(u.MAX_OUTPUT_TOKENS.value, '64000')
  // A flag under a word like auth is still a flag.
  assert.equal(u.DISABLE_AUTH.flag, true)
  for (const n of ['API_TOKEN', 'MY_PASSWORD', 'OTHER']) {
    assert.equal(u[n].masked, true, n)
    assert.equal(u[n].value, undefined, n)
  }
  assert.equal(u.SOME_URL.masked, true)
  assert.equal(u.SOME_URL.value, `https://${MASK}@x.example/m?key=${MASK}&page=2`)
  assert.equal(u.JSON_TRUE.flag, true)
  assert.equal(u.JSON_NUM.flag, true)
  assert.equal(u.COUNT.value, '12')
  // A name that itself looks like a secret, and what isn't a value, aren't sent.
  assert.equal(b.hidden, 2)
  assert.ok(!b.vars.some((v: any) => v.name === 'nested'))
  assert.equal(b.files.find((f: any) => f.scope === 'user').where, '~/.claude/settings.json')
  assert.ok(!JSON.stringify(b).includes(scratch))
})

test('where each comes from, and which is in effect: local over project over user', async () => {
  const app = project('scopes')
  writeFileSync(USER, JSON.stringify({ env: { A: 'user', B: 'user', C: 'user' } }))
  writeFileSync(join(app, '.claude/settings.json'), JSON.stringify({ env: { A: 'project', B: 'project' } }))
  writeFileSync(join(app, '.claude/settings.local.json'), JSON.stringify({ env: { A: 'local', c: 'lower' } }))
  writeFileSync(join(CLAUDE, '.claude.json'), JSON.stringify({ projects: { [app]: {} } }))
  const b = await list(folderId(app))
  assert.equal(b.folder, folderId(app))
  assert.deepEqual(b.files.map((f: any) => f.scope), ['user', 'project', 'local'])
  assert.ok(b.files[2].where.endsWith('/.claude/settings.local.json'))
  assert.ok(!b.files[2].where.includes(scratch))
  const [u, p, l] = ['user', 'project', 'local'].map((s) => by(b, s))
  assert.equal(l!.A.effective, true)
  assert.equal(p!.A.effective, false)
  assert.equal(p!.A.overriddenBy, 'local')
  assert.equal(u!.A.overriddenBy, 'local')
  assert.equal(p!.B.effective, true)
  assert.equal(u!.B.overriddenBy, 'project')
  // Claude Code reads names in capitals.
  assert.equal(u!.C.overriddenBy, 'local')
  // A folder whose project file is the user's own, as the home folder's is, doesn't list it twice.
  const { claudeEnv } = await import('../src/agent-env.ts')
  const home = await claudeEnv(app, '~', join(app, '.claude'))
  assert.deepEqual(home.files.map((f) => f.scope), ['user', 'local'])
})

test('add, change, flip keeping the form, delete: every other byte stays, and it goes where it\'s told', async () => {
  const text = '{\n    "hooks": { "Stop": [ ] },\n  "env": {\n    "KEEP": "1.0e3",\n    "F1": "1",\n    "F2": "true",\n    "F3": "On",\n    "F4": false,\n    "F5": 1\n  },\n  "numbers": [1.0, 1e3],\n  "theme": "dark"\n}\n'
  writeFileSync(USER, text)
  let r = await put('NEW_ONE', 'hello', sha(text))
  assert.equal(r.status, 200, r.text)
  assert.equal(r.body.value, 'hello')
  let now = readFileSync(USER, 'utf8')
  assert.ok(now.includes('"numbers": [1.0, 1e3]'))
  assert.ok(now.includes('"hooks": { "Stop": [ ] }'))
  assert.equal(fileEnv().NEW_ONE, 'hello')
  for (const [name, off] of [['F1', '0'], ['F2', 'false'], ['F3', 'Off'], ['F4', true], ['F5', 0]] as const) {
    r = await flip(name, hashOf())
    assert.equal(r.status, 200, r.text)
    assert.equal(fileEnv()[name], off, name)
  }
  // ...and back: each keeps its form.
  r = await flip('F3', hashOf())
  assert.equal(fileEnv().F3, 'On')
  assert.equal(r.body.flag, true)
  assert.equal((await flip('KEEP', hashOf())).body.error.code, 'not_a_flag')
  r = await del('NEW_ONE', hashOf())
  assert.equal(r.status, 200, r.text)
  assert.equal(fileEnv().NEW_ONE, undefined)
  now = readFileSync(USER, 'utf8')
  assert.ok(now.includes('"theme": "dark"'))
  assert.equal((await del('NOT_THERE', hashOf())).status, 404)
})

test('a file\'s first variable makes the file, and a project\'s and a local one go in that folder\'s', async () => {
  const r = await put('FIRST', '1', null)
  assert.equal(r.status, 200, r.text)
  assert.deepEqual(JSON.parse(readFileSync(USER, 'utf8')), { env: { FIRST: '1' } })
  const app = project('files')
  rmSync(join(app, '.claude'), { recursive: true })
  writeFileSync(join(CLAUDE, '.claude.json'), JSON.stringify({ projects: { [app]: {} } }))
  const folder = folderId(app)
  assert.equal((await call('POST', '/v1/agents/env', { agent: 'claude', scope: 'local', folder, name: 'MINE', value: 'x', expected: null })).status, 200)
  assert.equal(fileEnv(join(app, '.claude/settings.local.json')).MINE, 'x')
  assert.equal((await call('POST', '/v1/agents/env', { agent: 'claude', scope: 'project', folder, name: 'OURS', value: 'y', expected: null })).status, 200)
  assert.equal(fileEnv(join(app, '.claude/settings.json')).OURS, 'y')
  assert.equal((await call('POST', '/v1/agents/env', { agent: 'claude', scope: 'project', folder: 'nope', name: 'OURS', value: 'y', expected: null })).status, 404)
  assert.equal((await call('POST', '/v1/agents/env', { agent: 'claude', scope: 'managed', name: 'X', value: 'y', expected: null })).body.error.code, 'bad_scope')
})

test('names and values are checked here, and a masked value can\'t be written as dots', async () => {
  for (const name of ['1A', 'A B', 'A=B', 'A-B', '', 'A.B', '__proto__x y', 3, null]) assert.equal((await put(name as string, 'x', null)).body.error.code, 'bad_name', String(name))
  for (const value of ['a\nb', 'a\u0000b', 'a\tb', 'x'.repeat(8193), 3]) assert.equal((await put('OK', value as string, null)).body.error.code, 'bad_value', String(value).slice(0, 8))
  assert.equal((await put('OK', `a${MASK}b`, null)).body.error.code, 'masked_value')
  assert.equal(existsSync(USER), false)
})

test('a file changed since it was read, a link, a malformed file: refused, nothing written', async () => {
  writeFileSync(USER, '{"env":{"A":"1"}}')
  const stale = await put('B', 'x', 'old')
  assert.equal(stale.body.error.code, 'file_changed')
  assert.match(stale.body.error.message, /changed on the server/)
  assert.deepEqual(fileEnv(), { A: '1' })
  writeFileSync(USER, '{"env":')
  const b = await list()
  assert.equal(b.files[0].state, 'malformed')
  assert.equal((await put('B', 'x', hashOf())).body.error.code, 'file_malformed')
  writeFileSync(USER, '{"env":"not a table"}')
  assert.equal((await put('B', 'x', hashOf())).body.error.code, 'unsupported_shape')
  rmSync(USER)
  const real = join(scratch, 'elsewhere.json')
  writeFileSync(real, '{}')
  symlinkSync(real, USER)
  assert.equal((await list()).files[0].state, 'link')
  assert.equal((await put('B', 'x', null)).body.error.code, 'file_is_link')
  assert.equal(readFileSync(real, 'utf8'), '{}')
  rmSync(USER)
})

test('what changes what runs or where data goes needs the check, on the server, setting and taking away', async () => {
  writeFileSync(USER, '{}')
  const guarded = ['PATH', 'LD_PRELOAD', 'LD_LIBRARY_PATH', 'DYLD_INSERT_LIBRARIES', 'NODE_OPTIONS', 'NODE_PATH', 'PYTHONPATH', 'BASH_ENV', 'ENV', 'SHELL', 'ANTHROPIC_BASE_URL', 'OPENAI_BASE_URL', 'SOME_THING_BASE_URL', 'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NODE_TLS_REJECT_UNAUTHORIZED', 'CLAUDE_CODE_USE_BEDROCK', 'CLAUDE_CODE_USE_VERTEX', 'CLAUDE_CODE_SKIP_BEDROCK_AUTH', 'ANTHROPIC_CUSTOM_HEADERS', 'OTEL_EXPORTER_OTLP_ENDPOINT', 'OTEL_LOG_USER_PROMPTS', 'CLAUDE_CODE_SHELL_PREFIX', 'CLAUDE_CODE_API_KEY_HELPER_TTL_MS', 'CLAUDE_CONFIG_DIR', 'GIT_SSH_COMMAND', 'path', 'node_options']
  for (const name of guarded) assert.equal(guardOf(name), 'guarded', name)
  const r = await put('LD_PRELOAD', '/tmp/evil.so', hashOf())
  assert.equal(r.status, 403)
  assert.equal(r.body.error.code, 'confirm_run')
  assert.ok(r.body.error.message.includes('LD_PRELOAD: (not set) → /tmp/evil.so'))
  assert.equal(fileEnv(), undefined)
  assert.equal((await put('LD_PRELOAD', '/tmp/x.so', hashOf(), { confirmRun: true })).status, 200)
  const row = by(await list(), 'user').LD_PRELOAD
  assert.equal(row.guarded, true)
  assert.equal((await del('LD_PRELOAD', hashOf())).body.error.code, 'confirm_run')
  assert.equal((await del('LD_PRELOAD', hashOf(), { confirmRun: true })).status, 200)
  // A plain one asks nothing.
  assert.equal(guardOf('DISABLE_TELEMETRY'), 'plain')
  assert.equal(guardOf('CLAUDE_CODE_MAX_OUTPUT_TOKENS'), 'plain')
  assert.equal((await put('DISABLE_TELEMETRY', '1', hashOf())).status, 200)
})

test('what would turn on bypass or trust is refused outright, confirmed or not; it can only be taken away, and Undo won\'t put it back', async () => {
  const refused = ['IS_SANDBOX', 'CLAUDE_CODE_SANDBOXED', 'CLAUDE_CODE_EVEN_MORE_DANGEROUSLY_SKIP_PERMISSIONS', 'CLAUDE_BG_WORKSPACE_TRUSTED', 'CLAUDE_CODE_IGNORE_PERSISTED_TRUST', 'CLAUDE_CHROME_PERMISSION_MODE', 'SOME_NEW_BYPASS_FLAG', 'DANGER_FULL_ACCESS', 'is_sandbox']
  writeFileSync(USER, JSON.stringify({ env: { IS_SANDBOX: '1' } }))
  for (const name of refused) {
    assert.equal(guardOf(name), 'refused', name)
    const r = await put(name, '1', hashOf(), { confirmRun: true })
    assert.equal(r.status, 403, name)
    assert.equal(r.body.error.code, 'env_refused')
    assert.ok(r.body.error.message.includes(name))
  }
  assert.equal((await flip('IS_SANDBOX', hashOf(), { confirmRun: true })).body.error.code, 'env_refused')
  const b = await list()
  assert.equal(by(b, 'user').IS_SANDBOX.refused, true)
  // Moving off it asks nothing.
  assert.equal((await del('IS_SANDBOX', hashOf())).status, 200)
  assert.equal(fileEnv(), undefined)
  const offered = (await list()).undo
  assert.equal(offered, null)
  assert.equal((await call('POST', '/v1/agents/env/undo', { agent: 'claude', confirmRun: true })).body.error.code, 'undo_refused')
  assert.equal(fileEnv(), undefined)
})

test('Undo: one step, the file as it was byte for byte, asking where the change would; a file the change made goes as it came', async () => {
  const text = '{ "theme":"dark",   "env": {"A":"1"} }\n'
  writeFileSync(USER, text)
  assert.equal((await flip('A', sha(text))).status, 200)
  const offer = (await list()).undo
  assert.deepEqual(offer, { name: 'A', scope: 'user', folder: null, needsCheck: false, change: 'A: 0 → 1' })
  assert.equal((await call('POST', '/v1/agents/env/undo', { agent: 'claude' })).status, 200)
  assert.equal(readFileSync(USER, 'utf8'), text)
  assert.equal((await list()).undo, null)
  assert.equal((await call('POST', '/v1/agents/env/undo', { agent: 'claude' })).body.error.code, 'nothing_to_undo')

  rmSync(USER)
  assert.equal((await put('NODE_OPTIONS', '--x', null, { confirmRun: true })).status, 200)
  const guarded = (await list()).undo
  assert.equal(guarded.needsCheck, true)
  assert.equal(guarded.change, 'NODE_OPTIONS: --x → (not set)')
  assert.equal((await call('POST', '/v1/agents/env/undo', { agent: 'claude' })).body.error.code, 'confirm_run')
  assert.equal((await call('POST', '/v1/agents/env/undo', { agent: 'claude', confirmRun: true })).status, 200)
  assert.equal(existsSync(USER), false)

  // Someone else's write since: Undo is off, and refused.
  writeFileSync(USER, '{}')
  await put('A', 'x', hashOf())
  writeFileSync(USER, '{"env":{"A":"theirs"}}')
  assert.equal((await list()).undo, null)
  assert.equal((await call('POST', '/v1/agents/env/undo', { agent: 'claude' })).body.error.code, 'file_changed')
  assert.equal(fileEnv().A, 'theirs')
})

test('a secret\'s new value is write-only, and it stays out of the log, the replies and the Undo\'s words', async () => {
  writeFileSync(USER, '{}')
  const r = await put('API_TOKEN', PLANT, hashOf())
  assert.equal(r.status, 200)
  assert.equal(r.body.masked, true)
  assert.equal(r.body.value, undefined)
  assert.equal(fileEnv().API_TOKEN, PLANT)
  assert.equal((await list()).undo.change, `API_TOKEN: ${MASK} → (not set)`)
  const stale = await put('LD_PRELOAD', PLANT, 'old', { confirmRun: true })
  assert.equal(stale.status, 409)
  const ask = await put('HTTP_PROXY', `http://u:${PLANT}@proxy.example:3128`, hashOf())
  assert.equal(ask.status, 403)
  assert.ok(ask.body.error.message.includes(`http://${MASK}@proxy.example:3128`))
  assert.ok(!readFileSync(join(process.env.HYPERNOVA_LOGS!, 'daemon.log'), 'utf8').includes(PLANT))
})

test('only a paired phone, on every route', async () => {
  for (const [m, p] of [['GET', '/v1/agents/env?agent=claude'], ['POST', '/v1/agents/env'], ['POST', '/v1/agents/env/remove'], ['POST', '/v1/agents/env/undo']] as const) {
    assert.equal((await call(m, p, m === 'POST' ? { agent: 'claude' } : undefined, null)).status, 401, p)
  }
  assert.equal((await call('GET', '/v1/agents/env?agent=other')).status, 400)
})

test('words for a change, and shown values on their own', () => {
  assert.equal(changeWords('PATH', '/a', '/b'), 'PATH: /a → /b')
  assert.equal(changeWords('API_KEY', 'x', null), `API_KEY: ${MASK} → (not set)`)
  assert.deepEqual(shownValue('COUNT_TOKENS', '123'), { masked: false, value: '123' })
  assert.deepEqual(shownValue('COUNT_TOKENS', 'abc'), { masked: true })
  assert.deepEqual(shownValue('MY_FLAG', 'yes'), { masked: false, value: 'yes' })
  assert.deepEqual(shownValue('LONG', 'x '.repeat(400)), { masked: true })
})

test('Codex, from a stand-in: its one table; the same guards; what its writer is told', async () => {
  const writes: any[] = []
  let user: Record<string, unknown> = { HN_FLAG: 'true', API_KEY: PLANT, PATH: '/x', HELLO: 'there' }
  let version = 'sha256:one'
  useCodex({
    rpc: async (work) =>
      work(async (method: string, params: any) => {
        if (method === 'config/read') return { config: { shell_environment_policy: { set: { ...user, HELLO: 'managed' } } }, layers: [{ name: { type: 'system' }, config: {} }, { name: { type: 'user', file: '/never/sent' }, version, config: { model: 'x', shell_environment_policy: { set: user } } }] }
        if (method === 'config/value/write') {
          writes.push(params)
          if (params.expectedVersion !== version) throw Object.assign(new Error('Configuration was modified since last read.'), {})
          const name = params.keyPath.split('.').at(-1)
          if (params.value === null) delete user[name]
          else user[name] = params.value
          version = `sha256:${writes.length + 1}`
          return { status: 'ok', version }
        }
        return {}
      }),
  })
  try {
    const b = (await call('GET', '/v1/agents/env?agent=codex')).body
    assert.deepEqual(b.files, [{ scope: 'user', where: '~/.codex/config.toml', state: 'ok', hash: 'sha256:one' }])
    assert.ok(!JSON.stringify(b).includes('/never/sent'))
    const row = Object.fromEntries(b.vars.map((v: any) => [v.name, v])) as Record<string, any>
    assert.equal(row.HN_FLAG.flag, true)
    assert.equal(row.API_KEY.masked, true)
    assert.equal(row.PATH.guarded, true)
    assert.equal(row.HELLO.overriddenBy, 'system')
    const send = (o: object) => call('POST', '/v1/agents/env', { agent: 'codex', ...o })
    assert.equal((await send({ name: 'PATH', value: '/y', expected: version })).body.error.code, 'confirm_run')
    assert.equal((await send({ name: 'IS_SANDBOX', value: '1', expected: version, confirmRun: true })).body.error.code, 'env_refused')
    assert.equal((await send({ name: 'X', value: '1', expected: 'old' })).body.error.code, 'file_changed')
    assert.equal(writes.length, 0)
    const r = await send({ name: 'HN_FLAG', flip: true, expected: version })
    assert.equal(r.status, 200, r.text)
    assert.deepEqual(writes[0], { keyPath: 'shell_environment_policy.set.HN_FLAG', value: 'false', mergeStrategy: 'upsert', expectedVersion: 'sha256:one' })
    assert.equal((await call('GET', '/v1/agents/env?agent=codex')).body.undo.change, 'HN_FLAG: false → true')
    assert.equal((await call('POST', '/v1/agents/env/undo', { agent: 'codex' })).status, 200)
    assert.equal(user.HN_FLAG, 'true')
    assert.equal((await call('POST', '/v1/agents/env/remove', { agent: 'codex', name: 'HELLO', expected: version })).status, 200)
    assert.equal(user.HELLO, undefined)
  } finally {
    useCodex(null)
  }
})
