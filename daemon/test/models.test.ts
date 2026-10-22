// The models each provider offers (models.ts): Claude Code's own list as its /model picker has it,
// the built-in list while it hasn't answered, OpenRouter's catalogue (only models that call tools,
// read at the provider's own address without the key, never through a redirect, kept for hours),
// and the providers' settings first. Against the fake provider, never the real one.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { startFake } from './fake-provider/server.ts'

const scratch = mkdtempSync(join(tmpdir(), 'hn-models-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
mkdirSync(process.env.HOME, { recursive: true })

const fake = await startFake({ dir: join(scratch, 'fake') })
const store = await import('../src/store.ts')
const models = await import('../src/models.ts')

after(async () => {
  await fake.close()
  rmSync(scratch, { recursive: true, force: true })
})

const seen: string[] = []
models.useFetch((url, init) => {
  seen.push(`${url.host}${url.pathname} ${(init.headers as Record<string, string>).authorization ?? 'no-key'} ${init.redirect}`)
  return fetch(url, init)
})

/** Claude Code 2.1.302's answer on a Max plan, as `initialize` gives it (trimmed). */
const CLAUDE_CODE = [
  { value: 'default', resolvedModel: 'claude-opus-5-5', displayName: 'Default (recommended)', description: 'Opus 5.5 · Best for everyday, complex tasks' },
  { value: 'opus', resolvedModel: 'claude-opus-5-5', displayName: 'Opus 5.5', description: 'For complex work and everyday tasks' },
  { value: 'fable', resolvedModel: 'claude-fable-5-1', displayName: 'Fable 5.1', description: 'For your toughest challenges' },
  { value: 'sonnet', resolvedModel: 'claude-sonnet-5-5', displayName: 'Sonnet 5.5', description: 'Most efficient for simpler tasks' },
  { value: 'haiku', resolvedModel: 'claude-haiku-5-5', displayName: 'Haiku 5.5', description: 'Fastest for quick answers' },
  { value: 'claude-haiku-4-5-20251001', resolvedModel: 'claude-haiku-4-5-20251001', displayName: 'Haiku 4.5', description: 'Fastest for quick answers' },
  { value: 'claude-opus-4-6', resolvedModel: 'claude-opus-4-6', displayName: 'Opus 4.6', description: 'Best for everyday, complex tasks' },
  { value: 'opus', displayName: 'Opus again' },
  { nope: true },
]

test('Claude Code’s list: the default is no --model, an id is an earlier version, the name it shows apart', () => {
  const list = models.fromClaude(CLAUDE_CODE)
  assert.deepEqual(
    list.map((m) => [m.value, m.name, m.model, m.older, m.context]),
    [
      [null, 'Default', 'claude-opus-5-5', false, 1_000_000],
      ['opus', 'Opus 5.5', 'claude-opus-5-5', false, 1_000_000],
      ['fable', 'Fable 5.1', 'claude-fable-5-1', false, 1_000_000],
      ['sonnet', 'Sonnet 5.5', 'claude-sonnet-5-5', false, 1_000_000],
      ['haiku', 'Haiku 5.5', 'claude-haiku-5-5', false, 1_000_000],
      ['claude-haiku-4-5-20251001', 'Haiku 4.5', 'claude-haiku-4-5-20251001', true, 200_000],
      ['claude-opus-4-6', 'Opus 4.6', 'claude-opus-4-6', true, 200_000],
    ],
  )
  // "Opus 5.5 · Best for…": the phone draws the model apart, never a "·"-joined line.
  assert.equal(list[0]!.description, 'Best for everyday, complex tasks')
  assert.equal(models.plainDescription('For your toughest challenges'), 'For your toughest challenges')
  assert.deepEqual(models.fromClaude('nope'), [])
  // The list this daemon knows has the current ones, Fable among them.
  assert.deepEqual(models.CLAUDE_BUILTIN.map((m) => m.value), [null, 'opus', 'fable', 'sonnet', 'haiku'])
})

test('OpenRouter’s catalogue: models that call tools, by id, prices per million tokens, no vendor in the name', () => {
  const list = models.parseCatalogue({
    data: [
      { id: 'openai/gpt-5', name: 'OpenAI: GPT-5', context_length: 400000, pricing: { prompt: '0.00000125', completion: '0.00001' }, supported_parameters: ['tools'] },
      { id: 'x/no-tools', name: 'X: No Tools', supported_parameters: ['temperature'] },
      { id: 'openrouter/auto', name: 'Auto Router', context_length: 2000000, pricing: { prompt: '-1', completion: '-1' } },
      { id: 'bad id', name: 'Spaces' },
      { id: 'openai/gpt-5', name: 'Twice' },
      { id: 'deepseek/deepseek-chat-v3:free', name: 'DeepSeek: V3 (free)', context_length: 163840, pricing: { prompt: '0', completion: '0' }, supported_parameters: ['tools'] },
      { id: 'anthropic/claude-fable-5.1', name: 'Anthropic: Claude Fable 5.1', created: 1790000000, context_length: 1000000, supported_parameters: ['tools'] },
      { id: 'anthropic/claude-fable-5.1:batch', name: 'Anthropic: Claude Fable 5.1 (batch)', created: 1790000000, supported_parameters: ['tools'] },
    ],
  })
  assert.deepEqual(
    list.map((m) => [m.value, m.name, m.context, m.price]),
    [
      // Newest first, then by id; no batch-priced copies.
      ['anthropic/claude-fable-5.1', 'Claude Fable 5.1', 1000000, null],
      ['deepseek/deepseek-chat-v3:free', 'V3 (free)', 163840, { input: 0, output: 0 }],
      ['openai/gpt-5', 'GPT-5', 400000, { input: 1.25, output: 10 }],
      // A price that varies isn't one; an entry that doesn't say what it supports is offered.
      ['openrouter/auto', 'Auto Router', 2000000, null],
    ],
  )
  assert.deepEqual(models.parseCatalogue({ nope: 1 }), [])
  assert.equal(models.catalogueUrl('https://openrouter.ai/api')?.href, 'https://openrouter.ai/api/v1/models')
  assert.equal(models.catalogueUrl('https://user:pw@openrouter.ai/api'), null)
  assert.equal(models.catalogueUrl('file:///etc/passwd'), null)
})

test('a provider’s settings come first, and the catalogue doesn’t list them twice', () => {
  const p = { id: 'p', kind: 'openrouter', baseUrl: null, models: { default: 'openai/gpt-5', sonnet: 'anthropic/claude-sonnet-4.5' } }
  const catalogue = models.parseCatalogue({ data: [{ id: 'openai/gpt-5', name: 'OpenAI: GPT-5', context_length: 400000, pricing: { prompt: '0.00000125', completion: '0.00001' } }, { id: 'z-ai/glm-5', name: 'Z.ai: GLM 5' }] })
  const own = models.settingsModels(p, catalogue)
  assert.deepEqual(own.map((m) => [m.value, m.name, m.model, m.slot, m.context]), [
    [null, 'GPT-5', 'openai/gpt-5', 'default', 400000],
    ['sonnet', 'claude-sonnet-4.5', 'anthropic/claude-sonnet-4.5', 'sonnet', null],
  ])
  assert.deepEqual(models.mergeLists(own, catalogue).map((m) => m.model), ['openai/gpt-5', 'anthropic/claude-sonnet-4.5', 'z-ai/glm-5'])
  // No default named: Claude Code's own, still offered.
  assert.deepEqual(models.settingsModels({ ...p, models: {} }).map((m) => [m.value, m.name, m.slot]), [[null, 'Default', 'default']])
})

test('every provider’s list: Claude Code’s once it answers, OpenRouter’s catalogue read without the key and kept', async () => {
  models.forgetModelLists()
  let asked = 0
  // Claude Code can't be asked: the list this daemon knows, said to be that.
  models.useClaudeProbe(async () => {
    asked++
    throw new Error('no claude here')
  })
  const or = store.saveProvider({ name: 'Fake OpenRouter', kind: 'openrouter', baseUrl: `${fake.url}/or`, apiKey: 'fake-or-key', models: { sonnet: 'anthropic/claude-sonnet-4.5' } })
  const custom = store.saveProvider({ name: 'Gateway', kind: 'custom', baseUrl: `${fake.url}/gw`, apiKey: null, models: { default: 'local/coder' } })
  let lists = await models.listModels()
  const builtIn = lists.find((l) => l.providerId === 'anthropic')!
  assert.equal(builtIn.source, 'builtin')
  assert.equal(builtIn.error, 'unreachable')
  assert.equal(asked, 1)
  const orList = lists.find((l) => l.providerId === or.id)!
  assert.equal(orList.source, 'openrouter')
  assert.deepEqual(orList.models.map((m) => m.value), [null, 'sonnet', 'openai/gpt-5', 'qwen/qwen3-coder:free'])
  assert.equal(orList.models[1]!.name, 'Claude Sonnet 4.5')
  assert.equal(orList.models[1]!.context, 1_000_000)
  // At the provider's own address, with no key and no redirects.
  assert.equal(seen.at(-1), `${new URL(fake.url).host}/or/v1/models no-key error`)
  assert.deepEqual(lists.find((l) => l.providerId === custom.id)!.models.map((m) => [m.value, m.model]), [[null, 'local/coder']])

  // Asked again: the catalogue is kept, nothing is sent.
  const n = seen.length
  lists = await models.listModels(or.id)
  assert.equal(lists.length, 1)
  assert.equal(seen.length, n)

  // Claude Code answers (here, or the "/" list's ask does): its list from then on.
  models.useClaudeProbe(async () => models.noteClaudeModels(CLAUDE_CODE))
  models.forgetModelLists()
  lists = await models.listModels('anthropic')
  assert.equal(lists[0]!.source, 'claude')
  assert.equal(lists[0]!.models.find((m) => m.value === 'fable')?.name, 'Fable 5.1')
  assert.doesNotMatch(JSON.stringify(lists), /fake-or-key/)
})

test('an unreachable catalogue keeps the settings’ models and says so', async () => {
  models.forgetModelLists()
  const p = store.saveProvider({ name: 'Down', kind: 'openrouter', baseUrl: 'http://127.0.0.1:9/or', apiKey: 'k', models: { default: 'openai/gpt-5' } })
  const [list] = await models.listModels(p.id)
  assert.equal(list!.error, 'unreachable')
  assert.deepEqual(list!.models.map((m) => [m.value, m.model]), [[null, 'openai/gpt-5']])
})
