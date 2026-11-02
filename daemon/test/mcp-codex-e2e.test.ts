// The real Codex against a tiny real MCP server, through the phone's routes, in a throwaway CODEX_HOME. No model is
// asked anything. Skipped where a current Codex isn't installed (CI).
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { createServer as httpServer } from 'node:http'
import { createServer } from 'node:net'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'

const nvm = join(homedir(), '.nvm/versions/node')
const candidates = [process.env.HN_E2E_CODEX, ...(existsSync(nvm) ? readdirSync(nvm).map((v) => join(nvm, v, 'bin/codex')) : [])].filter((p): p is string => !!p && existsSync(p))
const realCodex = candidates.find((p) => {
  try {
    const v = /(\d+)\.(\d+)/.exec(execFileSync(p, ['--version'], { encoding: 'utf8' }))
    return v ? Number(v[1]) > 0 || Number(v[2]) >= 163 : false
  } catch {
    return false
  }
})
const skip = realCodex ? false : 'a current Codex isn’t installed here'

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-mcp-codex-e2e-')))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.CODEX_HOME = join(scratch, 'codex')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
process.env.HYPERNOVA_WSL = '0'
process.env.HYPERNOVA_CODEX = realCodex ?? join(scratch, 'none')
const CODEX = process.env.CODEX_HOME
mkdirSync(process.env.HOME, { recursive: true })
mkdirSync(CODEX, { recursive: true })
mkdirSync(process.env.CLAUDE_CONFIG_DIR, { recursive: true })

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
settings.lanAccess = false
const TOKEN = 'mcp-codex-e2e-test-device-token-0123456789'
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

const SECRET = 'PLANTEDcodexSecret0123456789abcdefABCDEF'
const SERVER = join(scratch, 'tiny.mjs')
const MARKS = join(scratch, 'started.log')
writeFileSync(SERVER, `
import { appendFileSync } from 'node:fs'
import { createInterface } from 'node:readline'
appendFileSync(${JSON.stringify(MARKS)}, JSON.stringify({ label: process.argv[2] ?? null, token: process.env.API_TOKEN ?? null }) + '\\n')
const send = (o) => process.stdout.write(JSON.stringify(o) + '\\n')
const tools = [{ name: 'echo', description: 'Says back what it is sent.', annotations: { readOnlyHint: true }, inputSchema: { type: 'object', properties: { text: { type: 'string', description: 'What to say' } }, required: ['text'] } }]
createInterface({ input: process.stdin }).on('line', (l) => {
  const m = JSON.parse(l)
  if (m.method === 'initialize') send({ jsonrpc: '2.0', id: m.id, result: { protocolVersion: m.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: 'tiny', version: '1.2.3' } } })
  else if (m.method === 'tools/list') send({ jsonrpc: '2.0', id: m.id, result: { tools } })
  else if (m.id !== undefined) send({ jsonrpc: '2.0', id: m.id, result: {} })
})
`)
const toml = () => readFileSync(join(CODEX, 'config.toml'), 'utf8')
const call = async (method: string, path: string, body?: unknown) => {
  const r = await fetch(`http://127.0.0.1:${API}${path}`, { method, headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
  const text = await r.text()
  assert.ok(!text.includes(SECRET), `a secret reached the phone from ${path}: ${text}`)
  return { status: r.status, body: JSON.parse(text) as any }
}
const q = (o: Record<string, string> = {}) => `?${new URLSearchParams({ agent: 'codex', ...o })}`
const status = async () => Object.fromEntries((await call('GET', `/v1/agents/mcp/status${q({ fresh: '1' })}`)).body.servers.map((s: any) => [s.name, s])) as Record<string, any>
const page = async (name: string) => (await call('GET', `/v1/agents/mcp/server${q({ name, scope: 'user' })}`)).body

test('Codex: add, see, its tools in full, off and on, change keeping the secret, remove, Undo; the rest of config.toml stays', { skip, timeout: 180_000 }, async () => {
  writeFileSync(join(CODEX, 'config.toml'), '# my own comment\nmodel = "gpt-6"\n')
  const form = { agent: 'codex', scope: 'user', name: 'tiny', transport: 'stdio', command: process.execPath, args: [SERVER, 'one', '--token', SECRET], env: [{ name: 'API_TOKEN', value: SECRET }] }
  assert.equal((await call('POST', '/v1/agents/mcp/servers', form)).body.error.code, 'confirm_run')
  const added = await call('POST', '/v1/agents/mcp/servers', { ...form, confirmRun: true })
  assert.equal(added.status, 200, JSON.stringify(added.body))
  assert.ok(toml().includes('# my own comment'))
  assert.ok(toml().includes(SECRET))

  const list = (await call('GET', `/v1/agents/mcp${q()}`)).body
  assert.deepEqual(list.scopes, ['user'])
  assert.deepEqual(list.servers, [{ name: 'tiny', scope: 'user', transport: 'stdio', summary: /[^/]+$/.exec(process.execPath)![0], enabled: true }])
  const p = await page('tiny')
  assert.equal(p.where, '~/.codex/config.toml')
  assert.deepEqual(p.envNames, ['API_TOKEN'])
  assert.deepEqual(p.args.slice(1), ['one', '--token', '•••'])

  const live = await status()
  assert.equal(live.tiny.status, 'connected', JSON.stringify(live.tiny))
  assert.deepEqual(live.tiny.tools, [{ name: 'echo', readOnly: true }])
  const tools = (await call('GET', `/v1/agents/mcp/tools${q({ name: 'tiny', scope: 'user' })}`)).body
  assert.equal(tools.tools[0].description, 'Says back what it is sent.')
  assert.deepEqual(tools.tools[0].params.map((x: any) => x.name), ['text'])

  assert.equal((await call('POST', '/v1/agents/mcp/toggle', { agent: 'codex', name: 'tiny', enabled: false })).status, 200)
  assert.match(toml(), /enabled = false/)
  assert.equal((await status()).tiny.status, 'disabled')
  assert.equal((await call('GET', `/v1/agents/mcp/tools${q({ name: 'tiny', scope: 'user' })}`)).body.whyNot, 'disabled')
  assert.equal((await call('POST', '/v1/agents/mcp/toggle', { agent: 'codex', name: 'tiny', enabled: true })).status, 200)
  assert.equal((await call('POST', '/v1/agents/mcp/reconnect', { agent: 'codex', name: 'tiny' })).body.server.status, 'connected')

  const now = await page('tiny')
  const edit = await call('PUT', '/v1/agents/mcp/servers', { agent: 'codex', scope: 'user', name: 'tiny', transport: 'stdio', command: now.command, args: [now.args[0], 'two', '--token', '•••'], env: [{ name: 'API_TOKEN', keep: true }], expected: now.hash, confirmRun: true })
  assert.equal(edit.status, 200, JSON.stringify(edit.body))
  assert.ok(toml().includes(SECRET) && toml().includes('"two"'))
  assert.ok(toml().includes('# my own comment'))

  const offered = (await call('GET', `/v1/agents/mcp${q()}`)).body.undo
  assert.equal(offered.op, 'edit')
  assert.equal(offered.needsCheck, true)
  assert.equal((await call('POST', '/v1/agents/mcp/undo', { agent: 'codex', confirmRun: true })).status, 200)
  assert.ok(toml().includes('"one"') && !toml().includes('"two"'))

  const last = await page('tiny')
  assert.equal((await call('POST', '/v1/agents/mcp/servers/remove', { agent: 'codex', scope: 'user', name: 'tiny', expected: last.hash })).status, 200)
  assert.ok(!toml().includes('mcp_servers.tiny'))
  assert.ok(toml().includes('model = "gpt-6"'))
  assert.equal((await call('POST', '/v1/agents/mcp/undo', { agent: 'codex', confirmRun: true })).status, 200)
  assert.ok(toml().includes(SECRET))
  // Codex has neither of these.
  assert.equal((await call('POST', '/v1/agents/mcp/approval', { agent: 'codex', name: 'tiny', approved: true })).body.error.code, 'not_for_codex')
  assert.equal((await call('POST', '/v1/agents/mcp/servers', { agent: 'codex', scope: 'user', name: 'old', transport: 'sse', url: 'https://x.example/m' })).body.error.code, 'bad_transport')
})

test('Codex: a timeout is its tool_timeout_sec, kept and taken out; sign-in settings are refused by name, with nothing written', { skip, timeout: 120_000 }, async () => {
  const web = { agent: 'codex', scope: 'user', transport: 'http', url: 'https://mcp.example.invalid/m' }
  for (const oauth of [{ clientId: 'cid' }, { callbackPort: 3118 }, { clientSecret: SECRET }]) {
    const r = await call('POST', '/v1/agents/mcp/servers', { ...web, name: 'noauth', oauth })
    assert.equal(r.status, 400, JSON.stringify(r.body))
    assert.equal(r.body.error.code, 'codex_no_oauth')
  }
  assert.ok(!existsSync(join(CODEX, 'config.toml')) || !toml().includes('noauth'), 'a refused server was written')
  assert.equal((await call('POST', '/v1/agents/mcp/servers', { ...web, name: 'noauth', oauth: { clientId: null, callbackPort: null } })).status, 200, 'a form with nothing to say about sign-in is fine')
  assert.equal((await call('POST', '/v1/agents/mcp/servers/remove', { agent: 'codex', scope: 'user', name: 'noauth', expected: (await page('noauth')).hash })).status, 200)

  assert.equal((await call('POST', '/v1/agents/mcp/servers', { ...web, name: 'timed', timeout: 5 })).body.error.code, 'bad_timeout')
  const added = await call('POST', '/v1/agents/mcp/servers', { ...web, name: 'timed', timeout: 2500 })
  assert.equal(added.status, 200, JSON.stringify(added.body))
  assert.match(toml(), /tool_timeout_sec = 2\.5/)
  const p = await page('timed')
  assert.equal(p.timeout, 2500, 'Codex’s own list reads it back')
  assert.equal(p.toolTimeout, 2.5)

  const kept = await call('PUT', '/v1/agents/mcp/servers', { ...web, name: 'timed', expected: p.hash })
  assert.equal(kept.status, 200, JSON.stringify(kept.body))
  assert.equal((await page('timed')).timeout, 2500, 'a form that doesn’t speak of it keeps it')
  const changed = await call('PUT', '/v1/agents/mcp/servers', { ...web, name: 'timed', expected: (await page('timed')).hash, timeout: 60_000 })
  assert.equal(changed.status, 200, JSON.stringify(changed.body))
  assert.equal((await page('timed')).toolTimeout, 60)
  const off = await call('PUT', '/v1/agents/mcp/servers', { ...web, name: 'timed', expected: (await page('timed')).hash, timeout: null })
  assert.equal(off.status, 200, JSON.stringify(off.body))
  assert.equal((await page('timed')).timeout, undefined)
  assert.ok(!toml().includes('tool_timeout_sec'))
  assert.equal((await call('POST', '/v1/agents/mcp/servers/remove', { agent: 'codex', scope: 'user', name: 'timed', expected: (await page('timed')).hash })).status, 200)
})

test('Codex: an HTTP server with headers and a bearer variable, and OAuth from the phone against a local authorization server', { skip, timeout: 120_000 }, async () => {
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
      if (url.pathname.startsWith('/.well-known/')) return send({ issuer: base, authorization_endpoint: `${base}/authorize`, token_endpoint: `${base}/token`, registration_endpoint: `${base}/register`, response_types_supported: ['code'], grant_types_supported: ['authorization_code', 'refresh_token'], code_challenge_methods_supported: ['S256'], token_endpoint_auth_methods_supported: ['none'] })
      if (url.pathname === '/register') return send({ client_id: 'tiny-client', redirect_uris: JSON.parse(b).redirect_uris, token_endpoint_auth_method: 'none' }, 201)
      if (url.pathname === '/authorize') {
        res.writeHead(302, { location: `${url.searchParams.get('redirect_uri')}?code=the-code&state=${url.searchParams.get('state')}` })
        return res.end()
      }
      if (url.pathname === '/token') return send({ access_token: 'tiny-access', token_type: 'bearer', expires_in: 3600, refresh_token: 'r' })
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
    const headed = await call('POST', '/v1/agents/mcp/servers', { agent: 'codex', scope: 'user', name: 'headed', transport: 'http', url: `https://mcp.example.invalid/m?key=${SECRET}`, headers: [{ name: 'X-Key', value: SECRET }], bearerEnv: 'MY_TOKEN' })
    assert.equal(headed.status, 200, JSON.stringify(headed.body))
    const hp = await page('headed')
    assert.deepEqual(hp.headerNames, ['X-Key'])
    assert.equal(hp.bearerEnv, 'MY_TOKEN')
    assert.equal(hp.url, 'https://mcp.example.invalid/m?key=•••')
    assert.equal((await call('POST', '/v1/agents/mcp/servers', { agent: 'codex', scope: 'user', name: 'bad', transport: 'http', url: 'https://x.example', bearerEnv: 'not a name' })).body.error.code, 'bad_bearer')
    assert.equal((await call('POST', '/v1/agents/mcp/servers/remove', { agent: 'codex', scope: 'user', name: 'headed', expected: hp.hash })).status, 200)

    assert.equal((await call('POST', '/v1/agents/mcp/servers', { agent: 'codex', scope: 'user', name: 'webauth', transport: 'http', url: `${base}/mcp` })).status, 200)
    assert.equal((await status()).webauth.status, 'needs-auth')
    const start = await call('POST', '/v1/agents/mcp/login', { agent: 'codex', name: 'webauth' })
    assert.equal(start.status, 200, JSON.stringify(start.body))
    assert.ok(start.body.url.startsWith(`${base}/authorize`), start.body.url)
    const address = (await fetch(start.body.url, { redirect: 'manual' })).headers.get('location')!
    const done = await call('POST', '/v1/agents/mcp/login/finish', { agent: 'codex', login: start.body.login, address })
    assert.equal(done.status, 200, JSON.stringify(done.body))
    const live = await status()
    assert.equal(live.webauth.status, 'connected', JSON.stringify(live.webauth))
    assert.equal((await page('webauth')).authStatus, 'o_auth')
    assert.equal((await call('POST', '/v1/agents/mcp/logout', { agent: 'codex', name: 'webauth' })).status, 200)
    assert.equal((await status()).webauth.status, 'needs-auth')
  } finally {
    srv.closeAllConnections()
    srv.close()
  }
})
