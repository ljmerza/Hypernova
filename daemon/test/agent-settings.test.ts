// GET /v1/agents/settings (agent-settings.ts): the fixed list of Claude Code's and Codex's user-level
// settings, from scratch files. Never the real ones: CLAUDE_CONFIG_DIR and HOME point at the scratch folder. Only named keys leave: a secret is planted in each place that must
// stay on the server, and none of it may appear in the reply, in a value or in a key's name.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { chmodSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { basename, join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-agent-settings-'))
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

const { agentSettings, claudeConfigFile, claudeMcp, claudeSettings, codexSettings, sessionMcp, CLAUDE_SHOWN, CODEX_SHOWN } = await import('../src/agent-settings.ts')
const { scanToml, OTHER, TomlError } = await import('../src/toml-reader.ts')
const { startServers } = await import('../src/http.ts')
const { addDevice, settings } = await import('../src/store.ts')
const { upsertSession } = await import('../src/hub.ts')
const { saveThread, removeThread } = await import('../src/codex/threads.ts')
settings.lanAccess = false
const TOKEN = 'agent-settings-test-device-token-0123456789'
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

const dir = (name: string) => {
  const d = join(scratch, 'files', name)
  mkdirSync(d, { recursive: true })
  return d
}
const claudeFile = (name: string, content: unknown) => {
  const d = dir(name)
  writeFileSync(join(d, 'settings.json'), typeof content === 'string' ? content : JSON.stringify(content))
  return claudeSettings(d)
}
const codexFile = (name: string, content: string) => {
  const d = dir(name)
  writeFileSync(join(d, 'config.toml'), content)
  return codexSettings(d)
}
const valueOf = (r: { values: { key: string; state: string; value?: unknown }[] }, key: string) => r.values.find((v) => v.key === key)

/** A different secret per place, so a failure says which place leaked. */
const secret = (place: string) => `sk-ant-api03-PLANTED${place}Abcdefghijklmnop0123456789`

/** A Claude Code settings.json with a secret in every place that must stay here, each named below. */
const PLANTED_CLAUDE: Record<string, unknown> = {
  env: { ANTHROPIC_API_KEY: secret('Env'), PLANTEDENVNAME: 'x' },
  hooks: {
    Stop: [{ matcher: secret('HookMatcher'), hooks: [{ type: 'command', command: `curl -H "Authorization: Bearer ${secret('HookCommand')}" https://example.com` }] }],
  },
  statusLine: { type: 'command', command: `echo ${secret('StatusLine')}` },
  awsAuthRefresh: `aws sso login ${secret('AwsAuthRefresh')}`,
  awsCredentialExport: secret('AwsCredentialExport'),
  apiKeyHelper: `echo ${secret('ApiKeyHelper')}`,
  extraKnownMarketplaces: { 'PLANTEDMARKETNAME': { source: { source: 'git', url: `https://user:${secret('Marketplace')}@example.com/repo.git` } } },
  autoMode: { environment: [secret('AutoModeEnvironment')] },
  skipDangerousModePermissionPrompt: true,
  skipWorkflowUsageWarning: secret('SkipKey'),
  skipPLANTEDNAME: secret('SkipKeyTwo'),
  mcpServers: {
    'home-assistant': {
      command: `npx ${secret('McpCommand')}`,
      args: ['--token', secret('McpArgs')],
      env: { TOKEN: secret('McpEnv'), PLANTEDMCPENVNAME: 'x' },
      headers: { Authorization: secret('McpHeader') },
    },
  },
  permissions: {
    allow: [`Bash(curl ${secret('AllowRule')})`, 'Read'],
    ask: [`Bash(${secret('AskRule')})`],
    deny: [`Read(${secret('DenyRule')})`, 'Write', 'Edit'],
    additionalDirectories: [`/home/PLANTEDDIR/${secret('Directory')}`],
    defaultMode: 'plan',
  },
  enabledPlugins: { 'formatter@PLANTEDPLUGINMARKET': true, 'linter@anthropic-tools': false },
  PLANTEDUNKNOWNKEY: secret('UnknownKey'),
  model: secret('NotOnTheList'),
  effortLevel: 'high',
}

const PLANTED_CODEX = `# ${secret('Comment')}
model = "gpt-6.1-sol"
model_reasoning_effort = "high"
PLANTEDTOPKEY = "${secret('TopKey')}"
developer_instructions = """
model = "${secret('InMultiline')}"
[mcp_servers.PLANTEDFAKESERVER]
sandbox_mode = "danger-full-access"
"""
notify = ["sh", "-c", "${secret('Notify')}"]
experimental_bearer_token = "${secret('Bearer')}"

[model_providers.PLANTEDPROVIDER]
base_url = "https://example.com"
api_key = "${secret('ProviderKey')}"
model = "${secret('TableModel')}"

[projects."/home/PLANTEDPROJECT/a"]
trust_level = "trusted"

[projects."/home/PLANTEDPROJECT/b"]
trust_level = "untrusted"

[mcp_servers.home-assistant]
command = "npx ${secret('McpCommand')}"
args = ["--token", "${secret('McpArgs')}"]

[mcp_servers.home-assistant.env]
TOKEN = "${secret('McpEnv')}"
PLANTEDMCPENV = "x"

[mcp_servers.fetch]
url = "https://example.com/${secret('McpUrl')}"
bearer_token_env_var = "${secret('McpBearer')}"

[shell_environment_policy.set]
PLANTEDSHELLVAR = "${secret('ShellEnv')}"
`

function assertNothingPlanted(body: string) {
  assert.ok(!/PLANTED/.test(body), `something planted left: ${body}`)
  assert.ok(!/sk-ant/.test(body), `a secret left: ${body}`)
}

/** Every key anywhere in [v], and every string value. */
function everyKey(v: unknown, out: string[] = []): string[] {
  if (Array.isArray(v)) v.forEach((x) => everyKey(x, out))
  else if (v && typeof v === 'object') for (const [k, x] of Object.entries(v)) {
    out.push(k)
    everyKey(x, out)
  }
  return out
}

test('Claude Code: a secret in every place that must stay, and only the list and the summary leave', () => {
  const r = claudeFile('planted-claude', PLANTED_CLAUDE)
  assertNothingPlanted(JSON.stringify(r))
  assert.equal(r.state, 'ok')
  assert.deepEqual(r.summary, {
    rules: { allow: 2, ask: 1, deny: 3 },
    // The plugin's name without its marketplace.
    plugins: [{ name: 'formatter', on: true }, { name: 'linter', on: false }],
    // The names, never what's inside.
    mcpServers: ['home-assistant'],
    hookEvents: ['Stop'],
    hidden: 0,
  })
  assert.deepEqual(valueOf(r, 'effortLevel'), { key: 'effortLevel', state: 'set', value: 'high' })
  assert.deepEqual(valueOf(r, 'permissions.defaultMode'), { key: 'permissions.defaultMode', state: 'set', value: 'plan' })
  // The only keys in the reply are the reply's own.
  const allowed = new Set(['state', 'values', 'summary', 'key', 'value', 'rules', 'allow', 'ask', 'deny', 'plugins', 'name', 'on', 'mcpServers', 'hookEvents', 'hidden'])
  for (const k of everyKey(r)) assert.ok(allowed.has(k), `an unexpected key: ${k}`)
})

test('Codex: a secret in every place that must stay, also where a line only looks like a key', () => {
  const r = codexFile('planted-codex', PLANTED_CODEX)
  assertNothingPlanted(JSON.stringify(r))
  assert.equal(r.state, 'ok')
  assert.deepEqual(valueOf(r, 'model'), { key: 'model', state: 'set', value: 'gpt-6.1-sol' })
  // Inside the multi-line string and inside a table: not this key.
  assert.deepEqual(valueOf(r, 'sandbox_mode'), { key: 'sandbox_mode', state: 'unset' })
  assert.deepEqual(r.summary, { projects: 2, mcpServers: ['home-assistant', 'fetch'], hidden: 0 })
  const allowed = new Set(['state', 'values', 'summary', 'key', 'value', 'projects', 'mcpServers', 'hidden'])
  for (const k of everyKey(r)) assert.ok(allowed.has(k), `an unexpected key: ${k}`)
})

test('the route: both agents, the scope said, nothing planted, whichever way it asks', async () => {
  writeFileSync(join(CLAUDE, 'settings.json'), JSON.stringify(PLANTED_CLAUDE))
  writeFileSync(join(CODEX, 'config.toml'), PLANTED_CODEX)
  try {
    const r = await fetch(`http://127.0.0.1:${API}/v1/agents/settings`, { headers: { authorization: `Bearer ${TOKEN}` } })
    assert.equal(r.status, 200)
    const text = await r.text()
    assertNothingPlanted(text)
    const body = JSON.parse(text)
    assert.equal(body.scope, 'user')
    assert.deepEqual(Object.keys(body).sort(), ['claude', 'codex', 'scope'])
    assert.equal(body.claude.state, 'ok')
    assert.equal(body.codex.state, 'ok')
  } finally {
    rmSync(join(CLAUDE, 'settings.json'))
    rmSync(join(CODEX, 'config.toml'))
  }
})

test('the route is for a paired phone: no token, a wrong one and the local API get nothing', async () => {
  const url = `http://127.0.0.1:${API}/v1/agents/settings`
  assert.equal((await fetch(url)).status, 401)
  assert.equal((await fetch(url, { headers: { authorization: 'Bearer not-a-token' } })).status, 401)
  const local = await fetch(`http://127.0.0.1:${LOCAL}/v1/agents/settings`)
  assert.ok(local.status === 401 || local.status === 404, `the local API answered ${local.status}`)
  // PUT is the one way to write (agent-settings-edit.test.ts): an empty body changes nothing.
  for (const method of ['POST', 'PATCH', 'DELETE']) {
    const w = await fetch(url, { method, headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' }, body: method === 'DELETE' ? undefined : '{}' })
    assert.equal(w.status, 404, method)
  }
  const put = await fetch(url, { method: 'PUT', headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' }, body: '{}' })
  assert.equal(put.status, 400)
})

test('both files missing: each agent says so, with nothing else', async () => {
  const r = await fetch(`http://127.0.0.1:${API}/v1/agents/settings`, { headers: { authorization: `Bearer ${TOKEN}` } })
  assert.equal(r.status, 200)
  const body = (await r.json()) as any
  assert.deepEqual(Object.keys(body).sort(), ['claude', 'codex', 'scope'])
  // What can be set by making the file is agent-settings-edit.test.ts's.
  const { editable: _claude, ...claude } = body.claude
  assert.deepEqual(claude, { state: 'missing', values: [], summary: null, mcp: { state: 'missing', servers: [], hidden: 0, foldersNotRead: 0 }, hash: null, undo: null, whyNot: null })
  const { editable: _codex, ...codex } = body.codex
  assert.deepEqual(codex, { state: 'missing', values: [], summary: null, hash: null, undo: null, whyNot: null })
})

test('every shown value is its type and shape; anything else is unrecognised, not passed through', () => {
  const r = claudeFile('bad-values', {
    effortLevel: 'ultra-mega', // not a level
    permissions: { defaultMode: secret('DefaultMode') },
    showThinkingSummaries: 'yes',
    autoDreamEnabled: 1,
    includeCoAuthoredBy: { nested: secret('Nested') },
    autoUpdatesChannel: ['latest'],
    inputNeededNotifEnabled: null,
    agentPushNotifEnabled: true,
  })
  assert.deepEqual(r.values.map((v) => [v.key, v.state]), [
    ['effortLevel', 'unrecognised'],
    ['permissions.defaultMode', 'unrecognised'],
    ['showThinkingSummaries', 'unrecognised'],
    ['autoDreamEnabled', 'unrecognised'],
    ['includeCoAuthoredBy', 'unrecognised'],
    ['autoUpdatesChannel', 'unrecognised'],
    ['inputNeededNotifEnabled', 'unrecognised'],
    ['agentPushNotifEnabled', 'set'],
  ])
  const text = JSON.stringify(r)
  assert.ok(!text.includes('ultra-mega') && !text.includes('sk-ant') && !text.includes('yes'), text)
})

test('an unset key says unset, and the list is the same keys in the same order each time', () => {
  const r = claudeFile('empty', {})
  assert.deepEqual(r.values, CLAUDE_SHOWN.map((s) => ({ key: s.key, state: 'unset' })))
  assert.deepEqual(r.summary, { rules: { allow: 0, ask: 0, deny: 0 }, plugins: [], mcpServers: [], hookEvents: [], hidden: 0 })
  const c = codexFile('empty-codex', '')
  assert.deepEqual(c.values, CODEX_SHOWN.map((s) => ({ key: s.key, state: 'unset' })))
  assert.deepEqual(c.summary, { projects: 0, mcpServers: [], hidden: 0 })
})

test('Codex’s values: a model id’s shape, a length cap and maskSecrets; a table or number is unrecognised', () => {
  const state = (toml: string, key: string) => valueOf(codexFile(`v-${Math.random()}`, toml), key)
  assert.equal(state('model = "qwen/qwen3[1m]:free"', 'model')?.state, 'set')
  assert.equal(state(`model = "${secret('Model')}"`, 'model')?.state, 'unrecognised')
  assert.equal(state('model = "abcdef0123456789abcdef0123456789abcdef01"', 'model')?.state, 'unrecognised')
  assert.equal(state(`model = "${'a'.repeat(81)}"`, 'model')?.state, 'unrecognised')
  assert.equal(state('model = "two words"', 'model')?.state, 'unrecognised')
  assert.equal(state('model = 5', 'model')?.state, 'unrecognised')
  assert.equal(state('[model]\nname = "x"', 'model')?.state, 'unrecognised')
  // The granular form is a real one; it isn't a short word, so it's said to be something else.
  assert.equal(state('approval_policy = { granular = { rules = true } }', 'approval_policy')?.state, 'unrecognised')
  assert.equal(state('[approval_policy.granular]\nrules = true', 'approval_policy')?.state, 'unrecognised')
  assert.equal(state('approval_policy.granular.rules = true', 'approval_policy')?.state, 'unrecognised')
  assert.equal(state('web_search = "live"', 'web_search')?.state, 'set')
  assert.equal(state('web_search = "everything"', 'web_search')?.state, 'unrecognised')
  assert.equal(state('sandbox_mode = "danger-full-access"', 'sandbox_mode')?.state, 'set')
  assert.equal(state("personality = 'friendly'", 'personality')?.state, 'set')
})

test('names are checked too: one that might be a secret or isn’t a name is counted, never sent', () => {
  const r = claudeFile('bad-names', {
    mcpServers: { fine: {}, [secret('McpName')]: {}, 'two words': {}, 'curl https://x.example': {}, __proto__x: {} },
    hooks: { Stop: [], [`echo ${secret('HookName')}`]: [] },
    enabledPlugins: { [`${secret('PluginName')}@market`]: true, 'good@market': ['1.0.0'], 'plain': false },
  })
  const text = JSON.stringify(r)
  assert.ok(!text.includes('sk-ant') && !text.includes('two words') && !text.includes('curl'), text)
  assert.deepEqual(r.summary!.mcpServers, ['fine'])
  assert.deepEqual(r.summary!.hookEvents, ['Stop'])
  // Neither on nor off (the extended form): null, not a guess.
  assert.deepEqual(r.summary!.plugins, [{ name: 'good', on: null }, { name: 'plain', on: false }])
  assert.equal(r.summary!.hidden, 6)
  const c = codexFile('bad-names-codex', `[mcp_servers."${secret('CodexMcpName')}"]\n[mcp_servers."two words"]\n[mcp_servers.fine]\n`)
  assert.ok(!JSON.stringify(c).includes('sk-ant'))
  assert.deepEqual(c.summary, { projects: 0, mcpServers: ['fine'], hidden: 2 })
})

test('past the cap: the first names, and the rest counted', () => {
  const many = Object.fromEntries(Array.from({ length: 205 }, (_, i) => [`s${i}`, {}]))
  const r = claudeFile('many', { mcpServers: many })
  assert.equal(r.summary!.mcpServers!.length, 200)
  assert.equal(r.summary!.hidden, 5)
})

test('a summary whose table is something else is null, not a count', () => {
  const r = claudeFile('bad-shapes', { permissions: 'all', enabledPlugins: 3, mcpServers: [secret('Array')], hooks: secret('Hooks') })
  assert.deepEqual(r.summary, { rules: { allow: null, ask: null, deny: null }, plugins: null, mcpServers: null, hookEvents: null, hidden: 0 })
  assert.ok(!JSON.stringify(r).includes('sk-ant'))
  const p = claudeFile('bad-rules', { permissions: { allow: 'Bash', deny: [] } })
  assert.deepEqual(p.summary!.rules, { allow: null, ask: 0, deny: 0 })
  const c = codexFile('bad-shapes-codex', `projects = "${secret('Projects')}"\nmcp_servers = true\n`)
  assert.deepEqual(c.summary, { projects: null, mcpServers: null, hidden: 0 })
  assert.ok(!JSON.stringify(c).includes('sk-ant'))
})

test('a malformed file gives the state, and no parse error that could quote it', () => {
  const quoted = `{"env": {"KEY": "${secret('Quoted')}"`
  const bad = claudeFile('malformed', quoted)
  assert.deepEqual(bad, { state: 'malformed', values: [], summary: null })
  for (const notAnObject of ['[]', '"text"', '12', 'null']) assert.equal(claudeFile(`m-${notAnObject.length}-${notAnObject[0]}`, notAnObject).state, 'malformed')
  const toml = codexFile('malformed-codex', `model = unquoted-${secret('Quoted')}\n`)
  assert.deepEqual(toml, { state: 'malformed', values: [], summary: null })
  assert.equal(codexFile('malformed-dup', 'model = "a"\nmodel = "b"\n').state, 'malformed')
  assert.equal(codexFile('malformed-open', 'model = "a\n').state, 'malformed')
})

test('malformed through the route is a 200 with the state, for each agent on its own', async () => {
  writeFileSync(join(CLAUDE, 'settings.json'), `{ "env": "${secret('Quoted')}" `)
  writeFileSync(join(CODEX, 'config.toml'), 'model = "gpt-6.1-sol"\n')
  try {
    const r = await fetch(`http://127.0.0.1:${API}/v1/agents/settings`, { headers: { authorization: `Bearer ${TOKEN}` } })
    assert.equal(r.status, 200)
    const text = await r.text()
    assert.ok(!text.includes('sk-ant'), text)
    const body = JSON.parse(text)
    assert.equal(body.claude.state, 'malformed')
    assert.equal(body.codex.state, 'ok')
  } finally {
    rmSync(join(CLAUDE, 'settings.json'))
    rmSync(join(CODEX, 'config.toml'))
  }
})

test('an unreadable file, a folder in its place, a pipe and a huge file say unreadable; a link is followed', { skip: process.getuid?.() === 0 && 'root reads anything' }, () => {
  const d = dir('unreadable')
  writeFileSync(join(d, 'settings.json'), '{}')
  chmodSync(join(d, 'settings.json'), 0o000)
  assert.deepEqual(claudeSettings(d), { state: 'unreadable', values: [], summary: null })
  chmodSync(join(d, 'settings.json'), 0o600)

  const folder = dir('a-folder')
  mkdirSync(join(folder, 'config.toml'))
  assert.equal(codexSettings(folder).state, 'unreadable')

  // A pipe with nobody writing would hang a plain read for good.
  const pipe = dir('a-pipe')
  execFileSync('mkfifo', [join(pipe, 'settings.json')])
  assert.equal(claudeSettings(pipe).state, 'unreadable')

  const huge = dir('huge')
  writeFileSync(join(huge, 'config.toml'), `# ${'x'.repeat(4 * 1024 * 1024)}\n`)
  assert.equal(codexSettings(huge).state, 'unreadable')

  const linked = dir('linked')
  const real = join(dir('linked-target'), 'real.json')
  writeFileSync(real, '{"effortLevel":"low"}')
  symlinkSync(real, join(linked, 'settings.json'))
  assert.equal(valueOf(claudeSettings(linked), 'effortLevel')?.value, 'low')
})

test('CODEX_HOME says where Codex’s file is, and agentSettings answers from the folders it’s given', async () => {
  const claude = dir('given-claude')
  const codex = dir('given-codex')
  writeFileSync(join(claude, 'settings.json'), '{"effortLevel":"xhigh"}')
  writeFileSync(join(codex, 'config.toml'), 'sandbox_mode = "read-only"\n')
  const r = await agentSettings({ claude, codex })
  assert.equal(valueOf(r.claude, 'effortLevel')?.value, 'xhigh')
  assert.equal(valueOf(r.codex, 'sandbox_mode')?.value, 'read-only')
  writeFileSync(join(CODEX, 'config.toml'), 'web_search = "cached"\n')
  try {
    assert.equal(valueOf(codexSettings(), 'web_search')?.value, 'cached')
  } finally {
    rmSync(join(CODEX, 'config.toml'))
  }
})

// ── MCP servers: `~/.claude.json` and each project's `.mcp.json` ─────────

/** A server with a secret in every place that must stay, each named for [place]. */
const plantedServer = (place: string) => ({
  type: 'http',
  command: `/home/PLANTEDUSER/bin/${secret(`${place}Command`)}`,
  args: ['--token', secret(`${place}Args`)],
  env: { TOKEN: secret(`${place}Env`), PLANTEDENVNAME: 'x' },
  url: `https://PLANTEDUSER:${secret(`${place}Password`)}@PLANTEDHOST.example.com/PLANTEDPATH?token=${secret(`${place}Query`)}`,
  headers: { Authorization: `Bearer ${secret(`${place}Header`)}` },
  headersHelper: `echo ${secret(`${place}Helper`)}`,
  oauth: { clientId: 'PLANTEDCLIENT', clientSecret: secret(`${place}Oauth`) },
})

/** A `~/.claude.json` with the account's details, OAuth data and history around the MCP tables. */
const plantedConfig = (projects: Record<string, unknown>, mcpServers: Record<string, unknown> = { 'user-one': plantedServer('User') }) => ({
  userID: 'PLANTEDUSERID0123456789',
  numStartups: 1234,
  primaryApiKey: secret('PrimaryKey'),
  oauthAccount: { accountUuid: 'PLANTEDACCOUNT', emailAddress: 'PLANTEDEMAIL@example.com', organizationName: 'PLANTEDORG', organizationUuid: 'PLANTEDORGID', displayName: 'PLANTEDNAME' },
  claudeAiOauth: { accessToken: secret('Access'), refreshToken: secret('Refresh') },
  customApiKeyResponses: { approved: [secret('Approved')], rejected: [secret('Rejected')] },
  claudeAiMcpEverConnected: ['PLANTEDCONNECTOR'],
  cachedChangelog: `PLANTEDCHANGELOG ${secret('Changelog')}`,
  mcpServers,
  projects,
})

const plantedEntry = (mcpServers: Record<string, unknown> = {}) => ({
  allowedTools: [`Bash(curl ${secret('AllowedTool')})`],
  history: [{ display: `PLANTEDHISTORY ${secret('History')}`, pastedContents: { a: secret('Pasted') } }],
  lastSessionId: 'PLANTEDSESSION',
  exampleFiles: ['PLANTEDEXAMPLE.ts'],
  hasTrustDialogAccepted: true,
  enabledMcpjsonServers: ['PLANTEDENABLED'],
  disabledMcpServers: ['PLANTEDDISABLED'],
  mcpServers,
})

/** A project folder in a place that says PLANTED, so its path can't leave unnoticed. */
function project(name: string, mcpJson?: unknown): string {
  const d = join(scratch, 'PLANTEDPATH', name)
  mkdirSync(d, { recursive: true })
  if (mcpJson !== undefined) writeFileSync(join(d, '.mcp.json'), typeof mcpJson === 'string' ? mcpJson : JSON.stringify(mcpJson))
  return d
}

function configFile(name: string, content: unknown): string {
  const file = join(dir(name), '.claude.json')
  writeFileSync(file, typeof content === 'string' ? content : JSON.stringify(content))
  return file
}

test('MCP: every scope is listed, by name, with the folder’s base name and nothing more', async () => {
  const app = project('app', { mcpServers: { 'project-one': plantedServer('Project') }, PLANTEDMCPJSONKEY: secret('McpJsonKey') })
  const site = project('my site', { mcpServers: { 'project-two': plantedServer('ProjectTwo') } })
  const quiet = project('quiet')
  const r = await claudeMcp(['from-settings'], configFile('scopes', plantedConfig({
    [app]: plantedEntry({ 'local-one': plantedServer('Local') }),
    [site]: plantedEntry(),
    [quiet]: plantedEntry(),
  })))
  assert.deepEqual(r, {
    state: 'ok',
    servers: [
      { name: 'from-settings', scope: 'user' },
      { name: 'user-one', scope: 'user' },
      { name: 'local-one', scope: 'local', project: 'app' },
      { name: 'project-one', scope: 'project', project: 'app' },
      { name: 'project-two', scope: 'project', project: 'my site' },
    ],
    hidden: 0,
    foldersNotRead: 0,
  })
  assertNothingPlanted(JSON.stringify(r))
})

test('the route: a secret anywhere in ~/.claude.json or a .mcp.json, and none of it in the raw body', async () => {
  const app = project('routed', { mcpServers: { 'project-one': plantedServer('Project') }, PLANTEDMCPJSONKEY: secret('McpJsonKey') })
  writeFileSync(join(CLAUDE, '.claude.json'), JSON.stringify(plantedConfig({ [app]: plantedEntry({ 'local-one': plantedServer('Local') }) })))
  writeFileSync(join(CLAUDE, 'settings.json'), JSON.stringify(PLANTED_CLAUDE))
  try {
    const r = await fetch(`http://127.0.0.1:${API}/v1/agents/settings`, { headers: { authorization: `Bearer ${TOKEN}` } })
    assert.equal(r.status, 200)
    const text = await r.text()
    assertNothingPlanted(text)
    assert.ok(!text.includes(scratch), `a path left: ${text}`)
    for (const leak of ['example.com', '--token', 'Bearer', 'numStartups', 'userID', 'oauth', 'history', 'command', 'args', 'env"', 'url', 'headers']) assert.ok(!text.includes(leak), `${leak} left: ${text}`)
    const body = JSON.parse(text)
    // Beside the older list, which an older app keeps reading as it did.
    assert.deepEqual(body.claude.summary.mcpServers, ['home-assistant'])
    assert.deepEqual(body.claude.mcp.servers, [
      { name: 'home-assistant', scope: 'user' },
      { name: 'user-one', scope: 'user' },
      { name: 'local-one', scope: 'local', project: 'routed' },
      { name: 'project-one', scope: 'project', project: 'routed' },
    ])
    // What a phone can change adds only these, each a hash, a word on the fixed list or a flag (agent-settings-edit.ts).
    const allowed = new Set(['hash', 'editable', 'choices', 'loosens', 'undo', 'whyNot', 'state', 'values', 'summary', 'key', 'value', 'rules', 'allow', 'ask', 'deny', 'plugins', 'name', 'on', 'mcpServers', 'hookEvents', 'hidden', 'mcp', 'servers', 'scope', 'project', 'foldersNotRead'])
    for (const k of everyKey(body.claude)) assert.ok(allowed.has(k), `an unexpected key: ${k}`)
  } finally {
    rmSync(join(CLAUDE, '.claude.json'))
    rmSync(join(CLAUDE, 'settings.json'))
  }
})

test('MCP servers don’t depend on settings.json: with it missing or malformed they’re still listed', async () => {
  const file = configFile('no-settings', plantedConfig({}))
  const missing = await agentSettings({ claude: dir('no-settings-claude'), codex: dir('no-settings-codex'), claudeConfig: file })
  assert.equal(missing.claude.state, 'missing')
  assert.deepEqual(missing.claude.mcp.servers, [{ name: 'user-one', scope: 'user' }])
  const bad = dir('bad-settings-claude')
  writeFileSync(join(bad, 'settings.json'), '{ nope')
  const malformed = await agentSettings({ claude: bad, codex: dir('no-settings-codex'), claudeConfig: file })
  assert.equal(malformed.claude.state, 'malformed')
  assert.deepEqual(malformed.claude.mcp.servers, [{ name: 'user-one', scope: 'user' }])
})

test('a name that isn’t one, or a folder’s, is counted and never sent; the home folder is ~', async () => {
  const fine = project('fine', { mcpServers: { [secret('ProjectName')]: {}, 'two words': {}, ok: {} } })
  const oddFolder = project(`bad;${secret('FolderName')}`, { mcpServers: { a: {}, b: {} } })
  writeFileSync(join(process.env.HOME!, '.mcp.json'), JSON.stringify({ mcpServers: { 'at-home': {} } }))
  try {
    const r = await claudeMcp([], configFile('bad-mcp-names', plantedConfig({
      [fine]: plantedEntry({ [secret('LocalName')]: {} }),
      [oddFolder]: plantedEntry({ c: {} }),
      [process.env.HOME!]: plantedEntry({ 'local-home': {} }),
    }, { [secret('UserName')]: {}, 'curl https://x.example': {}, good: {} })))
    assertNothingPlanted(JSON.stringify(r))
    assert.deepEqual(r.servers, [
      { name: 'good', scope: 'user' },
      // The home folder's name is the username: not sent.
      { name: 'local-home', scope: 'local', project: '~' },
      { name: 'ok', scope: 'project', project: 'fine' },
      { name: 'at-home', scope: 'project', project: '~' },
    ])
    // Two users', a local one, the folder's local one, two project ones, the folder's two.
    assert.equal(r.hidden, 2 + 1 + 1 + 2 + 2)
  } finally {
    rmSync(join(process.env.HOME!, '.mcp.json'))
  }
})

test('MCP’s caps: names, folders walked, and each file’s size; the rest of the reply stands', async () => {
  const many = Object.fromEntries(Array.from({ length: 205 }, (_, i) => [`s${i}`, {}]))
  const names = await claudeMcp([], configFile('mcp-many', plantedConfig({}, many)))
  assert.equal(names.servers.length, 200)
  assert.equal(names.hidden, 5)

  const projects: Record<string, unknown> = {}
  for (let i = 0; i < 203; i++) projects[join(scratch, 'PLANTEDPATH', 'walk', `p${i}`)] = {}
  const walked = await claudeMcp([], configFile('mcp-walk', plantedConfig(projects)))
  assert.equal(walked.state, 'ok')
  assert.equal(walked.foldersNotRead, 3)

  const big = project('big', `{"mcpServers":{"too-big":{}},"pad":"${'x'.repeat(256 * 1024)}"}`)
  const ok = project('ok', { mcpServers: { 'small-enough': {} } })
  const sized = await claudeMcp([], configFile('mcp-sized', plantedConfig({ [big]: {}, [ok]: {} }, {})))
  assert.deepEqual(sized.servers, [{ name: 'small-enough', scope: 'project', project: 'ok' }])

  const huge = configFile('mcp-huge', `{"mcpServers":{"past-the-cap":{}},"pad":"${'x'.repeat(16 * 1024 * 1024)}"}`)
  const past = await agentSettings({ claude: dir('mcp-huge-claude'), codex: dir('mcp-huge-codex'), claudeConfig: huge })
  assert.deepEqual(past.claude.mcp, { state: 'unreadable', servers: [], hidden: 0, foldersNotRead: 0 })
  assert.equal(past.codex.state, 'missing')
})

test('~/.claude.json missing, malformed or the wrong shape: a state, the rest of the reply, and no parse error', async () => {
  const none = await claudeMcp(['from-settings'], join(dir('mcp-none'), '.claude.json'))
  assert.deepEqual(none, { state: 'missing', servers: [{ name: 'from-settings', scope: 'user' }], hidden: 0, foldersNotRead: 0 })
  const bad = await claudeMcp([], configFile('mcp-malformed', `{"oauthAccount":{"emailAddress":"PLANTEDEMAIL@example.com","x":"${secret('Quoted')}"`))
  assert.deepEqual(bad, { state: 'malformed', servers: [], hidden: 0, foldersNotRead: 0 })
  for (const notAnObject of ['[]', '"text"', '12', 'null']) assert.equal((await claudeMcp([], configFile(`mcp-odd-${notAnObject.length}`, notAnObject))).state, 'malformed')
  // Tables that are something else: none, not a crash, and none of the values.
  const odd = await claudeMcp([], configFile('mcp-shapes', { mcpServers: [secret('Array')], projects: { a: 'x', '/b': { mcpServers: secret('Text') }, '/c': null } }))
  assert.deepEqual(odd, { state: 'ok', servers: [], hidden: 0, foldersNotRead: 0 })
  assert.deepEqual((await claudeMcp([], configFile('mcp-empty', ''))).state, 'ok')
  // A project's own file, malformed or missing, costs that project alone.
  const broken = project('broken', `{ "mcpServers": { "x": "${secret('QuotedMcpJson')}"`)
  const gone = join(scratch, 'PLANTEDPATH', 'never-made')
  const kept = project('kept', { mcpServers: { fine: {} } })
  const some = await claudeMcp([], configFile('mcp-some', plantedConfig({ [broken]: {}, [gone]: {}, [kept]: {} }, {})))
  assert.deepEqual(some, { state: 'ok', servers: [{ name: 'fine', scope: 'project', project: 'kept' }], hidden: 0, foldersNotRead: 0 })
})

test('.mcp.json as a link, a folder or a pipe is skipped; nothing outside the project is read', async () => {
  const outside = join(dir('outside'), 'mcp.json')
  writeFileSync(outside, JSON.stringify({ mcpServers: { 'PLANTEDOUTSIDE': {} } }))
  const linked = project('linked')
  symlinkSync(outside, join(linked, '.mcp.json'))
  const folder = project('folder')
  mkdirSync(join(folder, '.mcp.json'))
  // A pipe with nobody writing would hang a plain read for good.
  const pipe = project('pipe')
  execFileSync('mkfifo', [join(pipe, '.mcp.json')])
  const kept = project('kept-too', { mcpServers: { fine: {} } })
  const r = await claudeMcp([], configFile('mcp-links', plantedConfig({ [linked]: {}, [folder]: {}, [pipe]: {}, [kept]: {} }, {})))
  assert.deepEqual(r, { state: 'ok', servers: [{ name: 'fine', scope: 'project', project: 'kept-too' }], hidden: 0, foldersNotRead: 0 })
  assertNothingPlanted(JSON.stringify(r))
})

test('a project’s path that isn’t a clean absolute one, or a worktree’s, has its .mcp.json left alone', async () => {
  const real = project('real', { mcpServers: { 'through-dots': {} } })
  const worktree = project(join('repo', '.claude', 'worktrees', 'agent-1'), { mcpServers: { 'a-copy': {} } })
  const r = await claudeMcp([], configFile('mcp-paths', plantedConfig({ [`${real}/../real`]: {}, 'relative/path': {}, [worktree]: {} }, {})))
  assert.deepEqual(r, { state: 'ok', servers: [], hidden: 0, foldersNotRead: 0 })
})

test('Claude Code’s own file: in the home folder, or the config folder once that’s moved, or the old name there', () => {
  const home = dir('cfg-home')
  assert.equal(claudeConfigFile({}, home), join(home, '.claude.json'))
  const moved = dir('cfg-moved')
  assert.equal(claudeConfigFile({ CLAUDE_CONFIG_DIR: moved }, home), join(moved, '.claude.json'))
  // As Claude Code's `||`: empty is the home folder.
  assert.equal(claudeConfigFile({ CLAUDE_CONFIG_DIR: '' }, home), join(home, '.claude.json'))
  // An empty old file doesn't count, one with something in it does.
  writeFileSync(join(moved, '.config.json'), '')
  assert.equal(claudeConfigFile({ CLAUDE_CONFIG_DIR: moved }, home), join(moved, '.claude.json'))
  writeFileSync(join(moved, '.config.json'), '{}')
  assert.equal(claudeConfigFile({ CLAUDE_CONFIG_DIR: moved }, home), join(moved, '.config.json'))
  mkdirSync(join(home, '.claude'))
  writeFileSync(join(home, '.claude', '.config.json'), '{}')
  assert.equal(claudeConfigFile({}, home), join(home, '.claude', '.config.json'))
})

// ── One session's folder: GET /v1/sessions/{id}/mcp ─────────────────────

const stdioServer = (place: string, command = '/home/PLANTEDUSER/PLANTEDBIN/npx') => ({
  command,
  args: ['--token', secret(`${place}Args`), `--dir=/home/PLANTEDUSER/PLANTEDDIR`],
  env: { TOKEN: secret(`${place}Env`), PLANTEDENVNAME: 'x' },
})
const httpServer = (place: string, type = 'http') => ({
  type,
  url: `https://PLANTEDUSER:${secret(`${place}Password`)}@mcp.example.com:8443/PLANTEDPATH/${secret(`${place}Path`)}?token=${secret(`${place}Query`)}#PLANTEDFRAGMENT`,
  headers: { Authorization: `Bearer ${secret(`${place}Header`)}` },
  headersHelper: `/home/PLANTEDUSER/${secret(`${place}Helper`)}`,
})

test('a session’s folder: its .mcp.json and its local servers, with what each may say, and only a count for the user’s', async () => {
  const cwd = project('session-app', { mcpServers: { docs: stdioServer('Docs'), remote: httpServer('Remote'), stream: httpServer('Stream', 'sse') } })
  const other = project('session-other', { mcpServers: { 'not-this-folder': stdioServer('Other') } })
  const file = configFile('session-scopes', plantedConfig(
    { [cwd]: plantedEntry({ db: stdioServer('Db', 'uvx') }), [other]: plantedEntry({ 'nor-this-one': stdioServer('Other') }) },
    { 'for-all': stdioServer('User'), 'for-all-too': httpServer('UserTwo') },
  ))
  const r = await sessionMcp(cwd, file, dir('session-scopes-claude'))
  assert.deepEqual(r, {
    state: 'ok',
    servers: [
      { name: 'db', scope: 'local', transport: 'stdio', command: 'uvx' },
      { name: 'docs', scope: 'project', transport: 'stdio', command: 'npx' },
      { name: 'remote', scope: 'project', transport: 'http', host: 'mcp.example.com' },
      { name: 'stream', scope: 'project', transport: 'sse', host: 'mcp.example.com' },
    ],
    forAllProjects: 2,
    hidden: 0,
    foldersNotRead: 0,
  })
  assertNothingPlanted(JSON.stringify(r))
})

test('parent folders count as Claude Code has them: the nearer file wins a name, and the source’s base name is said', async () => {
  const parent = project('monorepo', { mcpServers: { 'from-parent': stdioServer('Parent'), shared: stdioServer('Far', 'far') } })
  const cwd = join(parent, 'pkg', 'web')
  mkdirSync(cwd, { recursive: true })
  writeFileSync(join(cwd, '.mcp.json'), JSON.stringify({ mcpServers: { shared: stdioServer('Near', 'near') } }))
  const r = await sessionMcp(cwd, configFile('session-parents', plantedConfig({}, {})), dir('session-parents-claude'))
  assert.deepEqual(r.servers, [
    { name: 'from-parent', scope: 'project', transport: 'stdio', command: 'npx', from: 'monorepo' },
    { name: 'shared', scope: 'project', transport: 'stdio', command: 'near' },
  ])
  // The home folder's name is the username: `~`, there too.
  const atHome = join(process.env.HOME!, 'somewhere')
  mkdirSync(atHome, { recursive: true })
  writeFileSync(join(process.env.HOME!, '.mcp.json'), JSON.stringify({ mcpServers: { 'from-home': {} } }))
  try {
    const home = await sessionMcp(atHome, configFile('session-at-home', plantedConfig({}, {})), dir('session-parents-claude'))
    assert.deepEqual(home.servers, [{ name: 'from-home', scope: 'project', transport: null, from: '~' }])
  } finally {
    rmSync(join(process.env.HOME!, '.mcp.json'))
  }
})

test('local scope is that folder’s entry, and the repository’s root that Claude Code files it under, also from a worktree', async () => {
  const repo = project('local-repo')
  mkdirSync(join(repo, '.git', 'worktrees', 'wt'), { recursive: true })
  const below = join(repo, 'sub')
  mkdirSync(below)
  const file = configFile('session-local', plantedConfig({
    [repo]: plantedEntry({ 'at-root': stdioServer('Root', 'rooted') }),
    [below]: plantedEntry({ 'at-folder': stdioServer('Folder', 'foldered') }),
  }, {}))
  const none = dir('session-local-claude')
  assert.deepEqual((await sessionMcp(repo, file, none)).servers, [{ name: 'at-root', scope: 'local', transport: 'stdio', command: 'rooted' }])
  assert.deepEqual((await sessionMcp(below, file, none)).servers, [
    { name: 'at-folder', scope: 'local', transport: 'stdio', command: 'foldered' },
    { name: 'at-root', scope: 'local', transport: 'stdio', command: 'rooted', from: 'local-repo' },
  ])
  // A linked worktree: its .git is a file pointing into the main repository's.
  const worktree = project('local-worktree')
  writeFileSync(join(worktree, '.git'), `gitdir: ${join(repo, '.git', 'worktrees', 'wt')}\n`)
  writeFileSync(join(repo, '.git', 'worktrees', 'wt', 'commondir'), '../..\n')
  assert.deepEqual((await sessionMcp(worktree, file, none)).servers, [{ name: 'at-root', scope: 'local', transport: 'stdio', command: 'rooted', from: 'local-repo' }])
  // Not in a repository: that folder's entry alone.
  const loose = project('local-loose')
  assert.deepEqual((await sessionMcp(loose, file, none)).servers, [])
})

test('what can’t be said is left out: a secret or a username in a command, an odd URL, an unknown type', async () => {
  const user = basename(process.env.HOME!)
  const cwd = project('session-odd', {
    mcpServers: {
      'secret-command': { command: `/opt/${secret('CommandName')}` },
      'args-in-command': { command: `npx --token ${secret('InCommand')}` },
      'user-command': { command: `/opt/bin/${user}-tool` },
      'user-as-command': { command: `/opt/${user}` },
      'var-command': { command: '${PLANTEDVAR}' },
      'no-command': { type: 'stdio' },
      'number-command': { command: 5 },
      'secret-host': { type: 'http', url: `https://${secret('Host')}.example.com/` },
      'var-host': { type: 'http', url: 'https://${PLANTEDHOST}/mcp' },
      'file-url': { type: 'http', url: 'file:///home/PLANTEDUSER/x' },
      'bad-url': { type: 'sse', url: `not a url ${secret('Url')}` },
      'no-url': { type: 'http' },
      'ipv6': { type: 'http', url: 'http://[::1]:8123/PLANTEDPATH' },
      'a-socket': { type: 'ws', url: 'wss://sock.example.com/PLANTEDPATH' },
      'ide-type': { type: 'sse-ide', url: 'http://PLANTEDIDE.example.com/', ideName: 'PLANTEDIDE' },
      'sdk-type': { type: 'sdk', name: 'PLANTEDSDK' },
      'odd-type': { type: secret('Type'), url: 'https://PLANTEDODD.example.com/' },
      'not-a-table': secret('NotATable'),
      [`bad name ${secret('Name')}`]: stdioServer('BadName'),
    },
  })
  const r = await sessionMcp(cwd, configFile('session-odd', plantedConfig({}, {})), dir('session-odd-claude'))
  assertNothingPlanted(JSON.stringify(r))
  assert.equal(r.hidden, 1)
  const byName = Object.fromEntries(r.servers.map(({ name, scope: _scope, ...rest }) => [name, rest]))
  assert.deepEqual(byName, {
    'secret-command': { transport: 'stdio' },
    'args-in-command': { transport: 'stdio' },
    'user-command': { transport: 'stdio' },
    'user-as-command': { transport: 'stdio' },
    'var-command': { transport: 'stdio' },
    'no-command': { transport: 'stdio' },
    'number-command': { transport: null },
    'secret-host': { transport: 'http' },
    'var-host': { transport: 'http' },
    'file-url': { transport: 'http' },
    'bad-url': { transport: 'sse' },
    'no-url': { transport: 'http' },
    ipv6: { transport: 'http', host: '[::1]' },
    'a-socket': { transport: 'ws', host: 'sock.example.com' },
    'ide-type': { transport: null },
    'sdk-type': { transport: null },
    'odd-type': { transport: null },
    'not-a-table': { transport: null },
  })
})

test('a session’s .mcp.json as a link, a folder, a pipe or malformed is skipped; ~/.claude.json’s trouble is a state', async () => {
  const outside = join(dir('session-outside'), 'mcp.json')
  writeFileSync(outside, JSON.stringify({ mcpServers: { 'PLANTEDOUTSIDE': stdioServer('Outside') } }))
  const none = dir('session-trouble-claude')
  const noServers = configFile('session-trouble', plantedConfig({}, {}))
  const linked = project('session-linked')
  symlinkSync(outside, join(linked, '.mcp.json'))
  const folder = project('session-folder')
  mkdirSync(join(folder, '.mcp.json'))
  const pipe = project('session-pipe')
  execFileSync('mkfifo', [join(pipe, '.mcp.json')])
  const broken = project('session-broken', `{ "mcpServers": { "PLANTEDBROKEN": "${secret('Quoted')}"`)
  const big = project('session-big', `{"mcpServers":{"too-big":{}},"pad":"${'x'.repeat(256 * 1024)}"}`)
  for (const cwd of [linked, folder, pipe, broken, big]) {
    const r = await sessionMcp(cwd, noServers, none)
    assert.deepEqual(r, { state: 'ok', servers: [], forAllProjects: 0, hidden: 0, foldersNotRead: 0 }, cwd)
  }
  // Its own .mcp.json still counts with ~/.claude.json gone or broken, and the file isn't quoted.
  const cwd = project('session-kept', { mcpServers: { kept: stdioServer('Kept', 'kept') } })
  const kept = [{ name: 'kept', scope: 'project', transport: 'stdio', command: 'kept' }]
  assert.deepEqual(await sessionMcp(cwd, join(none, 'not-there.json'), none), { state: 'missing', servers: kept, forAllProjects: 0, hidden: 0, foldersNotRead: 0 })
  const bad = await sessionMcp(cwd, configFile('session-bad', `{"userID":"PLANTEDUSERID","x":"${secret('Quoted')}"`), none)
  assert.deepEqual(bad, { state: 'malformed', servers: kept, forAllProjects: 0, hidden: 0, foldersNotRead: 0 })
  // A count of the user's takes settings.json's names too.
  const settings = dir('session-settings')
  writeFileSync(join(settings, 'settings.json'), JSON.stringify({ mcpServers: { 'from-settings': {}, both: {} } }))
  assert.equal((await sessionMcp(cwd, configFile('session-count', { mcpServers: { both: {}, 'for-all': {} } }), settings)).forAllProjects, 3)
})

test('the route: a paired phone’s, for a session that’s there; nothing planted in the raw body; Codex’s has none', async () => {
  const cwd = project('session-routed', { mcpServers: { docs: stdioServer('Docs'), remote: httpServer('Remote') } })
  upsertSession('sess-mcp', { cwd, origin: 'hypernova', title: 'MCP', state: 'idle' })
  writeFileSync(join(CLAUDE, '.claude.json'), JSON.stringify(plantedConfig({ [cwd]: plantedEntry({ db: stdioServer('Db', 'uvx') }) })))
  const url = `http://127.0.0.1:${API}/v1/sessions/sess-mcp/mcp`
  const auth = { headers: { authorization: `Bearer ${TOKEN}` } }
  try {
    const r = await fetch(url, auth)
    assert.equal(r.status, 200)
    const text = await r.text()
    assertNothingPlanted(text)
    assert.ok(!text.includes(scratch), `a path left: ${text}`)
    for (const leak of ['--token', '--dir', 'Bearer', 'args', 'env"', 'url', 'headers', '8443']) assert.ok(!text.includes(leak), `${leak} left: ${text}`)
    const body = JSON.parse(text)
    assert.deepEqual(body.servers.map((s: { name: string }) => s.name), ['db', 'docs', 'remote'])
    const allowed = new Set(['state', 'servers', 'forAllProjects', 'hidden', 'foldersNotRead', 'name', 'scope', 'transport', 'command', 'host', 'from'])
    for (const k of everyKey(body)) assert.ok(allowed.has(k), `an unexpected key: ${k}`)

    assert.equal((await fetch(url)).status, 401)
    assert.equal((await fetch(url, { headers: { authorization: 'Bearer not-a-token' } })).status, 401)
    const local = await fetch(`http://127.0.0.1:${LOCAL}/v1/sessions/sess-mcp/mcp`)
    assert.ok(local.status === 401 || local.status === 404, `the local API answered ${local.status}`)
    const gone = await fetch(`http://127.0.0.1:${API}/v1/sessions/no-such-session/mcp`, auth)
    assert.equal(gone.status, 404)
    assert.equal((await gone.json()).error.code, 'no_session')
    for (const method of ['POST', 'PUT', 'PATCH', 'DELETE']) {
      const w = await fetch(url, { method, headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' }, body: method === 'DELETE' ? undefined : '{}' })
      assert.notEqual(w.status, 200, method)
    }
  } finally {
    rmSync(join(CLAUDE, '.claude.json'))
  }
  saveThread({ id: 'thr-mcp', cwd, createdAt: Date.now() } as never)
  upsertSession('thr-mcp', { cwd, origin: 'hypernova', title: 'Codex', state: 'idle' })
  try {
    const codex = await fetch(`http://127.0.0.1:${API}/v1/sessions/thr-mcp/mcp`, auth)
    assert.equal(codex.status, 409)
    assert.equal((await codex.json()).error.code, 'not_claude')
  } finally {
    removeThread('thr-mcp')
  }
})

// ── The TOML reader ──────────────────────────────────────────────────────

function keys(toml: string): [string, unknown][] {
  const out: [string, unknown][] = []
  scanToml(toml, (path, v) => out.push([path.join(' / '), v]))
  return out
}

test('the reader: top-level keys, tables, dotted keys, quoted parts, comments, CRLF, a BOM', () => {
  assert.deepEqual(keys('\ufeff# a comment\r\na = "x" # after\r\nb.c = true\r\n[t."d.e" . f]\r\ng = false\r\n[[arr]]\nh = 1\n[[arr]]\nh = 2\n'), [
    ['a', 'x'],
    ['b / c', true],
    ['t / d.e / f', undefined],
    ['t / d.e / f / g', false],
    ['arr', undefined],
    ['arr / h', OTHER],
    ['arr', undefined],
    ['arr / h', OTHER],
  ])
})

test('the reader: strings, each kind, with their escapes; a line inside one is never a key', () => {
  assert.deepEqual(keys(String.raw`a = "q\"\t\u00e9\\" `), [['a', 'q"\té\\']])
  assert.deepEqual(keys(`a = 'C:\\no\\escape'`), [['a', 'C:\\no\\escape']])
  assert.deepEqual(keys('a = """\nline\nb = "not a key"\n[neither]\n"""\nc = 1\n'), [['a', 'line\nb = "not a key"\n[neither]\n'], ['c', OTHER]])
  assert.deepEqual(keys(`a = '''\nb = 1\n'''`), [['a', 'b = 1\n']])
  assert.deepEqual(keys('a = """two\\\n   joined"""'), [['a', 'twojoined']])
  // Up to two quotes may end a multi-line string's text.
  assert.deepEqual(keys('a = """x"""""'), [['a', 'x""']])
})

test('the reader: arrays and inline tables over lines; a table inside an array is no key', () => {
  assert.deepEqual(keys('a = [\n  1, # one\n  "[b]",\n  { c = "d" },\n  [2, 3],\n]\ne = "f"\n'), [['a', OTHER], ['e', 'f']])
  assert.deepEqual(keys('s = { a = { c = "x" }, "b.c" = true }\n'), [['s / a / c', 'x'], ['s / a', OTHER], ['s / b.c', true], ['s', OTHER]])
})

test('the reader: numbers and dates are OTHER, whatever’s not TOML throws without quoting the file', () => {
  for (const v of ['1', '-1_000', '1.5e3', '0xFF', '0o7', '0b1', 'inf', 'nan', '1979-05-27', '1979-05-27T07:32:00Z', '1979-05-27 07:32:00', '07:32:00']) assert.deepEqual(keys(`a = ${v}\n`), [['a', OTHER]], v)
  for (const bad of ['a', 'a =', 'a = b', 'a = "x" b', '[', '[a', '[[a]', 'a = "', 'a = \'', 'a = """x', 'a = [1', 'a = {b', 'a = "\\q"', 'a = "\\u12"', '= 1', 'a.b', 'a = 1 2', '[a] b = 1', 'a = [1 2]', 'a = """x""""""']) {
    assert.throws(() => keys(bad), (e: Error) => e instanceof TomlError && e.message === 'not TOML', bad)
  }
  // Too deep: refused, not a stack overflow.
  assert.throws(() => keys(`a = ${'['.repeat(100_000)}`), TomlError)
})
