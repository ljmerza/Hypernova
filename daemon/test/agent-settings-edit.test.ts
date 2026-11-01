// PUT /v1/agents/settings and its undo (agent-settings-edit.ts): what may be set, that nothing else in
// the file moves by a byte, and that a refusal writes nothing. Every file here is a scratch one.
import { test, before, after, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { chmodSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-agent-settings-edit-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.CODEX_HOME = join(scratch, 'codex')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
process.env.HYPERNOVA_WSL = '0'
mkdirSync(process.env.HOME, { recursive: true })
const CLAUDE = process.env.CLAUDE_CONFIG_DIR
const CODEX = process.env.CODEX_HOME
mkdirSync(CLAUDE, { recursive: true })
mkdirSync(CODEX, { recursive: true })
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
const LOCAL = await freePort()
process.env.HYPERNOVA_API_PORT = String(API)
process.env.HYPERNOVA_LOCAL_PORT = String(LOCAL)
process.env.HYPERNOVA_LAN_PORT = '0'

const edit = await import('../src/agent-settings-edit.ts')
const { BACKUPS_KEPT, backupDir, changeSetting, EDITABLE, EditRefused, jsonEdit, loosens, settingsReply, tomlEdit, undoChange } = edit
const { CLAUDE_SHOWN, CODEX_SHOWN } = await import('../src/agent-settings.ts')
const { startServers } = await import('../src/http.ts')
const { addDevice, adminToken, settings } = await import('../src/store.ts')
settings.lanAccess = false
const TOKEN = 'agent-settings-edit-test-device-token-0123456789'
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
beforeEach(() => {
  rmSync(backupDir(), { recursive: true, force: true })
  rmSync(join(CLAUDE, 'settings.json'), { force: true })
  rmSync(join(CODEX, 'config.toml'), { force: true })
})

const sha = (b: Buffer | string) => createHash('sha256').update(b).digest('hex')
const CLAUDE_FILE = join(CLAUDE, 'settings.json')
const CODEX_FILE = join(CODEX, 'config.toml')
const noModels = async () => null
const someModels = async () => [{ value: 'gpt-6.1-sol', name: 'GPT 6.1 Sol' }, { value: 'gpt-6-luna', name: 'GPT 6 Luna' }]

const put = (body: unknown, headers: Record<string, string> = { authorization: `Bearer ${TOKEN}` }, port = API) =>
  fetch(`http://127.0.0.1:${port}/v1/agents/settings`, { method: 'PUT', headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(body) })
const undo = (body: unknown, headers: Record<string, string> = { authorization: `Bearer ${TOKEN}` }, port = API) =>
  fetch(`http://127.0.0.1:${port}/v1/agents/settings/undo`, { method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(body) })
const read = async () => (await fetch(`http://127.0.0.1:${API}/v1/agents/settings`, { headers: { authorization: `Bearer ${TOKEN}` } })).json() as Promise<any>
const code = async (r: Response) => ((await r.json()) as { error: { code: string } }).error.code

const dir = (name: string) => {
  const d = join(scratch, 'files', `${name}-${Math.random().toString(36).slice(2)}`)
  mkdirSync(d, { recursive: true })
  return d
}
const backups = () => (existsSync(backupDir()) ? readdirSync(backupDir()).filter((n) => /^(claude|codex)-\d+$/.test(n)) : [])

/** [next] is [old] with one stretch replaced, [from] by [to]: every other byte, before and after, the same. */
function onlyThisChanged(old: string, next: string, from: string, to: string) {
  // The first byte that differs is inside the stretch, so the search starts no later than that.
  let first = 0
  while (first < old.length && old[first] === next[first]) first++
  const at = old.indexOf(from, Math.max(0, first - from.length))
  assert.ok(at >= 0 && at <= first, `not there: ${from}`)
  assert.equal(next, old.slice(0, at) + to + old.slice(at + from.length))
}

/** A settings.json with what must never move: hooks, env, odd spacing, escapes, numbers a re-serialize would change. */
const RICH_JSON = `{
    "env":   { "PATH_EXTRA": "/a\\u002fb", "KEY" : "sk-ant-api03-PLANTEDEnvAbcdefghijklmnop0123456789" },
  "hooks": {
    "Stop": [ { "hooks": [ { "type": "command", "command": "echo \\"done\\" # {\\"permissions\\":1}" } ] } ]
  },
  "numbers": [1.0, 1e3, 0.10, 12345678901234567890],

  "__proto__": { "x": 1 },
  "unicode": "é ☃ \\u00e9",
  "permissions": {
    "allow": [
      "Read",   "Bash(npm test *)"
    ],
    "defaultMode": "acceptEdits",
    "deny": []
  },
  "showThinkingSummaries":false
  ,"statusLine": { "type": "command", "command": "x" }
}
`

const RICH_TOML = `# my config, with a "model" in its comment
model = "gpt-6.1-sol"   # the one I like
model_reasoning_effort = 'high'
developer_instructions = """
model = "not this"
sandbox_mode = "danger-full-access"
[not.a.table]
"""
notify = [
  "sh", # a comment in an array
  "-c",
]
"web_search" = "cached"

# about the projects
[projects."/home/me/a"]
trust_level = "trusted"
sandbox_mode = "inside a table"

[mcp_servers.fetch]
command = "npx"
`

// ── What may be set ──────────────────────────────────────────────────────

test('every editable key is one that is shown, and every word it takes is one the agent has', () => {
  for (const r of EDITABLE) {
    const s = (r.agent === 'claude' ? CLAUDE_SHOWN : CODEX_SHOWN).find((x) => x.key === r.key)
    assert.ok(s, r.key)
    if (r.of !== 'models' && s!.kind === 'word') for (const v of r.of) assert.ok(s!.of.includes(v as string), `${r.key}: ${v}`)
    if (s!.kind === 'boolean') assert.deepEqual(r.of, [true, false])
  }
  assert.deepEqual(EDITABLE.map((r) => `${r.agent}:${r.key}`), [
    'claude:permissions.defaultMode', 'claude:showThinkingSummaries', 'claude:autoUpdatesChannel',
    'codex:model', 'codex:model_reasoning_effort', 'codex:approval_policy', 'codex:sandbox_mode', 'codex:web_search',
  ])
})

test('the permissive values and the ones that break an agent are on no list, for any key', () => {
  const all = EDITABLE.flatMap((r) => (r.of === 'models' ? [] : r.of))
  for (const v of ['bypassPermissions', 'never', 'danger-full-access', 'rc', 'untrusted', 'on-failure', 'manual']) assert.ok(!all.includes(v), v)
})

test('the order of permissiveness: what loosens and what doesn’t', () => {
  const set = (key: string, value: string) => ({ key, state: 'set' as const, value })
  const k = 'permissions.defaultMode'
  assert.equal(loosens(k, set(k, 'plan'), 'default'), true)
  assert.equal(loosens(k, set(k, 'default'), 'acceptEdits'), true)
  assert.equal(loosens(k, set(k, 'acceptEdits'), 'auto'), true)
  assert.equal(loosens(k, set(k, 'auto'), 'acceptEdits'), false)
  assert.equal(loosens(k, set(k, 'bypassPermissions'), 'auto'), false)
  assert.equal(loosens(k, set(k, 'bypassPermissions'), 'plan'), false)
  // Claude Code's own table has these level; manual is default's other name.
  assert.equal(loosens(k, set(k, 'dontAsk'), 'default'), false)
  assert.equal(loosens(k, set(k, 'manual'), 'default'), false)
  // Not set is default: only what's looser than that asks.
  assert.equal(loosens(k, { key: k, state: 'unset' }, 'plan'), false)
  assert.equal(loosens(k, { key: k, state: 'unset' }, 'default'), false)
  assert.equal(loosens(k, { key: k, state: 'unset' }, 'acceptEdits'), true)
  // Unrecognised: it can't be placed, so it counts.
  assert.equal(loosens(k, { key: k, state: 'unrecognised' }, 'plan'), true)
  assert.equal(loosens('sandbox_mode', set('sandbox_mode', 'read-only'), 'workspace-write'), true)
  assert.equal(loosens('sandbox_mode', set('sandbox_mode', 'danger-full-access'), 'workspace-write'), false)
  assert.equal(loosens('sandbox_mode', { key: 'sandbox_mode', state: 'unset' }, 'read-only'), false)
  assert.equal(loosens('sandbox_mode', { key: 'sandbox_mode', state: 'unset' }, 'workspace-write'), true)
  assert.equal(loosens('approval_policy', set('approval_policy', 'never'), 'on-request'), false)
  assert.equal(loosens('approval_policy', set('approval_policy', 'untrusted'), 'on-request'), true)
  assert.equal(loosens('approval_policy', set('approval_policy', 'on-failure'), 'on-request'), false)
  // Only the permission keys ever do.
  assert.equal(loosens('web_search', set('web_search', 'disabled'), 'live'), false)
  assert.equal(loosens('__proto__', { key: '__proto__', state: 'unset' }, 'x'), false)
})

test('every key off the list is refused, nested paths and __proto__ too, and nothing is written', async () => {
  writeFileSync(CLAUDE_FILE, RICH_JSON)
  writeFileSync(CODEX_FILE, RICH_TOML)
  const hashes = { claude: sha(RICH_JSON), codex: sha(RICH_TOML) }
  const refused: [string, unknown][] = [
    ...['hooks', 'env', 'permissions', 'permissions.allow', 'permissions.deny', 'permissions.defaultMode.x', 'permissions.__proto__', '__proto__', 'constructor', 'prototype', 'hasOwnProperty', '', 'effortLevel', 'includeCoAuthoredBy', 'autoDreamEnabled', 'inputNeededNotifEnabled', 'agentPushNotifEnabled', 'statusLine', 'apiKeyHelper', 'skipDangerousModePermissionPrompt', 'model', 'sandbox_mode'].map((k) => ['claude', k] as [string, unknown]),
    ['claude', ['permissions', 'defaultMode']],
    ['claude', { key: 'permissions.defaultMode' }],
    ['claude', null],
    ['claude', 5],
    ...['personality', 'notify', 'mcp_servers.fetch.command', 'projects', 'developer_instructions', 'experimental_bearer_token', '__proto__', 'permissions.defaultMode', 'showThinkingSummaries'].map((k) => ['codex', k] as [string, unknown]),
  ]
  for (const [agent, key] of refused) {
    const r = await put({ agent, key, value: 'plan', expected: hashes[agent as 'claude' | 'codex'] })
    assert.equal(r.status, 403, JSON.stringify(key))
    assert.equal(await code(r), 'key_not_editable', JSON.stringify(key))
  }
  assert.equal(readFileSync(CLAUDE_FILE, 'utf8'), RICH_JSON)
  assert.equal(readFileSync(CODEX_FILE, 'utf8'), RICH_TOML)
  assert.deepEqual(backups(), [])
})

test('refused values: permissive, off the list, the wrong type, arrays, objects; nothing is written', async () => {
  writeFileSync(CLAUDE_FILE, RICH_JSON)
  writeFileSync(CODEX_FILE, RICH_TOML)
  const h = { claude: sha(RICH_JSON), codex: sha(RICH_TOML) }
  const cases: [string, string, unknown][] = [
    ['claude', 'permissions.defaultMode', 'bypassPermissions'],
    ['claude', 'permissions.defaultMode', 'manual'],
    ['claude', 'permissions.defaultMode', 'BYPASSPERMISSIONS'],
    ['claude', 'permissions.defaultMode', ''],
    ['claude', 'permissions.defaultMode', true],
    ['claude', 'permissions.defaultMode', ['plan']],
    ['claude', 'permissions.defaultMode', { value: 'plan' }],
    ['claude', 'permissions.defaultMode', null],
    ['claude', 'permissions.defaultMode', 3],
    ['claude', 'autoUpdatesChannel', 'rc'],
    ['claude', 'autoUpdatesChannel', 'nightly'],
    ['claude', 'showThinkingSummaries', 'true'],
    ['claude', 'showThinkingSummaries', 1],
    ['codex', 'approval_policy', 'never'],
    ['codex', 'approval_policy', 'untrusted'],
    ['codex', 'approval_policy', 'on-failure'],
    ['codex', 'sandbox_mode', 'danger-full-access'],
    ['codex', 'sandbox_mode', { type: 'danger-full-access' }],
    ['codex', 'web_search', 'everything'],
    ['codex', 'web_search', '"live"\nsandbox_mode = "danger-full-access"'],
    ['codex', 'model_reasoning_effort', 'silly'],
  ]
  for (const [agent, key, value] of cases) {
    const r = await put({ agent, key, value, expected: h[agent as 'claude' | 'codex'] })
    assert.equal(r.status, 403, `${key}: ${JSON.stringify(value)}`)
    assert.equal(await code(r), 'value_not_allowed')
  }
  assert.equal(sha(readFileSync(CLAUDE_FILE)), h.claude)
  assert.equal(sha(readFileSync(CODEX_FILE)), h.codex)
  assert.deepEqual(backups(), [])
})

test('a body that isn’t the shape: refused before any file is touched', async () => {
  for (const body of [[], 'plan', 5, null]) assert.equal((await put(body)).status, 400, JSON.stringify(body))
  assert.equal(await code(await put({ agent: 'gemini', key: 'model', value: 'x', expected: null })), 'bad_agent')
  assert.equal(await code(await put({ agent: '__proto__', key: 'model', value: 'x', expected: null })), 'bad_agent')
  assert.equal(await code(await put({ agent: 'claude', key: 'autoUpdatesChannel', value: 'stable' })), 'bad_expected')
  assert.equal(await code(await put({ agent: 'claude', key: 'autoUpdatesChannel', value: 'stable', expected: 5 })), 'bad_expected')
  assert.ok(!existsSync(CLAUDE_FILE))
})

test('Codex’s model: only one Codex offers; without a list it can’t be set at all', async () => {
  const d = dir('models')
  writeFileSync(join(d, 'config.toml'), 'model = "gpt-6.1-sol"\n')
  const expected = sha('model = "gpt-6.1-sol"\n')
  const body = { agent: 'codex', key: 'model', value: 'gpt-6-luna', expected }
  await assert.rejects(changeSetting(body, noModels, { codex: d }), (e: InstanceType<typeof EditRefused>) => e.code === 'models_unavailable')
  await assert.rejects(changeSetting(body, async () => { throw new Error('off') }, { codex: d }), (e: InstanceType<typeof EditRefused>) => e.code === 'models_unavailable')
  await assert.rejects(changeSetting({ ...body, value: 'free-text-model' }, someModels, { codex: d }), (e: InstanceType<typeof EditRefused>) => e.code === 'value_not_allowed')
  await assert.rejects(changeSetting({ ...body, value: true }, someModels, { codex: d }), (e: InstanceType<typeof EditRefused>) => e.code === 'value_not_allowed')
  // A listed one that couldn't be shown as an id couldn't be written either.
  await assert.rejects(changeSetting({ ...body, value: 'two words' }, async () => [{ value: 'two words', name: 'x' }], { codex: d }), (e: InstanceType<typeof EditRefused>) => e.code === 'value_not_allowed')
  assert.equal(readFileSync(join(d, 'config.toml'), 'utf8'), 'model = "gpt-6.1-sol"\n')
  const r = await changeSetting(body, someModels, { codex: d })
  assert.equal(r.changed, true)
  assert.equal(readFileSync(join(d, 'config.toml'), 'utf8'), 'model = "gpt-6-luna"\n')
})

test('who may: a device token only. No token, a wrong one, the admin token and the local API are refused', async () => {
  writeFileSync(CLAUDE_FILE, '{}')
  const body = { agent: 'claude', key: 'autoUpdatesChannel', value: 'stable', expected: sha('{}') }
  assert.equal((await put(body, {})).status, 401)
  assert.equal((await put(body, { authorization: 'Bearer not-a-token' })).status, 401)
  assert.equal((await put(body, { authorization: `Bearer ${adminToken}` })).status, 401)
  assert.equal((await undo({ agent: 'claude', expected: sha('{}') }, { authorization: `Bearer ${adminToken}` })).status, 401)
  for (const headers of [{}, { authorization: `Bearer ${adminToken}` }, { authorization: `Bearer ${TOKEN}` }] as Record<string, string>[]) {
    const r = await put(body, headers, LOCAL)
    assert.ok(r.status === 401 || r.status === 404, `the local API answered ${r.status}`)
    const u = await undo({ agent: 'claude', expected: sha('{}') }, headers, LOCAL)
    assert.ok(u.status === 401 || u.status === 404, `the local API answered ${u.status}`)
  }
  assert.equal(readFileSync(CLAUDE_FILE, 'utf8'), '{}')
})

// ── Byte identity ────────────────────────────────────────────────────────

test('JSON: a value that’s there changes alone, every other byte the same', () => {
  onlyThisChanged(RICH_JSON, jsonEdit(RICH_JSON, ['permissions', 'defaultMode'], 'plan'), '"acceptEdits"', '"plan"')
  onlyThisChanged(RICH_JSON, jsonEdit(RICH_JSON, ['showThinkingSummaries'], true), 'false', 'true')
  // Tabs, CRLF, no newline at the end, a compact file: each as it was.
  const tabs = '{\r\n\t"permissions":\t{\r\n\t\t"defaultMode" : "plan"\r\n\t}\r\n}'
  assert.equal(jsonEdit(tabs, ['permissions', 'defaultMode'], 'auto'), '{\r\n\t"permissions":\t{\r\n\t\t"defaultMode" : "auto"\r\n\t}\r\n}')
  assert.equal(jsonEdit('{"a":1,"autoUpdatesChannel":"latest","z":[1.0]}', ['autoUpdatesChannel'], 'stable'), '{"a":1,"autoUpdatesChannel":"stable","z":[1.0]}')
  // A string that looks like the key, earlier in the file, isn't it.
  const tricky = '{"x":"\\"autoUpdatesChannel\\": \\"latest\\"","autoUpdatesChannel":"latest"}'
  assert.equal(jsonEdit(tricky, ['autoUpdatesChannel'], 'stable'), '{"x":"\\"autoUpdatesChannel\\": \\"latest\\"","autoUpdatesChannel":"stable"}')
  // Given twice: JSON.parse takes the last, so that's the one changed, and the first is left as it is.
  assert.equal(jsonEdit('{"autoUpdatesChannel":"rc","autoUpdatesChannel":"latest"}', ['autoUpdatesChannel'], 'stable'), '{"autoUpdatesChannel":"rc","autoUpdatesChannel":"stable"}')
  // A key whose name is escaped is still that key.
  assert.equal(jsonEdit('{"autoUpdates\\u0043hannel":"latest"}', ['autoUpdatesChannel'], 'stable'), '{"autoUpdates\\u0043hannel":"stable"}')
})

test('JSON: a key that isn’t there is added after the last on its level, in that level’s own layout', () => {
  // Every byte of the old file is there, in order, around the addition; the last member here has no space before it, so neither does this.
  const tail = '"command": "x" }'
  assert.equal(jsonEdit(RICH_JSON, ['autoUpdatesChannel'], 'stable'), RICH_JSON.replace(tail, `${tail},"autoUpdatesChannel": "stable"`))

  assert.equal(jsonEdit('{\n  "a": 1\n}\n', ['autoUpdatesChannel'], 'stable'), '{\n  "a": 1,\n  "autoUpdatesChannel": "stable"\n}\n')
  assert.equal(jsonEdit('{\n\t"a": 1\n}', ['autoUpdatesChannel'], 'stable'), '{\n\t"a": 1,\n\t"autoUpdatesChannel": "stable"\n}')
  assert.equal(jsonEdit('{\r\n  "a": 1\r\n}\r\n', ['showThinkingSummaries'], true), '{\r\n  "a": 1,\r\n  "showThinkingSummaries": true\r\n}\r\n')
  assert.equal(jsonEdit('{"a":1}', ['autoUpdatesChannel'], 'stable'), '{"a":1,"autoUpdatesChannel":"stable"}')
  assert.equal(jsonEdit('{}', ['autoUpdatesChannel'], 'stable'), '{\n  "autoUpdatesChannel": "stable"\n}')
  assert.equal(jsonEdit('{ }\n', ['autoUpdatesChannel'], 'stable'), '{\n  "autoUpdatesChannel": "stable"\n}\n')
  // Into a table that's there, with others in it or empty.
  assert.equal(jsonEdit('{\n  "permissions": {\n    "allow": [\n      "Read"\n    ]\n  }\n}\n', ['permissions', 'defaultMode'], 'plan'), '{\n  "permissions": {\n    "allow": [\n      "Read"\n    ],\n    "defaultMode": "plan"\n  }\n}\n')
  assert.equal(jsonEdit('{\n  "permissions": {}\n}\n', ['permissions', 'defaultMode'], 'plan'), '{\n  "permissions": {\n    "defaultMode": "plan"\n  }\n}\n')
  assert.equal(jsonEdit('{"permissions":{}}', ['permissions', 'defaultMode'], 'plan'), '{"permissions":{"defaultMode":"plan"}}')
  // A table that has to be made.
  assert.equal(jsonEdit('{\n  "a": 1\n}\n', ['permissions', 'defaultMode'], 'plan'), '{\n  "a": 1,\n  "permissions": {\n    "defaultMode": "plan"\n  }\n}\n')
  assert.equal(jsonEdit('{"a":1}', ['permissions', 'defaultMode'], 'plan'), '{"a":1,"permissions":{"defaultMode":"plan"}}')
  assert.equal(jsonEdit('{}\n', ['permissions', 'defaultMode'], 'plan'), '{\n  "permissions": {\n    "defaultMode": "plan"\n  }\n}\n')
})

test('JSON: a key in a shape that isn’t a plain value, or a table that isn’t one, is refused', () => {
  const refuses = (t: string, path: string[]) => assert.throws(() => jsonEdit(t, path, 'plan'), (e: InstanceType<typeof EditRefused>) => e.code === 'unsupported_shape', t)
  refuses('{"permissions":"all"}', ['permissions', 'defaultMode'])
  refuses('{"permissions":["a"]}', ['permissions', 'defaultMode'])
  refuses('{"permissions":{"defaultMode":{"x":1}}}', ['permissions', 'defaultMode'])
  refuses('{"permissions":{"defaultMode":["x"]}}', ['permissions', 'defaultMode'])
  refuses('{"autoUpdatesChannel":{"x":1}}', ['autoUpdatesChannel'])
  refuses('{"autoUpdatesChannel":[1]}', ['autoUpdatesChannel'])
})

test('TOML: a value that’s there changes alone: comments, multi-line strings and tables stay byte-identical', () => {
  onlyThisChanged(RICH_TOML, tomlEdit(RICH_TOML, 'model', 'gpt-6-luna'), '"gpt-6.1-sol"', '"gpt-6-luna"')
  // A literal string, and a quoted key.
  onlyThisChanged(RICH_TOML, tomlEdit(RICH_TOML, 'model_reasoning_effort', 'low'), "'high'", '"low"')
  onlyThisChanged(RICH_TOML, tomlEdit(RICH_TOML, 'web_search', 'live'), '"cached"', '"live"')
  // CRLF, a BOM, no newline at the end, a comment right after the value.
  assert.equal(tomlEdit('﻿model = "a"\r\nb = 1\r\n', 'model', 'c'), '﻿model = "c"\r\nb = 1\r\n')
  assert.equal(tomlEdit('model = "a"', 'model', 'c'), 'model = "c"')
  assert.equal(tomlEdit('model="a"#why', 'model', 'c'), 'model="c"#why')
  // A boolean or one on a single line in triple quotes is still one plain value.
  assert.equal(tomlEdit('web_search = """cached"""\n', 'web_search', 'live'), 'web_search = "live"\n')
})

test('TOML: a key that isn’t there goes on a new line at the end of the top level, before the first table', () => {
  const v = tomlEdit(RICH_TOML, 'sandbox_mode', 'read-only')
  // After the last top-level key, not inside the multi-line string, not in a table, and the comment above the table stays with it.
  assert.equal(v, RICH_TOML.replace('"web_search" = "cached"\n', '"web_search" = "cached"\nsandbox_mode = "read-only"\n'))
  assert.equal(tomlEdit('', 'model', 'a'), 'model = "a"\n')
  assert.equal(tomlEdit('# only a comment\n', 'model', 'a'), 'model = "a"\n# only a comment\n')
  assert.equal(tomlEdit('[t]\nmodel = "no"\n', 'model', 'a'), 'model = "a"\n[t]\nmodel = "no"\n')
  assert.equal(tomlEdit('b = 1', 'model', 'a'), 'b = 1\nmodel = "a"\n')
  assert.equal(tomlEdit('b = 1\r\n[t]\r\n', 'model', 'a'), 'b = 1\r\nmodel = "a"\r\n[t]\r\n')
  assert.equal(tomlEdit('﻿[t]\n', 'model', 'a'), '﻿model = "a"\n[t]\n')
})

test('TOML: refused unless it’s a plain single-line value at the top', () => {
  const refuses = (t: string, key: string) => assert.throws(() => tomlEdit(t, key, 'x'), (e: InstanceType<typeof EditRefused>) => e.code === 'unsupported_shape', t)
  refuses('approval_policy = { granular = { rules = true } }\n', 'approval_policy')
  refuses('[approval_policy.granular]\nrules = true\n', 'approval_policy')
  refuses('[approval_policy]\n', 'approval_policy')
  refuses('[[approval_policy]]\n', 'approval_policy')
  refuses('approval_policy.granular.rules = true\n', 'approval_policy')
  refuses('model = """\nmulti\nline\n"""\n', 'model')
  refuses("model = '''\nmulti\n'''\n", 'model')
  refuses('model = ["a"]\n', 'model')
  refuses('model = [\n"a",\n]\n', 'model')
  refuses('model = 5\n', 'model')
  refuses('model = 1979-05-27\n', 'model')
  assert.throws(() => tomlEdit('model = unquoted\n', 'model', 'x'), (e: InstanceType<typeof EditRefused>) => e.code === 'file_malformed')
})

test('through the route: the file on disk is byte-identical around the edit, with hooks and env and all', async () => {
  writeFileSync(CLAUDE_FILE, RICH_JSON)
  const r = await put({ agent: 'claude', key: 'permissions.defaultMode', value: 'plan', expected: sha(RICH_JSON) })
  assert.equal(r.status, 200)
  const body = (await r.json()) as any
  assert.equal(body.changed, true)
  assert.equal(body.loosened, false)
  const on = readFileSync(CLAUDE_FILE, 'utf8')
  assert.equal(body.hash, sha(on))
  onlyThisChanged(RICH_JSON, on, '"acceptEdits"', '"plan"')

  writeFileSync(CODEX_FILE, RICH_TOML)
  const c = await put({ agent: 'codex', key: 'web_search', value: 'live', expected: sha(RICH_TOML) })
  assert.equal(c.status, 200)
  onlyThisChanged(RICH_TOML, readFileSync(CODEX_FILE, 'utf8'), '"cached"', '"live"')
})

// ── The file itself ──────────────────────────────────────────────────────

test('a file changed since the phone read it: 409, and nothing is written', async () => {
  writeFileSync(CLAUDE_FILE, '{"autoUpdatesChannel":"latest"}')
  const r = await put({ agent: 'claude', key: 'autoUpdatesChannel', value: 'stable', expected: sha('something else') })
  assert.equal(r.status, 409)
  assert.equal(await code(r), 'file_changed')
  assert.equal(readFileSync(CLAUDE_FILE, 'utf8'), '{"autoUpdatesChannel":"latest"}')
  assert.deepEqual(backups(), [])
  // A file that has since appeared, when the phone thought there was none.
  assert.equal(await code(await put({ agent: 'claude', key: 'autoUpdatesChannel', value: 'stable', expected: null })), 'file_changed')
  // One that has since gone, when the phone thought there was one.
  rmSync(CLAUDE_FILE)
  assert.equal(await code(await put({ agent: 'claude', key: 'autoUpdatesChannel', value: 'stable', expected: sha('x') })), 'file_changed')
  assert.ok(!existsSync(CLAUDE_FILE))
})

test('a file changed mid-way, after it was read and just before the rename: theirs stands, with no temp file or backup left', async () => {
  const d = dir('midway')
  const file = join(d, 'settings.json')
  writeFileSync(file, '{"autoUpdatesChannel":"latest"}')
  await assert.rejects(
    changeSetting({ agent: 'claude', key: 'autoUpdatesChannel', value: 'stable', expected: sha('{"autoUpdatesChannel":"latest"}') }, noModels, { claude: d }, { beforeRename: () => writeFileSync(file, '{"theirs":true}') }),
    (e: InstanceType<typeof EditRefused>) => e.code === 'file_changed',
  )
  assert.equal(readFileSync(file, 'utf8'), '{"theirs":true}')
  assert.deepEqual(readdirSync(d), ['settings.json'])
  assert.deepEqual(backups(), [])
})

test('a read-only file is left alone, though a rename could replace it', { skip: process.getuid?.() === 0 && 'root writes anything' }, async () => {
  writeFileSync(CLAUDE_FILE, '{}')
  chmodSync(CLAUDE_FILE, 0o400)
  try {
    const r = await put({ agent: 'claude', key: 'autoUpdatesChannel', value: 'stable', expected: sha('{}') })
    assert.equal(r.status, 409)
    assert.equal(await code(r), 'file_read_only')
    assert.equal(readFileSync(CLAUDE_FILE, 'utf8'), '{}')
    assert.equal(statSync(CLAUDE_FILE).mode & 0o777, 0o400)
    assert.deepEqual(backups(), [])
    const body = await read()
    assert.equal(body.claude.whyNot, 'read_only')
    assert.deepEqual(body.claude.editable, [])
  } finally {
    chmodSync(CLAUDE_FILE, 0o600)
  }
})

test('a folder it can’t write in fails with the file untouched, and says nothing changed', { skip: process.getuid?.() === 0 && 'root writes anything' }, async () => {
  const d = dir('ro-folder')
  const file = join(d, 'settings.json')
  writeFileSync(file, '{}')
  chmodSync(d, 0o500)
  try {
    await assert.rejects(changeSetting({ agent: 'claude', key: 'autoUpdatesChannel', value: 'stable', expected: sha('{}') }, noModels, { claude: d }), (e: InstanceType<typeof EditRefused>) => e.code === 'write_failed' && e.status === 500 && !e.message.includes(d))
    assert.equal(readFileSync(file, 'utf8'), '{}')
    assert.deepEqual(readdirSync(d), ['settings.json'])
  } finally {
    chmodSync(d, 0o700)
  }
})

test('a malformed file is never written, and its text is never quoted', async () => {
  const secret = 'sk-ant-api03-PLANTEDQuotedAbcdefghijklmnop0123456789'
  const bad = `{"env":{"KEY":"${secret}"`
  writeFileSync(CLAUDE_FILE, bad)
  const r = await put({ agent: 'claude', key: 'autoUpdatesChannel', value: 'stable', expected: sha(bad) })
  assert.equal(r.status, 409)
  const text = await r.text()
  assert.ok(text.includes('file_malformed') && !text.includes('sk-ant'), text)
  assert.equal(readFileSync(CLAUDE_FILE, 'utf8'), bad)

  for (const notAnObject of ['[]', '"x"', 'null', '5']) {
    writeFileSync(CLAUDE_FILE, notAnObject)
    assert.equal(await code(await put({ agent: 'claude', key: 'autoUpdatesChannel', value: 'stable', expected: sha(notAnObject) })), 'file_malformed', notAnObject)
    assert.equal(readFileSync(CLAUDE_FILE, 'utf8'), notAnObject)
  }

  const toml = `model = unquoted-${secret}\n`
  writeFileSync(CODEX_FILE, toml)
  const c = await put({ agent: 'codex', key: 'web_search', value: 'live', expected: sha(toml) })
  assert.equal(c.status, 409)
  const words = await c.text()
  assert.ok(words.includes('file_malformed') && !words.includes('sk-ant'), words)
  assert.equal(readFileSync(CODEX_FILE, 'utf8'), toml)
  assert.deepEqual(backups(), [])
  const body = await read()
  assert.deepEqual(body.codex.editable, [])
})

test('bytes that aren’t UTF-8 can’t come back as they were, so they’re not edited', async () => {
  const bytes = Buffer.concat([Buffer.from('{"x":"'), Buffer.from([0xff, 0xfe]), Buffer.from('"}')])
  writeFileSync(CLAUDE_FILE, bytes)
  assert.equal(await code(await put({ agent: 'claude', key: 'autoUpdatesChannel', value: 'stable', expected: sha(bytes) })), 'unsupported_shape')
  assert.ok(readFileSync(CLAUDE_FILE).equals(bytes))
})

test('a link is refused, not followed: the link and its target stay as they are', async () => {
  const target = join(dir('link-target'), 'real.json')
  writeFileSync(target, '{"autoUpdatesChannel":"latest"}')
  symlinkSync(target, CLAUDE_FILE)
  const r = await put({ agent: 'claude', key: 'autoUpdatesChannel', value: 'stable', expected: sha('{"autoUpdatesChannel":"latest"}') })
  assert.equal(r.status, 409)
  assert.equal(await code(r), 'file_is_link')
  assert.ok(lstatSync(CLAUDE_FILE).isSymbolicLink())
  assert.equal(readFileSync(target, 'utf8'), '{"autoUpdatesChannel":"latest"}')
  const body = await read()
  // It still reads, and says why it can't be changed here.
  assert.equal(body.claude.state, 'ok')
  assert.equal(body.claude.whyNot, 'link')
  assert.deepEqual(body.claude.editable, [])
})

test('the write keeps the file’s mode, and replaces it whole', async () => {
  writeFileSync(CLAUDE_FILE, '{}')
  chmodSync(CLAUDE_FILE, 0o640)
  const inode = statSync(CLAUDE_FILE).ino
  const r = await put({ agent: 'claude', key: 'autoUpdatesChannel', value: 'stable', expected: sha('{}') })
  assert.equal(r.status, 200)
  const st = statSync(CLAUDE_FILE)
  assert.equal(st.mode & 0o777, 0o640)
  assert.notEqual(st.ino, inode)
  assert.equal(st.uid, process.getuid?.())
  assert.deepEqual(readdirSync(CLAUDE), ['settings.json'])
})

test('a file that isn’t there yet is made with the one key, mode 0600; a folder that isn’t there is refused', async () => {
  const r = await put({ agent: 'claude', key: 'permissions.defaultMode', value: 'plan', expected: null })
  assert.equal(r.status, 200)
  assert.equal(readFileSync(CLAUDE_FILE, 'utf8'), '{\n  "permissions": {\n    "defaultMode": "plan"\n  }\n}\n')
  assert.equal(statSync(CLAUDE_FILE).mode & 0o777, 0o600)
  assert.deepEqual(backups(), [])

  const c = await put({ agent: 'codex', key: 'sandbox_mode', value: 'read-only', expected: null })
  assert.equal(c.status, 200)
  assert.equal(readFileSync(CODEX_FILE, 'utf8'), 'sandbox_mode = "read-only"\n')
  assert.equal(statSync(CODEX_FILE).mode & 0o777, 0o600)

  const gone = join(dir('no-folder'), 'not-here')
  await assert.rejects(changeSetting({ agent: 'claude', key: 'autoUpdatesChannel', value: 'stable', expected: null }, noModels, { claude: gone }), (e: InstanceType<typeof EditRefused>) => e.code === 'no_folder')
  assert.ok(!existsSync(gone))
  await assert.rejects(changeSetting({ agent: 'codex', key: 'web_search', value: 'live', expected: null }, noModels, { codex: gone }), (e: InstanceType<typeof EditRefused>) => e.code === 'no_folder')
  assert.ok(!existsSync(gone))
})

test('the same value again changes nothing: no write, no backup, no broadcast’s worth of change', async () => {
  writeFileSync(CLAUDE_FILE, '{"autoUpdatesChannel":"stable"}')
  const inode = statSync(CLAUDE_FILE).ino
  const r = await put({ agent: 'claude', key: 'autoUpdatesChannel', value: 'stable', expected: sha('{"autoUpdatesChannel":"stable"}') })
  assert.equal(r.status, 200)
  assert.equal(((await r.json()) as any).changed, false)
  assert.equal(statSync(CLAUDE_FILE).ino, inode)
  assert.deepEqual(backups(), [])
  // It's still checked against what the phone read: the screen is out of date, whatever the value.
  assert.equal(await code(await put({ agent: 'claude', key: 'autoUpdatesChannel', value: 'stable', expected: sha('older') })), 'file_changed')
})

// ── Backups ──────────────────────────────────────────────────────────────

test('one backup per change: the old bytes, mode 0600, in the support folder, never next to the file', async () => {
  writeFileSync(CLAUDE_FILE, RICH_JSON)
  const r = await put({ agent: 'claude', key: 'permissions.defaultMode', value: 'plan', expected: sha(RICH_JSON) })
  assert.equal(r.status, 200)
  const [one, ...more] = backups()
  assert.deepEqual(more, [])
  assert.match(one!, /^claude-\d+$/)
  const file = join(process.env.HYPERNOVA_SUPPORT!, 'settings-backups', one!)
  assert.equal(readFileSync(file, 'utf8'), RICH_JSON)
  assert.equal(statSync(file).mode & 0o777, 0o600)
  assert.equal(statSync(backupDir()).mode & 0o777, 0o700)
  assert.deepEqual(readdirSync(CLAUDE), ['settings.json'])
})

test('the backups are capped per agent, the oldest going first', async () => {
  const d = dir('cap')
  const file = join(d, 'config.toml')
  writeFileSync(file, '')
  writeFileSync(join(d, 'settings.json'), '{}')
  await changeSetting({ agent: 'claude', key: 'autoUpdatesChannel', value: 'stable', expected: sha('{}') }, noModels, { claude: d })
  const first = backups().find((n) => n.startsWith('claude-'))
  assert.ok(first)
  let cur = ''
  for (let i = 0; i < BACKUPS_KEPT + 3; i++) {
    const value = i % 2 ? 'live' : 'cached'
    const r = await changeSetting({ agent: 'codex', key: 'web_search', value, expected: sha(cur) }, noModels, { codex: d })
    assert.equal(r.changed, true)
    cur = readFileSync(file, 'utf8')
  }
  const codexOnes = backups().filter((n) => n.startsWith('codex-'))
  assert.equal(codexOnes.length, BACKUPS_KEPT)
  // Another agent's aren't counted against it.
  assert.ok(backups().includes(first!))
})

// ── Undo ─────────────────────────────────────────────────────────────────

test('undo restores the last backup’s bytes, one step only, and says what it was', async () => {
  writeFileSync(CLAUDE_FILE, RICH_JSON)
  chmodSync(CLAUDE_FILE, 0o640)
  const r = (await (await put({ agent: 'claude', key: 'permissions.defaultMode', value: 'plan', expected: sha(RICH_JSON) })).json()) as any
  assert.deepEqual(r.undo, { key: 'permissions.defaultMode', to: 'acceptEdits', loosens: true })
  const seen = await read()
  assert.deepEqual(seen.claude.undo, r.undo)
  const u = await undo({ agent: 'claude', expected: r.hash })
  assert.equal(u.status, 200)
  const body = (await u.json()) as any
  assert.equal(body.loosened, true)
  assert.equal(body.hash, sha(RICH_JSON))
  assert.equal(readFileSync(CLAUDE_FILE, 'utf8'), RICH_JSON)
  assert.equal(statSync(CLAUDE_FILE).mode & 0o777, 0o640)
  const again = await undo({ agent: 'claude', expected: sha(RICH_JSON) })
  assert.equal(again.status, 409)
  assert.equal(await code(again), 'nothing_to_undo')
  assert.equal((await read()).claude.undo, null)
})

test('undo: a file changed since that change is left alone, with a 409', async () => {
  writeFileSync(CLAUDE_FILE, '{"autoUpdatesChannel":"latest"}')
  const r = (await (await put({ agent: 'claude', key: 'autoUpdatesChannel', value: 'stable', expected: sha('{"autoUpdatesChannel":"latest"}') })).json()) as any
  // Claude Code writes to it: the phone's hash is now out of date, and the offer goes.
  writeFileSync(CLAUDE_FILE, '{"autoUpdatesChannel":"stable","theirs":1}')
  assert.equal((await read()).claude.undo, null)
  const u = await undo({ agent: 'claude', expected: r.hash })
  assert.equal(u.status, 409)
  assert.equal(await code(u), 'file_changed')
  // The file's own hash doesn't help: it isn't what the change wrote.
  assert.equal(await code(await undo({ agent: 'claude', expected: sha('{"autoUpdatesChannel":"stable","theirs":1}') })), 'file_changed')
  assert.equal(readFileSync(CLAUDE_FILE, 'utf8'), '{"autoUpdatesChannel":"stable","theirs":1}')
  // Nor does an out-of-date hash on a file that still matches.
  writeFileSync(CLAUDE_FILE, readFileSync(CLAUDE_FILE, 'utf8').replace(',"theirs":1', ''))
  assert.equal(await code(await undo({ agent: 'claude', expected: 'stale' })), 'file_changed')
  assert.equal(await code(await undo({ agent: 'claude' })), 'bad_expected')
})

test('undo of a file the change made removes it again', async () => {
  const r = (await (await put({ agent: 'codex', key: 'web_search', value: 'live', expected: null })).json()) as any
  assert.deepEqual(r.undo, { key: 'web_search', to: null, loosens: false })
  const u = await undo({ agent: 'codex', expected: r.hash })
  assert.equal(u.status, 200)
  assert.equal(((await u.json()) as any).hash, null)
  assert.ok(!existsSync(CODEX_FILE))
})

test('undo never puts a permissive value back: a bypass moved off stays off, also for a word it doesn’t know', async () => {
  for (const [agent, file, key, oldWord, to] of [
    ['claude', CLAUDE_FILE, 'permissions.defaultMode', 'bypassPermissions', 'plan'],
    ['claude', CLAUDE_FILE, 'permissions.defaultMode', 'someNewOpenMode', 'plan'],
    ['claude', CLAUDE_FILE, 'autoUpdatesChannel', 'rc', 'stable'],
    ['codex', CODEX_FILE, 'sandbox_mode', 'danger-full-access', 'read-only'],
    ['codex', CODEX_FILE, 'approval_policy', 'never', 'on-request'],
    ['codex', CODEX_FILE, 'approval_policy', 'untrusted', 'on-request'],
  ] as const) {
    const oldText = agent === 'codex' ? `${key} = "${oldWord}"\n` : key === 'autoUpdatesChannel' ? `{"autoUpdatesChannel":"${oldWord}"}` : `{"permissions":{"defaultMode":"${oldWord}"}}`
    writeFileSync(file, oldText)
    const res = await put({ agent, key, value: to, expected: sha(oldText) })
    assert.equal(res.status, 200, oldText)
    const r = (await res.json()) as any
    assert.equal(r.changed, true)
    assert.equal(r.undo, null, oldText)
    assert.equal((await read())[agent].undo, null, oldText)
    const u = await undo({ agent, expected: r.hash })
    assert.equal(u.status, 409, oldText)
    assert.equal(await code(u), 'undo_refused')
    assert.ok(!readFileSync(file, 'utf8').includes(`"${oldWord}"`), oldText)
    // The backup is on the server, for doing it by hand.
    assert.ok(backups().some((n) => readFileSync(join(backupDir(), n), 'utf8') === oldText))
  }
})

test('undo does put `manual` back: it’s `default` by another name, and a change is one step whatever it was', async () => {
  const text = '{"permissions":{"defaultMode":"manual"}}'
  writeFileSync(CLAUDE_FILE, text)
  const first = (await (await put({ agent: 'claude', key: 'permissions.defaultMode', value: 'plan', expected: sha(text) })).json()) as any
  assert.deepEqual(first.undo, { key: 'permissions.defaultMode', to: 'manual', loosens: true })
  const second = (await (await put({ agent: 'claude', key: 'permissions.defaultMode', value: 'default', expected: first.hash })).json()) as any
  // The last change, not the one before it.
  assert.equal(second.undo.to, 'plan')
  assert.equal((await undo({ agent: 'claude', expected: second.hash })).status, 200)
  assert.equal(JSON.parse(readFileSync(CLAUDE_FILE, 'utf8')).permissions.defaultMode, 'plan')
  assert.equal(await code(await undo({ agent: 'claude', expected: first.hash })), 'nothing_to_undo')
})

test('moving off a permissive value is allowed, doesn’t count as loosening, and the rest stays', async () => {
  const text = '{\n  "hooks": { "Stop": [] },\n  "permissions": { "defaultMode": "bypassPermissions", "allow": ["Read"] }\n}\n'
  writeFileSync(CLAUDE_FILE, text)
  const seen = await read()
  assert.deepEqual(seen.claude.values.find((v: any) => v.key === 'permissions.defaultMode'), { key: 'permissions.defaultMode', state: 'set', value: 'bypassPermissions' })
  const mode = seen.claude.editable.find((e: any) => e.key === 'permissions.defaultMode')
  assert.ok(!mode.choices.some((c: any) => c.value === 'bypassPermissions'))
  assert.ok(mode.choices.every((c: any) => c.loosens === false))
  const r = (await (await put({ agent: 'claude', key: 'permissions.defaultMode', value: 'auto', expected: seen.claude.hash })).json()) as any
  assert.equal(r.loosened, false)
  assert.equal(readFileSync(CLAUDE_FILE, 'utf8'), text.replace('"bypassPermissions"', '"auto"'))
  // And going back up is refused like anything else.
  const up = await put({ agent: 'claude', key: 'permissions.defaultMode', value: 'bypassPermissions', expected: r.hash })
  assert.equal(up.status, 403)
})

// ── What the phone is told ───────────────────────────────────────────────

test('the reply: each file’s hash, what can be picked, and what would loosen', async () => {
  const json = '{"permissions":{"defaultMode":"acceptEdits"},"showThinkingSummaries":true,"autoUpdatesChannel":"rc"}'
  const toml = 'sandbox_mode = "workspace-write"\napproval_policy = "never"\n'
  writeFileSync(CLAUDE_FILE, json)
  writeFileSync(CODEX_FILE, toml)
  const r = await read()
  assert.equal(r.claude.hash, sha(json))
  assert.equal(r.codex.hash, sha(toml))
  assert.equal(r.claude.whyNot, null)
  assert.equal(r.claude.undo, null)
  assert.deepEqual(r.claude.editable, [
    {
      key: 'permissions.defaultMode',
      choices: [
        { value: 'plan', loosens: false },
        { value: 'default', loosens: false },
        { value: 'dontAsk', loosens: false },
        { value: 'acceptEdits', loosens: false },
        { value: 'auto', loosens: true },
      ],
    },
    { key: 'showThinkingSummaries', choices: [{ value: true, loosens: false }, { value: false, loosens: false }] },
    { key: 'autoUpdatesChannel', choices: [{ value: 'latest', loosens: false }, { value: 'stable', loosens: false }] },
  ])
  // No list of Codex's models here: its Model row stays as shown.
  assert.deepEqual(r.codex.editable.map((e: any) => e.key), ['model_reasoning_effort', 'approval_policy', 'sandbox_mode', 'web_search'])
  assert.deepEqual(r.codex.editable.find((e: any) => e.key === 'approval_policy').choices, [{ value: 'on-request', loosens: false }])
  assert.deepEqual(r.codex.editable.find((e: any) => e.key === 'sandbox_mode').choices, [{ value: 'read-only', loosens: false }, { value: 'workspace-write', loosens: false }])
})

test('with Codex’s models known, Model offers them, named; one that can’t be shown as an id isn’t offered', () => {
  const d = dir('with-models')
  writeFileSync(join(d, 'config.toml'), 'model = "gpt-6.1-sol"\n')
  const r = settingsReply([{ value: 'gpt-6.1-sol', name: 'GPT 6.1 Sol' }, { value: 'two words', name: 'Bad' }], { codex: d, claude: dir('with-models-claude') })
  assert.deepEqual(r.codex.editable.find((e) => e.key === 'model')?.choices, [{ value: 'gpt-6.1-sol', label: 'GPT 6.1 Sol', loosens: false }])
})

test('a key in a shape that can’t be edited in place isn’t offered', () => {
  const d = dir('granular')
  writeFileSync(join(d, 'config.toml'), 'approval_policy = { granular = { rules = true } }\n[sandbox_mode]\nx = 1\n')
  const r = settingsReply(null, { codex: d, claude: dir('granular-claude') })
  assert.deepEqual(r.codex.editable.map((e) => e.key), ['model_reasoning_effort', 'web_search'])
  const c = dir('nested')
  writeFileSync(join(c, 'settings.json'), '{"permissions":"all","autoUpdatesChannel":{"x":1}}')
  assert.deepEqual(settingsReply(null, { claude: c, codex: dir('nested-codex') }).claude.editable.map((e) => e.key), ['showThinkingSummaries'])
})

test('a missing file: no hash, and what can be set when it’s made; no folder: nothing', () => {
  const r = settingsReply(null, { claude: dir('fresh-claude'), codex: dir('fresh-codex') })
  assert.equal(r.claude.state, 'missing')
  assert.equal(r.claude.hash, null)
  assert.deepEqual(r.claude.values, [])
  assert.ok(r.claude.editable.length === 3)
  // Not set means default: going looser than that loosens.
  assert.deepEqual(r.claude.editable[0]!.choices.filter((c) => c.loosens).map((c) => c.value), ['acceptEdits', 'auto'])
  const gone = settingsReply(null, { claude: join(scratch, 'nope-claude'), codex: join(scratch, 'nope-codex') })
  assert.equal(gone.claude.whyNot, 'no_folder')
  assert.deepEqual(gone.claude.editable, [])
  assert.equal(gone.codex.whyNot, 'no_folder')
})

// ── The log ──────────────────────────────────────────────────────────────

test('the log: one line per change with the agent, key and old and new values; never a value it couldn’t show', async () => {
  const logFile = join(process.env.HYPERNOVA_LOGS!, 'daemon.log')
  const lines = () => (existsSync(logFile) ? readFileSync(logFile, 'utf8').split('\n').filter((l) => l.includes('agent settings:')) : [])
  const before = lines().length
  const secret = 'sk-ant-api03-PLANTEDLogAbcdefghijklmnop0123456789'
  const text = `{"env":{"KEY":"${secret}"},"permissions":{"defaultMode":"acceptEdits"},"autoUpdatesChannel":"${secret}"}`
  writeFileSync(CLAUDE_FILE, text)
  const r = (await (await put({ agent: 'claude', key: 'permissions.defaultMode', value: 'auto', expected: sha(text) })).json()) as any
  const after = lines().slice(before)
  assert.equal(after.length, 1)
  assert.match(after[0]!, /agent settings: claude permissions\.defaultMode acceptEdits -> auto \(loosens\)$/)

  const s = await put({ agent: 'claude', key: 'autoUpdatesChannel', value: 'stable', expected: r.hash })
  assert.equal(s.status, 200)
  const more = lines().slice(before)
  assert.equal(more.length, 2)
  assert.match(more[1]!, /agent settings: claude autoUpdatesChannel \(unrecognised\) -> stable$/)
  assert.ok(!readFileSync(logFile, 'utf8').includes('sk-ant'))
  assert.ok(!readFileSync(logFile, 'utf8').includes('PLANTED'))
})
