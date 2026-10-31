// What "Always" covers (always.ts; docs/PROTOCOL.md, "Approvals"; IDEAS PA2, PA3, PA6): the rule
// read and written as Claude Code does, the exact PermissionRequest decision for each scope the
// phone can choose (and for none, Claude Code's suggestion as it was), the answer's checks, the
// rules in effect for "this session", and "Allow these N", which only ever allows. Suggestions are
// written by hand in the shapes Claude Code 2.1.314 sends; no settings file is read or written.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-always-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
for (const d of [process.env.HOME, process.env.ZDOTDIR, process.env.CLAUDE_CONFIG_DIR]) mkdirSync(d, { recursive: true })

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

const hub = await import('../src/hub.ts')
const { handleHook } = await import('../src/hooks.ts')
const { addDevice } = await import('../src/store.ts')
const { startServers } = await import('../src/http.ts')
const always = await import('../src/always.ts')
const TOKEN = 'always-test-device-token-0123456789abcdef'
addDevice({ id: 'phone', name: 'Phone', model: 'Pixel', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)

let close: () => Promise<void>
before(async () => {
  close = startServers()
  for (let i = 0; i < 50; i++) {
    try {
      if ((await call('GET', '/v1/health')).status === 200) return
    } catch {}
    await new Promise((r) => setTimeout(r, 20))
  }
})
after(async () => {
  await close?.()
  rmSync(scratch, { recursive: true, force: true })
})

function call(method: string, path: string, body?: unknown): Promise<{ status: number; json: any }> {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? undefined : JSON.stringify(body)
    const req = request(
      {
        host: '127.0.0.1', port: API, method, path,
        headers: { host: `127.0.0.1:${API}`, authorization: `Bearer ${TOKEN}`, ...(data ? { 'content-type': 'application/json', 'content-length': Buffer.byteLength(data) } : {}) },
      },
      (res) => {
        let s = ''
        res.setEncoding('utf8')
        res.on('data', (c) => (s += c))
        res.on('end', () => {
          let json: any = null
          try {
            json = JSON.parse(s)
          } catch {}
          resolve({ status: res.statusCode ?? 0, json })
        })
      },
    )
    req.on('error', reject)
    req.end(data)
  })
}

// Claude Code's own suggestion for `npm test --prefix daemon`: a prefix rule for the project's local file.
const NPM = [{ type: 'addRules', rules: [{ toolName: 'Bash', ruleContent: 'npm test:*' }], behavior: 'allow', destination: 'localSettings' }]
const out = (decision: object) => ({ hookSpecificOutput: { hookEventName: 'PermissionRequest', decision } })

/** A PermissionRequest from session [sid]; resolves to what the hook hands Claude Code. */
function ask(sid: string, command: string, suggestions: unknown[] = NPM, tool = 'Bash', input: object = { command }) {
  const res = { on: () => {}, writableEnded: false }
  const answer = handleHook({ session_id: sid, cwd: join(scratch, 'projects', sid), hook_event_name: 'PermissionRequest', tool_name: tool, tool_input: input, permission_suggestions: suggestions }, {} as never, res as never)
  const a = hub.pendingApprovals().filter((p) => p.sessionId === sid).at(-1)!
  return { answer, a }
}

// ── The rule, as Claude Code reads and writes it ─────────────────────────
test('a rule reads as Claude Code reads one: Tool or Tool(content), escapes undone, the rest refused', () => {
  const p = always.parseRule
  assert.deepEqual(p('Bash(npm test:*)'), { toolName: 'Bash', ruleContent: 'npm test:*' })
  assert.deepEqual(p('  WebFetch  '), { toolName: 'WebFetch' })
  assert.deepEqual(p('mcp__github__create_issue'), { toolName: 'mcp__github__create_issue' })
  assert.deepEqual(p('Read(//home/u/p/**)'), { toolName: 'Read', ruleContent: '//home/u/p/**' })
  // The whole tool, as Claude Code takes them.
  assert.deepEqual(p('Bash()'), { toolName: 'Bash' })
  assert.deepEqual(p('Bash(*)'), { toolName: 'Bash' })
  // Escaped parentheses and backslashes are the content's own.
  assert.deepEqual(p('Bash(echo \\(hi\\))'), { toolName: 'Bash', ruleContent: 'echo (hi)' })
  assert.deepEqual(p('Bash(printf a\\\\b)'), { toolName: 'Bash', ruleContent: 'printf a\\b' })
  for (const bad of ['', '   ', 'Bash(', 'Bash)', 'Bash(npm test', '(npm test)', 'Bash(npm) x', 'Ba sh', '1Bash', 'Bash(a\nb)', 'Bash(a\u0007b)', 7, null, 'B'.repeat(2001)]) {
    assert.equal(p(bad), null, `refused: ${JSON.stringify(bad)}`)
  }
  // Written back as Claude Code writes it: the same string.
  for (const s of ['Bash(npm test:*)', 'WebFetch', 'Bash(echo \\(hi\\))', 'Bash(printf a\\\\b)']) assert.equal(always.ruleString(p(s)!), s)
})

test('the suggestion’s rules and scope: only its allow rules, once each, in its order', () => {
  const s = [
    { type: 'addRules', rules: [{ toolName: 'Bash', ruleContent: 'git status:*' }, { toolName: 'Bash', ruleContent: 'echo (x)' }], behavior: 'allow', destination: 'localSettings' },
    { type: 'addRules', rules: [{ toolName: 'Bash', ruleContent: 'git status:*' }], behavior: 'allow', destination: 'localSettings' },
    { type: 'addRules', rules: [{ toolName: 'Bash', ruleContent: 'rm:*' }], behavior: 'deny', destination: 'localSettings' },
    { type: 'addDirectories', directories: ['/tmp/x'], destination: 'session' },
  ]
  assert.deepEqual(always.suggestedRules(s), ['Bash(git status:*)', 'Bash(echo \\(x\\))'])
  assert.equal(always.suggestedScope(s), 'project')
  assert.equal(always.suggestedScope([{ type: 'addRules', rules: [{ toolName: 'WebSearch' }], behavior: 'allow', destination: 'userSettings' }]), 'everywhere')
  assert.equal(always.suggestedScope([{ type: 'addRules', rules: [{ toolName: 'Read' }], behavior: 'allow', destination: 'session' }]), 'session')
  // A mode or a folder only: nothing to edit.
  assert.deepEqual(always.suggestedRules([{ type: 'setMode', mode: 'acceptEdits', destination: 'session' }]), [])
  assert.equal(always.suggestedScope([{ type: 'setMode', mode: 'acceptEdits', destination: 'session' }]), null)
  assert.deepEqual(always.suggestedRules(undefined), [])
})

// ── The decision Claude Code gets, per scope ─────────────────────────────
test('the approval carries Claude Code’s rule and scope for the phone’s Always sheet', async () => {
  const { answer, a } = ask('al-carry', 'npm test --prefix daemon')
  assert.equal(a.canAlways, true)
  assert.deepEqual(a.alwaysRules, ['Bash(npm test:*)'])
  assert.equal(a.alwaysScope, 'project')
  hub.answerApproval(a.id, { decision: 'allow' }, 'phone')
  assert.deepEqual(await answer, out({ behavior: 'allow' }))
  // No suggestion: no Always, nothing to show.
  const none = ask('al-carry', 'ls', [])
  assert.equal(none.a.canAlways, false)
  assert.deepEqual(none.a.alwaysRules, [])
  assert.equal(none.a.alwaysScope, null)
  hub.answerApproval(none.a.id, { decision: 'allow' }, 'phone')
  await none.answer
})

test('Always with no scope: Claude Code’s suggestion exactly as it came (an older phone)', async () => {
  const { answer, a } = ask('al-plain', 'npm test --prefix daemon')
  assert.equal((await call('POST', `/v1/approvals/${a.id}`, { decision: 'always' })).status, 200)
  assert.deepEqual(await answer, out({ behavior: 'allow', updatedPermissions: NPM }))
})

test('this session: the rule to the session only (nothing saved), and the rules in effect say so', async () => {
  const { answer, a } = ask('al-session', 'npm test --prefix daemon')
  assert.equal((await call('POST', `/v1/approvals/${a.id}`, { decision: 'always', scope: 'session', rules: ['Bash(npm test:*)'] })).status, 200)
  assert.deepEqual(
    await answer,
    out({ behavior: 'allow', updatedPermissions: [{ type: 'addRules', rules: [{ toolName: 'Bash', ruleContent: 'npm test:*' }], behavior: 'allow', destination: 'session' }] }),
  )
  const r = await call('GET', '/v1/sessions/al-session/permissions')
  assert.equal(r.status, 200)
  assert.deepEqual(r.json.permissions.sources.find((x: any) => x.source === 'session').allow, ['Bash(npm test:*)'])
})

test('this project: the project’s local file (localSettings), with the rule as edited', async () => {
  const { answer, a } = ask('al-project', 'npm test --prefix daemon')
  assert.equal((await call('POST', `/v1/approvals/${a.id}`, { decision: 'always', scope: 'project', rules: ['Bash(npm test --prefix daemon:*)'] })).status, 200)
  assert.deepEqual(
    await answer,
    out({ behavior: 'allow', updatedPermissions: [{ type: 'addRules', rules: [{ toolName: 'Bash', ruleContent: 'npm test --prefix daemon:*' }], behavior: 'allow', destination: 'localSettings' }] }),
  )
})

test('everywhere: the user’s settings (userSettings), widened to the whole tool', async () => {
  const { answer, a } = ask('al-everywhere', 'npm test --prefix daemon')
  assert.equal((await call('POST', `/v1/approvals/${a.id}`, { decision: 'always', scope: 'everywhere', rules: ['Bash'] })).status, 200)
  assert.deepEqual(await answer, out({ behavior: 'allow', updatedPermissions: [{ type: 'addRules', rules: [{ toolName: 'Bash' }], behavior: 'allow', destination: 'userSettings' }] }))
})

test('a scope without rules keeps Claude Code’s; the rest of its suggestion stays, to the session for "this session"', async () => {
  const mixed = [...NPM, { type: 'addDirectories', directories: ['/srv/shared'], destination: 'localSettings' }, { type: 'setMode', mode: 'acceptEdits', destination: 'session' }]
  const one = ask('al-mixed', 'npm test --prefix daemon', mixed)
  assert.equal((await call('POST', `/v1/approvals/${one.a.id}`, { decision: 'always', scope: 'everywhere' })).status, 200)
  assert.deepEqual(
    await one.answer,
    out({
      behavior: 'allow',
      updatedPermissions: [
        { type: 'addRules', rules: [{ toolName: 'Bash', ruleContent: 'npm test:*' }], behavior: 'allow', destination: 'userSettings' },
        { type: 'addDirectories', directories: ['/srv/shared'], destination: 'localSettings' },
        { type: 'setMode', mode: 'acceptEdits', destination: 'session' },
      ],
    }),
  )
  const two = ask('al-mixed', 'npm test --prefix daemon', mixed)
  assert.equal((await call('POST', `/v1/approvals/${two.a.id}`, { decision: 'always', scope: 'session', rules: ['Bash(npm test:*)'] })).status, 200)
  assert.deepEqual(
    await two.answer,
    out({
      behavior: 'allow',
      updatedPermissions: [
        { type: 'addRules', rules: [{ toolName: 'Bash', ruleContent: 'npm test:*' }], behavior: 'allow', destination: 'session' },
        { type: 'addDirectories', directories: ['/srv/shared'], destination: 'session' },
        { type: 'setMode', mode: 'acceptEdits', destination: 'session' },
      ],
    }),
  )
})

test('an Always that names a bad scope or rule is refused and the ask still waits', async () => {
  const { answer, a } = ask('al-bad', 'npm test --prefix daemon')
  for (const [body, code] of [
    [{ decision: 'always', scope: 'projectSettings', rules: ['Bash'] }, 'bad_scope'],
    [{ decision: 'always', rules: ['Bash'] }, 'bad_scope'],
    [{ decision: 'always', scope: 'project', rules: [] }, 'bad_rule'],
    [{ decision: 'always', scope: 'project', rules: ['Bash(npm test'] }, 'bad_rule'],
    [{ decision: 'always', scope: 'project', rules: ['Bash(a)', 'not a rule'] }, 'bad_rule'],
    [{ decision: 'always', scope: 'project', rules: 'Bash' }, 'bad_rule'],
    [{ decision: 'always', scope: 'project', rules: Array.from({ length: 21 }, (_, i) => `Bash(x${i})`) }, 'bad_rule'],
  ] as const) {
    const r = await call('POST', `/v1/approvals/${a.id}`, body)
    assert.equal(r.status, 400, JSON.stringify(body))
    assert.equal(r.json.error.code, code)
  }
  assert.ok(hub.pendingApprovals().some((p) => p.id === a.id))
  hub.answerApproval(a.id, { decision: 'deny' }, 'phone')
  await answer
})

// ── Allow these N ─────────────────────────────────────────────────────────
test('allow these N: each listed ask allowed as its own Allow; the others wait, nothing is denied', async () => {
  const sid = 'al-batch'
  const one = ask(sid, 'npm test')
  const two = ask(sid, 'git status')
  const three = ask(sid, 'rm -rf build')
  const other = ask('al-batch-other', 'ls')
  const q = ask(sid, '', [], 'AskUserQuestion', { questions: [{ question: 'Which?', header: 'Pick', multiSelect: false, options: [{ label: 'A' }, { label: 'B' }] }] })

  // Another session's ask, or a question, refuses the lot before any is answered.
  for (const ids of [[one.a.id, other.a.id], [one.a.id, q.a.id]]) {
    const r = await call('POST', `/v1/sessions/${sid}/allow`, { ids })
    assert.equal(r.status, 400)
    assert.equal(r.json.error.code, 'bad_ids')
  }
  for (const body of [{}, { ids: [] }, { ids: ['x y'] }, { ids: [7] }]) assert.equal((await call('POST', `/v1/sessions/${sid}/allow`, body)).status, 400)
  assert.equal((await call('POST', '/v1/sessions/no-such-session/allow', { ids: [one.a.id] })).status, 404)
  assert.ok([one, two, three, other, q].every((x) => hub.pendingApprovals().some((p) => p.id === x.a.id)))

  // One of them was answered a moment ago elsewhere: it's gone, the rest are allowed.
  hub.answerApproval(two.a.id, { decision: 'allow' }, 'mac')
  const r = await call('POST', `/v1/sessions/${sid}/allow`, { ids: [one.a.id, two.a.id, three.a.id, one.a.id] })
  assert.equal(r.status, 200)
  assert.deepEqual(r.json, { allowed: [one.a.id, three.a.id], gone: [two.a.id] })
  // Each a plain Allow: no rule is added for any of them.
  assert.deepEqual(await one.answer, out({ behavior: 'allow' }))
  assert.deepEqual(await three.answer, out({ behavior: 'allow' }))
  // Not listed: still waiting for its own answer.
  assert.ok(hub.pendingApprovals().some((p) => p.id === other.a.id))
  assert.ok(hub.pendingApprovals().some((p) => p.id === q.a.id))
  hub.answerApproval(other.a.id, { decision: 'deny' }, 'phone')
  assert.equal((await other.answer as any).hookSpecificOutput.decision.behavior, 'deny')
  hub.answerApproval(q.a.id, { decision: 'deny' }, 'phone')
  await q.answer
})
