// What a form may say (mcp-edit.ts), checked on the server whatever the app sends. Nothing here runs Claude Code:
// mcp-e2e.test.ts does, where it's installed.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-mcp-edit-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')

const { changesWhatRuns, cliWords, commandLine, formConfig, loginFinish, McpRefused, scopeOf } = await import('../src/mcp-edit.ts')
const { MASK } = await import('../src/mcp-view.ts')

const refused = (f: () => unknown, code: string) => assert.throws(f, (e: any) => e instanceof McpRefused && e.code === code, code)
const stdio = { name: 'tiny', transport: 'stdio', command: 'node', args: ['a.js'] }

test('a name, a type, a command and arguments are checked here, not only in the app', () => {
  assert.deepEqual(formConfig(stdio).config, { type: 'stdio', command: 'node', args: ['a.js'] })
  for (const name of ['', '-rm', '../x', 'a b', 'a.b', 'x'.repeat(65), 7, undefined]) refused(() => formConfig({ ...stdio, name }), 'bad_name')
  for (const transport of ['ws', 'claudeai-proxy', 'sdk', undefined]) refused(() => formConfig({ ...stdio, transport }), 'bad_transport')
  for (const command of ['', '   ', 'a\nb', 'x'.repeat(1025), 3]) refused(() => formConfig({ ...stdio, command }), 'bad_command')
  for (const args of ['x', [1], ['a\nb'], Array(65).fill('a')]) refused(() => formConfig({ ...stdio, args }), 'bad_args')
})

test('env and headers: names by their rule, values with no line breaks; keep needs something to keep', () => {
  assert.deepEqual(formConfig({ ...stdio, env: [{ name: 'A_B', value: 'x' }] }).config.env, { A_B: 'x' })
  for (const name of ['1A', 'A B', 'A=B', '', 3]) refused(() => formConfig({ ...stdio, env: [{ name, value: 'x' }] }), 'bad_pair_name')
  refused(() => formConfig({ ...stdio, env: [{ name: 'A', value: 'x\ny' }] }), 'bad_pair_value')
  refused(() => formConfig({ ...stdio, env: 'A=B' }), 'bad_pairs')
  refused(() => formConfig({ ...stdio, env: [{ name: 'A', keep: true }] }), 'nothing_to_keep')
  const web = { name: 'web', transport: 'http', url: 'https://x.example/m' }
  assert.deepEqual(formConfig({ ...web, headers: [{ name: 'Authorization', value: 'Bearer z' }] }).config.headers, { Authorization: 'Bearer z' })
  refused(() => formConfig({ ...web, headers: [{ name: 'A B', value: 'x' }] }), 'bad_pair_name')
  refused(() => formConfig({ ...web, headers: [{ name: 'A', value: 'x\r\nEvil: y' }] }), 'bad_pair_value')
  assert.deepEqual(formConfig({ ...stdio, env: [{ name: 'A', value: 'sec' }] }).secrets, ['sec'])
})

test('a URL is http or https; ${VAR} is Claude Code\'s to expand; OAuth only with an address, and its secret goes separately', () => {
  const web = { name: 'web', transport: 'sse' }
  for (const url of ['file:///etc/passwd', 'javascript:alert(1)', 'not a url', 3]) refused(() => formConfig({ ...web, url }), 'bad_url')
  assert.equal(formConfig({ ...web, url: '${BASE}/m' }).config.url, '${BASE}/m')
  const f = formConfig({ ...web, url: 'https://u:pw@x.example/m', oauth: { clientId: 'cid', callbackPort: 3118, clientSecret: 'shh' } })
  assert.deepEqual(f.config.oauth, { clientId: 'cid', callbackPort: 3118 })
  assert.equal(f.clientSecret, 'shh')
  assert.ok(f.secrets.includes('shh') && f.secrets.includes('pw'))
  assert.ok(!JSON.stringify(f.config).includes('shh'))
  refused(() => formConfig({ ...web, url: 'https://x.example', oauth: { callbackPort: 70000 } }), 'bad_oauth')
  refused(() => formConfig({ ...stdio, oauth: { clientId: 'c' } }), 'bad_oauth')
  refused(() => formConfig({ ...stdio, timeout: 5 }), 'bad_timeout')
})

test('editing: a kept value and a mask stand for the real ones; other fields carry; a new type starts clean', () => {
  const was = { type: 'stdio', command: 'node', args: ['a.js', '--token', 'real'], env: { K: 'real-env' }, alwaysLoad: true, timeout: 5000 }
  const f = formConfig({ ...stdio, args: ['a.js', 'new', '--token', MASK], env: [{ name: 'K', keep: true }] }, was)
  assert.deepEqual(f.config, { type: 'stdio', command: 'node', args: ['a.js', 'new', '--token', 'real'], env: { K: 'real-env' }, alwaysLoad: true, timeout: 5000 })
  assert.deepEqual(f.secrets, [])
  // A variable left out goes, and three dots typed by hand aren't a value.
  assert.equal(formConfig({ ...stdio, env: [] }, was).config.env, undefined)
  refused(() => formConfig({ ...stdio, args: [MASK, MASK, MASK, MASK] }, was), 'masked_unknown')
  refused(() => formConfig({ ...stdio, command: MASK }), 'masked_unknown')
  const web = { type: 'http', url: 'https://x.example/m?key=real', headers: { Authorization: 'real-h' }, oauth: { clientId: 'c' } }
  const g = formConfig({ name: 'web', transport: 'http', url: `https://x.example/m?key=${MASK}`, headers: [{ name: 'Authorization', keep: true }] }, web)
  assert.equal(g.config.url, 'https://x.example/m?key=real')
  assert.equal((g.config.headers as any).Authorization, 'real-h')
  assert.deepEqual(g.config.oauth, { clientId: 'c' })
  refused(() => formConfig({ name: 'web', transport: 'http', url: `https://evil.example/?key=${MASK}` }, web), 'masked_unknown')
  // A secret can't be walked to another type by keeping it.
  refused(() => formConfig({ name: 'web', transport: 'stdio', command: 'x', env: [{ name: 'Authorization', keep: true }] }, web), 'nothing_to_keep')
  assert.deepEqual(formConfig({ name: 'web', transport: 'stdio', command: 'x' }, web).config, { type: 'stdio', command: 'x' })
})

test('what counts as changing what runs', () => {
  const a = { type: 'stdio', command: 'node', args: ['a'], env: { K: 'v' } }
  assert.equal(changesWhatRuns(null, a), true)
  assert.equal(changesWhatRuns(a, { ...a }), false)
  assert.equal(changesWhatRuns(a, { ...a, timeout: 5000 }), false)
  assert.equal(changesWhatRuns(a, { ...a, command: 'x' }), true)
  assert.equal(changesWhatRuns(a, { ...a, args: ['b'] }), true)
  assert.equal(changesWhatRuns(a, { ...a, env: { K: 'w' } }), true)
  assert.equal(changesWhatRuns({ type: 'http', url: 'u' }, a), true)
  assert.equal(changesWhatRuns(a, { type: 'http', url: 'u' }), false)
  assert.equal(changesWhatRuns(null, { type: 'http', url: 'u' }), false)
})

test('a command\'s words: what was typed and what looks secret are gone; a failure says one line', () => {
  const said = cliWords({ code: 1, stdout: '\u001b[31mError: MCP server tiny already exists\u001b[0m with sk-ant-api03-PLANTEDabcdefghijklmnop0123456789\nmore', stderr: '' }, ['the-secret'])!
  assert.ok(said.startsWith('Error: MCP server tiny already exists'))
  assert.ok(!said.includes('PLANTED'))
  assert.ok(!cliWords({ code: 1, stdout: '', stderr: 'failed for the-secret at https://u:p@x.example?token=abc' }, ['the-secret'])!.includes('the-secret'))
  assert.ok(!cliWords({ code: 1, stdout: '', stderr: 'failed at https://u:p@x.example?token=abc' })!.includes('abc'))
  assert.equal(cliWords({ code: 1, stdout: '', stderr: '' }), null)
  assert.equal(commandLine({ command: 'node', args: ['a', '--token', 'x'] }), `node a --token ${MASK}`)
  assert.equal(commandLine({ type: 'http', url: 'u' }), null)
  refused(() => scopeOf('settings'), 'bad_scope')
})

test('a sign-in\'s address has to be one a sign-in ends at', async () => {
  await assert.rejects(loginFinish({ login: 'nope', address: 'http://localhost/x' }), (e: any) => e.code === 'no_login')
})
