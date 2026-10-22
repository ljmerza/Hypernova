// Other providers' errors classified (docs/PROTOCOL.md, "Account", the other providers' column):
// out of credit (the balance or a cap), rate limited (until when, only if it said), a refused key,
// not answering; and Claude Code's lines about carrying on after a limit.
import { test } from 'node:test'
import assert from 'node:assert/strict'

process.env.TZ = 'Europe/Moscow'
const { classifyApiError, classifyNotice, limitNoticeText, providerReset, autoContinueOf, CONTINUE_TEXT } = await import('../src/blockers.ts')

const at = Date.parse('2026-10-16T09:00:00Z')
/** As Claude Code writes another provider's error: its status, then the provider's body. */
const err = (status: number, body: string, error = 'unknown') => classifyApiError({ error, status, text: `API Error: ${status} ${body}`, builtIn: false, at })

test('OpenRouter names what ran out: its credits, a cap (never a bad key), or a moment’s in-flight budget', () => {
  const credits = err(402, '{"error":{"code":402,"message":"Insufficient credits. Add more using https://openrouter.ai/settings/credits","metadata":{"limit_source":"openrouter_credits"}}}')
  assert.deepEqual([credits.kind, credits.provider, credits.budget], ['provider', 'no_credit', undefined])
  const keyCap = err(403, '{"error":{"code":403,"message":"Key limit exceeded","metadata":{"limit_source":"openrouter_key_limit"}}}')
  assert.deepEqual([keyCap.provider, keyCap.budget], ['no_credit', true])
  const guard = err(403, '{"error":{"code":403,"message":"Budget exceeded","metadata":{"limit_source":"openrouter_guardrail_budget"}}}')
  assert.deepEqual([guard.provider, guard.budget], ['no_credit', true])
  const inFlight = err(402, '{"error":{"code":402,"message":"Request would exceed your in-flight budget.","metadata":{"limit_source":"openrouter_in_flight_budget"}}}')
  assert.equal(inFlight.provider, 'limited')
  assert.equal(inFlight.resetsAt, at + 60_000)
  // A 402 without OpenRouter's words is out of credit all the same.
  assert.equal(err(402, 'Payment Required').provider, 'no_credit')
})

test('a rate limit lasts until the reset the provider gave, else nobody guesses', () => {
  const reset = at + 7 * 60_000
  const or = err(429, `{"error":{"code":429,"message":"Rate limit exceeded: free-models-per-min.","metadata":{"headers":{"X-RateLimit-Limit":"20","X-RateLimit-Remaining":"0","X-RateLimit-Reset":"${reset}"}}}}`, 'rate_limit')
  assert.deepEqual([or.provider, or.resetsAt], ['limited', reset])
  // In seconds, too, and escaped as Claude Code may print it.
  assert.equal(providerReset(`{\\"X-RateLimit-Reset\\":\\"${reset / 1000}\\"}`, null, at), reset)
  assert.equal(providerReset('Please retry after 90 seconds', null, at), at + 90_000)
  assert.equal(providerReset('retry-after: 90', null, at), at + 90_000)
  assert.equal(providerReset('Rate limited. Please try again in 5 minutes.', null, at), at + 5 * 60_000)
  assert.equal(providerReset('', { resetsAt: (at + 3600_000) / 1000 }, at), at + 3600_000)
  // A reset in the past or absurdly far isn't believed.
  assert.equal(providerReset(`X-RateLimit-Reset: ${at - 3600_000}`, null, at), null)
  const bare = err(429, 'Too Many Requests', 'rate_limit')
  assert.deepEqual([bare.provider, bare.resetsAt], ['limited', null])
})

test('a refused key, and a provider not answering', () => {
  assert.equal(err(401, '{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}', 'authentication_failed').provider, 'bad_key')
  assert.equal(err(403, '{"type":"error","error":{"type":"permission_error","message":"Your API key does not have permission"}}').provider, 'bad_key')
  for (const [status, body] of [[529, '{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}'], [503, 'Service Unavailable'], [500, 'Internal Server Error']] as const) {
    const b = err(status, body)
    assert.deepEqual([b.kind, b.provider], ['provider', 'down'], `${status}`)
  }
  const net = classifyApiError({ error: 'unknown', status: null, text: 'API Error: Connection error.', builtIn: false, at })
  assert.equal(net.provider, 'down')
  // The built-in provider's table is unchanged: an overload there is a notice, not a block.
  assert.equal(classifyApiError({ error: 'overloaded', status: 529, text: 'API Error: 529 Overloaded', builtIn: true, at }).kind, null)
})

test("Claude Code's lines about carrying on after a limit (2.1.300's own strings)", () => {
  const armed = classifyNotice('Usage limit reached · continuing automatically at 2am · esc to cancel', at)
  assert.equal(armed?.kind, 'armed')
  assert.equal(armed?.text, 'Usage limit reached · continuing automatically at 2am')
  assert.equal(classifyNotice('Continuing automatically at 2am · esc to cancel', at)?.kind, 'armed')
  const waits = classifyNotice('Claude Code will continue automatically at 2am. Keep this session open; it may still pause for permission prompts. Press esc to cancel the wait.', at)
  assert.deepEqual([waits?.kind, waits?.text], ['armed', 'Claude Code will continue automatically at 2am.'])
  assert.deepEqual(classifyNotice('Automatic continue cancelled · /rate-limit-options to re-arm', at), { kind: 'cancelled', text: 'Automatic continue cancelled' })
  const cases: Array<[string, string, string | null]> = [
    ['Automatic continue was turned off · this task will not resume on its own (/rate-limit-options to wait anyway)', 'off', 'setting'],
    ['Automatic continue stopped · the usage limit now resets more than 24 hours out, so this task will not resume on its own (/rate-limit-options to wait anyway)', 'off', 'far'],
    ['Automatic continue stopped after repeated usage-limit hits · this task will not resume on its own (/rate-limit-options to try again)', 'off', 'repeated'],
    ['Automatic continue cancelled · Claude Code exited during the wait, so the task will not resume on its own', 'off', 'exited'],
    ['Usage limit has reset · press enter to continue', 'ready', 'slept'],
    ['Automatic continue did not run · the continuation was blocked before it reached the model, so this task did not resume on its own · send a prompt to continue', 'ready', 'dropped'],
    ['Usage limit reset · continuing automatically', 'reset', null],
    ['Usage limit available again · continuing now', 'reset', null],
  ]
  for (const [line, kind, why] of cases) {
    const n = classifyNotice(line, at)
    assert.equal(n?.kind, kind, line)
    if (why) assert.equal((n as { why?: string }).why, why, line)
    assert.doesNotMatch(n!.text, /rate-limit-options|press enter|send a prompt|esc to cancel/, line)
  }
  assert.equal(limitNoticeText('Usage limit has reset · press enter to continue'), 'Usage limit has reset')
  assert.equal(classifyNotice('Compacted while idle', at), null)
})

test('a continuation is told apart from the owner’s own prompts', () => {
  assert.equal(autoContinueOf(CONTINUE_TEXT.claude), 'limit')
  assert.equal(autoContinueOf(` ${CONTINUE_TEXT.provider}\n`), 'limit')
  assert.equal(autoContinueOf(CONTINUE_TEXT.manual), 'manual')
  assert.equal(autoContinueOf('continue'), null)
})

test('what Claude Code keeps of another provider’s error: only its message (recorded against the fake)', () => {
  // As the transcript has them: Claude Code drops the body's metadata (limit_source, headers).
  const line = (status: number, msg: string, error = 'unknown') => classifyApiError({ error, status, text: `API Error: ${status} ${msg}`, builtIn: false, at })
  assert.deepEqual([line(402, 'Insufficient credits. Add more using https://openrouter.ai/settings/credits').provider, line(402, 'Insufficient credits.').budget], ['no_credit', undefined])
  const cap = line(403, 'Key limit exceeded')
  assert.deepEqual([cap.provider, cap.budget], ['no_credit', true])
  const inFlight = line(402, 'Request would exceed your in-flight budget. Retry after the requests in flight finish.')
  assert.equal(inFlight.provider, 'limited')
  // The SDK's error has no status: the line names it.
  const sdk = classifyApiError({ error: 'unknown', text: 'API Error: 402 Insufficient credits.', builtIn: false, at })
  assert.equal(sdk.provider, 'no_credit')
  // A rate limit's window is often all it says.
  const perMin = classifyApiError({ error: 'rate_limit', status: 429, text: 'API Error: Request rejected (429) · Rate limit exceeded: free-models-per-min.', builtIn: false, at })
  assert.deepEqual([perMin.provider, perMin.resetsAt], ['limited', at + 60_000])
  const perDay = classifyApiError({ error: 'rate_limit', status: 429, text: 'API Error: Request rejected (429) · Rate limit exceeded: free-models-per-day.', builtIn: false, at })
  assert.equal(perDay.resetsAt, Date.parse('2026-10-17T00:00:00Z'))
})

test('the recorded transcript entries (Claude Code 2.1.300 against the fake provider) read as the faults they were', async () => {
  const { readFileSync } = await import('node:fs')
  const { join, dirname } = await import('node:path')
  const { fileURLToPath } = await import('node:url')
  const dir = join(dirname(fileURLToPath(import.meta.url)), 'fixtures', 'provider-errors')
  const read = (kind: string) => {
    const o = JSON.parse(readFileSync(join(dir, `${kind}.jsonl`), 'utf8').trim())
    const text = o.message.content.map((b: { text: string }) => b.text).join('\n')
    return classifyApiError({ error: o.error, apiError: o.apiError, status: o.apiErrorStatus, text, transient: o.apiErrorIsTransient, builtIn: false, at })
  }
  assert.deepEqual([read('credits').provider, read('credits').budget], ['no_credit', undefined])
  // Claude Code calls OpenRouter's 403 for a key's cap "authentication_failed": still not a bad key.
  assert.deepEqual([read('budget').provider, read('budget').budget], ['no_credit', true])
  assert.deepEqual([read('in_flight').provider, read('in_flight').resetsAt], ['limited', at + 60_000])
  assert.deepEqual([read('rate').provider, read('rate').resetsAt], ['limited', at + 60_000])
})
