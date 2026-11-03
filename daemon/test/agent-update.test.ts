// Seeing, checking and updating Claude Code and Codex, with stand-ins for both programs on a throwaway home. No real update runs.
import { test, before, after, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { createServer, type Server } from 'node:http'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'hn-agent-update-')))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.HOME = join(scratch, 'home')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.CODEX_HOME = join(scratch, 'codex-home')
for (const d of [process.env.HOME, process.env.HYPERNOVA_SUPPORT, process.env.HYPERNOVA_LOGS]) mkdirSync(d, { recursive: true })

const CLAUDE_DIR = join(scratch, 'fake-claude')
const CODEX_DIR = join(scratch, 'fake-codex')
const CLAUDE = join(CLAUDE_DIR, 'claude')
const CODEX = join(CODEX_DIR, 'codex')

/** A stand-in: says what's in its `version` file, prints its `doctor` file, and for `update` runs its `update` script, noting how it was called. */
function fake(dir: string, bin: string, versionLine: (v: string) => string): void {
  mkdirSync(dir, { recursive: true })
  writeFileSync(
    bin,
    `#!/bin/sh
D="${dir}"
case "$1" in
  --version) v=$(cat "$D/version"); echo "${versionLine('$v')}" ;;
  doctor) echo "doctor $*" >> "$D/calls"; cat "$D/doctor" ;;
  update) echo "$*|$PATH|$(pwd)" >> "$D/calls"; . "$D/update" ;;
  app-server) cat "$D/appserver" 2>/dev/null || exit 1 ;;
  *) echo "other $*" >> "$D/calls" ;;
esac
`,
  )
  chmodSync(bin, 0o755)
}
fake(CLAUDE_DIR, CLAUDE, (v) => `${v} (Claude Code)`)
fake(CODEX_DIR, CODEX, (v) => `codex-cli ${v}`)
process.env.CLAUDE_BIN = CLAUDE
process.env.HYPERNOVA_CODEX = CODEX

const set = (dir: string, file: string, text: string) => writeFileSync(join(dir, file), text)
const calls = (dir: string) => (existsSync(join(dir, 'calls')) ? readFileSync(join(dir, 'calls'), 'utf8').trim().split('\n') : [])
const updates = (dir: string) => calls(dir).filter((l) => l.startsWith('update'))

const claudeDoctor = (running = 'native', extra = '') => `Claude Code doctor\n\nRunning: ${running} (2.1.316)\nPath: /x\nAuto-updates: enabled\nAuto-update channel: stable\n${extra}`
const codexDoctor = (action: string, latest: string | null = '0.166.0') => JSON.stringify({ schemaVersion: 1, codexVersion: '0.164.0', checks: { 'updates.status': { details: { 'update action': action, ...(latest ? { 'latest version': latest } : {}), secret: 'never-sent' } }, 'auth.credentials': { details: { 'auth file': 'never-sent' } } } })

let release = '2.1.320'
let lookups = 0
let lastChannel = ''
let server: Server
const mod = await import('../src/agent-update.ts')
const { versionRoute, planClaude, planCodex, planOldCodex, parseClaudeDoctor, parseCodexDoctor, claudeKindOf, npmFolderOf, caskOf, effectOf, codexWarning, onNetworkDisk, cleanLines, outcomeOf, isNewer, forgetAgentUpdates, startAgentUpdates, tellingLine } = mod
const { CODEX_CHECKED, CODEX_MIN } = await import('../src/codex/binary.ts')

before(async () => {
  server = createServer((req, res) => {
    lookups++
    lastChannel = (req.url ?? '').split('/').pop() ?? ''
    if (release === 'down') {
      res.statusCode = 500
      return res.end('no')
    }
    res.end(`${release}\n`)
  })
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r))
  process.env.HYPERNOVA_CLAUDE_RELEASES = `http://127.0.0.1:${(server.address() as { port: number }).port}`
})
after(() => {
  server.close()
  rmSync(scratch, { recursive: true, force: true })
})

const stateFile = () => join(process.env.HYPERNOVA_SUPPORT!, 'agent-updates.json')
beforeEach(() => {
  forgetAgentUpdates()
  rmSync(stateFile(), { force: true })
  for (const d of [CLAUDE_DIR, CODEX_DIR]) rmSync(join(d, 'calls'), { force: true })
  rmSync(join(CODEX_DIR, 'appserver'), { force: true })
  set(CLAUDE_DIR, 'version', '2.1.316\n')
  set(CLAUDE_DIR, 'doctor', claudeDoctor())
  set(CLAUDE_DIR, 'update', 'exit 0\n')
  set(CODEX_DIR, 'version', '0.164.0\n')
  set(CODEX_DIR, 'doctor', codexDoctor('npm install -g @openai/codex'))
  set(CODEX_DIR, 'update', 'exit 0\n')
  release = '2.1.320'
  lookups = 0
  delete process.env.HYPERNOVA_AGENT_UPDATE_MS
  process.env.CLAUDE_BIN = CLAUDE
  process.env.HYPERNOVA_CODEX = CODEX
})

class Refusal extends Error {
  status: number
  code: string
  extra: unknown
  constructor(e: any) {
    super(e.message)
    this.status = e.status
    this.code = e.code
    this.extra = e.extra
  }
}

/** One call of the routes, as http.ts makes it. */
async function call(method: string, path: string, body?: unknown, query = ''): Promise<any> {
  let answer: unknown
  const deps = { body: async () => body, running: (agent: string) => (agent === 'claude' ? 2 : 1) }
  try {
    const handled = await versionRoute(path, method, new URL(`http://x${path}${query}`), null, (_r: unknown, _s: number, b: unknown) => (answer = b), deps as never)
    assert.ok(handled, `${method} ${path} wasn't handled`)
  } catch (e) {
    throw new Refusal(e)
  }
  return answer
}
const look = (agent: string) => call('GET', '/v1/agents/version', undefined, `?agent=${agent}`)
const check = (agent: string) => call('POST', '/v1/agents/version/check', { agent })
const update = (agent: string, extra: object = { confirmRun: true }) => call('POST', '/v1/agents/version/update', { agent, ...extra })

async function settled(agent: string, ms = 8000): Promise<any> {
  const until = Date.now() + ms
  for (;;) {
    const v = await look(agent)
    if (v.job?.state !== 'running') return v
    assert.ok(Date.now() < until, 'the update never ended')
    await new Promise((r) => setTimeout(r, 25))
  }
}

// ── What a kind of install can do ────────────────────────────────────────

test('a native Claude Code and an npm one in a folder this user can write update with its own command', () => {
  assert.deepEqual(planClaude('native', null, 'enabled', '/h/.local/share/claude/versions/2.1.316'), { how: 'native', words: 'the native installer', can: true, why: null, command: null })
  const npm = planClaude('npm-global', null, 'enabled', '/h/.nvm/versions/node/v22/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe', () => true)
  assert.equal(npm.can, true)
  assert.equal(npm.how, 'npm')
  assert.equal(planClaude('npm-local', null, null, '/h/.claude/local/node_modules/x').can, true)
})

test('an npm Claude Code in a folder it can’t write says so and shows the command, with no sudo offered by the daemon', () => {
  let asked = ''
  const p = planClaude('npm-global', null, 'enabled', '/usr/lib/node_modules/@anthropic-ai/claude-code/cli', (d) => ((asked = d), false))
  assert.equal(asked, '/usr/lib/node_modules')
  assert.equal(p.can, false)
  assert.match(p.why!, /isn’t writable/)
  assert.equal(p.command, 'npm install -g @anthropic-ai/claude-code@latest')
})

test('what a package manager owns isn’t updated from here: each says why and shows its own command', () => {
  const cases: [string, string, string | null, RegExp][] = [
    ['homebrew', '/opt/homebrew/Caskroom/claude-code/2.1/claude', 'brew upgrade claude-code', /Homebrew manages/],
    ['homebrew', '/opt/homebrew/Caskroom/claude-code@latest/2.1/claude', 'brew upgrade claude-code@latest', /Homebrew manages/],
    ['deb', '/usr/bin/claude', 'sudo apt update && sudo apt upgrade claude-code', /needs root/],
    ['rpm', '/usr/bin/claude', 'sudo dnf upgrade claude-code', /needs root/],
    ['apk', '/usr/bin/claude', 'apk update && apk upgrade claude-code', /needs root/],
    ['pacman', '/usr/bin/claude', null, /needs root/],
    ['mise', '/h/.local/share/mise/installs/claude/claude', null, /update it there/],
    ['asdf', '/h/.asdf/installs/claude/claude', null, /update it there/],
    ['something-new', '/x/claude', null, /package manager/],
  ]
  for (const [manager, real, command, why] of cases) {
    const p = planClaude('package-manager', manager, 'enabled', real)
    assert.equal(p.can, false, manager)
    assert.equal(p.command, command, manager)
    assert.match(p.why!, why, manager)
  }
})

test('a development build, an unknown install and DISABLE_UPDATES aren’t updated from here', () => {
  assert.equal(planClaude('development', null, null, '/x').can, false)
  const unknown = planClaude('unknown', null, null, '/x')
  assert.equal(unknown.can, false)
  assert.equal(unknown.command, 'claude update')
  const off = planClaude('native', null, 'disabled (set by env: DISABLE_UPDATES)', '/x')
  assert.equal(off.can, false)
  assert.match(off.why!, /DISABLE_UPDATES/)
  // Only the background check: `claude update` still works (its docs, and 2.1.316's doctor).
  assert.equal(planClaude('native', null, 'disabled (set by env: DISABLE_AUTOUPDATER)', '/x').can, true)
})

test('claude doctor’s lines are read from its real wording, and a doctor that says nothing falls back to where the file is', () => {
  const real = `Claude Code doctor\n\nRunning: package-manager (2.1.316)\nCommit: cd7\nPackage manager: homebrew\nPath: /x\nAuto-updates: disabled (set by env: DISABLE_AUTOUPDATER)\nAuto-update channel: stable\nSomething else: secret`
  assert.deepEqual(parseClaudeDoctor(real), { running: 'package-manager', manager: 'homebrew', channel: 'stable', updates: 'disabled (set by env: DISABLE_AUTOUPDATER)' })
  assert.deepEqual(parseClaudeDoctor('nothing here'), { running: null, manager: null, channel: null, updates: null })
  assert.equal(claudeKindOf('/h/.local/share/claude/versions/2.1.316').running, 'native')
  assert.equal(claudeKindOf('/p/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe').running, 'npm-global')
  assert.equal(claudeKindOf('/h/.claude/local/node_modules/@anthropic-ai/claude-code/cli').running, 'npm-local')
  assert.deepEqual(claudeKindOf('/opt/homebrew/Caskroom/claude-code/2/claude'), { running: 'package-manager', manager: 'homebrew' })
  assert.equal(claudeKindOf('/somewhere/claude').running, 'unknown')
  assert.equal(caskOf('/h/Caskroom/evil;rm -rf/claude'), 'claude-code')
})

test('each of Codex’s update actions, as 0.164.0 words them, is offered; anything else isn’t', () => {
  for (const [action, how] of [['npm install -g @openai/codex', 'npm'], ['bun install -g @openai/codex', 'bun'], ['pnpm add -g @openai/codex', 'pnpm'], ['vp install -g @openai/codex', 'vp'], ['brew upgrade --cask codex', 'homebrew'], ['standalone installer', 'standalone']] as const) {
    const p = planCodex(action, '/somewhere/codex', () => true)
    assert.equal(p.can, true, action)
    assert.equal(p.how, how, action)
  }
  for (const action of ['manual or unknown', 'a word from a later Codex', null]) {
    const p = planCodex(action, '/x', () => true)
    assert.equal(p.can, false)
    assert.match(p.why!, /can’t update itself/)
  }
  const stuck = planCodex('npm install -g @openai/codex', '/usr/lib/node_modules/@openai/codex/bin/codex.js', () => false)
  assert.equal(stuck.can, false)
  assert.equal(stuck.command, 'sudo npm install -g @openai/codex@latest')
  assert.equal(npmFolderOf('/a/lib/node_modules/@openai/codex/bin/codex.js'), '/a/lib/node_modules')
  assert.equal(planOldCodex('/a/lib/node_modules/@openai/codex/bin/codex.js').command, 'npm install -g @openai/codex@latest')
  assert.equal(planOldCodex('/usr/local/bin/codex').command, null)
})

test('only named fields leave codex doctor’s report, and a junk one is no report', () => {
  assert.deepEqual(parseCodexDoctor(`WARNING: x\n${codexDoctor('standalone installer')}`), { action: 'standalone installer', latest: '0.166.0' })
  assert.equal(parseCodexDoctor('not json'), null)
  assert.equal(parseCodexDoctor('{"checks":{}}'), null)
  assert.deepEqual(parseCodexDoctor(codexDoctor('x', '$(rm -rf) 1.2.3')), { action: 'x', latest: null })
})

test('versions compare part by part; a network disk is told from a local one', () => {
  assert.ok(isNewer('2.1.316', '2.1.99'))
  assert.ok(!isNewer('2.1.99', '2.1.316'))
  assert.ok(!isNewer('0.164.0', '0.164.0'))
  assert.ok(onNetworkDisk(0x6969))
  assert.ok(!onNetworkDisk(0xef53))
})

test('the words on the confirm name what happens to what’s running, per agent and kind', () => {
  assert.match(effectOf('claude', 'native'), /keeps its jobs/)
  assert.match(effectOf('claude', 'npm'), /npm replaces/)
  assert.match(effectOf('codex', 'npm'), /app server/)
  assert.equal(codexWarning(CODEX_CHECKED), null)
  assert.equal(codexWarning(null), null)
  assert.match(codexWarning('99.0.0')!, /last checked against Codex/)
  assert.equal(CODEX_MIN, '0.163.0')
})

// ── Looking and checking ─────────────────────────────────────────────────

test('opening runs nothing but --version and asks nobody outside', async () => {
  const v = await look('claude')
  assert.equal(v.installed, true)
  assert.equal(v.version, '2.1.316')
  assert.equal(v.latest, null)
  assert.equal(v.canUpdate, false)
  assert.equal(v.running, 2)
  assert.equal(lookups, 0)
  assert.deepEqual(calls(CLAUDE_DIR), [])
})

test('a check says what’s newest on the channel the program follows, and a second within minutes asks nobody again', async () => {
  const v = await check('claude')
  assert.equal(v.latest, '2.1.320')
  assert.equal(lastChannel, 'stable')
  assert.equal(v.newer, true)
  assert.equal(v.canUpdate, true)
  assert.equal(v.how, 'native')
  assert.equal(v.autoUpdates, true)
  assert.match(v.effect, /keeps its jobs/)
  assert.equal(lookups, 1)
  await check('claude')
  assert.equal(lookups, 1)
  // It's kept: a restarted daemon still has it.
  forgetAgentUpdates()
  assert.equal((await look('claude')).latest, '2.1.320')
})

test('a check when it’s current, and one that can’t reach the list, offer no update and say why', async () => {
  release = '2.1.316'
  const same = await check('claude')
  assert.equal(same.newer, false)
  assert.equal(same.canUpdate, false)
  assert.equal(same.problem, null)
  forgetAgentUpdates()
  rmSync(stateFile())
  release = 'down'
  const down = await check('claude')
  assert.equal(down.latest, null)
  assert.equal(down.canUpdate, false)
  assert.match(down.problem, /release list/)
})

test('Codex’s check asks Codex, and nothing else in its report reaches the phone', async () => {
  const v = await check('codex')
  assert.equal(v.latest, '0.166.0')
  assert.equal(v.how, 'npm')
  assert.equal(v.canUpdate, true)
  assert.equal(lookups, 0)
  assert.ok(calls(CODEX_DIR).includes('doctor doctor --json'))
  assert.ok(!JSON.stringify(v).includes('never-sent'))
})

test('an update past what this daemon was checked with is said before the confirm', async () => {
  const past = await check('codex')
  assert.match(past.warning, /last checked against Codex 0\.164\.0, and 0\.166\.0 is newer/)
  forgetAgentUpdates()
  rmSync(stateFile())
  set(CODEX_DIR, 'version', '0.163.0\n')
  set(CODEX_DIR, 'doctor', codexDoctor('npm install -g @openai/codex', '0.164.0'))
  assert.equal((await check('codex')).warning, null)
})

test('a Codex older than the floor gets no doctor and no update run: 0.39.0 takes either for a prompt', async () => {
  set(CODEX_DIR, 'version', '0.39.0\n')
  const v = await check('codex')
  assert.equal(v.canUpdate, false)
  assert.match(v.why, /older than the 0\.163\.0/)
  assert.deepEqual(calls(CODEX_DIR), [])
  await assert.rejects(update('codex'), (e: Refusal) => e.code === 'cant_update')
  assert.deepEqual(updates(CODEX_DIR), [])
})

test('Codex that can’t tell how it was installed says so', async () => {
  set(CODEX_DIR, 'doctor', codexDoctor('manual or unknown', null))
  const v = await check('codex')
  assert.equal(v.canUpdate, false)
  assert.match(v.why, /can’t tell/)
  assert.match(v.problem, /newest/)
})

test('not installed says so, and a check or an update can’t run', async () => {
  process.env.CLAUDE_BIN = join(scratch, 'no-such-claude')
  process.env.HYPERNOVA_CODEX = join(scratch, 'no-such-codex')
  const v = await look('codex')
  assert.equal(v.installed, false)
  assert.equal(v.version, null)
  assert.equal(v.canUpdate, false)
  assert.equal((await check('codex')).installed, false)
  await assert.rejects(update('codex'), (e: Refusal) => e.status === 409 && e.code === 'no_codex')
})

test('a wrong agent is refused on every route, and an unknown route isn’t handled', async () => {
  for (const f of [() => look('sh'), () => check('$(id)'), () => update('rm')]) await assert.rejects(f(), (e: Refusal) => e.status === 400 && e.code === 'bad_agent')
  assert.equal(await versionRoute('/v1/agents/version/other', 'POST', new URL('http://x/'), null, () => {}, { body: async () => ({}), running: () => 0 }), false)
})

// ── Updating ─────────────────────────────────────────────────────────────

test('the server holds an update to the fingerprint’s claim: nothing runs without it', async () => {
  await check('claude')
  for (const body of [{}, { confirmRun: false }, { confirmRun: 'true' }, { confirmRun: 1 }]) {
    await assert.rejects(update('claude', body), (e: Refusal) => e.status === 403 && e.code === 'confirm_run' && /fingerprint/.test(e.message))
  }
  assert.deepEqual(updates(CLAUDE_DIR), [])
})

test('a check comes first, and what can’t update is refused with its command', async () => {
  await assert.rejects(update('claude'), (e: Refusal) => e.status === 409 && e.code === 'check_first')
  set(CLAUDE_DIR, 'doctor', claudeDoctor('package-manager', 'Package manager: deb\n'))
  const v = await check('claude')
  assert.equal(v.canUpdate, false)
  assert.equal(v.command, 'sudo apt update && sudo apt upgrade claude-code')
  await assert.rejects(update('claude'), (e: Refusal) => e.code === 'cant_update' && (e.extra as any).command === v.command)
  assert.deepEqual(updates(CLAUDE_DIR), [])
})

test('the command is fixed: only `update`, in the home folder, with the program’s own folder first on PATH; nothing from the phone reaches it', async () => {
  await check('claude')
  set(CLAUDE_DIR, 'update', 'echo 2.1.320 > "$D/version"; echo done\n')
  await update('claude', { confirmRun: true, version: '1; touch /tmp/hn-evil', package: 'evil', path: '/tmp/evil', args: ['--bad'], command: 'rm' })
  const v = await settled('claude')
  assert.equal(v.job.state, 'updated')
  const [line] = updates(CLAUDE_DIR)
  const [args, path, cwd] = line!.split('|')
  assert.equal(args, 'update')
  assert.ok(path!.startsWith(`${CLAUDE_DIR}:`), path)
  assert.equal(cwd, process.env.HOME)
  assert.ok(!existsSync('/tmp/hn-evil'))
})

test('a real change is read back and said in plain words', async () => {
  await check('claude')
  set(CLAUDE_DIR, 'update', 'echo "Installing update..."; sleep 0.2; echo 2.1.320 > "$D/version"; echo "Successfully updated from 2.1.316 to version 2.1.320"\n')
  const first = await update('claude')
  assert.equal(first.job.state, 'running')
  assert.equal(first.job.from, '2.1.316')
  const v = await settled('claude')
  assert.equal(v.job.state, 'updated')
  assert.equal(v.job.to, '2.1.320')
  assert.equal(v.version, '2.1.320')
  assert.equal(v.newer, false)
  assert.equal(v.canUpdate, false)
  assert.match(v.job.message, /Claude Code is now 2\.1\.320, was 2\.1\.316/)
  assert.ok(v.job.endedAt >= v.job.startedAt)
  assert.ok(v.job.output.includes('Installing update...'))
  assert.ok(!('pid' in v.job))
  assert.ok(!existsSync(join(process.env.HYPERNOVA_SUPPORT!, 'agent-updates', 'claude.log')), 'the raw output is gone')
  assert.match(readFileSync(join(process.env.HYPERNOVA_LOGS!, 'daemon.log'), 'utf8'), /agent update: claude updated, 2\.1\.316 to 2\.1\.320/)
})

test('“Successfully updated” with the same version is no success: measured, Claude Code says it when npm did nothing', async () => {
  await check('claude')
  set(CLAUDE_DIR, 'update', 'echo "Successfully updated from 2.1.316 to version 2.1.320"\nexit 0\n')
  await update('claude')
  const v = await settled('claude')
  assert.equal(v.job.state, 'unchanged')
  assert.equal(v.job.to, '2.1.316')
  assert.match(v.job.message, /still 2\.1\.316: the update changed nothing/)
  assert.equal(v.canUpdate, true, 'it can be tried again')
})

test('another Claude Code holding the lock exits 0 and changes nothing: said in its own words', async () => {
  await check('claude')
  set(CLAUDE_DIR, 'update', 'echo "Another Claude process (PID 12) is currently running. Please try again in a moment."\nexit 0\n')
  await update('claude')
  const v = await settled('claude')
  assert.equal(v.job.state, 'unchanged')
  assert.match(v.job.message, /Another Claude process/)
})

test('a failed update says it failed, what it said, and that the version didn’t move', async () => {
  await check('claude')
  set(CLAUDE_DIR, 'update', 'echo "Current version: 2.1.316"\necho "Error: Failed to install native update" >&2\necho "Try running claude doctor for diagnostics" >&2\nexit 1\n')
  await update('claude')
  const v = await settled('claude')
  assert.equal(v.job.state, 'failed')
  assert.match(v.job.message, /didn’t finish\. It’s still 2\.1\.316/)
  assert.match(v.job.message, /Failed to install native update/)
})

test('a version that moved despite a failed exit is reported as what it is', async () => {
  await check('claude')
  set(CLAUDE_DIR, 'update', 'echo 2.1.320 > "$D/version"; echo "Error: cleanup failed" >&2; exit 1\n')
  await update('claude')
  const v = await settled('claude')
  assert.equal(v.job.state, 'updated')
  assert.match(v.job.message, /now 2\.1\.320.*also reported a problem.*cleanup failed/)
})

test('a program that no longer says its version after an update isn’t called a success', async () => {
  await check('claude')
  set(CLAUDE_DIR, 'update', 'rm "$D/version"; exit 0\n')
  await update('claude')
  const v = await settled('claude')
  assert.equal(v.job.state, 'failed')
  assert.match(v.job.message, /didn’t say its version/)
})

test('one at a time per program: a second is refused while the first runs, the other program isn’t', async () => {
  await check('claude')
  await check('codex')
  set(CLAUDE_DIR, 'update', 'sleep 0.5; echo 2.1.320 > "$D/version"\n')
  set(CODEX_DIR, 'update', 'echo 0.166.0 > "$D/version"\n')
  const both = await Promise.allSettled([update('claude'), update('claude')])
  assert.deepEqual(both.map((r) => r.status).sort(), ['fulfilled', 'rejected'])
  assert.equal(((both.find((r) => r.status === 'rejected') as PromiseRejectedResult).reason as Refusal).code, 'update_running')
  await assert.rejects(update('claude'), (e: Refusal) => e.status === 409 && e.code === 'update_running')
  await update('codex')
  assert.equal((await settled('codex')).job.state, 'updated')
  assert.equal((await settled('claude')).job.state, 'updated')
  assert.equal(updates(CLAUDE_DIR).length, 1)
})

test('an update that runs too long is stopped, and says so', async () => {
  process.env.HYPERNOVA_AGENT_UPDATE_MS = '150'
  await check('claude')
  set(CLAUDE_DIR, 'update', 'echo started; exec sleep 30\n')
  await update('claude')
  const v = await settled('claude')
  assert.equal(v.job.state, 'failed')
  assert.match(v.job.message, /was stopped after/)
})

test('output to the phone and the log is scrubbed of tokens, keys and paths', async () => {
  await check('claude')
  const secrets = ['npm_abcdefghijklmnopqrstuvwxyz0123456789', 'sk-ant-api03-abcdefghijklmnop', 'hunter2secret', 'ghp_abcdefghijklmnopqrstuvwxyz0123456789']
  set(
    CLAUDE_DIR,
    'update',
    `printf '\\033[31mError: npm ERR! //registry.npmjs.org/:_authToken=${secrets[0]}\\033[0m\\n'
echo "Authorization: Bearer ${secrets[1]}"
echo "PASSWORD=${secrets[2]}"
echo "fetching https://user:${secrets[3]}@example.com/x"
echo "/home/somebody/.claude.json failed"
exit 1
`,
  )
  await update('claude')
  const running = await look('claude')
  const v = await settled('claude')
  for (const shown of [JSON.stringify(running), JSON.stringify(v), readFileSync(join(process.env.HYPERNOVA_LOGS!, 'daemon.log'), 'utf8')]) {
    for (const s of secrets) assert.ok(!shown.includes(s), s)
    assert.ok(!shown.includes('.claude.json'))
    // eslint-disable-next-line no-control-regex
    assert.ok(!/\u001b/.test(shown))
  }
  assert.ok(JSON.stringify(v.job.output).includes('•••'))
})

test('a long output keeps its last lines, each cut to a phone’s size', () => {
  const many = Array.from({ length: 100 }, (_, i) => `line ${i}`).join('\r\n')
  const out = cleanLines(`${many}\n${'x'.repeat(1000)}`)
  assert.equal(out.length, 40)
  assert.equal(out[0], 'line 61')
  assert.ok(out[39]!.length <= 200)
})

test('leaving doesn’t stop it, and a new daemon finishes what the last one left running', async () => {
  await check('claude')
  // The first daemon starts it. Nothing here keeps a connection to it: only a saved pid and a file.
  set(CLAUDE_DIR, 'update', 'echo working; sleep 0.6; echo 2.1.320 > "$D/version"; echo finished\n')
  await update('claude')
  const saved = JSON.parse(readFileSync(stateFile(), 'utf8'))
  assert.equal(saved.claude.job.state, 'running')
  assert.ok(saved.claude.job.pid > 0)
  // A second daemon: memory gone, the file as it was.
  forgetAgentUpdates()
  let told: string | null = null
  startAgentUpdates((a) => (told = a))
  const during = await look('claude')
  assert.equal(during.job.state, 'running')
  assert.ok(during.job.output.includes('working'), 'the output is still read')
  const v = await settled('claude', 10_000)
  assert.equal(v.job.state, 'updated')
  assert.equal(v.job.to, '2.1.320')
  assert.equal(told, 'claude')
})

test('Codex’s app server not moving is said: measured, a new Codex leaves the running one alone', async () => {
  await check('codex')
  set(CODEX_DIR, 'update', 'echo 0.166.0 > "$D/version"\n')
  set(CODEX_DIR, 'appserver', '{"status":"running","cliVersion":"0.166.0","appServerVersion":"0.164.0"}\n')
  await update('codex')
  const v = await settled('codex')
  assert.equal(v.job.state, 'updated')
  assert.match(v.job.message, /Codex is now 0\.166\.0, was 0\.164\.0\. Its app server is still on 0\.164\.0 until it restarts/)
  assert.equal(updates(CODEX_DIR).length, 1)
})

test('what came of it, from the versions and the exit, whatever the words', () => {
  assert.equal(outcomeOf('claude', '1.0.0', '1.0.0', 0, false, ['Successfully updated']).state, 'unchanged')
  assert.equal(outcomeOf('claude', '1.0.0', '1.0.0', 1, false, []).state, 'failed')
  assert.equal(outcomeOf('claude', '1.0.0', '1.0.0', null, true, []).state, 'failed')
  assert.equal(outcomeOf('claude', '1.0.0', '1.1.0', 0, false, []).state, 'updated')
  assert.equal(outcomeOf('claude', '1.0.0', null, 0, false, []).state, 'failed')
  assert.equal(outcomeOf('codex', '1.0.0', '1.1.0', 0, false, [], '1.1.0').message, 'Codex is now 1.1.0, was 1.0.0.')
  assert.equal(tellingLine(['all good', 'Error: nope', 'bye']), 'Error: nope')
  assert.equal(tellingLine(['all good', 'bye']), 'bye')
  assert.equal(tellingLine([]), null)
})
