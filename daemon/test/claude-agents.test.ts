// settings.claudeAgents: sessions live in Claude Code's own background daemon (`claude agents`).
// The registry's "bg" entries count as live sessions with their job id; the phone reaches one
// through `claude attach <job>` in a shared terminal, never a fork; a new one starts with
// `claude --bg`. A stand-in claude registers a job as Claude Code does and draws an empty input box
// when attached; a job named "gone0000" refuses to attach, as one that was removed does.
import { test, after, mock } from 'node:test'
import assert from 'node:assert/strict'
import { spawn, type ChildProcess } from 'node:child_process'
import { chmodSync, copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, renameSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
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
// Laid out as Claude Code's own install is: a file named for its version, `claude` a link to it.
mkdirSync(join(scratch, 'versions'))
mkdirSync(join(scratch, 'bin'))
const fake = join(scratch, 'versions', '2.1.314')
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
    # form0000: AskUserQuestion's form of several questions (form-screen.txt); its keys, arrows
    # included, land in a file as they come.
    if [ "$2" = "form0000" ]; then
      cat "$S/form-screen.txt"; stty -echo -icanon min 1 time 0 2>/dev/null; exec cat > "$S/keys-$2"
    fi
    # hb*: the dialog of an ask handed back (perm-screen.txt) until a key answers it (that key lands
    # in keys-<job>-<n>, n counting attaches), then the input box. hbdrift0's first attach shows
    # another session's title, as a holder that drifted does.
    case "$2" in hb*)
      n=$(( $(cat "$S/n-$2" 2>/dev/null || echo 0) + 1 )); echo $n > "$S/n-$2"
      [ "$2" = hbdrift0 ] && [ $n = 1 ] && printf '\\033]0;Some other session\\007'
      cat "$S/perm-screen.txt"; stty -echo -icanon min 1 time 0 2>/dev/null
      dd bs=1 count=1 2>/dev/null > "$S/keys-$2-$n"
      printf '\\033[2J\\033[H\\n────────────────────\\n❯ \\n────────────────────\\n'
      exec sleep 30 ;;
    esac
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
    # eff*: an input box that takes a typed line (echoed into it) and empties on Enter; each line
    # lands in typed-<job>. effstuk* show another session (its title) on every attach, effdrft*
    # on the first one only (attaches counted in n-<job>).
    case "$2" in eff*)
      n=$(( $(cat "$S/n-$2" 2>/dev/null || echo 0) + 1 )); echo $n > "$S/n-$2"
      t=Mine; case "$2" in effstuk*) t='Some other session' ;; effdrft*) [ $n = 1 ] && t='Some other session' ;; esac
      draw() { printf '\\033[2J\\033[H\\033]0;%s\\007\\n────────────────────\\n❯ \\n────────────────────\\n\\033[3;3H' "$t"; }
      stty echo icanon 2>/dev/null; draw
      while IFS= read -r l; do echo "$l" >> "$S/typed-$2"; draw; done
      exit 0 ;;
    esac
    printf '\\n────────────────────\\n❯ \\n────────────────────\\n'
    exec sleep 30
    ;;
  --version) echo "2.1.314 (Claude Code)" ;;
  stop|rm|respawn)
    echo "$1 $2" >> "$S/verbs"
    if [ "$2" = "dirty000" ]; then echo "Not deleted: the worktree has unpushed commits" >&2; exit 1; fi
    case "$2" in nore*) echo "Couldn't respawn $2: it isn't running" >&2; exit 1 ;; esac
    echo "Deleted $2"
    ;;
  *) exec sleep 30 ;;
esac
`,
)
chmodSync(fake, 0o755)
const claudeLink = join(scratch, 'bin', 'claude')
symlinkSync(fake, claudeLink)
process.env.CLAUDE_BIN = claudeLink
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
const { answerMenu, attachAgentsTerm, attachDrifted, attachWaitingJobs, createAgentsSession, createSharedSession, scanTerms, termOf, watchMenus } = await import('../src/terms.ts')
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
function job(id: string, jobId: string, status = 'idle', o: { name?: string; spare?: boolean; version?: string } = {}): number {
  const p = spawn('sleep', ['30'], { stdio: 'ignore' })
  procs.push(p)
  writeFileSync(join(registry, `${p.pid}.json`), JSON.stringify({ pid: p.pid, sessionId: id, cwd: scratch, kind: 'bg', jobId, status, updatedAt: Date.now(), ...(o.name ? { name: o.name } : {}), ...(o.version ? { version: o.version } : {}) }))
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

test('the setting from the phone: POST /v1/claude {agents}, saved, said to every phone, taken without a restart', async () => {
  settings.claudeAgents = false
  const feed: any[] = []
  hub.addClient({ send: (s) => feed.push(JSON.parse(s)) })
  const bad = await post('/v1/claude', { agents: 'yes' })
  assert.equal(bad.status, 400)
  assert.equal(bad.json.error.code, 'bad_value')
  assert.equal(settings.claudeAgents, false)
  const saved = () => JSON.parse(readFileSync(join(process.env.HYPERNOVA_SUPPORT!, 'settings.json'), 'utf8')).claudeAgents
  const on = await post('/v1/claude', { agents: true })
  assert.equal(on.status, 200, JSON.stringify(on.json))
  assert.equal(on.json.claude.agents, true)
  assert.equal(settings.claudeAgents, true)
  assert.equal(saved(), true)
  assert.ok(feed.some((m) => m.t === 'claude' && m.claude.agents === true))
  // The registry's next look takes the job, no restart.
  job('agents-route-1', 'rout0001')
  hub.pollRegistry()
  assert.equal(hub.agentJobOf('agents-route-1'), 'rout0001')
  const off = await post('/v1/claude', { agents: false })
  assert.equal(off.json.claude.agents, false)
  assert.equal(saved(), false)
  assert.ok(feed.some((m) => m.t === 'claude' && m.claude.agents === false))
  settings.claudeAgents = true
})

test('setting on: a claude agents session is live, with the job id claude attach takes', () => {
  settings.claudeAgents = true
  const pid = job('agents-on-1', 'bbbb0001')
  hub.pollRegistry()
  assert.equal(hub.getSession('agents-on-1')?.pid, pid)
  assert.equal(hub.agentJobOf('agents-on-1'), 'bbbb0001')
  // Idle with its process running: the phone offers Stop.
  assert.equal(hub.listSessions().find((s) => s.id === 'agents-on-1')?.agentRunning, true)
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
  // Done, no process: nothing for claude stop, so the phone doesn't offer it.
  assert.equal(hub.listSessions().find((s) => s.id === 'agents-forget-1')?.agentRunning, false)
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

test('a question form of several questions: its steps on the menu, Back/Next and a jump press ← and → there', async () => {
  settings.claudeAgents = true
  writeFileSync(join(process.env.FAKE_AGENTS_DIR!, 'form-screen.txt'), readFileSync(join(import.meta.dirname, 'fixtures', 'question-screens', 'two-questions.txt')))
  job('agents-form-1', 'form0000', 'busy')
  hub.pollRegistry()
  const tid = await attachAgentsTerm('agents-form-1', scratch, 'form0000', 1500)
  assert.ok(tid)
  scanTerms()
  await settleMenus('agents-form-1', (m) => m !== null)
  const menu = hub.getSession('agents-form-1')!.menu!
  assert.deepEqual(menu.steps, { labels: ['Versioning', 'First version'], answered: [false, false] })
  const keys = join(process.env.FAKE_AGENTS_DIR!, 'keys-form0000')
  const pressed = async (n: number) => {
    const t0 = Date.now()
    while (!(existsSync(keys) && readFileSync(keys, 'utf8').length >= n) && Date.now() - t0 < 3000) await new Promise((ok) => setTimeout(ok, 50))
    return readFileSync(keys, 'utf8')
  }
  // Next: one →.
  assert.deepEqual(await answerMenu(tid!, menu.id, { move: 1 }), { ok: true })
  assert.equal(await pressed(3), '\x1b[C')
  assert.equal(hub.getSession('agents-form-1')?.menu, null, 'off the session until the next look')
  await settleMenus('agents-form-1', (m) => m !== null)
  // Back: one ←.
  assert.deepEqual(await answerMenu(tid!, menu.id, { move: -1 }), { ok: true })
  assert.equal(await pressed(6), '\x1b[C\x1b[D')
  await settleMenus('agents-form-1', (m) => m !== null)
  // Straight to Submit (step 2 of two questions): ← twice (to the first, wherever it was), → twice.
  assert.deepEqual(await answerMenu(tid!, menu.id, { step: 2 }), { ok: true })
  assert.equal(await pressed(18), '\x1b[C\x1b[D' + '\x1b[D\x1b[D\x1b[C\x1b[C')
  await settleMenus('agents-form-1', (m) => m !== null)
  // Past Submit, or on a menu with no steps: refused, nothing pressed.
  assert.deepEqual(await answerMenu(tid!, menu.id, { step: 3 }), { ok: false, reason: 'menu_moved' })
  assert.equal(readFileSync(keys, 'utf8').length, 18)
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

const hooks = await import('../src/hooks.ts')
/**
 * Session [sid] asks to run [command] (a subagent of it when [agent] is given) and nobody answers
 * on the phone for 10 minutes: its PermissionRequest hook hands the ask back to Claude Code.
 */
async function handedBack(sid: string, command: string, agent?: string): Promise<void> {
  const res = { on() {}, writableEnded: false }
  let out: Promise<object>
  mock.timers.enable({ apis: ['setTimeout'] })
  try {
    out = hooks.handleHook({ session_id: sid, cwd: scratch, hook_event_name: 'PermissionRequest', tool_name: 'Bash', tool_input: { command }, ...(agent ? { agent_id: agent } : {}) }, {} as never, res as never)
    mock.timers.tick(hub.AWAY_MS)
  } finally {
    mock.timers.reset()
  }
  assert.deepEqual(await out, {}, 'no decision: Claude Code asks in its own dialog')
}
const handBackNotices = (sid: string) => hub.eventsFor(sid).filter((e) => e.kind === 'notice' && Object.values(hub.HANDED_BACK).includes(e.text))
const until = async (ok: () => boolean, ms = 6000): Promise<void> => {
  const t0 = Date.now()
  while (!ok() && Date.now() - t0 < ms) await new Promise((r) => setTimeout(r, 100))
}

test('an ask handed back on a claude agents job with no terminal: claude attach at once, its dialog a card, one notice for every ask, gone once answered', async () => {
  settings.claudeAgents = true
  // A subagent's ask, as Claude Code shows it in its session's terminal.
  writeFileSync(join(process.env.FAKE_AGENTS_DIR!, 'perm-screen.txt'), readFileSync(join(import.meta.dirname, 'fixtures', 'permission-screens', 'subagent-ask.txt')))
  job('agents-hb-1', 'hb000001', 'busy')
  hub.pollRegistry()
  assert.equal(termOf('agents-hb-1'), null, 'nothing attached: nobody reads its dialogs')
  const feed: any[] = []
  hub.addClient({ send: (s: string) => feed.push(JSON.parse(s)) })
  await handedBack('agents-hb-1', 'ls /media/cubxi/docker/projects/hypernova/daemon/test', 'agent-1')
  await settleMenus('agents-hb-1', (m: any) => !!m?.permission)
  const s = hub.getSession('agents-hb-1')!
  assert.ok(termOf('agents-hb-1'), 'a claude attach holder')
  assert.equal(attaches('hb000001'), 1)
  assert.equal(s.menu?.permission?.tool, 'Bash command · from the general-purpose agent')
  assert.equal(s.menu?.permission?.command, 'ls /media/cubxi/docker/projects/hypernova/daemon/test')
  assert.equal(s.state, 'needs_you')
  // One notice, and with the card up it says the answer is below, not "open the terminal".
  let notes = handBackNotices('agents-hb-1')
  assert.equal(notes.length, 1)
  assert.equal(notes[0]!.kind === 'notice' && notes[0]!.text, hub.HANDED_BACK.card)
  const noticeId = notes[0]!.id
  // Two more asks go back (the main agent's, another agent's): the same holder, still one notice.
  await handedBack('agents-hb-1', 'npm test')
  await handedBack('agents-hb-1', 'git status', 'agent-2')
  await settleMenus('agents-hb-1', (m: any) => !!m?.permission)
  assert.equal(attaches('hb000001'), 1, 'one attach for every ask')
  notes = handBackNotices('agents-hb-1')
  assert.deepEqual(notes.map((e) => e.id), [noticeId])
  assert.equal(notes[0]!.kind === 'notice' && notes[0]!.text, hub.HANDED_BACK.card)
  // Allow on the card: Enter in the dialog, which closes; then the notice goes.
  const menuId = hub.getSession('agents-hb-1')!.menu!.id
  const r = await post('/v1/sessions/agents-hb-1/menu', { menuId, choice: 0 })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  const keys = join(process.env.FAKE_AGENTS_DIR!, 'keys-hb000001-1')
  await until(() => existsSync(keys) && readFileSync(keys, 'utf8') === '\n', 3000)
  assert.equal(readFileSync(keys, 'utf8'), '\n', 'Enter, nothing else')
  const t0 = Date.now()
  while (handBackNotices('agents-hb-1').length && Date.now() - t0 < 6000) {
    await watchMenus()
    await new Promise((ok) => setTimeout(ok, 200))
  }
  assert.equal(handBackNotices('agents-hb-1').length, 0)
  assert.ok(feed.some((m) => m.t === 'event.removed' && m.sessionId === 'agents-hb-1' && m.id === noticeId), 'clients told it went')
})

test('a restart handed a held ask back: a job waiting with no ask held gets claude attach, its dialog a card; not one still held, nor one at work', async () => {
  settings.claudeAgents = true
  writeFileSync(join(process.env.FAKE_AGENTS_DIR!, 'perm-screen.txt'), readFileSync(join(import.meta.dirname, 'fixtures', 'permission-screens', 'subagent-ask.txt')))
  // Claude Code's own words after the hook's connection dropped (2.1.316, seen against a restarted daemon).
  job('agents-rs-1', 'hbrs0001', 'waiting')
  jobState('hbrs0001', 'PermissionRequest hook failed: socket hang up · approve Bash: npm test')
  job('agents-rs-2', 'hbrs0002', 'busy')
  job('agents-rs-3', 'hbrs0003', 'waiting')
  jobState('hbrs0003', 'approve Bash: git status')
  hub.applyAgentList([...listed.filter((j) => !j.sessionId.startsWith('agents-rs')), { id: 'hbrs0001', sessionId: 'agents-rs-1', cwd: scratch, state: 'blocked' }, { id: 'hbrs0002', sessionId: 'agents-rs-2', cwd: scratch, state: 'working' }, { id: 'hbrs0003', sessionId: 'agents-rs-3', cwd: scratch, state: 'blocked' }])
  hub.pollRegistry()
  // What it waits for, in the job's words, not a question typed in words because of the hook's failure.
  const waiting = hub.listSessions().find((s) => s.id === 'agents-rs-1')!
  assert.equal(waiting.question ?? null, null)
  assert.equal(waiting.state, 'needs_you')
  assert.ok(!String(waiting.activity).includes('hook failed'), String(waiting.activity))
  // Its ask is still held by this daemon's hook: the card is there already.
  const held = hub.requestApproval({ sessionId: 'agents-rs-3', kind: 'tool', toolName: 'Bash', summary: 'Bash: git status', input: { command: 'git status' }, reason: null, canAlways: false, questions: null, plan: null }, null)
  attachWaitingJobs()
  await settleMenus('agents-rs-1', (m: any) => !!m?.permission)
  assert.ok(termOf('agents-rs-1'), 'a claude attach holder')
  assert.equal(attaches('hbrs0001'), 1)
  assert.ok(hub.getSession('agents-rs-1')?.menu?.permission)
  assert.equal(hub.getSession('agents-rs-1')?.state, 'needs_you')
  assert.equal(attaches('hbrs0002'), 0, 'at work: nothing to hand back')
  assert.equal(attaches('hbrs0003'), 0, 'an ask still held has its card')
  // Twice is once (a second look at start).
  attachWaitingJobs()
  await new Promise((ok) => setTimeout(ok, 300))
  assert.equal(attaches('hbrs0001'), 1)
  hub.answerApproval(hub.pendingApprovals().find((a) => a.sessionId === 'agents-rs-3')!.id, { decision: 'allow' }, 'phone')
  await held
})

test('an ask handed back while the job’s claude attach shows another session: that holder is never read or answered, a fresh attach is', async () => {
  settings.claudeAgents = true
  writeFileSync(join(process.env.FAKE_AGENTS_DIR!, 'perm-screen.txt'), readFileSync(join(import.meta.dirname, 'fixtures', 'permission-screens', 'attach-ask-rule.txt')))
  job('agents-hb-2', 'hbdrift0', 'busy', { name: 'Mine' })
  hub.pollRegistry()
  const first = await attachAgentsTerm('agents-hb-2', scratch, 'hbdrift0', 1500)
  assert.ok(first)
  scanTerms()
  await until(() => attachDrifted(first!, 'agents-hb-2'), 5000)
  assert.equal(attachDrifted(first!, 'agents-hb-2'), true)
  for (let i = 0; i < 3; i++) await watchMenus()
  assert.equal(hub.getSession('agents-hb-2')?.menu, null, 'the drifted holder’s dialog isn’t the session’s')
  await handedBack('agents-hb-2', 'python check.py')
  await until(() => !!termOf('agents-hb-2') && termOf('agents-hb-2') !== first)
  await settleMenus('agents-hb-2', (m: any) => !!m?.permission)
  assert.notEqual(termOf('agents-hb-2'), first, 'a fresh claude attach')
  assert.equal(attaches('hbdrift0'), 2)
  assert.equal(hub.getSession('agents-hb-2')?.menu?.permission?.tool, 'Bash command')
  assert.equal(readFileSync(join(process.env.FAKE_AGENTS_DIR!, 'keys-hbdrift0-1'), 'utf8'), '', 'nothing pressed in the drifted one')
  const notes = handBackNotices('agents-hb-2')
  assert.equal(notes.length, 1)
  assert.equal(notes[0]!.kind === 'notice' && notes[0]!.text, hub.HANDED_BACK.card)
})

test('an ask handed back on a job whose terminal is attached already: no second attach', async () => {
  settings.claudeAgents = true
  writeFileSync(join(process.env.FAKE_AGENTS_DIR!, 'perm-screen.txt'), readFileSync(join(import.meta.dirname, 'fixtures', 'permission-screens', 'attach-ask-rule.txt')))
  job('agents-hb-3', 'hb000003', 'busy')
  hub.pollRegistry()
  const tid = await attachAgentsTerm('agents-hb-3', scratch, 'hb000003', 1500)
  assert.ok(tid)
  scanTerms()
  await handedBack('agents-hb-3', 'python check.py')
  await settleMenus('agents-hb-3', (m: any) => !!m?.permission)
  assert.equal(termOf('agents-hb-3'), tid)
  assert.equal(attaches('hb000003'), 1)
})


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

// AG4: a job keeps the Claude Code it started on until `claude respawn`.
const { installedClaudeVersion, outdatedClaude } = await import('../src/util.ts')
const { termAlive } = await import('../src/terms.ts')

/** Claude Code updating itself: `claude` now links to versions/[v] (the same stand-in). */
function installClaude(v: string): void {
  const file = join(scratch, 'versions', v)
  if (!existsSync(file)) copyFileSync(fake, file)
  chmodSync(file, 0o755)
  const tmp = `${claudeLink}.new`
  rmSync(tmp, { force: true })
  symlinkSync(file, tmp)
  renameSync(tmp, claudeLink)
}

test('outdatedClaude: older than the installed one, by each number, never newer or unknown', () => {
  assert.deepEqual(outdatedClaude('2.1.312', '2.1.314'), { runs: '2.1.312', installed: '2.1.314' })
  assert.deepEqual(outdatedClaude('2.1.99', '2.1.314'), { runs: '2.1.99', installed: '2.1.314' }, 'by number, not text')
  assert.deepEqual(outdatedClaude('1.9.400', '2.0.0'), { runs: '1.9.400', installed: '2.0.0' })
  assert.equal(outdatedClaude('2.1.314', '2.1.314'), undefined)
  assert.equal(outdatedClaude('2.1.315', '2.1.314'), undefined, 'newer than installed: nothing to restart onto')
  assert.equal(outdatedClaude('2.10.0', '2.9.9'), undefined)
  assert.equal(outdatedClaude(undefined, '2.1.314'), undefined)
  assert.equal(outdatedClaude(42, '2.1.314'), undefined)
  assert.equal(outdatedClaude('next', '2.1.314'), undefined)
  assert.equal(outdatedClaude('2.1.312', null), undefined)
})

test('installedClaudeVersion: the version file claude links to, followed when Claude Code updates itself', () => {
  assert.equal(installedClaudeVersion(), '2.1.314')
  installClaude('2.1.320')
  assert.equal(installedClaudeVersion(), '2.1.320')
  installClaude('2.1.314')
  assert.equal(installedClaudeVersion(), '2.1.314')
})

test('a job on an older Claude Code says so, with both versions; one on the installed one, or with no version, doesn’t', async () => {
  settings.claudeAgents = true
  const oldPid = job('agents-old-1', 'old00001', 'busy', { version: '2.1.312' })
  job('agents-new-1', 'new00001', 'idle', { version: '2.1.314' })
  job('agents-nov-1', 'nov00001', 'idle')
  hub.pollRegistry()
  const view = (id: string) => hub.listSessions().find((s) => s.id === id)
  assert.deepEqual(view('agents-old-1')?.agentOutdated, { runs: '2.1.312', installed: '2.1.314' })
  assert.equal(view('agents-new-1')?.agentOutdated, undefined)
  assert.equal(view('agents-nov-1')?.agentOutdated, undefined)
  assert.ok(!('agentOutdated' in JSON.parse(JSON.stringify(view('agents-new-1')))), 'absent on the wire')
  // Claude Code updated itself: the job that was current is now behind too.
  installClaude('2.1.320')
  try {
    hub.pollRegistry()
    assert.deepEqual(view('agents-new-1')?.agentOutdated, { runs: '2.1.314', installed: '2.1.320' })
    assert.deepEqual(view('agents-old-1')?.agentOutdated, { runs: '2.1.312', installed: '2.1.320' })
  } finally {
    installClaude('2.1.314')
  }
  hub.pollRegistry()
  assert.equal(view('agents-new-1')?.agentOutdated, undefined)
  // Its process gone (stopped): no process runs an old Claude Code, so nothing to restart.
  const p = procs.find((c) => c.pid === oldPid)!
  await new Promise((ok) => {
    p.once('exit', ok)
    p.kill('SIGKILL')
  })
  hub.pollRegistry()
  assert.equal(view('agents-old-1')?.state, 'ended')
  assert.equal(view('agents-old-1')?.agentOutdated, undefined)
})

test('the respawn route: the attach holder ends first, then claude respawn <job>; the session without its notice', async () => {
  settings.claudeAgents = true
  job('agents-respawn-1', 'resp0001', 'idle', { version: '2.1.312' })
  hub.pollRegistry()
  assert.ok(hub.getSession('agents-respawn-1')?.agentOutdated)
  const tid = await attachAgentsTerm('agents-respawn-1', scratch, 'resp0001')
  assert.ok(tid)
  scanTerms()
  assert.equal(termOf('agents-respawn-1'), tid)
  const r = await post('/v1/sessions/agents-respawn-1/respawn', {})
  assert.equal(r.status, 200, JSON.stringify(r.json))
  assert.equal(r.json.session.id, 'agents-respawn-1')
  assert.equal(r.json.session.agentOutdated, undefined)
  assert.equal(termAlive(tid!, null), false, 'its claude attach holder went first')
  assert.match(readFileSync(join(process.env.FAKE_AGENTS_DIR!, 'verbs'), 'utf8'), /\nrespawn resp0001\n$/)
})

test('the respawn route: Claude Code’s words when it refuses; a session that is no claude agents job is a 409', async () => {
  settings.claudeAgents = true
  job('agents-respawn-2', 'nore0000', 'idle', { version: '2.1.312' })
  hub.pollRegistry()
  const r = await post('/v1/sessions/agents-respawn-2/respawn', {})
  assert.equal(r.status, 409)
  assert.equal(r.json.error.code, 'agents_refused')
  assert.match(r.json.error.message, /isn't running/)
  assert.ok(hub.getSession('agents-respawn-2')?.agentOutdated, 'still on the old one')
  hub.upsertSession('agents-plain-1', { cwd: scratch, origin: 'terminal' })
  const plain = await post('/v1/sessions/agents-plain-1/respawn', {})
  assert.equal(plain.status, 409)
  assert.equal(plain.json.error.code, 'not_agents')
})

/** The `claude stop|rm|respawn` lines the stand-in took since [from] of them. */
const verbsSince = (from: number): string[] => {
  const f = join(process.env.FAKE_AGENTS_DIR!, 'verbs')
  return (existsSync(f) ? readFileSync(f, 'utf8').split('\n').filter(Boolean) : []).slice(from)
}

test('the respawn-all route: claude respawn <job> for each job on an older Claude Code only, holders first; each one’s result, a refusal in Claude Code’s words', async () => {
  settings.claudeAgents = true
  job('agents-all-1', 'all00001', 'idle', { version: '2.1.312' })
  job('agents-all-2', 'all00002', 'busy', { version: '2.1.300' })
  job('agents-all-3', 'all00003', 'idle', { version: '2.1.314' })
  job('agents-all-4', 'nore0001', 'idle', { version: '2.1.312' })
  hub.pollRegistry()
  assert.equal(hub.getSession('agents-all-3')?.agentOutdated, undefined, 'on the installed one')
  const tid = await attachAgentsTerm('agents-all-1', scratch, 'all00001')
  assert.ok(tid)
  scanTerms()
  assert.equal(termOf('agents-all-1'), tid)
  const from = verbsSince(0).length
  const r = await post('/v1/jobs/respawn', {})
  assert.equal(r.status, 200, JSON.stringify(r.json))
  const mine = (r.json.results as { id: string; job: string; ok: boolean; said: string }[]).filter((x) => x.id.startsWith('agents-all-'))
  assert.deepEqual(mine.map((x) => [x.id, x.job, x.ok]).sort(), [['agents-all-1', 'all00001', true], ['agents-all-2', 'all00002', true], ['agents-all-4', 'nore0001', false]])
  assert.match(mine.find((x) => x.id === 'agents-all-4')!.said, /isn't running/)
  const verbs = verbsSince(from)
  for (const j of ['all00001', 'all00002', 'nore0001']) assert.ok(verbs.includes(`respawn ${j}`), JSON.stringify(verbs))
  assert.ok(!verbs.some((v) => v.includes('all00003') || v.includes('--all')), 'never the current one, never --all')
  assert.equal(termAlive(tid!, null), false, 'its claude attach holder went first')
  assert.equal(hub.getSession('agents-all-1')?.agentOutdated, undefined)
  assert.equal(hub.getSession('agents-all-2')?.agentOutdated, undefined)
  assert.ok(hub.getSession('agents-all-4')?.agentOutdated, 'refused: still on the old one')
  // `ids`: only those of them; one on the installed Claude Code is passed over all the same.
  const from2 = verbsSince(0).length
  const only = await post('/v1/jobs/respawn', { ids: ['agents-all-3', 'agents-all-4'] })
  assert.equal(only.status, 200)
  assert.deepEqual(only.json.results.map((x: { id: string }) => x.id), ['agents-all-4'])
  assert.deepEqual(verbsSince(from2), ['respawn nore0001'])
  // Sessions not run in claude agents: nothing to restart.
  settings.claudeAgents = false
  try {
    const off = await post('/v1/jobs/respawn', {})
    assert.equal(off.status, 409)
    assert.equal(off.json.error.code, 'not_agents')
  } finally {
    settings.claudeAgents = true
  }
})

// ── Effort (docs/PROTOCOL.md, "A session's effort") ──────────────────────

/** The lines the stand-in took in job [jobId]'s input box. */
const typed = (jobId: string): string[] => {
  const f = join(process.env.FAKE_AGENTS_DIR!, `typed-${jobId}`)
  return existsSync(f) ? readFileSync(f, 'utf8').split('\n').filter(Boolean) : []
}

test('createAgentsSession with an effort: `claude --bg --effort`, and the session says it; without one, none (Claude Code’s default)', async () => {
  settings.claudeAgents = true
  const bgArgs = () => readFileSync(join(process.env.FAKE_AGENTS_DIR!, 'bg-args'), 'utf8').trim()
  const sid = await createAgentsSession({ cwd: scratch, model: 'opus', effort: 'xhigh', prompt: 'Think hard about the parser' })
  assert.match(bgArgs(), /^--bg --model opus --effort xhigh -- Think hard about the parser$/)
  assert.equal(hub.listSessions().find((s) => s.id === sid)?.effort, 'xhigh')
  await createAgentsSession({ cwd: scratch, prompt: 'Plain one' })
  assert.equal(bgArgs(), '--bg -- Plain one')
})

test('a new session with an effort Claude Code doesn’t have is refused before anything starts', async () => {
  settings.claudeAgents = true
  const before = hub.listSessions().length
  const bad = await post('/v1/sessions', { projectPath: scratch, prompt: 'Too much', effort: 'ultracode' })
  assert.equal(bad.status, 400)
  assert.equal(bad.json.error.code, 'unknown_effort')
  assert.equal(hub.listSessions().length, before)
})

test('createSharedSession: --effort among a shared terminal’s arguments, kept with the session', async () => {
  const id = createSharedSession({ cwd: scratch, effort: 'low', prompt: 'Quick one' })
  const dir = join(process.env.HYPERNOVA_SUPPORT!, 'terms')
  let args = null as string[] | null
  const t0 = Date.now()
  while (!args && Date.now() - t0 < 5000) {
    for (const f of existsSync(dir) ? readdirSync(dir) : []) {
      if (!f.endsWith('.json')) continue
      try {
        const meta = JSON.parse(readFileSync(join(dir, f), 'utf8'))
        if (meta.sessionId === id) args = meta.args
      } catch {}
    }
    if (!args) await new Promise((ok) => setTimeout(ok, 100))
  }
  assert.ok(args, 'its holder')
  const at = args!.indexOf('--effort')
  assert.ok(at > 0 && args![at + 1] === 'low', JSON.stringify(args))
  assert.ok(at < args!.indexOf('--'), 'before the prompt')
  assert.equal(hub.getSession(id)?.effort, 'low')
})

/** The arguments of session [id]'s shared terminal holder, once it has one. */
async function holderArgs(id: string): Promise<string[] | null> {
  const dir = join(process.env.HYPERNOVA_SUPPORT!, 'terms')
  const t0 = Date.now()
  while (Date.now() - t0 < 5000) {
    for (const f of existsSync(dir) ? readdirSync(dir) : []) {
      if (!f.endsWith('.json')) continue
      try {
        const meta = JSON.parse(readFileSync(join(dir, f), 'utf8'))
        if (meta.sessionId === id) return meta.args
      } catch {}
    }
    await new Promise((ok) => setTimeout(ok, 100))
  }
  return null
}
const effortArg = (args: string[] | null): string | null => {
  const at = args?.indexOf('--effort') ?? -1
  return at >= 0 ? args![at + 1]! : null
}

test('createSharedSession resuming: the effort it last ran at again, not the default; none known, none; not on another provider', async () => {
  const { editProvider, saveManaged } = await import('../src/store.ts')
  // What the status line last said (or the phone set) wins over what it started with.
  saveManaged({ id: 'resume-effort-1', cwd: scratch, providerId: 'anthropic', model: null, permissionMode: 'default', createdAt: Date.now(), engine: 'term', effort: 'low' })
  hub.upsertSession('resume-effort-1', { cwd: scratch, origin: 'hypernova', state: 'ended', effort: 'max' })
  createSharedSession({ cwd: scratch, resume: 'resume-effort-1', prompt: 'Go on' })
  const a1 = await holderArgs('resume-effort-1')
  assert.equal(effortArg(a1), 'max', JSON.stringify(a1))
  assert.ok(a1!.indexOf('--effort') < a1!.indexOf('--'), 'before the prompt')
  // Only what it was started with: that one.
  saveManaged({ id: 'resume-effort-2', cwd: scratch, providerId: 'anthropic', model: null, permissionMode: 'default', createdAt: Date.now(), engine: 'term', effort: 'medium' })
  hub.upsertSession('resume-effort-2', { cwd: scratch, origin: 'hypernova', state: 'ended' })
  createSharedSession({ cwd: scratch, resume: 'resume-effort-2', prompt: 'Go on' })
  assert.equal(effortArg(await holderArgs('resume-effort-2')), 'medium')
  // None known: nothing passed, Claude Code's own default.
  hub.upsertSession('resume-effort-3', { cwd: scratch, origin: 'terminal', state: 'ended' })
  createSharedSession({ cwd: scratch, resume: 'resume-effort-3', prompt: 'Go on' })
  const a3 = await holderArgs('resume-effort-3')
  assert.ok(a3, 'its holder')
  assert.equal(effortArg(a3), null)
  // On another provider (the phone offers no effort there): none either.
  const other = editProvider({ name: 'Gateway test', kind: 'custom', baseUrl: 'https://llm.example' }).provider
  saveManaged({ id: 'resume-effort-4', cwd: scratch, providerId: other.id, model: null, permissionMode: 'default', createdAt: Date.now(), engine: 'term' })
  hub.upsertSession('resume-effort-4', { cwd: scratch, origin: 'hypernova', state: 'ended', providerId: other.id, effort: 'high' })
  createSharedSession({ cwd: scratch, resume: 'resume-effort-4', prompt: 'Go on' })
  const a4 = await holderArgs('resume-effort-4')
  assert.ok(a4, 'its holder')
  assert.equal(effortArg(a4), null)
})

test('the effort route on a claude agents job: claude attach, then /effort typed into its box', async () => {
  settings.claudeAgents = true
  job('agents-effort-1', 'eff00000', 'idle', { name: 'Mine' })
  hub.pollRegistry()
  assert.equal(termOf('agents-effort-1'), null)
  const r = await post('/v1/sessions/agents-effort-1/effort', { effort: 'max' })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  assert.equal(r.json.effort, 'max')
  assert.equal(r.json.session.effort, 'max')
  assert.equal(r.json.applies, 'now')
  assert.deepEqual(typed('eff00000'), ['/effort max'])
  // A level Claude Code doesn't have: nothing typed.
  const bad = await post('/v1/sessions/agents-effort-1/effort', { effort: 'extreme' })
  assert.equal(bad.status, 400)
  assert.equal(bad.json.error.code, 'unknown_effort')
  assert.deepEqual(typed('eff00000'), ['/effort max'])
})

test('the effort route never types into a claude attach that shows another session', async () => {
  settings.claudeAgents = true
  job('agents-effort-2', 'effstuk0', 'idle', { name: 'Mine' })
  hub.pollRegistry()
  const r = await post('/v1/sessions/agents-effort-2/effort', { effort: 'high' })
  assert.equal(r.status, 409, JSON.stringify(r.json))
  assert.equal(r.json.error.code, 'busy')
  assert.equal(r.json.error.reason, 'terminal_busy')
  assert.deepEqual(typed('effstuk0'), [])
  assert.equal(hub.getSession('agents-effort-2')?.effort ?? null, null)
})

test('the status line’s effort is the session’s, unless the phone set one after it was written', () => {
  settings.claudeAgents = true
  job('agents-effort-3', 'eff00003', 'idle')
  hub.pollRegistry()
  hub.noteStatusEffort('agents-effort-3', 'high', Date.now())
  assert.equal(hub.getSession('agents-effort-3')?.effort, 'high')
  // Set from the phone just now: an older line doesn't take it back, a newer one has the last word.
  hub.upsertSession('agents-effort-3', { effort: 'low' })
  hub.noteEffortSet('agents-effort-3', 2000)
  hub.noteStatusEffort('agents-effort-3', 'high', 1000)
  assert.equal(hub.getSession('agents-effort-3')?.effort, 'low')
  hub.noteStatusEffort('agents-effort-3', 'medium', 3000)
  assert.equal(hub.getSession('agents-effort-3')?.effort, 'medium')
  // Not an effort: nothing.
  hub.noteStatusEffort('agents-effort-3', { level: 'max' }, 4000)
  assert.equal(hub.getSession('agents-effort-3')?.effort, 'medium')
})

// ── Model (docs/PROTOCOL.md, "A session's model") ────────────────────────

const { useClaudeProbe, noteClaudeModels } = await import('../src/models.ts')
// Claude Code's own list, as its initialize answer gives it (no Claude Code is started).
useClaudeProbe(async () =>
  noteClaudeModels([
    { value: 'default', displayName: 'Default (recommended)', description: 'Opus 5.5', resolvedModel: 'claude-opus-5-5' },
    { value: 'sonnet', displayName: 'Sonnet', resolvedModel: 'claude-sonnet-5-5' },
  ]),
)

test('the model route on a claude agents job with no terminal: claude attach, then /model typed into its box', async () => {
  settings.claudeAgents = true
  job('agents-model-1', 'effmod00', 'idle', { name: 'Mine' })
  hub.pollRegistry()
  assert.equal(termOf('agents-model-1'), null)
  const r = await post('/v1/sessions/agents-model-1/model', { model: 'sonnet' })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  assert.deepEqual([r.json.model, r.json.applies, r.json.session.model], ['claude-sonnet-5-5', 'now', 'claude-sonnet-5-5'])
  assert.deepEqual(typed('effmod00'), ['/model sonnet'])
  assert.equal(attaches('effmod00'), 1)
  // Again: the same holder.
  const d = await post('/v1/sessions/agents-model-1/model', { model: null })
  assert.equal(d.status, 200, JSON.stringify(d.json))
  assert.deepEqual(typed('effmod00'), ['/model sonnet', '/model default'])
  assert.equal(attaches('effmod00'), 1)
})

test('the model route on a finished claude agents job attaches to it, never keeps a model for a resume that won’t use it', async () => {
  settings.claudeAgents = true
  hub.applyAgentList([...listed, { id: 'effmodd0', sessionId: 'agents-model-2', cwd: scratch, state: 'done' }])
  const r = await post('/v1/sessions/agents-model-2/model', { model: 'sonnet' })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  assert.deepEqual(typed('effmodd0'), ['/model sonnet'])
  assert.equal(r.json.session.model, 'claude-sonnet-5-5')
})

test('the model route never types into a claude attach that shows another session', async () => {
  settings.claudeAgents = true
  job('agents-model-3', 'effstuk1', 'idle', { name: 'Mine' })
  hub.pollRegistry()
  const before = hub.getSession('agents-model-3')?.model ?? null
  const r = await post('/v1/sessions/agents-model-3/model', { model: 'sonnet' })
  assert.equal(r.status, 409, JSON.stringify(r.json))
  assert.equal(r.json.error.code, 'busy')
  assert.equal(r.json.error.reason, 'terminal_busy')
  assert.deepEqual(typed('effstuk1'), [])
  assert.equal(hub.getSession('agents-model-3')?.model ?? null, before, 'no model shown that wasn’t applied')
})

test('the model route replaces a claude attach holder that drifted, typing only into the fresh one', async () => {
  settings.claudeAgents = true
  job('agents-model-4', 'effdrft0', 'idle', { name: 'Mine' })
  hub.pollRegistry()
  const first = await attachAgentsTerm('agents-model-4', scratch, 'effdrft0')
  assert.ok(first)
  scanTerms()
  const t0 = Date.now()
  while (!attachDrifted(first!, 'agents-model-4') && Date.now() - t0 < 5000) await new Promise((ok) => setTimeout(ok, 100))
  assert.equal(attachDrifted(first!, 'agents-model-4'), true)
  const r = await post('/v1/sessions/agents-model-4/model', { model: 'sonnet' })
  assert.equal(r.status, 200, JSON.stringify(r.json))
  assert.equal(attaches('effdrft0'), 2, 'a fresh claude attach')
  assert.notEqual(termOf('agents-model-4'), first)
  assert.deepEqual(typed('effdrft0'), ['/model sonnet'])
})

// ── A job that died (job-deaths.ts, IDEAS NT4) ───────────────────────────────
/** A job's state file as Claude Code leaves it when the job ends: its state and its detail. */
function jobEnded(job: string, state: string, detail: string): void {
  const dir = join(process.env.CLAUDE_CONFIG_DIR!, 'jobs', job)
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, 'state.json'), JSON.stringify({ state, detail, tempo: 'idle', updatedAt: new Date().toISOString() }))
}
function del(path: string): Promise<{ status: number; json: any }> {
  return new Promise((resolve, reject) => {
    const req = request({ host: '127.0.0.1', port: API, method: 'DELETE', path, headers: { host: `127.0.0.1:${API}`, authorization: `Bearer ${TOKEN}` } }, (res) => {
      let data = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (data += c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, json: data ? JSON.parse(data) : null }))
    })
    req.on('error', reject)
    req.end()
  })
}
/** The `{t:"job.died"}` messages every phone hears from now on. */
function deathFeed(): any[] {
  const feed: any[] = []
  hub.addClient({ send: (s) => void (JSON.parse(s).t === 'job.died' && feed.push(JSON.parse(s))) })
  return feed
}

test('a job that crashes mid-turn: one job.died with Claude Code’s reason, its session says died; later reads say nothing more', async () => {
  settings.claudeAgents = true
  const feed = deathFeed()
  const pid = job('agents-died-1', 'died0001', 'busy', { name: 'Crashy' })
  const list = [{ id: 'died0001', sessionId: 'agents-died-1', cwd: scratch, name: 'Crashy', state: 'working' }]
  hub.applyAgentList(list)
  hub.pollRegistry()
  assert.equal(hub.getSession('agents-died-1')?.state, 'working')
  // The process dies mid-turn: gone from the registry, and Claude Code marks the job failed.
  process.kill(pid, 'SIGKILL')
  await new Promise((ok) => setTimeout(ok, 100))
  rmSync(join(registry, `${pid}.json`), { force: true })
  hub.pollRegistry()
  jobEnded('died0001', 'failed', 'exit 1 — Error: ANTHROPIC_API_KEY=sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789 rejected')
  hub.applyAgentList([{ ...list[0]!, state: 'failed' }])
  assert.equal(feed.length, 1, JSON.stringify(feed))
  assert.equal(feed[0].sessionId, 'agents-died-1')
  assert.equal(feed[0].job, 'died0001')
  assert.match(feed[0].reason, /^exit 1 — Error: /)
  assert.doesNotMatch(feed[0].reason, /sk-ant-api03/, 'secrets masked')
  const s = hub.listSessions().find((x) => x.id === 'agents-died-1')
  assert.equal(s?.state, 'idle', 'on Home with the finished ones')
  assert.equal(s?.died?.reason, feed[0].reason)
  hub.applyAgentList([{ ...list[0]!, state: 'failed' }])
  hub.applyAgentList([{ ...list[0]!, state: 'failed' }])
  assert.equal(feed.length, 1, 'never twice for one death')
  // At work again (a prompt woke it): no longer died; dying once more is a new death.
  hub.applyAgentList([{ ...list[0]!, state: 'working' }])
  assert.equal(hub.listSessions().find((x) => x.id === 'agents-died-1')?.died, undefined)
  jobEnded('died0001', 'failed', 'SIGKILL (137)')
  hub.applyAgentList([{ ...list[0]!, state: 'failed' }])
  assert.equal(feed.length, 2)
  assert.equal(feed[1].reason, 'SIGKILL (137)')
})

test('a job that dies with the list still saying done: its state file’s failed counts', () => {
  settings.claudeAgents = true
  const feed = deathFeed()
  const j = { id: 'died0002', sessionId: 'agents-died-2', cwd: scratch, state: 'working' }
  hub.applyAgentList([j])
  jobEnded('died0002', 'failed', 'API error')
  hub.applyAgentList([{ ...j, state: 'done' }])
  assert.equal(feed.length, 1)
  assert.equal(feed[0].reason, 'API error')
})

test('a job that finishes normally: no job.died, and its session has no died', () => {
  settings.claudeAgents = true
  const feed = deathFeed()
  const j = { id: 'fine0001', sessionId: 'agents-fine-1', cwd: scratch, state: 'working' }
  hub.applyAgentList([j])
  jobEnded('fine0001', 'done', 'Pushed the fix')
  hub.applyAgentList([{ ...j, state: 'done' }])
  assert.deepEqual(feed, [])
  assert.equal(hub.listSessions().find((x) => x.id === 'agents-fine-1')?.died, undefined)
})

test('the owner’s Stop and Delete from the phone: no job.died, whatever the job ends as', async () => {
  settings.claudeAgents = true
  const feed = deathFeed()
  const a = { id: 'stop0001', sessionId: 'agents-stop-1', cwd: scratch, state: 'working' }
  const b = { id: 'dele0001', sessionId: 'agents-dele-1', cwd: scratch, state: 'working' }
  const c = { id: 'kill0001', sessionId: 'agents-kill-1', cwd: scratch, state: 'working' }
  hub.applyAgentList([a, b, c])
  // Stop: Claude Code says stopped.
  assert.equal((await post('/v1/sessions/agents-stop-1/stop', {})).status, 200)
  jobEnded('stop0001', 'stopped', 'stopped')
  // Stopped from the phone, though Claude Code calls the end of it failed: still his doing.
  assert.equal((await post('/v1/sessions/agents-kill-1/stop', {})).status, 200)
  jobEnded('kill0001', 'failed', 'SIGTERM (143)')
  // Delete: gone from the list.
  assert.equal((await del('/v1/sessions/agents-dele-1')).status, 200)
  hub.applyAgentList([{ ...a, state: 'stopped' }, { ...c, state: 'failed' }])
  hub.applyAgentList([{ ...a, state: 'stopped' }, { ...c, state: 'failed' }])
  assert.deepEqual(feed, [])
})
