// One session's MCP servers (mcp-session.ts) for each kind of session, with the terminal played from real screens and stand-ins for the rest. Whether each
// reaches a real session's process is mcp-session-e2e.test.ts's; this is what's said, and when nothing's said.
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, beforeEach, test } from 'node:test'
import { playedTerm } from './played-mcp-term.ts'

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-mcp-session-')))
for (const k of Object.keys(process.env)) if (/^(CLAUDE|ANTHROPIC|HYPERNOVA|HN_)/.test(k)) delete process.env[k]
process.env.HOME = join(scratch, 'home')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.CODEX_HOME = join(scratch, 'codex')
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
for (const d of [process.env.HOME, process.env.CLAUDE_CONFIG_DIR, process.env.CODEX_HOME]) mkdirSync(d, { recursive: true })

const hub = await import('../src/hub.ts')
const { kindFrom, offers, outcomeOf, sessionMcpAct, sessionMcpLive, sessionMcpTools, useMcpTimes, useTermOps } = await import('../src/mcp-session.ts')
const { useProbe, useSaveWait } = await import('../src/mcp-probe.ts')
const { useCodex, useThreadRpc } = await import('../src/mcp-codex.ts')
const { testTurn } = await import('../src/runner.ts')
const { saveManaged } = await import('../src/store.ts')
const { saveThread } = await import('../src/codex/threads.ts')
const { McpRefused } = await import('../src/mcp-edit.ts')

useMcpTimes({ poll: 1, open: 200, act: 100, settle: 30, settlePoll: 1 })
useSaveWait(50)
const folder = join(scratch, 'app')
mkdirSync(folder, { recursive: true })
const SECRET = 'PLANTEDsecret0123456789abcdef'
const configFile = join(process.env.CLAUDE_CONFIG_DIR!, '.claude.json')
const config = (o: object = {}) => writeFileSync(configFile, JSON.stringify({ projects: { [folder]: { hasTrustDialogAccepted: true, mcpServers: { many: { command: '/usr/bin/many', args: ['--token', SECRET], env: { API_TOKEN: SECRET } } }, ...o } } }))
const secretFree = (v: unknown) => assert.ok(!JSON.stringify(v).includes(SECRET), `a secret in ${JSON.stringify(v)}`)
const refusal = async (p: Promise<unknown>, status: number, code: string) => {
  try {
    await p
  } catch (e) {
    assert.ok(e instanceof McpRefused, String(e))
    assert.equal(e.code, code)
    assert.equal(e.status, status)
    return e
  }
  throw new Error(`no refusal: wanted ${code}`)
}
const row = (r: { servers: { name: string }[] }, name: string) => r.servers.find((s) => s.name === name) as any
const terminal = (id: string) => hub.upsertSession(id, { cwd: folder, state: 'idle', term: { id: `t-${id}`, mac: 0, phone: 0 } as never })

beforeEach(() => config())
after(() => {
  useTermOps(null)
  useProbe(null)
  useCodex(null)
  useThreadRpc(null)
  rmSync(scratch, { recursive: true, force: true })
})

test('which kind a session is', () => {
  const all = { ended: false, codex: false, sdk: false, turn: false, term: false, job: false }
  assert.equal(kindFrom({ ...all, ended: true, term: true, codex: true }), 'ended')
  assert.equal(kindFrom({ ...all, codex: true }), 'codex')
  assert.equal(kindFrom({ ...all, sdk: true }), 'rest')
  assert.equal(kindFrom({ ...all, sdk: true, turn: true }), 'turn')
  assert.equal(kindFrom({ ...all, job: true }), 'job')
  assert.equal(kindFrom({ ...all, job: true, term: true }), 'job')
  assert.equal(kindFrom({ ...all, term: true }), 'terminal')
  assert.equal(kindFrom(all), 'watched')
})

test('what each kind offers', () => {
  assert.deepEqual(offers('terminal', true, 'connected'), ['reconnect', 'disable'])
  assert.deepEqual(offers('terminal', true, 'failed'), ['reconnect', 'disable'])
  assert.deepEqual(offers('terminal', true, 'needs-auth'), ['reconnect', 'disable'])
  assert.deepEqual(offers('terminal', true, 'disabled'), ['enable'])
  assert.deepEqual(offers('terminal', true, 'pending'), ['disable'])
  assert.deepEqual(offers('terminal', true, 'waiting'), [])
  assert.deepEqual(offers('terminal', true, 'rejected'), [])
  assert.deepEqual(offers('terminal', true, 'connected', { cut: true }), [])
  assert.deepEqual(offers('terminal', true, 'connected', { offForNew: true }), ['enable', 'disable'])
  assert.deepEqual(offers('job', true, 'connected'), ['reconnect', 'disable'])
  assert.deepEqual(offers('turn', true, 'connected'), ['reconnect', 'disable'])
  // Nothing's connected at rest, so nothing to reconnect.
  assert.deepEqual(offers('rest', false, 'connected'), ['disable'])
  assert.deepEqual(offers('rest', false, 'failed'), ['disable'])
  assert.deepEqual(offers('rest', false, 'disabled'), ['enable'])
  // Codex's reload leaves a healthy server alone (measured), so it's no reconnect.
  assert.deepEqual(offers('codex', true, 'connected'), ['disable'])
  assert.deepEqual(offers('codex', true, 'failed'), ['reconnect', 'disable'])
  assert.deepEqual(offers('codex', false, 'connected'), ['disable'])
  assert.deepEqual(offers('watched', false, 'connected'), [])
  assert.deepEqual(offers('ended', false, 'disabled'), [])
})

test('the state read back decides what was done, never the answer alone', () => {
  const ok = { said: 'ok' as const, words: 'x' }
  assert.equal(outcomeOf('reconnect', 'connected', ok), 'done')
  assert.equal(outcomeOf('reconnect', 'failed', ok), 'failed', 'it said so, and it isn’t')
  assert.equal(outcomeOf('reconnect', 'connected', null), 'unconfirmed', 'connected, but nothing says it’s a new connection')
  assert.equal(outcomeOf('reconnect', 'connected', { said: 'failed', words: 'x' }), 'failed')
  assert.equal(outcomeOf('reconnect', null, ok), 'failed', 'gone from the list')
  assert.equal(outcomeOf('disable', 'disabled', null), 'done')
  assert.equal(outcomeOf('disable', 'connected', ok), 'failed')
  assert.equal(outcomeOf('enable', 'connected', null), 'done')
  assert.equal(outcomeOf('enable', 'pending', null), 'done')
  assert.equal(outcomeOf('enable', 'failed', ok), 'failed', 'on, but it didn’t connect')
  assert.equal(outcomeOf('enable', 'connected', { said: 'other_session', words: 'x' }), 'no_change')
})

test('a terminal: read from its /mcp, closed again, nothing of its command in the answer', async () => {
  const p = playedTerm()
  useTermOps(p)
  terminal('s-read')
  const r = await sessionMcpLive('s-read')
  assert.equal(r.kind, 'terminal')
  assert.equal(r.live, true)
  assert.equal(p.shown(), 'box', 'left open')
  assert.deepEqual(p.typed, ['/mcp'])
  assert.equal(row(r, 'many').status, 'connected')
  assert.equal(row(r, 'many').toolCount, 30)
  assert.equal(row(r, 'many').transport, 'stdio')
  assert.equal(row(r, 'many').command, 'many', 'the same allow-list as the Home card’s sheet')
  assert.equal(row(r, 'broken').status, 'failed')
  assert.deepEqual(row(r, 'broken').actions, ['reconnect', 'disable'])
  secretFree(r)
  assert.ok(!JSON.stringify(r).includes('--token'))
  assert.ok(!JSON.stringify(r).includes('.claude.json'))
})

test('a terminal with no servers: empty, with nothing left to close', async () => {
  const p = playedTerm({ list: 'result-no-servers' })
  const type = p.type
  let added = false
  p.type = async (t, x) => {
    added = true
    return x === '/mcp' ? { ok: true } : type(t, x)
  }
  const screen = p.screen
  p.screen = async (t) => (added ? `${await screen(t)}\n  ⎿  No MCP servers configured. Run claude doctor` : screen(t))
  useTermOps(p)
  terminal('s-none')
  const r = await sessionMcpLive('s-none')
  assert.deepEqual(r.servers, [])
  assert.equal(r.unread, undefined)
  assert.deepEqual(p.pressed, [], 'Esc with nothing open stops a turn')
})

test('a terminal that can’t be read says why, and no rows pretend to be it', async () => {
  for (const [reason, code] of [['no_input', 'waiting'], ['busy_input', 'typing'], ['not_started', 'starting'], ['not_seen', 'not_sent'], ['unreachable', 'terminal_busy']] as const) {
    const p = playedTerm()
    p.refuse = reason
    useTermOps(p)
    terminal(`s-${reason}`)
    const r = await sessionMcpLive(`s-${reason}`)
    assert.equal(r.unread?.code, code)
    assert.ok(r.unread?.words)
    assert.deepEqual(r.servers, [])
    p.refuse = reason
    await refusal(sessionMcpAct(`s-${reason}`, 'reconnect', 'many'), 409, code)
    assert.deepEqual(p.pressed, [], 'nothing pressed')
  }
})

test('a screen that never shows /mcp is said, and no Esc goes in', async () => {
  const p = playedTerm()
  const type = p.type
  p.type = async (t, x) => {
    const r = await type(t, x)
    return r
  }
  const screen = p.screen
  p.screen = async () => 'something else altogether'
  void screen
  useTermOps(p)
  terminal('s-unseen')
  const r = await sessionMcpLive('s-unseen')
  assert.equal(r.unread?.code, 'unseen')
  assert.deepEqual(p.pressed, [], 'Esc on a screen that isn’t its dialog would stop a turn')
})

test('reconnect: typed, answered, read back', async () => {
  const p = playedTerm({ answers: { '/mcp reconnect many': '  ⎿  Successfully reconnected to many' } })
  useTermOps(p)
  terminal('s-rec')
  const a = await sessionMcpAct('s-rec', 'reconnect', 'many')
  assert.deepEqual(p.typed, ['/mcp', '/mcp reconnect many', '/mcp'])
  assert.equal(a.outcome, 'done')
  assert.equal(a.said, 'Successfully reconnected to many')
  assert.equal(a.server!.status, 'connected')
  assert.equal(p.shown(), 'box')
  assert.equal(row(a.reply, 'broken').status, 'failed', 'the whole list comes back')
})

test('a reconnect that fails says so, however it answered', async () => {
  const p = playedTerm({ answers: { '/mcp reconnect broken': '  ⎿  Failed to reconnect to broken' } })
  useTermOps(p)
  terminal('s-fail')
  const a = await sessionMcpAct('s-fail', 'reconnect', 'broken')
  assert.equal(a.outcome, 'failed')
  assert.equal(a.said, 'Failed to reconnect to broken')
  assert.equal(a.server!.status, 'failed')
})

test('no answer on the screen, and connected: not said to have reconnected', async () => {
  const p = playedTerm()
  useTermOps(p)
  terminal('s-quiet')
  const a = await sessionMcpAct('s-quiet', 'reconnect', 'many')
  assert.equal(a.outcome, 'unconfirmed')
})

test('an older answer of the same words on the screen isn’t this one', async () => {
  const p = playedTerm({ box: 'result-reconnected' })
  useTermOps(p)
  terminal('s-old')
  const a = await sessionMcpAct('s-old', 'reconnect', 'many')
  assert.equal(a.outcome, 'unconfirmed', 'that answer was already there')
})

test('disable and enable: what’s read back, whichever the screen says', async () => {
  const p = playedTerm({ answers: { '/mcp disable tiny': '  ⎿  MCP server "tiny" disabled' }, list: 'busy-list-disabled' })
  useTermOps(p)
  terminal('s-off')
  const off = await sessionMcpAct('s-off', 'disable', 'tiny')
  assert.equal(off.outcome, 'done')
  assert.equal(off.server!.status, 'disabled')
  assert.deepEqual(off.server!.actions, ['enable'])
  // Said to be off, and it isn't.
  const q = playedTerm({ answers: { '/mcp disable many': '  ⎿  MCP server "many" disabled' } })
  useTermOps(q)
  terminal('s-liar')
  assert.equal((await sessionMcpAct('s-liar', 'disable', 'many')).outcome, 'failed')
})

test('turned off for new sessions elsewhere: said on the row, and on again only changes the list', async () => {
  config({ disabledMcpServers: ['many'] })
  let toggled: boolean | null = null
  useProbe(async (_c, _w, work) =>
    work({
      mcpServerStatus: async () => [],
      reconnectMcpServer: async () => {},
      toggleMcpServer: async (_n: string, on: boolean) => {
        toggled = on
        config()
      },
    } as never),
  )
  const p = playedTerm()
  useTermOps(p)
  terminal('s-new')
  const r = await sessionMcpLive('s-new')
  assert.equal(row(r, 'many').status, 'connected')
  assert.equal(row(r, 'many').offForNew, true)
  assert.deepEqual(row(r, 'many').actions, ['enable', 'disable'])
  const a = await sessionMcpAct('s-new', 'enable', 'many')
  assert.equal(a.outcome, 'new_sessions')
  assert.equal(toggled, true)
  assert.ok(!p.typed.some((t) => t.startsWith('/mcp enable')), '/mcp wouldn’t do it, and says so')
  assert.equal(row(a.reply, 'many').offForNew, undefined)
})

test('/mcp’s own word for another session’s off comes through as no change', async () => {
  const p = playedTerm({ answers: { '/mcp enable many': `  ⎿  "many" is still available in this session, but another session disabled it — it keeps working here and\n     won't reconnect after the next launch. Disable and re-enable it in /mcp to persist the re-enable.` } })
  useTermOps(p)
  terminal('s-other')
  const a = await sessionMcpAct('s-other', 'enable', 'many')
  assert.equal(a.outcome, 'no_change')
  assert.match(a.said!, /another session disabled it/)
})

test('an action that can’t be read back is unconfirmed, with why', async () => {
  const p = playedTerm({ answers: { '/mcp reconnect many': '  ⎿  Successfully reconnected to many' } })
  const type = p.type
  p.type = async (t, x) => {
    const r = await type(t, x)
    // A question comes up over the box just after it's typed.
    if (x.startsWith('/mcp reconnect')) p.refuse = 'no_input'
    return r
  }
  useTermOps(p)
  terminal('s-unconfirmed')
  const a = await sessionMcpAct('s-unconfirmed', 'reconnect', 'many')
  assert.equal(a.outcome, 'unconfirmed')
  assert.equal(a.reply.unread?.code, 'waiting')
  assert.equal(a.said, 'Successfully reconnected to many')
})

test('refused before anything is typed: an unknown server, a project’s not approved, a cut name, “all”', async () => {
  const p = playedTerm()
  useTermOps(p)
  terminal('s-no')
  await refusal(sessionMcpAct('s-no', 'reconnect', 'nosuch'), 404, 'no_server')
  assert.ok(!p.typed.some((t) => t.includes('nosuch')))
  const cut = playedTerm({ list: 'list-narrow-cut' })
  useTermOps(cut)
  terminal('s-cut')
  const r = await sessionMcpLive('s-cut')
  const first = r.servers[0] as any
  assert.equal(first.cut, true)
  assert.deepEqual(first.actions, [])
  await refusal(sessionMcpAct('s-cut', 'reconnect', 'x y\nz'), 400, 'bad_name')
  await refusal(sessionMcpAct('s-cut', 'explode', 'many'), 400, 'bad_action')
  await refusal(sessionMcpAct('s-gone', 'reconnect', 'many'), 404, 'no_session')
})

test('a project’s servers it never started are rows of their own, with nothing to do from here', async () => {
  writeFileSync(join(folder, '.mcp.json'), JSON.stringify({ mcpServers: { waits: { command: 'x', args: [] } } }))
  const p = playedTerm()
  useTermOps(p)
  terminal('s-held')
  const r = await sessionMcpLive('s-held')
  assert.equal(row(r, 'waits').status, 'waiting')
  assert.deepEqual(row(r, 'waits').actions, [])
  await refusal(sessionMcpAct('s-held', 'reconnect', 'waits'), 404, 'no_server')
  rmSync(join(folder, '.mcp.json'))
})

test('a cut name a file has is whole, and acts', async () => {
  const name = 'srv-00-with-a-rather-long-name-indeed'
  config({ mcpServers: { [name]: { command: 'x', args: [] } } })
  const p = playedTerm({ list: 'list-narrow-cut' })
  useTermOps(p)
  terminal('s-whole')
  const r = await sessionMcpLive('s-whole')
  assert.equal(r.servers[0]!.name, name)
  assert.equal((r.servers[0] as any).cut, undefined)
})

test('a list too long for the screen is paged through', async () => {
  const p = playedTerm({ list: 'list-narrow-cut' })
  const screen = p.screen
  let down = 0
  const keys = p.keys
  p.keys = (t, d) => {
    if (d === '\x1b[B') down++
    return keys(t, d)
  }
  p.screen = async (t) => (down > 0 && p.shown() === 'list' ? (await import('./played-mcp-term.ts')).screenFile('list-narrow-cut-end') : screen(t))
  useTermOps(p)
  terminal('s-long')
  const r = await sessionMcpLive('s-long')
  assert.ok(down > 0)
  assert.ok(r.servers.some((s) => s.name === 'usertiny'), 'the row past the first screen')
  assert.equal(p.shown(), 'box')
})

test('a server’s tools: through its page, and back out to the box', async () => {
  const p = playedTerm()
  useTermOps(p)
  terminal('s-tools')
  const t = await sessionMcpTools('s-tools', 'many')
  assert.equal(t.live, true)
  assert.equal(t.tools[0]!.name, 'tool_00')
  assert.equal(t.tools[0]!.readOnly, true)
  assert.equal(t.tools[1]!.destructive, true)
  assert.ok(t.tools.length >= 25)
  assert.equal(p.shown(), 'box')
  // A page with no tools option has none to show.
  const q = playedTerm({ page: 'page-failed' })
  useTermOps(q)
  terminal('s-no-tools')
  assert.deepEqual((await sessionMcpTools('s-no-tools', 'broken')).tools, [])
  assert.equal(q.shown(), 'box')
  await refusal(sessionMcpTools('s-tools', 'nosuch'), 404, 'no_server')
})

// ── What has no terminal ──

const probe = (calls: string[] = []) =>
  useProbe(async (_c, _w, work) =>
    work({
      mcpServerStatus: async () => [
        { name: 'many', status: 'connected', source: 'local', tools: [{ name: 'echo', annotations: { readOnly: true } }] },
        { name: 'bad', status: 'failed', source: 'user', error: `no --token ${SECRET}`, tools: [] },
      ],
      reconnectMcpServer: async (n: string) => void calls.push(`reconnect ${n}`),
      toggleMcpServer: async (n: string, on: boolean) => {
        calls.push(`toggle ${n} ${on}`)
        config(on ? {} : { disabledMcpServers: [n] })
      },
    } as never),
  )

test('a terminal not shared: what a new session would get, not its connections, and nothing to do', async () => {
  probe()
  hub.upsertSession('s-watched', { cwd: folder, state: 'idle' })
  const r = await sessionMcpLive('s-watched', { fresh: true })
  assert.equal(r.kind, 'watched')
  assert.equal(r.live, false)
  assert.deepEqual(row(r, 'many').actions, [])
  secretFree(r)
  await refusal(sessionMcpAct('s-watched', 'reconnect', 'many'), 409, 'not_here')
  await refusal(sessionMcpAct('s-watched', 'disable', 'many'), 409, 'not_here')
})

test('ended: the same', async () => {
  probe()
  hub.upsertSession('s-ended', { cwd: folder, state: 'ended' })
  const r = await sessionMcpLive('s-ended', { fresh: true })
  assert.equal(r.kind, 'ended')
  assert.equal(r.live, false)
  assert.deepEqual(row(r, 'many').actions, [])
  await refusal(sessionMcpAct('s-ended', 'enable', 'many'), 409, 'not_here')
})

const sdk = (id: string) => {
  saveManaged({ id, cwd: folder, providerId: 'anthropic', permissionMode: 'default', createdAt: Date.now() } as never)
  hub.upsertSession(id, { cwd: folder, state: 'idle', origin: 'hypernova' })
}

test('an SDK session at rest: no reconnect, and a toggle is for its next turn', async () => {
  const calls: string[] = []
  probe(calls)
  sdk('s-rest')
  const r = await sessionMcpLive('s-rest', { fresh: true })
  assert.equal(r.kind, 'rest')
  assert.equal(r.live, false)
  assert.deepEqual(row(r, 'many').actions, ['disable'])
  await refusal(sessionMcpAct('s-rest', 'reconnect', 'many'), 409, 'not_here')
  const a = await sessionMcpAct('s-rest', 'disable', 'many')
  assert.equal(a.outcome, 'next_turn')
  assert.deepEqual(calls, ['toggle many false'])
  assert.ok(JSON.parse(readFileSync(configFile, 'utf8')).projects[folder].disabledMcpServers.includes('many'))
})

test('an SDK session in a turn: its own process, whatever happened read back', async () => {
  sdk('s-turn')
  const state: Record<string, string> = { many: 'connected', bad: 'failed' }
  const calls: string[] = []
  const q = {
    setPermissionMode: async () => {},
    mcpServerStatus: async () => Object.entries(state).map(([name, status]) => ({ name, status, source: 'local', tools: [{ name: 'echo' }], ...(status === 'failed' ? { error: `no --token ${SECRET}` } : {}) })),
    reconnectMcpServer: async (n: string) => {
      calls.push(`reconnect ${n}`)
      if (n === 'bad') throw new Error(`Connection closed --token ${SECRET}`)
    },
    toggleMcpServer: async (n: string, on: boolean) => {
      calls.push(`toggle ${n} ${on}`)
      state[n] = on ? 'connected' : 'disabled'
    },
  }
  testTurn('s-turn', q as never)
  const r = await sessionMcpLive('s-turn')
  assert.equal(r.kind, 'turn')
  assert.equal(r.live, true)
  assert.equal(row(r, 'many').toolCount, 1)
  assert.deepEqual(row(r, 'many').tools, [{ name: 'echo' }])
  secretFree(r)
  assert.equal((await sessionMcpTools('s-turn', 'many')).tools[0]!.name, 'echo')
  const again = await sessionMcpAct('s-turn', 'reconnect', 'many')
  assert.equal(again.outcome, 'done')
  const bad = await sessionMcpAct('s-turn', 'reconnect', 'bad')
  assert.equal(bad.outcome, 'failed')
  secretFree(bad)
  assert.match(bad.said!, /Connection closed/)
  const off = await sessionMcpAct('s-turn', 'disable', 'many')
  assert.equal(off.outcome, 'done')
  assert.equal(off.server!.status, 'disabled')
  assert.deepEqual(calls, ['reconnect many', 'reconnect bad', 'toggle many false'])
  // The turn ended before an action: at rest now, and said as that.
  testTurn('s-turn', null)
  probe()
  assert.equal((await sessionMcpLive('s-turn', { fresh: true })).kind, 'rest')
  await refusal(sessionMcpAct('s-turn', 'reconnect', 'many'), 409, 'not_here')
})

// ── Codex ──

const codexList = (extra: object[] = []) => ({ stdout: JSON.stringify([{ name: 'fetch', enabled: true, transport: { type: 'stdio', command: 'x', args: [] } }, ...extra]), stderr: '', code: 0 })
const thread = (id: string) => {
  saveThread({ id, cwd: folder, title: '[Hypernova test]', createdAt: Date.now(), lastActivityAt: Date.now(), origin: 'hypernova', started: true } as never)
  hub.upsertSession(id, { cwd: folder, state: 'idle', agent: 'codex' as never })
}

test('Codex: a loaded thread’s own status; on and off everywhere, read back; a reload only for what’s down', async () => {
  thread('th-1')
  let runtime = { fetch: 'failed' } as Record<string, string>
  let afterReload = 'connected'
  const calls: string[] = []
  useThreadRpc(async (method, params: any) => {
    calls.push(method)
    if (method === 'mcpServerStatus/list') {
      assert.equal(params.threadId, 'th-1')
      return { data: Object.entries(runtime).map(([name, runtimeStatus]) => ({ name, runtimeStatus, tools: { echo: { name: 'echo', inputSchema: {} } }, serverInfo: { name, version: '1' }, authStatus: 'unsupported', toolsError: null, resources: [] })) }
    }
    if (method === 'config/mcpServer/reload') runtime = { fetch: afterReload }
    return {}
  })
  useCodex({ cli: async () => codexList(), rpc: async (work) => work(async () => ({})) })
  const r = await sessionMcpLive('th-1')
  assert.equal(r.agent, 'codex')
  assert.equal(r.kind, 'codex')
  assert.equal(r.live, true)
  assert.equal(row(r, 'fetch').status, 'failed')
  assert.deepEqual(row(r, 'fetch').actions, ['reconnect', 'disable'])
  const again = await sessionMcpAct('th-1', 'reconnect', 'fetch')
  assert.equal(again.outcome, 'done')
  assert.ok(calls.includes('config/mcpServer/reload'))
  assert.deepEqual(again.server!.actions, ['disable'], 'a healthy one has nothing to reconnect')
  afterReload = 'disabled'
  const off = await sessionMcpAct('th-1', 'disable', 'fetch')
  assert.equal(off.outcome, 'done')
  assert.equal(off.server!.status, 'disabled')
})

test('Codex: a thread not loaded isn’t said to be connected; a toggle counts from its next turn', async () => {
  thread('th-2')
  useThreadRpc(async (method) => {
    if (method === 'mcpServerStatus/list') throw new Error('thread not found: th-2')
    return {}
  })
  useCodex({ cli: async () => codexList(), rpc: async (work) => work(async (m: string) => (m === 'mcpServerStatus/list' ? { data: [{ name: 'fetch', tools: {}, serverInfo: { name: 'fetch' }, authStatus: 'unsupported', toolsError: null }] } : {})) })
  const r = await sessionMcpLive('th-2', { fresh: true })
  assert.equal(r.live, false)
  assert.equal(row(r, 'fetch').status, 'connected')
  assert.deepEqual(row(r, 'fetch').actions, ['disable'])
  const off = await sessionMcpAct('th-2', 'disable', 'fetch')
  assert.equal(off.outcome, 'next_turn')
})

test('Codex: no connection to the shared app-server, so a reconnect says nothing reaches it', async () => {
  thread('th-3')
  useThreadRpc(null)
  useCodex({ cli: async () => codexList(), rpc: async (work) => work(async (m: string) => (m === 'mcpServerStatus/list' ? { data: [{ name: 'fetch', tools: {}, serverInfo: { name: 'fetch' }, authStatus: 'unsupported', toolsError: null }] } : {})) })
  assert.equal((await sessionMcpLive('th-3', { fresh: true })).live, false)
  await refusal(sessionMcpAct('th-3', 'reconnect', 'fetch'), 409, 'not_here')
})
