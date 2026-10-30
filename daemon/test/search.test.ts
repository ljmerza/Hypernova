// Full-text search for History (search.ts; docs/PROTOCOL.md, "History search"): every word, any
// case, in what the owner typed and Claude's replies only; snippets with their hits marked; the
// result cap, the time budget, and the route's auth. Every transcript is written by hand in the
// shapes Claude Code 2.1 records; none is copied from a real one.
import { test, before, after, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { appendFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-search-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
mkdirSync(process.env.HOME, { recursive: true })
process.env.CLAUDE_BIN = join(scratch, 'no-claude')

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

const { searchTranscripts, snippetOf, matchDoc, queryWords, clearSearchCache } = await import('../src/search.ts')
const hub = await import('../src/hub.ts')
const { addDevice } = await import('../src/store.ts')
const { startServers } = await import('../src/http.ts')
const TOKEN = 'search-test-device-token-0123456789'
addDevice({ id: 'phone', name: 'Phone', model: 'Pixel', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)

const projects = join(process.env.CLAUDE_CONFIG_DIR, 'projects', '-tmp-demo')
mkdirSync(projects, { recursive: true })

let close: () => Promise<void>
before(async () => {
  close = startServers()
  for (let i = 0; i < 50; i++) {
    try {
      if ((await call('GET', '/v1/health')).status === 200) return
    } catch {}
    await new Promise((r) => setTimeout(r, 20))
  }
})
after(async () => {
  await close?.()
  rmSync(scratch, { recursive: true, force: true })
})
beforeEach(() => clearSearchCache())

function call(method: string, path: string, token: string | null = TOKEN): Promise<{ status: number; json: any }> {
  return new Promise((resolve, reject) => {
    const headers: Record<string, string> = { host: `127.0.0.1:${API}` }
    if (token) headers.authorization = `Bearer ${token}`
    const req = request({ host: '127.0.0.1', port: API, method, path, headers }, (res) => {
      let data = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (data += c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, json: data ? JSON.parse(data) : null }))
    })
    req.on('error', reject)
    req.end()
  })
}

// ── Fixture helpers ──────────────────────────────────────────────────────
let n = 0
const base = () => ({ uuid: `u-${n++}`, parentUuid: null, timestamp: new Date(Date.parse('2026-10-14T10:00:00Z') + n * 1000).toISOString(), sessionId: 's', cwd: '/tmp/demo', isSidechain: false, userType: 'external' })
const user = (content: unknown, extra: object = {}) => ({ ...base(), type: 'user', message: { role: 'user', content }, ...extra })
const assistant = (text: string, extra: object = {}) => ({ ...base(), type: 'assistant', message: { id: `msg-${n}`, role: 'assistant', model: 'claude-test', content: [{ type: 'text', text }] }, ...extra })
const toolUse = (id: string, command: string) => ({ ...base(), type: 'assistant', message: { id: `msg-${n}`, role: 'assistant', model: 'claude-test', content: [{ type: 'tool_use', id, name: 'Bash', input: { command } }] } })
const toolResult = (id: string, out: string) => ({ ...base(), type: 'user', message: { role: 'user', content: [{ tool_use_id: id, type: 'tool_result', content: out }] }, toolUseResult: { stdout: out } })

function transcript(id: string, ...entries: object[]): string {
  const p = join(projects, `${id}.jsonl`)
  writeFileSync(p, entries.map((e) => JSON.stringify(e) + '\n').join(''))
  return p
}
const src = (id: string, path: string, at = 0) => ({ id, path, at })

test('the query is split into its words, each once whatever its case', () => {
  assert.deepEqual(queryWords('  PIA  port pia\tPort forwarding '), ['PIA', 'port', 'forwarding'])
  assert.deepEqual(queryWords('   '), [])
  assert.equal(queryWords('a b c d e f g h i j').length, 8)
})

test('every word must be in the conversation (AND), in any message, any case', async () => {
  const both = transcript('aaaa', user('Why does qBittorrent never connect?'), assistant('The PIA tunnel has no forwarded PORT yet.'))
  const one = transcript('bbbb', user('What is PIA?'), assistant('A VPN.'))
  const r = await searchTranscripts('pia port', [src('aaaa', both, 2), src('bbbb', one, 1)])
  assert.deepEqual(r.results.map((h) => h.id), ['aaaa'])
  assert.equal(r.partial, false)
  assert.equal(r.results[0]!.role, 'assistant')
  assert.equal(r.results[0]!.matches, 2)
  const none = await searchTranscripts('pia wireguard', [src('aaaa', both), src('bbbb', one)])
  assert.deepEqual(none.results, [])
})

test('tool output, tool calls, meta lines, compaction summaries and subagents are not searched', async () => {
  const p = transcript(
    'cccc',
    user('List the folder'),
    toolUse('toolu_1', 'ls zebra'),
    toolResult('toolu_1', 'zebra.txt\ngiraffe.txt'),
    user('<system-reminder>giraffe</system-reminder>'),
    user('giraffe caveat', { isMeta: true }),
    user('This session is being continued. giraffe', { isCompactSummary: true }),
    assistant('giraffe from an agent', { isSidechain: true }),
    assistant('giraffe limit', { isApiErrorMessage: true }),
    user([{ type: 'text', text: '<local-command-stdout>giraffe</local-command-stdout>' }]),
    assistant('Done.'),
  )
  assert.deepEqual((await searchTranscripts('giraffe', [src('cccc', p)])).results, [])
  assert.deepEqual((await searchTranscripts('zebra', [src('cccc', p)])).results, [])
  assert.deepEqual((await searchTranscripts('folder', [src('cccc', p)])).results.map((h) => h.role), ['user'])
})

test('a message written twice counts once; a prompt queued and logged again counts once', async () => {
  const a = assistant('Fixed the kitchen lights.')
  const queued = { ...base(), type: 'attachment', attachment: { type: 'queued_command', prompt: 'and the kitchen fan', source_uuid: 'q-1', commandMode: 'prompt' } }
  const p = transcript('dddd', a, a, queued, user('and the kitchen fan', { uuid: 'q-1' }))
  const r = await searchTranscripts('kitchen', [src('dddd', p)])
  assert.equal(r.results[0]!.matches, 2)
})

test('the snippet is cut around the first hit, whitespace squeezed, the hits marked by offsets', async () => {
  const long = 'Lorem ipsum dolor sit amet. '.repeat(10) + 'We moved\n\nthe  PIA port to 51820 and then the port stayed open. ' + 'Consectetur adipiscing elit. '.repeat(10)
  const p = transcript('eeee', user('hello'), assistant(long))
  const h = (await searchTranscripts('port PIA', [src('eeee', p)])).results[0]!
  assert.ok(h.snippet.startsWith('…') && h.snippet.endsWith('…'), h.snippet)
  assert.ok(h.snippet.length <= 124, String(h.snippet.length))
  assert.ok(!/\s\s/.test(h.snippet) && !h.snippet.includes('\n'))
  assert.deepEqual(h.marks.map((m) => h.snippet.slice(m.start, m.end)), ['PIA', 'port', 'port'])
  assert.equal(h.matches, 3)
})

test('the snippet comes from the first message with every word, else the first with any', () => {
  const msgs = [
    { role: 'user' as const, text: 'the port is closed' },
    { role: 'assistant' as const, text: 'PIA hands out a forwarded port' },
  ]
  const h = matchDoc(msgs, ['pia', 'port'])!
  assert.equal(h.snippet, 'PIA hands out a forwarded port')
  assert.equal(h.role, 'assistant')
  const s = snippetOf('short text here', [{ start: 6, end: 10 }])
  assert.deepEqual(s, { snippet: 'short text here', marks: [{ start: 6, end: 10 }] })
})

test('results are capped, newest first, and an appended transcript is read on from where it ended', async () => {
  const sources = []
  for (let i = 0; i < 6; i++) sources.push(src(`cap${i}`, transcript(`cap${i}`, user(`walrus number ${i}`)), i))
  const r = await searchTranscripts('walrus', sources, { max: 4 })
  assert.deepEqual(r.results.map((h) => h.id), ['cap5', 'cap4', 'cap3', 'cap2'])
  appendFileSync(sources[5]!.path, JSON.stringify(assistant('another walrus')) + '\n')
  const again = await searchTranscripts('walrus', sources, { max: 1 })
  assert.equal(again.results[0]!.matches, 2)
})

test('the time budget stops a search and flags it partial', async () => {
  const sources = []
  for (let i = 0; i < 5; i++) sources.push(src(`late${i}`, transcript(`late${i}`, user('otter')), i))
  // Each look at the clock moves it on half a second: the first transcript is read, then time is up.
  let t = 0
  const r = await searchTranscripts('otter', sources, { budgetMs: 2500, now: () => (t += 500) })
  assert.equal(r.partial, true)
  assert.ok(r.results.length >= 1 && r.results.length < 5, String(r.results.length))
  // What was read is kept: the next search goes further.
  const full = await searchTranscripts('otter', sources)
  assert.equal(full.partial, false)
  assert.equal(full.results.length, 5)
})

test('GET /v1/history/search: paired phones only, the sessions the daemon lists, a bad query refused', async () => {
  const id = '11111111-2222-3333-4444-555555555555'
  const p = transcript(id, user('Set up the heron camera'), assistant('The heron camera streams now.'))
  hub.upsertSession(id, { cwd: '/tmp/demo', transcriptPath: p, state: 'ended' })
  // A file outside Claude Code's projects folder is never read, whatever a session claims.
  const outside = join(scratch, 'elsewhere.jsonl')
  writeFileSync(outside, JSON.stringify(user('heron camera secrets')) + '\n')
  hub.upsertSession('99999999-2222-3333-4444-555555555555', { cwd: '/tmp/demo', transcriptPath: outside, state: 'ended' })

  assert.equal((await call('GET', '/v1/history/search?q=heron', null)).status, 401)
  assert.equal((await call('GET', '/v1/history/search?q=heron', 'wrong-token')).status, 401)
  const r = await call('GET', '/v1/history/search?q=HERON%20camera')
  assert.equal(r.status, 200)
  assert.deepEqual(r.json.results.map((h: any) => h.id), [id])
  assert.equal(r.json.partial, false)
  assert.equal(r.json.results[0].snippet, 'Set up the heron camera')
  assert.deepEqual(r.json.results[0].marks, [{ start: 11, end: 16 }, { start: 17, end: 23 }])
  const bad = await call('GET', '/v1/history/search?q=%20x%20')
  assert.equal(bad.status, 400)
  assert.equal(bad.json.error?.code ?? bad.json.code, 'bad_query')
})
