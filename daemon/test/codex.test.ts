// Codex as a second agent (docs/PROTOCOL.md, "Codex"; docs/specs/codex-app-server.md), end to end
// through the daemon's routes against a stand-in for Codex's app-server, on scratch state.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, realpathSync, rmSync, writeFileSync, chmodSync } from 'node:fs'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { FakeCodex, fakeCodexBin } from './fake-codex/server.ts'

const scratch = mkdtempSync(join(tmpdir(), 'hn-cx-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
mkdirSync(process.env.HOME, { recursive: true })
mkdirSync(join(scratch, 'home', 'Programming', 'demo'), { recursive: true })
// As the daemon resolves it (macOS's /var is /private/var).
const project = realpathSync(join(scratch, 'home', 'Programming', 'demo'))
const fakeClaude = join(scratch, 'claude-bin')
writeFileSync(fakeClaude, '#!/bin/sh\necho "9.9.9 (Claude Code)"\n')
chmodSync(fakeClaude, 0o755)
process.env.CLAUDE_BIN = fakeClaude
process.env.HYPERNOVA_CODEX = fakeCodexBin(join(scratch, 'codex'))
process.env.HYPERNOVA_CODEX_SOCKET = join(scratch, 'cx.sock')
process.env.HYPERNOVA_LAN_PORT = '0'

const freePort = () =>
  new Promise<number>((resolve) => {
    const s = createServer()
    s.listen(0, '127.0.0.1', () => {
      const port = (s.address() as { port: number }).port
      s.close(() => resolve(port))
    })
  })
const LOCAL = await freePort()
process.env.HYPERNOVA_API_PORT = String(await freePort())
process.env.HYPERNOVA_LOCAL_PORT = String(LOCAL)

const codex = new FakeCodex()
await codex.start(process.env.HYPERNOVA_CODEX_SOCKET)
const { startServers } = await import('../src/http.ts')
const { adminToken, settings } = await import('../src/store.ts')
const { listSessions } = await import('../src/hub.ts')
const { dropCodexForTests } = await import('../src/codex/client.ts')

let close: () => Promise<void>
before(async () => {
  close = startServers()
  for (let i = 0; i < 50; i++) {
    try {
      if ((await call('GET', '/v1/health')).status === 200) return
    } catch {}
    await sleep(20)
  }
})
after(async () => {
  await close?.()
  await codex.close()
  rmSync(scratch, { recursive: true, force: true })
})

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
async function call(method: string, path: string, body?: unknown): Promise<{ status: number; body: any }> {
  const r = await fetch(`http://127.0.0.1:${LOCAL}${path}`, { method, headers: { authorization: `Bearer ${adminToken}`, 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
  return { status: r.status, body: await r.json() }
}
const session = (id: string) => listSessions().find((s) => s.id === id)
async function until(f: () => boolean, ms = 3000): Promise<void> {
  for (let t = 0; t < ms && !f(); t += 20) await sleep(20)
  assert.ok(f(), 'timed out waiting')
}
const events = async (id: string) => (await call('GET', `/v1/sessions/${id}/events`)).body.events as any[]

test('a new Codex session starts its thread and its first turn, and never resumes a thread it just made', async () => {
  const r = await call('POST', '/v1/sessions', { agent: 'codex', projectPath: project, prompt: '[Hypernova test] Say OK.', clientId: 'c-1' })
  assert.equal(r.status, 200, JSON.stringify(r.body))
  const id = r.body.session.id
  assert.equal(r.body.session.agent, 'codex')
  assert.equal(r.body.session.providerId, 'codex')
  assert.equal(r.body.session.permissionMode, 'read-only')
  // The first phone session turned Codex on for this computer.
  assert.equal(settings.codex, true)
  const start = await codex.waitFor('thread/start')
  assert.equal(start.params.cwd, project)
  assert.equal(start.params.sandbox, 'read-only')
  assert.equal(start.params.approvalPolicy, 'on-request')
  const turn = await codex.waitFor('turn/start')
  assert.equal(turn.params.threadId, id)
  assert.equal(turn.params.clientUserMessageId, 'c-1')
  await until(() => session(id)?.state === 'idle' && session(id)?.lastText === 'OK')
  assert.equal(codex.count('thread/resume'), 0, 'a fresh thread has nothing to resume: Codex refuses it (no rollout)')
  const evs = await events(id)
  assert.deepEqual(evs.map((e) => e.kind), ['user', 'assistant', 'result'])
  // The prompt shows once: Codex's echo of it is the same event.
  assert.equal(evs[0].clientId, 'c-1')
  assert.equal(evs[1].text, 'OK')
  assert.equal(evs[1].streaming, false)
})

test('the snapshot says what Codex is, with its usage windows and no email', async () => {
  const r = await call('GET', '/v1/snapshot')
  const c = r.body.codex
  assert.equal(c.available, true)
  assert.equal(c.enabled, true)
  assert.equal(c.version, '0.163.0')
  await until(() => !!listSessions().length)
  const st = (await call('GET', '/v1/codex')).body.codex
  assert.equal(st.account.signedIn, true)
  assert.equal(st.account.planType, 'plus')
  assert.equal(st.usage.fiveHour.pct, 12)
  assert.equal(st.usage.sevenDay.pct, 40)
  assert.ok(!JSON.stringify(r.body).includes('owner@example.com'), 'the email never leaves the daemon')
})

test('a prompt while a turn runs waits in the queue, and goes once the turn ends', async () => {
  codex.autoTurn = false
  try {
    const id = (await call('POST', '/v1/sessions', { agent: 'codex', projectPath: project, prompt: 'first' })).body.session.id
    await until(() => codex.running.has(id))
    codex.notify('turn/started', { threadId: id, turn: { id: codex.running.get(id), status: 'inProgress', items: [] } })
    await until(() => session(id)?.state === 'working')
    const q = await call('POST', `/v1/sessions/${id}/prompt`, { text: 'second', clientId: 'c-2' })
    assert.equal(q.body.queued, true)
    assert.equal(session(id)?.queued, 1)
    const before = codex.count('turn/start')
    codex.end(id, 'completed')
    await until(() => codex.count('turn/start') === before + 1)
    assert.equal(session(id)?.queued, 0)
    codex.end(id, 'completed')
  } finally {
    codex.autoTurn = true
  }
})

test('Stop interrupts the turn and gives back the prompts that waited, named', async () => {
  codex.autoTurn = false
  try {
    const id = (await call('POST', '/v1/sessions', { agent: 'codex', projectPath: project, prompt: 'long one' })).body.session.id
    await until(() => codex.running.has(id))
    codex.notify('turn/started', { threadId: id, turn: { id: codex.running.get(id), status: 'inProgress', items: [] } })
    await until(() => session(id)?.state === 'working')
    await call('POST', `/v1/sessions/${id}/prompt`, { text: 'never sent', clientId: 'c-3' })
    assert.equal((await call('POST', `/v1/sessions/${id}/interrupt`)).status, 200)
    await codex.waitFor('turn/interrupt', (p) => p.threadId === id)
    await until(() => session(id)?.state === 'idle')
    const evs = await events(id)
    const dropped = evs.find((e) => e.kind === 'notice' && e.unsent)
    assert.deepEqual(dropped.unsent, { reason: 'dropped', prompts: [{ text: 'never sent', clientId: 'c-3' }] })
    // One "Stopped." only: the notice that named the dropped prompt said it.
    assert.equal(evs.filter((e) => e.kind === 'notice' && /Stopped/.test(e.text)).length, 1)
  } finally {
    codex.autoTurn = true
  }
})

test('an approval Codex asks shows as a card; the phone’s answer goes back to Codex', async () => {
  const id = (await call('POST', '/v1/sessions', { agent: 'codex', projectPath: project, prompt: 'write a file' })).body.session.id
  await until(() => session(id)?.state === 'idle')
  const rid = codex.request('item/commandExecution/requestApproval', { threadId: id, turnId: 't', itemId: 'exec-1', command: "/bin/zsh -lc 'touch a.txt'", commandActions: [{ type: 'unknown', command: 'touch a.txt' }], cwd: project, reason: 'The sandbox is read-only.' })
  await until(() => !!session(id)?.pendingApprovalIds.length)
  const snap = (await call('GET', '/v1/snapshot')).body
  const a = snap.approvals.find((x: any) => x.sessionId === id)
  assert.equal(a.toolName, 'Bash')
  assert.equal(a.summary, 'Bash: touch a.txt')
  assert.equal(a.reason, 'The sandbox is read-only.')
  assert.equal(session(id)?.state, 'needs_you')
  assert.equal((await call('POST', `/v1/approvals/${a.id}`, { decision: 'allow' })).status, 200)
  await until(() => codex.answers.has(rid))
  assert.deepEqual(codex.answers.get(rid), { decision: 'accept' })
})

test('an approval a terminal answered first goes from the phone too', async () => {
  const id = (await call('POST', '/v1/sessions', { agent: 'codex', projectPath: project, prompt: 'edit' })).body.session.id
  await until(() => session(id)?.state === 'idle')
  const rid = codex.request('item/fileChange/requestApproval', { threadId: id, turnId: 't', itemId: 'fc-1', reason: null })
  await until(() => !!session(id)?.pendingApprovalIds.length)
  codex.notify('serverRequest/resolved', { threadId: id, requestId: rid })
  await until(() => !session(id)?.pendingApprovalIds.length)
  assert.equal(codex.answers.has(rid), false, 'the phone answers nothing once another client did')
})

test('mode and model take effect from the next turn; Claude-only actions are refused', async () => {
  const id = (await call('POST', '/v1/sessions', { agent: 'codex', projectPath: project, prompt: 'one' })).body.session.id
  await until(() => session(id)?.state === 'idle')
  assert.equal((await call('POST', `/v1/sessions/${id}/mode`, { permissionMode: 'acceptEdits' })).status, 400)
  assert.equal((await call('POST', `/v1/sessions/${id}/mode`, { permissionMode: 'workspace-write' })).status, 200)
  assert.equal((await call('POST', `/v1/sessions/${id}/model`, { model: 'gpt-other', effort: 'high' })).status, 400, 'an effort the model doesn’t offer')
  assert.equal((await call('POST', `/v1/sessions/${id}/model`, { model: 'gpt-other', effort: 'medium' })).status, 200)
  const n = codex.count('turn/start')
  await call('POST', `/v1/sessions/${id}/prompt`, { text: 'two' })
  const t = await codex.waitFor('turn/start', (p) => p.threadId === id && p.input?.[0]?.text === 'two')
  assert.equal(codex.count('turn/start'), n + 1)
  assert.deepEqual(t.params.sandboxPolicy, { type: 'workspaceWrite', writableRoots: [], networkAccess: false, excludeTmpdirEnvVar: false, excludeSlashTmp: false })
  assert.equal(t.params.model, 'gpt-other')
  assert.equal(t.params.effort, 'medium')
  await until(() => session(id)?.state === 'idle')
  // The model once; the sandbox and asking with every turn Hypernova starts, whatever a terminal set meanwhile.
  await call('POST', `/v1/sessions/${id}/prompt`, { text: 'three' })
  const t3 = await codex.waitFor('turn/start', (p) => p.input?.[0]?.text === 'three')
  assert.equal(t3.params.model, undefined)
  assert.equal(t3.params.sandboxPolicy.type, 'workspaceWrite')
  assert.equal(t3.params.approvalPolicy, 'on-request')
  for (const [path, body] of [['rewind', { eventId: 'x' }], ['provider', { providerId: 'anthropic' }], ['auto-continue', { on: true }]] as const) {
    const r = await call('POST', `/v1/sessions/${id}/${path}`, body)
    assert.equal(r.status, 409, path)
    assert.equal(r.body.error.code, 'unsupported_for_agent', path)
  }
})

test('a switch carries the approved text: to Claude as its system prompt, back to Codex put into the thread', async () => {
  codex.reply = 'Made notes.txt'
  const cx = (await call('POST', '/v1/sessions', { agent: 'codex', projectPath: project, prompt: 'make notes.txt' })).body.session.id
  await until(() => session(cx)?.state === 'idle' && session(cx)?.lastText === 'Made notes.txt')
  codex.reply = 'OK'
  const p = (await call('POST', `/v1/sessions/${cx}/switch/preview`, { to: 'claude', context: 'recent' })).body
  assert.equal(p.messages, 2)
  assert.match(p.text, /^User: make notes\.txt\nCodex: Made notes\.txt$/)
  assert.equal(p.maxBytes, 96 * 1024)
  assert.equal((await call('POST', `/v1/sessions/${cx}/switch/preview`, { to: 'codex', context: 'recent' })).body.error.code, 'same_agent')
  const big = (await call('POST', `/v1/sessions/${cx}/switch`, { to: 'claude', text: 'x'.repeat(97 * 1024) })).body
  assert.equal(big.error.code, 'context_too_large')
  const sw = await call('POST', `/v1/sessions/${cx}/switch`, { to: 'claude', text: p.text })
  assert.equal(sw.status, 200, JSON.stringify(sw.body))
  const cl = sw.body.session.id
  assert.equal(sw.body.session.agent, undefined, 'a Claude session carries no agent field')
  assert.equal(sw.body.session.switchedFrom, cx)
  assert.equal(session(cx)?.switchedTo, cl)
  assert.equal(session(cl)?.state, 'idle', 'a switch runs nothing until the next message')
  const { getManaged } = await import('../src/store.ts')
  assert.match(getManaged(cl)!.context!, /switched to you in Hypernova[\s\S]*<previous_conversation>\nUser: make notes\.txt\nCodex: Made notes\.txt\n<\/previous_conversation>/)
  // The old session takes no prompts: the phone goes to the new one.
  const refused = await call('POST', `/v1/sessions/${cx}/prompt`, { text: 'hello?' })
  assert.equal(refused.status, 409)
  assert.equal(refused.body.error.code, 'switched')
  assert.equal(refused.body.error.to, cl)
  assert.equal((await call('POST', `/v1/sessions/${cx}/switch`, { to: 'claude', text: '' })).body.error.code, 'switched', 'it went on once already')

  // Back to Codex with the whole conversation: a new thread, the text put in, no turn.
  const all = (await call('POST', `/v1/sessions/${cl}/switch/preview`, { to: 'codex', context: 'all' })).body
  assert.match(all.text, /make notes\.txt/)
  const turns = codex.count('turn/start')
  const back = await call('POST', `/v1/sessions/${cl}/switch`, { to: 'codex', text: all.text, permissionMode: 'workspace-write' })
  assert.equal(back.status, 200, JSON.stringify(back.body))
  const cx2 = back.body.session.id
  const inject = await codex.waitFor('thread/inject_items', (q) => q.threadId === cx2)
  assert.match(inject.params.items[0].content[0].text, /<previous_conversation>[\s\S]*Made notes\.txt/)
  assert.equal(codex.count('turn/start'), turns, 'switching starts no turn')
  assert.equal((await codex.waitFor('thread/start', (q) => q.sandbox === 'workspace-write')).params.cwd, project)
  assert.equal(session(cl)?.switchedTo, cx2)
  assert.equal(session(cx2)?.switchedFrom, cl)
  assert.equal(session(cx2)?.state, 'idle')
})

test('a busy session can’t switch: the phone stops it first', async () => {
  codex.autoTurn = false
  try {
    const id = (await call('POST', '/v1/sessions', { agent: 'codex', projectPath: project, prompt: 'busy' })).body.session.id
    await until(() => codex.running.has(id))
    codex.notify('turn/started', { threadId: id, turn: { id: codex.running.get(id), status: 'inProgress', items: [] } })
    await until(() => session(id)?.state === 'working')
    const r = await call('POST', `/v1/sessions/${id}/switch`, { to: 'claude', text: '' })
    assert.equal(r.status, 409)
    assert.equal(r.body.error.code, 'busy')
    codex.end(id, 'completed')
  } finally {
    codex.autoTurn = true
  }
})

test('items outside a turn (a terminal resuming the thread) don’t make a session look busy', async () => {
  const id = (await call('POST', '/v1/sessions', { agent: 'codex', projectPath: project, prompt: 'hi' })).body.session.id
  await until(() => session(id)?.state === 'idle')
  codex.notify('item/completed', { threadId: id, turnId: 'old', item: { type: 'hookPrompt', id: 'h-1', fragments: [] } })
  codex.notify('item/completed', { threadId: id, turnId: 'old', item: { type: 'agentMessage', id: 'm-old', text: 'from before', phase: 'final_answer' } })
  await sleep(100)
  assert.equal(session(id)?.state, 'idle')
})

test('Codex turned off: no connection, and no Codex session', async () => {
  const off = await call('POST', '/local/settings', { codex: false })
  assert.equal(off.status, 200)
  assert.equal(settings.codex, false)
  const r = await call('POST', '/v1/sessions', { agent: 'codex', projectPath: project, prompt: 'x' })
  // The phone's first Codex session turns it back on: what the owner asked for from the phone.
  assert.equal(r.status, 200)
  assert.equal(settings.codex, true)
  assert.equal((await call('POST', '/v1/sessions', { agent: 'gemini', projectPath: project, prompt: 'x' })).body.error.code, 'bad_agent')
  assert.equal((await call('POST', '/v1/sessions', { agent: 'codex', projectPath: project, prompt: 'x', permissionMode: 'bypassPermissions' })).body.error.code, 'bad_mode')
})

test('a turn Codex lost while the daemon was away ends when Codex is reached again, and prompts go', async () => {
  codex.autoTurn = false
  try {
    const id = (await call('POST', '/v1/sessions', { agent: 'codex', projectPath: project, prompt: 'long' })).body.session.id
    await until(() => codex.running.has(id))
    codex.notify('turn/started', { threadId: id, turn: { id: codex.running.get(id), status: 'inProgress', items: [] } })
    await until(() => session(id)?.state === 'working')
    // Codex restarts under it: the turn is gone, nothing says it ended.
    codex.running.delete(id)
    dropCodexForTests()
    await call('POST', '/v1/codex/refresh', {})
    await until(() => session(id)?.state === 'idle')
    const n = codex.count('turn/start')
    const r = await call('POST', `/v1/sessions/${id}/prompt`, { text: 'after', clientId: 'c-after' })
    assert.equal(r.body.queued, false)
    await until(() => codex.count('turn/start') === n + 1)
    codex.end(id, 'completed')
  } finally {
    codex.autoTurn = true
  }
})

test('a queued prompt Codex refuses comes back named, and the next one still goes', async () => {
  codex.autoTurn = false
  const real = codex.handlers['turn/start']!
  try {
    const id = (await call('POST', '/v1/sessions', { agent: 'codex', projectPath: project, prompt: 'first' })).body.session.id
    await until(() => codex.running.has(id))
    codex.notify('turn/started', { threadId: id, turn: { id: codex.running.get(id), status: 'inProgress', items: [] } })
    await until(() => session(id)?.state === 'working')
    await call('POST', `/v1/sessions/${id}/prompt`, { text: 'second', clientId: 'c-second' })
    await call('POST', `/v1/sessions/${id}/prompt`, { text: 'third', clientId: 'c-third' })
    assert.equal(session(id)?.queued, 2)
    codex.handlers['turn/start'] = (p, ws) => {
      if (p.input?.[0]?.text === 'second') throw { code: -32603, message: 'nope' }
      return real(p, ws)
    }
    codex.end(id, 'completed')
    await codex.waitFor('turn/start', (p) => p.input?.[0]?.text === 'third')
    const evs = await events(id)
    const back = evs.find((e) => e.kind === 'notice' && e.unsent?.prompts?.[0]?.clientId === 'c-second')
    assert.ok(back, 'the refused prompt is given back')
    assert.equal(evs.some((e) => e.kind === 'user' && e.clientId === 'c-second'), false, 'a prompt Codex never took doesn’t show as sent')
    assert.equal(session(id)?.queued, 0)
    codex.end(id, 'completed')
  } finally {
    codex.handlers['turn/start'] = real
    codex.autoTurn = true
  }
})

test('two switches at once make one new session', async () => {
  const id = (await call('POST', '/v1/sessions', { agent: 'codex', projectPath: project, prompt: 'hi' })).body.session.id
  await until(() => session(id)?.state === 'idle')
  const [a, b] = await Promise.all([call('POST', `/v1/sessions/${id}/switch`, { to: 'claude', text: '' }), call('POST', `/v1/sessions/${id}/switch`, { to: 'claude', text: '' })])
  const codes = [a, b].map((r) => (r.status === 200 ? 'ok' : r.body.error.code)).sort()
  assert.deepEqual(codes, ['ok', 'switched'])
})

test('the models’ default is the one Codex starts a thread on: the owner’s own config, with its effort', async () => {
  codex.handlers['config/read'] = () => ({ config: { model: 'gpt-other', model_reasoning_effort: 'medium' }, origins: {}, layers: null })
  try {
    const models = (await call('GET', '/v1/codex/models?fresh=1')).body.models
    assert.deepEqual(models.filter((m: any) => m.isDefault).map((m: any) => [m.value, m.defaultEffort]), [['gpt-other', 'medium']])
  } finally {
    delete codex.handlers['config/read']
  }
  // No config of the owner's: Codex's own default.
  const plain = (await call('GET', '/v1/codex/models?fresh=1')).body.models
  assert.deepEqual(plain.filter((m: any) => m.isDefault).map((m: any) => m.value), ['gpt-test'])
})
