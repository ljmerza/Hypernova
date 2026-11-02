// What the phone may see of an MCP server (mcp-view.ts, mcp-routes.ts): masks, scopes, approval, and no
// secret in any raw body. Every file here is a scratch one; the status comes from a stand-in process.
import { test, before, after, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-mcp-view-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.CODEX_HOME = join(scratch, 'codex')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
process.env.HYPERNOVA_WSL = '0'
const HOME = process.env.HOME
const CLAUDE = process.env.CLAUDE_CONFIG_DIR
mkdirSync(HOME, { recursive: true })
mkdirSync(CLAUDE, { recursive: true })
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
process.env.HYPERNOVA_API_PORT = String(API)
process.env.HYPERNOVA_LOCAL_PORT = String(await freePort())
process.env.HYPERNOVA_LAN_PORT = '0'

const { approvalOf, claudeList, configHash, folderId, HOME_FOLDER, maskArgs, maskUrl, MASK, restoreArgs, setupOf } = await import('../src/mcp-view.ts')
const { useProbe, forgetLive } = await import('../src/mcp-probe.ts')
const { expandVars, talkTo, TalkFailed } = await import('../src/mcp-client.ts')
const { factsOf } = await import('../src/mcp-tools.ts')
const { forgetTools } = await import('../src/mcp-routes.ts')
const { startServers } = await import('../src/http.ts')
const { addDevice, settings } = await import('../src/store.ts')
settings.lanAccess = false
const TOKEN = 'mcp-view-test-device-token-0123456789abcdef'
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
  useProbe(null)
  rmSync(scratch, { recursive: true, force: true })
})
const CONFIG = join(CLAUDE, '.claude.json')
beforeEach(() => {
  rmSync(CONFIG, { force: true })
  rmSync(join(CLAUDE, 'settings.json'), { force: true })
  forgetLive()
  forgetTools()
})

const get = (path: string, token: string | null = TOKEN) => fetch(`http://127.0.0.1:${API}${path}`, { headers: token ? { authorization: `Bearer ${token}` } : {} })
const q = (o: Record<string, string>) => `?${new URLSearchParams(o)}`

const PLANT = 'PLANTEDsecretValue0123456789abcdefABCDEF'
const project = (name: string) => {
  const d = join(scratch, 'projects', `${name}-${Math.random().toString(36).slice(2, 7)}`)
  mkdirSync(d, { recursive: true })
  return d
}
const planted = {
  type: 'stdio',
  command: '/usr/bin/node',
  args: ['server.js', '--token', PLANT, `--api-key=${PLANT}`, '-H', `Authorization: Bearer ${PLANT}`, `https://user:${PLANT}@example.com/x?key=${PLANT}&page=2`, `API_TOKEN=${PLANT}`, 'plain'],
  env: { API_TOKEN: PLANT, PATHY: 'not-secret-but-never-sent' },
}

test('arguments: every shape of a secret, by its flag, its name or its look; the rest as they are', () => {
  assert.deepEqual(maskArgs(['--token', 'xyz', '--verbose', 'a', '--port', '8080']), ['--token', MASK, '--verbose', 'a', '--port', '8080'])
  assert.deepEqual(maskArgs(['--api-key=abc', '--name=fine']), [`--api-key=${MASK}`, '--name=fine'])
  assert.deepEqual(maskArgs(['--header', 'X-Custom: abc', '-H', 'Authorization: Bearer abc']), ['--header', `X-Custom: ${MASK}`, '-H', `Authorization: ${MASK}`])
  assert.deepEqual(maskArgs(['Bearer abc', 'basic dXNlcjpwYXNz', 'Authorization: abc', 'Accept: json']), [`Bearer ${MASK}`, `basic ${MASK}`, `Authorization: ${MASK}`, 'Accept: json'])
  assert.deepEqual(maskArgs(['API_KEY=abc', 'NODE_ENV=production', 'MY_SECRET=x']), [`API_KEY=${MASK}`, 'NODE_ENV=production', `MY_SECRET=${MASK}`])
  assert.deepEqual(maskArgs(['--password', '-v']), ['--password', '-v'])
  assert.deepEqual(maskArgs(['sk-ant-api03-PLANTEDabcdefghijklmnop0123456789']), [MASK])
  // Words that merely hold one: `monkey`, `keyboard`, `author`.
  assert.deepEqual(maskArgs(['--monkey', 'x', '--keyboard=us', '--author', 'y']), ['--monkey', 'x', '--keyboard=us', '--author', 'y'])
})

test('a URL: its credentials and secret-named query values, not its host, path or other values', () => {
  assert.equal(maskUrl('https://mcp.example.com/sse?x=1'), 'https://mcp.example.com/sse?x=1')
  assert.equal(maskUrl('https://user:pass@mcp.example.com/a'), `https://${MASK}@mcp.example.com/a`)
  assert.equal(maskUrl('https://mcp.example.com/a?key=abc&page=2&access_token=def'), `https://mcp.example.com/a?key=${MASK}&page=2&access_token=${MASK}`)
  assert.equal(maskUrl('https://mcp.example.com/a?API-KEY=abc'), `https://mcp.example.com/a?API-KEY=${MASK}`)
  assert.equal(maskUrl('https://mcp.example.com/a#token=abc'), `https://mcp.example.com/a#${MASK}`)
  assert.deepEqual(maskArgs(['--url=https://u:p@x.example/a?token=z']), [`--url=https://${MASK}@x.example/a?token=${MASK}`])
})

test('what a form sends back masked is the real value again, wherever it moved; a mask it wasn\'t given is refused', () => {
  const real = ['--token', 'xyz', 'plain', '--api-key=abc']
  const shown = maskArgs(real)
  assert.deepEqual(restoreArgs(shown, real), real)
  assert.deepEqual(restoreArgs(['new', ...shown.slice(2), ...shown.slice(0, 2)], real), ['new', 'plain', '--api-key=abc', '--token', 'xyz'])
  assert.deepEqual(restoreArgs(['--token', 'replaced', 'plain'], real), ['--token', 'replaced', 'plain'])
  assert.equal(restoreArgs([`--other=${MASK}`], real), null)
  assert.equal(restoreArgs([MASK, MASK], real), null)
})

test('a setup says names, never values', () => {
  const s = setupOf(planted)
  assert.ok(!JSON.stringify(s).includes(PLANT))
  assert.ok(!JSON.stringify(s).includes('never-sent'))
  assert.deepEqual(s.envNames, ['API_TOKEN', 'PATHY'])
  assert.equal(s.transport, 'stdio')
  assert.equal(s.args!.at(-1), 'plain')
  const h = setupOf({ type: 'http', url: `https://x.example/m?key=${PLANT}`, headers: { Authorization: `Bearer ${PLANT}`, [`sk-ant-api03-${PLANT}`]: 'x' }, oauth: { clientId: 'cid', callbackPort: 1234, clientSecret: PLANT }, timeout: 5000, headersHelper: `get --token ${PLANT}` })
  assert.deepEqual(h.headerNames, ['Authorization'])
  assert.equal(h.hidden, 1)
  assert.deepEqual(h.oauth, { clientId: 'cid', callbackPort: 1234 })
  assert.equal(h.timeout, 5000)
  // A helper's a whole command line: the plain masks still hold for what looks random.
  assert.ok(!JSON.stringify(h).includes(PLANT))
})

test('the hash isn\'t a plain hash of the entry, so a short secret can\'t be guessed from it', () => {
  const a = configHash({ env: { K: 'abc' } })
  assert.equal(a, configHash({ env: { K: 'abc' } }))
  assert.notEqual(a, configHash({ env: { K: 'abd' } }))
  assert.equal(a.length, 32)
})

test('the list: each scope, its folder by id and base name, nothing started, no secret, no path', async () => {
  const app = project('app')
  const mcp = { mcpServers: { shared: planted, web: { type: 'http', url: `https://mcp.example.com/m?key=${PLANT}`, headers: { Authorization: PLANT } } } }
  writeFileSync(join(app, '.mcp.json'), JSON.stringify(mcp))
  writeFileSync(CONFIG, JSON.stringify({ oauthAccount: { emailAddress: 'owner@example.com' }, userID: PLANT, history: [PLANT], mcpServers: { globalOne: planted }, projects: { [app]: { history: [PLANT], mcpServers: { localOne: { type: 'sse', url: `https://sse.example.com/?token=${PLANT}` } } } } }))
  writeFileSync(join(CLAUDE, 'settings.json'), JSON.stringify({ mcpServers: { fromSettings: planted } }))
  useProbe(async () => {
    throw new Error('the list never starts anything')
  })
  const r = await get(`/v1/agents/mcp${q({ agent: 'claude' })}`)
  assert.equal(r.status, 200)
  const text = await r.text()
  assert.ok(!text.includes(PLANT))
  assert.ok(!text.includes(scratch), `a path left: ${text}`)
  assert.ok(!text.includes('owner@example.com'))
  const body = JSON.parse(text)
  const by = Object.fromEntries(body.servers.map((s: any) => [`${s.scope}:${s.name}`, s]))
  assert.deepEqual(by['user:globalOne'], { name: 'globalOne', scope: 'user', transport: 'stdio', summary: 'node' })
  assert.equal(by['settings:fromSettings'].transport, 'stdio')
  assert.equal(by['local:localOne'].folder, folderId(app))
  assert.equal(by['local:localOne'].summary, 'sse.example.com')
  assert.equal(by['project:shared'].approval, 'pending')
  assert.equal(by['project:web'].summary, 'mcp.example.com')
  assert.equal(body.folders[0].id, HOME_FOLDER)
  assert.equal(body.folders[0].name, '~')
  assert.ok(body.folders.some((f: any) => f.id === folderId(app)))
})

test('a server\'s page: where, how, its hash; a value in no reply; refused ways in', async () => {
  const app = project('page')
  writeFileSync(join(app, '.mcp.json'), JSON.stringify({ mcpServers: { shared: planted } }))
  writeFileSync(CONFIG, JSON.stringify({ mcpServers: { globalOne: planted }, projects: { [app]: { mcpServers: { localOne: planted } } } }))
  const page = async (o: Record<string, string>) => get(`/v1/agents/mcp/server${q({ agent: 'claude', ...o })}`)
  for (const [scope, folder, where] of [['user', undefined, '~/.claude.json'], ['local', folderId(app), `under ${app.split('/').at(-1)}`], ['project', folderId(app), '/.mcp.json']] as const) {
    const name = { user: 'globalOne', local: 'localOne', project: 'shared' }[scope]
    const r = await page({ name, scope, ...(folder ? { folder } : {}) })
    assert.equal(r.status, 200, scope)
    const text = await r.text()
    assert.ok(!text.includes(PLANT), scope)
    assert.ok(!text.includes(scratch), scope)
    const b = JSON.parse(text)
    assert.ok(b.where.includes(where), b.where)
    assert.deepEqual(b.envNames, ['API_TOKEN', 'PATHY'])
    assert.equal(b.command, '/usr/bin/node')
    assert.ok(b.hash)
    assert.equal(b.editable, true)
  }
  assert.equal((await page({ name: 'nope', scope: 'user' })).status, 404)
  assert.equal((await page({ name: 'shared', scope: 'project', folder: 'not-a-folder' })).status, 404)
  assert.equal((await page({ name: '../etc', scope: 'user' })).status, 400)
  assert.equal((await page({ name: 'x', scope: 'bogus' })).status, 400)
  assert.equal((await get(`/v1/agents/mcp/server${q({ agent: 'other', name: 'x', scope: 'user' })}`)).status, 400)
  // A link as .mcp.json isn't followed.
  const linked = project('linked')
  symlinkSync(join(app, '.mcp.json'), join(linked, '.mcp.json'))
  writeFileSync(CONFIG, JSON.stringify({ projects: { [linked]: {} } }))
  assert.equal((await page({ name: 'shared', scope: 'project', folder: folderId(linked) })).status, 404)
})

test('only a paired phone, on every route', async () => {
  for (const path of ['/v1/agents/mcp?agent=claude', '/v1/agents/mcp/status?agent=claude', '/v1/agents/mcp/server?agent=claude&name=a&scope=user']) {
    assert.equal((await get(path, null)).status, 401, path)
    assert.equal((await get(path, 'wrong')).status, 401, path)
  }
})

test('approval is Claude Code\'s: a folder\'s own settings only once it\'s trusted; rejected wins; the user\'s count at once', () => {
  const app = project('approval')
  const write = (t: object) => {
    mkdirSync(join(app, '.claude'), { recursive: true })
    writeFileSync(join(app, '.claude', 'settings.local.json'), JSON.stringify(t))
  }
  const trusted = { projects: { [app]: { hasTrustDialogAccepted: true } } }
  assert.equal(approvalOf('a', app, {}), 'pending')
  write({ enabledMcpjsonServers: ['a'] })
  assert.equal(approvalOf('a', app, {}), 'pending')
  assert.equal(approvalOf('a', app, trusted), 'approved')
  assert.equal(approvalOf('b', app, trusted), 'pending')
  write({ enableAllProjectMcpServers: true })
  assert.equal(approvalOf('b', app, trusted), 'approved')
  write({ disabledMcpjsonServers: ['a'], enabledMcpjsonServers: ['a'] })
  assert.equal(approvalOf('a', app, trusted), 'rejected')
  rmSync(join(app, '.claude'), { recursive: true })
  writeFileSync(join(CLAUDE, 'settings.json'), JSON.stringify({ enabledMcpjsonServers: ['a'] }))
  assert.equal(approvalOf('a', app, {}), 'approved')
  // A folder below a trusted one is trusted too.
  assert.equal(approvalOf('c', join(app, 'sub'), trusted), 'pending')
})

test('status: a project server nobody approved is named as rejected to the process and said to be waiting; errors masked', async () => {
  const app = project('status')
  writeFileSync(join(app, '.mcp.json'), JSON.stringify({ mcpServers: { unapproved: planted } }))
  writeFileSync(CONFIG, JSON.stringify({ mcpServers: { globalOne: planted }, projects: { [app]: {} } }))
  const asked: string[][] = []
  useProbe(async (cwd, waiting, work) => {
    assert.equal(cwd, app)
    asked.push([...waiting])
    return work({
      mcpServerStatus: async () => [
        { name: 'globalOne', status: 'failed', source: 'user', error: `boom https://u:${PLANT}@x.example?key=${PLANT}`, config: planted, tools: [] },
        { name: 'plugin:ctx:ctx', status: 'connected', source: 'plugin', serverInfo: { name: 'ctx', version: '1' }, config: { type: 'stdio', command: 'npx', args: ['--token', PLANT] }, tools: [{ name: 'lookup', annotations: { readOnly: true } }] },
        { name: 'bad name\n', status: 'connected', source: 'user', tools: [] },
      ],
    } as any)
  })
  const r = await get(`/v1/agents/mcp/status${q({ agent: 'claude', folder: folderId(app) })}`)
  assert.equal(r.status, 200)
  const text = await r.text()
  assert.ok(!text.includes(PLANT), text)
  const body = JSON.parse(text)
  assert.deepEqual(asked, [['unapproved']])
  const by = Object.fromEntries(body.servers.map((s: any) => [s.name, s]))
  assert.equal(by.unapproved.status, 'waiting')
  assert.equal(by.globalOne.status, 'failed')
  assert.equal(by.globalOne.setup, undefined)
  assert.ok(by.globalOne.error.startsWith('boom'))
  assert.deepEqual(by['plugin:ctx:ctx'].tools, [{ name: 'lookup', readOnly: true }])
  assert.equal(by['plugin:ctx:ctx'].setup.command, 'npx')
  assert.equal(by['bad name\n'], undefined)
  // A moment later it's the same answer, not a second process.
  await get(`/v1/agents/mcp/status${q({ agent: 'claude', folder: folderId(app) })}`)
  assert.equal(asked.length, 1)
  await get(`/v1/agents/mcp/status${q({ agent: 'claude', folder: folderId(app), fresh: '1' })}`)
  assert.equal(asked.length, 2)
  // A plugin's page comes from the same answer, and can't be changed.
  const page = (await (await get(`/v1/agents/mcp/server${q({ agent: 'claude', name: 'plugin:ctx:ctx', scope: 'plugin', folder: folderId(app) })}`)).json()) as any
  assert.equal(page.editable, false)
  assert.equal(page.command, 'npx')
})

// ── A tool's detail, from the server itself (mcp-client.ts, mcp-tools.ts) ──

/** A tiny stdio MCP server, written fresh per test: it notes each start beside itself. */
function tinyServer(extraTools = ''): { file: string; marks: string } {
  const dir = project('tiny')
  const file = join(dir, 'server.mjs')
  const marks = join(dir, 'started.log')
  writeFileSync(file, `
import { appendFileSync } from 'node:fs'
import { createInterface } from 'node:readline'
appendFileSync(${JSON.stringify(marks)}, JSON.stringify({ token: process.env.API_TOKEN ?? null, args: process.argv.slice(2) }) + '\\n')
const send = (o) => process.stdout.write(JSON.stringify(o) + '\\n')
const tools = [
  { name: 'echo', description: 'Says back. See https://u:p@x.example/d?key=${PLANT}', annotations: { title: 'Echo', readOnlyHint: true }, inputSchema: { type: 'object', properties: { text: { type: 'string', description: 'What' }, times: { type: 'integer', default: 1 }, how: { enum: ['a', 'b'] }, list: { type: 'array', items: { type: 'string' } }, either: { anyOf: [{ type: 'string' }, { type: 'number' }] } }, required: ['text'] } },
  { name: 'wipe', annotations: { destructiveHint: true }, inputSchema: { type: 'object' } },${extraTools}
]
createInterface({ input: process.stdin }).on('line', (l) => {
  const m = JSON.parse(l)
  if (m.method === 'initialize') send({ jsonrpc: '2.0', id: m.id, result: { protocolVersion: m.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: 'tiny', version: '1.2.3' } } })
  else if (m.method === 'tools/list') send({ jsonrpc: '2.0', id: m.id, result: { tools } })
  else if (m.id !== undefined) send({ jsonrpc: '2.0', id: m.id, result: {} })
})
`)
  return { file, marks }
}
const { readFileSync, existsSync } = await import('node:fs')

test('a tool in full: description, annotations, parameters in words, schema; a secret in someone\'s description is masked; its env reached it', async () => {
  const { file, marks } = tinyServer()
  writeFileSync(CONFIG, JSON.stringify({ mcpServers: { tiny: { type: 'stdio', command: process.execPath, args: [file, '--flag', '${TINY_ARG:-fallback}'], env: { API_TOKEN: PLANT } } } }))
  const r = await get(`/v1/agents/mcp/tools${q({ agent: 'claude', name: 'tiny', scope: 'user' })}`)
  assert.equal(r.status, 200)
  const text = await r.text()
  assert.ok(!text.includes(PLANT), text)
  const b = JSON.parse(text)
  assert.equal(b.serverName, 'tiny')
  assert.equal(b.version, '1.2.3')
  const echo = b.tools.find((t: any) => t.name === 'echo')
  assert.ok(echo.description.startsWith('Says back.'))
  assert.deepEqual(echo.annotations, { title: 'Echo', readOnly: true })
  assert.deepEqual(echo.params.map((p: any) => [p.name, p.type, p.required]), [['text', 'string', true], ['times', 'integer', false], ['how', 'one of', false], ['list', 'array of string', false], ['either', 'string or number', false]])
  assert.equal(echo.params[0].description, 'What')
  assert.equal(echo.params[1].default, '1')
  assert.deepEqual(echo.params[2].choices, ['a', 'b'])
  assert.ok(echo.schema.includes('"required"'))
  assert.deepEqual(b.tools.find((t: any) => t.name === 'wipe').annotations, { destructive: true })
  const start = JSON.parse(readFileSync(marks, 'utf8').trim())
  assert.equal(start.token, PLANT)
  assert.deepEqual(start.args, ['--flag', 'fallback'])
  // A moment later, the same answer without starting it again.
  await get(`/v1/agents/mcp/tools${q({ agent: 'claude', name: 'tiny', scope: 'user' })}`)
  assert.equal(readFileSync(marks, 'utf8').trim().split('\n').length, 1)
})

test('a server that\'s off, or that nobody approved, isn\'t started to see its tools', async () => {
  const app = project('held')
  const { file, marks } = tinyServer()
  writeFileSync(join(app, '.mcp.json'), JSON.stringify({ mcpServers: { held: { command: process.execPath, args: [file] } } }))
  writeFileSync(CONFIG, JSON.stringify({ mcpServers: { off: { command: process.execPath, args: [file] } }, projects: { [app]: { disabledMcpServers: ['off'] } } }))
  const ask = async (o: Record<string, string>) => (await (await get(`/v1/agents/mcp/tools${q({ agent: 'claude', ...o })}`)).json()) as any
  assert.equal((await ask({ name: 'held', scope: 'project', folder: folderId(app) })).whyNot, 'approval')
  assert.equal((await ask({ name: 'off', scope: 'user', folder: folderId(app) })).whyNot, 'disabled')
  assert.equal((await ask({ name: 'plugin:ctx:ctx', scope: 'plugin' })).whyNot, 'no_direct')
  assert.equal(existsSync(marks), false)
})

test('the talk: a server that fails or hangs, and HTTP: JSON and a stream, a sign-in wanted, a redirect not followed', async () => {
  await assert.rejects(talkTo({ type: 'stdio', command: join(scratch, 'not-there') }, HOME, 2000), (e: any) => e instanceof TalkFailed && e.why === 'failed')
  await assert.rejects(talkTo({ type: 'stdio', command: process.execPath, args: ['-e', 'setInterval(()=>{},1000)'] }, HOME, 300), (e: any) => e instanceof TalkFailed && e.why === 'too_slow')
  await assert.rejects(talkTo({ type: 'ws', url: 'ws://x' }), (e: any) => e.why === 'no_direct')
  await assert.rejects(talkTo({ type: 'http', url: 'http://127.0.0.1:1', headersHelper: 'x' }), (e: any) => e.why === 'no_direct')

  const { createServer: http } = await import('node:http')
  const seen: Record<string, unknown>[] = []
  const srv = http((req, res) => {
    let b = ''
    req.on('data', (d) => (b += d))
    req.on('end', () => {
      if (req.url === '/redirect') {
        res.writeHead(302, { location: 'https://elsewhere.example/' })
        return res.end()
      }
      if (req.url === '/secure') {
        res.writeHead(401)
        return res.end()
      }
      const m = b ? JSON.parse(b) : {}
      seen.push({ url: req.url, auth: req.headers.authorization, session: req.headers['mcp-session-id'] })
      if (m.id === undefined) {
        res.writeHead(202)
        return res.end()
      }
      const result = m.method === 'initialize' ? { protocolVersion: '2025-06-18', capabilities: { tools: {} }, serverInfo: { name: 'web', version: '9' } } : { tools: [{ name: 'fetch', inputSchema: {} }] }
      const session = { 'mcp-session-id': 'sess-1' }
      if (req.url === '/stream') {
        res.writeHead(200, { 'content-type': 'text/event-stream', ...session })
        res.write(`data: ${JSON.stringify({ jsonrpc: '2.0', method: 'noise' })}\n\n`)
        res.end(`data: ${JSON.stringify({ jsonrpc: '2.0', id: m.id, result })}\n\n`)
      } else {
        res.writeHead(200, { 'content-type': 'application/json', ...session })
        res.end(JSON.stringify({ jsonrpc: '2.0', id: m.id, result }))
      }
    })
  })
  await new Promise<void>((r) => srv.listen(0, '127.0.0.1', r))
  const base = `http://127.0.0.1:${(srv.address() as { port: number }).port}`
  try {
    process.env.TINY_HEADER = 'from-env'
    for (const path of ['/json', '/stream']) {
      const facts = factsOf(await talkTo({ type: 'http', url: base + path, headers: { Authorization: 'Bearer ${TINY_HEADER}' } }))
      assert.equal(facts.serverName, 'web')
      assert.deepEqual(facts.tools.map((t) => t.name), ['fetch'])
    }
    assert.equal(seen[0]!.auth, 'Bearer from-env')
    assert.equal(seen.at(-1)!.session, 'sess-1')
    await assert.rejects(talkTo({ type: 'http', url: `${base}/secure` }), (e: any) => e.why === 'needs_auth')
    await assert.rejects(talkTo({ type: 'http', url: `${base}/redirect` }), (e: any) => e.why === 'failed')
  } finally {
    srv.closeAllConnections()
    srv.close()
  }
  assert.equal(expandVars('${A}/${B:-b}/${C}', { A: 'a' }), 'a/b/${C}')
})

test('the capped text: many tools, a long description, control characters', () => {
  const f = factsOf({ tools: [...Array(510).keys()].map((i) => ({ name: `t${i}`, description: 'x'.repeat(10000) + '\u0007' })), prompts: [{ name: 'p', arguments: [{ name: 'a' }] }], resources: [{ name: 'r', uri: 'r://1' }, {}], instructions: 7 })
  assert.equal(f.tools.length, 500)
  assert.equal(f.moreTools, 10)
  assert.ok(f.tools[0]!.description!.length <= 4000)
  assert.equal(f.instructions, undefined)
  assert.deepEqual(f.prompts, [{ name: 'p', args: ['a'] }])
  assert.deepEqual(f.resources, [{ name: 'r', uri: 'r://1' }])
})
