// "New sessions start in" (start-modes.ts; docs/DECISIONS.md, "New sessions start in, per server and
// agent"): each agent's own, kept apart, and nothing saved or found on disk reads back as more than that
// agent's modes, so a stale or hand-edited settings file falls back rather than widens.
import { test, after, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-start-modes-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.HOME = join(scratch, 'home')
mkdirSync(process.env.HOME, { recursive: true })

const store = await import('../src/store.ts')
const { isStartMode, setStartMode, startMode } = await import('../src/start-modes.ts')

after(() => rmSync(scratch, { recursive: true, force: true }))
beforeEach(() => { delete store.settings.startModes })

test('unset: Ask for Claude Code, Read only for Codex', () => {
  assert.equal(startMode('claude'), 'default')
  assert.equal(startMode('codex'), 'read-only')
})

test('each agent keeps its own, and saving one leaves the other', () => {
  setStartMode('claude', 'auto')
  assert.equal(startMode('claude'), 'auto')
  assert.equal(startMode('codex'), 'read-only')
  setStartMode('codex', 'workspace-write')
  assert.equal(startMode('claude'), 'auto')
  assert.equal(startMode('codex'), 'workspace-write')
})

test('Bypass, full access, never and the other agent\'s words are never one', () => {
  for (const m of ['bypassPermissions', 'dontAsk', 'manual', 'read-only', 'workspace-write', 'danger-full-access', 'never', '', null, 3]) assert.equal(isStartMode('claude', m), false, String(m))
  for (const m of ['danger-full-access', 'never', 'on-request', 'default', 'acceptEdits', 'plan', 'auto', 'bypassPermissions', '', null, 3]) assert.equal(isStartMode('codex', m), false, String(m))
  setStartMode('claude', 'bypassPermissions')
  setStartMode('codex', 'danger-full-access')
  assert.equal(store.settings.startModes, undefined, 'nothing saved')
})

test('a hand-edited or stale file falls back, it never widens', () => {
  store.settings.startModes = { claude: 'bypassPermissions', codex: 'danger-full-access' }
  assert.equal(startMode('claude'), 'default')
  assert.equal(startMode('codex'), 'read-only')
  store.settings.startModes = { claude: 'workspace-write', codex: 'auto' }
  assert.equal(startMode('claude'), 'default')
  assert.equal(startMode('codex'), 'read-only')
  store.settings.startModes = { claude: 3, codex: { x: 1 } } as never
  assert.equal(startMode('claude'), 'default')
  assert.equal(startMode('codex'), 'read-only')
})

test('moving a phone\'s old setting: only where nothing usable is saved', () => {
  setStartMode('claude', 'plan', true)
  assert.equal(startMode('claude'), 'plan')
  setStartMode('claude', 'auto', true)
  assert.equal(startMode('claude'), 'plan', 'what the server has stays')
  store.settings.startModes = { claude: 'bypassPermissions' }
  setStartMode('claude', 'plan', true)
  assert.equal(startMode('claude'), 'plan', 'a value that can\'t be used counts as unset')
})
