// Plugins and skills through the phone's routes, with real Claude Code and real Codex, a local marketplace and a throwaway
// home. No prompt is sent. Skipped where they aren't installed (CI). A plugin's hook notes each time it runs, to
// show what does and doesn't run someone else's code.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'

const realClaude = [process.env.HN_E2E_CLAUDE, join(homedir(), '.local/bin/claude')].find((p) => p && existsSync(p))
const nvm = join(homedir(), '.nvm/versions/node')
const realCodex = [process.env.HN_E2E_CODEX, ...(existsSync(nvm) ? readdirSync(nvm).map((v) => join(nvm, v, 'bin/codex')) : [])]
  .filter((p): p is string => !!p && existsSync(p))
  .find((p) => {
    try {
      const v = /(\d+)\.(\d+)/.exec(execFileSync(p, ['--version'], { encoding: 'utf8' }))
      return v ? Number(v[1]) > 0 || Number(v[2]) >= 163 : false
    } catch {
      return false
    }
  })
const skipClaude = realClaude ? false : 'Claude Code isn’t installed here'
const skipCodex = realCodex ? false : 'a current Codex isn’t installed here'

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-extras-e2e-')))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.CODEX_HOME = join(scratch, 'codex')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
process.env.HYPERNOVA_WSL = '0'
process.env.CLAUDE_BIN = realClaude ? realpathSync(realClaude) : join(scratch, 'none')
process.env.HYPERNOVA_CODEX = realCodex ?? join(scratch, 'none')
const CLAUDE = process.env.CLAUDE_CONFIG_DIR
const CODEX = process.env.CODEX_HOME
for (const d of [process.env.HOME, CLAUDE, CODEX]) mkdirSync(d, { recursive: true })

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
const { folderId } = await import('../src/mcp-view.ts')
settings.lanAccess = false
const TOKEN = 'extras-e2e-test-device-token-0123456789abc'
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

const SECRET = 'PLANTEDextrasSecret0123456789abcdefABCDEF'
const call = async (method: string, path: string, body?: unknown) => {
  const r = await fetch(`http://127.0.0.1:${API}${path}`, { method, headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
  const text = await r.text()
  assert.ok(!text.includes(SECRET), `a secret reached the phone from ${path}: ${text}`)
  return { status: r.status, body: JSON.parse(text) as any }
}
const ok = async (p: Promise<{ status: number; body: any }>) => {
  const r = await p
  assert.equal(r.status, 200, JSON.stringify(r.body))
  return r.body
}
const write = (file: string, text: string) => {
  mkdirSync(join(file, '..'), { recursive: true })
  writeFileSync(file, text)
}
const HOOK_LOG = join(scratch, 'hook.log')
const hookRuns = () => (existsSync(HOOK_LOG) ? readFileSync(HOOK_LOG, 'utf8').trim().split('\n').length : 0)

function claudeMarket(): string {
  const mp = join(scratch, 'mp')
  write(join(mp, '.claude-plugin/marketplace.json'), JSON.stringify({ name: 'testmp', owner: { name: 'me' }, plugins: [{ name: 'hello', source: './plugins/hello', description: 'A test plugin', version: '1.0.0' }] }))
  const p = join(mp, 'plugins/hello')
  write(join(p, '.claude-plugin/plugin.json'), JSON.stringify({ name: 'hello', version: '1.0.0', description: 'A test plugin', userConfig: { api_token: { type: 'string', title: 'API token', description: 'A token', sensitive: true }, region: { type: 'string', title: 'Region', description: 'Where' } } }))
  write(join(p, 'skills/greet/SKILL.md'), '---\nname: greet\ndescription: Says hello nicely.\n---\nSay hello.\n')
  write(join(p, 'agents/helper.md'), '---\nname: helper\ndescription: A helper.\n---\nHelp.\n')
  write(join(p, 'hooks/hooks.json'), JSON.stringify({ hooks: { SessionStart: [{ hooks: [{ type: 'command', command: `echo ran >> ${HOOK_LOG}` }] }] } }))
  write(join(p, '.mcp.json'), JSON.stringify({ mcpServers: { hellomcp: { command: 'node', args: ['--version'], env: { HELLO_TOKEN: SECRET } } } }))
  return mp
}

const q = (o: Record<string, string>) => `?${new URLSearchParams(o)}`

test('Claude Code: a marketplace and a plugin, in full, and what runs code when', { skip: skipClaude, timeout: 240_000 }, async () => {
  const mp = claudeMarket()
  const claude = { agent: 'claude' }
  assert.equal((await call('POST', '/v1/agents/plugins/marketplaces/add', { ...claude, source: mp, scope: 'user' })).body.error.code, 'confirm_run')
  for (const source of ['--evil', 'a b', '']) assert.equal((await call('POST', '/v1/agents/plugins/marketplaces/add', { ...claude, source, scope: 'user', confirmRun: true })).body.error.code, 'bad_source', source)
  assert.equal((await ok(call('POST', '/v1/agents/plugins/marketplaces/add', { ...claude, source: mp, scope: 'user', confirmRun: true }))).name, 'testmp')
  const empty = await ok(call('GET', `/v1/agents/plugins${q({ agent: 'claude' })}`))
  assert.deepEqual(empty.plugins, [])
  assert.deepEqual(empty.marketplaces, [{ name: 'testmp', type: 'directory', where: mp }])

  const found = await ok(call('GET', `/v1/agents/plugins/available${q({ agent: 'claude', marketplace: 'testmp' })}`))
  assert.equal(found.plugins[0].id, 'hello@testmp')
  assert.equal(found.plugins[0].description, 'A test plugin')
  assert.equal(found.plugins[0].source, './plugins/hello')

  assert.equal((await call('POST', '/v1/agents/plugins/install', { ...claude, plugin: 'hello@testmp', scope: 'user' })).body.error.code, 'confirm_run')
  await ok(call('POST', '/v1/agents/plugins/install', { ...claude, plugin: 'hello@testmp', scope: 'user', confirmRun: true }))
  const list = await ok(call('GET', `/v1/agents/plugins${q({ agent: 'claude' })}`))
  assert.deepEqual(list.plugins[0], { id: 'hello@testmp', name: 'hello', marketplace: 'testmp', version: '1.0.0', scope: 'user', enabled: true, hasConfig: true, mcpServers: ['hellomcp'] })

  const d = await ok(call('GET', `/v1/agents/plugins/detail${q({ agent: 'claude', plugin: 'hello@testmp' })}`))
  assert.deepEqual([d.skills, d.agents, d.hooks, d.mcpServers], [['greet'], ['helper'], ['SessionStart'], ['hellomcp']])
  assert.ok(d.alwaysOnTokens > 0)
  assert.equal(d.description, 'A test plugin')
  assert.deepEqual(d.options.map((o: any) => [o.key, o.sensitive, o.set]), [['api_token', true, false], ['region', false, false]])

  await ok(call('POST', '/v1/agents/plugins/configure', { ...claude, plugin: 'hello@testmp', values: { region: 'eu', api_token: SECRET } }))
  const after = await ok(call('GET', `/v1/agents/plugins/detail${q({ agent: 'claude', plugin: 'hello@testmp' })}`))
  assert.deepEqual(after.options.map((o: any) => [o.key, o.set, o.value]), [['api_token', true, undefined], ['region', true, 'eu']])

  // None of these has run its hook. Enabling only lets it run at the next session.
  await ok(call('POST', '/v1/agents/plugins/toggle', { ...claude, plugin: 'hello@testmp', scope: 'user', enabled: false }))
  assert.equal((await ok(call('GET', `/v1/agents/plugins${q({ agent: 'claude' })}`))).plugins[0].enabled, false)
  await ok(call('POST', '/v1/agents/plugins/toggle', { ...claude, plugin: 'hello@testmp', scope: 'user', enabled: true }))
  await ok(call('POST', '/v1/agents/plugins/update', { ...claude, plugin: 'hello@testmp', scope: 'user' }))
  await ok(call('POST', '/v1/agents/plugins/marketplaces/update', { ...claude, name: 'testmp' }))
  assert.equal(hookRuns(), 0, 'a change ran the plugin’s hook')

  // Its skill shows; a plugin's is locked to the plugin.
  const app = join(scratch, 'home/app')
  write(join(app, '.claude/skills/own/SKILL.md'), '---\nname: own\ndescription: A skill of this folder.\n---\nDo.\n')
  write(join(CLAUDE, 'skills/mine/SKILL.md'), '---\nname: mine\ndescription: One of mine.\n---\nDo.\n')
  execFileSync('git', ['init', '-q', app])
  const config = join(CLAUDE, '.claude.json')
  writeFileSync(config, JSON.stringify({ ...JSON.parse(readFileSync(config, 'utf8')), projects: { [app]: { hasTrustDialogAccepted: true } } }))
  const folder = folderId(app)
  const skills = async () => Object.fromEntries((await ok(call('GET', `/v1/agents/skills${q({ agent: 'claude', folder })}`))).skills.map((s: any) => [s.name, s])) as Record<string, any>
  const before = await skills()
  assert.equal(before.own.source, 'project')
  assert.equal(before.own.description, 'A skill of this folder.')
  assert.equal(before.mine.source, 'user')
  assert.equal(before.mine.state, 'on')
  assert.ok(before.mine.tokens > 0)
  assert.equal(before['hello:greet'].source, 'plugin')
  assert.equal(before['hello:greet'].lockedBy, 'plugin')
  await ok(call('POST', '/v1/agents/skills', { agent: 'claude', folder, name: 'mine', state: 'off' }))
  assert.equal((await skills()).mine.state, 'off')
  assert.deepEqual(JSON.parse(readFileSync(join(app, '.claude/settings.local.json'), 'utf8')).skillOverrides, { mine: 'off' })
  await ok(call('POST', '/v1/agents/skills', { agent: 'claude', folder, name: 'mine', state: 'name-only' }))
  assert.equal((await skills()).mine.state, 'name-only')
  await ok(call('POST', '/v1/agents/skills', { agent: 'claude', folder, name: 'mine', state: 'user-invocable-only' }))
  assert.equal((await skills()).mine.state, 'user-invocable-only')
  await ok(call('POST', '/v1/agents/skills', { agent: 'claude', folder, name: 'mine', state: 'on' }))
  assert.equal((await skills()).mine.state, 'on')
  assert.equal((await call('POST', '/v1/agents/skills', { agent: 'claude', folder, name: 'mine', state: 'bogus' })).body.error.code, 'bad_state')
  assert.equal((await call('POST', '/v1/agents/skills', { agent: 'claude', folder, name: '../x\n', state: 'off' })).body.error.code, 'bad_skill')

  await ok(call('POST', '/v1/agents/plugins/uninstall', { ...claude, plugin: 'hello@testmp', scope: 'user' }))
  assert.deepEqual((await ok(call('GET', `/v1/agents/plugins${q({ agent: 'claude' })}`))).plugins, [])
  await ok(call('POST', '/v1/agents/plugins/prune', { ...claude, scope: 'user' }))
  await ok(call('POST', '/v1/agents/plugins/marketplaces/remove', { ...claude, name: 'testmp' }))
  assert.deepEqual((await ok(call('GET', `/v1/agents/plugins${q({ agent: 'claude' })}`))).marketplaces, [])
  assert.equal(hookRuns(), 0)
  for (const plugin of ['--evil', 'a b', '../x']) assert.equal((await call('POST', '/v1/agents/plugins/install', { ...claude, plugin, scope: 'user', confirmRun: true })).body.error.code, 'bad_plugin', plugin)
  assert.equal((await call('POST', '/v1/agents/plugins/install', { ...claude, plugin: 'a@b', scope: 'other', confirmRun: true })).body.error.code, 'bad_scope')
})

test('Codex: a marketplace and a plugin, off and on, its skills', { skip: skipCodex, timeout: 180_000 }, async () => {
  const mp = join(scratch, 'cxmp')
  write(join(mp, '.agents/plugins/marketplace.json'), JSON.stringify({ name: 'testmp', interface: { displayName: 'Test' }, plugins: [{ name: 'hello', source: { source: 'local', path: './plugins/hello' }, policy: { installation: 'AVAILABLE', authentication: 'ON_INSTALL' }, category: 'Productivity' }] }))
  write(join(mp, 'plugins/hello/.codex-plugin/plugin.json'), JSON.stringify({ name: 'hello', version: '1.0.0', description: 'A test plugin', skills: './skills/', interface: { displayName: 'Hello', shortDescription: 'Says hi' } }))
  write(join(mp, 'plugins/hello/skills/greet/SKILL.md'), '---\nname: greet\ndescription: Says hello nicely.\n---\nSay hello.\n')
  const codex = { agent: 'codex' }
  assert.equal((await call('POST', '/v1/agents/plugins/marketplaces/add', { ...codex, source: mp })).body.error.code, 'confirm_run')
  await ok(call('POST', '/v1/agents/plugins/marketplaces/add', { ...codex, source: mp, confirmRun: true }))
  const none = await ok(call('GET', `/v1/agents/plugins${q({ agent: 'codex' })}`))
  assert.deepEqual(none.marketplaces, [{ name: 'testmp', type: 'local', where: mp }])
  assert.deepEqual(none.scopes, ['user'])
  const offer = await ok(call('GET', `/v1/agents/plugins/available${q({ agent: 'codex' })}`))
  assert.equal(offer.plugins[0].id, 'hello@testmp')
  assert.equal((await call('POST', '/v1/agents/plugins/install', { ...codex, plugin: 'hello@testmp' })).body.error.code, 'confirm_run')
  await ok(call('POST', '/v1/agents/plugins/install', { ...codex, plugin: 'hello@testmp', confirmRun: true }))
  const list = await ok(call('GET', `/v1/agents/plugins${q({ agent: 'codex' })}`))
  assert.equal(list.plugins[0].id, 'hello@testmp')
  assert.equal(list.plugins[0].enabled, true)
  const d = await ok(call('GET', `/v1/agents/plugins/detail${q({ agent: 'codex', plugin: 'hello@testmp' })}`))
  assert.ok(d.skills.some((s: string) => s.includes('greet')), JSON.stringify(d))

  const skills = async () => Object.fromEntries((await ok(call('GET', `/v1/agents/skills${q({ agent: 'codex' })}`))).skills.map((s: any) => [s.name, s])) as Record<string, any>
  const s = await skills()
  assert.equal(s['hello:greet'].source, 'plugin')
  assert.equal(s['hello:greet'].state, 'on')
  assert.ok(Object.values(s).some((x: any) => x.source === 'built-in'))
  await ok(call('POST', '/v1/agents/skills', { agent: 'codex', name: 'hello:greet', state: 'off' }))
  assert.equal((await skills())['hello:greet'].state, 'off')
  assert.equal((await call('POST', '/v1/agents/skills', { agent: 'codex', name: 'hello:greet', state: 'name-only' })).body.error.code, 'bad_state')
  await ok(call('POST', '/v1/agents/skills', { agent: 'codex', name: 'hello:greet', state: 'on' }))

  await ok(call('POST', '/v1/agents/plugins/toggle', { ...codex, plugin: 'hello@testmp', enabled: false }))
  assert.equal((await ok(call('GET', `/v1/agents/plugins${q({ agent: 'codex' })}`))).plugins[0].enabled, false)
  await ok(call('POST', '/v1/agents/plugins/toggle', { ...codex, plugin: 'hello@testmp', enabled: true }))
  await ok(call('POST', '/v1/agents/plugins/uninstall', { ...codex, plugin: 'hello@testmp' }))
  assert.deepEqual((await ok(call('GET', `/v1/agents/plugins${q({ agent: 'codex' })}`))).plugins, [])
  await ok(call('POST', '/v1/agents/plugins/marketplaces/remove', { ...codex, name: 'testmp' }))
  assert.equal((await call('POST', '/v1/agents/plugins/prune', { ...codex, scope: 'user' })).body.error.code, 'not_for_codex')
})
