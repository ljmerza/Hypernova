// The Claude Code permission rules in effect (permission-rules.ts; docs/PROTOCOL.md, "Rules in
// effect"): `GET /v1/sessions/{id}/permissions` with fixture settings files in a scratch managed
// folder, Claude folder and project. Highest precedence first; missing, invalid and oversized
// files said by name; lists and rules cut to size; only the permissions lists ever leave, never
// `env`, `apiKeyHelper`, hooks or MCP servers (nor a parse error's quote of a broken file).
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-perm-rules-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
for (const d of [process.env.HOME, process.env.ZDOTDIR, process.env.CLAUDE_CONFIG_DIR]) mkdirSync(d, { recursive: true })

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

const hub = await import('../src/hub.ts')
const { addDevice, saveManaged } = await import('../src/store.ts')
const { startServers } = await import('../src/http.ts')
const rules = await import('../src/permission-rules.ts')
const TOKEN = 'perm-rules-test-device-token-0123456789'
addDevice({ id: 'phone', name: 'Phone', model: 'Pixel', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)

const MANAGED = join(scratch, 'managed')
rules.useManagedDir(MANAGED)
const SECRET = 'sk-ant-api03-TOPSECRET-do-not-leak-0123456789'

/** A project folder with its own `.claude`, and a session in it. */
function project(name: string, sessionId: string): string {
  const cwd = join(scratch, 'projects', name)
  mkdirSync(join(cwd, '.claude'), { recursive: true })
  hub.upsertSession(sessionId, { cwd })
  return cwd
}
const put = (path: string, v: unknown) => writeFileSync(path, typeof v === 'string' ? v : JSON.stringify(v))
const userFile = join(process.env.CLAUDE_CONFIG_DIR, 'settings.json')

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

function call(method: string, path: string, token: string | null = TOKEN): Promise<{ status: number; json: any; text: string }> {
  return new Promise((resolve, reject) => {
    const req = request({ host: '127.0.0.1', port: API, method, path, headers: { host: `127.0.0.1:${API}`, ...(token ? { authorization: `Bearer ${token}` } : {}) } }, (res) => {
      let s = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (s += c))
      res.on('end', () => {
        let json: any = null
        try {
          json = JSON.parse(s)
        } catch {}
        resolve({ status: res.statusCode ?? 0, json, text: s })
      })
    })
    req.on('error', reject)
    req.end()
  })
}

test('highest precedence first: managed (its file, then drop-ins A to Z), local, project, user', async () => {
  mkdirSync(join(MANAGED, 'managed-settings.d'), { recursive: true })
  put(join(MANAGED, 'managed-settings.json'), { permissions: { deny: ['Bash(curl:*)'] } })
  put(join(MANAGED, 'managed-settings.d', '20-b.json'), { permissions: { ask: ['Bash(git push:*)'] } })
  put(join(MANAGED, 'managed-settings.d', '10-a.json'), { permissions: { allow: ['Read'] } })
  put(join(MANAGED, 'managed-settings.d', '.hidden.json'), { permissions: { allow: ['Hidden'] } })
  put(join(MANAGED, 'managed-settings.d', 'notes.txt'), 'not settings')
  const cwd = project('order', 'perm-order')
  put(join(cwd, '.claude', 'settings.local.json'), { permissions: { allow: ['Bash(npm test:*)'], defaultMode: 'acceptEdits' } })
  put(join(cwd, '.claude', 'settings.json'), { permissions: { allow: ['Edit'], deny: ['Read(./.env)'], additionalDirectories: ['../shared'] } })
  put(userFile, { permissions: { allow: ['WebSearch'], ask: ['Bash(rm:*)'], defaultMode: 'plan' } })
  try {
    const r = await call('GET', '/v1/sessions/perm-order/permissions')
    assert.equal(r.status, 200)
    const s = r.json.permissions.sources
    assert.deepEqual(
      s.map((x: any) => [x.source, x.path]),
      [
        ['managed', join(MANAGED, 'managed-settings.json')],
        ['managed', join(MANAGED, 'managed-settings.d', '10-a.json')],
        ['managed', join(MANAGED, 'managed-settings.d', '20-b.json')],
        ['local', join(cwd, '.claude', 'settings.local.json')],
        ['project', join(cwd, '.claude', 'settings.json')],
        ['user', userFile],
      ],
    )
    assert.ok(s.every((x: any) => x.state === 'ok'))
    assert.deepEqual(s[0].deny, ['Bash(curl:*)'])
    assert.deepEqual(s[3], { source: 'local', path: join(cwd, '.claude', 'settings.local.json'), state: 'ok', allow: ['Bash(npm test:*)'], ask: [], deny: [], defaultMode: 'acceptEdits', additionalDirectories: [], clipped: false })
    assert.deepEqual(s[4].additionalDirectories, ['../shared'])
    assert.deepEqual(s[5].ask, ['Bash(rm:*)'])
    assert.equal(s[5].defaultMode, 'plan')
    assert.equal(r.json.permissions.managedOnly, false)
  } finally {
    rmSync(MANAGED, { recursive: true, force: true })
  }
})

test('missing and broken files are said by name; no managed policy, no managed source', async () => {
  const cwd = project('broken', 'perm-broken')
  put(join(cwd, '.claude', 'settings.local.json'), '{"permissions": {"allow": [')
  mkdirSync(join(cwd, '.claude', 'settings.json'))
  put(userFile, '[1, 2, 3]')
  const r = await call('GET', '/v1/sessions/perm-broken/permissions')
  assert.equal(r.status, 200)
  assert.deepEqual(
    r.json.permissions.sources.map((x: any) => [x.source, x.state]),
    [
      ['local', 'invalid'],
      ['project', 'unreadable'],
      ['user', 'invalid'],
    ],
  )
  rmSync(userFile)
  const gone = await call('GET', '/v1/sessions/perm-broken/permissions')
  assert.equal(gone.json.permissions.sources.at(-1).state, 'missing')
  for (const x of gone.json.permissions.sources) assert.deepEqual([x.allow, x.ask, x.deny, x.defaultMode], [[], [], [], null])
})

test('secrets in a settings file never leave: env, apiKeyHelper, hooks, MCP servers, other keys, a broken file', async () => {
  const cwd = project('secret', 'perm-secret')
  const loaded = {
    env: { ANTHROPIC_API_KEY: SECRET, GITHUB_TOKEN: SECRET },
    apiKeyHelper: `echo ${SECRET}`,
    hooks: { PreToolUse: [{ matcher: '*', hooks: [{ type: 'command', command: `curl -H "x: ${SECRET}"` }] }] },
    mcpServers: { s: { command: 'x', env: { KEY: SECRET } } },
    awsAuthRefresh: SECRET,
    permissions: { allow: ['Bash(ls)'], secretish: SECRET, defaultMode: SECRET, additionalDirectories: [{ token: SECRET }] },
  }
  put(join(cwd, '.claude', 'settings.local.json'), loaded)
  put(join(cwd, '.claude', 'settings.json'), `{"env": {"TOKEN": "${SECRET}"}, oops`)
  put(userFile, loaded)
  try {
    const r = await call('GET', '/v1/sessions/perm-secret/permissions')
    assert.equal(r.status, 200)
    assert.ok(!r.text.includes('TOPSECRET'), 'no secret in the response')
    assert.ok(!r.text.includes('ANTHROPIC_API_KEY') && !r.text.includes('apiKeyHelper') && !r.text.includes('mcpServers'))
    const [local, proj, user] = r.json.permissions.sources
    assert.deepEqual(local.allow, ['Bash(ls)'])
    assert.equal(local.defaultMode, null)
    assert.deepEqual(local.additionalDirectories, [])
    assert.equal(proj.state, 'invalid')
    assert.deepEqual(Object.keys(user).sort(), ['additionalDirectories', 'allow', 'ask', 'clipped', 'defaultMode', 'deny', 'path', 'source', 'state'])
  } finally {
    rmSync(userFile)
  }
})

test('cut to size: 200 rules a list, 300 characters a rule, strings only, a big file unread', async () => {
  const cwd = project('caps', 'perm-caps')
  const many = Array.from({ length: 250 }, (_, i) => `Bash(cmd${i})`)
  put(join(cwd, '.claude', 'settings.local.json'), { permissions: { allow: many, deny: ['x'.repeat(1000), 7, null, '  ', 'Read'], additionalDirectories: Array.from({ length: 80 }, (_, i) => `/d${i}`) } })
  put(join(cwd, '.claude', 'settings.json'), JSON.stringify({ permissions: { allow: ['Read'] }, pad: 'p'.repeat(rules.FILE_MAX) }))
  const r = await call('GET', '/v1/sessions/perm-caps/permissions')
  const [local, proj] = r.json.permissions.sources
  assert.equal(local.allow.length, rules.LIST_MAX)
  assert.equal(local.allow[199], 'Bash(cmd199)')
  assert.equal(local.deny.length, 2)
  assert.equal(local.deny[0].length, rules.RULE_MAX)
  assert.ok(local.deny[0].endsWith('…'))
  assert.equal(local.deny[1], 'Read')
  assert.equal(local.additionalDirectories.length, rules.DIRS_MAX)
  assert.equal(local.clipped, true)
  assert.equal(proj.state, 'too_big')
  assert.deepEqual(proj.allow, [])
})

test('the session’s own: an automation’s allow rules and "this session" answers', async () => {
  const cwd = join(scratch, 'projects', 'own')
  mkdirSync(cwd, { recursive: true })
  saveManaged({ id: 'perm-own', cwd, providerId: 'anthropic', model: null, permissionMode: 'default', createdAt: Date.now(), engine: 'term', automation: { id: 'a1', name: 'Nightly', runId: 'r1' }, allowedTools: ['Bash(git log:*)'] })
  hub.upsertSession('perm-own', { cwd })
  const asked = hub.requestApproval({ sessionId: 'perm-own', kind: 'tool', toolName: 'WebFetch', summary: 'WebFetch: x', input: {}, reason: null, canAlways: false, questions: null, plan: null }, null)
  const a = hub.pendingApprovals().find((p) => p.sessionId === 'perm-own')!
  assert.ok(hub.answerApproval(a.id, { decision: 'session' }, 'phone'))
  await asked
  const r = await call('GET', '/v1/sessions/perm-own/permissions')
  const own = r.json.permissions.sources.find((x: any) => x.source === 'session')
  assert.deepEqual(own, { source: 'session', path: null, state: 'ok', allow: ['Bash(git log:*)', 'WebFetch'], ask: [], deny: [], defaultMode: null, additionalDirectories: [], clipped: false })
  // Before the project's files, after the managed policy.
  assert.deepEqual(r.json.permissions.sources.map((x: any) => x.source), ['session', 'local', 'project', 'user'])
})

test('a managed policy that keeps rules to itself says so', async () => {
  mkdirSync(MANAGED, { recursive: true })
  put(join(MANAGED, 'managed-settings.json'), { allowManagedPermissionRulesOnly: true, permissions: { allow: ['Read'] } })
  try {
    project('only', 'perm-only')
    const r = await call('GET', '/v1/sessions/perm-only/permissions')
    assert.equal(r.json.permissions.managedOnly, true)
    // From another file, the same words count for nothing.
    rmSync(join(MANAGED, 'managed-settings.json'))
    put(userFile, { allowManagedPermissionRulesOnly: true, permissions: { allowManagedPermissionRulesOnly: true } })
    assert.equal((await call('GET', '/v1/sessions/perm-only/permissions')).json.permissions.managedOnly, false)
  } finally {
    rmSync(MANAGED, { recursive: true, force: true })
    rmSync(userFile, { force: true })
  }
})

test('a device token is needed; an unknown session is 404; other methods are not this route', async () => {
  project('auth', 'perm-auth')
  assert.equal((await call('GET', '/v1/sessions/perm-auth/permissions', null)).status, 401)
  assert.equal((await call('GET', '/v1/sessions/perm-auth/permissions', 'not-a-device-token')).status, 401)
  assert.equal((await call('GET', '/v1/sessions/perm-nobody/permissions')).status, 404)
  assert.notEqual((await call('POST', '/v1/sessions/perm-auth/permissions')).status, 200)
})
