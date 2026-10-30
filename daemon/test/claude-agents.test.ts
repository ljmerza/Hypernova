// settings.claudeAgents: sessions live in Claude Code's own background daemon (`claude agents`).
// The registry's "bg" entries count as live sessions with their job id; the phone reaches one
// through `claude attach <job>` in a shared terminal, never a fork; a new one starts with
// `claude --bg`. A stand-in claude registers a job as Claude Code does and draws an empty input box
// when attached; a job named "gone0000" refuses to attach, as one that was removed does.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawn, type ChildProcess } from 'node:child_process'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:net'
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
    # btw*: Claude Code's /btw panel (fixtures/fake-claude-btw.mjs).
    case "$2" in btw*) exec "${process.execPath}" "${join(import.meta.dirname, 'fixtures', 'fake-claude-btw.mjs')}" "$2" ;; esac
    if [ "$2" = "drift000" ]; then printf '\\033]0;Some other session\\007\\n────────────────────\\n❯ \\n────────────────────\\n'; exec sleep 30; fi
    if [ "$2" = "ask00000" ]; then printf '\\n Do you want to proceed?\\n ❯ 1. Yes\\n   2. No\\n'; exec sleep 30; fi
    # Claude Code's own permission dialog (the Arc screen); keys pressed into it land in a file.
    # permdrft: the same, under another session's title (the holder drifted to another view).
    if [ "$2" = "perm0000" ] || [ "$2" = "permdrft" ]; then
      [ "$2" = "permdrft" ] && printf '\\033]0;Some other session\\007'
      cat "$S/perm-screen.txt"; stty -echo 2>/dev/null; exec cat > "$S/keys-$2"
    fi
    # mode*: Claude Code's mode line under the box, Shift+Tab cycling it. Each attach is counted and
    # its Shift+Tabs land in keys-<job>-<n>. modedrft's first attach and every one of modestuk's
    # show another session (its title), as a holder that drifted does.
    case "$2" in mode*)
      n=$(( $(cat "$S/n-$2" 2>/dev/null || echo 0) + 1 )); echo $n > "$S/n-$2"
      t=Mine; [ "$2" = modestuk ] && t='Some other session'; [ "$2" = modedrft ] && [ $n = 1 ] && t='Some other session'
      m=0
      draw() {
        case $m in 0) l='manual mode on';; 1) l='accept edits on';; *) l='plan mode on';; esac
        printf '\\033[2J\\033[H\\033]0;%s\\007\\n────────────────────\\n❯ \\n────────────────────\\n  ⏵⏵ %s (shift+tab to cycle)\\n' "$t" "$l"
      }
      stty -echo -icanon min 1 time 0 2>/dev/null; draw
      while k=$(dd bs=3 count=1 2>/dev/null) && [ -n "$k" ]; do
        case "$k" in *Z*) echo shift-tab >> "$S/keys-$2-$n"; m=$(( (m + 1) % 3 )); draw ;; esac
      done
      exit 0 ;;
    esac
    printf '\\n────────────────────\\n❯ \\n────────────────────\\n'
    exec sleep 30
    ;;
  stop|rm)
    echo "$1 $2" >> "$S/verbs"
    if [ "$2" = "dirty000" ]; then echo "Not deleted: the worktree has unpushed commits" >&2; exit 1; fi
    echo "Deleted $2"
    ;;
  *) exec sleep 30 ;;
esac
`,
)
chmodSync(fake, 0o755)
process.env.CLAUDE_BIN = fake
// The phone API, for the routes (POST /v1/sessions/<id>/mode).
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
const { addDevice, settings } = await import('../src/store.ts')
const { answerMenu, attachAgentsTerm, attachDrifted, createAgentsSession, scanTerms, termOf, watchMenus } = await import('../src/terms.ts')
const { agentJobCommand, parseAgentList } = await import('../src/claude-agents.ts')
const { heldReady, promptSession, startServers } = await import('../src/http.ts')
const { askAside } = await import('../src/aside.ts')
const { heldOf } = await import('../src/held.ts')
const TOKEN = 'test-device-token-agents-0123456789'
addDevice({ id: 'phone', name: 'Phone', model: 'P', createdAt: Date.now(), lastSeenAt: Date.now() }, TOKEN)
const closeServers = startServers()

function post(path: string, body: object): Promise<{ status: number; json: any }> {
  return new Promise((resolve, reject) => {
    const req = request({ host: '127.0.0.1', port: API, method: 'POST', path, headers: { host: `127.0.0.1:${API}`, authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' } }, (res) => {
      let data = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (data += c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, json: data ? JSON.parse(data) : null }))
    })
    req.on('error', reject)
    req.end(JSON.stringify(body))
  })
}

const procs: ChildProcess[] = []
after(async () => {
  await closeServers()
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
const listed: { id: string; sessionId: string; cwd: string; state: string }[] = []
function job(id: string, jobId: string, status = 'idle', o: { name?: string; spare?: boolean } = {}): number {
  const p = spawn('sleep', ['30'], { stdio: 'ignore' })
  procs.push(p)
  writeFileSync(join(registry, `${p.pid}.json`), JSON.stringify({ pid: p.pid, sessionId: id, cwd: scratch, kind: 'bg', jobId, status, updatedAt: Date.now(), ...(o.name ? { name: o.name } : {}) }))
  if (!o.spare && !listed.some((j) => j.sessionId === id)) listed.push({ id: jobId, sessionId: id, cwd: scratch, state: 'working' })
  if (!o.spare && settings.claudeAgents === true) hub.applyAgentList([...listed])
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

test('a claude agents session says its job id to the phone; one forgotten is gone from the list', () => {
  settings.claudeAgents = true
  hub.applyAgentList([{ id: 'abab0001', sessionId: 'agents-forget-1', cwd: scratch, state: 'done' }])
  assert.equal(hub.listSessions().find((s) => s.id === 'agents-forget-1')?.agentJob, 'abab0001')
  hub.forgetSession('agents-forget-1')
  assert.equal(hub.getSession('agents-forget-1'), undefined)
  assert.equal(hub.agentJobOf('agents-forget-1'), null)
})

test('agentJobCommand: claude stop / rm with the job id, and Claude Code’s words when it refuses', async () => {
  assert.deepEqual(await agentJobCommand('stop', 'abab0002'), { ok: true, said: 'Deleted abab0002' })
  const r = await agentJobCommand('rm', 'dirty000')
  assert.equal(r.ok, false)
  assert.match(r.said, /unpushed commits/)
  assert.match(readFileSync(join(process.env.FAKE_AGENTS_DIR!, 'verbs'), 'utf8'), /^stop abab0002\nrm dirty000\n$/)
})

/** A job's state file as Claude Code writes it, with what it waits for ([needs]). */
function jobState(job: string, needs: string): void {
  const dir = join(process.env.CLAUDE_CONFIG_DIR!, 'jobs', job)
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, 'state.json'), JSON.stringify({ state: 'blocked', needs }))
}

test('a blocked job that asked in words has its question, not "waiting in the terminal"; a dialog has none', () => {
  settings.claudeAgents = true
  job('agents-q-1', 'qqqq0001', 'idle')
  jobState('qqqq0001', 'Say "go" when the bridge is awake')
  job('agents-q-2', 'qqqq0002', 'waiting')
  jobState('qqqq0002', 'approve Bash: rm -rf build')
  hub.applyAgentList([
    { id: 'qqqq0001', sessionId: 'agents-q-1', cwd: scratch, state: 'blocked' },
    { id: 'qqqq0002', sessionId: 'agents-q-2', cwd: scratch, state: 'blocked' },
    { id: 'qqqq0003', sessionId: 'agents-q-3', cwd: scratch, state: 'blocked' },
  ])
  jobState('qqqq0003', 'Complete steps 1–3, then tell me')
  hub.pollRegistry()
  const asked = hub.listSessions().find((s) => s.id === 'agents-q-1')
  assert.equal(asked?.state, 'needs_you')
  assert.equal(asked?.question, 'Say "go" when the bridge is awake')
  assert.equal(asked?.activity, 'Say "go" when the bridge is awake')
  const dialog = hub.listSessions().find((s) => s.id === 'agents-q-2')
  assert.equal(dialog?.state, 'needs_you')
  assert.equal(dialog?.question ?? null, null)
  // Stopped (no process): what it waits for is a reply, read on the next list.
  hub.applyAgentList([{ id: 'qqqq0003', sessionId: 'agents-q-3', cwd: scratch, state: 'blocked' }])
  assert.equal(hub.getSession('agents-q-3')?.question, 'Complete steps 1–3, then tell me')
})

test('a spare Claude Code started ahead (registered "bg", not listed by claude agents) is no session', () => {
  settings.claudeAgents = true
  job('agents-spare-1', 'spar0001', 'idle', { spare: true })
  hub.pollRegistry()
  assert.equal(hub.getSession('agents-spare-1'), undefined)
  assert.equal(hub.agentJobOf('agents-spare-1'), null)
})

test('a claude attach holder that drifted to another session is replaced, never typed into', async () => {
  settings.claudeAgents = true
  job('agents-drift-1', 'drift000', 'idle', { name: 'Mine' })
  hub.pollRegistry()
  const first = await attachAgentsTerm('agents-drift-1', scratch, 'drift000')
  assert.ok(first)
  scanTerms()
  const t0 = Date.now()
  while (!attachDrifted(first!, 'agents-drift-1') && Date.now() - t0 < 5000) await new Promise((ok) => setTimeout(ok, 100))
  assert.equal(attachDrifted(first!, 'agents-drift-1'), true, 'its title is another session’s')
  const second = await attachAgentsTerm('agents-drift-1', scratch, 'drift000')
  assert.ok(second)
  assert.notEqual(second, first, 'a fresh claude attach, not the drifted one')
})

test('attachDrifted: a holder showing its own session (title = its registry name) has not drifted', async () => {
  settings.claudeAgents = true
  job('agents-own-1', 'cccc0002', 'idle', { name: 'Own name' })
  hub.pollRegistry()
  const tid = await attachAgentsTerm('agents-own-1', scratch, 'cccc0002')
  assert.ok(tid)
  assert.equal(attachDrifted(tid!, 'agents-own-1'), false)
})

/** Look at the terminals until session [id]'s menu (or its absence) holds, as the daemon does every 0.8 s. */
async function settleMenus(id: string, want: (m: unknown) => boolean): Promise<void> {
  const t0 = Date.now()
  while (Date.now() - t0 < 6000) {
    await watchMenus()
    if (want(hub.getSession(id)?.menu ?? null)) return
    await new Promise((ok) => setTimeout(ok, 200))
  }
}

test('Claude Code’s own permission dialog through claude attach: a menu with permission, needs you though the registry says busy, Allow presses Enter there', async () => {
  settings.claudeAgents = true
  writeFileSync(join(process.env.FAKE_AGENTS_DIR!, 'perm-screen.txt'), readFileSync(join(import.meta.dirname, 'fixtures', 'permission-screens', 'attach-ask-rule.txt')))
  // The Arc session as it was: a job at work, its registry "busy", no approval open.
  job('agents-perm-1', 'perm0000', 'busy')
  hub.pollRegistry()
  assert.equal(hub.getSession('agents-perm-1')?.state, 'working')
  const tid = await attachAgentsTerm('agents-perm-1', scratch, 'perm0000', 1500)
  assert.ok(tid)
  scanTerms()
  assert.equal(termOf('agents-perm-1'), tid)
  await settleMenus('agents-perm-1', (m) => m !== null)
  const s = hub.getSession('agents-perm-1')!
  assert.equal(s.menu?.permission?.tool, 'Bash command')
  assert.match(s.menu?.permission?.command ?? '', /check\.py http:\/\/192\.168\.1\.76:8108 \/healthz/)
  assert.equal(s.state, 'needs_you')
  assert.match(s.activity ?? '', /^Bash command: \/media\/cubxi\/docker\/projects\/arc\/\.venv\/bin\/python/)
  // The registry still says busy, and a tool call elsewhere says working: it still needs you.
  hub.pollRegistry()
  hub.setState('agents-perm-1', 'working', 'Read: x')
  assert.equal(hub.getSession('agents-perm-1')?.state, 'needs_you')
  // Held prompts wait for it (http.ts, heldReady).
  assert.equal(heldReady('agents-perm-1'), 'wait')
  // Allow: option 0, where the cursor already is, so Enter goes in.
  assert.deepEqual(await answerMenu(tid!, s.menu!.id, { choice: 0 }), { ok: true })
  const keys = join(process.env.FAKE_AGENTS_DIR!, 'keys-perm0000')
  const t0 = Date.now()
  while (!(existsSync(keys) && readFileSync(keys, 'utf8').includes('\n')) && Date.now() - t0 < 3000) await new Promise((ok) => setTimeout(ok, 50))
  assert.equal(readFileSync(keys, 'utf8'), '\n', 'Enter, nothing else')
  assert.equal(hub.getSession('agents-perm-1')?.menu, null)
})

test('a claude attach holder that drifted: its dialog is not the session’s menu', async () => {
  settings.claudeAgents = true
  writeFileSync(join(process.env.FAKE_AGENTS_DIR!, 'perm-screen.txt'), readFileSync(join(import.meta.dirname, 'fixtures', 'permission-screens', 'attach-ask-rule.txt')))
  job('agents-perm-2', 'permdrft', 'busy', { name: 'Mine' })
  hub.pollRegistry()
  const tid = await attachAgentsTerm('agents-perm-2', scratch, 'permdrft', 1500)
  assert.ok(tid)
  scanTerms()
  const t0 = Date.now()
  while (!attachDrifted(tid!, 'agents-perm-2') && Date.now() - t0 < 5000) await new Promise((ok) => setTimeout(ok, 100))
  assert.equal(attachDrifted(tid!, 'agents-perm-2'), true)
  for (let i = 0; i < 3; i++) await watchMenus()
  assert.equal(hub.getSession('agents-perm-2')?.menu, null)
  assert.equal(hub.getSession('agents-perm-2')?.state, 'working')
})

/** The Shift+Tabs the stand-in took on job [jobId]'s [n]th attach (0 when it had none or wasn't attached). */
function shiftTabs(jobId: string, n: number): number {
  const f = join(process.env.FAKE_AGENTS_DIR!, `keys-${jobId}-${n}`)
  return existsSync(f) ? readFileSync(f, 'utf8').split('\n').filter(Boolean).length : 0
}
const attaches = (jobId: string): number => {
  const f = join(process.env.FAKE_AGENTS_DIR!, `n-${jobId}`)
  return existsSync(f) ? Number(readFileSync(f, 'utf8').trim()) : 0
}

test('the mode route on a claude agents job with no terminal: claude attach, then Shift+Tab until the mode shows', async () => {
  settings.claudeAgents = true
  job('agents-mode-1', 'mode0000', 'idle', { name: 'Mine' })
  hub.pollRegistry()
  assert.equal(termOf('agents-mode-1'), null)
  const r = await post('/v1/sessions/agents-mode-1/mode', { permissionMode: 'plan' })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  assert.equal(r.json.session.permissionMode, 'plan')
  assert.equal(attaches('mode0000'), 1)
  assert.equal(shiftTabs('mode0000', 1), 2, 'manual → accept edits → plan')
  // Bypass: the job wasn't started with it allowed, so nothing is pressed.
  const by = await post('/v1/sessions/agents-mode-1/mode', { permissionMode: 'bypassPermissions' })
  assert.equal(by.status, 409)
  assert.equal(by.json.error.code, 'mode_unavailable')
  assert.equal(attaches('mode0000'), 1, 'the same holder, not a second attach')
  assert.equal(shiftTabs('mode0000', 1), 2)
})

test('the mode route on a finished claude agents job (no process) attaches to it too', async () => {
  settings.claudeAgents = true
  hub.applyAgentList([...listed, { id: 'modedone', sessionId: 'agents-mode-2', cwd: scratch, state: 'done' }])
  const r = await post('/v1/sessions/agents-mode-2/mode', { permissionMode: 'acceptEdits' })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  assert.equal(r.json.session.permissionMode, 'acceptEdits')
  assert.equal(shiftTabs('modedone', 1), 1)
})

test('the mode route never presses Shift+Tab in a claude attach holder that drifted: a fresh attach takes it', async () => {
  settings.claudeAgents = true
  job('agents-mode-3', 'modedrft', 'idle', { name: 'Mine' })
  hub.pollRegistry()
  const first = await attachAgentsTerm('agents-mode-3', scratch, 'modedrft')
  assert.ok(first)
  scanTerms()
  const t0 = Date.now()
  while (!attachDrifted(first!, 'agents-mode-3') && Date.now() - t0 < 5000) await new Promise((ok) => setTimeout(ok, 100))
  assert.equal(attachDrifted(first!, 'agents-mode-3'), true)
  const r = await post('/v1/sessions/agents-mode-3/mode', { permissionMode: 'plan' })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  assert.equal(shiftTabs('modedrft', 1), 0, 'nothing pressed in the drifted one')
  assert.equal(shiftTabs('modedrft', 2), 2)
  assert.notEqual(termOf('agents-mode-3'), first)
})

function get(path: string): Promise<{ status: number; json: any }> {
  return new Promise((resolve, reject) => {
    const req = request({ host: '127.0.0.1', port: API, method: 'GET', path, headers: { host: `127.0.0.1:${API}`, authorization: `Bearer ${TOKEN}` } }, (res) => {
      let data = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (data += c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, json: data ? JSON.parse(data) : null }))
    })
    req.on('error', reject)
    req.end()
  })
}
/** The keys the /btw stand-in got on job [jobId] ('' when none). */
const btwKeys = (jobId: string): string => {
  const f = join(process.env.FAKE_AGENTS_DIR!, `btw-keys-${jobId}`)
  return existsSync(f) ? readFileSync(f, 'utf8') : ''
}
/** Session [id]'s side question once it's no longer `asking` (6 s at most). */
async function settledAside(id: string): Promise<any> {
  const t0 = Date.now()
  for (;;) {
    const r = await get(`/v1/sessions/${id}/btw`)
    assert.equal(r.status, 200)
    if (r.json.aside?.state !== 'asking' || Date.now() - t0 > 6000) return r.json.aside
    await new Promise((ok) => setTimeout(ok, 150))
  }
}

test('/btw to a claude agents job at work: typed through claude attach at once, never held or queued; its answer read off the panel, which Space closes', async () => {
  settings.claudeAgents = true
  job('agents-btw-1', 'btw00001', 'busy', { name: 'Mine' })
  hub.pollRegistry()
  assert.equal(hub.getSession('agents-btw-1')?.state, 'working')
  assert.equal(termOf('agents-btw-1'), null)
  const r = await post('/v1/sessions/agents-btw-1/btw', { question: 'Why does the parser\n  wrap around?' })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  assert.equal(r.json.aside.state, 'asking')
  assert.equal(r.json.aside.question, 'Why does the parser wrap around?')
  assert.equal(r.json.queued, undefined)
  assert.equal(heldOf('agents-btw-1').length, 0, 'not held for later')
  assert.match(btwKeys('btw00001'), /\/btw Why does the parser wrap around\?(\x1b\[201~)?\r/, 'typed and entered while Claude works')
  // One at a time: the last one is still being answered.
  const twice = await post('/v1/sessions/agents-btw-1/btw', { question: 'And the lexer?' })
  assert.equal(twice.status, 409)
  assert.equal(twice.json.error.code, 'asking')
  const a = await settledAside('agents-btw-1')
  assert.equal(a.state, 'answered')
  assert.equal(
    a.answer,
    'The parser keeps its tokens in a ring buffer of 64 entries, so a lookahead past that many tokens wraps around and reads stale ones. That only happens in the generated fixtures.\n\n- src/parser.ts: the buffer\n- src/lexer.ts: where tokens are made',
  )
  assert.equal(a.clipped, false)
  // Space closes the panel, never Esc (which, the panel gone, would stop Claude's turn).
  const t0 = Date.now()
  while (!btwKeys('btw00001').endsWith(' ') && Date.now() - t0 < 3000) await new Promise((ok) => setTimeout(ok, 50))
  assert.ok(btwKeys('btw00001').endsWith('\r '), JSON.stringify(btwKeys('btw00001')))
  assert.doesNotMatch(btwKeys('btw00001').replace(/\x1b\[20[01]~/g, ''), /\x1b/)
})

test('/btw: Claude Code’s error in the panel comes back as failed, in its words', async () => {
  settings.claudeAgents = true
  job('agents-btw-2', 'btwfail1', 'idle', { name: 'Mine' })
  hub.pollRegistry()
  const r = await post('/v1/sessions/agents-btw-2/btw', { question: 'Is the build green?' })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  const a = await settledAside('agents-btw-2')
  assert.equal(a.state, 'failed')
  assert.equal(a.error, 'Rate limited')
  assert.equal(a.answer, null)
})

test('/btw never types into a claude attach holder that shows another session', async () => {
  settings.claudeAgents = true
  job('agents-btw-3', 'btwdrft1', 'busy', { name: 'Mine' })
  hub.pollRegistry()
  const r = await post('/v1/sessions/agents-btw-3/btw', { question: 'Which file?' })
  assert.equal(r.status, 409)
  assert.equal(r.json.error.code, 'terminal_busy')
  assert.doesNotMatch(btwKeys('btwdrft1'), /btw/)
  assert.equal((await get('/v1/sessions/agents-btw-3/btw')).json.aside, null)
})

test('/btw: an empty question is a 400, a session without a terminal Hypernova holds a 409, never a fork', async () => {
  settings.claudeAgents = true
  job('agents-btw-4', 'btw00004', 'idle', { name: 'Mine' })
  hub.pollRegistry()
  const empty = await post('/v1/sessions/agents-btw-4/btw', { question: '  \n ' })
  assert.equal(empty.status, 400)
  assert.equal(empty.json.error.code, 'empty')
  settings.claudeAgents = false
  hub.upsertSession('agents-btw-5', { cwd: scratch, origin: 'terminal', state: 'idle', title: 'Plain', lastActivityAt: Date.now() })
  const before = hub.listSessions().length
  await assert.rejects(askAside('agents-btw-5', 'Where is it?'), (e: any) => e.status === 409 && e.code === 'no_terminal')
  assert.equal(hub.listSessions().length, before)
  settings.claudeAgents = true
})

test('the mode route refuses a claude attach that shows another session from the start, pressing nothing', async () => {
  settings.claudeAgents = true
  job('agents-mode-4', 'modestuk', 'idle', { name: 'Mine' })
  hub.pollRegistry()
  const r = await post('/v1/sessions/agents-mode-4/mode', { permissionMode: 'plan' })
  assert.equal(r.status, 409)
  assert.equal(r.json.error.code, 'terminal_busy')
  assert.ok(attaches('modestuk') >= 1)
  for (let n = 1; n <= attaches('modestuk'); n++) assert.equal(shiftTabs('modestuk', n), 0)
})
