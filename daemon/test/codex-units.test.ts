// Codex's pieces on their own (docs/specs/codex-app-server.md): which command lines a shared
// terminal takes, the TUI's arguments, the version floor, items as events, usage windows, approval
// cards and answers, the switch's framing, and the shell function that comes with Codex.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir, userInfo } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-cxu-'))
process.env.HOME = join(scratch, 'home')
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'home', '.claude')
process.env.ZDOTDIR = join(scratch, 'zdot')
process.env.HYPERNOVA_CODEX = join(scratch, 'no-codex-here')
for (const d of [process.env.HOME, process.env.ZDOTDIR, process.env.CLAUDE_CONFIG_DIR]) mkdirSync(d, { recursive: true })
after(() => rmSync(scratch, { recursive: true, force: true }))

const { codexLaunch } = await import('../src/codex/launch.ts')
const { codexTuiArgs, codexProxyPath } = await import('../src/term/shared.ts')
const { atLeast } = await import('../src/codex/binary.ts')
const { itemEvents, historyEvents, diffOf, turnEnd } = await import('../src/codex/events.ts')
const { usageOf, codexState } = await import('../src/codex/account.ts')
const { askOf, replyOf } = await import('../src/codex/approvals.ts')
const { framed } = await import('../src/switch.ts')

test('only the TUI’s own command lines go into a shared terminal', () => {
  assert.deepEqual(codexLaunch([]), { resume: null })
  assert.deepEqual(codexLaunch(['fix the tests']), { resume: null })
  assert.deepEqual(codexLaunch(['-m', 'gpt-x', '--search', 'fix it']), { resume: null })
  assert.deepEqual(codexLaunch(['resume', '01a16028-2541-71d1-81e8-3701bb53d054']), { resume: '01a16028-2541-71d1-81e8-3701bb53d054' })
  assert.deepEqual(codexLaunch(['resume', '--last']), { resume: null })
  assert.deepEqual(codexLaunch(['fork', '01a16028-2541-71d1-81e8-3701bb53d054']), { resume: null })
  assert.deepEqual(codexLaunch(['-c', 'model="o3"']), { resume: null })
  assert.deepEqual(codexLaunch(['--config=model="o3"']), { resume: null })
  for (const args of [['exec', 'hi'], ['login'], ['--help'], ['-V'], ['--remote', 'unix://'], ['-i', 'a.png'], ['two', 'prompts'], ['-m'], ['app-server'], ['resume', 'x y']]) assert.equal(codexLaunch(args), undefined, args.join(' '))
})

test('the TUI goes to its holder’s socket, in its folder, with --remote after resume', () => {
  const remote = `unix://${codexProxyPath('abc')}`
  assert.deepEqual(codexTuiArgs('abc', [], '/p'), ['--remote', remote, '--cd', '/p'])
  assert.deepEqual(codexTuiArgs('abc', ['resume', 'T1'], '/p'), ['resume', '--remote', remote, '--cd', '/p', 'T1'])
  assert.deepEqual(codexTuiArgs('abc', ['-C', '/q', 'hi'], '/p'), ['--remote', remote, '-C', '/q', 'hi'])
})

test('Codex 0.163.0 and later are taken, earlier ones not', () => {
  assert.equal(atLeast('0.163.0', '0.163.0'), true)
  assert.equal(atLeast('0.170.1', '0.163.0'), true)
  assert.equal(atLeast('1.0.0', '0.163.0'), true)
  assert.equal(atLeast('0.162.9', '0.163.0'), false)
})

test('no Codex here: the snapshot says nothing about it', () => {
  assert.equal(codexState(), null)
})

test('items as the chat’s events: commands, file changes, replies, a recognized read', () => {
  const ts = 1000
  const [cmd] = itemEvents({ type: 'commandExecution', id: 'e1', command: "/bin/zsh -lc 'npm test'", commandActions: [{ type: 'unknown', command: 'npm test' }], status: 'failed', aggregatedOutput: 'boom', exitCode: 1, durationMs: 50 }, ts)
  assert.equal(cmd!.kind, 'tool')
  assert.deepEqual([cmd!.kind === 'tool' && cmd!.name, cmd!.kind === 'tool' && cmd!.summary, cmd!.kind === 'tool' && cmd!.status, cmd!.kind === 'tool' && cmd!.exitCode], ['Bash', 'npm test', 'error', 1])
  const [read] = itemEvents({ type: 'commandExecution', id: 'e2', command: 'cat a.ts', commandActions: [{ type: 'read', command: 'cat a.ts', name: 'a.ts', path: '/p/a.ts' }], status: 'completed' }, ts)
  assert.equal(read!.kind === 'tool' && read!.name, 'Read')
  assert.equal(read!.kind === 'tool' && read!.path, '/p/a.ts')
  const patch = '@@ -1,2 +1,2 @@\n-old\n+new\n same'
  const files = itemEvents({ type: 'fileChange', id: 'f1', status: 'completed', changes: [{ path: '/p/a.ts', kind: { type: 'update', move_path: null }, diff: patch }, { path: '/p/b.ts', kind: { type: 'add' }, diff: '@@ -0,0 +1 @@\n+hi' }] }, ts)
  assert.deepEqual(files.map((e) => e.kind === 'tool' && [e.id, e.name, e.path, e.diff?.added, e.diff?.removed]), [['f1', 'Edit', '/p/a.ts', 1, 1], ['f1#1', 'Write', '/p/b.ts', 1, 0]])
  assert.deepEqual(itemEvents({ type: 'agentMessage', id: 'm', text: 'Hi' }, ts, true), [{ id: 't:m', kind: 'assistant', ts, text: 'Hi', streaming: true }])
  const [user] = itemEvents({ type: 'userMessage', id: 'u', clientId: 'c-9', content: [{ type: 'text', text: 'go', text_elements: [] }] }, ts)
  assert.equal(user!.id, 'u:c-9')
  assert.equal(user!.kind === 'user' && user!.clientId, 'c-9')
  assert.deepEqual(itemEvents({ type: 'hookPrompt', id: 'h', fragments: [] }, ts), [])
  assert.equal(diffOf('/x', 'no hunks'), null)
})

test('a thread’s history reads as its turns: items in order, then how each ended', () => {
  const evs = historyEvents({ turns: [
    { id: 'T1', status: 'completed', startedAt: 10, completedAt: 12, durationMs: 2000, items: [{ type: 'userMessage', id: 'u1', clientId: null, content: [{ type: 'text', text: 'hi', text_elements: [] }] }, { type: 'agentMessage', id: 'a1', text: 'hello' }] },
    { id: 'T2', status: 'interrupted', startedAt: 20, completedAt: 21, items: [{ type: 'userMessage', id: 'u2', clientId: null, content: [{ type: 'text', text: 'wait', text_elements: [] }] }] },
  ] })
  assert.deepEqual(evs.map((e) => [e.kind, e.id]), [['user', 'u:u1'], ['assistant', 't:a1'], ['result', 'r:T1'], ['user', 'u:u2'], ['notice', 'r:T2']])
  assert.equal(turnEnd({ id: 'X', status: 'failed', error: { message: 'Usage limit reached.' } }, 0)!.kind === 'result' && (turnEnd({ id: 'X', status: 'failed', error: { message: 'Usage limit reached.' } }, 0) as any).error, 'Usage limit reached.')
})

test('Codex’s windows go by their length: a week alone is the week', () => {
  const week = usageOf({ primary: { usedPercent: 89, windowDurationMins: 10080, resetsAt: 1793092247 }, secondary: null }, 5)
  assert.deepEqual(week, { fiveHour: null, sevenDay: { pct: 89, resetsAt: 1793092247000 }, limited: false, observedAt: 5 })
  const both = usageOf({ primary: { usedPercent: 3, windowDurationMins: 300, resetsAt: 100 }, secondary: { usedPercent: 50, windowDurationMins: 10080, resetsAt: 200 }, rateLimitReachedType: 'rate_limit_reached' }, 5)
  assert.equal(both.fiveHour!.pct, 3)
  assert.equal(both.sevenDay!.pct, 50)
  assert.equal(both.limited, true)
})

test('approval cards and Codex’s answers for them', () => {
  const cmd = { id: 1, method: 'item/commandExecution/requestApproval', params: { threadId: 'T', itemId: 'e', command: "/bin/zsh -lc 'rm -rf build'", commandActions: [{ type: 'unknown', command: 'rm -rf build' }], reason: 'Outside the sandbox.' } }
  // askOf reads the session's own events for file changes; a command needs nothing of it.
  const a = askOf(cmd, 'no-such-session')!
  assert.deepEqual([a.kind, a.toolName, a.summary, a.reason], ['tool', 'Bash', 'Bash: rm -rf build', 'Outside the sandbox.'])
  assert.deepEqual(replyOf(cmd, { decision: 'allow' }), { decision: 'accept' })
  assert.deepEqual(replyOf(cmd, { decision: 'deny' }), { decision: 'decline' })
  assert.deepEqual(replyOf(cmd, { decision: 'timeout' }), { decision: 'cancel' })
  const q = { id: 2, method: 'item/tool/requestUserInput', params: { threadId: 'T', itemId: 'q', questions: [{ id: 'q1', header: 'Pick', question: 'Which one?', isOther: false, isSecret: false, options: [{ label: 'A', description: '' }] }] } }
  const qa = askOf(q, 'S')!
  assert.equal(qa.kind, 'question')
  assert.deepEqual(replyOf(q, { decision: 'allow', answers: { 'Which one?': 'A' } }), { answers: { q1: { answers: ['A'] } } })
  assert.equal(askOf({ ...q, params: { ...q.params, questions: [{ ...q.params.questions[0], isSecret: true }] } }, 'S'), null, 'a secret is answered in the terminal')
  const perm = { id: 3, method: 'item/permissions/requestApproval', params: { threadId: 'T', itemId: 'p', permissions: { network: { enabled: true }, fileSystem: null } } }
  assert.match(askOf(perm, 'S')!.summary, /the network/)
  assert.deepEqual(replyOf(perm, { decision: 'allow' }), { permissions: { network: { enabled: true } }, scope: 'turn' })
  assert.deepEqual(replyOf(perm, { decision: 'deny' }), { permissions: {}, scope: 'turn' })
  assert.equal(askOf({ id: 4, method: 'mcpServer/elicitation/request', params: { threadId: 'T' } }, 'S'), null)
})

test('a switch’s text reaches the other agent as background, and nothing for none', () => {
  assert.equal(framed('', 'codex', '/p/demo'), '')
  const f = framed('User: hi\nCodex: hello', 'codex', '/p/demo')
  assert.match(f, /^The user was working with Codex in demo \(\/p\/demo\) and switched to you in Hypernova\./)
  assert.match(f, /not a new request/)
  assert.match(f, /<previous_conversation>\nUser: hi\nCodex: hello\n<\/previous_conversation>$/)
})

// The file the block goes in, for this machine's login shell: ~/.zshrc for zsh, ~/.bashrc for bash on Linux.
const { PLATFORM, shellRc } = await import('../src/platform.ts')
const shell = shellRc(PLATFORM, userInfo().shell, process.env, process.env.HOME!)
test('the shell block: a stale `codex` function goes, and Codex on brings Hypernova’s own', { skip: !shell && 'the login shell is neither zsh nor bash' }, async () => {
  const rc = shell!.path
  // As another tool left it: a codex function of its own inside Hypernova's markers.
  writeFileSync(rc, `export A=1\n\n# >>> hypernova shared terminals (lets your phone see and type into claude sessions; safe to delete) hn:sep=1\nfunction claude { command claude "$@"; }\nfunction codex {\n  "$HOME/x/hn" run-codex "$@"\n}\n# <<< hypernova shared terminals\n`)
  const hooks = await import('../src/hooks.ts')
  const { settings } = await import('../src/store.ts')
  settings.codex = false
  hooks.installIntegration()
  let text = readFileSync(rc, 'utf8')
  assert.match(text, /^export A=1\n/)
  assert.match(text, /function claude \{/)
  assert.doesNotMatch(text, /function codex/)
  settings.codex = true
  hooks.installIntegration()
  text = readFileSync(rc, 'utf8')
  assert.match(text, /function codex \{\n  if \[\[ -t 0 && -t 1 && -z "\$HYPERNOVA_TERM" && -z "\$HYPERNOVA_OFF"/)
  // The codex this shell would run: zsh's $commands, bash's `type -P`.
  assert.match(text, shell!.kind === 'zsh' ? /HYPERNOVA_CODEX="\$\{commands\[codex\]\}" .* run-codex "\$@"/ : /HYPERNOVA_CODEX="\$\(type -P codex\)" .* run-codex "\$@"/)
  assert.match(text, /command codex "\$@"/)
  assert.equal(text.split('# >>> hypernova shared terminals').length, 2, 'one block')
})

test('Codex’s tokens in Statistics: a joined thread’s history isn’t today’s work, and a report just after the turn still counts', async () => {
  const { noteCodexTokens, noteCodexTurn, codexDays } = await import('../src/codex/stats.ts')
  const at = new Date(2026, 9, 22, 10).getTime()
  const tok = (n: number) => ({ input: 800 * n, output: 50 * n, cacheRead: 200 * n, cacheWrite: 0 })
  const day = () => codexDays()['2026-10-22']!
  // An old thread joined from a terminal: Codex replays its total outside a turn. Nothing counts.
  noteCodexTokens({ thread: 'old', cwd: '/p/solo', at, total: tok(40), last: tok(1), inTurn: false })
  assert.equal(day()?.tokens.input ?? 0, 0)
  // Its next turn adds one request: only that counts.
  noteCodexTokens({ thread: 'old', cwd: '/p/solo', at, total: tok(41), last: tok(1), inTurn: true })
  assert.deepEqual(day().tokens, tok(1))
  // Another old thread whose first report comes in a turn: its last request, not its 30 before.
  noteCodexTokens({ thread: 'older', cwd: '/p/solo', at, total: tok(30), last: tok(1), inTurn: true })
  assert.deepEqual(day().tokens, tok(2))
  assert.equal(day().projects.solo!.tokens, 2 * 1050)
  // Sessions: a thread counts once, on its first turn.
  noteCodexTurn({ thread: 'old', title: 't', cwd: '/p/solo', at, ms: 1000, prompts: 1, tools: { Bash: 2 }, files: 0 })
  noteCodexTurn({ thread: 'old', title: 't', cwd: '/p/solo', at, ms: 2000, prompts: 1, tools: {}, files: 1 })
  assert.equal(day().sessions, 1)
  assert.equal(day().ms, 3000)
  assert.equal(day().toolCalls, 2)
  assert.equal(day().longest?.ms, 3000)
})
