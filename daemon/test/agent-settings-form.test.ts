// The form of every setting (agent-settings-form.ts): each class of key, proven on the server. Every file here is a scratch one.
import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, realpathSync, rmSync, symlinkSync, writeFileSync, chmodSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createHash } from 'node:crypto'

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-settings-form-')))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.HOME = join(scratch, 'home')
mkdirSync(process.env.HOME, { recursive: true })
mkdirSync(process.env.HYPERNOVA_LOGS, { recursive: true })

const { changeFormSetting, plainDescription, schemaOf, settingsForm, shapeOf, titleOf, undoFormChange } = await import('../src/agent-settings-form.ts')
const { CLAUDE_KEYS, CLAUDE_NESTED, CODEX_KEYS, GROUPS } = await import('../src/agent-settings-classes.ts')
const { IGNORED, problem, UNDERSTOOD } = await import('../src/json-schema-lite.ts')
const { PLACEHOLDER } = await import('../src/agent-raw.ts')
const { PLAIN_KEYS } = await import('../src/agent-raw.ts')
const { backupDir, changeSetting, undoChange } = await import('../src/agent-settings-edit.ts')

const claude = join(scratch, 'claude')
const codex = join(scratch, 'codex')
const dirs = { claude, codex, claudeConfig: join(scratch, 'nothing.json') }
const claudeFile = join(claude, 'settings.json')
const codexFile = join(codex, 'config.toml')
const noModels = async () => null
const models = async () => [{ value: 'gpt-x', name: 'GPT X' }]
const sha = (s: string) => createHash('sha256').update(s).digest('hex')

beforeEach(() => {
  rmSync(claude, { recursive: true, force: true })
  rmSync(codex, { recursive: true, force: true })
  rmSync(process.env.HYPERNOVA_SUPPORT!, { recursive: true, force: true })
  mkdirSync(claude, { recursive: true })
  mkdirSync(codex, { recursive: true })
  mkdirSync(process.env.HYPERNOVA_SUPPORT!, { recursive: true })
})

const put = (text: string, file = claudeFile) => {
  writeFileSync(file, text)
  return sha(text)
}
const set = (key: string, value: unknown, expected: string | null, confirm?: boolean, agent = 'claude') => changeFormSetting({ agent, key, value, expected, ...(confirm ? { confirm } : {}) }, models, dirs)
const refusal = async (p: Promise<unknown>, code: string) => assert.rejects(p, (e: any) => {
  assert.equal(e.code, code, `refused as ${e.code}: ${e.message}`)
  return true
})
const row = async (key: string, agent: 'claude' | 'codex' = 'claude', use: () => Promise<{ value: string; name: string }[] | null> = noModels): Promise<any> => (await settingsForm(agent, use, dirs)).rows.find((r: any) => r.key === key)

// ── The table: nothing in the shipped schema goes unlooked at ────────────

test('every key of the shipped Claude Code schema is classified, and nothing else', () => {
  const schema = schemaOf('claude').properties
  assert.equal(Object.keys(schema).length, 142, 'the schema this was reviewed against has 142: a refresh that changes this needs a look at the new keys')
  assert.deepEqual(Object.keys(schema).filter((k) => !Object.hasOwn(CLAUDE_KEYS, k)), [], 'add each to agent-settings-classes.ts, after reading what it does')
  assert.deepEqual(Object.keys(CLAUDE_KEYS).filter((k) => !Object.hasOwn(schema, k)), [], 'in the table, gone from the schema')
  const nested = Object.keys(schema.permissions.properties).map((k) => `permissions.${k}`)
  assert.deepEqual(nested.filter((k) => !Object.hasOwn(CLAUDE_NESTED, k)), [])
  assert.deepEqual(Object.keys(CLAUDE_NESTED).filter((k) => !nested.includes(k)), [])
})

test('every key of the shipped Codex schema is classified, and nothing else', () => {
  const schema = schemaOf('codex').properties
  assert.deepEqual(Object.keys(schema).filter((k) => !Object.hasOwn(CODEX_KEYS, k)), [])
  assert.deepEqual(Object.keys(CODEX_KEYS).filter((k) => !Object.hasOwn(schema, k)), [])
})

test('the helper commands and what runs by name are never plain', () => {
  for (const k of ['hooks', 'apiKeyHelper', 'awsAuthRefresh', 'awsCredentialExport', 'gcpAuthRefresh', 'otelHeadersHelper', 'statusLine', 'subagentStatusLine', 'fileSuggestion', 'processWrapper', 'enableAllProjectMcpServers', 'disableAllHooks', 'disableSkillShellExecution', 'autoInstallIdeExtension', 'disableClaudeAiConnectors']) assert.equal(CLAUDE_KEYS[k].take, 'runs', k)
  for (const k of ['apiKeyHelper', 'awsAuthRefresh', 'awsCredentialExport', 'gcpAuthRefresh', 'otelHeadersHelper', 'processWrapper']) assert.equal(CLAUDE_KEYS[k].mask, true, k)
  for (const k of ['sandbox', 'autoMode', 'skipWebFetchPreflight', 'useAutoModeDuringPlan']) assert.equal(CLAUDE_KEYS[k].take, 'loosens', k)
})

test('what the JSON tab calls plain the form doesn’t take further: only keys it reviewed are plain, and each group has words', () => {
  for (const e of [...Object.values(CLAUDE_KEYS), ...Object.values(CLAUDE_NESTED), ...Object.values(CODEX_KEYS)]) assert.ok(GROUPS[e.group], e.group)
  // Plain here and asking in the JSON tab is fine (the tab's list is shorter and stays as it is). The other way round would be a surprise.
  for (const [k, e] of Object.entries(CLAUDE_KEYS)) if (PLAIN_KEYS.has(k) && !e.managed) assert.equal(e.take, 'plain', `${k} is plain in the JSON tab`)
})

test('the validator understands every keyword the shipped schemas use', () => {
  const seen = new Set<string>()
  const walk = (s: any) => {
    if (Array.isArray(s)) return s.forEach(walk)
    if (!s || typeof s !== 'object') return
    for (const [k, v] of Object.entries(s) as [string, any][]) {
      seen.add(k)
      if (k === 'properties' || k === '$defs' || k === 'definitions') Object.values(v).forEach(walk)
      else if (!['examples', 'default', 'enum', 'const', 'description', 'required', 'title'].includes(k)) walk(v)
    }
  }
  walk(schemaOf('claude'))
  walk(schemaOf('codex'))
  assert.deepEqual([...seen].filter((k) => !UNDERSTOOD.has(k) && !IGNORED.has(k)), [], 'teach json-schema-lite.ts or say why it decides nothing')
})

test('the validator: each keyword, and it never quotes the value', () => {
  const root = { $defs: { a: { type: 'string', minLength: 2 } } }
  const check = (s: object, v: unknown) => problem(root, s, v, 'k')
  assert.equal(check({ type: 'boolean' }, true), null)
  assert.match(check({ type: 'boolean' }, 'sk-secret')!, /boolean/)
  assert.ok(!check({ type: 'boolean' }, 'sk-secret')!.includes('sk-secret'))
  assert.equal(check({ type: ['string', 'null'] }, null), null)
  assert.ok(check({ type: 'integer' }, 1.5))
  assert.ok(check({ enum: ['a'] }, 'b'))
  assert.ok(check({ const: 'a' }, 'b'))
  assert.ok(check({ minimum: 1 }, 0))
  assert.ok(check({ maximum: 1 }, 2))
  assert.ok(check({ exclusiveMinimum: 0 }, 0))
  assert.ok(check({ maxLength: 1 }, 'ab'))
  assert.ok(check({ pattern: '^a$' }, 'b'))
  assert.ok(check({ maxItems: 1 }, [1, 2]))
  assert.ok(check({ minItems: 1 }, []))
  assert.ok(check({ uniqueItems: true }, [1, 1]))
  assert.ok(check({ items: { type: 'string' } }, [1]))
  assert.ok(check({ required: ['a'] }, {}))
  assert.ok(check({ additionalProperties: false, properties: { a: {} } }, { b: 1 }))
  assert.ok(check({ additionalProperties: { type: 'string' } }, { b: 1 }))
  assert.ok(check({ propertyNames: { pattern: '^..$' } }, { b: 1 }))
  assert.ok(check({ $ref: '#/$defs/a' }, 'x'))
  assert.equal(check({ $ref: '#/$defs/a' }, 'xy'), null)
  assert.ok(check({ anyOf: [{ type: 'string' }, { type: 'boolean' }] }, 1))
  assert.ok(check({ oneOf: [{ type: 'number' }, { type: 'integer' }] }, 1), 'both match')
  assert.ok(check({ allOf: [{ type: 'string' }] }, 1))
  assert.ok(check({ not: { type: 'string' } }, 'x'))
})

// ── The catalog ──────────────────────────────────────────────────────────

test('a row’s control comes from the schema alone: a new key needs no code', () => {
  const root = {}
  assert.equal(shapeOf(root, { type: 'boolean' }).control, 'switch')
  assert.deepEqual(shapeOf(root, { type: 'string', enum: ['a', 'b'] }), { control: 'pick', of: ['a', 'b'] })
  assert.deepEqual(shapeOf(root, { anyOf: [{ type: 'string', enum: ['a'] }, { type: 'string', pattern: '^x' }] }), { control: 'pick', of: ['a'] })
  assert.deepEqual(shapeOf(root, { type: 'string', minLength: 1 }), { control: 'text', minLength: 1 })
  assert.deepEqual(shapeOf(root, { type: 'integer', minimum: 1 }), { control: 'number', integer: true, min: 1 })
  assert.deepEqual(shapeOf(root, { type: 'number', exclusiveMinimum: 0, maximum: 1 }), { control: 'number', max: 1, above: 0 })
  assert.deepEqual(shapeOf(root, { type: 'array', maxItems: 3, items: { type: 'string', minLength: 1 } }), { control: 'list', maxItems: 3, itemMinLength: 1 })
  assert.equal(shapeOf(root, { type: 'array', items: { type: 'object' } }).control, 'json')
  assert.equal(shapeOf(root, { type: 'object' }).control, 'json')
  assert.equal(shapeOf(root, { oneOf: [{ type: 'string' }, { type: 'array' }] }).control, 'json')
})

test('the whole catalog from an empty file: unset, its default said, enterprise keys hidden, permissions taken apart', async () => {
  const reply = await settingsForm('claude', noModels, dirs)
  assert.equal(reply.state, 'missing')
  assert.equal(reply.hash, null)
  assert.equal(reply.source.licence, 'Apache-2.0')
  const keys = reply.rows.map((r: any) => r.key)
  assert.equal(new Set(keys).size, keys.length)
  assert.ok(reply.rows.every((r: any) => r.state === 'unset' && r.value === undefined))
  assert.ok(!keys.includes('allowManagedHooksOnly'), 'enterprise-only and not in the file: hidden')
  assert.ok(!keys.includes('permissions'))
  for (const k of ['permissions.defaultMode', 'permissions.additionalDirectories', 'permissions.allow', 'modelSettings', 'env']) assert.ok(keys.includes(k), k)
  const compact: any = reply.rows.find((r: any) => r.key === 'autoCompactEnabled')
  assert.equal(compact.default, true)
  assert.equal(compact.control, 'switch')
  assert.equal(compact.asks, null)
  assert.equal(compact.title, 'Auto compact')
  const size = JSON.stringify(reply).length
  assert.ok(size < 120_000, `the catalog is ${size} bytes`)
})

test('what has its own screen points there, and sends nothing of the file', async () => {
  put(JSON.stringify({ env: { ANTHROPIC_API_KEY: 'sk-ant-api03-planted' }, permissions: { allow: ['Bash(*)'] }, enabledPlugins: { 'a@b': true }, enabledMcpjsonServers: ['m'], modelSettings: { 'claude-x': { effortLevel: 'low' } } }))
  const reply = await settingsForm('claude', noModels, dirs)
  assert.ok(!JSON.stringify(reply).includes('sk-ant-api03-planted'))
  assert.ok(!JSON.stringify(reply).includes('Bash(*)'))
  const screens = Object.fromEntries(reply.rows.filter((r: any) => r.control === 'link').map((r: any) => [r.key, r.screen]))
  assert.equal(screens.env, 'env')
  assert.equal(screens['permissions.allow'], 'rules')
  assert.equal(screens['permissions.ask'], 'rules')
  assert.equal(screens['permissions.deny'], 'rules')
  assert.equal(screens.enabledPlugins, 'plugins')
  assert.equal(screens.enabledMcpjsonServers, 'mcp')
  assert.equal(screens.modelSettings, 'effort')
  assert.equal((reply.rows.find((r: any) => r.key === 'env') as any).value, undefined)
})

test('a description loses its links out, and a title reads as words', () => {
  assert.equal(plainDescription('Does a thing. See https://code.claude.com/docs/en/a#b'), 'Does a thing.')
  assert.equal(plainDescription('Tool config. See https://a.example/x and https://b.example/y See https://c.example/z for full list of tools available to Claude.'), 'Tool config.')
  assert.equal(titleOf('autoCompactEnabled'), 'Auto compact')
  assert.equal(titleOf('permissions.additionalDirectories'), 'Additional directories')
  assert.equal(titleOf('model_verbosity'), 'Model verbosity')
  assert.equal(titleOf('$schema'), 'Schema')
})

// ── Plain ────────────────────────────────────────────────────────────────

test('plain: asks nothing, and only that value’s bytes change, odd spacing and escapes and all', async () => {
  const text = '{\n    "z" :  1.0,\n  "theme": "dark",\n  "unicode": "\\u00e9"\n}\n'
  const r = await set('theme', 'light', put(text))
  assert.equal(r.changed, true)
  assert.equal(readFileSync(claudeFile, 'utf8'), text.replace('"dark"', '"light"'))
  assert.equal(r.hash, sha(readFileSync(claudeFile, 'utf8')))
})

test('a boolean, a number and a list, each its own bytes, and a new key laid out as the file is', async () => {
  let hash = put('{\n  "a": 1\n}\n')
  hash = (await set('verbose', true, hash)).hash!
  hash = (await set('cleanupPeriodDays', 60, hash)).hash!
  hash = (await set('availableModels', ['opus', 'sonnet'], hash)).hash!
  assert.equal(readFileSync(claudeFile, 'utf8'), '{\n  "a": 1,\n  "verbose": true,\n  "cleanupPeriodDays": 60,\n  "availableModels": [\n    "opus",\n    "sonnet"\n  ]\n}\n')
  await set('availableModels', ['opus'], hash)
  assert.deepEqual(JSON.parse(readFileSync(claudeFile, 'utf8')).availableModels, ['opus'])
  assert.equal(JSON.parse(readFileSync(claudeFile, 'utf8')).a, 1)
})

test('a key can be taken out, by its bytes, and a key not there is no change', async () => {
  const hash = put('{\n  "a": 1,\n  "verbose": true,\n  "b": 2\n}\n')
  const r = await set('verbose', null, hash)
  assert.equal(readFileSync(claudeFile, 'utf8'), '{\n  "a": 1,\n  "b": 2\n}\n')
  const again = await set('verbose', null, r.hash)
  assert.equal(again.changed, false)
})

test('the schema decides what a value may be, on the server', async () => {
  const hash = put('{}\n')
  await refusal(set('cleanupPeriodDays', 0, hash), 'bad_value')
  await refusal(set('cleanupPeriodDays', 1.5, hash), 'bad_value')
  await refusal(set('verbose', 'yes', hash), 'bad_value')
  await refusal(set('editorMode', 'emacs', hash), 'bad_value')
  await refusal(set('fallbackModel', ['a', 'b', 'c', 'd'], hash), 'bad_value')
  await refusal(set('attribution', { nope: 1 }, hash), 'bad_value')
  assert.equal(readFileSync(claudeFile, 'utf8'), '{}\n')
})

test('a file’s value that isn’t what the control holds is shown as it is, as JSON', async () => {
  put('{"verbose":"yes","theme":"custom:mine"}')
  assert.equal((await row('verbose')).control, 'json')
  assert.equal((await row('verbose')).value, 'yes')
  const theme = await row('theme')
  assert.equal(theme.control, 'pick')
  assert.equal(theme.value, 'custom:mine')
  assert.ok(!theme.of.some((c: any) => c.value === 'custom:mine'))
})

// ── Loosening ────────────────────────────────────────────────────────────

test('loosening: permissions.defaultMode asks going up, not coming down, each choice says, and the server wants the flag', async () => {
  const hash = put('{"permissions":{"defaultMode":"default"}}')
  const mode = await row('permissions.defaultMode')
  const asks = Object.fromEntries(mode.of.map((c: any) => [c.value, c.asks]))
  assert.equal(asks.plan, null)
  assert.equal(asks.acceptEdits, 'loosens')
  assert.equal(asks.auto, 'loosens')
  await refusal(set('permissions.defaultMode', 'auto', hash), 'confirm_needed')
  assert.equal(readFileSync(claudeFile, 'utf8'), '{"permissions":{"defaultMode":"default"}}', 'nothing written')
  const r = await set('permissions.defaultMode', 'auto', hash, true)
  assert.equal(r.changed, true)
  const down = await set('permissions.defaultMode', 'plan', r.hash)
  assert.equal(down.changed, true, 'tightening asks nothing')
})

test('taking a key out that means a looser one asks: an unset defaultMode is `default`', async () => {
  const hash = put('{"permissions":{"defaultMode":"plan"}}')
  await refusal(set('permissions.defaultMode', null, hash), 'confirm_needed')
  const r = await set('permissions.defaultMode', null, hash, true)
  assert.equal(r.changed, true)
  assert.deepEqual(JSON.parse(readFileSync(claudeFile, 'utf8')), {})
})

for (const [key, value] of [['sandbox', { enabled: false }], ['skipWebFetchPreflight', true], ['useAutoModeDuringPlan', true], ['permissions.additionalDirectories', ['/']], ['autoMode', { allow: ['x'] }]] as const) {
  test(`loosening: ${key} wants the confirm`, async () => {
    const hash = put('{}\n')
    await refusal(set(key, value, hash), 'confirm_needed')
    assert.equal(readFileSync(claudeFile, 'utf8'), '{}\n')
    assert.equal((await set(key, value, hash, true)).changed, true)
  })
}

// ── Runs code ────────────────────────────────────────────────────────────

const runsCode: [string, unknown][] = [
  ['hooks', { Stop: [{ hooks: [{ type: 'command', command: 'touch /tmp/x' }] }] }],
  ['apiKeyHelper', '/bin/key.sh'],
  ['awsAuthRefresh', 'aws sso login'],
  ['awsCredentialExport', '/bin/grant.sh'],
  ['gcpAuthRefresh', 'gcloud auth'],
  ['otelHeadersHelper', '/bin/headers.sh'],
  ['statusLine', { type: 'command', command: '/bin/line.sh' }],
  ['fileSuggestion', { type: 'command', command: '/bin/files.sh' }],
  ['processWrapper', '/opt/launcher'],
  ['enableAllProjectMcpServers', true],
  ['disableAllHooks', true],
]
for (const [key, value] of runsCode) {
  test(`runs code: ${key} wants the confirm, and its words say so, with nothing of the value`, async () => {
    const hash = put('{}\n')
    await assert.rejects(set(key, value, hash), (e: any) => {
      assert.equal(e.code, 'confirm_needed')
      assert.equal(e.status, 403)
      assert.match(e.message, /run a command/)
      assert.ok(e.message.startsWith(key))
      assert.ok(!e.message.includes('/bin') && !e.message.includes('touch'))
      return true
    })
    assert.equal(readFileSync(claudeFile, 'utf8'), '{}\n')
    assert.equal((await set(key, value, hash, true)).changed, true)
    assert.deepEqual(JSON.parse(readFileSync(claudeFile, 'utf8'))[key], value)
  })
}

test('confirm is only `true`: a truthy word isn’t it', async () => {
  const hash = put('{}\n')
  await refusal(changeFormSetting({ agent: 'claude', key: 'hooks', value: {}, expected: hash, confirm: 'yes' }, models, dirs), 'confirm_needed')
})

test('taking out what runs code asks too: a hook may be a guard', async () => {
  const hash = put('{"hooks":{}}')
  await refusal(set('hooks', null, hash), 'confirm_needed')
})

// ── Secrets ──────────────────────────────────────────────────────────────

test('secret: a helper’s command stays on the server as a whole, even one that looks harmless', async () => {
  const text = '{"apiKeyHelper":"/opt/get-key --profile work"}'
  const hash = put(text)
  const r = await row('apiKeyHelper')
  assert.equal(r.value, PLACEHOLDER)
  assert.equal(r.hidden, 1)
  assert.ok(!JSON.stringify(await settingsForm('claude', noModels, dirs)).includes('/opt/get-key'))
  // Sent back as it came, with or without the confirm: nothing changes, and the mask isn't written.
  assert.equal((await set('apiKeyHelper', PLACEHOLDER, hash)).changed, false)
  assert.equal((await set('apiKeyHelper', PLACEHOLDER, hash, true)).changed, false)
  assert.equal(readFileSync(claudeFile, 'utf8'), text)
  // A new one needs the confirm and replaces it.
  await refusal(set('apiKeyHelper', '/opt/other', hash), 'confirm_needed')
  await set('apiKeyHelper', '/opt/other', hash, true)
  assert.equal(JSON.parse(readFileSync(claudeFile, 'utf8')).apiKeyHelper, '/opt/other')
})

test('secret: the mask can’t be planted where nothing was hidden', async () => {
  const hash = put('{}\n')
  await refusal(set('apiKeyHelper', PLACEHOLDER, hash, true), 'placeholder_unknown')
  await refusal(set('language', PLACEHOLDER, hash), 'placeholder_unknown')
  await refusal(set('attribution', { commit: PLACEHOLDER }, hash), 'placeholder_unknown')
  assert.equal(readFileSync(claudeFile, 'utf8'), '{}\n')
})

test('secret: inside an object, the JSON tab’s masks hold; left in place it comes back as stored, moved it’s refused', async () => {
  const token = 'sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789'
  const stored = { Stop: [{ hooks: [{ type: 'command', command: `curl -H "x-api-key: ${token}" https://example.test`, timeout: 5 }] }] }
  const hash = put(JSON.stringify({ hooks: stored }))
  const shown = await row('hooks')
  assert.ok(shown.hidden >= 1)
  assert.ok(!JSON.stringify(shown).includes(token))
  const sent = structuredClone(shown.value) as typeof stored
  // A change beside the secret.
  sent.Stop[0]!.hooks[0]!.timeout = 9
  await set('hooks', sent, hash, true)
  const after = JSON.parse(readFileSync(claudeFile, 'utf8')).hooks
  assert.equal(after.Stop[0].hooks[0].command, stored.Stop[0]!.hooks[0]!.command, 'the secret is what was stored, not the mask')
  assert.equal(after.Stop[0].hooks[0].timeout, 9)
  assert.ok(!readFileSync(claudeFile, 'utf8').includes(PLACEHOLDER))
})

test('secret: a token in a plain string row is masked and kept', async () => {
  const token = 'sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789'
  const text = JSON.stringify({ prUrlTemplate: `https://x.test/${token}` })
  const hash = put(text)
  const r = await row('prUrlTemplate')
  assert.ok(!JSON.stringify(r).includes(token))
  assert.equal((await set('prUrlTemplate', r.value, hash)).changed, false)
  assert.equal(readFileSync(claudeFile, 'utf8'), text)
})

test('secret: nothing a value holds reaches the log: only the key', async () => {
  const log = join(process.env.HYPERNOVA_LOGS!, 'daemon.log')
  const hash = put('{}\n')
  const r = await set('apiKeyHelper', '/opt/very-secret-helper', hash, true)
  await set('apiKeyHelper', '/opt/another-secret-helper', r.hash, true)
  await new Promise((done) => setTimeout(done, 50))
  const text = existsSync(log) ? readFileSync(log, 'utf8') : ''
  assert.ok(text.includes('apiKeyHelper'), 'the change is logged where the logs go')
  assert.ok(!text.includes('very-secret-helper') && !text.includes('another-secret-helper'))
})

// ── Unknown ──────────────────────────────────────────────────────────────

test('unknown: a key of the file the schema has never heard of asks, shown as it is, and a later Claude Code’s key doesn’t slip through', async () => {
  const hash = put('{"aNewKeyFromLater":{"a":1},"bash":"ok"}')
  const r = await row('aNewKeyFromLater')
  assert.equal(r.asks, 'unknown')
  assert.equal(r.notInSchema, true)
  assert.deepEqual(r.value, { a: 1 })
  assert.equal(r.control, 'json')
  await refusal(set('aNewKeyFromLater', { a: 2 }, hash), 'confirm_needed')
  assert.equal((await set('aNewKeyFromLater', { a: 2 }, hash, true)).changed, true)
})

test('unknown: a key neither in the schema nor the file can’t be made up', async () => {
  const hash = put('{}\n')
  await refusal(set('madeUpKey', true, hash, true), 'key_not_editable')
  await refusal(set('__proto__', true, hash, true), 'key_not_editable')
  await refusal(set('constructor', true, hash, true), 'key_not_editable')
  await refusal(set('permissions', { defaultMode: 'plan' }, hash, true), 'key_not_editable')
  assert.equal(readFileSync(claudeFile, 'utf8'), '{}\n')
})

test('unknown: a name that looks like a secret isn’t sent, only counted', async () => {
  put(JSON.stringify({ 'sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789': 1 }))
  const reply = await settingsForm('claude', noModels, dirs)
  assert.equal(reply.hiddenKeys, 1)
  assert.ok(!JSON.stringify(reply).includes('sk-ant-api03'))
})

test('unknown: a dotted name in the file is one key, not a path', async () => {
  const hash = put('{"permissions.nope":1,"a.b":2}')
  await set('a.b', 3, hash, true)
  assert.deepEqual(JSON.parse(readFileSync(claudeFile, 'utf8')), { 'permissions.nope': 1, 'a.b': 3 })
})

// ── Enterprise ───────────────────────────────────────────────────────────

test('enterprise: hidden, read-only when the file has it, and refused on the server', async () => {
  let hash = put('{}\n')
  assert.equal(await row('allowManagedHooksOnly'), undefined)
  await refusal(set('allowManagedHooksOnly', true, hash, true), 'managed_only')
  hash = put('{"allowManagedHooksOnly":true,"strictKnownMarketplaces":[]}')
  const r = await row('allowManagedHooksOnly')
  assert.equal(r.control, 'readonly')
  assert.equal(r.readOnly, 'managed')
  assert.equal(r.value, true)
  assert.equal(r.removable, false)
  await refusal(set('allowManagedHooksOnly', false, hash, true), 'managed_only')
  await refusal(set('allowManagedHooksOnly', null, hash, true), 'managed_only')
})

// ── Permissive values ────────────────────────────────────────────────────

test('permissive: bypass isn’t among the choices and is refused, confirm or not', async () => {
  const hash = put('{"permissions":{"defaultMode":"default"}}')
  const mode = await row('permissions.defaultMode')
  const values = mode.of.map((c: any) => c.value)
  for (const v of ['bypassPermissions', 'delegate', 'manual']) assert.ok(!values.includes(v), v)
  await refusal(set('permissions.defaultMode', 'bypassPermissions', hash, true), 'value_not_allowed')
  await refusal(set('permissions.defaultMode', 'delegate', hash, true), 'value_not_allowed')
})

test('permissive: a file holding bypass shows it as it is, can move off, and Undo won’t put it back', async () => {
  const hash = put('{"permissions":{"defaultMode":"bypassPermissions"}}')
  const shown = await row('permissions.defaultMode')
  assert.equal(shown.value, 'bypassPermissions')
  assert.ok(!shown.of.some((c: any) => c.value === 'bypassPermissions'))
  const r = await set('permissions.defaultMode', 'plan', hash)
  assert.equal(r.changed, true)
  assert.equal(r.undo, null, 'no Undo is offered')
  await refusal(Promise.resolve().then(() => undoFormChange({ agent: 'claude', expected: r.hash, confirm: true }, dirs)), 'undo_refused')
  assert.equal(JSON.parse(readFileSync(claudeFile, 'utf8')).permissions.defaultMode, 'plan')
})

test('permissive: skipDangerousModePermissionPrompt can’t be set, only taken out', async () => {
  const hash = put('{"skipDangerousModePermissionPrompt":true}')
  const r = await row('skipDangerousModePermissionPrompt')
  assert.equal(r.readOnly, 'not_offered')
  assert.equal(r.removable, true)
  await refusal(set('skipDangerousModePermissionPrompt', false, hash, true), 'value_not_allowed')
  await refusal(set('skipDangerousModePermissionPrompt', true, hash, true), 'value_not_allowed')
  assert.equal((await set('skipDangerousModePermissionPrompt', null, hash, true)).changed, true)
})

test('permissive: a dangerous key nested in what an object editor sends is refused, whatever the confirm', async () => {
  const hash = put('{}\n')
  await refusal(set('sandbox', { dangerouslyDisableSandbox: true }, hash, true), 'value_not_allowed')
  const h2 = put('{"later":{}}')
  await refusal(set('later', { deep: { skipDangerousModePermissionPrompt: true } }, h2, true), 'value_not_allowed')
})

test('the update channel keeps the list’s words: `rc` isn’t offered or taken', async () => {
  const hash = put('{}\n')
  await refusal(set('autoUpdatesChannel', 'rc', hash), 'value_not_allowed')
  assert.deepEqual((await row('autoUpdatesChannel')).of.map((c: any) => c.value), ['latest', 'stable'])
  assert.equal((await row('autoUpdatesChannel')).applies, 'next_update')
})

// ── Nested ───────────────────────────────────────────────────────────────

test('nested: a part of `permissions` is its own row, beside the rules, and those are untouched', async () => {
  const text = '{\n  "permissions": {\n    "allow": ["Bash(ls)"],\n    "defaultMode": "plan"\n  }\n}\n'
  const hash = put(text)
  const r = await set('permissions.additionalDirectories', ['/data'], hash, true)
  const doc = JSON.parse(readFileSync(claudeFile, 'utf8'))
  assert.deepEqual(doc.permissions, { allow: ['Bash(ls)'], defaultMode: 'plan', additionalDirectories: ['/data'] })
  assert.ok(readFileSync(claudeFile, 'utf8').startsWith('{\n  "permissions": {\n    "allow": ["Bash(ls)"],\n    "defaultMode": "plan",'))
  assert.ok(r.changed)
})

test('nested: a permission rule has its own screen and is refused here', async () => {
  const hash = put('{}\n')
  for (const k of ['permissions.allow', 'permissions.ask', 'permissions.deny', 'env', 'enabledPlugins', 'enabledMcpjsonServers', 'modelSettings']) await refusal(set(k, ['x'], hash, true), 'own_screen')
})

test('nested: `permissions` as something other than a table is left alone', async () => {
  const hash = put('{"permissions":"odd"}')
  await refusal(set('permissions.additionalDirectories', ['/x'], hash, true), 'unsupported_shape')
  assert.equal(readFileSync(claudeFile, 'utf8'), '{"permissions":"odd"}')
})

// ── The same writer ──────────────────────────────────────────────────────

test('an object replaces only its own bytes, laid out as the file is', async () => {
  const text = '{\n  "before": [1,  2],\n  "attribution": {"commit":"a"},\n  "after": "\\u00e9"\n}\n'
  await set('attribution', { commit: 'b', pr: 'c' }, put(text))
  assert.equal(readFileSync(claudeFile, 'utf8'), '{\n  "before": [1,  2],\n  "attribution": {\n    "commit": "b",\n    "pr": "c"\n  },\n  "after": "\\u00e9"\n}\n')
})

test('a JSON editor’s text goes as text: its keys stay in the order typed, and bad JSON says where, not what', async () => {
  const hash = put('{"attribution":{"commit":"a"}}')
  const shown = await row('attribution')
  assert.equal(shown.text, '{\n  "commit": "a"\n}')
  await refusal(changeFormSetting({ agent: 'claude', key: 'attribution', valueJson: '{"pr": sk-secret', expected: hash }, models, dirs), 'bad_json')
  await assert.rejects(changeFormSetting({ agent: 'claude', key: 'attribution', valueJson: '{"pr": sk-secret', expected: hash }, models, dirs), (e: any) => !e.message.includes('sk-secret') && /Line 1, column/.test(e.message))
  await changeFormSetting({ agent: 'claude', key: 'attribution', valueJson: '{"pr":"p","commit":"c"}', expected: hash }, models, dirs)
  assert.deepEqual(Object.keys(JSON.parse(readFileSync(claudeFile, 'utf8')).attribution), ['pr', 'commit'])
})

test('rows come in the groups’ order, each group by key', async () => {
  const { rows } = await settingsForm('claude', noModels, dirs)
  const order = Object.keys(GROUPS)
  const at = rows.map((r: any) => order.indexOf(r.group))
  assert.deepEqual(at, [...at].sort((a, b) => a - b))
  assert.equal(rows[0]!.group, 'perms')
})

test('hash: an old hash is refused and nothing is written', async () => {
  put('{"verbose":true}')
  await refusal(set('verbose', false, sha('something else')), 'file_changed')
  assert.equal(readFileSync(claudeFile, 'utf8'), '{"verbose":true}')
})

test('hash: another program’s write in the last instant stands', async () => {
  const hash = put('{"verbose":true}')
  await refusal(changeFormSetting({ agent: 'claude', key: 'verbose', value: false, expected: hash }, models, dirs, { beforeRename: () => writeFileSync(claudeFile, '{"other":1}') }), 'file_changed')
  assert.equal(readFileSync(claudeFile, 'utf8'), '{"other":1}')
})

test('backup: the old bytes are kept, 0600, not beside the file', async () => {
  const text = '{"verbose":true}'
  await set('verbose', false, put(text))
  const names = readdirSync(backupDir()).filter((n) => n.startsWith('claude-'))
  assert.equal(names.length, 1)
  assert.equal(readFileSync(join(backupDir(), names[0]!), 'utf8'), text)
  assert.deepEqual(readdirSync(claude), ['settings.json'])
})

test('a link is refused, not followed, and a read-only file too', async () => {
  const target = join(scratch, 'elsewhere.json')
  writeFileSync(target, '{}')
  symlinkSync(target, claudeFile)
  const reply = await settingsForm('claude', noModels, dirs)
  assert.equal(reply.whyNot, 'link')
  assert.equal(reply.rows.length, 0)
  await refusal(set('verbose', true, sha('{}')), 'file_is_link')
  assert.equal(readFileSync(target, 'utf8'), '{}')
  rmSync(claudeFile)
  const hash = put('{}')
  chmodSync(claudeFile, 0o400)
  if (process.getuid?.() !== 0) await refusal(set('verbose', true, hash), 'file_read_only')
  chmodSync(claudeFile, 0o600)
})

test('a file that isn’t JSON: no rows, no text, and no write', async () => {
  const hash = put('{"secret": "sk-ant-api03-abc", broken')
  const reply = await settingsForm('claude', noModels, dirs)
  assert.equal(reply.state, 'malformed')
  assert.deepEqual(reply.rows, [])
  assert.ok(!JSON.stringify(reply).includes('sk-ant'))
  await refusal(set('verbose', true, hash), 'file_malformed')
})

test('a file that isn’t there is made with the one key, and Undo takes it away', async () => {
  const r = await set('verbose', true, null)
  assert.deepEqual(JSON.parse(readFileSync(claudeFile, 'utf8')), { verbose: true })
  assert.ok(r.undo)
  undoFormChange({ agent: 'claude', expected: r.hash }, dirs)
  assert.equal(existsSync(claudeFile), false)
})

// ── Undo ─────────────────────────────────────────────────────────────────

test('Undo: one step, the file as it was byte for byte, and it’s offered with what it takes', async () => {
  const text = '{\n   "verbose" : false,\n "x": 1.0 }\n'
  const r = await set('verbose', true, put(text))
  assert.deepEqual(r.undo, { key: 'verbose', asks: false })
  const reply = await settingsForm('claude', noModels, dirs)
  assert.deepEqual(reply.undo, { key: 'verbose', asks: false })
  undoFormChange({ agent: 'claude', expected: r.hash }, dirs)
  assert.equal(readFileSync(claudeFile, 'utf8'), text)
  assert.equal((await settingsForm('claude', noModels, dirs)).undo, null)
  await refusal(Promise.resolve().then(() => undoFormChange({ agent: 'claude', expected: sha(text) }, dirs)), 'nothing_to_undo')
})

test('Undo: putting back what runs code wants the confirm too, on the server', async () => {
  const text = '{"apiKeyHelper":"/bin/a"}'
  const r = await set('apiKeyHelper', null, put(text), true)
  assert.deepEqual(r.undo, { key: 'apiKeyHelper', asks: true })
  await refusal(Promise.resolve().then(() => undoFormChange({ agent: 'claude', expected: r.hash }, dirs)), 'confirm_needed')
  assert.equal(JSON.parse(readFileSync(claudeFile, 'utf8')).apiKeyHelper, undefined)
  undoFormChange({ agent: 'claude', expected: r.hash, confirm: true }, dirs)
  assert.equal(readFileSync(claudeFile, 'utf8'), text)
})

test('Undo: an old hash refuses, and someone else’s write stops it', async () => {
  const r = await set('verbose', true, put('{}'))
  writeFileSync(claudeFile, '{"other":1}')
  await refusal(Promise.resolve().then(() => undoFormChange({ agent: 'claude', expected: r.hash }, dirs)), 'file_changed')
  assert.equal(readFileSync(claudeFile, 'utf8'), '{"other":1}')
})

test('Undo: the list’s route won’t put back a form change it has no rule for, and doesn’t offer one', async () => {
  const r = await set('verbose', true, put('{}'))
  assert.throws(() => undoChange({ agent: 'claude', expected: r.hash }, dirs), (e: any) => e.code === 'undo_refused')
  assert.deepEqual(JSON.parse(readFileSync(claudeFile, 'utf8')), { verbose: true })
})

test('Undo: a list change isn’t the form’s to undo', async () => {
  const hash = put('{}\n')
  const r = await changeSetting({ agent: 'claude', key: 'showThinkingSummaries', value: true, expected: hash }, noModels, dirs)
  assert.equal((await settingsForm('claude', noModels, dirs)).undo, null)
  await refusal(Promise.resolve().then(() => undoFormChange({ agent: 'claude', expected: r.hash }, dirs)), 'nothing_to_undo')
})

// ── Codex ────────────────────────────────────────────────────────────────

const toml = (text: string) => put(text, codexFile)

test('Codex: plain top-level values get controls, a table or a list only that it’s there', async () => {
  const hash = toml('# mine\nmodel_verbosity = "low" # note\nhide_agent_reasoning = true\nmodel_context_window = 1000\nnotify = ["a", "b"]\n\n[mcp_servers.x]\ncommand = "secret-cmd"\n\n[history]\npersistence = "none"\n')
  const reply = await settingsForm('codex', noModels, dirs)
  assert.equal(reply.hash, hash)
  assert.ok(!JSON.stringify(reply).includes('secret-cmd'))
  const of = (k: string): any => reply.rows.find((r: any) => r.key === k)
  assert.equal(of('model_verbosity').value, 'low')
  assert.equal(of('model_verbosity').control, 'pick')
  assert.equal(of('hide_agent_reasoning').control, 'switch')
  assert.equal(of('hide_agent_reasoning').value, true)
  assert.equal(of('model_context_window').control, 'number')
  assert.equal(of('model_context_window').value, 1000)
  assert.equal(of('notify').control, 'readonly')
  assert.equal(of('notify').state, 'opaque')
  assert.equal(of('notify').value, undefined)
  assert.equal(of('history').control, 'readonly')
  assert.equal(of('mcp_servers').control, 'link')
  assert.equal(of('mcp_servers').screen, 'mcp')
})

test('Codex: a value’s bytes change, comments stay, a number and a boolean too, and a key can be taken out', async () => {
  const text = '# mine\nmodel_verbosity = "low" # note\nhide_agent_reasoning = true\nmodel_context_window = 1000\n\n[history]\npersistence = "none"\n'
  let hash = toml(text)
  hash = (await set('model_verbosity', 'high', hash, false, 'codex')).hash!
  assert.equal(readFileSync(codexFile, 'utf8'), text.replace('"low"', '"high"'))
  hash = (await set('hide_agent_reasoning', false, hash, false, 'codex')).hash!
  hash = (await set('model_context_window', 2000, hash, false, 'codex')).hash!
  hash = (await set('tool_output_token_limit', 5, hash, false, 'codex')).hash!
  const now = readFileSync(codexFile, 'utf8')
  assert.ok(now.includes('hide_agent_reasoning = false\nmodel_context_window = 2000\ntool_output_token_limit = 5\n\n[history]'), now)
  hash = (await set('model_context_window', null, hash, false, 'codex')).hash!
  assert.ok(!readFileSync(codexFile, 'utf8').includes('model_context_window'))
  assert.ok(readFileSync(codexFile, 'utf8').includes('# mine\n'))
  assert.ok(readFileSync(codexFile, 'utf8').includes('persistence = "none"'))
})

test('Codex: permissive values stay refused and not offered, a file holding one shows it, loosening confirms, Undo won’t put it back', async () => {
  const hash = toml('approval_policy = "never"\nsandbox_mode = "danger-full-access"\n')
  const policy = await row('approval_policy', 'codex')
  assert.equal(policy.value, 'never')
  assert.deepEqual(policy.of.map((c: any) => c.value), ['on-request'])
  const sandbox = await row('sandbox_mode', 'codex')
  assert.equal(sandbox.value, 'danger-full-access')
  assert.deepEqual(sandbox.of.map((c: any) => c.value), ['read-only', 'workspace-write'])
  await refusal(set('approval_policy', 'never', sha('x'), true, 'codex'), 'file_changed')
  await refusal(set('approval_policy', 'untrusted', hash, true, 'codex'), 'value_not_allowed')
  const r = await set('sandbox_mode', 'read-only', hash, false, 'codex')
  assert.equal(r.undo, null)
  await refusal(Promise.resolve().then(() => undoFormChange({ agent: 'codex', expected: r.hash, confirm: true }, dirs)), 'undo_refused')
  await refusal(set('sandbox_mode', 'workspace-write', r.hash, false, 'codex'), 'confirm_needed')
  await refusal(set('sandbox_mode', 'danger-full-access', r.hash, true, 'codex'), 'value_not_allowed')
})

test('Codex: what runs or reaches out asks, tables are refused, what has its own screen too, and made-up keys aren’t taken', async () => {
  const hash = toml('')
  await refusal(set('openai_base_url', 'https://x.test', hash, false, 'codex'), 'confirm_needed')
  await refusal(set('developer_instructions', 'hello', hash, false, 'codex'), 'confirm_needed')
  await refusal(set('notify', ['x'], hash, true, 'codex'), 'read_only_here')
  await refusal(set('hooks', {}, hash, true, 'codex'), 'read_only_here')
  await refusal(set('mcp_servers', {}, hash, true, 'codex'), 'own_screen')
  await refusal(set('madeUp', 'x', hash, true, 'codex'), 'key_not_editable')
  await refusal(set('model_context_window', 'many', hash, false, 'codex'), 'bad_value')
  await refusal(set('hide_agent_reasoning', { a: 1 }, hash, false, 'codex'), 'value_not_allowed')
})

test('Codex: the model comes from Codex’s own list, and nothing else', async () => {
  const hash = toml('')
  const r = await row('model', 'codex', models)
  assert.deepEqual(r.of, [{ value: 'gpt-x', label: 'GPT X', asks: null }])
  await refusal(set('model', 'other', hash, false, 'codex'), 'value_not_allowed')
  assert.equal((await set('model', 'gpt-x', hash, false, 'codex')).changed, true)
  assert.equal((await row('model', 'codex', models)).applies, 'new_threads')
  const again = sha(readFileSync(codexFile, 'utf8'))
  await refusal(changeFormSetting({ agent: 'codex', key: 'model', value: 'other', expected: again }, noModels, dirs), 'models_unavailable')
})

test('Codex: a token as a plain value is masked, kept, and never written as the mask', async () => {
  const token = 'sk-proj-abcdefghijklmnopqrstuvwxyz0123456789'
  const text = `openai_base_url = "https://x.test/${token}"\n`
  const hash = toml(text)
  const r = await row('openai_base_url', 'codex')
  assert.ok(!JSON.stringify(r).includes(token))
  assert.ok(r.hidden >= 1)
  assert.equal((await set('openai_base_url', PLACEHOLDER, hash, true, 'codex')).changed, false)
  assert.equal(readFileSync(codexFile, 'utf8'), text)
})

test('Codex: a key it doesn’t know in the file: its name at most, and read-only', async () => {
  const hash = toml('later_key = "x"\n')
  const r = await row('later_key', 'codex')
  assert.equal(r.notInSchema, true)
  assert.equal(r.control, 'readonly')
  assert.equal(r.value, undefined)
  await refusal(set('later_key', 'y', hash, true, 'codex'), 'key_not_editable')
})

test('Codex: Undo goes back byte for byte', async () => {
  const text = '# keep\nmodel_verbosity = "low"\n'
  const r = await set('model_verbosity', 'high', toml(text), false, 'codex')
  assert.deepEqual(r.undo, { key: 'model_verbosity', asks: false })
  undoFormChange({ agent: 'codex', expected: r.hash }, dirs)
  assert.equal(readFileSync(codexFile, 'utf8'), text)
})

test('bad requests stop before anything', async () => {
  await refusal(changeFormSetting(null, models, dirs), 'bad_request')
  await refusal(changeFormSetting({ agent: 'nope', key: 'a', value: 1, expected: null }, models, dirs), 'bad_agent')
  await refusal(changeFormSetting({ agent: 'claude', value: 1, expected: null }, models, dirs), 'bad_request')
  await refusal(changeFormSetting({ agent: 'claude', key: 'a', expected: null }, models, dirs), 'bad_value')
  await refusal(changeFormSetting({ agent: 'claude', key: 'a', value: 'x'.repeat(300 * 1024), expected: null }, models, dirs), 'bad_value')
  await refusal(changeFormSetting({ agent: 'claude', key: 'a', value: 1 }, models, dirs), 'bad_expected')
})
