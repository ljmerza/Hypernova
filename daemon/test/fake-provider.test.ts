// The fake provider (test/fake-provider): canned replies streamed like Anthropic's, each fault's
// status, headers and body, faults for one session only, the OpenRouter balance, and a control API
// that wants its token.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { CANNED, startFake } from './fake-provider/server.ts'

const dir = mkdtempSync(join(tmpdir(), 'hn-fake-'))
const fake = await startFake({ dir })
after(async () => {
  await fake.close()
  rmSync(dir, { recursive: true, force: true })
})

const ctl = (method: string, path: string, body?: unknown, token = fake.token) =>
  fetch(fake.url + path, { method, headers: { 'x-fake-token': token, 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
const ask = (o: { sid?: string; stream?: boolean; path?: string } = {}) =>
  fetch(fake.url + (o.path ?? '/v1/messages?beta=true'), {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: 'Bearer fake-or-key', ...(o.sid ? { 'x-claude-code-session-id': o.sid } : {}) },
    body: JSON.stringify({ model: 'fake-model', stream: o.stream ?? true, max_tokens: 100, messages: [{ role: 'user', content: '[Hypernova test] hi' }] }),
  })

test('only loopback, and the token file is private', async () => {
  await assert.rejects(startFake({ dir, host: '0.0.0.0' }))
  assert.equal(statSync(join(dir, 'token')).mode & 0o777, 0o600)
})

test('a canned reply streams as Anthropic streams it', async () => {
  const res = await ask()
  assert.equal(res.status, 200)
  assert.match(res.headers.get('content-type') ?? '', /text\/event-stream/)
  const text = await res.text()
  assert.match(text, /^event: message_start\n/)
  assert.match(text, /event: message_stop/)
  const said = [...text.matchAll(/"text_delta","text":"([^"]*)"/g)].map((m) => m[1]).join('')
  assert.equal(said, CANNED)
  const whole = await (await ask({ stream: false })).json()
  assert.equal(whole.content[0].text, CANNED)
  assert.equal((await fetch(fake.url + '/api/hello', { method: 'HEAD' })).status, 200)
})

test('the control API wants its token', async () => {
  assert.equal((await ctl('GET', '/_fake/faults', undefined, 'wrong')).status, 401)
  assert.equal((await ctl('POST', '/_fake/faults', { kind: 'nonsense' })).status, 400)
})

test('each fault answers like the real provider', async () => {
  const cases: Array<[string, number, RegExp, string?]> = [
    ['credits', 402, /"limit_source":"openrouter_credits"/],
    ['budget', 403, /"limit_source":"openrouter_key_limit"/],
    ['in_flight', 402, /openrouter_in_flight_budget/, 'retry-after'],
    ['rate', 429, /X-RateLimit-Reset/, 'retry-after'],
    ['rate_bare', 429, /^Too Many Requests$/],
    ['key', 401, /authentication_error/],
    ['forbidden', 403, /permission_error/],
    ['overloaded', 529, /overloaded_error/],
    ['limit_sub', 429, /rate_limit_error/, 'anthropic-ratelimit-unified-status'],
  ]
  for (const [kind, status, body, header] of cases) {
    await ctl('POST', '/_fake/faults', { kind, times: 1, for: 600 })
    const res = await ask()
    assert.equal(res.status, status, kind)
    assert.match(await res.text(), body, kind)
    if (header) assert.ok(res.headers.get(header), `${kind}: ${header}`)
    // Used up: the next request is fine again.
    assert.equal((await ask()).status, 200, `${kind} once`)
  }
})

test('rate announces the reset it lasts until, then lets requests through', async () => {
  const until = Math.floor(Date.now() / 1000) + 2
  await ctl('POST', '/_fake/faults', { kind: 'rate', until })
  const res = await ask()
  const o = await res.json()
  assert.equal(Number(o.error.metadata.headers['X-RateLimit-Reset']), until * 1000)
  await new Promise((r) => setTimeout(r, 2100))
  assert.equal((await ask()).status, 200)
  await ctl('DELETE', '/_fake/faults')
})

test('a fault for one session leaves the others alone', async () => {
  await ctl('POST', '/_fake/faults', { kind: 'credits', sid: 'abc' })
  assert.equal((await ask({ sid: 'abcdef-123' })).status, 402)
  assert.equal((await ask({ sid: 'zzz' })).status, 200)
  assert.equal((await ask()).status, 200)
  await ctl('DELETE', '/_fake/faults')
})

test('a cut stream and an error event mid-reply', async () => {
  await ctl('POST', '/_fake/faults', { kind: 'cut', times: 1, bytes: 200 })
  await assert.rejects(async () => {
    const res = await ask()
    await res.text()
  })
  await ctl('POST', '/_fake/faults', { kind: 'error_event', times: 1 })
  const text = await (await ask()).text()
  assert.match(text, /event: error\ndata: .*overloaded_error/)
})

test('the OpenRouter balance, set by the tests', async () => {
  const key = () => fetch(fake.url + '/or/v1/key', { headers: { authorization: 'Bearer fake-or-key' } })
  let o = await (await key()).json()
  assert.equal(o.data.limit_remaining, 4.2)
  await ctl('POST', '/_fake/balance', { remaining: 0 })
  o = await (await key()).json()
  assert.equal(o.data.limit_remaining, 0)
  await ctl('POST', '/_fake/balance', { status: 401 })
  assert.equal((await key()).status, 401)
  assert.equal((await fetch(fake.url + '/or/v1/key')).status, 401)
})

test('the log keeps request lines, never headers or bodies', async () => {
  await (await ask({ sid: 'secret-session-id-1234' })).text()
  await new Promise((r) => setTimeout(r, 50))
  const o = await (await ctl('GET', '/_fake/faults')).json()
  const line = o.log.at(-1)
  assert.equal(line.sid, 'secret-s')
  assert.deepEqual(Object.keys(line).sort(), ['at', 'fault', 'method', 'ms', 'path', 'sid', 'status'])
  assert.doesNotMatch(JSON.stringify(o.log), /fake-or-key|Hypernova test/)
})
