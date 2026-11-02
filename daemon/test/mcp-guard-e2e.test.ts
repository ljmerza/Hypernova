// Opening a list must not run a folder's code: the "/" list, the model list and an MCP status against a real Claude Code, in a
// throwaway home, with project servers that leave a marker file when they start. No prompt is sent. The one test that needs
// no Claude Code checks the flags; the rest are skipped where it isn't installed (CI).
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'

const realClaude = [process.env.HN_E2E_CLAUDE, join(homedir(), '.local/bin/claude')].find((p) => p && existsSync(p))
const skip = realClaude ? false : 'Claude Code isn’t installed here'

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-mcp-guard-')))
const HOME = join(scratch, 'home')
const CLAUDE = join(scratch, 'claude')
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = CLAUDE
process.env.CODEX_HOME = join(scratch, 'codex')
process.env.HOME = HOME
process.env.ZDOTDIR = join(scratch, 'zdot')
process.env.HYPERNOVA_WSL = '0'
process.env.CLAUDE_BIN = realClaude ? realpathSync(realClaude) : join(scratch, 'none')
for (const d of [HOME, CLAUDE]) mkdirSync(d, { recursive: true })

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

const { startServers } = await import('../src/http.ts')
const { addDevice, settings } = await import('../src/store.ts')
const { guardArgs } = await import('../src/mcp-probe.ts')
const { folderId } = await import('../src/mcp-view.ts')
const { forgetModelLists } = await import('../src/models.ts')
settings.lanAccess = false
const TOKEN = 'mcp-guard-e2e-test-device-token-0123456789'
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

const get = async (path: string) => {
  const r = await fetch(`http://127.0.0.1:${API}${path}`, { headers: { authorization: `Bearer ${TOKEN}` } })
  const body = (await r.json()) as any
  assert.equal(r.status, 200, JSON.stringify(body))
  return body
}

// A server that says it has one prompt, and leaves a marker named for it when it starts.
const MARKS = join(scratch, 'marks')
mkdirSync(MARKS)
const SERVER = join(scratch, 'tiny.mjs')
writeFileSync(SERVER, `
import { writeFileSync } from 'node:fs'
import { createInterface } from 'node:readline'
writeFileSync(${JSON.stringify(MARKS)} + '/' + process.argv[2], '')
const send = (o) => process.stdout.write(JSON.stringify(o) + '\\n')
createInterface({ input: process.stdin }).on('line', (l) => {
  const m = JSON.parse(l)
  if (m.method === 'initialize') send({ jsonrpc: '2.0', id: m.id, result: { protocolVersion: m.params.protocolVersion, capabilities: { tools: {}, prompts: {} }, serverInfo: { name: 'tiny', version: '1' } } })
  else if (m.method === 'prompts/list') send({ jsonrpc: '2.0', id: m.id, result: { prompts: [{ name: 'hello', description: 'Says hello.' }] } })
  else if (m.method === 'tools/list') send({ jsonrpc: '2.0', id: m.id, result: { tools: [] } })
  else if (m.id !== undefined) send({ jsonrpc: '2.0', id: m.id, result: {} })
})
`)
const entry = (name: string) => ({ command: process.execPath, args: [SERVER, name] })
const mcpJson = (...names: string[]) => JSON.stringify({ mcpServers: Object.fromEntries(names.map((n) => [n, entry(n)])) })
const started = (name: string) => existsSync(join(MARKS, name))
/** Long enough for a server that was going to start to have left its marker: the same servers do in well under a second. */
const settle = () => new Promise((r) => setTimeout(r, 2000))
const folder = (name: string, json?: string) => {
  const dir = join(scratch, name)
  mkdirSync(dir, { recursive: true })
  if (json !== undefined) writeFileSync(join(dir, '.mcp.json'), json)
  return dir
}
const commandsIn = async (dir: string) => (await get(`/v1/commands?project=${encodeURIComponent(dir)}`)).commands as { name: string; builtin: boolean }[]
const hasOwn = (list: { name: string }[], name: string) => list.some((c) => c.name === name)

test('the flags name what isn’t approved, and read no .mcp.json whose names can’t be known', async () => {
  writeFileSync(join(CLAUDE, 'settings.json'), JSON.stringify({ enabledMcpjsonServers: ['flags-yes'], disabledMcpjsonServers: ['flags-off'] }))
  const plain = await guardArgs(folder('flags-plain', mcpJson('flags-yes', 'flags-no', 'flags-off')))
  assert.deepEqual(JSON.parse(plain.settings!), { disableAllHooks: true, apiKeyHelper: '', disabledMcpjsonServers: ['flags-no', 'flags-off'] })
  assert.equal('strict-mcp-config' in plain, false)

  const none = await guardArgs(folder('flags-none'))
  assert.deepEqual(JSON.parse(none.settings!), { disableAllHooks: true, apiKeyHelper: '' })
  assert.equal('strict-mcp-config' in none, false)

  const big = await guardArgs(folder('flags-big', JSON.stringify({ mcpServers: { x: entry('x') }, pad: 'x'.repeat(300 * 1024) })))
  assert.equal(big['strict-mcp-config'], null)
  assert.ok('strict-mcp-config' in big)

  const broken = await guardArgs(folder('flags-broken', '{ not json'))
  assert.ok('strict-mcp-config' in broken)

  const linked = folder('flags-link')
  symlinkSync(join(folder('flags-plain'), '.mcp.json'), join(linked, '.mcp.json'))
  assert.ok('strict-mcp-config' in (await guardArgs(linked)))

  // Through a folder that's a link: it's the real path's parents that count.
  const real = folder('flags-parent', mcpJson('flags-above'))
  mkdirSync(join(real, 'sub'))
  symlinkSync(join(real, 'sub'), join(scratch, 'flags-cwd'))
  assert.deepEqual(JSON.parse((await guardArgs(join(scratch, 'flags-cwd'))).settings!).disabledMcpjsonServers, ['flags-above'])
  // The user's own helper stays theirs, whatever a folder names.
  writeFileSync(join(CLAUDE, 'settings.json'), JSON.stringify({ apiKeyHelper: '/home/me/key.sh' }))
  assert.equal(JSON.parse((await guardArgs(folder('flags-none'))).settings!).apiKeyHelper, '/home/me/key.sh')
  writeFileSync(join(CLAUDE, 'settings.json'), '{}')
})

test('the "/" list starts an approved project server and lists its prompt, and never starts one nobody approved', { skip, timeout: 120_000 }, async () => {
  writeFileSync(join(CLAUDE, 'settings.json'), JSON.stringify({ enabledMcpjsonServers: ['good'] }))
  const dir = folder('mixed', mcpJson('good', 'bad'))
  const list = await commandsIn(dir)
  await settle()
  assert.equal(started('bad'), false, 'opening the list started a server nobody approved')
  assert.equal(started('good'), true, 'an approved server didn’t start')
  assert.ok(hasOwn(list, 'good:hello (MCP)'), `the approved server's prompt isn't listed: ${list.map((c) => c.name).join(' ')}`)
  assert.ok(!list.some((c) => c.name.startsWith('bad:')))
  assert.ok(hasOwn(list, 'compact'), 'Claude Code’s own commands are still there')
  writeFileSync(join(CLAUDE, 'settings.json'), '{}')
})

test('the "/" list: not from a parent’s .mcp.json, one through a linked folder, a linked file or a big one', { skip, timeout: 120_000 }, async () => {
  const above = folder('above', mcpJson('above'))
  mkdirSync(join(above, 'sub'))
  await commandsIn(join(above, 'sub'))

  const through = folder('through', mcpJson('through'))
  mkdirSync(join(through, 'sub'))
  symlinkSync(join(through, 'sub'), join(scratch, 'through-link'))
  await commandsIn(join(scratch, 'through-link'))

  writeFileSync(join(scratch, 'elsewhere.json'), mcpJson('linkedfile'))
  const linked = folder('linked')
  symlinkSync(join(scratch, 'elsewhere.json'), join(linked, '.mcp.json'))
  assert.ok(hasOwn(await commandsIn(linked), 'compact'))

  const big = folder('big', JSON.stringify({ mcpServers: { big: entry('big') }, pad: 'x'.repeat(300 * 1024) }))
  assert.ok(hasOwn(await commandsIn(big), 'compact'))

  await settle()
  for (const name of ['above', 'through', 'linkedfile', 'big']) assert.equal(started(name), false, `${name} started`)
})

const helperIn = (name: string, file: 'settings.json' | 'settings.local.json' = 'settings.json') => {
  const dir = folder(name)
  mkdirSync(join(dir, '.claude'), { recursive: true })
  writeFileSync(join(dir, '.claude', file), JSON.stringify({ apiKeyHelper: `touch ${join(MARKS, name)}` }))
  return dir
}

test('a folder’s apiKeyHelper doesn’t run for the "/" list or a status, and the list still answers with no way to sign in at all', { skip, timeout: 120_000 }, async () => {
  const shared = helperIn('helper-project')
  const local = helperIn('helper-local', 'settings.local.json')
  assert.ok(hasOwn(await commandsIn(shared), 'compact'))
  assert.ok(hasOwn(await commandsIn(local), 'compact'))
  const config = join(CLAUDE, '.claude.json')
  const status = helperIn('helper-status')
  writeFileSync(config, JSON.stringify({ ...(existsSync(config) ? JSON.parse(readFileSync(config, 'utf8')) : {}), projects: { [status]: {} } }))
  await get(`/v1/agents/mcp/status?agent=claude&fresh=1&folder=${folderId(status)}`)
  await settle()
  for (const name of ['helper-project', 'helper-local', 'helper-status']) assert.equal(started(name), false, `${name}’s helper ran`)
})

test('the user’s own apiKeyHelper still runs for the "/" list, and a folder’s doesn’t take its place', { skip, timeout: 120_000 }, async () => {
  writeFileSync(join(CLAUDE, 'settings.json'), JSON.stringify({ apiKeyHelper: `touch ${join(MARKS, 'helper-own')}` }))
  const list = await commandsIn(helperIn('helper-beside-own'))
  await settle()
  writeFileSync(join(CLAUDE, 'settings.json'), '{}')
  assert.ok(hasOwn(list, 'compact'))
  assert.equal(started('helper-own'), true, 'the user’s own helper didn’t run')
  assert.equal(started('helper-beside-own'), false, 'the folder’s helper ran')
})

test('a status doesn’t start one through a linked file or a big one either', { skip, timeout: 120_000 }, async () => {
  writeFileSync(join(scratch, 'elsewhere2.json'), mcpJson('statuslink'))
  const linked = folder('status-linked')
  symlinkSync(join(scratch, 'elsewhere2.json'), join(linked, '.mcp.json'))
  const big = folder('status-big', JSON.stringify({ mcpServers: { statusbig: entry('statusbig') }, pad: 'x'.repeat(300 * 1024) }))
  const config = join(CLAUDE, '.claude.json')
  writeFileSync(config, JSON.stringify({ ...(existsSync(config) ? JSON.parse(readFileSync(config, 'utf8')) : {}), projects: { [linked]: {}, [big]: {} } }))
  for (const dir of [linked, big]) await get(`/v1/agents/mcp/status?agent=claude&fresh=1&folder=${folderId(dir)}`)
  await settle()
  for (const name of ['statuslink', 'statusbig']) assert.equal(started(name), false, `${name} started`)
})

test('the model list asks in the home folder, and doesn’t start the .mcp.json there', { skip, timeout: 120_000 }, async () => {
  writeFileSync(join(HOME, '.mcp.json'), mcpJson('athome'))
  // The lists above brought the model list along: this has to be the model list's own ask.
  forgetModelLists()
  const { providers } = await get('/v1/models')
  await settle()
  assert.equal(started('athome'), false, 'the model list started a server nobody approved')
  assert.equal(providers.find((p: any) => p.source === 'claude')?.source, 'claude', JSON.stringify(providers))
  rmSync(join(HOME, '.mcp.json'))
})
