// settings.claudeAgents: sessions live in Claude Code's own background daemon (`claude agents`).
// The registry's "bg" entries count as live sessions with their job id; the phone reaches one
// through `claude attach <job>` in a shared terminal, never a fork; a new one starts with
// `claude --bg`. A stand-in claude registers a job as Claude Code does and draws an empty input box
// when attached; a job named "gone0000" refuses to attach, as one that was removed does.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawn, type ChildProcess } from 'node:child_process'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-agents-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
process.env.FAKE_AGENTS_DIR = join(scratch, 'fake')
for (const d of [process.env.HOME, process.env.ZDOTDIR, process.env.FAKE_AGENTS_DIR]) mkdirSync(d, { recursive: true })
const registry = join(process.env.CLAUDE_CONFIG_DIR, 'sessions')
mkdirSync(registry, { recursive: true })
const fake = join(scratch, 'fake-claude')
writeFileSync(
  fake,
  `#!/bin/sh
S="$FAKE_AGENTS_DIR"
case "$1" in
  --bg)
    echo "$*" > "$S/bg-args"
    sleep 30 >/dev/null 2>&1 &
    printf '{"pid":%s,"sessionId":"feedbeef-0000-4000-8000-000000000001","cwd":"%s","kind":"bg","jobId":"feedbeef","status":"idle","updatedAt":%s000}' "$!" "$PWD" "$(date +%s)" > "$CLAUDE_CONFIG_DIR/sessions/$!.json"
    echo "backgrounded · feedbeef · fake"
    ;;
  attach)
    echo "$2" > "$S/attached"
    if [ "$2" = "gone0000" ]; then echo "No background session $2" >&2; exit 1; fi
    if [ "$2" = "ask00000" ]; then printf '\\n Do you want to proceed?\\n ❯ 1. Yes\\n   2. No\\n'; exec sleep 30; fi
    printf '\\n────────────────────\\n❯ \\n────────────────────\\n'
    exec sleep 30
    ;;
  *) exec sleep 30 ;;
esac
`,
)
chmodSync(fake, 0o755)
process.env.CLAUDE_BIN = fake

const hub = await import('../src/hub.ts')
const { settings } = await import('../src/store.ts')
const { attachAgentsTerm, createAgentsSession, scanTerms, termOf } = await import('../src/terms.ts')
const { parseAgentList } = await import('../src/claude-agents.ts')
const { promptSession } = await import('../src/http.ts')

const procs: ChildProcess[] = []
after(() => {
  for (const p of procs) p.kill('SIGKILL')
  // The holders the tests started, and the jobs the stand-in's --bg left running.
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

/** A `claude agents` job for session [id], registered as Claude Code registers a background one. */
function job(id: string, jobId: string, status = 'idle'): number {
  const p = spawn('sleep', ['30'], { stdio: 'ignore' })
  procs.push(p)
  writeFileSync(join(registry, `${p.pid}.json`), JSON.stringify({ pid: p.pid, sessionId: id, cwd: scratch, kind: 'bg', jobId, status, updatedAt: Date.now() }))
  return p.pid!
}

test('setting off: a claude agents entry in the registry is passed over, as before', () => {
  settings.claudeAgents = false
  job('agents-off-1', 'aaaa0001')
  hub.pollRegistry()
  assert.equal(hub.getSession('agents-off-1'), undefined)
  assert.equal(hub.agentJobOf('agents-off-1'), null)
})

test('setting on: a claude agents session is live, with the job id claude attach takes', () => {
  settings.claudeAgents = true
  const pid = job('agents-on-1', 'bbbb0001')
  hub.pollRegistry()
  assert.equal(hub.getSession('agents-on-1')?.pid, pid)
  assert.equal(hub.agentJobOf('agents-on-1'), 'bbbb0001')
  assert.equal(hub.isLiveTerminal('agents-on-1'), true)
  assert.equal(hub.sessionOfAgentJob('bbbb0001'), 'agents-on-1')
  assert.equal(hub.sessionOfAgentJob('nope0000'), null)
})

test('attachAgentsTerm: `claude attach <job>` in a shared terminal of the session, ready once the box shows', async () => {
  settings.claudeAgents = true
  job('agents-attach-1', 'cccc0001')
  hub.pollRegistry()
  const tid = await attachAgentsTerm('agents-attach-1', scratch, 'cccc0001')
  assert.ok(tid, 'a holder with the input box')
  assert.equal(readFileSync(join(process.env.FAKE_AGENTS_DIR!, 'attached'), 'utf8').trim(), 'cccc0001')
  scanTerms()
  assert.equal(termOf('agents-attach-1'), tid)
  // Asked again: the same terminal, not a second one.
  assert.equal(await attachAgentsTerm('agents-attach-1', scratch, 'cccc0001'), tid)
})

test('a job claude attach refuses (removed): null at once, not after the whole wait', async () => {
  settings.claudeAgents = true
  const t0 = Date.now()
  assert.equal(await attachAgentsTerm('agents-gone-1', scratch, 'gone0000'), null)
  assert.ok(Date.now() - t0 < 8000, `gave up after ${Date.now() - t0} ms`)
})

test('promptSession never forks a claude agents session: a terminal that won’t open is a 409', async () => {
  settings.claudeAgents = true
  job('agents-prompt-1', 'gone0000')
  hub.pollRegistry()
  const before = hub.listSessions().length
  await assert.rejects(promptSession('agents-prompt-1', 'hello'), (e: any) => e.status === 409 && e.code === 'terminal_busy')
  assert.equal(hub.listSessions().length, before, 'no fork, no resume')
})

test('createAgentsSession: `claude --bg` with the phone’s choices, the session id read from the registry', async () => {
  settings.claudeAgents = true
  const sid = await createAgentsSession({ cwd: scratch, model: 'sonnet', permissionMode: 'acceptEdits', prompt: 'Tidy the README' })
  assert.equal(sid, 'feedbeef-0000-4000-8000-000000000001')
  const args = readFileSync(join(process.env.FAKE_AGENTS_DIR!, 'bg-args'), 'utf8').trim()
  assert.match(args, /^--bg --model sonnet --permission-mode acceptEdits /)
  assert.match(args, /-- Tidy the README$/)
  assert.equal(hub.getSession(sid)?.title, 'Tidy the README')
  assert.equal(hub.getSession(sid)?.cwd, scratch)
})

test('attachAgentsTerm: Claude asking something (no input box) is still an open terminal, so typing says why', async () => {
  settings.claudeAgents = true
  job('agents-ask-1', 'ask00000', 'waiting')
  hub.pollRegistry()
  const t0 = Date.now()
  const tid = await attachAgentsTerm('agents-ask-1', scratch, 'ask00000')
  assert.ok(tid, 'the holder, not null ("didn’t open")')
  assert.ok(Date.now() - t0 < 8000, `waited ${Date.now() - t0} ms for a box that isn’t coming`)
})

test('attachAgentsTerm: a screen with no box and no word from the registry is kept once the wait is over', async () => {
  settings.claudeAgents = true
  const tid = await attachAgentsTerm('agents-ask-2', scratch, 'ask00000', 1500)
  assert.ok(tid)
})

test('parseAgentList: the background jobs of claude agents --json --all, nothing else', () => {
  const out = JSON.stringify([
    { id: 'dddd0001', cwd: '/w', kind: 'background', sessionId: 'agents-list-1', name: 'One', state: 'done' },
    { cwd: '/w', kind: 'interactive', sessionId: 'not-a-job', status: 'idle' },
    { id: 'dddd0002', kind: 'background', sessionId: 'no-cwd' },
  ])
  assert.deepEqual(parseAgentList(out)?.map((j) => j.id), ['dddd0001'])
  assert.equal(parseAgentList('not json'), null)
  assert.equal(parseAgentList('{}'), null)
})

test('applyAgentList: every job is a session, finished and stopped ones too, with its job id; a job gone from the list ends', () => {
  settings.claudeAgents = true
  hub.applyAgentList([
    { id: 'eeee0001', sessionId: 'agents-done-1', cwd: scratch, name: 'Finished one', state: 'done', startedAt: 1000 },
    { id: 'eeee0002', sessionId: 'agents-blocked-1', cwd: scratch, name: 'Stopped on a question', state: 'blocked', startedAt: 1000 },
  ])
  const done = hub.getSession('agents-done-1')
  assert.equal(done?.state, 'idle', 'on Home (done), not History (ended)')
  assert.equal(done?.title, 'Finished one')
  assert.equal(hub.agentJobOf('agents-done-1'), 'eeee0001')
  assert.equal(hub.getSession('agents-blocked-1')?.state, 'needs_you')
  hub.applyAgentList([{ id: 'eeee0002', sessionId: 'agents-blocked-1', cwd: scratch, state: 'blocked' }])
  assert.equal(hub.getSession('agents-done-1')?.state, 'ended')
  assert.equal(hub.agentJobOf('agents-done-1'), null)
})

test('a running job claude agents shows as blocked waits on the owner, though the registry says idle', () => {
  settings.claudeAgents = true
  job('agents-blocked-2', 'eeee0003', 'idle')
  hub.applyAgentList([{ id: 'eeee0003', sessionId: 'agents-blocked-2', cwd: scratch, state: 'blocked' }])
  hub.pollRegistry()
  assert.equal(hub.getSession('agents-blocked-2')?.state, 'needs_you')
})

test('promptSession: a finished job (no process) is typed into through claude attach, never resumed as a copy', async () => {
  settings.claudeAgents = true
  hub.applyAgentList([{ id: 'ffff0001', sessionId: 'agents-wake-1', cwd: scratch, state: 'done' }])
  const before = hub.listSessions().length
  // The stand-in's box never empties on Enter, so the send itself comes back "not sent": what
  // counts here is where it went.
  await promptSession('agents-wake-1', 'and one more thing').catch((e) => assert.ok(['not_sent', 'not_seen'].includes(e.code), e.message))
  assert.equal(readFileSync(join(process.env.FAKE_AGENTS_DIR!, 'attached'), 'utf8').trim(), 'ffff0001')
  assert.equal(hub.listSessions().length, before, 'no fork, no resume')
})
