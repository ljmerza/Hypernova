// A custom provider's environment must never let Claude Code sign its requests with the owner's
// Claude login: with no key of its own, Claude Code would send the login to the provider's address.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

process.env.HYPERNOVA_SUPPORT = mkdtempSync(join(tmpdir(), 'hypernova-provider-env-'))
const { providerEnv } = await import('../src/store.ts')

const custom = { id: 'gw', name: 'Gateway', kind: 'custom' as const, baseUrl: 'http://127.0.0.1:4000', models: {}, builtIn: false }

test('a keyless provider gets a placeholder token, never the login', () => {
  const env = providerEnv({ ...custom, apiKey: null })
  assert.equal(env.ANTHROPIC_BASE_URL, 'http://127.0.0.1:4000')
  assert.equal(env.ANTHROPIC_AUTH_TOKEN, 'hypernova-no-key')
  assert.equal(env.ANTHROPIC_API_KEY, '')
})

test('a provider with a key sends that key', () => {
  const env = providerEnv({ ...custom, apiKey: 'sk-or-v1-abc' })
  assert.equal(env.ANTHROPIC_AUTH_TOKEN, 'sk-or-v1-abc')
})

test('the built-in provider keeps Claude Code\'s own login', () => {
  const env = providerEnv({ id: 'anthropic', name: 'Claude', kind: 'anthropic', baseUrl: null, apiKey: null, models: {}, builtIn: true })
  assert.equal(env.ANTHROPIC_AUTH_TOKEN, undefined)
  assert.equal(env.ANTHROPIC_BASE_URL, undefined)
})
