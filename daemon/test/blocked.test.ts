// A session's Blocked tab (docs/PROTOCOL.md, "Blocked calls"): calls auto mode's classifier refused
// (its PermissionDenied hook and the transcript, one row for both) and calls nobody answered for 10
// minutes; Allow & retry types the owner's approval in and answers the same call's next approval;
// Dismiss keeps it gone after the transcript is read again. The transcript lines are shaped as the
// recorded refusal's (fixtures/permission-denied.json), with Claude Code 2.1.312's auto mode words.
import { test, after, mock } from 'node:test'
import assert from 'node:assert/strict'
import { appendFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-blocked-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
for (const d of [process.env.HOME, process.env.ZDOTDIR, process.env.CLAUDE_CONFIG_DIR]) mkdirSync(d, { recursive: true })
after(() => rmSync(scratch, { recursive: true, force: true }))

const hub = await import('../src/hub.ts')
const blocked = await import('../src/blocked.ts')
const hooks = await import('../src/hooks.ts')
const { SUPPORT } = await import('../src/util.ts')

const cwd = join(scratch, 'proj')
mkdirSync(cwd, { recursive: true })
const AUTO = (why: string) =>
  `Permission for this action was denied by the Claude Code auto mode classifier. Reason: ${why}. If you have other tasks that don't depend on this action, continue working on those. IMPORTANT: You *may* attempt to accomplish this action using other tools.`
const hook = (sid: string, ev: object) => hooks.handleHook({ session_id: sid, cwd, ...ev }, {} as never, {} as never)
const sent = () => {
  const out: object[] = []
  hub.addClient({ send: (s) => out.push(JSON.parse(s)) })
  return out as any[]
}

/** A tool call and its result, as Claude Code writes them to the transcript. */
function call(sid: string, toolUseId: string, input: object, result: string, isError: boolean, at = Date.now()): void {
  const path = hub.transcriptPathFor(cwd, sid)
  mkdirSync(dirname(path), { recursive: true })
  const ts = new Date(at).toISOString()
  const lines = [
    { type: 'assistant', uuid: `a-${toolUseId}`, sessionId: sid, cwd, timestamp: ts, message: { id: `msg-${toolUseId}`, type: 'message', role: 'assistant', content: [{ type: 'tool_use', id: toolUseId, name: 'Bash', input }] } },
    {
      type: 'user',
      uuid: `u-${toolUseId}`,
      sessionId: sid,
      cwd,
      timestamp: ts,
      message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: toolUseId, is_error: isError, content: result }] },
      toolUseResult: isError ? `Error: ${result}` : { stdout: result, stderr: '' },
    },
  ]
  appendFileSync(path, lines.map((l) => JSON.stringify(l)).join('\n') + '\n')
}

test('a refusal in words: auto mode (and its fail-closed form) and the 10 minutes, nothing else', () => {
  assert.deepEqual(blocked.refusalOf(AUTO('Pushing to the default branch')), { reason: 'auto_mode', why: 'Pushing to the default branch' })
  assert.deepEqual(blocked.refusalOf(`Error: ${AUTO('Deletes files outside the project')}`), { reason: 'auto_mode', why: 'Deletes files outside the project' })
  assert.equal(blocked.refusalOf('Permission for this action has been denied. Reason: the classifier is unavailable')?.reason, 'auto_mode')
  assert.deepEqual(blocked.refusalOf(hub.AWAY_MESSAGE), { reason: 'away', why: null })
  // Don't ask mode, a deny rule, the owner's own no: not this list's (a retry can't get past them).
  const fx = JSON.parse(readFileSync(new URL('./fixtures/permission-denied.json', import.meta.url), 'utf8'))
  assert.equal(blocked.refusalOf(fx.transcript[1].message.content[0].content), null)
  assert.equal(blocked.refusalOf('The user denied this from Hypernova.'), null)
  assert.equal(blocked.refusalOf('Exit code 1\nnpm ERR!'), null)
})

test('the hook and the transcript are one row; the count is on the session; keys are masked', async () => {
  const sid = 'bl-auto-1'
  const feed = sent()
  hub.upsertSession(sid, { cwd, origin: 'terminal', state: 'working' })
  const command = 'curl -H "Authorization: Bearer sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123" https://example.com/deploy'
  await hook(sid, { hook_event_name: 'PermissionDenied', tool_name: 'Bash', tool_input: { command }, tool_use_id: 'toolu_auto1', reason: 'Deploys to production' })
  assert.equal(hub.listSessions().find((s) => s.id === sid)?.blockedCalls, 1)
  assert.ok(feed.some((m) => m.t === 'session' && m.session.id === sid && m.session.blockedCalls === 1), 'the count goes out with the session')
  // The transcript a moment later: the same call (its id), not a second row.
  call(sid, 'toolu_auto1', { command }, AUTO('Deploys to production without being asked'), true)
  hub.pollTranscripts()
  const list = blocked.blockedList(sid)
  assert.equal(list.length, 1)
  const [b] = list
  assert.deepEqual([b!.tool, b!.reason, b!.toolUseId, b!.why], ['Bash', 'auto_mode', 'toolu_auto1', 'Deploys to production without being asked'])
  assert.ok(!b!.target.includes('sk-ant-api03'), b!.target)
  assert.ok(b!.target.startsWith('curl -H') && b!.target.endsWith('https://example.com/deploy'))
  assert.ok(!JSON.stringify(b!.input).includes('sk-ant-api03'))
  // Refused again (Claude tried once more): still one row, the newer refusal's.
  call(sid, 'toolu_auto2', { command }, AUTO('Deploys to production'), true)
  hub.pollTranscripts()
  assert.equal(blocked.blockedCount(sid), 1)
  assert.equal(blocked.blockedList(sid)[0]!.toolUseId, 'toolu_auto2')
  // The same call ran after all (the owner allowed it in the terminal): off the list.
  call(sid, 'toolu_auto3', { command }, 'deployed', false)
  hub.pollTranscripts()
  assert.equal(blocked.blockedCount(sid), 0)
  assert.equal(hub.listSessions().find((s) => s.id === sid)?.blockedCalls, 0)
})

test('the transcript alone (no hook) is enough, also when the session is read whole later; old refusals are history', () => {
  const sid = 'bl-transcript-1'
  call(sid, 'toolu_old1', { command: 'rm -rf build' }, AUTO('Old'), true, Date.now() - 3 * 86400_000)
  call(sid, 'toolu_tr1', { command: 'git push --force' }, AUTO('Force-pushes shared history'), true)
  hub.ensureParsed(hub.upsertSession(sid, { cwd, origin: 'terminal' }))
  assert.deepEqual(
    blocked.blockedList(sid).map((b) => [b.target, b.why]),
    [['git push --force', 'Force-pushes shared history']],
  )
})

test('nobody answered for 10 minutes (Claude Code): handed back to its own dialog, not blocked, not paused', async () => {
  const sid = 'bl-away-0'
  hub.upsertSession(sid, { cwd, origin: 'terminal' })
  mock.timers.enable({ apis: ['setTimeout'] })
  try {
    const asked = hub.requestApproval({ sessionId: sid, kind: 'tool', toolName: 'Bash', summary: 'Bash: npm publish', input: { command: 'npm publish' }, reason: null, canAlways: false, questions: null, plan: null }, 'toolu_away0')
    mock.timers.tick(hub.AWAY_MS)
    assert.equal((await asked).decision, 'timeout')
  } finally {
    mock.timers.reset()
  }
  assert.equal(blocked.blockedCount(sid), 0, 'never a "no", so nothing to retry')
  assert.equal(hub.getSession(sid)?.stoppedForAway, false)
})

test('asks handed back to the terminal: one notice for the session, gone once each was answered there', async () => {
  const sid = 'bl-away-n'
  hub.upsertSession(sid, { cwd, origin: 'terminal' })
  const feed = sent()
  const notices = () => hub.eventsFor(sid).filter((e) => e.kind === 'notice')
  const away = async (toolUseId: string, command: string) => {
    mock.timers.enable({ apis: ['setTimeout'] })
    try {
      const asked = hub.requestApproval({ sessionId: sid, kind: 'tool', toolName: 'Bash', summary: `Bash: ${command}`, input: { command }, reason: null, canAlways: false, questions: null, plan: null }, toolUseId)
      mock.timers.tick(hub.AWAY_MS)
      assert.equal((await asked).decision, 'timeout')
    } finally {
      mock.timers.reset()
    }
  }
  await away('toolu_awayn1', 'npm publish')
  await away('toolu_awayn2', 'git push')
  const [n] = notices()
  assert.equal(notices().length, 1, 'one notice, not one per ask')
  // No terminal the daemon reads (no card on the phone): it says where the question is.
  assert.equal(n!.kind === 'notice' && n!.text, hub.HANDED_BACK.terminal)
  // The first answered in the terminal (its tool finished): one still waits there.
  hub.resolveByToolUse('toolu_awayn1')
  assert.equal(notices().length, 1)
  await hook(sid, { hook_event_name: 'PostToolUse', tool_name: 'Bash', tool_input: { command: 'git push' }, tool_use_id: 'toolu_awayn2', tool_response: {} })
  assert.equal(notices().length, 0)
  assert.ok(feed.some((m) => m.t === 'event.removed' && m.sessionId === sid && m.id === n!.id))
  // Another later: a new notice. The session ends with it unanswered: it goes too.
  await away('toolu_awayn3', 'rm -rf build')
  assert.equal(notices().length, 1)
  hub.setState(sid, 'ended')
  assert.equal(notices().length, 0)
})

test('a session the daemon runs through the SDK: its ask waits for the phone past 10 minutes, never a no, no notice, no countdown', async () => {
  const { sdkAwayRule } = await import('../src/runner.ts')
  assert.equal(sdkAwayRule({}), 'never')
  const sid = 'bl-sdk-wait'
  hub.upsertSession(sid, { cwd, origin: 'hypernova', state: 'working' })
  const feed = sent()
  let settled = false
  mock.timers.enable({ apis: ['setTimeout'] })
  let asked: Promise<{ decision: string }>
  try {
    asked = hub.requestApproval({ sessionId: sid, kind: 'tool', toolName: 'Bash', summary: 'Bash: npm publish', input: { command: 'npm publish' }, reason: null, canAlways: false, questions: null, plan: null }, 'toolu_sdk1', undefined, sdkAwayRule({}))
    void asked.then(() => (settled = true))
    mock.timers.tick(hub.AWAY_MS)
    mock.timers.tick(10 * hub.AWAY_MS)
    await new Promise((r) => setImmediate(r))
  } finally {
    mock.timers.reset()
  }
  assert.equal(settled, false, 'still waiting')
  const [a] = hub.pendingApprovals().filter((x) => x.sessionId === sid)
  assert.ok(a, 'the phone still has the card')
  assert.deepEqual([a!.expiresAt, a!.autoDeny], [null, false])
  assert.ok(feed.some((m) => m.t === 'approval' && m.approval.id === a!.id && m.approval.expiresAt === null))
  assert.equal(hub.getSession(sid)?.state, 'needs_you')
  assert.equal(hub.getSession(sid)?.stoppedForAway, false)
  assert.equal(hub.eventsFor(sid).filter((e) => e.kind === 'notice').length, 0, 'no "the question is in the terminal"')
  assert.equal(blocked.blockedCount(sid), 0)
  // Answered from the phone an hour later: Claude gets that answer.
  assert.ok(hub.answerApproval(a!.id, { decision: 'allow' }, 'phone'))
  assert.equal((await asked!).decision, 'allow')
})

test('an automation’s run through the SDK waits for the owner too: no 10-minute "no", no countdown', async () => {
  const { sdkAwayRule } = await import('../src/runner.ts')
  const rule = sdkAwayRule({ automation: { id: 'a1', name: 'Morning brief', runId: 'r1' } })
  assert.equal(rule, 'never')
  const sid = 'bl-sdk-auto'
  hub.upsertSession(sid, { cwd, origin: 'hypernova', state: 'working' })
  let settled = false
  mock.timers.enable({ apis: ['setTimeout'] })
  let asked: Promise<{ decision: string }>
  try {
    asked = hub.requestApproval({ sessionId: sid, kind: 'tool', toolName: 'Bash', summary: 'Bash: npm publish', input: { command: 'npm publish' }, reason: null, canAlways: false, questions: null, plan: null }, 'toolu_sdk2', undefined, rule)
    void asked.then(() => (settled = true))
    const [a] = hub.pendingApprovals().filter((x) => x.sessionId === sid)
    assert.deepEqual([a!.autoDeny, a!.expiresAt], [false, null])
    mock.timers.tick(10 * hub.AWAY_MS)
    await new Promise((r) => setImmediate(r))
  } finally {
    mock.timers.reset()
  }
  assert.equal(settled, false, 'still waiting')
  assert.equal(hub.getSession(sid)?.stoppedForAway, false)
  assert.equal(blocked.blockedCount(sid), 0)
  const [a] = hub.pendingApprovals().filter((x) => x.sessionId === sid)
  assert.ok(hub.answerApproval(a!.id, { decision: 'deny' }, 'phone'))
  assert.equal((await asked!).decision, 'deny')
})

test('a call refused for want of an answer (Codex): Allow & retry says so, and its next ask is answered yes once', async () => {
  const sid = 'bl-away-1'
  hub.upsertSession(sid, { cwd, origin: 'terminal' })
  blocked.noteBlocked(sid, { tool: 'Bash', input: { command: 'npm publish' }, reason: 'away', toolUseId: 'toolu_away1' })
  const [b] = blocked.blockedList(sid)
  assert.deepEqual([b?.reason, b?.toolUseId, b?.target], ['away', 'toolu_away1', 'npm publish'])
  // The send fails (the terminal had unsent text, say): the row stays, the error is the route's.
  await assert.rejects(
    blocked.retryBlocked(sid, b!.id, async () => {
      throw Object.assign(new Error('There’s unsent text'), { code: 'typing' })
    }),
    /unsent text/,
  )
  assert.equal(blocked.blockedCount(sid), 1)
  const texts: string[] = []
  const out = await blocked.retryBlocked(sid, b!.id, async (_id, text) => (texts.push(text), { id: sid, queued: false }))
  assert.deepEqual(out, { id: sid, queued: false })
  assert.equal(texts[0], 'I’m back, and I approve the call you were waiting on. Run it again now, exactly as it was:\n\nBash: npm publish')
  assert.equal(blocked.blockedCount(sid), 0)
  await assert.rejects(blocked.retryBlocked(sid, b!.id, async () => ({})), (e: any) => e.status === 404 && e.code === 'no_blocked')
  // Claude runs it again and Claude Code asks: the owner already said yes, once.
  const ask = () => hub.requestApproval({ sessionId: sid, kind: 'tool', toolName: 'Bash', summary: 'Bash: npm publish', input: { command: 'npm publish' }, reason: null, canAlways: false, questions: null, plan: null }, null)
  assert.deepEqual(await ask(), { decision: 'allow' })
  const again = ask()
  assert.equal(hub.pendingApprovals().filter((a) => a.sessionId === sid).length, 1, 'a second time asks')
  hub.resolveSession(sid)
  assert.equal((await again).decision, 'terminal')
})

test('auto mode: Allow & retry names the call whole, in the owner’s words', async () => {
  const sid = 'bl-retry-1'
  hub.upsertSession(sid, { cwd, origin: 'terminal' })
  const command = 'git push origin main\ngit push --tags'
  blocked.noteBlocked(sid, { tool: 'Bash', input: { command }, reason: 'auto_mode', why: 'Pushes to the default branch', toolUseId: 'toolu_r1' })
  const texts: string[] = []
  await blocked.retryBlocked(sid, blocked.blockedList(sid)[0]!.id, async (_id, text) => texts.push(text))
  assert.equal(texts[0], `I approve this call that auto mode blocked. Run it again now, exactly as it was:\n\nBash: ${command}`)
})

test('Dismiss: gone, and still gone when the transcript is read again from the start', () => {
  const sid = 'bl-dismiss-1'
  call(sid, 'toolu_d1', { command: 'docker system prune -af' }, AUTO('Deletes every image'), true)
  const rec = hub.upsertSession(sid, { cwd, origin: 'terminal' })
  hub.ensureParsed(rec)
  const [b] = blocked.blockedList(sid)
  assert.ok(b)
  assert.equal(blocked.dismissBlocked(sid, b!.id), true)
  assert.equal(blocked.dismissBlocked(sid, b!.id), false)
  assert.equal(blocked.blockedCount(sid), 0)
  assert.ok(JSON.parse(readFileSync(join(SUPPORT, 'blocked-settled.json'), 'utf8')).toolu_d1, 'kept for a restart')
  rec.parse = null
  hub.ensureParsed(rec)
  assert.equal(blocked.blockedCount(sid), 0)
})

test('PermissionDenied goes in with the optional hooks (any tool), to an older install too, and out on uninstall', () => {
  const file = join(process.env.CLAUDE_CONFIG_DIR!, 'settings.json')
  writeFileSync(file, '{}')
  hooks.installIntegration()
  const read = () => JSON.parse(readFileSync(file, 'utf8'))
  assert.deepEqual(read().hooks.PermissionDenied.map((g: any) => g.matcher), ['*'])
  const s = read()
  delete s.hooks.PermissionDenied
  writeFileSync(file, JSON.stringify(s))
  assert.equal(hooks.integrationStatus().hooks.installed, true, 'never required')
  assert.equal(hooks.addOptionalHooks(), true)
  assert.equal(read().hooks.PermissionDenied[0].matcher, '*')
  assert.equal(read().hooks.PermissionDenied[0].hooks[0].type, 'http')
  hooks.uninstallIntegration()
  assert.equal(read().hooks?.PermissionDenied, undefined)
  assert.ok(existsSync(file))
})
