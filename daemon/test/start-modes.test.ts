// What a New session form opens on (start-modes.ts; docs/DECISIONS.md, "One starting mode, the agent's own"): the
// agent's own setting, from its own file, and anything in that file that isn't one of the form's modes falls back
// rather than widens. Every file here is this test's own, under a throwaway home.
import { test, after, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-start-modes-'))
const support = join(scratch, 'support')
process.env.HYPERNOVA_SUPPORT = support
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.HOME = join(scratch, 'home')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.CODEX_HOME = join(scratch, 'codex')
for (const d of [process.env.HOME, process.env.CLAUDE_CONFIG_DIR, process.env.CODEX_HOME, support]) mkdirSync(d, { recursive: true })
const claudeFile = join(process.env.CLAUDE_CONFIG_DIR, 'settings.json')
const codexFile = join(process.env.CODEX_HOME, 'config.toml')

// As v0.27.0 left it, before the daemon has read it: this is what an update finds.
const settingsFile = join(support, 'settings.json')
writeFileSync(settingsFile, JSON.stringify({ projectsRoot: '/nowhere', loginContinue: false, startModes: { claude: 'auto', codex: 'workspace-write' } }))

const store = await import('../src/store.ts')
const { startMode } = await import('../src/start-modes.ts')

after(() => rmSync(scratch, { recursive: true, force: true }))
beforeEach(() => {
  rmSync(claudeFile, { recursive: true, force: true })
  rmSync(codexFile, { recursive: true, force: true })
})

/** As the agents and the edit route save: a new file renamed over it, so two saves in one clock tick are still two. */
const save = (file: string, text: string) => {
  writeFileSync(`${file}.next`, text)
  renameSync(`${file}.next`, file)
}
const claude = (text: string) => save(claudeFile, text)
const claudeMode = (v: unknown) => claude(JSON.stringify({ permissions: { defaultMode: v } }))
const codex = (text: string) => save(codexFile, text)
/** A file's change time can be a clock tick coarse; this is past one, for a change that isn't a new file. */
const tick = () => new Promise((r) => setTimeout(r, 30))

test('v0.27.0\'s saved value is dropped and never written into the agent\'s file', () => {
  assert.equal(existsSync(claudeFile), false)
  assert.equal(existsSync(codexFile), false)
  assert.equal(startMode('claude'), 'default', 'not the Auto it had')
  assert.equal(startMode('codex'), 'read-only', 'not the Can edit it had')
  assert.equal(existsSync(claudeFile), false, 'no Auto written into settings.json')
  assert.equal(existsSync(codexFile), false)
  const kept = JSON.parse(readFileSync(settingsFile, 'utf8'))
  assert.equal('startModes' in kept, false, 'gone from the daemon\'s settings too')
  assert.equal('startModes' in store.settings, false)
  assert.equal(kept.loginContinue, false, 'and nothing else moved')
})

test('Claude Code: each of the form\'s four modes opens as itself', () => {
  for (const m of ['default', 'acceptEdits', 'plan', 'auto']) {
    claudeMode(m)
    assert.equal(startMode('claude'), m)
  }
})

test('Claude Code: bypass, Don\'t ask, a name for Ask, another agent\'s word and anything unknown open on Ask', () => {
  for (const m of ['bypassPermissions', 'dontAsk', 'manual', 'read-only', 'workspace-write', 'danger-full-access', 'somethingNew', '', 3, true, null, ['auto'], { auto: true }]) {
    claudeMode(m)
    assert.equal(startMode('claude'), 'default', JSON.stringify(m))
  }
})

test('Claude Code: not set, missing, broken and not a table all open on Ask', () => {
  assert.equal(startMode('claude'), 'default', 'missing')
  claude('{}')
  assert.equal(startMode('claude'), 'default', 'no permissions')
  claude(JSON.stringify({ permissions: 'auto' }))
  assert.equal(startMode('claude'), 'default', 'permissions isn\'t a table')
  claude('{ "permissions": { "defaultMode": "auto"')
  assert.equal(startMode('claude'), 'default', 'cut off')
  claude('["auto"]')
  assert.equal(startMode('claude'), 'default', 'not a table')
  claude(JSON.stringify({ defaultMode: 'auto' }))
  assert.equal(startMode('claude'), 'default', 'the key, in the wrong place')
})

test('Claude Code: a file that can\'t be read opens on Ask', { skip: process.getuid?.() === 0 ? 'root reads anything' : false }, async () => {
  claudeMode('auto')
  assert.equal(startMode('claude'), 'auto')
  await tick()
  chmodSync(claudeFile, 0o000)
  try {
    assert.equal(startMode('claude'), 'default')
  } finally {
    await tick()
    chmodSync(claudeFile, 0o600)
  }
  assert.equal(startMode('claude'), 'auto', 'and reads again once it can')
})

test('neither a folder nor a pipe in the file\'s place holds the daemon up', () => {
  mkdirSync(claudeFile)
  assert.equal(startMode('claude'), 'default', 'a folder')
  rmSync(claudeFile, { recursive: true })
  execFileSync('mkfifo', [claudeFile])
  assert.equal(startMode('claude'), 'default', 'a pipe')
  rmSync(claudeFile)
  execFileSync('mkfifo', [codexFile])
  assert.equal(startMode('codex'), 'read-only', 'a pipe')
})

test('a file that\'s a link is followed, as Claude Code does, and a dangling one is missing', () => {
  const real = join(scratch, 'dotfiles.json')
  writeFileSync(real, JSON.stringify({ permissions: { defaultMode: 'plan' } }))
  symlinkSync(real, claudeFile)
  assert.equal(startMode('claude'), 'plan')
  rmSync(real)
  assert.equal(startMode('claude'), 'default')
})

test('Codex: Can edit only from workspace-write, with asking on-request or not set', () => {
  assert.equal(startMode('codex'), 'read-only', 'missing')
  codex('')
  assert.equal(startMode('codex'), 'read-only', 'empty')
  const cases: [string, string][] = [
    ['sandbox_mode = "workspace-write"\n', 'workspace-write'],
    ['sandbox_mode = "workspace-write"\napproval_policy = "on-request"\n', 'workspace-write'],
    ['sandbox_mode = "read-only"\n', 'read-only'],
    ['sandbox_mode = "read-only"\napproval_policy = "on-request"\n', 'read-only'],
    ['approval_policy = "on-request"\n', 'read-only'],
  ]
  for (const [text, mode] of cases) {
    codex(text)
    assert.equal(startMode('codex'), mode, text)
  }
})

test('Codex: full access, never, and anything it can\'t place open on Read only', () => {
  for (const text of [
    'sandbox_mode = "danger-full-access"\n',
    'sandbox_mode = "danger-full-access"\napproval_policy = "on-request"\n',
    'sandbox_mode = "workspace-write"\napproval_policy = "never"\n',
    'approval_policy = "never"\n',
    'sandbox_mode = "workspace-write"\napproval_policy = "untrusted"\n',
    'sandbox_mode = "workspace-write"\napproval_policy = "on-failure"\n',
    'sandbox_mode = "workspace-write"\napproval_policy = { granular = { rules = true } }\n',
    'sandbox_mode = "workspace-write"\napproval_policy = "somethingNew"\n',
    'sandbox_mode = "somethingNew"\n',
    'sandbox_mode = "auto"\n',
    'sandbox_mode = 3\n',
    'sandbox_mode = { a = 1 }\n',
    '[profile]\nsandbox_mode = "workspace-write"\n',
    'sandbox_mode = "workspace-write\n',
    'this isn\'t toml',
  ]) {
    codex(text)
    assert.equal(startMode('codex'), 'read-only', text)
  }
})

test('Codex: a file that can\'t be read opens on Read only', { skip: process.getuid?.() === 0 ? 'root reads anything' : false }, async () => {
  codex('sandbox_mode = "workspace-write"\n')
  assert.equal(startMode('codex'), 'workspace-write')
  await tick()
  chmodSync(codexFile, 0o000)
  try {
    assert.equal(startMode('codex'), 'read-only')
  } finally {
    chmodSync(codexFile, 0o600)
  }
})

test('each agent reads its own file', () => {
  claudeMode('auto')
  codex('sandbox_mode = "read-only"\n')
  assert.deepEqual([startMode('claude'), startMode('codex')], ['auto', 'read-only'])
  claudeMode('default')
  codex('sandbox_mode = "workspace-write"\n')
  assert.deepEqual([startMode('claude'), startMode('codex')], ['default', 'workspace-write'])
})

test('a change is seen at the next look: an agent\'s rename over the file, a hand edit in place, and the file going', async () => {
  claudeMode('plan')
  assert.equal(startMode('claude'), 'plan')
  claudeMode('auto')
  assert.equal(startMode('claude'), 'auto', 'renamed over')
  await tick()
  writeFileSync(claudeFile, JSON.stringify({ permissions: { defaultMode: 'plan' } }))
  assert.equal(startMode('claude'), 'plan', 'in place, the same size')
  claudeMode('bypassPermissions')
  assert.equal(startMode('claude'), 'default')
  claudeMode('plan')
  assert.equal(startMode('claude'), 'plan')
  rmSync(claudeFile)
  assert.equal(startMode('claude'), 'default')
})
