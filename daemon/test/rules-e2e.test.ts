// What the real Claude Code does with what the rules route writes, in a throwaway home, against a scripted API: no account, no
// model. Holds judgeRule to `claude doctor`, a written rule to a real call, and a running session to a change. Skipped where
// Claude Code isn't installed (CI).
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'

const realClaude = [process.env.HN_E2E_CLAUDE, join(homedir(), '.local/bin/claude')].find((p) => p && existsSync(p))
const skip = realClaude ? false : 'Claude Code isn’t installed here'
for (const k of Object.keys(process.env)) if (/^(CLAUDE|ANTHROPIC|HYPERNOVA|HN_)/.test(k)) delete process.env[k]

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-rules-e2e-')))
const HOME = join(scratch, 'home')
const CWD = join(HOME, 'proj')
const CLAUDE = join(scratch, 'claude')
process.env.HOME = HOME
process.env.CLAUDE_CONFIG_DIR = CLAUDE
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
mkdirSync(CWD, { recursive: true })
mkdirSync(CLAUDE, { recursive: true })
const SETTINGS = join(CLAUDE, 'settings.json')

const { startFake } = await import('./fake-provider/server.ts')
const { judgeRule } = await import('../src/rule-text.ts')
const { changeRule } = await import('../src/agent-rules.ts')

type Plan = { blocks: Array<{ text: string } | { tool: string; input: Record<string, unknown> }>; waitMs?: number }
let plan: Plan[] = []
let results: string[] = []
const fake = await startFake({
  dir: join(scratch, 'fake'),
  script: (_s: string, body: any) => {
    if (!(body.tools?.length && body.stream === true)) return null
    const last = body.messages?.at(-1)
    const tr = Array.isArray(last?.content) ? last.content.find((c: any) => c.type === 'tool_result') : null
    if (tr) {
      const t = typeof tr.content === 'string' ? tr.content : JSON.stringify(tr.content)
      if (!/doesn.t want to proceed/.test(t)) results.push(t)
    }
    return plan.shift() ?? { blocks: [{ text: 'done' }] }
  },
})
writeFileSync(join(CLAUDE, '.claude.json'), JSON.stringify({ hasCompletedOnboarding: true, projects: { [CWD]: { hasTrustDialogAccepted: true, hasCompletedProjectOnboarding: true } } }))
const env = { PATH: process.env.PATH!, HOME, CLAUDE_CONFIG_DIR: CLAUDE, TERM: 'xterm-256color', ANTHROPIC_AUTH_TOKEN: 'sk-fake', ANTHROPIC_BASE_URL: fake.url, DISABLE_AUTOUPDATER: '1', CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: '1' }
const QUIET = ['--settings', '{"disableAllHooks":true}', '--model', 'claude-sonnet-4-5']
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
const bash = (command: string): Plan => ({ blocks: [{ tool: 'Bash', input: { command, description: 'x' } }] })
const CMD = 'node -e 1'

after(async () => {
  await fake.close()
  rmSync(scratch, { recursive: true, force: true })
})

/** One call in print mode, with these rules: what became of it. */
function oneCall(permissions: object, command = CMD): Promise<'ran' | 'asked' | 'denied' | 'no tool'> {
  writeFileSync(SETTINGS, JSON.stringify({ permissions }))
  plan = [bash(command)]
  results = []
  return new Promise((resolve) => {
    const c = spawn(realClaude!, ['-p', '[Hypernova test] rule', '--output-format', 'json', ...QUIET, '--permission-mode', 'default'], { env, cwd: CWD, stdio: ['ignore', 'ignore', 'ignore'] })
    c.on('close', () => {
      const t = results[0] ?? ''
      resolve(/No such tool/.test(t) ? 'no tool' : /has been denied/.test(t) ? 'denied' : /requires approval|haven.t granted|needs approval/.test(t) ? 'asked' : 'ran')
    })
  })
}

test('judgeRule says what `claude doctor` says it skips', { skip, timeout: 90_000 }, () => {
  const table = ['Bash', 'Bash(*)', 'Bash(npm run test:*)', 'Bash(git *)', 'Read(~/x/**)', 'Edit(src/**)', 'Write(~/**)', 'WebFetch(domain:github.com)', 'WebSearch(claude)', 'mcp__server__tool', 'mcp__server', 'mcp__server__*', 'Foo(bar)', 'Bash(a:*b)', 'Bash(', 'Bash)', 'Bash(x)y', 'Bash()', 'bash', 'bash(ls)', 'WebFetch(https://x.test)', 'WebFetch(x.test)', 'mcp__a__b(c)', 'Read(a:*)', 'Bash(:*)', '*', 'Ba*', 'WebSearch(a*)', '(x)', 'a_b', 'Bash(a\\(b)']
  // doctor reads a list per kind; Ba* differs between them.
  for (const list of ['allow', 'deny'] as const) {
    writeFileSync(SETTINGS, JSON.stringify({ permissions: { [list]: table } }))
    const r = spawnSync(realClaude!, ['doctor'], { env, cwd: CWD, encoding: 'utf8', timeout: 40_000, input: '' })
    const out = `${r.stdout}\n${r.stderr}`
    assert.match(out, /Invalid permission rule/, 'doctor said what it skips')
    for (const rule of table) {
      const skipped = out.includes(`Invalid permission rule "${rule}" was skipped`)
      assert.equal(!!judgeRule(rule, list).problem, skipped, `${rule} in ${list}: doctor ${skipped ? 'skips it' : 'keeps it'}`)
    }
  }
})

test('what a client adds is what Claude Code does: Allow runs, Deny refuses, Ask asks', { skip, timeout: 120_000 }, async () => {
  assert.equal(await oneCall({}), 'asked')
  rmSync(SETTINGS)
  const add = (list: string, text: string, expected: string | null) => changeRule({ agent: 'claude', rule: { op: 'add', list, text }, expected, confirmed: true }, { claude: CLAUDE })
  const a = add('allow', `Bash(${CMD})`, null)
  const run = async () => {
    plan = [bash(CMD)]
    results = []
    await new Promise<void>((resolve) => spawn(realClaude!, ['-p', '[Hypernova test] rule', '--output-format', 'json', ...QUIET, '--permission-mode', 'default'], { env, cwd: CWD, stdio: ['ignore', 'ignore', 'ignore'] }).on('close', () => resolve()))
    return results[0] ?? ''
  }
  assert.match(await run(), /completed with no output/)
  const b = add('ask', `Bash(${CMD})`, a.hash)
  assert.match(await run(), /haven.t granted|requires approval/)
  add('deny', `Bash(${CMD})`, b.hash)
  assert.match(await run(), /has been denied/)
  assert.deepEqual(JSON.parse(readFileSync(SETTINGS, 'utf8')).permissions, { allow: [`Bash(${CMD})`], ask: [`Bash(${CMD})`], deny: [`Bash(${CMD})`] })
})

test('deny beats ask beats allow, a bad rule is skipped and the rest kept, a list that isn’t one is ignored', { skip, timeout: 180_000 }, async () => {
  const rule = `Bash(${CMD})`
  assert.equal(await oneCall({ deny: [rule], allow: ['Bash'] }), 'denied')
  assert.equal(await oneCall({ ask: [rule], allow: ['Bash'] }), 'asked')
  assert.equal(await oneCall({ ask: ['Bash'], allow: [rule] }), 'asked')
  assert.equal(await oneCall({ deny: ['Bash'], allow: [rule] }), 'no tool')
  assert.equal(await oneCall({ allow: ['Bash(', 'bash', 123, rule] }), 'ran')
  assert.equal(await oneCall({ allow: ['bash'] }), 'asked')
  assert.equal(await oneCall({ allow: [` ${rule} `] }), 'asked', 'spaces round it, why the server trims')
  assert.equal(await oneCall({ allow: 'nope' }), 'asked')
})

test('a running session takes a rule change at its next call, within seconds', { skip, timeout: 120_000 }, async () => {
  const pty = createRequire(import.meta.url)('node-pty')
  writeFileSync(SETTINGS, JSON.stringify({ permissions: { allow: [`Bash(${CMD})`] } }))
  let screen = ''
  const c = pty.spawn(realClaude!, QUIET, { cols: 120, rows: 40, cwd: CWD, env })
  c.onData((d: string) => {
    screen += d
  })
  const turn = async () => {
    plan = [bash(CMD)]
    results = []
    screen = ''
    c.write('[Hypernova test] go\r')
    for (let t0 = Date.now(); Date.now() - t0 < 20_000; await sleep(200)) {
      if (results.length) return /denied/.test(results[0]!) ? 'denied' : 'ran'
      if (/Do you want to proceed/i.test(screen.replace(/\x1b\[[0-9;?]*[ -/]*[@-~]/g, ' ').replace(/ +/g, ' '))) {
        c.write('\x1b')
        await sleep(1500)
        return 'asked'
      }
    }
    return 'nothing'
  }
  try {
    await sleep(8000)
    assert.equal(await turn(), 'ran')
    await sleep(1000)
    const hash = (await import('node:crypto')).createHash('sha256').update(readFileSync(SETTINGS)).digest('hex')
    // Through the real writer, so through its rename.
    const off = changeRule({ agent: 'claude', rule: { op: 'delete', list: 'allow', index: 0 }, expected: hash }, { claude: CLAUDE })
    await sleep(4000)
    assert.equal(await turn(), 'asked', 'taken off Allow')
    const on = changeRule({ agent: 'claude', rule: { op: 'add', list: 'allow', text: `Bash(${CMD})` }, expected: off.hash, confirmed: true }, { claude: CLAUDE })
    await sleep(4000)
    assert.equal(await turn(), 'ran', 'put back on Allow')
    await sleep(1000)
    changeRule({ agent: 'claude', rule: { op: 'add', list: 'deny', text: `Bash(${CMD})` }, expected: on.hash }, { claude: CLAUDE })
    await sleep(4000)
    assert.equal(await turn(), 'denied', 'added to Deny')
  } finally {
    c.kill()
  }
})
