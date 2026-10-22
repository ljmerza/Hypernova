// Writing state files: the folder is made when it's missing, as on a Mac where Claude Code has
// never run (no ~/.claude yet), which used to stop "Connect Claude Code" with ENOENT.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync } from 'node:fs'
import { tmpdir, userInfo } from 'node:os'
import { join } from 'node:path'

// Everything in a scratch folder: the daemon's state, a Claude Code folder that doesn't exist
// yet, and the home and zsh folders, so the owner's ~/.zshrc and ~/.claude are never touched.
const scratch = mkdtempSync(join(tmpdir(), 'hypernova-test-'))
const claudeDir = join(scratch, 'home', '.claude')
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = claudeDir
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
mkdirSync(process.env.HOME, { recursive: true })
mkdirSync(process.env.ZDOTDIR, { recursive: true })
after(() => rmSync(scratch, { recursive: true, force: true }))

const { projectNameOf, writeJson, writeText } = await import('../src/util.ts')

test('writeJson and writeText make a missing folder first, private to the owner', () => {
  const json = join(scratch, 'new', 'deeper', 'state.json')
  writeJson(json, { ok: true })
  assert.deepEqual(JSON.parse(readFileSync(json, 'utf8')), { ok: true })
  assert.equal(statSync(join(scratch, 'new', 'deeper')).mode & 0o777, 0o700)
  assert.equal(statSync(json).mode & 0o777, 0o600)
  const text = join(scratch, 'other', 'note.txt')
  writeText(text, 'hello\n', 0o644)
  assert.equal(readFileSync(text, 'utf8'), 'hello\n')
  // A folder that exists keeps its mode.
  mkdirSync(join(scratch, 'open'), { mode: 0o755 })
  writeJson(join(scratch, 'open', 'x.json'), 1)
  assert.equal(statSync(join(scratch, 'open')).mode & 0o777, 0o755)
})

test('connecting Claude Code works before Claude Code has ever run (no ~/.claude)', async () => {
  const real = join(userInfo().homedir, '.zshrc')
  const before = existsSync(real) ? statSync(real).mtimeMs : null
  assert.equal(existsSync(claudeDir), false)
  const { installIntegration, uninstallIntegration } = await import('../src/hooks.ts')
  const status = installIntegration()
  assert.equal(status.hooks.installed, true)
  const settings = JSON.parse(readFileSync(join(claudeDir, 'settings.json'), 'utf8'))
  assert.ok(settings.hooks.PermissionRequest)
  assert.equal(uninstallIntegration().hooks.installed, false)
  // The owner's own ~/.zshrc stayed as it was.
  assert.equal(existsSync(real) ? statSync(real).mtimeMs : null, before)
})

test('projectNameOf: the home folder is "Home", any other folder its own name', () => {
  assert.equal(projectNameOf('/Users/you', '/Users/you'), 'Home')
  assert.equal(projectNameOf('/Users/you/', '/Users/you'), 'Home')
  assert.equal(projectNameOf('/home/dev', '/home/dev/'), 'Home')
  assert.equal(projectNameOf('/Users/you/Programming/hypernova', '/Users/you'), 'hypernova')
  assert.equal(projectNameOf('/Users/you-old', '/Users/you'), 'you-old')
  assert.equal(projectNameOf('/', '/Users/x'), '')
})
