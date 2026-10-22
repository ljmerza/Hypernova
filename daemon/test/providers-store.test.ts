// Providers as kept (store.ts) and checked (providers-check.ts): addresses cleaned the way Claude
// Code needs them, plain http only on this computer's networks, keys and names tidied, a key that
// never follows an address to another host, a deleted provider that is never the subscription,
// and the check's verdicts against a stand-in.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-prov-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
mkdirSync(process.env.HOME, { recursive: true })

const store = await import('../src/store.ts')
const { checkProvider, modelIds, useCheckFetch } = await import('../src/providers-check.ts')

const code = (f: () => unknown): string | null => {
  try {
    f()
    return null
  } catch (e) {
    return (e as { code?: string }).code ?? 'thrown'
  }
}

test('an address as Claude Code wants it: no trailing /, /v1 or /v1/messages (OpenRouter documents …/api/v1)', () => {
  assert.equal(store.cleanBaseUrl('https://openrouter.ai/api/v1'), 'https://openrouter.ai/api')
  assert.equal(store.cleanBaseUrl(' https://openrouter.ai/api/v1/ \n'), 'https://openrouter.ai/api')
  assert.equal(store.cleanBaseUrl('https://openrouter.ai/api'), 'https://openrouter.ai/api')
  assert.equal(store.cleanBaseUrl('https://gw.example/v1/messages'), 'https://gw.example')
  assert.equal(store.cleanBaseUrl('https://gw.example/anthropic/v1/messages/'), 'https://gw.example/anthropic')
  assert.equal(store.cleanBaseUrl('https://gw.example//'), 'https://gw.example')
  assert.equal(store.cleanBaseUrl('https://gw.example:8443/x?y=1#z'), 'https://gw.example:8443/x')
  assert.equal(code(() => store.cleanBaseUrl('gw.example')), 'bad_url')
  assert.equal(code(() => store.cleanBaseUrl('ftp://gw.example')), 'bad_url')
  assert.equal(code(() => store.cleanBaseUrl('https://user:pw@gw.example')), 'bad_url')
})

test('plain http only to this computer, a private or Tailscale network, or the local network’s names', () => {
  for (const ok of ['http://localhost:4000', 'http://127.0.0.1:11434', 'http://[::1]:8080', 'http://10.0.0.5', 'http://172.20.1.1', 'http://192.168.1.20:4000/v1', 'http://100.101.102.103', 'http://mac.tail1234.ts.net', 'http://studio.local:4000', 'http://host.docker.internal:4000']) {
    assert.doesNotThrow(() => store.cleanBaseUrl(ok), ok)
  }
  for (const no of ['http://openrouter.ai/api', 'http://203.0.113.5:4000', 'http://172.32.0.1', 'http://100.128.0.1', 'http://example.com']) {
    assert.equal(code(() => store.cleanBaseUrl(no)), 'insecure_url', no)
  }
  // https anywhere.
  assert.equal(store.cleanBaseUrl('https://203.0.113.5:4000'), 'https://203.0.113.5:4000')
})

test('a pasted key is trimmed, and one with characters no key has is refused', () => {
  assert.equal(store.cleanKey('  sk-or-v1-abc\n'), 'sk-or-v1-abc')
  assert.equal(store.cleanKey(''), null)
  assert.equal(store.cleanKey('   '), null)
  assert.equal(store.cleanKey(null), null)
  assert.equal(code(() => store.cleanKey('sk-abc\ndef')), 'bad_key_format')
  assert.equal(code(() => store.cleanKey('sk abc')), 'bad_key_format')
  assert.equal(code(() => store.cleanKey('ключ')), 'bad_key_format')
})

test('adding and editing are checked alike; an unknown id is 404; a key never follows a new host', () => {
  assert.equal(code(() => store.editProvider({ name: 'X', kind: 'custom' })), 'missing_url')
  assert.equal(code(() => store.editProvider({ name: 'X', kind: 'nope' as never, baseUrl: 'https://x.example' })), 'bad_kind')
  assert.equal(code(() => store.editProvider({ name: 'X', kind: 'custom', baseUrl: 'https://x.example' }, 'no-such-id')), 'no_provider')
  const a = store.editProvider({ name: 'Gateway', kind: 'custom', baseUrl: 'https://gw.example/v1', apiKey: ' key-one ' })
  assert.deepEqual([a.provider.baseUrl, a.provider.apiKey, a.keyChanged, a.addressChanged, a.keyDropped], ['https://gw.example', 'key-one', true, true, false])
  // PUT runs POST's checks.
  assert.equal(code(() => store.editProvider({ name: 'Gateway', kind: 'custom', baseUrl: 'http://gw.example' }, a.provider.id)), 'insecure_url')
  // Same host, another path: the key stays.
  const b = store.editProvider({ name: 'Gateway', kind: 'custom', baseUrl: 'https://gw.example/anthropic' }, a.provider.id)
  assert.deepEqual([b.provider.apiKey, b.keyDropped, b.addressChanged], ['key-one', false, true])
  // Another host without a new key: the old vendor's key doesn't go there.
  const c = store.editProvider({ name: 'Gateway', kind: 'custom', baseUrl: 'https://other.example' }, a.provider.id)
  assert.deepEqual([c.provider.apiKey, c.keyDropped], [null, true])
  assert.equal(store.listProviders().find((p) => p.id === a.provider.id)?.hasKey, false)
  // Another host with its own key: fine.
  const d = store.editProvider({ name: 'Gateway', kind: 'custom', baseUrl: 'https://third.example', apiKey: 'key-two' }, a.provider.id)
  assert.deepEqual([d.provider.apiKey, d.keyDropped], ['key-two', false])
  // OpenRouter without an address gets its own, cleaned.
  const or = store.editProvider({ name: 'OpenRouter', kind: 'openrouter', apiKey: 'k' })
  assert.equal(or.provider.baseUrl, store.OPENROUTER_URL)
  store.deleteProvider(a.provider.id)
  store.deleteProvider(or.provider.id)
})

test('names: at most 40 characters, and never another provider’s', () => {
  const one = store.saveProvider({ name: 'Studio', kind: 'custom', baseUrl: 'http://127.0.0.1:4000' })
  const two = store.saveProvider({ name: ' studio ', kind: 'custom', baseUrl: 'http://127.0.0.1:4001' })
  const three = store.saveProvider({ name: 'Studio', kind: 'custom', baseUrl: 'http://127.0.0.1:4002' })
  assert.deepEqual([one.name, two.name, three.name], ['Studio', 'studio 2', 'Studio 3'])
  // Saving one under its own name keeps it.
  assert.equal(store.saveProvider({ name: 'Studio', kind: 'custom' }, one.id).name, 'Studio')
  assert.equal(code(() => store.saveProvider({ name: 'x'.repeat(41), kind: 'custom', baseUrl: 'http://127.0.0.1:4003' })), 'bad_name')
  for (const p of [one, two, three]) store.deleteProvider(p.id)
})

test('a provider that costs nothing is the owner’s word; unset, every other provider costs money', () => {
  const local = store.saveProvider({ name: 'Ollama', kind: 'custom', baseUrl: 'http://127.0.0.1:11434', paid: false })
  const gw = store.saveProvider({ name: 'LiteLLM', kind: 'custom', baseUrl: 'http://127.0.0.1:4000' })
  const listed = (id: string) => store.listProviders().find((p) => p.id === id)!
  assert.deepEqual([listed(local.id).paid, listed(gw.id).paid, listed('anthropic').paid], [false, true, false])
  // Kept through an edit that doesn't say, changed by one that does.
  assert.equal(listed(store.saveProvider({ name: 'Ollama', kind: 'custom' }, local.id).id).paid, false)
  assert.equal(listed(store.saveProvider({ name: 'Ollama', kind: 'custom', paid: true }, local.id).id).paid, true)
  store.deleteProvider(local.id)
  store.deleteProvider(gw.id)
})

test('a deleted provider is never the subscription: a stand-in, refused for a new start', () => {
  const p = store.saveProvider({ name: 'Short-lived', kind: 'openrouter', apiKey: 'k' })
  assert.equal(store.isSubscription(p.id), false)
  store.deleteProvider(p.id)
  const gone = store.getProvider(p.id)
  assert.deepEqual([gone.id, gone.kind, gone.removed, gone.apiKey, gone.baseUrl], [p.id, 'custom', true, null, null])
  assert.equal(store.isSubscription(p.id), false)
  assert.equal(store.providerRemoved(p.id), true)
  assert.equal(code(() => store.useProvider(p.id)), 'no_provider')
  // Its environment can't reach the owner's Claude login either.
  assert.equal(store.providerEnv(gone).ANTHROPIC_API_KEY, '')
  assert.equal(store.providerEnv(gone).ANTHROPIC_AUTH_TOKEN, 'hypernova-no-key')
  // No id, or the built-in one's, is the subscription.
  for (const id of [null, undefined, '', 'anthropic']) {
    assert.equal(store.isSubscription(id), true, String(id))
    assert.equal(store.providerRemoved(id), false, String(id))
    assert.equal(store.useProvider(id).id, 'anthropic')
  }
})

test('the check: a key works (and its models), is refused, out of credit, rate limited; a wrong address; nothing answering', async () => {
  const seen: { url: string; headers: Record<string, string>; redirect: unknown }[] = []
  useCheckFetch(async (url, init) => {
    const headers = init.headers as Record<string, string>
    seen.push({ url: url.href, headers, redirect: init.redirect })
    const key = headers['x-api-key'] ?? headers.authorization?.replace(/^Bearer /, '')
    if (url.hostname === 'down.example') throw new TypeError('fetch failed')
    if (url.pathname === '/or/v1/key') return key === 'good' ? Response.json({ data: { limit_remaining: 4.2, usage_daily: 0.3 } }) : new Response('no', { status: 401 })
    if (url.pathname !== '/gw/v1/models') return new Response('not here', { status: 404 })
    if (key === 'good') return Response.json({ data: [{ id: 'local/coder', display_name: 'Coder' }, { id: 'local/chat' }, { id: 'local/coder' }] })
    if (key === 'broke') return new Response('', { status: 402 })
    if (key === 'busy') return new Response('', { status: 429 })
    return new Response('{"type":"error","error":{"type":"authentication_error"}}', { status: 401 })
  })
  const custom = (apiKey: string | null, baseUrl = 'https://gw.example/gw') => checkProvider({ kind: 'custom', name: 'Gateway', baseUrl, apiKey })
  const ok = await custom('good')
  assert.deepEqual([ok.state, ok.text, ok.models], ['ok', 'Key works. 2 models.', ['local/coder', 'local/chat']])
  // The key both ways gateways take it, never through a redirect.
  assert.deepEqual([seen[0]!.headers['x-api-key'], seen[0]!.headers.authorization, seen[0]!.redirect, seen[0]!.url], ['good', 'Bearer good', 'error', 'https://gw.example/gw/v1/models'])
  assert.deepEqual([(await custom('nope')).state, (await custom('nope')).text], ['bad_key', 'Gateway refused this key.'])
  assert.equal((await custom('broke')).state, 'no_credit')
  assert.equal((await custom('busy')).state, 'limited')
  assert.equal((await custom('good', 'https://gw.example/elsewhere')).state, 'not_found')
  assert.deepEqual([(await custom('good', 'https://down.example')).state, (await custom('good', 'https://down.example')).text], ['unreachable', 'Nothing answers at this address.'])
  // A keyless gateway is asked without one.
  const before = seen.length
  await custom(null)
  assert.equal(seen[before]!.headers['x-api-key'], undefined)
  // OpenRouter: its key's balance read, which says what's left.
  const or = await checkProvider({ kind: 'openrouter', baseUrl: 'https://openrouter.example/or', apiKey: 'good' })
  assert.deepEqual([or.state, or.text, or.balance?.remaining], ['ok', 'Key works. $4.20 left.', 4.2])
  assert.equal((await checkProvider({ kind: 'openrouter', name: 'OpenRouter', baseUrl: 'https://openrouter.example/or', apiKey: 'bad' })).text, 'OpenRouter refused this key.')
  assert.equal((await checkProvider({ kind: 'openrouter', baseUrl: 'https://openrouter.example/or', apiKey: null })).state, 'bad_key')
  assert.deepEqual(modelIds({ data: [{ id: 'a b' }, { id: '' }, {}, { id: 'ok' }] }), ['ok'])
  assert.deepEqual(modelIds(null), [])
})
