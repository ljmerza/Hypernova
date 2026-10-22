// A session's model (docs/PROTOCOL.md, "A session's model"): kept current from every reply, a /model
// in the terminal (its output, and the status line before any reply), and switched from the phone
// with POST /v1/sessions/{id}/model; and an SDK session's mode change mid-turn says next_turn.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { appendFileSync, chmodSync, mkdirSync, mkdtempSync, rmSync, utimesSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hypernova-model-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
mkdirSync(process.env.HOME, { recursive: true })
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

const { startServers } = await import('../src/http.ts')
const store = await import('../src/store.ts')
const hub = await import('../src/hub.ts')
const { pollStatusLine } = await import('../src/services.ts')
const { modelSet } = await import('../src/normalize.ts')
const { useClaudeProbe, noteClaudeModels } = await import('../src/models.ts')
const { testTurn } = await import('../src/runner.ts')
const TOKEN = 'test-device-token-model-0123456789ab'
store.addDevice({ id: 'phone', name: 'Phone', model: 'P', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)
// Claude Code's own list, as its initialize answer gives it (no Claude Code is started).
const CLAUDE_LIST = [
  { value: 'default', displayName: 'Default (recommended)', description: 'Opus 5.5 · Best for everyday tasks', resolvedModel: 'claude-opus-5-5' },
  { value: 'opus', displayName: 'Opus', resolvedModel: 'claude-opus-5-5' },
  { value: 'sonnet', displayName: 'Sonnet', resolvedModel: 'claude-sonnet-5-5' },
  { value: 'sonnet[1m]', displayName: 'Sonnet (1M context)', resolvedModel: 'claude-sonnet-5-5[1m]' },
]
useClaudeProbe(async () => noteClaudeModels(CLAUDE_LIST))

let close: () => Promise<void>
before(async () => {
  close = startServers()
  await new Promise((r) => setTimeout(r, 200))
})
after(async () => {
  await close?.()
  rmSync(scratch, { recursive: true, force: true })
})

function post(path: string, body: object): Promise<{ status: number; json: any }> {
  return new Promise((resolve, reject) => {
    const req = request({ host: '127.0.0.1', port: API, method: 'POST', path, headers: { host: `127.0.0.1:${API}`, authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' } }, (res) => {
      let data = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (data += c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, json: data ? JSON.parse(data) : null }))
    })
    req.on('error', reject)
    req.end(JSON.stringify(body))
  })
}
function listen() {
  const got: any[] = []
  const stop = hub.addClient({ send: (s) => got.push(JSON.parse(s)) })
  return { got, stop, models: (id: string) => got.filter((m) => m.t === 'session' && m.session.id === id).map((m) => m.session.model) }
}

let tick = 0
const T0 = Date.now() - 10 * 60_000
const at = () => new Date(T0 + tick++ * 1000).toISOString()
const line = (o: object) => JSON.stringify(o) + '\n'
const reply = (uuid: string, model: string, text: string) => line({ type: 'assistant', uuid, timestamp: at(), message: { id: `m-${uuid}`, role: 'assistant', model, content: [{ type: 'text', text }] } })
const typed = (uuid: string, text: string) => line({ type: 'user', uuid, timestamp: at(), message: { role: 'user', content: text } })

test('/model in the terminal: the session’s model follows its output, every reply, and the status line before any reply', () => {
  const id = 'aaaaaaaa-0000-4000-8000-000000000001'
  const cwd = join(scratch, 'proj')
  mkdirSync(cwd, { recursive: true })
  const transcript = join(scratch, 'claude', 'projects', '-proj', `${id}.jsonl`)
  mkdirSync(join(scratch, 'claude', 'projects', '-proj'), { recursive: true })
  // Two models already: the session started on Opus, then the owner switched to Sonnet.
  writeFileSync(transcript, typed('u1', 'hi') + reply('a1', 'claude-opus-5-5', 'Hello.') + typed('u2', 'more') + reply('a2', 'claude-sonnet-5-5', 'Sure.'))
  const r = hub.upsertSession(id, { cwd, transcriptPath: transcript, origin: 'terminal', model: 'opus' })
  const { models, stop } = listen()
  hub.ensureParsed(r)
  assert.equal(hub.getSession(id)!.model, 'claude-sonnet-5-5')
  // /model haiku: its output says so before any reply comes.
  appendFileSync(transcript, line({ type: 'system', subtype: 'local_command', uuid: 'c1', timestamp: at(), content: '<command-name>/model</command-name>\n<command-message>model</command-message>\n<command-args>haiku</command-args>' }) +
    typed('o1', '<local-command-stdout>Set model to \u001b[1mHaiku 5.5\u001b[22m (claude-haiku-5-5)</local-command-stdout>'))
  hub.pollTranscripts()
  assert.equal(hub.getSession(id)!.model, 'claude-haiku-5-5')
  assert.ok(models(id).includes('claude-haiku-5-5'), 'broadcast at once')
  // The status line after "/model sonnet[1m]": newer than the transcript, so it's the model now.
  const dir = join(process.env.HYPERNOVA_SUPPORT!, 'statusline')
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, `${id}.json`), JSON.stringify({ session_id: id, transcript_path: transcript, model: { id: 'claude-sonnet-5-5[1m]', display_name: 'Sonnet 5.5 (1M)' } }))
  pollStatusLine()
  assert.equal(hub.getSession(id)!.model, 'claude-sonnet-5-5[1m]')
  // The next reply from the same model keeps the 1M mark; a repainted status line changes nothing.
  appendFileSync(transcript, typed('u3', 'go') + reply('a3', 'claude-sonnet-5-5', 'Done.'))
  hub.pollTranscripts()
  pollStatusLine()
  assert.equal(hub.getSession(id)!.model, 'claude-sonnet-5-5[1m]')
  // A status line written before the transcript's last word is old news.
  appendFileSync(transcript, line({ type: 'assistant', uuid: 'a4', timestamp: new Date(Date.now() + 60_000).toISOString(), message: { id: 'm-a4', role: 'assistant', model: 'claude-opus-5-5', content: [{ type: 'text', text: 'Opus again.' }] } }))
  hub.pollTranscripts()
  writeFileSync(join(dir, `${id}.json`), JSON.stringify({ session_id: id, model: { id: 'claude-haiku-5-5' } }))
  const old = new Date(Date.now() - 30_000)
  utimesSync(join(dir, `${id}.json`), old, old)
  pollStatusLine()
  assert.equal(hub.getSession(id)!.model, 'claude-opus-5-5')
  stop()
})

test('what /model says it set', () => {
  assert.equal(modelSet('Set model to Sonnet 5.5 (claude-sonnet-5-5)'), 'claude-sonnet-5-5')
  assert.equal(modelSet('Set model to Opus 5.5 (1M context) (claude-opus-5-5[1m])'), 'claude-opus-5-5[1m]')
  assert.equal(modelSet('Set model to sonnet'), 'sonnet')
  assert.equal(modelSet('Kept model as Opus'), null)
  assert.equal(modelSet('Something else'), null)
})

test('POST …/model: validated against the provider’s list, shown at once, an SDK session takes it now or from its next turn', async () => {
  const id = 'sdk-model'
  store.saveManaged({ id, cwd: scratch, providerId: 'anthropic', model: 'opus', permissionMode: 'default', createdAt: Date.now(), engine: 'sdk' })
  hub.upsertSession(id, { cwd: scratch, origin: 'hypernova', state: 'idle', model: 'opus' })
  assert.equal((await post('/v1/sessions/nope/model', { model: 'sonnet' })).json.error.code, 'no_session')
  const bad = await post(`/v1/sessions/${id}/model`, { model: 'gpt-9' })
  assert.deepEqual([bad.status, bad.json.error.code], [400, 'unknown_model'])
  assert.equal((await post(`/v1/sessions/${id}/model`, {})).json.error.code, 'unknown_model')
  const { models, stop } = listen()
  const ok = await post(`/v1/sessions/${id}/model`, { model: 'sonnet' })
  assert.equal(ok.status, 200)
  assert.deepEqual([ok.json.model, ok.json.applies, ok.json.session.model], ['claude-sonnet-5-5', 'now', 'claude-sonnet-5-5'])
  assert.equal(store.getManaged(id)?.model, 'sonnet') // what the next query is started with
  assert.ok(models(id).includes('claude-sonnet-5-5'))
  // Mid-turn: from the next one.
  testTurn(id, { setPermissionMode: async () => {} })
  const mid = await post(`/v1/sessions/${id}/model`, { model: 'sonnet[1m]' })
  assert.deepEqual([mid.json.model, mid.json.applies], ['claude-sonnet-5-5[1m]', 'next_turn'])
  // The default: null.
  const def = await post(`/v1/sessions/${id}/model`, { model: null })
  assert.deepEqual([def.json.model, store.getManaged(id)?.model], ['claude-opus-5-5', null])
  testTurn(id, null)
  stop()
})

test('POST …/model: a shared terminal’s refusals, a terminal Hypernova doesn’t hold, an ended session, a paid provider', async () => {
  // A shared terminal that doesn't answer: 409 busy, with why.
  const t = 'term-model'
  store.saveManaged({ id: t, cwd: scratch, providerId: 'anthropic', model: null, permissionMode: 'default', createdAt: Date.now(), engine: 'term' })
  hub.upsertSession(t, { cwd: scratch, origin: 'hypernova', state: 'idle', term: { id: 'T-none', mac: 0, phone: 0 } })
  const r = await post(`/v1/sessions/${t}/model`, { model: 'sonnet' })
  assert.deepEqual([r.status, r.json.error.code, r.json.error.reason], [409, 'busy', 'terminal_busy'])
  // Open in a terminal the daemon doesn't hold: typed there.
  const live = 'live-model'
  hub.upsertSession(live, { cwd: scratch, origin: 'terminal', state: 'idle', pid: process.pid })
  const l = await post(`/v1/sessions/${live}/model`, { model: 'sonnet' })
  assert.deepEqual([l.status, l.json.error.code], [409, 'terminal_model'])
  // Ended: it takes the model when it resumes.
  const ended = 'ended-model'
  hub.upsertSession(ended, { cwd: scratch, origin: 'terminal', state: 'ended' })
  const e = await post(`/v1/sessions/${ended}/model`, { model: 'sonnet' })
  assert.deepEqual([e.status, e.json.applies, e.json.session.model], [200, 'next_turn', 'claude-sonnet-5-5'])
  // On a provider that costs money: only once the owner agreed.
  const p = store.saveProvider({ name: 'Gateway', kind: 'custom', baseUrl: 'http://127.0.0.1:9', apiKey: 'test-only', models: { default: 'gw-large', sonnet: 'gw-small' } })
  const paid = 'paid-model'
  store.saveManaged({ id: paid, cwd: scratch, providerId: p.id, model: null, permissionMode: 'default', createdAt: Date.now(), engine: 'sdk' })
  hub.upsertSession(paid, { cwd: scratch, origin: 'hypernova', state: 'idle', providerId: p.id })
  const ask = await post(`/v1/sessions/${paid}/model`, { model: 'sonnet' })
  assert.deepEqual([ask.status, ask.json.error.code], [409, 'confirm_paid'])
  const yes = await post(`/v1/sessions/${paid}/model`, { model: 'sonnet', confirmPaid: true })
  assert.deepEqual([yes.status, yes.json.model], [200, 'gw-small'])
})

test('an SDK session’s mode change mid-turn starts with the next turn (the running query can’t change it)', async () => {
  const id = 'sdk-mode'
  store.saveManaged({ id, cwd: scratch, providerId: 'anthropic', model: null, permissionMode: 'default', createdAt: Date.now(), engine: 'sdk' })
  hub.upsertSession(id, { cwd: scratch, origin: 'hypernova', state: 'working' })
  testTurn(id, { setPermissionMode: async () => { throw new Error('only in streaming input mode') } })
  const r = await post(`/v1/sessions/${id}/mode`, { permissionMode: 'acceptEdits' })
  assert.deepEqual([r.status, r.json.applies, r.json.session.permissionMode], [200, 'next_turn', 'acceptEdits'])
  testTurn(id, null)
  const idle = await post(`/v1/sessions/${id}/mode`, { permissionMode: 'plan' })
  assert.equal(idle.json.applies, 'now')
})
