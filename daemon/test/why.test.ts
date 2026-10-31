// Why it asked (why.ts; IDEAS PA7; docs/PROTOCOL.md, "Approvals", "Why it asked"): each `why` kind
// from what Claude Code itself says (its terminal dialog, the SDK's canUseTool options, a hook's
// suggestions, in the shapes Claude Code 2.1.316 sends them), then Hypernova's own reading of the
// rules in effect, from rules written here and from fixture settings files in a scratch project,
// Claude folder and managed folder. No real settings file is read.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import type { RuleFile, RuleSource } from '../src/permission-rules.ts'

const scratch = mkdtempSync(join(tmpdir(), 'hn-why-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
process.env.HYPERNOVA_API_PORT = '0'
process.env.HYPERNOVA_LOCAL_PORT = '0'
process.env.HYPERNOVA_LAN_PORT = '0'
for (const d of [process.env.HOME, process.env.ZDOTDIR, process.env.CLAUDE_CONFIG_DIR]) mkdirSync(d, { recursive: true })

const why = await import('../src/why.ts')
const rules = await import('../src/permission-rules.ts')
const { menuOf } = await import('../src/term/shared.ts')
const hub = await import('../src/hub.ts')
const { handleHook } = await import('../src/hooks.ts')
const { addDevice } = await import('../src/store.ts')
rules.useManagedDir(join(scratch, 'managed'))
addDevice({ id: 'phone', name: 'Phone', model: 'Pixel', createdAt: Date.now(), lastSeenAt: Date.now() }, 'why-test-device-token-0123456789abcdef')

after(() => rmSync(scratch, { recursive: true, force: true }))

const fixtures = join(import.meta.dirname, 'fixtures')
const screen = (path: string) => readFileSync(join(fixtures, path), 'utf8').split('\n')

// ── What Claude Code's dialog says ───────────────────────────────────────
test('the owner’s Arc dialog: the ask rule over auto mode, Claude Code’s words, the rule alone', () => {
  const m = menuOf(screen('permission-screens/attach-ask-rule.txt'))!
  assert.deepEqual(m.permission!.why, { kind: 'ask_rule', text: 'Ask rule Bash(python:*) overrides auto mode for this command.', rule: 'Bash(python:*)', source: 'claude' })
})

test('the same dialog with each of Claude Code’s other reasons (its wording in 2.1.316)', () => {
  const lines = screen('permission-screens/attach-ask-rule.txt')
  const swap = (reason: string, config: string | null) =>
    menuOf(lines.flatMap((l) => (l.includes('Ask rule') ? [` ${reason}`] : l.includes('/permissions to let') ? (config ? [` ${config}`] : []) : [l])))!.permission!.why
  assert.deepEqual(swap('Permission rule Bash(rm:*) requires confirmation for this command.', '/permissions to update rules'), {
    kind: 'ask_rule', text: 'Permission rule Bash(rm:*) requires confirmation for this command.', rule: 'Bash(rm:*)', source: 'claude',
  })
  assert.deepEqual(swap('Hook guard.sh requires confirmation for this command: touches prod [user settings]', '/hooks to update hooks'), {
    kind: 'hook', text: 'Hook guard.sh requires confirmation for this command: touches prod [user settings]', source: 'claude',
  })
  assert.deepEqual(swap('Path is outside allowed working directories', '/permissions to update rules'), { kind: 'outside_folder', text: 'Path is outside allowed working directories', source: 'claude' })
  assert.equal(swap('/etc/hosts, which is outside the allowed working directories', null)!.kind, 'outside_folder')
  assert.deepEqual(swap("Path '~/.ssh/x' resolves near a sensitive file under quote-stripping and cannot be statically validated; requires manual approval", null), {
    kind: 'safety', text: "Path '~/.ssh/x' resolves near a sensitive file under quote-stripping and cannot be statically validated; requires manual approval", source: 'claude',
  })
  // A reason of a kind not known here, said with a "/permissions to …" line under it.
  assert.deepEqual(swap('Something new asks for this.', '/permissions to update rules'), { kind: 'other', text: 'Something new asks for this.', source: 'claude' })
  // No reason printed: none from the dialog (its description isn't one).
  assert.equal(swap('', null), null)
})

test('the subagent’s dialog prints no reason', () => {
  assert.equal(menuOf(screen('permission-screens/subagent-ask.txt'))!.permission!.why, null)
})

// ── What the SDK says ────────────────────────────────────────────────────
test('the SDK’s options: the ask rule that forced it first, then the decision reason’s type', () => {
  assert.deepEqual(why.whyFromSdk({ decisionReason: 'x', decisionReasonType: 'rule', matchedAskRule: { source: 'localSettings', toolName: 'Bash', ruleContent: 'python:*' } }), {
    kind: 'ask_rule', text: 'Ask rule Bash(python:*) in the project’s local settings asks for this call.', rule: 'Bash(python:*)', source: 'claude',
  })
  assert.deepEqual(why.whyFromSdk({ decisionReasonType: 'workingDir', decisionReason: 'Path is outside allowed working directories', blockedPath: '/etc' }), {
    kind: 'outside_folder', text: 'Path is outside allowed working directories', source: 'claude',
  })
  // A write outside the folders comes as a safety check; its words say outside.
  assert.equal(why.whyFromSdk({ decisionReasonType: 'safetyCheck', decisionReason: '/etc/hosts, which is outside the allowed working directories', blockedPath: '/etc/hosts' })!.kind, 'outside_folder')
  assert.equal(why.whyFromSdk({ blockedPath: '/etc/hosts' })!.text, '/etc/hosts is outside the project’s folders.')
  assert.deepEqual(why.whyFromSdk({ decisionReasonType: 'safetyCheck', decisionReason: 'Dangerous rm operation on /' }), { kind: 'safety', text: 'Dangerous rm operation on /', source: 'claude' })
  assert.equal(why.whyFromSdk({ decisionReasonType: 'classifier' })!.kind, 'classifier')
  assert.equal(why.whyFromSdk({ decisionReasonType: 'mode' })!.kind, 'mode')
  assert.equal(why.whyFromSdk({ decisionReasonType: 'hook', decisionReason: 'Hook guard.sh asked' })!.kind, 'hook')
  assert.deepEqual(why.whyFromSdk({ decisionReason: 'Something else' }), { kind: 'other', text: 'Something else', source: 'claude' })
  assert.equal(why.whyFromSdk({}), null)
  assert.equal(why.whyFromSdk(undefined), null)
})

test('a hook’s suggestion to add a folder: outside the project’s folders, as Claude Code offers it', () => {
  assert.deepEqual(why.whyFromSuggestions([{ type: 'addDirectories', directories: ['/tmp/hn-tests'], destination: 'session' }]), {
    kind: 'outside_folder', text: 'It reaches /tmp/hn-tests, outside the project’s folders: Claude Code offers to add it.', source: 'claude',
  })
  assert.equal(why.whyFromSuggestions([{ type: 'addRules', rules: [{ toolName: 'Bash', ruleContent: 'ls *' }], behavior: 'allow', destination: 'localSettings' }]), null)
  assert.equal(why.whyFromSuggestions(undefined), null)
})

// ── Hypernova's reading of the rules ─────────────────────────────────────
const CWD = '/w/arc'
const HOME = '/h'
const file = (source: RuleSource, list: Partial<Pick<RuleFile, 'allow' | 'ask' | 'deny' | 'additionalDirectories'>>): RuleFile => ({
  source, path: source === 'session' ? null : `/x/${source}.json`, state: 'ok', allow: [], ask: [], deny: [], defaultMode: null, additionalDirectories: [], clipped: false, ...list,
})
const read = (tool: string, input: Record<string, unknown>, sources: RuleFile[], mode: string | null = 'default', managedOnly = false) =>
  why.whyFromRules(tool, input, mode, CWD, { sources, managedOnly }, HOME)
const ARC = '/media/cubxi/docker/projects/arc/.venv/bin/python /media/cubxi/docker/projects/arc/scratch/deploy/check.py http://192.168.1.76:8108 /healthz'

test('an ask rule that matches: the rule, where it is written, Hypernova’s reading', () => {
  // The owner's: Bash(python:*) over a python run by its path.
  assert.deepEqual(read('Bash', { command: ARC }, [file('local', {}), file('user', { ask: ['Bash(python:*)'], allow: ['Bash'] })], 'auto'), {
    kind: 'ask_rule', text: 'Ask rule Bash(python:*) in your user settings matches this command.', rule: 'Bash(python:*)', source: 'hypernova',
  })
  // The highest source that has one wins; a part of a compound command is enough for an ask.
  const w = read('Bash', { command: 'cd x && git push origin main' }, [file('project', { ask: ['Bash(git push *)'] }), file('user', { ask: ['Bash(git:*)'] })])
  assert.equal(w.rule, 'Bash(git push *)')
  assert.match(w.text, /the project’s settings/)
  // VAR=value in front doesn't hide the program.
  assert.equal(read('Bash', { command: 'FOO=1 npm publish' }, [file('user', { ask: ['Bash(npm publish)'] })]).kind, 'ask_rule')
  // Not a word prefix: python3 is not python:*.
  assert.equal(read('Bash', { command: 'python3 x.py' }, [file('user', { ask: ['Bash(python:*)'] })]).kind, 'no_rule')
})

test('no allow rule matches: said per mode; every part of a command needs one', () => {
  assert.deepEqual(read('Bash', { command: 'make deploy' }, [file('user', { allow: ['Bash(npm test *)'] })]), { kind: 'no_rule', text: 'No allow rule matches this command.', source: 'hypernova' })
  assert.deepEqual(read('Bash', { command: 'npm test && make deploy' }, [file('user', { allow: ['Bash(npm test *)'] })]), {
    kind: 'no_rule', text: 'No allow rule matches every part of this command.', source: 'hypernova',
  })
  assert.deepEqual(read('Bash', { command: 'make deploy' }, [], 'plan'), { kind: 'mode', text: 'Plan mode, and no allow rule matches this command.', source: 'hypernova' })
  assert.equal(read('Bash', { command: 'make deploy' }, [], 'auto').kind, 'other')
  assert.equal(read('Write', { file_path: `${CWD}/a.txt`, content: 'x' }, []).text, 'No allow rule matches this edit.')
})

test('an allow rule that matches anyway: one of Claude Code’s own checks, most likely', () => {
  // `npm test *` covers `npm test` itself; each part allowed.
  assert.deepEqual(read('Bash', { command: 'npm test' }, [file('local', { allow: ['Bash(npm test *)'] })]), {
    kind: 'safety', text: 'Allow rule Bash(npm test *) matches, yet Claude Code asked: one of its own checks, most likely.', rule: 'Bash(npm test *)', source: 'hypernova',
  })
  assert.equal(read('Bash', { command: 'git status | head -3' }, [file('user', { allow: ['Bash(git status:*)', 'Bash(head:*)'] })]).kind, 'safety')
  assert.equal(read('Edit', { file_path: `${CWD}/.claude/settings.json` }, [file('user', { allow: ['Edit'] })]).kind, 'safety')
})

test('a file outside the project’s folders; its extra folders and ~ count as inside', () => {
  assert.deepEqual(read('Edit', { file_path: '/etc/hosts' }, []), { kind: 'outside_folder', text: '/etc/hosts is outside the project’s folders.', source: 'hypernova' })
  assert.equal(read('Read', { file_path: `${CWD}/../other/x` }, []).text, '/w/other/x is outside the project’s folders.')
  assert.equal(read('Read', { file_path: '/data/x.csv' }, [file('project', { additionalDirectories: ['/data'] })]).kind, 'no_rule')
  assert.equal(read('Read', { file_path: '~/notes/x.md' }, [file('user', { additionalDirectories: ['~/notes'] })]).kind, 'no_rule')
  // An ask rule still comes first.
  assert.equal(read('Read', { file_path: '/etc/hosts' }, [file('user', { ask: ['Read(//etc/**)'] })]).kind, 'ask_rule')
})

test('path, web and MCP rules as Claude Code writes them', () => {
  // Edit rules cover every edit tool; Read rules Grep and Glob; a bare path from the project's folder.
  assert.equal(read('Write', { file_path: `${CWD}/src/a.ts` }, [file('project', { ask: ['Edit(src/**)'] })]).kind, 'ask_rule')
  assert.equal(read('Write', { file_path: `${CWD}/lib/a.ts` }, [file('project', { ask: ['Edit(src/**)'] })]).kind, 'no_rule')
  assert.equal(read('Grep', { pattern: 'x', path: `${CWD}/secrets` }, [file('project', { ask: ['Read(./secrets)'] })]).kind, 'ask_rule')
  assert.equal(read('Edit', { file_path: `${HOME}/.bashrc` }, [file('user', { ask: ['Edit(~/.bashrc)'], additionalDirectories: [HOME] })]).kind, 'ask_rule')
  assert.equal(read('WebFetch', { url: 'https://docs.example.com/a' }, [file('user', { ask: ['WebFetch(domain:docs.example.com)'] })]).kind, 'ask_rule')
  assert.equal(read('WebFetch', { url: 'https://example.org/' }, [file('user', { ask: ['WebFetch(domain:docs.example.com)'] })]).kind, 'no_rule')
  assert.equal(read('mcp__github__create_issue', {}, [file('user', { ask: ['mcp__github'] })]).kind, 'ask_rule')
  assert.equal(read('mcp__github__create_issue', {}, [file('user', { ask: ['mcp__gitlab'] })]).kind, 'no_rule')
})

test('managed rules only: the other sources are set aside; a source that isn’t ok has none', () => {
  const sources = [file('managed', { allow: ['Bash(ls *)'] }), file('user', { ask: ['Bash(ls *)'] })]
  assert.equal(read('Bash', { command: 'ls -la' }, sources, 'default', true).kind, 'safety')
  assert.equal(read('Bash', { command: 'ls -la' }, sources, 'default', false).kind, 'ask_rule')
  assert.equal(read('Bash', { command: 'ls -la' }, [{ ...file('user', { ask: ['Bash(ls *)'] }), state: 'invalid' }]).kind, 'no_rule')
})

// ── A hook's ask, end to end, from fixture settings files ────────────────
/** A PermissionRequest from session [sid] in its scratch project; the approval the phone gets. */
function ask(sid: string, tool: string, input: object, suggestions: unknown[] = [], mode = 'default') {
  const res = { on: () => {}, writableEnded: false }
  const answer = handleHook({ session_id: sid, cwd: join(scratch, 'projects', sid), hook_event_name: 'PermissionRequest', permission_mode: mode, tool_name: tool, tool_input: input, permission_suggestions: suggestions }, {} as never, res as never)
  const a = hub.pendingApprovals().filter((p) => p.sessionId === sid).at(-1)!
  return { answer, a }
}
async function done(x: { answer: Promise<object>; a: { id: string } }) {
  hub.answerApproval(x.a.id, { decision: 'allow' }, 'phone')
  await x.answer
}
function project(sid: string, local: object | null, user: object | null = null) {
  const d = join(scratch, 'projects', sid, '.claude')
  mkdirSync(d, { recursive: true })
  if (local) writeFileSync(join(d, 'settings.local.json'), JSON.stringify(local))
  // Secrets beside the rules never come out (only a rule string does).
  writeFileSync(join(process.env.CLAUDE_CONFIG_DIR!, 'settings.json'), JSON.stringify({ env: { API_TOKEN: 'sk-secret-123' }, ...(user ?? {}) }))
}

test('a hook’s ask carries why: the ask rule in the project’s local file, Hypernova’s reading', async () => {
  project('why-ask', { permissions: { ask: ['Bash(python:*)'] } })
  const x = ask('why-ask', 'Bash', { command: ARC.replace('/media/cubxi/docker/projects/arc', join(scratch, 'projects', 'why-ask')) }, [], 'auto')
  assert.deepEqual(x.a.why, { kind: 'ask_rule', text: 'Ask rule Bash(python:*) in the project’s local settings matches this command.', rule: 'Bash(python:*)', source: 'hypernova' })
  assert.doesNotMatch(JSON.stringify(x.a), /sk-secret/)
  await done(x)
})

test('a hook’s ask: Claude Code’s folder suggestion first; no allow rule; none for a question', async () => {
  project('why-dir', null, { permissions: { allow: ['Bash(ls *)'] } })
  const dir = ask('why-dir', 'Bash', { command: 'touch /tmp/hn-tests/x' }, [{ type: 'addDirectories', directories: ['/tmp/hn-tests'], destination: 'session' }])
  assert.equal(dir.a.why?.kind, 'outside_folder')
  assert.equal(dir.a.why?.source, 'claude')
  await done(dir)
  const plain = ask('why-dir', 'Bash', { command: 'make deploy' })
  assert.deepEqual(plain.a.why, { kind: 'no_rule', text: 'No allow rule matches this command.', source: 'hypernova' })
  await done(plain)
  const q = ask('why-dir', 'AskUserQuestion', { questions: [{ question: 'Which?', header: 'W', multiSelect: false, options: [{ label: 'A' }, { label: 'B' }] }] })
  assert.equal(q.a.why, null)
  hub.answerApproval(q.a.id, { decision: 'deny' }, 'phone')
  await q.answer
})

test('a hook’s ask in plan mode', async () => {
  project('why-plan', null, null)
  const p = ask('why-plan', 'Bash', { command: 'make deploy' }, [], 'plan')
  assert.equal(p.a.why?.kind, 'mode')
  await done(p)
})
