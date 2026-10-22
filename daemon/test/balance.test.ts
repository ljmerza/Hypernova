// OpenRouter's balance (balance.ts): what `/v1/key` says, read with the key only at the provider's
// own address and never through a redirect, at most once a minute (10 s when asked), a refused key,
// and credit coming back ending "out of credits". Against the fake provider, never the real one.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { startFake } from './fake-provider/server.ts'

const scratch = mkdtempSync(join(tmpdir(), 'hn-balance-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
mkdirSync(process.env.HOME, { recursive: true })

const fake = await startFake({ dir: join(scratch, 'fake') })
const store = await import('../src/store.ts')
const account = await import('../src/account.ts')
const hub = await import('../src/hub.ts')
const { classifyApiError } = await import('../src/blockers.ts')
const balance = await import('../src/balance.ts')

after(async () => {
  await fake.close()
  rmSync(scratch, { recursive: true, force: true })
})

const setBalance = (body: unknown) => fetch(`${fake.url}/_fake/balance`, { method: 'POST', headers: { 'x-fake-token': fake.token, 'content-type': 'application/json' }, body: JSON.stringify(body) })
const seen: string[] = []
balance.useFetch((url, init) => {
  seen.push(`${url.host}${url.pathname} ${(init.headers as Record<string, string>).authorization} ${init.redirect}`)
  return fetch(url, init)
})

test('the key’s answer as numbers only: left under its cap, spent today, free requests left', () => {
  const b = balance.parseKey({ data: { label: 'secret label', limit: 10, limit_remaining: 4.2, usage_daily: 0.31, free_model_daily_requests: { used: 13, limit: 50, remaining: 37 }, effective_budget: null } }, 5)
  assert.deepEqual(b, { remaining: 4.2, usedToday: 0.31, freeLeft: 37, freeLimit: 50, checkedAt: 5, error: null })
  // A budget, when set, is what's left.
  assert.equal(balance.parseKey({ data: { limit_remaining: 9, effective_budget: { remaining_usd: 1.5 } } })?.remaining, 1.5)
  // No cap: nothing claimed about what's left.
  assert.equal(balance.parseKey({ data: { limit_remaining: null, usage_daily: 2 } })?.remaining, null)
  assert.equal(balance.parseKey({ nope: 1 }), null)
  assert.equal(balance.keyUrl('https://openrouter.ai/api')?.href, 'https://openrouter.ai/api/v1/key')
  assert.equal(balance.keyUrl('https://user:pw@openrouter.ai/api'), null)
  assert.equal(balance.keyUrl('file:///etc/passwd'), null)
})

test('read at the provider’s own address, with its key, and shown on the provider (never the key)', async () => {
  const p = store.saveProvider({ name: 'Fake OpenRouter', kind: 'openrouter', baseUrl: `${fake.url}/or`, apiKey: 'fake-or-key', models: {} })
  await setBalance({ remaining: 4.2, usedToday: 0.31, freeLeft: 37, freeLimit: 50 })
  const b = await balance.checkBalance(p.id, true)
  assert.equal(b?.remaining, 4.2)
  assert.deepEqual(seen.at(-1), `${new URL(fake.url).host}/or/v1/key Bearer fake-or-key error`)
  const listed = store.listProviders().find((x) => x.id === p.id)!
  assert.equal(listed.balance?.remaining, 4.2)
  assert.doesNotMatch(JSON.stringify(store.listProviders()), /fake-or-key/)
  // Asked again within 10 s: the last reading stands, nothing is sent.
  const n = seen.length
  await balance.checkBalance(p.id, true)
  assert.equal(seen.length, n)
  // A custom provider has no balance to read.
  const c = store.saveProvider({ name: 'Gateway', kind: 'custom', baseUrl: fake.url, apiKey: 'other', models: {} })
  assert.equal(await balance.checkBalance(c.id, true), null)
  assert.equal(seen.length, n)
  store.deleteProvider(p.id)
  store.deleteProvider(c.id)
})

test('out of room under the key’s cap, then raised: the status ends; the account’s own credit can’t tell; a refused key says so', async () => {
  const p = store.saveProvider({ name: 'Fake OpenRouter', kind: 'openrouter', baseUrl: `${fake.url}/or`, apiKey: 'fake-or-key', models: {} })
  // The account's credit ran out, while the key's cap has room: that room says nothing about it.
  await setBalance({ remaining: 3 })
  hub.upsertSession('bal-1', { cwd: scratch, origin: 'hypernova', providerId: p.id, state: 'idle' })
  const at = Date.now() - 1000
  hub.blockSession('bal-1', classifyApiError({ error: 'unknown', status: 402, text: 'API Error: 402 Insufficient credits. Add more using https://openrouter.ai/settings/credits', builtIn: false, at }), at)
  assert.equal(account.providerStatus(p.id)?.state, 'no_credit')
  await new Promise((r) => setTimeout(r, 200))
  assert.equal(store.listProviders().find((x) => x.id === p.id)!.balance?.remaining, 3)
  assert.equal(account.providerStatus(p.id)?.state, 'no_credit')
  account.clearProviderStatus(p.id)
  // The key's cap.
  await setBalance({ remaining: 0 })
  hub.blockSession('bal-1', classifyApiError({ error: 'unknown', status: 403, text: 'API Error: 403 Key limit exceeded', builtIn: false, at }), at)
  assert.equal(account.providerStatus(p.id)?.budget, true)
  // Read at once on running out ("$0.00 left").
  await new Promise((r) => setTimeout(r, 200))
  assert.equal(store.listProviders().find((x) => x.id === p.id)!.balance?.remaining, 0)
  assert.equal(account.providerStatus(p.id)?.state, 'no_credit')
  await setBalance({ remaining: 5 })
  await balance.providerSaved(p.id, true)
  assert.equal(account.providerStatus(p.id), null)
  // The key refused.
  await setBalance({ status: 401 })
  await balance.providerSaved(p.id, true)
  assert.equal(account.providerStatus(p.id)?.state, 'bad_key')
  assert.equal(store.listProviders().find((x) => x.id === p.id)!.balance?.error, 'bad_key')
  store.deleteProvider(p.id)
  await balance.providerSaved(p.id, false)
})
