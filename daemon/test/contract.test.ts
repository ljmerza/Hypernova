// The protocol's contract fixtures (docs/PROTOCOL.md, "Contract fixtures"): every message and answer
// the phone app parses, built here through the daemon's own code (the hub's views, the routes over
// HTTP, the broadcasts as phones hear them) and compared with the golden JSON in protocol/fixtures/.
// The app's ContractTest.kt runs the same files through its parsers, so a change on either side that
// the other doesn't follow fails one of the two suites. Volatile values (times, random ids, scratch
// paths, ports) are normalised before the comparison. A change to the protocol is made on purpose:
// `UPDATE_FIXTURES=1 npm test` (or `UPDATE_FIXTURES=1 node --test test/contract.test.ts`) writes the
// fixtures again; review the diff, then make the app's ContractTest pass.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync, spawn, type ChildProcess } from 'node:child_process'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fakeCodexBin } from './fake-codex/server.ts'

const FIXTURES = join(import.meta.dirname, '..', '..', 'protocol', 'fixtures')
const UPDATE = process.env.UPDATE_FIXTURES === '1'

const scratch = mkdtempSync(join(tmpdir(), 'hn-contract-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
process.env.FAKE_AGENTS_DIR = join(scratch, 'fake')
// `claude auth …` goes to the account tests' stand-in (fixtures/fake-claude.sh), signed out until a sign-in here finishes.
process.env.FAKE_CLAUDE_STATE = join(scratch, 'fake-auth')
process.env.HYPERNOVA_NO_DEV_APK = '1'
process.env.HYPERNOVA_WSL = '0'
const HOME = process.env.HOME
for (const d of [HOME, process.env.ZDOTDIR, process.env.FAKE_AGENTS_DIR, process.env.FAKE_CLAUDE_STATE, join(HOME, 'Projects')]) mkdirSync(d, { recursive: true })
const registry = join(process.env.CLAUDE_CONFIG_DIR, 'sessions')
mkdirSync(registry, { recursive: true })
// Codex installed (a stand-in that only says its version) and off: the snapshot's `codex`.
process.env.HYPERNOVA_CODEX = fakeCodexBin(join(scratch, 'codex'), '0.163.0')

// Claude Code as its install lays it out (a file named for its version, `claude` a link to it), a
// stand-in from claude-agents.test.ts cut down to what these routes need: `claude attach <job>`
// draws an empty input box that takes typed lines (eff*), cycles its mode line with Shift+Tab
// (mode*), or shows /btw's panel (btw*, fixtures/fake-claude-btw.mjs); `stop|rm|respawn` succeed.
mkdirSync(join(scratch, 'versions'))
mkdirSync(join(scratch, 'bin'))
const fake = join(scratch, 'versions', '2.1.314')
writeFileSync(
  fake,
  `#!/bin/sh
S="$FAKE_AGENTS_DIR"
case "$1" in
  attach)
    case "$2" in btw*) exec "${process.execPath}" "${join(import.meta.dirname, 'fixtures', 'fake-claude-btw.mjs')}" "$2" ;; esac
    case "$2" in mode*)
      m=0
      draw() {
        case $m in 0) l='manual mode on';; 1) l='accept edits on';; *) l='plan mode on';; esac
        printf '\\033[2J\\033[H\\033]0;Mine\\007\\n────────────────────\\n❯ \\n────────────────────\\n  ⏵⏵ %s (shift+tab to cycle)\\n' "$l"
      }
      stty -echo -icanon min 1 time 0 2>/dev/null; draw
      while k=$(dd bs=3 count=1 2>/dev/null) && [ -n "$k" ]; do
        case "$k" in *Z*) m=$(( (m + 1) % 3 )); draw ;; esac
      done
      exit 0 ;;
    esac
    draw() { printf '\\033[2J\\033[H\\033]0;Mine\\007\\n────────────────────\\n❯ \\n────────────────────\\n\\033[3;3H'; }
    stty echo icanon 2>/dev/null; draw
    while IFS= read -r l; do echo "$l" >> "$S/typed-$2"; draw; done
    exit 0
    ;;
  --version) echo "2.1.314 (Claude Code)" ;;
  auth) exec /bin/sh "${join(import.meta.dirname, 'fixtures', 'fake-claude.sh')}" "$@" ;;
  stop|rm|respawn) echo "$1 $2" >> "$S/verbs"; echo "Done $2" ;;
  *) exec sleep 30 ;;
esac
`,
)
chmodSync(fake, 0o755)
symlinkSync(fake, join(scratch, 'bin', 'claude'))
process.env.CLAUDE_BIN = join(scratch, 'bin', 'claude')

const freePort = () =>
  new Promise<number>((resolve) => {
    const s = createServer()
    s.listen(0, '127.0.0.1', () => {
      const port = (s.address() as { port: number }).port
      s.close(() => resolve(port))
    })
  })
const API = await freePort()
process.env.HYPERNOVA_API_PORT = String(API)
process.env.HYPERNOVA_LOCAL_PORT = String(await freePort())
process.env.HYPERNOVA_LAN_PORT = '0'

// The app the computer hands out (http.ts, findApk): the snapshot's `mac.app`, then `{t:"app"}`.
const apkDir = join(process.env.HYPERNOVA_SUPPORT, 'android')
mkdirSync(apkDir, { recursive: true })
writeFileSync(join(apkDir, 'hypernova.apk'), 'not really an apk')
writeFileSync(join(apkDir, 'hypernova.json'), JSON.stringify({ versionCode: 4200, versionName: '0.42.0' }))

const store = await import('../src/store.ts')
const { addDevice, settings } = store
settings.claudeAgents = true
// The local listener off until its own test turns it on: whether it starts by itself depends on this machine's network.
settings.lanAccess = false
const hub = await import('../src/hub.ts')
const { handleHook } = await import('../src/hooks.ts')
const { startServers, startPairing, announceApp } = await import('../src/http.ts')
const { menuOf } = await import('../src/term/shared.ts')
const { todoNow } = await import('../src/todos.ts')
const { hold } = await import('../src/held.ts')
const agents = await import('../src/agents.ts')
const { listProjects, createProject } = await import('../src/services.ts')
const { sendCodex } = await import('../src/codex/account.ts')
const { setLanAccess } = await import('../src/lan.ts')
const { getAccount } = await import('../src/account.ts')
const { useClaudeProbe, noteClaudeModels } = await import('../src/models.ts')
// Claude Code's own model list, as its initialize answer gives it (no Claude Code is started).
useClaudeProbe(async () =>
  noteClaudeModels([
    { value: 'default', displayName: 'Default (recommended)', description: 'Opus 5.5 · Best for everyday tasks', resolvedModel: 'claude-opus-5-5' },
    { value: 'sonnet', displayName: 'Sonnet', resolvedModel: 'claude-sonnet-5-5' },
    { value: 'sonnet[1m]', displayName: 'Sonnet (1M context)', resolvedModel: 'claude-sonnet-5-5[1m]' },
  ]),
)
const rules = await import('../src/permission-rules.ts')
// No managed policy from this machine's /etc: the rules in effect are the scratch ones only.
rules.useManagedDir(join(scratch, 'managed'))

const TOKEN = 'contract-test-device-token-0123456789abcdef'
addDevice({ id: 'phone', name: 'Pixel', model: 'Pixel 9', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)
const closeServers = startServers()

/** Every message the phones hear from now on. */
const feed: any[] = []
hub.addClient({ send: (s) => void feed.push(JSON.parse(s)), phone: true })
const heard = (t: string, f: (m: any) => boolean = () => true) => feed.filter((m) => m.t === t && f(m)).at(-1)

const procs: ChildProcess[] = []
after(async () => {
  // Nothing waits on an answer any more (an ask's 10-minute timer would hold the run open).
  for (const s of hub.listSessions()) hub.resolveSession(s.id)
  await closeServers()
  setLanAccess(false)
  for (const p of procs) p.kill('SIGKILL')
  const terms = join(process.env.HYPERNOVA_SUPPORT!, 'terms')
  for (const dir of [terms, registry]) {
    for (const f of existsSync(dir) ? readdirSync(dir) : []) {
      if (!f.endsWith('.json')) continue
      try {
        process.kill(JSON.parse(readFileSync(join(dir, f), 'utf8')).pid, 'SIGKILL')
      } catch {}
    }
  }
  rmSync(scratch, { recursive: true, force: true })
})

function call(method: string, path: string, body?: unknown, token: string | null = TOKEN): Promise<{ status: number; json: any }> {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? undefined : JSON.stringify(body)
    const headers: Record<string, string | number> = { host: `127.0.0.1:${API}` }
    if (token) headers.authorization = `Bearer ${token}`
    if (data) Object.assign(headers, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(data) })
    const req = request({ host: '127.0.0.1', port: API, method, path, headers }, (res) => {
      let s = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (s += c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, json: s ? JSON.parse(s) : null }))
    })
    req.on('error', reject)
    req.end(data)
  })
}
const get = (path: string) => call('GET', path)
const post = (path: string, body: unknown = {}) => call('POST', path, body)
/** A route's 200 answer (any other fails the test with the daemon's own error). */
async function ok(p: Promise<{ status: number; json: any }>): Promise<any> {
  const r = await p
  assert.equal(r.status, 200, JSON.stringify(r.json))
  return r.json
}

// ── Normalising and comparing ─────────────────────────────────────────────
/** Every time on the wire becomes this one (2027-01-15): the app reads them as numbers, never compares them with its clock here. */
const T = 1_800_000_000_000
const TIMES = new Set([
  'createdAt', 'lastActivityAt', 'ts', 'at', 'since', 'observedAt', 'expiresAt', 'turnStartedAt', 'startedAt', 'endedAt', 'resetsAt',
  'checkedAt', 'updatedAt', 'askedAt', 'autoResumeAt', 'modelsObservedAt', 'mtime', 'until', 'lastSeenAt', 'answeredAt',
])
const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi
/** The session ids this file picks itself: kept as they are, so both suites can name them. */
const MAIN = 'c0ffee00-0000-4000-8000-00000000c0de'
const LIMITED = 'c0ffee00-0000-4000-8000-00000000beef'
const KEPT_IDS = new Set([MAIN, LIMITED])

/**
 * [v] with what changes from run to run made fixed: times (by key), random UUIDs (numbered in the
 * order they first appear, so the same id stays the same id within a fixture), the scratch folder
 * (its home as /home/me), and the few values this machine or the moment decides (by key).
 */
function normalise(v: unknown): unknown {
  const ids = new Map<string, string>()
  const str = (s: string) =>
    s
      .split(join(scratch, 'home')).join('/home/me')
      .split(scratch).join('/tmp/hn')
      // A time inside an id ("qn:1793476377000:9").
      .replace(/\b1[789]\d{11}\b/g, String(T))
      .replace(UUID, (u) => {
        const k = u.toLowerCase()
        if (KEPT_IDS.has(k)) return u
        if (!ids.has(k)) ids.set(k, `00000000-0000-4000-8000-${String(ids.size + 1).padStart(12, '0')}`)
        return ids.get(k)!
      })
  const walk = (x: unknown, key: string | null): unknown => {
    if (typeof x === 'string') return str(x)
    if (typeof x === 'number') return key && TIMES.has(key) ? T : x
    if (Array.isArray(x)) return x.map((y) => walk(y, null))
    if (x && typeof x === 'object') {
      const out: Record<string, unknown> = {}
      for (const [k, y] of Object.entries(x)) out[k] = walk(y, k)
      return fixed(out)
    }
    return x
  }
  return walk(v, null)
}
/** Values set by this machine (its name, id, the daemon's version, the listener's port and key) or by chance (tokens, terminal ids). */
function fixed(o: Record<string, any>): Record<string, any> {
  if ('daemonVersion' in o && 'projectsRoot' in o) Object.assign(o, { id: 'mac-id', name: 'server', daemonVersion: '0.0.0' }, 'tz' in o ? { tz: 'UTC' } : {})
  if ('token' in o && 'deviceId' in o) Object.assign(o, { token: 'device-token', deviceId: 'device-id' }, o.mac ? { mac: { id: 'mac-id', name: 'server' } } : {})
  // The listener's: only values of the shape the app takes are replaced, so a change of shape still shows.
  if ('fingerprint' in o && 'addresses' in o && 'port' in o) {
    if (typeof o.port === 'number') o.port = 47323
    if (Array.isArray(o.addresses) && o.addresses.every((a: unknown) => typeof a === 'string')) o.addresses = ['192.168.1.20']
    if (typeof o.fingerprint === 'string' && /^[0-9a-f]{64}$/.test(o.fingerprint)) o.fingerprint = 'ab'.repeat(32)
  }
  // A shared terminal's id is the holder's own.
  if ('id' in o && 'mac' in o && 'phone' in o && typeof o.mac === 'number') o.id = 'term-id'
  if ('termId' in o && 'url' in o) Object.assign(o, { termId: 'term-id', url: '/v1/terms/term-id/ws?ticket=ticket' })
  // An agent transcript's cursor is the daemon's own token (the file it read and how far): the phone only sends it back.
  if (typeof o.cursor === 'string' && o.cursor && 'events' in o) o.cursor = 'cursor'
  return o
}

const checked = new Set<string>()
/**
 * The fixture [name] (protocol/fixtures/<name>.json) is [value], normalised. With UPDATE_FIXTURES=1
 * it's written instead.
 */
function contract(name: string, value: unknown): void {
  checked.add(name)
  const got = normalise(value)
  const file = join(FIXTURES, `${name}.json`)
  if (UPDATE) {
    mkdirSync(FIXTURES, { recursive: true })
    writeFileSync(file, JSON.stringify(got, null, 2) + '\n')
    return
  }
  try {
    assert.ok(existsSync(file), `protocol/fixtures/${name}.json is missing: run UPDATE_FIXTURES=1 npm test`)
    assert.deepEqual(got, JSON.parse(readFileSync(file, 'utf8')), `protocol/fixtures/${name}.json no longer matches what the daemon sends (docs/PROTOCOL.md, "Contract fixtures")`)
  } catch (e) {
    mismatched = true
    throw e
  }
}
/** A fixture didn't match: the check for left-over fixtures would only repeat it. */
let mismatched = false

// ── The world the fixtures come from ─────────────────────────────────────
const git = (cwd: string, ...a: string[]) => execFileSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@t', '-c', 'init.defaultBranch=main', ...a], { cwd, stdio: 'ignore' })
const commit = (cwd: string, file: string, text: string) => {
  writeFileSync(join(cwd, file), text)
  git(cwd, 'add', file)
  git(cwd, 'commit', '-qm', file)
}
// A project with an upstream (one commit behind it, two of its own ahead), worked on in a linked
// worktree "parser-fix" with a changed file and a new one: What changed's git line, every field.
const PROJECTS = join(HOME, 'Projects')
const bare = join(scratch, 'up.git')
const repo = join(PROJECTS, 'app')
const cwd = join(PROJECTS, 'app-parser-fix')
git(scratch, 'init', '-q', '--bare', bare)
git(PROJECTS, 'clone', '-q', bare, repo)
commit(repo, 'parser.ts', 'export const parse = (s: string) => s\n')
git(repo, 'push', '-q', '-u', 'origin', 'main')
const other = join(scratch, 'other')
git(scratch, 'clone', '-q', bare, other)
commit(other, 'README.md', '# app\n')
git(other, 'push', '-q')
git(repo, 'fetch', '-q')
git(repo, 'worktree', 'add', '-q', '-b', 'parser-fix', cwd)
git(cwd, 'branch', '-q', '--set-upstream-to', 'origin/main')
commit(cwd, 'lexer.ts', 'export const lex = 1\n')
commit(cwd, 'tokens.ts', 'export const tokens = []\n')
writeFileSync(join(cwd, 'parser.ts'), 'export const parse = (s: string) => s.trim()\n')
writeFileSync(join(cwd, 'notes.md'), 'todo\n')
// The project's own rules (the session's Rules in effect), and the user's.
mkdirSync(join(cwd, '.claude'))
writeFileSync(join(cwd, '.claude', 'settings.json'), JSON.stringify({ permissions: { allow: ['Bash(npm test *)'], deny: ['Read(./.env)'], defaultMode: 'acceptEdits' } }))
writeFileSync(join(process.env.CLAUDE_CONFIG_DIR, 'settings.json'), JSON.stringify({ permissions: { ask: ['Bash(git push *)'], additionalDirectories: ['~/shared'] } }))

// The main session's transcript, as Claude Code writes one: a prompt, a command, thinking and a
// reply, an Edit with its patch, a Bash run, a to-do list, an agent and a workflow in the background.
const PLAIN = 'a0123456789abcdef'
const STARTING = 'a1111111111111111'
const RUN = 'wf_0a1b2c3d-4e5'
const T0 = Math.floor(Date.now() / 1000) * 1000 - 10 * 60_000
let tick = 0
const at = () => new Date(T0 + tick++ * 1000).toISOString()
const line = (o: object) => JSON.stringify(o) + '\n'
const assistant = (uuid: string, blocks: object[], extra: object = {}) => ({ type: 'assistant', uuid, timestamp: at(), sessionId: MAIN, message: { id: `msg-${uuid}`, role: 'assistant', model: 'claude-opus-5-5', content: blocks }, ...extra })
const toolUse = (id: string, name: string, input: object) => ({ type: 'tool_use', id, name, input })
const result = (uuid: string, toolUseId: string, text: string, toolUseResult?: object, extra: object = {}) => ({
  type: 'user', uuid, timestamp: at(), sessionId: MAIN,
  message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: toolUseId, content: [{ type: 'text', text }] }] },
  ...(toolUseResult ? { toolUseResult } : {}), ...extra,
})
const prompt = (uuid: string, text: string, extra: object = {}) => ({ type: 'user', uuid, timestamp: at(), sessionId: MAIN, message: { role: 'user', content: text }, ...extra })
const SCRIPT = `export const meta = {
  name: 'parser-survey',
  description: 'Survey the parser, then fix it',
  phases: [{ title: 'Survey', detail: 'Read the code' }, { title: 'Implement' }],
}
phase('Survey')`
const transcript = [
  prompt('u1', 'Fix the parser so it trims its input'),
  prompt('u2', '<command-name>/effort</command-name>\n<command-message>effort</command-message>\n<command-args>high</command-args>'),
  assistant('a1', [{ type: 'thinking', thinking: 'The parser should trim before it splits.', signature: 'x' }]),
  assistant('a2', [{ type: 'text', text: 'I will make the parser trim its input.' }]),
  assistant('a3', [toolUse('toolu_edit', 'Edit', { file_path: join(cwd, 'parser.ts'), old_string: '=> s\n', new_string: '=> s.trim()\n' })]),
  result('r3', 'toolu_edit', 'The file has been updated.', { filePath: join(cwd, 'parser.ts'), structuredPatch: [{ oldStart: 1, oldLines: 1, newStart: 1, newLines: 1, lines: ['-export const parse = (s: string) => s', '+export const parse = (s: string) => s.trim()'] }] }),
  assistant('a4', [toolUse('toolu_bash', 'Bash', { command: 'npm test', description: 'Run the tests' })]),
  result('r4', 'toolu_bash', 'ok 82 tests', { stdout: 'ok 82 tests', stderr: '', interrupted: false }),
  assistant('a5', [toolUse('toolu_todo', 'TodoWrite', { todos: [
    { content: 'Trim the input', activeForm: 'Trimming the input', status: 'completed' },
    { content: 'Run the tests', activeForm: 'Running the tests', status: 'in_progress' },
    { content: 'Update the docs', activeForm: 'Updating the docs', status: 'pending' },
  ] })]),
  result('r5', 'toolu_todo', 'Todos have been modified successfully.'),
  assistant('a6', [toolUse('toolu_agent', 'Agent', { description: 'Review the parser', prompt: 'Read parser.ts and review it.', subagent_type: 'Explore' })]),
  result('r6', 'toolu_agent', `Async agent launched successfully.\nagentId: ${PLAIN} (internal metadata, never quote)`, { isAsync: true, status: 'async_launched', agentId: PLAIN, description: 'Review the parser', resolvedModel: 'claude-sonnet-5-5', prompt: 'Read parser.ts…', outputFile: '/tmp/x.output', canReadOutputFile: true }),
  assistant('a7', [toolUse('toolu_wf', 'Workflow', { script: SCRIPT })]),
  result('r7', 'toolu_wf', 'Workflow launched in the background.', { status: 'async_launched', taskId: 'w1a2b3c4d', taskType: 'local_workflow', workflowName: 'parser-survey', runId: RUN, summary: 'Survey the parser, then fix it', transcriptDir: '/tmp/x', scriptPath: '/tmp/x.js' }),
]
// Two prompts typed while Claude works, read as they come: the first goes back to the input box (a
// notice with what didn't reach Claude), the second waits in the queue.
const queueOp = (operation: string, content: string) => ({ type: 'queue-operation', operation, timestamp: at(), sessionId: MAIN, content })
const typedLater = [queueOp('enqueue', 'Run the linter too'), queueOp('enqueue', 'Check the lexer as well'), queueOp('popAll', 'Run the linter too')]
// A session Claude Code's usage limit stopped: its API error line, a notice with the block it puts on.
const limitCwd = join(PROJECTS, 'app')
const limitPath = hub.transcriptPathFor(limitCwd, LIMITED)
mkdirSync(dirname(limitPath), { recursive: true })
writeFileSync(limitPath, [
  { type: 'user', uuid: 'l1', parentUuid: null, timestamp: at(), isSidechain: false, sessionId: LIMITED, message: { role: 'user', content: 'Port the plugin' } },
  {
    type: 'assistant', uuid: 'l2', parentUuid: 'l1', isSidechain: false, timestamp: at(), sessionId: LIMITED,
    error: 'rate_limit', apiError: 'usage_limit_reached', apiErrorStatus: 429, isApiErrorMessage: true,
    apiErrorParams: { rate_limit_info: { status: 'rejected', resetsAt: Math.floor(Date.now() / 1000) + 3600, rateLimitType: 'five_hour', overageStatus: 'rejected', overageDisabledReason: 'org_level_disabled', isUsingOverage: false } },
    message: { id: 'm-l2', model: '<synthetic>', role: 'assistant', type: 'message', stop_reason: 'stop_sequence', content: [{ type: 'text', text: "You've hit your session limit · resets 2am" }] },
  },
].map(line).join(''))
const transcriptPath = hub.transcriptPathFor(cwd, MAIN)
mkdirSync(dirname(transcriptPath), { recursive: true })
writeFileSync(transcriptPath, transcript.map(line).join(''))
// The agent's own transcript and meta, and a workflow agent in its run's folder.
const sub = join(dirname(transcriptPath), MAIN, 'subagents')
mkdirSync(sub, { recursive: true })
writeFileSync(join(sub, `agent-${PLAIN}.meta.json`), JSON.stringify({ agentType: 'Explore', description: 'Review the parser', toolUseId: 'toolu_agent', spawnDepth: 1, requestShape: 'background', requestNonInteractive: false }))
const side = { isSidechain: true, agentId: PLAIN }
writeFileSync(join(sub, `agent-${PLAIN}.jsonl`), [
  prompt('s1', 'Read parser.ts and review it.', { ...side, parentUuid: null }),
  assistant('s2', [{ type: 'text', text: 'Reading the parser.' }], side),
  { ...assistant('s3', [toolUse('tu-read', 'Read', { file_path: join(cwd, 'parser.ts') })], side), message: { id: 'msg-s3', role: 'assistant', model: 'claude-sonnet-5-5', content: [toolUse('tu-read', 'Read', { file_path: join(cwd, 'parser.ts') })], usage: { input_tokens: 12, cache_read_input_tokens: 20000, cache_creation_input_tokens: 0, output_tokens: 88 } } },
].map(line).join(''))
const runDir = join(sub, 'workflows', RUN)
const SURVEYOR = 'a2222222222222222'
mkdirSync(runDir, { recursive: true })
writeFileSync(join(runDir, `agent-${SURVEYOR}.meta.json`), JSON.stringify({ agentType: 'workflow-subagent', description: 'survey:parser', workflowPhase: 'Survey', spawnDepth: 1, requestShape: 'foreground', requestNonInteractive: true }))
writeFileSync(join(runDir, `agent-${SURVEYOR}.jsonl`), '')
writeFileSync(join(runDir, 'journal.jsonl'), [
  { type: 'launched' },
  { type: 'started', key: 'k1', agentId: SURVEYOR, label: 'survey:parser', phase: 'Survey' },
  { type: 'result', key: 'k1', agentId: SURVEYOR, result: { summary: 'The parser never trims', findings: [] } },
].map(line).join(''))

const screen = (path: string) => readFileSync(join(import.meta.dirname, 'fixtures', path), 'utf8').split('\n')

/** A `claude agents` job for session [id], registered as Claude Code registers one and listed by `claude agents`. */
const listed: { id: string; sessionId: string; cwd: string; state: string; name?: string }[] = []
function job(id: string, jobId: string, status = 'idle', o: { name?: string; version?: string; state?: string } = {}): number {
  const p = spawn('sleep', ['60'], { stdio: 'ignore' })
  procs.push(p)
  writeFileSync(join(registry, `${p.pid}.json`), JSON.stringify({ pid: p.pid, sessionId: id, cwd, kind: 'bg', jobId, status, updatedAt: Date.now(), ...(o.name ? { name: o.name } : {}), ...(o.version ? { version: o.version } : {}) }))
  listed.push({ id: jobId, sessionId: id, cwd, state: o.state ?? 'working', ...(o.name ? { name: o.name } : {}) })
  hub.applyAgentList([...listed])
  return p.pid!
}

// Claude Code's own suggestion for `npm test`: a prefix rule for the project's local settings.
const NPM = [{ type: 'addRules', rules: [{ toolName: 'Bash', ruleContent: 'npm test *' }], behavior: 'allow', destination: 'localSettings' }]
/** A PermissionRequest hook from session [sid], left waiting; the approval it put up. */
function ask(sid: string, tool: string, input: object, suggestions: unknown[] = NPM) {
  const res = { on: () => {}, writableEnded: false }
  void handleHook({ session_id: sid, cwd, hook_event_name: 'PermissionRequest', tool_name: tool, tool_input: input, permission_suggestions: suggestions }, {} as never, res as never)
  return hub.pendingApprovals().filter((p) => p.sessionId === sid).at(-1)!
}

// ── Snapshot ─────────────────────────────────────────────────────────────
let mainApproval: { id: string }
let usageHeard: unknown
test('GET /v1/snapshot: sessions of every kind, approvals of every kind, usage without a five-hour window, the app offer, Claude Code and Codex', async () => {
  // The main session: in a turn, its effort and model known, its transcript read (todo, agents).
  const r = hub.upsertSession(MAIN, { cwd, transcriptPath, origin: 'terminal', title: 'Fix the parser', state: 'working', model: 'claude-opus-5-5', effort: 'high', permissionMode: 'acceptEdits', contextPct: 42.5, costUsd: 1.25 })
  hub.ensureParsed(r)
  writeFileSync(transcriptPath, readFileSync(transcriptPath, 'utf8') + typedLater.map(line).join(''))
  hub.pollTranscripts()
  hub.upsertSession(MAIN, { todo: todoNow(r.parse!.events) })
  agents.pollAgents()
  agents.listAgents(MAIN)
  // An ask from its hook (Claude Code's suggestion for Always, why it asked).
  mainApproval = ask(MAIN, 'Bash', { command: 'npm test --prefix daemon', description: 'Run the daemon tests' })

  // Claude Code's own permission dialog on a terminal's screen (an ask rule over auto mode).
  hub.upsertSession('sess-dialog', { cwd, origin: 'terminal', title: 'Smoke-check the pages', state: 'needs_you', menu: menuOf(screen('permission-screens/attach-ask-rule.txt')) })
  // AskUserQuestion's form of two questions in the terminal: the menu's steps.
  hub.upsertSession('sess-form', { cwd, origin: 'terminal', title: 'Pick a version', state: 'needs_you', menu: menuOf(screen('question-screens/two-questions.txt')) })
  // Stopped by a usage limit (as the parser's blocker sets it; one read from a transcript, which
  // also puts the account on the limit, comes after the snapshot).
  hub.upsertSession('sess-limit', { cwd, origin: 'terminal', title: 'Port the plugin', state: 'idle' })
  hub.setBlocked('sess-limit', { kind: 'limit', text: 'You’ve hit your session limit · resets 2am', resetsAt: Date.now() + 3600_000, autoResumeAt: null })
  // A prompt held until Claude Code's input box is back, while a question waits (an automation's: it says no by itself).
  hub.upsertSession('sess-held', { cwd, origin: 'terminal', title: 'Answer the question', state: 'idle' })
  void hub.requestApproval({ sessionId: 'sess-held', kind: 'question', toolName: 'AskUserQuestion', summary: 'Which version?', input: {}, reason: null, canAlways: false, questions: [{ question: 'Which version comes first?', header: 'Version', multiSelect: false, options: [{ label: '1.0', description: 'The stable one' }, { label: '2.0', description: 'The new parser' }] }], plan: null }, 'toolu_q', undefined, 'deny')
  hold('sess-held', 'Also run the linter', 'client-held-1')
  // A plan to approve, which waits for the phone (no countdown).
  hub.upsertSession('sess-plan', { cwd, origin: 'hypernova', title: 'Plan the refactor', state: 'idle' })
  void hub.requestApproval({ sessionId: 'sess-plan', kind: 'plan', toolName: 'ExitPlanMode', summary: 'Plan: split the parser', input: {}, reason: null, canAlways: false, questions: null, plan: '1. Split the lexer out\n2. Trim the input' }, 'toolu_plan', undefined, 'never')

  // `claude agents` jobs: one on an older Claude Code, one that died.
  job('sess-job', 'old00001', 'busy', { version: '2.1.312', name: 'Old job' })
  hub.pollRegistry()
  const dead = { id: 'died0001', sessionId: 'sess-died', cwd, state: 'working', name: 'Crashy' }
  listed.push(dead)
  hub.applyAgentList([...listed])
  const jobDir = join(process.env.CLAUDE_CONFIG_DIR!, 'jobs', 'died0001')
  mkdirSync(jobDir, { recursive: true })
  writeFileSync(join(jobDir, 'state.json'), JSON.stringify({ state: 'failed', detail: 'exit 1 — Error: the API refused the request', tempo: 'idle', updatedAt: new Date().toISOString() }))
  dead.state = 'failed'
  hub.applyAgentList([...listed])

  // Usage from the status line with the week only (no five-hour window), and a model's own week.
  hub.offerUsage(null, { pct: 62, resetsAt: Date.now() + 3 * 86400_000 }, Date.now(), false, true)
  hub.offerModelUsage([{ label: 'Sonnet', pct: 12, resetsAt: Date.now() + 3 * 86400_000 }])
  usageHeard = heard('usage')

  const snap = await ok(get('/v1/snapshot'))
  // The order of sessions and projects is the app's own to choose (it sorts them); ties in time aren't.
  snap.sessions.sort((a: { id: string }, b: { id: string }) => a.id.localeCompare(b.id))
  snap.projects.sort((a: { path: string }, b: { path: string }) => a.path.localeCompare(b.path))
  contract('snapshot', snap)
})

// ── Live messages ────────────────────────────────────────────────────────
test('{t:"session"}, {t:"session.removed"} and {t:"job.died"}', () => {
  contract('ws-session', heard('session', (m) => m.session.id === 'sess-died'))
  contract('ws-job-died', heard('job.died'))
  hub.upsertSession('sess-gone', { cwd, origin: 'terminal' })
  hub.forgetSession('sess-gone')
  contract('ws-session-removed', heard('session.removed'))
})

test('{t:"event"} of each kind the transcript brings, a notice, and {t:"event.removed"}', () => {
  contract('ws-event-approval', heard('event', (m) => m.sessionId === MAIN && m.event.kind === 'approval'))
  hub.notice(MAIN, 'Claude Code restarted', 'warn')
  const n = heard('event', (m) => m.event.kind === 'notice')
  contract('ws-event-notice', n)
  hub.dropEvent(MAIN, n.event.id)
  contract('ws-event-removed', heard('event.removed'))
})

test('{t:"approval"} and {t:"approval.resolved"}', async () => {
  contract('ws-approval', heard('approval', (m) => m.approval.id === mainApproval.id))
  await ok(post(`/v1/approvals/${mainApproval.id}`, { decision: 'allow' }))
  contract('ws-approval-resolved', heard('approval.resolved', (m) => m.id === mainApproval.id))
})

test('{t:"usage"}, {t:"agent"} and {t:"workflow"}', () => {
  contract('ws-usage', usageHeard)
  contract('ws-agent', heard('agent', (m) => m.agent.id === PLAIN))
  contract('ws-workflow', heard('workflow', (m) => m.workflow.id === RUN))
})

test('a usage limit in a transcript: its notice (GET …/events), the session it blocks, the account on the limit', async () => {
  const lim = hub.upsertSession(LIMITED, { cwd: limitCwd, transcriptPath: limitPath, origin: 'terminal', state: 'idle' })
  hub.ensureParsed(lim)
  hub.pollTranscripts()
  contract('rest-session-events-limit', await ok(get(`/v1/sessions/${LIMITED}/events?limit=300&output=4000`)))
  contract('ws-session-limited', heard('session', (m) => m.session.id === LIMITED))
  // The meter says the five-hour window is used up.
  contract('ws-usage-limited', heard('usage'))
})

test('{t:"projects"}, {t:"providers"}, {t:"account"}, {t:"codex"}', () => {
  createProject('demo', false)
  contract('ws-projects', heard('projects'))
  // As every place that sends them builds them (account.ts, balance.ts, http.ts).
  contract('ws-providers', { t: 'providers', providers: store.listProviders() })
  contract('ws-account', { t: 'account', account: getAccount() })
  sendCodex()
  contract('ws-codex', heard('codex'))
})

test('{t:"claude"} and POST /v1/claude', async () => {
  contract('rest-claude', await ok(post('/v1/claude', { agents: true })))
  contract('ws-claude', heard('claude'))
})

test('{t:"files"} and {t:"lan"}', async () => {
  await ok(post('/v1/mac/files', { browseOutsideProjects: true }))
  contract('ws-files', heard('files'))
  await ok(post('/v1/mac/files', { browseOutsideProjects: false }))
  setLanAccess(true)
  for (let i = 0; i < 100 && !heard('lan', (m) => m.lan); i++) await new Promise((r) => setTimeout(r, 50))
  contract('ws-lan', heard('lan', (m) => m.lan))
  setLanAccess(false)
})

test('{t:"update"} and POST /v1/mac/update: the server updating itself', async () => {
  contract('rest-update', await ok(post('/v1/mac/update', { autoUpdate: true })))
  contract('ws-update', heard('update', (m) => m.update.auto === true))
  await ok(post('/v1/mac/update', { autoUpdate: false }))
})

test('{t:"app"}: a newer app to hand out', () => {
  writeFileSync(join(apkDir, 'hypernova.json'), JSON.stringify({ versionCode: 4300, versionName: '0.43.0' }))
  announceApp()
  contract('ws-app', heard('app'))
})

// ── Routes ───────────────────────────────────────────────────────────────
test('GET …/events: the transcript as the chat draws it', async () => {
  contract('rest-session-events', await ok(get(`/v1/sessions/${MAIN}/events?limit=300&output=4000`)))
})

test('GET …/agents and an agent’s events, and one just started (starting)', async () => {
  contract('rest-agents', await ok(get(`/v1/sessions/${MAIN}/agents`)))
  contract('rest-agent-events', await ok(get(`/v1/sessions/${MAIN}/agents/${PLAIN}/events?limit=300`)))
  // An agent launched a moment ago, its file not written yet.
  const now = new Date().toISOString()
  const launch = [
    { ...assistant('a8', [toolUse('toolu_agent2', 'Agent', { description: 'Write the docs', prompt: 'Document the parser.', subagent_type: 'general-purpose' })]), timestamp: now },
    { ...result('r8', 'toolu_agent2', `Async agent launched successfully.\nagentId: ${STARTING} (internal metadata, never quote)`, { isAsync: true, status: 'async_launched', agentId: STARTING, description: 'Write the docs' }), timestamp: now },
  ]
  writeFileSync(transcriptPath, readFileSync(transcriptPath, 'utf8') + launch.map(line).join(''))
  hub.pollTranscripts()
  agents.listAgents(MAIN)
  contract('rest-agent-events-starting', await ok(get(`/v1/sessions/${MAIN}/agents/${STARTING}/events?limit=300`)))
})

test('GET …/changes (with the git line) and …/changes/diff', async () => {
  contract('rest-changes', await ok(get(`/v1/sessions/${MAIN}/changes`)))
  contract('rest-changes-diff', await ok(get(`/v1/sessions/${MAIN}/changes/diff?path=parser.ts`)))
})

test('GET …/permissions: the rules in effect', async () => {
  contract('rest-permissions', await ok(get(`/v1/sessions/${MAIN}/permissions`)))
})

test('GET /v1/history/search', async () => {
  contract('rest-history-search', await ok(get('/v1/history/search?q=parser%20trims')))
})

test('POST …/prompt with hold while a question waits: held, and the session with it', async () => {
  contract('rest-prompt-held', await ok(post('/v1/sessions/sess-held/prompt', { text: 'Then commit it', clientId: 'client-held-2', hold: true })))
})

test('POST …/allow: several asks allowed at once', async () => {
  hub.upsertSession('sess-allow', { cwd, origin: 'terminal', title: 'Run the checks', state: 'working' })
  const a = ask('sess-allow', 'Bash', { command: 'npm test' })
  const b = ask('sess-allow', 'Bash', { command: 'npm run lint' })
  contract('rest-allow', await ok(post('/v1/sessions/sess-allow/allow', { ids: [a.id, b.id] })))
})

test('POST …/effort on a claude agents job', async () => {
  job('sess-effort', 'eff00001', 'idle', { name: 'Mine' })
  hub.pollRegistry()
  contract('rest-session-effort', await ok(post('/v1/sessions/sess-effort/effort', { effort: 'max' })))
})

test('GET /v1/models: each provider’s models for the pickers', async () => {
  contract('rest-models', await ok(get('/v1/models')))
})

test('POST …/model on a claude agents job', async () => {
  job('sess-model', 'eff00002', 'idle', { name: 'Mine' })
  hub.pollRegistry()
  contract('rest-session-model', await ok(post('/v1/sessions/sess-model/model', { model: 'sonnet' })))
})

test('POST …/mode on a claude agents job', async () => {
  job('sess-mode', 'mode0001', 'idle', { name: 'Mine' })
  hub.pollRegistry()
  contract('rest-session-mode', await ok(post('/v1/sessions/sess-mode/mode', { permissionMode: 'plan' })))
})

test('POST …/rename on a claude agents job', async () => {
  job('sess-rename', 'eff00003', 'idle', { name: 'Mine' })
  hub.pollRegistry()
  contract('rest-session-rename', await ok(post('/v1/sessions/sess-rename/rename', { name: 'Parser fix' })))
})

test('POST and GET …/btw: a side question, asked, then answered', async () => {
  job('sess-btw', 'btw00001', 'busy', { name: 'Mine' })
  hub.pollRegistry()
  contract('rest-btw-ask', await ok(post('/v1/sessions/sess-btw/btw', { question: 'Why does the parser wrap around?' })))
  let a: any = null
  for (const t0 = Date.now(); Date.now() - t0 < 6000; await new Promise((r) => setTimeout(r, 150))) {
    a = await ok(get('/v1/sessions/sess-btw/btw'))
    if (a.aside?.state !== 'asking') break
  }
  contract('rest-btw', a)
})

test('POST …/stop, …/respawn and /v1/jobs/respawn', async () => {
  job('sess-stop', 'stop0001', 'busy', { name: 'Mine' })
  job('sess-respawn', 'resp0001', 'idle', { name: 'Mine', version: '2.1.312' })
  job('sess-all', 'all00001', 'idle', { name: 'Mine', version: '2.1.300' })
  hub.pollRegistry()
  contract('rest-session-stop', await ok(post('/v1/sessions/sess-stop/stop')))
  contract('rest-session-respawn', await ok(post('/v1/sessions/sess-respawn/respawn')))
  contract('rest-jobs-respawn', await ok(post('/v1/jobs/respawn', { ids: ['sess-all'] })))
})

test('POST /v1/pair', async () => {
  const { code } = await startPairing(true)
  contract('rest-pair', await ok(call('POST', '/v1/pair', { code, deviceName: 'Pixel', model: 'Google Pixel 9', appVersion: '0.42.0', installId: 'install-0123456789abcdef' }, null)))
})

// ── Settings, the account and Statistics ─────────────────────────────────
/** [v] with each of [ids] (a provider's random id, say) written as its name, so both suites can name it. */
function named(v: unknown, ids: Record<string, string>): any {
  let text = JSON.stringify(v)
  for (const [id, name] of Object.entries(ids)) text = text.split(id).join(name)
  return JSON.parse(text)
}
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
async function until(what: string, f: () => boolean, ms = 15_000): Promise<void> {
  for (const t0 = Date.now(); !f(); await sleep(50)) if (Date.now() - t0 > ms) throw new Error(`timed out waiting for ${what}`)
}

test('the providers: saved and checked, checked without saving, a balance, an edit that drops the key, the list, deleted, a refusal', async () => {
  const { useCheckFetch } = await import('../src/providers-check.ts')
  const balance = await import('../src/balance.ts')
  // The providers' own servers, answered here: nothing leaves this machine.
  const answer = (status: number, body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } }))
  useCheckFetch((url) => (url.pathname.endsWith('/v1/models') ? answer(200, { data: [{ id: 'gateway-large' }, { id: 'gateway-small' }] }) : answer(404, {})))
  balance.useFetch(() => answer(200, { data: { label: 'never kept', limit: 10, limit_remaining: 4.2, usage_daily: 0.31, free_model_daily_requests: { used: 13, limit: 50, remaining: 37 } } }))
  try {
    const gw = await ok(post('/v1/providers', { name: 'Gateway', kind: 'custom', baseUrl: 'https://gateway.example.com/v1', apiKey: 'gw-key-0123', models: { default: 'gateway-large', haiku: 'gateway-small' } }))
    const or = await ok(post('/v1/providers', { name: 'OpenRouter', kind: 'openrouter', apiKey: 'sk-or-0123', models: { default: 'anthropic/claude-sonnet-5' } }))
    const ids = { [gw.provider.id]: 'prov-gateway', [or.provider.id]: 'prov-openrouter' }
    contract('rest-provider-save', named(gw, ids))
    contract('rest-provider-save-balance', named(or, ids))
    contract('rest-provider-check', await ok(post('/v1/providers/check', { kind: 'custom', baseUrl: 'https://gateway.example.com', apiKey: 'gw-key-0123' })))
    contract('rest-provider-balance', named(await ok(post(`/v1/providers/${or.provider.id}/check`)), ids))
    // Moved to another host without a new key: the key stays behind.
    contract('rest-provider-edit', named(await ok(call('PUT', `/v1/providers/${gw.provider.id}`, { name: 'Gateway', kind: 'custom', baseUrl: 'https://other.example.com', models: { default: 'gateway-large' } })), ids))
    contract('rest-providers', named(await ok(get('/v1/providers')), ids))
    contract('rest-provider-delete', await ok(call('DELETE', `/v1/providers/${gw.provider.id}`)))
    await ok(call('DELETE', `/v1/providers/${or.provider.id}`))
    // A refusal names its field's code (the editor puts it under that field).
    const refused = await post('/v1/providers', { name: 'Plain', kind: 'custom', baseUrl: 'http://gateway.example.com', apiKey: 'k' })
    assert.equal(refused.status, 400)
    contract('rest-provider-refused', refused.json)
  } finally {
    useCheckFetch((u, i) => fetch(u, i))
    balance.useFetch((u, i) => fetch(u, i))
  }
})

// Signed out from here on (fake-claude.sh has no sign-in yet), so the usage refresh below needs no
// look at /usage (there's nothing to read while signed out) and answers with the usage at once.
test('POST /v1/account/check, then GET /v1/usage and POST /v1/usage/refresh', async () => {
  const checked = await ok(post('/v1/account/check'))
  assert.equal(checked.account.state, 'logged_out')
  contract('rest-account-check', checked)
  contract('rest-usage', await ok(get('/v1/usage')))
  contract('rest-usage-refresh', await ok(post('/v1/usage/refresh')))
})

test('signing in from the phone: started, its page, cancelled, started again, its code, then a code with none running', async () => {
  const waiting = (not?: string) => heard('account', (m) => m.account.login?.state === 'waiting_code' && !!m.account.login.url && m.account.login.url !== not)
  contract('rest-account-login', await ok(post('/v1/account/login', { where: 'phone' })))
  await until('the sign-in page', () => !!waiting())
  const first = waiting()
  contract('ws-account-sign-in', first)
  contract('rest-account-login-cancel', await ok(call('DELETE', '/v1/account/login')))
  await ok(post('/v1/account/login', { where: 'phone' }))
  await until('a new sign-in page', () => !!waiting(first.account.login.url))
  const state = new URL(waiting(first.account.login.url).account.login.url).searchParams.get('state')
  contract('rest-account-login-code', await ok(post('/v1/account/login/code', { code: `AuthCode0123456789#${state}` })))
  // Signed in: the sign-in is over and the plan known (the limit the account was on before comes back with it).
  await until('signed in', () => getAccount().login === null && getAccount().state !== 'logged_out' && getAccount().plan === 'max')
  // A code with no sign-in running.
  const noLogin = await post('/v1/account/login/code', { code: 'AuthCode#state' })
  assert.equal(noLogin.status, 409)
  contract('rest-account-login-code-refused', noLogin.json)
})

test('GET /v1/stats: a week of the transcripts above', async () => {
  // Days are the server's own: a zone where it's mid-day keeps the last ten minutes' transcripts in one day.
  const tz = process.env.TZ
  const h = new Date().getUTCHours()
  process.env.TZ = h >= 1 && h <= 22 ? 'UTC' : 'Etc/GMT-12'
  try {
    const s = await ok(get('/v1/stats?range=week'))
    // The days as dates counted back from the normalised day (2027-01-15), so a fixture keeps its shape whatever today is.
    const today = s.days.at(-1).day as string
    const back = (d: string) => Math.round((Date.parse(today) - Date.parse(d)) / 86_400_000)
    const date = (d: string) => new Date(Date.UTC(2027, 0, 15 - back(d))).toISOString().slice(0, 10)
    for (const d of s.days) d.day = date(d.day)
    if (s.busiestDay) s.busiestDay.day = date(s.busiestDay.day)
    for (const k of ['from', 'to', 'computedAt', 'countingSince']) if (typeof s[k] === 'number') s[k] = T
    // How long Claude worked runs up to now (a turn still open): any time worked is ten minutes here.
    const minutes = (x: any): void => {
      if (Array.isArray(x)) return x.forEach(minutes)
      if (!x || typeof x !== 'object') return
      for (const [k, v] of Object.entries(x)) {
        if ((k === 'ms' || k.endsWith('Ms')) && typeof v === 'number' && v > 0) x[k] = 600_000
        else minutes(v)
      }
    }
    minutes(s)
    // Tools with the same count come in the order the files were read: by name here.
    s.tools.sort((a: any, b: any) => b.count - a.count || a.name.localeCompare(b.name))
    contract('rest-stats', s)
    const bad = await get('/v1/stats?range=year')
    assert.equal(bad.status, 400)
    contract('rest-stats-bad-range', bad.json)
  } finally {
    if (tz === undefined) delete process.env.TZ
    else process.env.TZ = tz
  }
})

test('GET /v1/qa: the testing checklist’s page', async () => {
  const r = await ok(get('/v1/qa'))
  assert.match(r.url, /^http:\/\/127\.0\.0\.1:\d+\/qa\?k=[0-9a-f]{32}$/)
  contract('rest-qa', { url: r.url.replace(/:\d+\//, ':47321/').replace(/k=[0-9a-f]{32}/, 'k=qa-key') })
})

test('DELETE /v1/devices/me: the server forgets the phone that asks', async () => {
  const token = 'contract-test-second-phone-0123456789abcdef'
  addDevice({ id: 'phone-2', name: 'Pixel 2', model: 'Pixel 8', createdAt: Date.now(), lastSeenAt: Date.now() }, token)
  assert.equal((await call('GET', '/v1/usage', undefined, token)).status, 200)
  contract('rest-devices-me', (await call('DELETE', '/v1/devices/me', undefined, token)).json)
  assert.equal((await call('GET', '/v1/usage', undefined, token)).status, 401)
  // The other phone stays paired.
  assert.equal((await get('/v1/usage')).status, 200)
})

test('every fixture is one this file checks (none left over from a message that went)', (t) => {
  if (mismatched) return t.skip('a fixture above did not match')
  if (UPDATE) {
    for (const f of readdirSync(FIXTURES)) if (f.endsWith('.json') && !checked.has(f.slice(0, -5))) rmSync(join(FIXTURES, f))
    return
  }
  const files = readdirSync(FIXTURES).filter((f) => f.endsWith('.json')).map((f) => f.slice(0, -5)).sort()
  assert.deepEqual(files, [...checked].sort())
})
