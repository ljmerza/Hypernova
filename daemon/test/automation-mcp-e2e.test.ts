// An automation goes without a project's MCP servers nobody approved, on both ways it can run, and says so: a real Claude Code in a
// throwaway home, with no login and a dead API address, so nothing reaches a model. Servers leave a marker file when they start.
// Skipped where Claude Code isn't installed (CI).
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, realpathSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'

const realClaude = [process.env.HN_E2E_CLAUDE, join(homedir(), '.local/bin/claude')].find((p) => p && existsSync(p))
const skip = realClaude ? false : 'Claude Code isn’t installed here'

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-auto-mcp-')))
const HOME = join(scratch, 'home')
const CLAUDE = join(scratch, 'claude')
// Nothing of a Claude Code this test may itself be running under, and nowhere for a request to go.
for (const k of Object.keys(process.env)) if (/^(CLAUDE_|CLAUDECODE|ANTHROPIC_)/.test(k)) delete process.env[k]
process.env.ANTHROPIC_BASE_URL = 'http://127.0.0.1:9'
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = CLAUDE
process.env.CODEX_HOME = join(scratch, 'codex')
process.env.HOME = HOME
process.env.ZDOTDIR = join(scratch, 'zdot')
process.env.HYPERNOVA_WSL = '0'
process.env.CLAUDE_BIN = realClaude ? realpathSync(realClaude) : join(scratch, 'none')
for (const d of [HOME, CLAUDE, process.env.HYPERNOVA_SUPPORT]) mkdirSync(d, { recursive: true })

const { HOOK_FILE, startHeld } = await import('../src/automations/daemon.ts')
const { eventsFor } = await import('../src/hub.ts')
const { startedTerm, endTerm, ensureTerm, termScreen } = await import('../src/terms.ts')
const { automationHold, holdArgs, holdWords } = await import('../src/mcp-view.ts')
const { writeJson } = await import('../src/util.ts')

const ended: string[] = []
after(async () => {
  for (const t of ended) {
    try {
      endTerm(t)
    } catch {}
  }
  await new Promise((r) => setTimeout(r, 300))
  rmSync(scratch, { recursive: true, force: true })
})

const MARKS = join(scratch, 'marks')
mkdirSync(MARKS)
const SERVER = join(scratch, 'tiny.cjs')
writeFileSync(SERVER, `require('fs').writeFileSync(${JSON.stringify(MARKS)} + '/' + process.argv[2], ''); process.stdin.resume()`)
const started = (n: string) => existsSync(join(MARKS, n))
const mcpJson = (...names: string[]) => JSON.stringify({ mcpServers: Object.fromEntries(names.map((n) => [n, { command: process.execPath, args: [SERVER, n] }])) })
const folder = (name: string, json?: string) => {
  const dir = join(scratch, name)
  mkdirSync(dir, { recursive: true })
  if (json !== undefined) writeFileSync(join(dir, '.mcp.json'), json)
  return dir
}
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
const run = (cwd: string) => ({ cwd, prompt: '[Hypernova test] say hi', permissionMode: 'default', title: 'A run', automation: { id: 'au_1', name: 'A run', runId: 'ru_1' }, allowedTools: [], appendSystemPrompt: 'An automation.' })
const notices = (sid: string) => eventsFor(sid).filter((e: any) => e.kind === 'notice').map((e: any) => e.text as string)

const settings = (o: unknown) => writeFileSync(join(CLAUDE, 'settings.json'), JSON.stringify(o))
writeFileSync(join(CLAUDE, '.claude.json'), JSON.stringify({ hasCompletedOnboarding: true }))
writeJson(HOOK_FILE, { hooks: {} })

test('what an automation goes without, as flags and in words', async () => {
  settings({ enabledMcpjsonServers: ['words-yes'], disabledMcpjsonServers: ['words-off'] })
  const hold = await automationHold(folder('words', mcpJson('words-yes', 'words-no', 'words-off')))
  assert.deepEqual(hold, { names: ['words-no', 'words-off'], waiting: ['words-no'], unread: false })
  assert.deepEqual(holdArgs(hold), { settings: JSON.stringify({ disabledMcpjsonServers: ['words-no', 'words-off'] }) })
  assert.match(holdWords(hold)!, /aren’t approved.*words-no\./)
  assert.ok(!holdWords(hold)!.includes('words-off'), 'one the owner turned down isn’t news')

  const big = await automationHold(folder('words-big', JSON.stringify({ mcpServers: {}, pad: 'x'.repeat(300 * 1024) })))
  assert.deepEqual(holdArgs(big), { 'strict-mcp-config': null })
  assert.match(holdWords(big)!, /no MCP servers at all/)

  const none = await automationHold(folder('words-none'))
  assert.deepEqual(holdArgs(none), {})
  assert.equal(holdWords(none), null)
  assert.equal(holdWords({ names: ['off'], waiting: [], unread: false }), null)
  settings({})
})

test('under the SDK: an approved server starts, one nobody approved doesn’t, and the chat says so', { skip, timeout: 120_000 }, async () => {
  settings({ enabledMcpjsonServers: ['sdk-good'] })
  const sid = await startHeld(run(folder('sdk', mcpJson('sdk-good', 'sdk-bad'))) as never, false)
  await sleep(6000)
  assert.equal(started('sdk-bad'), false, 'an automation started a server nobody approved')
  assert.equal(started('sdk-good'), true, 'an automation lost a server the owner approved')
  assert.match(notices(sid).join(' '), /aren’t approved.*sdk-bad/)
  settings({})
})

test('under the SDK: a .mcp.json through a link starts nothing either, and says so', { skip, timeout: 120_000 }, async () => {
  writeFileSync(join(scratch, 'elsewhere.json'), mcpJson('sdk-linked'))
  const linked = folder('sdk-link')
  symlinkSync(join(scratch, 'elsewhere.json'), join(linked, '.mcp.json'))
  const sid = await startHeld(run(linked) as never, false)
  await sleep(6000)
  assert.equal(started('sdk-linked'), false, 'an automation started a server through a link')
  assert.match(notices(sid).join(' '), /no MCP servers at all/)
})

test('in a shared terminal: it isn’t stopped by Claude Code’s question, and only what’s approved starts', { skip, timeout: 120_000 }, async () => {
  settings({ enabledMcpjsonServers: ['term-good'] })
  const dir = folder('term', mcpJson('term-good', 'term-bad'))
  const config = join(CLAUDE, '.claude.json')
  writeFileSync(config, JSON.stringify({ ...JSON.parse(readFileSync(config, 'utf8')), projects: { [dir]: { hasTrustDialogAccepted: true } } }))
  const sid = await startHeld(run(dir) as never, true)
  const term = startedTerm(sid)!
  ended.push(term)
  let text = ''
  for (let i = 0; i < 40 && !started('term-good'); i++) {
    await sleep(500)
    ensureTerm(term)
    text = (await termScreen(term))?.text ?? text
    assert.ok(!/New MCP server found/.test(text), `it stopped at Claude Code's question:\n${text}`)
  }
  assert.equal(started('term-good'), true, `an approved server didn't start:\n${text}`)
  await sleep(1500)
  assert.equal(started('term-bad'), false, 'a server nobody approved started')
  assert.match(notices(sid).join(' '), /aren’t approved.*term-bad/)
  settings({})
  // Its own settings file: the run's hooks, with the names, and the shared one left as it was.
  const own = readdirSync(process.env.HYPERNOVA_SUPPORT!).filter((f) => /^automation-hooks-[0-9a-f]{16}\.json$/.test(f))
  assert.equal(own.length, 1)
  assert.deepEqual(JSON.parse(readFileSync(join(process.env.HYPERNOVA_SUPPORT!, own[0]!), 'utf8')), { hooks: {}, disabledMcpjsonServers: ['term-bad'] })
  assert.deepEqual(JSON.parse(readFileSync(HOOK_FILE, 'utf8')), { hooks: {} })
})
