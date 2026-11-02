// The real Claude Code against a tiny real MCP server, through the phone's routes, in a throwaway home. Nothing
// here costs tokens: no prompt is ever sent. Skipped where Claude Code isn't installed (CI).
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { createServer as httpServer } from 'node:http'
import { createServer } from 'node:net'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'

const realClaude = [process.env.HN_E2E_CLAUDE, join(homedir(), '.local/bin/claude')].find((p) => p && existsSync(p))
const skip = realClaude ? false : 'Claude Code isn’t installed here'
const bin = realClaude ? realpathSync(realClaude) : ''

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-mcp-e2e-')))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.CODEX_HOME = join(scratch, 'codex')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
process.env.HYPERNOVA_WSL = '0'
process.env.CLAUDE_BIN = bin || join(scratch, 'none')
const CLAUDE = process.env.CLAUDE_CONFIG_DIR
mkdirSync(process.env.HOME, { recursive: true })
mkdirSync(CLAUDE, { recursive: true })

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
const { backupDir } = await import('../src/agent-settings-edit.ts')
settings.lanAccess = false
const TOKEN = 'mcp-e2e-test-device-token-0123456789abcdef'
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

const SECRET = 'PLANTEDe2eSecret0123456789abcdefABCDEF'
const dir = join(scratch, 'work')
mkdirSync(dir, { recursive: true })
const SERVER = join(dir, 'tiny.mjs')
const MARKS = join(dir, 'started.log')
writeFileSync(SERVER, `
import { appendFileSync } from 'node:fs'
import { createInterface } from 'node:readline'
appendFileSync(${JSON.stringify(MARKS)}, JSON.stringify({ label: process.argv[2] ?? null, token: process.env.API_TOKEN ?? null }) + '\\n')
const send = (o) => process.stdout.write(JSON.stringify(o) + '\\n')
const tools = [
  { name: 'echo', description: 'Says back what it is sent.', annotations: { readOnlyHint: true }, inputSchema: { type: 'object', properties: { text: { type: 'string', description: 'What to say' } }, required: ['text'] } },
  { name: 'wipe', description: 'Pretends to wipe.', annotations: { destructiveHint: true }, inputSchema: { type: 'object', properties: {} } },
]
createInterface({ input: process.stdin }).on('line', (l) => {
  const m = JSON.parse(l)
  if (m.method === 'initialize') send({ jsonrpc: '2.0', id: m.id, result: { protocolVersion: m.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: 'tiny', version: '1.2.3' } } })
  else if (m.method === 'tools/list') send({ jsonrpc: '2.0', id: m.id, result: { tools } })
  else if (m.id !== undefined) send({ jsonrpc: '2.0', id: m.id, result: {} })
})
`)
const marks = () => (existsSync(MARKS) ? readFileSync(MARKS, 'utf8').trim().split('\n').filter(Boolean).map((l) => JSON.parse(l)) : [])

const call = async (method: string, path: string, body?: unknown) => {
  const r = await fetch(`http://127.0.0.1:${API}${path}`, { method, headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
  const text = await r.text()
  assert.ok(!text.includes(SECRET), `a secret reached the phone from ${path}: ${text}`)
  return { status: r.status, body: JSON.parse(text) as any }
}
const q = (o: Record<string, string>) => `?${new URLSearchParams({ agent: 'claude', ...o })}`
const list = async () => (await call('GET', `/v1/agents/mcp${q({})}`)).body
const status = async (folder = 'home') => {
  const r = await call('GET', `/v1/agents/mcp/status${q({ folder, fresh: '1' })}`)
  assert.equal(r.status, 200, JSON.stringify(r.body))
  return Object.fromEntries(r.body.servers.map((s: any) => [s.name, s])) as Record<string, any>
}
const page = async (name: string, scope: string, folder?: string) => (await call('GET', `/v1/agents/mcp/server${q({ name, scope, ...(folder ? { folder } : {}) })}`)).body
const config = () => JSON.parse(readFileSync(join(CLAUDE, '.claude.json'), 'utf8'))
const cli = (cwd: string, ...args: string[]) => execFileSync(bin, args, { cwd, encoding: 'utf8', env: process.env })

const stdioForm = { agent: 'claude', transport: 'stdio', command: process.execPath, args: [SERVER, 'one', '--token', SECRET], env: [{ name: 'API_TOKEN', value: SECRET }] }

test('add a stdio server to the user\'s scope: refused without the check, then running, its secrets never back', { skip, timeout: 120_000 }, async () => {
  const refused = await call('POST', '/v1/agents/mcp/servers', { ...stdioForm, scope: 'user', name: 'tiny' })
  assert.equal(refused.status, 403)
  assert.equal(refused.body.error.code, 'confirm_run')
  const r = await call('POST', '/v1/agents/mcp/servers', { ...stdioForm, scope: 'user', name: 'tiny', confirmRun: true })
  assert.equal(r.status, 200, JSON.stringify(r.body))
  assert.equal(config().mcpServers.tiny.env.API_TOKEN, SECRET)
  assert.deepEqual(config().mcpServers.tiny.args.slice(1), ['one', '--token', SECRET])

  const l = await list()
  assert.ok(l.servers.some((s: any) => s.name === 'tiny' && s.scope === 'user' && s.transport === 'stdio'))
  assert.equal(l.undo.op, 'add')
  const p = await page('tiny', 'user')
  assert.deepEqual(p.envNames, ['API_TOKEN'])
  assert.deepEqual(p.args.slice(1), ['one', '--token', '•••'])

  const live = await status()
  assert.equal(live.tiny.status, 'connected', JSON.stringify(live.tiny))
  assert.equal(live.tiny.source, 'user')
  assert.deepEqual(live.tiny.tools.map((t: any) => t.name), ['echo', 'wipe'])
  assert.equal(live.tiny.tools[0].readOnly, true)
  // It really got its env: the server notes it.
  assert.equal(marks().at(-1).token, SECRET)

  const tools = (await call('GET', `/v1/agents/mcp/tools${q({ name: 'tiny', scope: 'user' })}`)).body
  assert.equal(tools.whyNot, undefined)
  assert.equal(tools.tools[0].description, 'Says back what it is sent.')
  assert.deepEqual(tools.tools[0].params.map((x: any) => [x.name, x.required]), [['text', true]])
})

test('disable, enable and reconnect go through Claude Code\'s own writer, per folder', { skip, timeout: 120_000 }, async () => {
  const off = await call('POST', '/v1/agents/mcp/toggle', { agent: 'claude', name: 'tiny', folder: 'home', enabled: false })
  assert.equal(off.status, 200, JSON.stringify(off.body))
  assert.ok(config().projects[process.env.HOME!].disabledMcpServers.includes('tiny'))
  const before = marks().length
  assert.equal((await status()).tiny.status, 'disabled')
  assert.equal((await call('GET', `/v1/agents/mcp/tools${q({ name: 'tiny', scope: 'user' })}`)).body.whyNot, 'disabled')
  assert.equal(marks().length, before, 'an off server was started')
  assert.match(cli(process.env.HOME!, 'mcp', 'get', 'tiny'), /Disabled/i)
  const on = await call('POST', '/v1/agents/mcp/toggle', { agent: 'claude', name: 'tiny', folder: 'home', enabled: true })
  assert.equal(on.status, 200)
  assert.ok(!config().projects[process.env.HOME!].disabledMcpServers.includes('tiny'))
  assert.equal((await status()).tiny.status, 'connected')
  const again = await call('POST', '/v1/agents/mcp/reconnect', { agent: 'claude', name: 'tiny', folder: 'home' })
  assert.equal(again.status, 200)
  assert.equal(again.body.server.status, 'connected')
})

test('edit keeps what the form sent back masked or kept, and Undo puts the old entry back', { skip, timeout: 120_000 }, async () => {
  const p = await page('tiny', 'user')
  const r = await call('PUT', '/v1/agents/mcp/servers', { agent: 'claude', scope: 'user', name: 'tiny', transport: 'stdio', command: p.command, args: [p.args[0], 'two', '--token', '•••'], env: [{ name: 'API_TOKEN', keep: true }, { name: 'EXTRA', value: 'x' }], expected: p.hash, confirmRun: true })
  assert.equal(r.status, 200, JSON.stringify(r.body))
  const e = config().mcpServers.tiny
  assert.deepEqual(e.args.slice(1), ['two', '--token', SECRET])
  assert.deepEqual(e.env, { API_TOKEN: SECRET, EXTRA: 'x' })
  assert.equal((await status()).tiny.status, 'connected')
  assert.equal(marks().at(-1).label, 'two')

  // An old version's hash is refused.
  const stale = await call('PUT', '/v1/agents/mcp/servers', { agent: 'claude', scope: 'user', name: 'tiny', transport: 'stdio', command: 'x', expected: p.hash, confirmRun: true })
  assert.equal(stale.status, 409)
  assert.equal(stale.body.error.code, 'file_changed')

  const l = await list()
  assert.equal(l.undo.op, 'edit')
  assert.equal(l.undo.needsCheck, true)
  assert.ok(l.undo.command.includes('one --token •••'))
  assert.equal((await call('POST', '/v1/agents/mcp/undo', { agent: 'claude' })).body.error.code, 'confirm_run')
  assert.equal((await call('POST', '/v1/agents/mcp/undo', { agent: 'claude', confirmRun: true })).status, 200)
  assert.deepEqual(config().mcpServers.tiny.args.slice(1), ['one', '--token', SECRET])
  assert.deepEqual(config().mcpServers.tiny.env, { API_TOKEN: SECRET })
})

test('a project\'s server: added, not started until approved, and only once the folder is trusted; turned down; choices reset', { skip, timeout: 180_000 }, async () => {
  const app = join(scratch, 'app')
  mkdirSync(app, { recursive: true })
  execFileSync('git', ['init', '-q', app])
  const known = { ...config(), projects: { ...config().projects, [app]: { hasTrustDialogAccepted: false } } }
  writeFileSync(join(CLAUDE, '.claude.json'), JSON.stringify(known))
  const folder = folderId(app)
  const added = await call('POST', '/v1/agents/mcp/servers', { agent: 'claude', transport: 'stdio', command: process.execPath, args: [SERVER, 'project'], scope: 'project', folder, name: 'shared', confirmRun: true })
  assert.equal(added.status, 200, JSON.stringify(added.body))
  assert.ok(JSON.parse(readFileSync(join(app, '.mcp.json'), 'utf8')).mcpServers.shared)
  assert.equal((await page('shared', 'project', folder)).approval, 'pending')
  const before = marks().filter((m) => m.label === 'project').length
  const live = await status(folder)
  assert.equal(live.shared.status, 'waiting')
  assert.equal(live.tiny.status, 'connected')
  assert.equal(marks().filter((m) => m.label === 'project').length, before, 'a server nobody approved was started')
  assert.equal((await call('GET', `/v1/agents/mcp/tools${q({ name: 'shared', scope: 'project', folder })}`)).body.whyNot, 'approval')

  assert.equal((await call('POST', '/v1/agents/mcp/approval', { agent: 'claude', name: 'shared', folder, approved: true })).body.error.code, 'confirm_run')
  assert.equal((await call('POST', '/v1/agents/mcp/approval', { agent: 'claude', name: 'shared', folder, approved: true, confirmRun: true })).body.error.code, 'not_trusted')
  // The owner accepted the question in a session there.
  const trusted = config()
  trusted.projects[app].hasTrustDialogAccepted = true
  writeFileSync(join(CLAUDE, '.claude.json'), JSON.stringify(trusted))
  assert.equal((await call('POST', '/v1/agents/mcp/approval', { agent: 'claude', name: 'shared', folder, approved: true, confirmRun: true })).status, 200)
  assert.deepEqual(JSON.parse(readFileSync(join(app, '.claude', 'settings.local.json'), 'utf8')).enabledMcpjsonServers, ['shared'])
  assert.match(cli(app, 'mcp', 'get', 'shared'), /Connected/, 'Claude Code itself agrees it is approved')
  assert.equal((await page('shared', 'project', folder)).approval, 'approved')
  assert.equal((await status(folder)).shared.status, 'connected')

  assert.equal((await call('POST', '/v1/agents/mcp/approval', { agent: 'claude', name: 'shared', folder, approved: false })).status, 200)
  assert.match(cli(app, 'mcp', 'get', 'shared'), /Rejected/)
  assert.equal((await status(folder)).shared.status, 'rejected')

  assert.equal((await call('POST', '/v1/agents/mcp/reset-choices', { agent: 'claude', folder })).status, 200)
  assert.equal((await page('shared', 'project', folder)).approval, 'pending')
  assert.match(cli(app, 'mcp', 'get', 'shared'), /Pending approval/)
})

test('a local server, and remove with Undo', { skip, timeout: 120_000 }, async () => {
  const app = join(scratch, 'app')
  const folder = folderId(app)
  const added = await call('POST', '/v1/agents/mcp/servers', { agent: 'claude', transport: 'http', url: `https://mcp.example.invalid/m?key=${SECRET}`, headers: [{ name: 'Authorization', value: `Bearer ${SECRET}` }], scope: 'local', folder, name: 'webby' })
  assert.equal(added.status, 200, JSON.stringify(added.body))
  assert.equal(config().projects[app].mcpServers.webby.headers.Authorization, `Bearer ${SECRET}`)
  const p = await page('webby', 'local', folder)
  assert.deepEqual(p.headerNames, ['Authorization'])
  assert.equal(p.url, 'https://mcp.example.invalid/m?key=•••')
  // The form sends the masked URL and a kept header back: nothing lost.
  const same = await call('PUT', '/v1/agents/mcp/servers', { agent: 'claude', transport: 'http', url: p.url, headers: [{ name: 'Authorization', keep: true }, { name: 'X-More', value: 'y' }], scope: 'local', folder, name: 'webby', expected: p.hash })
  assert.equal(same.status, 200, JSON.stringify(same.body))
  assert.equal(config().projects[app].mcpServers.webby.url, `https://mcp.example.invalid/m?key=${SECRET}`)
  assert.equal(config().projects[app].mcpServers.webby.headers.Authorization, `Bearer ${SECRET}`)

  const now = await page('webby', 'local', folder)
  assert.equal((await call('POST', '/v1/agents/mcp/servers/remove', { agent: 'claude', scope: 'local', folder, name: 'webby', expected: 'old' })).body.error.code, 'file_changed')
  assert.equal((await call('POST', '/v1/agents/mcp/servers/remove', { agent: 'claude', scope: 'local', folder, name: 'webby', expected: now.hash })).status, 200)
  assert.equal(config().projects[app].mcpServers.webby, undefined)
  const l = await list()
  assert.equal(l.undo.op, 'remove')
  assert.equal(l.undo.needsCheck, false)
  assert.equal((await call('POST', '/v1/agents/mcp/undo', { agent: 'claude' })).status, 200)
  assert.equal(config().projects[app].mcpServers.webby.headers.Authorization, `Bearer ${SECRET}`)
  // The undo record holds an entry whole, so it's kept as a settings backup is.
  assert.equal(existsSync(backupDir()), true)
})

test('OAuth: sign in from the phone against a local authorization server, then sign out', { skip, timeout: 120_000 }, async () => {
  const srv = httpServer((req, res) => {
    let b = ''
    req.on('data', (d) => (b += d))
    req.on('end', () => {
      const base = `http://127.0.0.1:${(srv.address() as { port: number }).port}`
      const url = new URL(req.url!, base)
      const send = (o: unknown, status = 200) => {
        res.writeHead(status, { 'content-type': 'application/json' })
        res.end(JSON.stringify(o))
      }
      if (url.pathname.startsWith('/.well-known/oauth-protected-resource')) return send({ resource: `${base}/mcp`, authorization_servers: [base] })
      if (url.pathname.startsWith('/.well-known/oauth-authorization-server') || url.pathname.startsWith('/.well-known/openid-configuration')) return send({ issuer: base, authorization_endpoint: `${base}/authorize`, token_endpoint: `${base}/token`, registration_endpoint: `${base}/register`, response_types_supported: ['code'], grant_types_supported: ['authorization_code', 'refresh_token'], code_challenge_methods_supported: ['S256'], token_endpoint_auth_methods_supported: ['none'] })
      if (url.pathname === '/register') return send({ client_id: 'tiny-client', redirect_uris: JSON.parse(b).redirect_uris, token_endpoint_auth_method: 'none' }, 201)
      if (url.pathname === '/authorize') {
        res.writeHead(302, { location: `${url.searchParams.get('redirect_uri')}?code=the-code&state=${url.searchParams.get('state')}` })
        return res.end()
      }
      if (url.pathname === '/token') return send({ access_token: 'tiny-access', token_type: 'bearer', expires_in: 3600 })
      if (url.pathname === '/mcp') {
        if (req.headers.authorization !== 'Bearer tiny-access') {
          res.writeHead(401, { 'www-authenticate': `Bearer resource_metadata="${base}/.well-known/oauth-protected-resource"` })
          return res.end()
        }
        const m = b ? JSON.parse(b) : {}
        if (m.id === undefined) {
          res.writeHead(202)
          return res.end()
        }
        return send({ jsonrpc: '2.0', id: m.id, result: m.method === 'initialize' ? { protocolVersion: m.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: 'web', version: '1' } } : { tools: [{ name: 'fetch', inputSchema: { type: 'object' } }] } })
      }
      res.writeHead(404)
      res.end()
    })
  })
  await new Promise<void>((r) => srv.listen(0, '127.0.0.1', r))
  const base = `http://127.0.0.1:${(srv.address() as { port: number }).port}`
  try {
    const added = await call('POST', '/v1/agents/mcp/servers', { agent: 'claude', transport: 'http', url: `${base}/mcp`, scope: 'user', name: 'webauth' })
    assert.equal(added.status, 200, JSON.stringify(added.body))
    assert.equal((await status()).webauth.status, 'needs-auth')
    assert.equal((await call('GET', `/v1/agents/mcp/tools${q({ name: 'webauth', scope: 'user' })}`)).body.whyNot, 'needs_auth')

    const start = await call('POST', '/v1/agents/mcp/login', { agent: 'claude', name: 'webauth', folder: 'home' })
    assert.equal(start.status, 200, JSON.stringify(start.body))
    assert.equal(start.body.done, false)
    assert.ok(start.body.url.startsWith(`${base}/authorize`), start.body.url)
    // The browser on the phone: it signs in, and is sent to an address it can't open. That address gets pasted.
    const page = await fetch(start.body.url, { redirect: 'manual' })
    const address = page.headers.get('location')!
    assert.match(address, /^http:\/\/(localhost|127\.0\.0\.1)/)
    assert.equal((await call('POST', '/v1/agents/mcp/login/finish', { agent: 'claude', login: start.body.login, address: 'https://evil.example/?code=x' })).body.error.code, 'bad_address')
    const done = await call('POST', '/v1/agents/mcp/login/finish', { agent: 'claude', login: start.body.login, address })
    assert.equal(done.status, 200, JSON.stringify(done.body))
    assert.equal((await status()).webauth.status, 'connected')

    assert.equal((await call('POST', '/v1/agents/mcp/logout', { agent: 'claude', name: 'webauth', folder: 'home' })).status, 200)
    assert.equal((await status()).webauth.status, 'needs-auth')
  } finally {
    srv.closeAllConnections()
    srv.close()
  }
})
