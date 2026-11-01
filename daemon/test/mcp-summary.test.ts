// An MCP call's summary on its tool event: what it was called with, not its name again
// (docs/PROTOCOL.md, "Reading transcripts"). Approvals keep toolSummary's form.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-test-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
after(() => rmSync(scratch, { recursive: true, force: true }))

const { eventSummary, mcpArgument, newToolEvent, toolSummary } = await import('../src/normalize.ts')
const HA = 'mcp__home-assistant__ha_get_state'

test('an MCP call is summed up by its argument, not its name', () => {
  assert.equal(newToolEvent('t1', HA, { entity_id: 'light.kitchen' }, 0).summary, 'light.kitchen')
  assert.equal(eventSummary('mcp__home-assistant__ha_search', { limit: 5, query: 'kitchen lights' }), 'kitchen lights')
  assert.equal(eventSummary('mcp__fetch__get', { url: 'https://example.com/a' }), 'https://example.com/a')
})

test('the named arguments go first, in their order, then the first short string', () => {
  assert.equal(mcpArgument({ op: 'summary', name: 'Kitchen', entity_id: 'light.kitchen' }), 'light.kitchen')
  assert.equal(mcpArgument({ id: 'abc', path: 'flows/main' }), 'flows/main')
  assert.equal(mcpArgument({ limit: 3, op: 'summary', mode: 'full' }), 'summary')
  // A number or a list where a name is expected still names it.
  assert.equal(mcpArgument({ id: 42 }), '42')
  assert.equal(mcpArgument({ entity_id: ['light.a', 'light.b', 'light.c'] }), 'light.a +2')
  // Not a number or a flag elsewhere.
  assert.equal(mcpArgument({ count: 3, force: true }), null)
})

test('with nothing worth naming, the server stands in', () => {
  assert.equal(eventSummary(HA, {}), 'home-assistant')
  assert.equal(eventSummary('mcp__nodered__nodered_session', { force: true }), 'nodered')
})

test('secrets never reach the summary', () => {
  assert.equal(mcpArgument({ token: 'abc123def456', api_key: 'k', password: 'hunter2', Authorization: 'Bearer x', cookie: 'sid=1' }), null)
  assert.equal(mcpArgument({ client_secret: 'shh', label: 'Office' }), 'Office')
  assert.equal(eventSummary('mcp__x__login', { apiKey: 'sk-ant-api03-abcdefghijklmnop', user: 'leo' }), 'leo')
  // A credential inside an argument that is shown is masked as titles mask them.
  assert.equal(mcpArgument({ url: 'https://leo:hunter2pass@example.com/x' }), 'https://•••@example.com/x')
  assert.equal(mcpArgument({ query: 'where is TOKEN=ghp_abcdefghijklmnopqrstuvwxyz0123456789 set' }), 'where is TOKEN=••• set')
  // An argument that is only a key is left out, not shown as dots.
  assert.equal(mcpArgument({ query: 'ghp_abcdefghijklmnopqrstuvwxyz0123456789', name: 'Hall' }), 'Hall')
})

test('blobs are left out and long text is cut', () => {
  const b64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk'
  assert.equal(mcpArgument({ image: b64, name: 'snapshot' }), 'snapshot')
  assert.equal(mcpArgument({ data: 'data:image/png;base64,iVBORw0KGgo=' }), null)
  assert.equal(mcpArgument({ sha: '3f786850e387550fdab836ed7e6dc881de23001b9', title: 'Fix it' }), 'Fix it')
  // A long path is a path, not a blob.
  assert.equal(mcpArgument({ path: '/media/cubxi/docker/projects/hypernova/daemon/src' }), '/media/cubxi/docker/projects/hypernova/daemon/src')
  const long = mcpArgument({ query: 'x'.repeat(300) })!
  assert.equal(long.length, 80)
  assert.ok(long.endsWith('…'))
  // A named argument keeps its first line, spaces tidied; other strings must be short and on one line.
  assert.equal(mcpArgument({ query: '  first   line\nsecond' }), 'first line')
  assert.equal(mcpArgument({ template: '{{ states("a") }}\n{{ states("b") }}', op: 'eval' }), 'eval')
  assert.equal(mcpArgument({ note: 'y'.repeat(200) }), null)
})

test('built-in tools and approvals keep their summaries', () => {
  assert.equal(eventSummary('Read', { file_path: '/a/b/c.kt' }), 'b/c.kt')
  assert.equal(toolSummary(HA, { entity_id: 'light.kitchen' }), 'home-assistant · ha_get_state')
})
