// A session moving to another provider restarts in its shared terminal (docs/PROTOCOL.md,
// "Account"): the old claude gets SIGHUP, runs its SessionEnd hook and leaves Claude Code's
// registry, none of which ends the session, which goes on in a new claude. Two stand-in processes
// play the old and the new claude, each with a registry entry as Claude Code writes one.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawn, type ChildProcess } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync, unlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scratch = mkdtempSync(join(tmpdir(), 'hn-restart-'))
process.env.HYPERNOVA_SUPPORT = join(scratch, 'support')
process.env.HYPERNOVA_LOGS = join(scratch, 'logs')
process.env.CLAUDE_CONFIG_DIR = join(scratch, 'claude')
process.env.HOME = join(scratch, 'home')
process.env.ZDOTDIR = join(scratch, 'zdot')
for (const d of [process.env.HOME, process.env.ZDOTDIR]) mkdirSync(d, { recursive: true })
const registry = join(process.env.CLAUDE_CONFIG_DIR, 'sessions')
mkdirSync(registry, { recursive: true })

const hub = await import('../src/hub.ts')
const { handleHook } = await import('../src/hooks.ts')

const procs: ChildProcess[] = []
after(() => {
  for (const p of procs) p.kill('SIGKILL')
  rmSync(scratch, { recursive: true, force: true })
})

/** A stand-in claude for session [id], registered as Claude Code registers an interactive one. */
function claude(id: string): { pid: number; quit: () => Promise<void> } {
  const p = spawn('sleep', ['60'], { stdio: 'ignore' })
  procs.push(p)
  const pid = p.pid!
  const file = join(registry, `${pid}.json`)
  writeFileSync(file, JSON.stringify({ pid, sessionId: id, cwd: scratch, kind: 'interactive', status: 'idle', updatedAt: Date.now() }))
  return {
    pid,
    quit: () =>
      new Promise((resolve) => {
        p.once('exit', () => {
          try {
            unlinkSync(file)
          } catch {}
          resolve()
        })
        p.kill('SIGTERM')
      }),
  }
}
const sessionEnd = (id: string) => handleHook({ session_id: id, hook_event_name: 'SessionEnd', reason: 'other', cwd: scratch }, {} as any, {} as any)

test("a restart onto another provider: the old claude's end isn't the session's; the new claude's is", async () => {
  const id = 'restart-1'
  hub.upsertSession(id, { cwd: scratch, origin: 'hypernova', state: 'idle' })
  const old = claude(id)
  hub.pollRegistry()
  assert.equal(hub.getSession(id)?.pid, old.pid)
  // The restart begins (as POST /v1/sessions/{id}/provider does): the old process is on its way out.
  hub.markRestarting(id, old.pid, 60_000)
  hub.upsertSession(id, { pid: null })
  // Still in the registry while it closes: not the session's process any more.
  hub.pollRegistry()
  assert.equal(hub.getSession(id)?.pid, null)
  // SIGHUP: its SessionEnd hook, then it's gone.
  await sessionEnd(id)
  await old.quit()
  hub.pollRegistry()
  assert.notEqual(hub.getSession(id)?.state, 'ended')
  // The new claude registers: it's the session's process.
  hub.upsertSession(id, { state: 'starting' })
  const next = claude(id)
  hub.pollRegistry()
  assert.deepEqual([hub.getSession(id)?.pid, hub.getSession(id)?.state], [next.pid, 'idle'])
  // While the old one's end may still arrive, the new one quitting still ends the session.
  hub.markRestarting(id, old.pid, 15_000)
  await next.quit()
  hub.pollRegistry()
  assert.equal(hub.getSession(id)?.state, 'ended')
})

test('a restart whose terminal never closed: the old claude is the session’s again', () => {
  const id = 'restart-2'
  hub.upsertSession(id, { cwd: scratch, origin: 'hypernova', state: 'idle' })
  const c = claude(id)
  hub.markRestarting(id, c.pid, 60_000)
  hub.upsertSession(id, { pid: null })
  hub.pollRegistry()
  assert.equal(hub.getSession(id)?.pid, null)
  hub.clearRestarting(id)
  hub.pollRegistry()
  assert.equal(hub.getSession(id)?.pid, c.pid)
})

test('without a restart, SessionEnd ends the session, and so does its claude leaving the registry', async () => {
  hub.upsertSession('plain-1', { cwd: scratch, origin: 'terminal', state: 'idle' })
  await sessionEnd('plain-1')
  assert.equal(hub.getSession('plain-1')?.state, 'ended')
  hub.upsertSession('plain-2', { cwd: scratch, origin: 'terminal', state: 'idle' })
  const c = claude('plain-2')
  hub.pollRegistry()
  assert.equal(hub.getSession('plain-2')?.pid, c.pid)
  await c.quit()
  hub.pollRegistry()
  assert.equal(hub.getSession('plain-2')?.state, 'ended')
})

test("a restart whose old claude's pid wasn't known passes over its SessionEnd hook, never the new claude", async () => {
  const id = 'restart-4'
  hub.upsertSession(id, { cwd: scratch, origin: 'hypernova', state: 'idle' })
  hub.markRestarting(id, null, 60_000)
  // The old one's last word, which carries no pid: passed over.
  await sessionEnd(id)
  assert.notEqual(hub.getSession(id)?.state, 'ended')
  // The new claude registers within the minute: it's the session's process at once.
  hub.upsertSession(id, { state: 'starting' })
  const next = claude(id)
  hub.pollRegistry()
  assert.deepEqual([hub.getSession(id)?.pid, hub.getSession(id)?.state], [next.pid, 'idle'])
  // And when it quits, the session has ended.
  await next.quit()
  hub.pollRegistry()
  assert.equal(hub.getSession(id)?.state, 'ended')
})

test('a restart mark runs out by itself', () => {
  hub.markRestarting('restart-3', 123, 1)
  const t0 = Date.now()
  while (Date.now() - t0 < 5) {}
  assert.equal(hub.isRestarting('restart-3'), false)
})
