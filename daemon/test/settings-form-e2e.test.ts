// What the form offers against what the real Claude Code accepts: `claude doctor` in a throwaway home, which reports
// each invalid value in a settings file with no login and no model. Skipped where Claude Code isn't installed (CI).
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'

const realClaude = [process.env.HN_E2E_CLAUDE, join(homedir(), '.local/bin/claude')].find((p) => p && existsSync(p))
const skip = realClaude ? false : 'Claude Code isn’t installed here'

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-form-e2e-')))
const HOME = join(scratch, 'home')
const CLAUDE = join(HOME, '.claude')
for (const k of Object.keys(process.env)) if (/^(CLAUDE_|CLAUDECODE|ANTHROPIC_)/.test(k)) delete process.env[k]
process.env.ANTHROPIC_BASE_URL = 'http://127.0.0.1:9'
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
const binary = realClaude ? realpathSync(realClaude) : ''
process.env.HOME = HOME
for (const d of [CLAUDE, process.env.HYPERNOVA_SUPPORT, process.env.HYPERNOVA_LOGS]) mkdirSync(d!, { recursive: true })

const { changeFormSetting, settingsForm } = await import('../src/agent-settings-form.ts')
const { CLAUDE_KEYS } = await import('../src/agent-settings-classes.ts')
const dirs = { claude: CLAUDE, claudeConfig: join(scratch, 'none.json') }
const FILE = join(CLAUDE, 'settings.json')
const noModels = async () => null

after(() => rmSync(scratch, { recursive: true, force: true }))

/** What doctor says of a file: each `key: why` it reports. */
function doctor(doc: unknown): string[] {
  writeFileSync(FILE, JSON.stringify(doc))
  const r = spawnSync(binary, ['doctor'], { env: { ...process.env, HOME }, cwd: HOME, input: '', encoding: 'utf8', timeout: 60_000 })
  const at = r.stdout.indexOf('Invalid settings')
  if (at < 0) return []
  const block = r.stdout.slice(at, r.stdout.indexOf('\n\n', at) < 0 ? undefined : r.stdout.indexOf('\n\n', at))
  return block.split('\n').filter((l) => l.startsWith('- ')).map((l) => l.slice(l.indexOf('›') + 2))
}

test('every value the form offers, the real Claude Code accepts', { skip }, async () => {
  writeFileSync(FILE, '{}')
  const { rows } = await settingsForm('claude', noModels, dirs)
  const picks = rows.filter((r) => r.of)
  assert.ok(picks.length >= 15, 'there are pickers to try')
  const rounds = Math.max(...picks.map((r) => r.of!.length))
  for (let i = 0; i < rounds; i++) {
    const doc: Record<string, any> = {}
    for (const r of picks) {
      const value = r.of![i % r.of!.length]!.value
      if (r.key.startsWith('permissions.')) (doc.permissions ??= {})[r.key.split('.')[1]!] = value
      else doc[r.key] = value
    }
    assert.deepEqual(doctor(doc), [], `round ${i}`)
  }
})

test('what the form writes through the real writer, the real Claude Code reads as valid', { skip }, async () => {
  writeFileSync(FILE, '{}')
  const edits: [string, unknown, boolean][] = [
    ['verbose', true, false],
    ['cleanupPeriodDays', 60, false],
    ['availableModels', ['opus', 'sonnet'], false],
    ['attribution', { commit: 'a', pr: 'b' }, false],
    ['permissions.additionalDirectories', ['/tmp'], true],
    ['permissions.defaultMode', 'plan', false],
    ['statusLine', { type: 'command', command: '/bin/true' }, true],
    ['apiKeyHelper', '/bin/true', true],
    ['spinnerVerbs', { mode: 'append', verbs: ['Pondering'] }, false],
  ]
  for (const [key, value, confirm] of edits) {
    const expected = (await settingsForm('claude', noModels, dirs)).hash
    await changeFormSetting({ agent: 'claude', key, value, expected, confirm }, noModels, dirs)
  }
  const doc = JSON.parse(readFileSync(FILE, 'utf8'))
  assert.equal(doc.cleanupPeriodDays, 60)
  assert.deepEqual(doctor(doc), [])
})

test('the keys the table says aren’t read here: no complaint at a wrong type, while a key it does check complains', { skip }, () => {
  const notRead = Object.entries(CLAUDE_KEYS).filter(([, e]) => e.notRead).map(([k]) => k)
  assert.ok(notRead.length >= 8)
  const doc: Record<string, unknown> = Object.fromEntries(notRead.map((k) => [k, 12345]))
  doc.verbose = 'not a boolean'
  const said = doctor(doc)
  assert.ok(said.some((l) => l.startsWith('verbose:')), `the check is on: ${said}`)
  assert.deepEqual(said.filter((l) => notRead.some((k) => l.startsWith(`${k}:`))), [], 'the table is out of date for this Claude Code: look at these keys again')
})

test('a managed-only key the real Claude Code itself says it ignored here is hidden by the form', { skip }, async () => {
  const said = doctor({ managedMcpServers: [] })
  assert.ok(said.some((l) => /managedMcpServers.*only honored from managed settings/.test(l)), `${said}`)
  assert.equal(CLAUDE_KEYS.managedMcpServers!.managed, true)
})

test('the version this was checked against is said, so a newer one is a reason to look again', { skip }, () => {
  const version = execFileSync(binary, ['--version'], { env: { ...process.env, HOME }, encoding: 'utf8', timeout: 30_000 }).trim()
  assert.match(version, /^\d+\.\d+\.\d+/)
  rmSync(FILE, { force: true })
})
